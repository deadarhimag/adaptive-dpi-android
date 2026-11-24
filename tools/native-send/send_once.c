#include "send_once.h"

#include <errno.h>
#include <sys/socket.h>

struct adaptive_send_result adaptive_send_once(int fd, const void *data, size_t size, int cancelled)
{
    if (fd < 0 || data == NULL || size == 0 || size > ADAPTIVE_SEND_LIMIT)
        return (struct adaptive_send_result){ ADAPTIVE_SEND_INVALID, 0, EINVAL };
    if (cancelled)
        return (struct adaptive_send_result){ ADAPTIVE_SEND_CANCELLED, 0, 0 };

    const ssize_t accepted = send(fd, data, size, MSG_DONTWAIT | MSG_NOSIGNAL);
    if (accepted > 0)
        return (struct adaptive_send_result){ ADAPTIVE_SEND_PROGRESS, (size_t)accepted, 0 };
    if (accepted == 0)
        return (struct adaptive_send_result){ ADAPTIVE_SEND_FAILED, 0, 0 };

    const int failure = errno;
    if (failure == EAGAIN || failure == EWOULDBLOCK || failure == EINTR)
        return (struct adaptive_send_result){ ADAPTIVE_SEND_RETRY, 0, failure };
    return (struct adaptive_send_result){ ADAPTIVE_SEND_FAILED, 0, failure };
}
