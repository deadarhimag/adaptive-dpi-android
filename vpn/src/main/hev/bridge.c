#include <jni.h>
#include <android/log.h>
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

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static jlong owner;
static bool ready;
static bool stopping;

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

JNIEXPORT jint JNICALL
Java_org_adaptivedpi_android_vpn_HevBridge_run(JNIEnv *env, jobject instance, jlong token,
                                             jbyteArray config, jint fd)
{
    jbyte *bytes = (*env)->GetByteArrayElements(env, config, NULL);
    if (!bytes)
        return -1;
    int result = hev_config_init_from_str((const unsigned char *)bytes,
                                         (*env)->GetArrayLength(env, config));
    (*env)->ReleaseByteArrayElements(env, config, bytes, JNI_ABORT);
    log_phase("config", result);
    if (result < 0)
        return result;

    hev_socks5_set_connect_timeout(hev_config_get_misc_connect_timeout());
    hev_socks5_set_tcp_timeout(hev_config_get_misc_tcp_read_write_timeout());
    hev_socks5_set_udp_timeout(hev_config_get_misc_udp_read_write_timeout());
    hev_socks5_set_udp_recv_buffer_size(hev_config_get_misc_udp_recv_buffer_size());

    const char *file = hev_config_get_misc_log_file();
    int level = hev_config_get_misc_log_level();
    if (hev_logger_init(level, file) < 0)
        return -1;
    result = hev_socks5_logger_init(level, file);
    if (result < 0)
        goto logger;
    result = hev_task_system_init();
    log_phase("task-system", result);
    if (result < 0)
        goto socks_logger;

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
        log_phase("loop-exit", result);
        pthread_mutex_lock(&lock);
        ready = false;
        pthread_mutex_unlock(&lock);
        hev_socks5_tunnel_fini();
    }
    lwip_fini();
    hev_task_system_fini();
socks_logger:
    hev_socks5_logger_fini();
logger:
    hev_logger_fini();
    return result;
}
