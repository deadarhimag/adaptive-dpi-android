package org.adaptivedpi.android.vpn.analysis

import android.os.ParcelFileDescriptor
import java.net.Socket

/**
 * Эксперимент 1.6: операции `adaptive_ops.c` над своим TCP-сокетом. Только debug.
 * Дескриптор - дубликат из [ParcelFileDescriptor.fromSocket] на один вызов: библиотека его не хранит.
 * Возврат функций - значение `>= 0` или `-errno`.
 */
object NativeSocketOps {
    init { System.loadLibrary("adaptive-hev") }

    /** Дубликат дескриптора [socket] на время [action]; закрывается сразу после вызова. */
    fun <T> withDescriptor(socket: Socket, action: (Int) -> T): T =
        checkNotNull(ParcelFileDescriptor.fromSocket(socket)) { "У сокета нет дескриптора" }.use { action(it.fd) }

    external fun notSent(fd: Int): Int

    /** `[результат, неотправлено, повторных передач, RTT мкс]`; при ошибке результат `-errno`. */
    external fun tcpInfo(fd: Int): IntArray

    external fun attachRstFilter(fd: Int): Int

    external fun detachFilter(fd: Int): Int

    /** Один байт `S` через `vmsplice` + `splice`: только проверка разрешения. */
    external fun spliceProbe(fd: Int): Int

    external fun setMd5Sig(fd: Int, address: ByteArray, enable: Boolean): Int
}
