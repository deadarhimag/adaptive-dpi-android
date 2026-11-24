#ifndef ADAPTIVE_SEND_ONCE_H
#define ADAPTIVE_SEND_ONCE_H

#include <stddef.h>

#define ADAPTIVE_SEND_LIMIT ((size_t)16384)

enum adaptive_send_status {
    ADAPTIVE_SEND_PROGRESS,
    ADAPTIVE_SEND_CANCELLED,
    ADAPTIVE_SEND_RETRY,
    ADAPTIVE_SEND_INVALID,
    ADAPTIVE_SEND_FAILED
};

struct adaptive_send_result {
    enum adaptive_send_status status;
    size_t accepted;
    int system_error;
};

/*
 * Только один nonblocking send. Вызывающий удерживает эксклюзивную запись и
 * право на живой подключённый TCP FD на протяжении вызова. Метод FD не закрывает.
 * accepted — подтверждённый префикс. Повтор разрешён только для остатка после
 * PROGRESS или для всего блока после RETRY. Флаг отмены проверяется до syscall.
 */
struct adaptive_send_result adaptive_send_once(int fd, const void *data, size_t size, int cancelled);

#endif
