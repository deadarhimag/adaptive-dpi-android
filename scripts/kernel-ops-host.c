/*
 * Задача 1.6, шаг 5: те же функции adaptive_ops.c на Linux-хосте, сценарии на loopback-паре.
 * Запускается из scripts/kernel-ops-pcap.py, который параллельно захватывает пакеты на lo.
 * Пары портов и полезная нагрузка задаются seed текущего эксперимента.
 */
#define ADAPTIVE_OPS_HOST
#include "../vpn/src/main/hev/adaptive_ops.c"

#include <arpa/inet.h>
#include <netinet/ip.h>
#include <stdio.h>
#include <inttypes.h>
#include <stdlib.h>
#include <time.h>

static const unsigned char loopback[4] = { 127, 0, 0, 1 };
static uint64_t seed;
static int next_port;

static void fail(const char *what)
{
    perror(what);
    exit(2);
}

static void bind_loopback(int fd, int port)
{
    struct sockaddr_in address = {
        .sin_family = AF_INET, .sin_port = htons(port), .sin_addr.s_addr = htonl(INADDR_LOOPBACK)
    };
    if (bind(fd, (struct sockaddr *) &address, sizeof(address)))
        fail("bind");
}

static int client_socket(void)
{
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0)
        fail("socket");
    return fd;
}

