package org.adaptivedpi.android.engine

/**
 * Эксперимент для локального стенда. Делит запись первых байтов TLS в сокет.
 * TCP-сегментацию измеряет стенд. Границы вызовов send её не подтверждают.
 */
internal class ClientHelloSplitWriter(private val send: (ByteArray, Int, Int) -> Unit) {
    private val prefix = ByteArray(6)
    private var filled = 0
    private var decided = false

    fun write(bytes: ByteArray, length: Int): Boolean {
        if (decided) {
            send(bytes, 0, length)
            return true
        }
        val count = minOf(length, prefix.size - filled)
        bytes.copyInto(prefix, filled, 0, count)
        filled += count
        if (filled < prefix.size) return false
        // Проверяем тип TLS record и первый тип handshake. Фрагмент заголовка не разбираем.
        val recordLength = (prefix[3].toInt() and 255) * 256 + (prefix[4].toInt() and 255)
        val clientHello = prefix[0] == 22.toByte() && prefix[1] == 3.toByte() &&
            recordLength in 1..16_384 && prefix[5] == 1.toByte()
        decided = true
        if (clientHello) {
            send(prefix, 0, 3)
            send(prefix, 3, filled - 3)
        } else {
            send(prefix, 0, filled)
        }
        if (count < length) send(bytes, count, length - count)
        return true
    }

    fun finish() {
        if (decided || filled == 0) return
        decided = true
        send(prefix, 0, filled)
    }
}
