package org.adaptivedpi.android.engine

/** Пассивный разбор заданного префикса. Вызывающий ограничивает время накопления. */
internal object ClientHelloInspector {
    const val MAX_PREFIX = 20 * 1024

    sealed interface Result {
        data object NeedMore : Result
        data object Unknown : Result
        data class Hello(val serverName: String?, val alpn: List<String>, val encryptedHello: Boolean) : Result
    }

    fun inspect(prefix: ByteArray): Result {
        if (prefix.isEmpty()) return Result.NeedMore
        if (prefix.size > MAX_PREFIX || prefix[0].toInt() != 22) return Result.Unknown

        val incomplete = if (prefix.size == MAX_PREFIX) Result.Unknown else Result.NeedMore
        val handshake = ByteArray(prefix.size)
        var size = 0
        var offset = 0
        while (offset < prefix.size) {
            if (prefix.size - offset < 5) return incomplete
            if (prefix[offset].toInt() != 22 || prefix[offset + 1].toInt() != 3) return Result.Unknown
            val length = u16(prefix, offset + 3)
            if (length !in 1..16_384) return Result.Unknown
            offset += 5
            val available = minOf(length, prefix.size - offset)
            prefix.copyInto(handshake, size, offset, offset + available)
            size += available
            if (size > 0 && handshake[0].toInt() != 1) return Result.Unknown
            if (size >= 4) {
                val bodyLength = (unsigned(handshake[1]) shl 16) or u16(handshake, 2)
                if (bodyLength !in 41..MAX_PREFIX - 4) return Result.Unknown
                // Разбор только после полной TLS-записи: следующей стадии нужны границы записи, а не только сообщения.
                if (size >= bodyLength + 4) return if (available < length) incomplete else parseHello(handshake, bodyLength + 4)
            }
            if (available < length) return incomplete
            offset += length
        }
        return incomplete
    }

    private fun parseHello(bytes: ByteArray, end: Int): Result {
        return try {
            val body = Reader(bytes, 4, end)
            val legacyVersion = body.short()
            require(legacyVersion ushr 8 == 3)
            body.skip(32)
            body.skip(body.byte().also { require(it <= 32) })
            val ciphers = body.short()
            require(ciphers >= 2 && ciphers % 2 == 0)
            body.skip(ciphers)
            body.skip(body.byte().also { require(it > 0) })
            if (!body.hasRemaining) return Result.Hello(null, emptyList(), false)

            val extensions = body.vector(body.short())
            require(!body.hasRemaining)
            var sni: String? = null
            var alpn = emptyList<String>()
            var ech = false
            val seen = mutableSetOf<Int>()
            while (extensions.hasRemaining) {
                val type = extensions.short()
                require(seen.add(type))
                val extension = extensions.vector(extensions.short())
                when (type) {
                    0 -> {
                        val names = extension.vector(extension.short())
                        require(names.hasRemaining && !extension.hasRemaining)
                        val kinds = mutableSetOf<Int>()
                        while (names.hasRemaining) {
                            val kind = names.byte()
                            require(kinds.add(kind))
                            val name = names.ascii(names.short())
                            if (kind == 0) {
                                require(name.length <= 253 && name.split('.').all {
                                    HOST_LABEL.matches(it)
                                })
                                sni = name.lowercase()
                            }
                        }
                    }
                    16 -> {
                        val protocols = extension.vector(extension.short())
                        require(protocols.hasRemaining && !extension.hasRemaining)
                        alpn = buildList {
                            while (protocols.hasRemaining) {
                                val length = protocols.byte()
                                val value = protocols.raw(length)
                                // ALPN является opaque-вектором. В диагностике оставляем ASCII.
                                if (value.all { unsigned(it) in 33..126 } && size < 16) {
                                    add(value.toString(Charsets.US_ASCII))
                                }
                            }
                        }
                    }
                    0xfe0d -> ech = true
                }
            }
            Result.Hello(sni.takeUnless { ech }, alpn.takeUnless { ech }.orEmpty(), ech)
        } catch (_: IllegalArgumentException) {
            Result.Unknown
        }
    }

    private class Reader(private val bytes: ByteArray, private var offset: Int, private val end: Int) {
        val hasRemaining get() = offset < end
        fun skip(count: Int) {
            require(count in 0..end - offset)
            offset += count
        }
        fun byte() = unsigned(bytes[offset.also { skip(1) }])
        fun short() = (byte() shl 8) or byte()
        fun vector(count: Int): Reader {
            val start = offset
            skip(count)
            return Reader(bytes, start, offset)
        }
        fun raw(count: Int): ByteArray {
            require(count > 0)
            val start = offset
            skip(count)
            return bytes.copyOfRange(start, offset)
        }
        fun ascii(count: Int): String {
            val value = raw(count)
            require(value.all { unsigned(it) in 33..126 })
            return value.toString(Charsets.US_ASCII)
        }
    }

    private val HOST_LABEL = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
    private fun unsigned(value: Byte) = value.toInt() and 255
    private fun u16(bytes: ByteArray, index: Int) = (unsigned(bytes[index]) shl 8) or unsigned(bytes[index + 1])
}
