#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdlib.h>

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

/* Задачи HEV исполняются на потоке JNI, запустившем цикл. */
static _Thread_local JNIEnv *fault_env;
static _Thread_local jobject fault_observer;
static _Thread_local jmethodID fault_method;

void adaptive_transport_fault(int phase, int result, int native_errno)
{
    int saved_errno = errno;
    if (fault_env && fault_method) {
        (*fault_env)->CallVoidMethod(fault_env, fault_observer, fault_method, phase, result, native_errno);
        // Диагностика не влияет на возврат и состояние рабочего цикла.
        if ((*fault_env)->ExceptionCheck(fault_env))
            (*fault_env)->ExceptionClear(fault_env);
    }
    errno = saved_errno;
}

static void log_phase(const char *phase, int result)
{
    __android_log_print(result < 0 ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
                        "AdaptiveHev", "%s: %d", phase, result);
}

JNIEXPORT jboolean JNICALL
Java_org_adaptivedpi_android_vpn_HevBridge_reserve(JNIEnv *env, jobject instance, jlong token)
{
    pthread_mutex_lock(&lock);
    bool accepted = owner == 0;
    if (accepted) {
        owner = token;
        stopping = false;
    }
    pthread_mutex_unlock(&lock);
    return accepted;
}

JNIEXPORT void JNICALL
Java_org_adaptivedpi_android_vpn_HevBridge_stop(JNIEnv *env, jobject instance, jlong token)
{
    pthread_mutex_lock(&lock);
    if (owner == token && !stopping) {
        stopping = true;
        if (ready)
            hev_socks5_tunnel_stop();
    }
    pthread_mutex_unlock(&lock);
}

JNIEXPORT void JNICALL
Java_org_adaptivedpi_android_vpn_HevBridge_release(JNIEnv *env, jobject instance, jlong token)
{
    pthread_mutex_lock(&lock);
    if (owner == token)
        owner = 0;
    pthread_mutex_unlock(&lock);
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
    if (owner == token && ready)
        hev_socks5_tunnel_stats(&sent, NULL, &received, NULL);
    pthread_mutex_unlock(&lock);
    jlong values[] = { (jlong)sent, (jlong)received };
    jlongArray result = (*env)->NewLongArray(env, 2);
    if (result)
        (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    return result;
}

static int run_tunnel(JNIEnv *env, jlong token, jbyteArray config, jint fd)
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
                                             jbyteArray config, jint fd, jobject observer)
{
    jclass type = (*env)->GetObjectClass(env, observer);
    if (type) {
        fault_method = (*env)->GetMethodID(env, type, "report", "(III)V");
        (*env)->DeleteLocalRef(env, type);
    }
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
    fault_env = env;
    fault_observer = observer;
    int result = run_tunnel(env, token, config, fd);
    fault_env = NULL;
    fault_observer = NULL;
    fault_method = NULL;
    return result;
}
