/*
 * Узкая библиотека операций над своим TCP-сокетом (задача 1.6, docs/OPERATIONS.md, «Нативные функции»).
 * Собирается только в debug (ADAPTIVE_TEST_HOOKS) и на Linux-хосте для PCAP-проверки (ADAPTIVE_OPS_HOST).
 * Дескриптор арендован владельцем на один вызов: функции его не хранят и не закрывают, изменяемого
 * глобального состояния нет. Каждая функция возвращает значение >= 0 или -errno.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <netinet/in.h>
#include <linux/filter.h>
#include <linux/tcp.h>

enum { AOPS_TCP_INFO_FIELDS = 3 };

/* TCP_INFO: неотправленные байты, всего повторных передач, сглаженный RTT в микросекундах. */
static int aops_tcp_info(int fd, int out[AOPS_TCP_INFO_FIELDS])
{
    struct tcp_info info;
    socklen_t length = sizeof(info);
    memset(&info, 0, sizeof(info));
    if (getsockopt(fd, IPPROTO_TCP, TCP_INFO, &info, &length) != 0)
        return -errno;
    /* Старое ядро может вернуть укороченную структуру без tcpi_notsent_bytes. */
    if (length < offsetof(struct tcp_info, tcpi_notsent_bytes) + sizeof(info.tcpi_notsent_bytes))
        return -EMSGSIZE;
    out[0] = (int) info.tcpi_notsent_bytes;
    out[1] = (int) info.tcpi_total_retrans;
    out[2] = (int) info.tcpi_rtt;
    return 0;
}

static int aops_not_sent(int fd)
{
    int info[AOPS_TCP_INFO_FIELDS];
    int result = aops_tcp_info(fd, info);
    return result < 0 ? result : info[0];
}

/* Классический BPF на своём сокете: входящий сегмент с флагом RST отбрасывается до TCP. */
static int aops_attach_rst_filter(int fd)
{
    /* Для TCP-сокета программа видит пакет с начала TCP-заголовка: байт 13 - флаги, RST = 0x04. */
    struct sock_filter code[] = {
        BPF_STMT(BPF_LD | BPF_B | BPF_ABS, 13),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, 0x04, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, 0),
        BPF_STMT(BPF_RET | BPF_K, 0xFFFFFFFF),
    };
    struct sock_fprog program = { .len = sizeof(code) / sizeof(code[0]), .filter = code };
    if (setsockopt(fd, SOL_SOCKET, SO_ATTACH_FILTER, &program, sizeof(program)) != 0)
        return -errno;
    return 0;
}

static int aops_detach_filter(int fd)
{
    int unused = 0;
    if (setsockopt(fd, SOL_SOCKET, SO_DETACH_FILTER, &unused, sizeof(unused)) != 0)
        return -errno;
    return 0;
}

/* Проверка разрешения vmsplice + splice: один байт 'S' со своей страницы памяти в сокет. Не fake. */
static int aops_splice_probe(int fd)
{
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0)
        return -EINVAL;
    int pipes[2];
    if (pipe2(pipes, O_CLOEXEC) != 0)
        return -errno;
    int result;
    void *page = mmap(NULL, (size_t) page_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (page == MAP_FAILED) {
        result = -errno;
    } else {
        *(char *) page = 'S';
        struct iovec part = { .iov_base = page, .iov_len = 1 };
        ssize_t queued = vmsplice(pipes[1], &part, 1, 0);
        if (queued != 1) {
            result = queued < 0 ? -errno : -EIO;
        } else {
            ssize_t sent = splice(pipes[0], NULL, fd, NULL, 1, 0);
            result = sent < 0 ? -errno : (int) sent;
        }
        /* Страница, отданная в pipe, удерживается ядром по ссылке: снимать отображение можно сразу. */
        munmap(page, (size_t) page_size);
    }
    close(pipes[0]);
    close(pipes[1]);
    return result;
}

/* TCP_MD5SIG на IPv4-адрес собеседника; enable = 0 удаляет ключ. */
static int aops_md5sig(int fd, const unsigned char address[4], int enable)
{
    struct tcp_md5sig signature;
    memset(&signature, 0, sizeof(signature));
    struct sockaddr_in *peer = (struct sockaddr_in *) &signature.tcpm_addr;
    peer->sin_family = AF_INET;
    memcpy(&peer->sin_addr, address, 4);
    if (enable) {
        static const char key[] = "adaptive-dpi-md5";
        signature.tcpm_keylen = sizeof(key) - 1;
        memcpy(signature.tcpm_key, key, sizeof(key) - 1);
    }
    if (setsockopt(fd, IPPROTO_TCP, TCP_MD5SIG, &signature, sizeof(signature)) != 0)
        return -errno;
    return 0;
}

#ifndef ADAPTIVE_OPS_HOST
#include <jni.h>

JNIEXPORT jint JNICALL
Java_org_adaptivedpi_android_vpn_analysis_NativeSocketOps_notSent(JNIEnv *env, jobject self, jint fd)
{
    return aops_not_sent(fd);
}

/* [результат, неотправлено, повторных передач, RTT мкс]; при ошибке результат = -errno, остальное 0. */
JNIEXPORT jintArray JNICALL
Java_org_adaptivedpi_android_vpn_analysis_NativeSocketOps_tcpInfo(JNIEnv *env, jobject self, jint fd)
{
    int info[AOPS_TCP_INFO_FIELDS] = { 0, 0, 0 };
    jint values[AOPS_TCP_INFO_FIELDS + 1];
    values[0] = aops_tcp_info(fd, info);
    for (int index = 0; index < AOPS_TCP_INFO_FIELDS; index++)
        values[index + 1] = values[0] < 0 ? 0 : info[index];
    jintArray array = (*env)->NewIntArray(env, AOPS_TCP_INFO_FIELDS + 1);
    if (array)
        (*env)->SetIntArrayRegion(env, array, 0, AOPS_TCP_INFO_FIELDS + 1, values);
    return array;
}

JNIEXPORT jint JNICALL
Java_org_adaptivedpi_android_vpn_analysis_NativeSocketOps_attachRstFilter(JNIEnv *env, jobject self, jint fd)
{
    return aops_attach_rst_filter(fd);
}

JNIEXPORT jint JNICALL
Java_org_adaptivedpi_android_vpn_analysis_NativeSocketOps_detachFilter(JNIEnv *env, jobject self, jint fd)
{
    return aops_detach_filter(fd);
}

JNIEXPORT jint JNICALL
Java_org_adaptivedpi_android_vpn_analysis_NativeSocketOps_spliceProbe(JNIEnv *env, jobject self, jint fd)
{
    return aops_splice_probe(fd);
}

JNIEXPORT jint JNICALL
Java_org_adaptivedpi_android_vpn_analysis_NativeSocketOps_setMd5Sig(
    JNIEnv *env, jobject self, jint fd, jbyteArray address, jboolean enable)
{
    if (!address || (*env)->GetArrayLength(env, address) != 4)
        return -EINVAL;
    jbyte bytes[4];
    (*env)->GetByteArrayRegion(env, address, 0, 4, bytes);
    return aops_md5sig(fd, (const unsigned char *) bytes, enable == JNI_TRUE);
}
#endif
