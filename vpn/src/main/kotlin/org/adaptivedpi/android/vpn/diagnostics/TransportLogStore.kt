package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportCode
import org.adaptivedpi.android.engine.TransportDirection
import org.adaptivedpi.android.engine.TransportFailure
import org.adaptivedpi.android.engine.TransportOperation
import org.adaptivedpi.android.engine.TransportProtocol
import org.adaptivedpi.android.engine.TransportSite
import org.adaptivedpi.android.engine.TransportSubsystem
import org.adaptivedpi.android.vpn.VpnState
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32

/** Обращения к хранилищу сериализованы. Сетевые потоки его не вызывают. */
internal class TransportLogStore(
    private val directory: File,
    private val segmentBytes: Int = MAX_SEGMENT_BYTES,
    private val visibleLimit: Int = TransportJournal.VISIBLE_LIMIT,
) {
    private data class Segment(val slot: Int, val generation: Long, var count: Int = 0, var appendable: Boolean = true)
    private val segments = ArrayDeque<Segment>()
    private val recent = ArrayDeque<Pair<Int, TransportLogEntry>>()
    private val summaryFile = File(directory, "transport-summary")
    var storageError = false
        private set
    val bytes: Long get() = directory.listFiles().orEmpty().sumOf(File::length)
    val storedRecords: Int get() = segments.sumOf(Segment::count)
    val entries: List<TransportLogEntry> get() = recent.asReversed().map { it.second }

    init {
        require(segmentBytes in MAX_LINE_BYTES..MAX_SEGMENT_BYTES)
        require(visibleLimit in 1..TransportJournal.VISIBLE_LIMIT)
        check(directory.isDirectory || directory.mkdirs())
        val recovered = (0 until SEGMENTS).mapNotNull { slot ->
            val file = file(slot)
            if (!file.exists()) return@mapNotNull null
            runCatching {
                if (file.length() > segmentBytes) {
                    storageError = true
                    RandomAccessFile(file, "rw").use { it.setLength(segmentBytes.toLong()) }
                }
                val header = file.inputStream().buffered().use(::line)
                val generation = header?.takeIf { it.startsWith(HEADER) }?.removePrefix(HEADER)?.toLongOrNull()
                check(generation != null && generation in 1..Long.MAX_VALUE - 1)
                Segment(slot, generation)
            }.onFailure { storageError = true; file.delete() }.getOrNull()
        }.sortedBy(Segment::generation)
        for (segment in recovered) {
            segments.addLast(segment)
            scan(segment) { entry ->
                segment.count++
                remember(segment.slot, entry)
            }
            // При сбое процесса в конце файла может остаться строка без перевода строки.
            segment.appendable = runCatching {
                RandomAccessFile(file(segment.slot), "r").use { it.seek(it.length() - 1); it.read() == 10 }
            }.getOrDefault(false)
        }
        val temporary = File(directory, "transport-summary.tmp")
        if (temporary.exists()) temporary.delete()
        if (summaryFile.length() > MAX_LINE_BYTES) {
            storageError = true
            summaryFile.delete()
        }
    }

    fun append(entry: TransportLogEntry) {
        val data = (encode(entry) + "\n").toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_LINE_BYTES)
        val current = segments.lastOrNull()
        val target = if (current == null || !current.appendable || file(current.slot).length() + data.size > segmentBytes) {
            rotate()
        } else current
        try {
            FileOutputStream(file(target.slot), true).use { it.write(data) }
            target.count++
            remember(target.slot, entry)
        } catch (error: Exception) {
            storageError = true
            target.appendable = false
            throw error
        }
    }

    fun forEachEntry(visit: (TransportLogEntry) -> Unit) {
        for (segment in segments) scan(segment, visit)
    }

    fun saveSummary(summary: TransportSessionSummary) {
        val payload = listOf(
            summary.sessionId, summary.merged, summary.dropped, summary.lastFailure?.toEpochMilli() ?: 0,
            summary.byCategory.entries.joinToString(",") { (category, count) -> "${category.name}=$count" },
            summary.dropsBySource.entries.joinToString(",") { (source, count) -> "${source.name}=$count" },
        ).joinToString("\t")
        val data = checked(payload).toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_LINE_BYTES)
        writeDiagnosticFile(summaryFile, data)
    }

    fun readSummary(): TransportSessionSummary? {
        if (!summaryFile.exists()) return null
        return runCatching {
            require(summaryFile.length() <= MAX_LINE_BYTES)
            val parts = payload(summaryFile.readText()).split('\t')
            require(parts.size in 5..6)
            TransportSessionSummary(
                UUID.fromString(parts[0]),
                parts[4].split(',').filter(String::isNotEmpty).associate {
                    TransportCategory.valueOf(it.substringBefore('=')) to it.substringAfter('=').toLong().also { count -> require(count > 0) }
                },
                parts[1].toLong().also { require(it >= 0) }, parts[2].toLong().also { require(it >= 0) },
                parts[3].toLong().takeUnless { it == 0L }?.let(Instant::ofEpochMilli), true,
                parts.getOrNull(5).orEmpty().split(',').filter(String::isNotEmpty).associate {
                    TransportDropSource.valueOf(it.substringBefore('=')) to it.substringAfter('=').toLong().also { count -> require(count > 0) }
                },
            )
        }.onFailure { storageError = true }.getOrNull()
    }

    private fun rotate(): Segment {
        val last = segments.lastOrNull()
        val slot = if (segments.size >= SEGMENTS) segments.first().slot else (0 until SEGMENTS).first { index -> segments.none { it.slot == index } }
        val next = Segment(slot, (last?.generation ?: 0) + 1)
        // Усечение файла происходит до записи нового заголовка. Временной копии сегмента нет.
        segments.removeAll { it.slot == slot }
        recent.removeAll { it.first == slot }
        file(slot).writeText("$HEADER${next.generation}\n")
        segments.addLast(next)
        return next
    }

    private fun remember(slot: Int, entry: TransportLogEntry) {
        if (recent.size == visibleLimit) recent.removeFirst()
        recent.addLast(slot to entry)
    }

    private fun scan(segment: Segment, visit: (TransportLogEntry) -> Unit) {
        file(segment.slot).inputStream().buffered().use { stream ->
            val buffer = ByteArray(MAX_LINE_BYTES)
            line(stream, buffer)
            while (true) {
                val raw = line(stream, buffer) ?: break
                val entry = runCatching { decode(raw) }.getOrNull()
                if (entry == null) storageError = true else visit(entry)
            }
        }
    }

    private fun line(input: InputStream, buffer: ByteArray = ByteArray(MAX_LINE_BYTES)): String? {
        var length = 0
        var oversized = false
        while (true) {
            when (val byte = input.read()) {
                -1 -> { if (length > 0) storageError = true; return null }
                10 -> return if (oversized) "" else String(buffer, 0, length, Charsets.UTF_8)
                else -> if (length < buffer.size - 1) buffer[length++] = byte.toByte() else oversized = true
            }
        }
    }

    private fun file(slot: Int) = File(directory, "transport-log-$slot")

    private fun encode(entry: TransportLogEntry): String = with(entry) {
        val site = failure.site
        checked(listOf(sessionId, firstTime.toEpochMilli(), lastTime.toEpochMilli(), vpnState.name, site.subsystem.name,
            site.operation.name, site.protocol.name, site.direction.name, failure.category.name, failure.code.name,
            failure.errno ?: "-", failure.result ?: "-", repeats).joinToString("\t"))
    }

    private fun decode(line: String): TransportLogEntry {
        val parts = payload(line).split('\t')
        require(parts.size == 13)
        val first = Instant.ofEpochMilli(parts[1].toLong())
        val last = Instant.ofEpochMilli(parts[2].toLong())
        val repeats = parts[12].toLong()
        require(last >= first && repeats > 0)
        return TransportLogEntry(
            UUID.fromString(parts[0]), first, last, VpnState.valueOf(parts[3]),
            TransportFailure(
                TransportSite(TransportSubsystem.valueOf(parts[4]), TransportOperation.valueOf(parts[5]),
                    TransportProtocol.valueOf(parts[6]), TransportDirection.valueOf(parts[7])),
                TransportCategory.valueOf(parts[8]), TransportCode.valueOf(parts[9]),
                parts[10].takeUnless { it == "-" }?.toInt(), parts[11].takeUnless { it == "-" }?.toInt(),
            ), repeats,
        )
    }

    private fun checked(text: String) = "${checksum(text)}\t$text"
    private fun payload(line: String): String {
        val text = line.substringAfter('\t', "")
        require(text.isNotEmpty() && line.substringBefore('\t') == checksum(text))
        return text
    }
    private fun checksum(text: String) = CRC32().apply { update(text.toByteArray(Charsets.UTF_8)) }.value.toString(16)

    companion object {
        const val MAX_DISK_BYTES = 5L * 1024 * 1024
        const val MAX_LINE_BYTES = 4096
        private const val SEGMENTS = 5
        // Две копии итоговой сводки при атомарной замене помещаются в зарезервированные 10 КиБ.
        const val MAX_SEGMENT_BYTES = 1024 * 1024 - 2048
        private const val HEADER = "transport-v1\t"
    }
}