static int connect_pair(int client, int server_listener, int server_port)
{
    struct sockaddr_in address = { .sin_family = AF_INET, .sin_port = htons(server_port), .sin_addr.s_addr = htonl(INADDR_LOOPBACK) };
    if (connect(client, (struct sockaddr *) &address, sizeof(address)))
        fail("connect");
    int server = accept(server_listener, NULL, NULL);
    if (server < 0)
        fail("accept");
    struct timeval timeout = { .tv_sec = 3 };
    setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(server, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    return server;
}

static void read_exact(int fd, size_t size)
{
    char buffer[4096];
    while (size > 0) {
        ssize_t count = recv(fd, buffer, size < sizeof(buffer) ? size : sizeof(buffer), 0);
        if (count <= 0)
            fail("recv");
        size -= (size_t) count;
    }
}

static void write_all(int fd, size_t size, char value)
{
    unsigned char buffer[4096];
    size_t offset = 0;
    while (offset < size) {
        size_t chunk = size - offset < sizeof(buffer) ? size - offset : sizeof(buffer);
        for (size_t i = 0; i < chunk; i++)
            buffer[i] = (unsigned char) (value ^ (seed >> (8 * ((offset + i) % 8))));
        ssize_t count = send(fd, buffer, chunk, 0);
        if (count <= 0)
            fail("send");
        offset += (size_t) count;
    }
}

static long elapsed_micros(const struct timespec *start)
{
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (now.tv_sec - start->tv_sec) * 1000000L + (now.tv_nsec - start->tv_nsec) / 1000L;
}

/* Общий каркас: before - до connect, остальное - сценарий на паре (запятые в нём допустимы). */
#define PAIR(before, ...)                                                        \
    do {                                                                         \
        int port = next_port++;                                                  \
        int client_port = next_port++;                                           \
        int listen_fd = client_socket();                                         \
        int client = client_socket();                                            \
        bind_loopback(listen_fd, port);                                           \
        bind_loopback(client, client_port);                                      \
        if (listen(listen_fd, 1))                                                 \
            fail("listen");                                                      \
        before;                                                                  \
        int server = connect_pair(client, listen_fd, port);                       \
        __VA_ARGS__;                                                             \
        close(client);                                                           \
        close(server);                                                           \
        close(listen_fd);                                                        \
    } while (0)

int main(int argc, char **argv)
{
    char *end;
    if (argc != 2 || argv[1][0] == '\0')
        return 2;
    errno = 0;
    seed = strtoumax(argv[1], &end, 16);
    if (errno || *end)
        return 2;
    next_port = 40000 + (int) (seed % 1000) * 20;
    int one = 1;

    PAIR((void) 0, {
        int info[AOPS_TCP_INFO_FIELDS];
        int result = aops_tcp_info(client, info);
        printf("tcpinfo %d result=%d notsent=%d retrans=%d rtt=%d\n", client_port, result, info[0], info[1], info[2]);
    });

    /* Гарантированный разрез: 100 байт, ожидание notsent == 0, затем 200 байт. */
    PAIR(setsockopt(client, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one)), {
        write_all(client, 100, 'a');
        struct timespec start;
        clock_gettime(CLOCK_MONOTONIC, &start);
        int left;
        while ((left = aops_not_sent(client)) > 0 && elapsed_micros(&start) < 1000000L)
            usleep(1000);
        long waited = elapsed_micros(&start);
        write_all(client, 200, 'b');
        read_exact(server, 300);
        printf("split %d notsent=%d waited_us=%ld first=100 second=200\n", client_port, left, waited);
    });

    /* Малый MSS до connect: значение в SYN клиента. */
    PAIR({
        int mss = 300;
        printf("mss_set result=%d\n", setsockopt(client, IPPROTO_TCP, TCP_MAXSEG, &mss, sizeof(mss)) ? -errno : 0);
    }, {
        write_all(server, 4000, 'm');
        read_exact(client, 4000);
        printf("mss %d requested=300\n", client_port);
    });

    /* Малый буфер приёма до connect: окно в SYN клиента. */
    PAIR({
        int size = 2048;
        printf("rcvbuf_set result=%d\n", setsockopt(client, SOL_SOCKET, SO_RCVBUF, &size, sizeof(size)) ? -errno : 0);
    }, {
        write_all(server, 20000, 'r');
        read_exact(client, 20000);
        printf("rcvbuf %d requested=2048\n", client_port);
    });

    /* TCP_FASTOPEN_CONNECT: есть ли данные в SYN, зависит от ядра, cookie и сервера. */
    PAIR({
        printf("tfo_set result=%d\n", setsockopt(client, IPPROTO_TCP, TCP_FASTOPEN_CONNECT, &one, sizeof(one)) ? -errno : 0);
    }, {
        write_all(client, 50, 't');
        read_exact(server, 50);
        printf("tfo %d bytes=50\n", client_port);
    });

    /* IP_TTL 5 на данных клиента. */
    PAIR((void) 0, {
        int ttl = 5, previous = 0;
        socklen_t length = sizeof(previous);
        getsockopt(client, IPPROTO_IP, IP_TTL, &previous, &length);
        int result = setsockopt(client, IPPROTO_IP, IP_TTL, &ttl, sizeof(ttl)) ? -errno : 0;
        write_all(client, 10, 'l');
        read_exact(server, 10);
        setsockopt(client, IPPROTO_IP, IP_TTL, &previous, sizeof(previous));
        printf("ttl %d result=%d ttl=5 previous=%d\n", client_port, result, previous);
    });

    /* MSG_OOB: сегмент с флагом URG. */
    PAIR((void) 0, {
        write_all(client, 10, 'o');
        char urgent = 'U';
        ssize_t sent = send(client, &urgent, 1, MSG_OOB);
        read_exact(server, 10);
        char received = 0;
        if (sent != 1 || recv(server, &received, 1, MSG_OOB) != 1 || received != urgent)
            fail("urgent payload");
        printf("oob %d sent=%zd\n", client_port, sent < 0 ? (ssize_t) -errno : sent);
    });

    /* RST: контроль без фильтра и с фильтром. Сервер закрывает с SO_LINGER 0. */
    for (int filter = 0; filter <= 1; filter++) {
        PAIR((void) 0, {
            int attached = filter ? aops_attach_rst_filter(client) : 0;
            struct linger linger = { .l_onoff = 1, .l_linger = 0 };
            setsockopt(server, SOL_SOCKET, SO_LINGER, &linger, sizeof(linger));
            close(server);
            server = -1;
            struct timeval timeout = { .tv_usec = 500000 };
            setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
            char byte;
            ssize_t count = recv(client, &byte, 1, 0);
            int error = count < 0 ? errno : 0;
            const char *outcome = count == 0 ? "eof" : count > 0 ? "data" : error == EAGAIN || error == EWOULDBLOCK ? "silence" : error == ECONNRESET ? "reset" : "error";
            int detached = filter ? aops_detach_filter(client) : 0;
            printf("%s %d attach=%d outcome=%s errno=%d detach=%d\n", filter ? "rst_filter" : "rst_control", client_port, attached, outcome, error, detached);
        });
    }

    /* vmsplice + splice одного байта. */
    PAIR((void) 0, {
        int sent = aops_splice_probe(client);
        char byte = 0;
        ssize_t count = sent == 1 ? recv(server, &byte, 1, 0) : 0;
        printf("splice %d sent=%d received=%s\n", client_port, sent, count == 1 && byte == 'S' ? "S" : "none");
    });

    /* TCP_MD5SIG на неподключённом сокете: установка и снятие. */
    {
        int fd = client_socket();
        int enabled = aops_md5sig(fd, loopback, 1);
        int disabled = enabled == 0 ? aops_md5sig(fd, loopback, 0) : 0;
        printf("md5 0 enable=%d disable=%d\n", enabled, disabled);
        close(fd);
    }
    return 0;
}
