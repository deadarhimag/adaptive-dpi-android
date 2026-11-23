#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <time.h>

#include <lwip/init.h>
#include <hev-task-system.h>
#include <hev-socks5-misc.h>
#include "hev-config.h"
#include "hev-socks5-tunnel.h"
#include "hev-logger.h"
#include "hev-socks5-logger.h"
#include "transport-diagnostics.h"

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static jlong owner;
static bool ready;
static bool stopping;
static bool running;
static size_t final_sent, final_received;

#ifdef ADAPTIVE_TEST_HOOKS
/* Debug-only: тест удерживает тот же mutex, который нужен опросу и Stop. */
JNIEXPORT void JNICALL
Java_org_adaptivedpi_android_vpn_TransportTestHooks_withNativePollLock(JNIEnv *env, jobject instance, jobject action)
{
    jclass type = (*env)->GetObjectClass(env, action);
    if (!type)
        return;
    jmethodID run = (*env)->GetMethodID(env, type, "run", "()V");
    if (run) {
        pthread_mutex_lock(&lock);
        (*env)->CallVoidMethod(env, action, run);
        pthread_mutex_unlock(&lock);
    }
    (*env)->DeleteLocalRef(env, type);
}
#endif

/* Рабочие задачи HEV используют отдельные стеки. Отсюда нельзя вызывать JNI. */
enum { FAULT_LIMIT = 16, FAULT_FIELDS = 8, FAULT_CATEGORIES = 6 };
enum { FAULT_HEV_INIT, FAULT_INTERNAL, FAULT_TUN_IO, FAULT_CANCELLED, FAULT_BACKPRESSURE, FAULT_LIMITED };
static pthread_mutex_t fault_lock = PTHREAD_MUTEX_INITIALIZER;
static _Atomic int fault_state;
static _Atomic jlong fault_dropped[FAULT_CATEGORIES];
static _Atomic jlong fault_last_drop;
static jlong faults[FAULT_LIMIT][FAULT_FIELDS];
static size_t fault_count;

static int fault_category(int phase, int result, int err)
{
    switch (phase) {
    case ADAPTIVE_HEV_LOOP: return FAULT_INTERNAL;
    case ADAPTIVE_TUN_READ: return result == -2 ? FAULT_CANCELLED : FAULT_TUN_IO;
    case ADAPTIVE_TUN_WRITE:
        return err == EAGAIN || err == ENOBUFS || err == ENOMEM ? FAULT_BACKPRESSURE : FAULT_TUN_IO;
    case ADAPTIVE_TUN_BUFFER: return FAULT_LIMITED;
    default: return FAULT_HEV_INIT;
    }
}

void adaptive_transport_fault(int phase, int result, int native_errno)
{
    int saved_errno = errno;
    struct timespec now = { 0 };
    clock_gettime(CLOCK_REALTIME, &now);
    jlong millis = (jlong)now.tv_sec * 1000 + now.tv_nsec / 1000000;
    int category = fault_category(phase, result, native_errno);
    int state = atomic_load(&fault_state);
    if (pthread_mutex_trylock(&fault_lock) == 0) {
        for (size_t i = 0; i < fault_count; i++) {
            jlong *entry = faults[i];
            if (entry[0] == phase && entry[1] == result && entry[2] == native_errno && entry[4] == state) {
                entry[5]++;
                if (millis < entry[6]) entry[6] = millis;
                if (millis > entry[7]) entry[7] = millis;
                pthread_mutex_unlock(&fault_lock);
                errno = saved_errno;
                return;
            }
        }
        if (fault_count < FAULT_LIMIT) {
            jlong *entry = faults[fault_count++];
            entry[0] = phase;
            entry[1] = result;
            entry[2] = native_errno;
            entry[3] = category;
            entry[4] = state;
            entry[5] = 1;
            entry[6] = entry[7] = millis;
            pthread_mutex_unlock(&fault_lock);
            errno = saved_errno;
            return;
        }
        pthread_mutex_unlock(&fault_lock);
    }
    atomic_store(&fault_last_drop, millis);
    atomic_fetch_add(&fault_dropped[category], 1);
    errno = saved_errno;
}

