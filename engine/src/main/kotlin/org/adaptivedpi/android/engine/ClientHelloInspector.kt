package org.adaptivedpi.android.engine

/** Участок транспорта (поток TCP или CRYPTO QUIC): [start], [length] в байтах. */
internal data class StreamRange(val start: Int, val length: Int)

/**
 * Перевод позиции handshake-сообщения в позицию транспорта. TLS-записи вставляют заголовки между
 * кусками сообщения, поэтому пара «смещение и длина» для имени на стыке записей неверна.
 */
internal class StreamMap private constructor(private val handshakeStarts: IntArray, private val streamStarts: IntArray, private val lengths: IntArray) {
    /** Позиция байта [handshakeOffset] сообщения в координатах транспорта. */
    fun streamOffset(handshakeOffset: Int): Int {
        require(handshakeOffset >= 0)
        var index = handshakeStarts.indexOfLast { it <= handshakeOffset }
        require(index >= 0)
        // Позиция за последним куском продолжает его: так считается конец имени, лежащего у конца записи.
        if (handshakeOffset - handshakeStarts[index] > lengths[index]) index = -1
        require(index >= 0)
        return streamStarts[index] + handshakeOffset - handshakeStarts[index]
    }

    /** Участки транспорта, занятые байтами [start, start + length) сообщения; по одному на каждый кусок. */
    fun ranges(start: Int, length: Int): List<StreamRange> {
        val end = start + length
        return buildList {
            for (index in handshakeStarts.indices) {
                val from = maxOf(start, handshakeStarts[index])
                val to = minOf(end, handshakeStarts[index] + lengths[index])
                if (to > from) add(StreamRange(streamStarts[index] + from - handshakeStarts[index], to - from))
            }
        }
    }

    override fun equals(other: Any?) = other is StreamMap && handshakeStarts.contentEquals(other.handshakeStarts) &&
        streamStarts.contentEquals(other.streamStarts) && lengths.contentEquals(other.lengths)

    override fun hashCode() = (handshakeStarts.contentHashCode() * 31 + streamStarts.contentHashCode()) * 31 + lengths.contentHashCode()

    class Builder {
        private val handshake = ArrayList<Int>()
        private val stream = ArrayList<Int>()
        private val sizes = ArrayList<Int>()
        fun piece(handshakeStart: Int, streamStart: Int, length: Int) = apply {
            handshake.add(handshakeStart)
            stream.add(streamStart)
            sizes.add(length)
        }
        fun build() = StreamMap(handshake.toIntArray(), stream.toIntArray(), sizes.toIntArray())
    }

    companion object {
        /** Сообщение лежит в потоке без вставок (QUIC: CRYPTO-кадры уже собраны в сообщение). */
        fun identity(length: Int, streamStart: Int = 0) = Builder().piece(0, streamStart, length).build()
    }
}

/** Как надёжно по ClientHello определяется имя ресурса. */
internal enum class NameIdentification {
    /** Нет имени. */
    NONE,
    /** Нет ECH: видимое имя настоящее. */
    CERTAIN,
    /** Есть расширение ECH. GREASE от настоящего ECH по байтам не отличить: имя - либо настоящее, либо общее имя фронта. */
    AMBIGUOUS_ECH,
}

/** Пассивный разбор заданного префикса. Вызывающий ограничивает время накопления. */
internal object ClientHelloInspector {
    const val MAX_PREFIX = 20 * 1024

    sealed interface Result {
        data object NeedMore : Result
        data object Unknown : Result

        /**
         * Все позиции - в координатах транспорта ([StreamMap]).
         * [serverName] - видимое имя (при ECH это внешний SNI); [sniRanges] - участки потока с байтами имени
         * (несколько, если граница записи проходит внутри имени); [sniExtensionOffset] - начало расширения `server_name`;
         * [helloLength] - длина handshake-сообщения с его 4-байтным заголовком; [recordVersion] - младший байт версии первой записи.
         */
        data class Hello(
            val serverName: String?,
            val alpn: List<String>,
            val encryptedHello: Boolean,
            val identification: NameIdentification,
            val sniRanges: List<StreamRange>,
            val sniExtensionOffset: Int?,
            val recordCount: Int,
            val helloLength: Int,
            val recordVersion: Int,
            val map: StreamMap,
        ) : Result {
            fun streamOffset(handshakeOffset: Int): Int = map.streamOffset(handshakeOffset)
        }
    }

