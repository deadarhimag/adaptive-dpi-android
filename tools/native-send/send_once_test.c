#include "send_once.h"

#include <assert.h>
#include <errno.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

int main(void)
{
    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    const int buffer = 4096;
    assert(setsockopt(sockets[0], SOL_SOCKET, SO_SNDBUF, &buffer, sizeof(buffer)) == 0);

    const char payload[] = "ordinary controlled fixture";
    char received[sizeof(payload)];
    struct adaptive_send_result result = adaptive_send_once(sockets[0], payload, sizeof(payload), 1);
    assert(result.status == ADAPTIVE_SEND_CANCELLED && result.accepted == 0);
    assert(recv(sockets[1], received, sizeof(received), MSG_DONTWAIT) < 0 && errno == EAGAIN);

    result = adaptive_send_once(sockets[0], payload, sizeof(payload), 0);
    assert(result.status == ADAPTIVE_SEND_PROGRESS && result.accepted == sizeof(payload));
    assert(recv(sockets[1], received, sizeof(received), MSG_WAITALL) == (ssize_t)sizeof(received));
    assert(memcmp(payload, received, sizeof(payload)) == 0);

    const char block[ADAPTIVE_SEND_LIMIT] = { 0 };
    int saw_partial = 0;
    int saw_full = 0;
    for (int i = 0; i < 64; ++i) {
        result = adaptive_send_once(sockets[0], block, sizeof(block), 0);
        if (result.status == ADAPTIVE_SEND_RETRY) {
            assert(result.accepted == 0 && (result.system_error == EAGAIN || result.system_error == EWOULDBLOCK));
            saw_full = 1;
            break;
        }
        assert(result.status == ADAPTIVE_SEND_PROGRESS && result.accepted <= sizeof(block));
        if (result.accepted < sizeof(block))
            saw_partial = 1;
    }
    assert(saw_partial && saw_full);
    assert(adaptive_send_once(sockets[0], block, sizeof(block) + 1, 0).status == ADAPTIVE_SEND_INVALID);

    assert(close(sockets[1]) == 0);
    result = adaptive_send_once(sockets[0], payload, sizeof(payload), 0);
    assert(result.status == ADAPTIVE_SEND_FAILED && result.accepted == 0 && result.system_error == EPIPE);
    assert(close(sockets[0]) == 0);
    return 0;
}