static void log_phase(const char *phase, int result)
{
    __android_log_print(result < 0 ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
                        "AdaptiveHev", "%s: %d", phase, result);
}

JNIEXPORT jboolean JNICALL
Java_org_adaptivedpi_android_vpn_HevBridge_reserve(JNIEnv *env, jobject instance, jlong token, jint state)
{
    pthread_mutex_lock(&lock);
    bool accepted = owner == 0;
    if (accepted) {
        owner = token;
        stopping = false;
        ready = false;
        final_sent = final_received = 0;
        fault_count = 0;
        atomic_store(&fault_state, state);
        atomic_store(&fault_last_drop, 0);
        for (size_t i = 0; i < FAULT_CATEGORIES; i++) atomic_store(&fault_dropped[i], 0);
    }
    pthread_mutex_unlock(&lock);
    return accepted;
}

JNIEXPORT void JNICALL
Java_org_adaptivedpi_android_vpn_HevBridge_stop(JNIEnv *env, jobject instance, jlong token, jint state)
{
    pthread_mutex_lock(&lock);
    if (owner == token && !stopping) {
        atomic_store(&fault_state, state);
        stopping = true;
        if (ready)
            hev_socks5_tunnel_stop();
    }
    pthread_mutex_unlock(&lock);
}

JNIEXPORT void JNICALL
Java_org_adaptivedpi_android_vpn_HevBridge_setFaultState(JNIEnv *env, jobject instance, jlong token, jint state)
{
    pthread_mutex_lock(&lock);
    if (owner == token && !stopping) atomic_store(&fault_state, state);
    pthread_mutex_unlock(&lock);
}

JNIEXPORT jlongArray JNICALL
Java_org_adaptivedpi_android_vpn_HevBridge_takeFaults(JNIEnv *env, jobject instance, jlong token)
{
    jlong values[1 + FAULT_CATEGORIES + FAULT_LIMIT * FAULT_FIELDS];
    size_t length = 1 + FAULT_CATEGORIES;
    pthread_mutex_lock(&lock);
    if (owner != token || pthread_mutex_trylock(&fault_lock) != 0) {
        pthread_mutex_unlock(&lock);
        return NULL;
    }
    bool any = fault_count != 0;
    for (size_t i = 0; i < FAULT_CATEGORIES; i++) {
        values[1 + i] = atomic_exchange(&fault_dropped[i], 0);
        any = any || values[1 + i] != 0;
    }
    values[0] = atomic_load(&fault_last_drop);
    for (size_t i = 0; i < fault_count; i++)
        for (size_t field = 0; field < FAULT_FIELDS; field++) values[length++] = faults[i][field];
    fault_count = 0;
    pthread_mutex_unlock(&fault_lock);
    pthread_mutex_unlock(&lock);
    if (!any) return NULL;
    jlongArray output = (*env)->NewLongArray(env, (jsize)length);
    if (output) (*env)->SetLongArrayRegion(env, output, 0, (jsize)length, values);
    return output;
}

JNIEXPORT jboolean JNICALL
Java_org_adaptivedpi_android_vpn_HevBridge_release(JNIEnv *env, jobject instance, jlong token)
{
    pthread_mutex_lock(&lock);
    bool released = owner == token && !running;
    if (released)
        owner = 0;
    pthread_mutex_unlock(&lock);
    return released;
}