    fun inspect(prefix: ByteArray): Result = try {
        inspectRecords(prefix)
    } catch (_: RuntimeException) {
        Result.Unknown
    }

    /**
     * Разбор сообщения ClientHello, уже собранного вызывающим (TLS без заголовков записей или CRYPTO QUIC).
     * [map] переводит позиции сообщения в позиции его транспорта.
     */
    fun inspectHandshake(message: ByteArray, map: StreamMap, recordCount: Int = 0, recordVersion: Int = 0): Result = try {
        when {
            message.isEmpty() -> Result.NeedMore
            message.size > MAX_PREFIX || message[0].toInt() != 1 -> Result.Unknown
            message.size < 4 -> Result.NeedMore
            else -> {
                val bodyLength = (unsigned(message[1]) shl 16) or u16(message, 2)
                when {
                    bodyLength !in 41..MAX_PREFIX - 4 -> Result.Unknown
                    message.size < bodyLength + 4 -> Result.NeedMore
                    else -> parseHello(message, bodyLength + 4, map, recordCount, recordVersion)
                }
            }
        }
    } catch (_: RuntimeException) {
        Result.Unknown
    }

    private fun inspectRecords(prefix: ByteArray): Result {
        if (prefix.isEmpty()) return Result.NeedMore
        if (prefix.size > MAX_PREFIX || prefix[0].toInt() != 22) return Result.Unknown

        val incomplete = if (prefix.size == MAX_PREFIX) Result.Unknown else Result.NeedMore
        val handshake = ByteArray(prefix.size)
        val pieces = StreamMap.Builder()
        var records = 0
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
            pieces.piece(size, offset, available)
            records++
            size += available
            if (size > 0 && handshake[0].toInt() != 1) return Result.Unknown
            if (size >= 4) {
                val bodyLength = (unsigned(handshake[1]) shl 16) or u16(handshake, 2)
                if (bodyLength !in 41..MAX_PREFIX - 4) return Result.Unknown
                // Разбор только после полной TLS-записи: следующей стадии нужны границы записи, а не только сообщения.
                if (size >= bodyLength + 4) {
                    return if (available < length) incomplete else {
                        val message = handshake.copyOf(bodyLength + 4)
                        inspectHandshake(message, pieces.build(), records, unsigned(prefix[2]))
                    }
                }
            }
            if (available < length) return incomplete
            offset += length
        }
        return incomplete
    }

    private fun parseHello(bytes: ByteArray, end: Int, map: StreamMap, recordCount: Int, recordVersion: Int): Result {
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
            if (!body.hasRemaining) {
                return Result.Hello(null, emptyList(), false, NameIdentification.NONE, emptyList(), null, recordCount, end, recordVersion, map)
            }

            val extensions = body.vector(body.short())
            require(!body.hasRemaining)
            var sni: String? = null
            var sniRange: StreamRange? = null
            var sniExtension: Int? = null
            var alpn = emptyList<String>()
            var ech = false
            val seen = mutableSetOf<Int>()
            while (extensions.hasRemaining) {
                val extensionStart = extensions.position
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
                            val nameLength = names.short()
                            val nameStart = names.position
                            val name = names.ascii(nameLength)
                            if (kind == 0) {
                                sniExtension = extensionStart
                                sniRange = StreamRange(nameStart, nameLength)
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
            val identification = when {
                sni == null -> NameIdentification.NONE
                ech -> NameIdentification.AMBIGUOUS_ECH
                else -> NameIdentification.CERTAIN
            }
            val ranges = sniRange?.let { map.ranges(it.start, it.length) }.orEmpty()
            Result.Hello(
                sni, alpn, ech, identification, ranges, sniExtension?.let(map::streamOffset),
                recordCount, end, recordVersion, map,
            )
        } catch (_: IllegalArgumentException) {
            Result.Unknown
        }
    }

    private class Reader(private val bytes: ByteArray, private var offset: Int, private val end: Int) {
        val hasRemaining get() = offset < end
        /** Позиция в сообщении, считая от первого байта (тип handshake). */
        val position get() = offset
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