JNIEXPORT jboolean JNICALL
Java_org_adaptivedpi_android_vpn_HevBridge_isReady(JNIEnv *env, jobject instance, jlong token)
{
    pthread_mutex_lock(&lock);
    bool result = owner == token && ready && !stopping;
    pthread_mutex_unlock(&lock);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_org_adaptivedpi_android_vpn_HevBridge_counters(JNIEnv *env, jobject instance, jlong token)
{
    size_t sent = 0, received = 0;
    pthread_mutex_lock(&lock);
    if (owner == token) {
        if (ready)
            hev_socks5_tunnel_stats(&sent, NULL, &received, NULL);
        else {
            sent = final_sent;
            received = final_received;
        }
    }
    pthread_mutex_unlock(&lock);
    jlong values[] = { (jlong)sent, (jlong)received };
    jlongArray result = (*env)->NewLongArray(env, 2);
    if (result)
        (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    return result;
}

static jint run_transport(JNIEnv *env, jlong token, jbyteArray config, jint fd)
{
    jbyte *bytes = (*env)->GetByteArrayElements(env, config, NULL);
    if (!bytes) {
        adaptive_transport_fault(ADAPTIVE_HEV_CONFIG, -1, 0);
        return -1;
    }
    int result = hev_config_init_from_str((const unsigned char *)bytes,
                                         (*env)->GetArrayLength(env, config));
    (*env)->ReleaseByteArrayElements(env, config, bytes, JNI_ABORT);
    log_phase("config", result);
    if (result < 0) {
        adaptive_transport_fault(ADAPTIVE_HEV_CONFIG, result, 0);
        return result;
    }

    hev_socks5_set_connect_timeout(hev_config_get_misc_connect_timeout());
    hev_socks5_set_tcp_timeout(hev_config_get_misc_tcp_read_write_timeout());
    hev_socks5_set_udp_timeout(hev_config_get_misc_udp_read_write_timeout());
    hev_socks5_set_udp_recv_buffer_size(hev_config_get_misc_udp_recv_buffer_size());

    const char *file = hev_config_get_misc_log_file();
    int level = hev_config_get_misc_log_level();
    result = hev_logger_init(level, file);
    if (result < 0) {
        adaptive_transport_fault(ADAPTIVE_HEV_LOGGER, result, 0);
        return -1;
    }
    result = hev_socks5_logger_init(level, file);
    if (result < 0) {
        adaptive_transport_fault(ADAPTIVE_HEV_LOGGER, result, 0);
        goto logger;
    }
    result = hev_task_system_init();
    log_phase("task-system", result);
    if (result < 0) {
        adaptive_transport_fault(ADAPTIVE_HEV_TASKS, result, 0);
        goto socks_logger;
    }

    lwip_init();
    result = hev_socks5_tunnel_init(fd);
    log_phase("tun-init", result);
    if (result >= 0) {
        pthread_mutex_lock(&lock);
        bool should_run = owner == token && !stopping;
        ready = should_run;
        pthread_mutex_unlock(&lock);
        if (should_run)
            result = hev_socks5_tunnel_run();
        if (result < 0)
            adaptive_transport_fault(ADAPTIVE_HEV_LOOP, result, 0);
        log_phase("loop-exit", result);
        pthread_mutex_lock(&lock);
        hev_socks5_tunnel_stats(&final_sent, NULL, &final_received, NULL);
        ready = false;
        pthread_mutex_unlock(&lock);
        hev_socks5_tunnel_fini();
    } else {
        adaptive_transport_fault(ADAPTIVE_HEV_TUN_INIT, result, 0);
    }
    lwip_fini();
    hev_task_system_fini();
socks_logger:
    hev_socks5_logger_fini();
logger:
    hev_logger_fini();
    return result;
}

JNIEXPORT jint JNICALL
Java_org_adaptivedpi_android_vpn_HevBridge_run(JNIEnv *env, jobject instance, jlong token,
                                            jbyteArray config, jint fd)
{
    pthread_mutex_lock(&lock);
    bool accepted = owner == token && !running && !stopping;
    if (accepted)
        running = true;
    pthread_mutex_unlock(&lock);
    if (!accepted)
        return 0;

    jint result = run_transport(env, token, config, fd);
    pthread_mutex_lock(&lock);
    running = false;
    pthread_mutex_unlock(&lock);
    return result;
}
