package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.vpn.VpnState
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class DiagnosticJournal(
    private val directory: File,
    private val maxEntries: Int = 32,
    private val maxBytes: Int = 32 * 1024,
    private val maxAgeMillis: Long = 7 * 24 * 60 * 60 * 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val monotonicMillis: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
    bootCount: Int? = null,
) {
    private val journal = File(directory, "journal")
    private val crash = RecoverySlot("last-crash")
    private val active = RecoverySlot("active-vpn")
    private val bootMark = bootCount?.toString() ?: processBootMark
    @Volatile private var historyLosses = 0L
    var processExitIds: List<UUID> = emptyList()
        private set
    val unreadableRecords: Long get() = listOf(historyLosses, crash.losses, active.losses)
        .fold(0L) { total, loss -> total + loss.coerceAtMost(Long.MAX_VALUE - total) }

    init {
        require(maxEntries > 0 && maxBytes > 0 && maxAgeMillis > 0)
    }

    fun now() = clock()

    fun stamp(event: DiagnosticEvent) = event.copy(age = DiagnosticAge(bootMark, monotonicMillis()))

    fun read(): List<DiagnosticEvent> {
        var ids = emptyList<UUID>()
        val history = readEvents(journal) { index, line ->
            if (index > 1 || !line.startsWith(EXIT_IDS_PREFIX)) false else {
                val parsed = runCatching {
                    line.removePrefix(EXIT_IDS_PREFIX).split(',').map(UUID::fromString)
                }.getOrNull()
                if (ids.isNotEmpty() || parsed == null || parsed.size > ExitReasonReader.MAX_RECORDS) false else {
                    ids = parsed
                    true
                }
            }
        }
        processExitIds = ids
        historyLosses = history.losses
        val retained = bounded(history.entries)
        if (retained != history.entries || history.damaged) runCatching { write(retained) }
        return retained
    }

    fun readCrash(): DiagnosticEvent? = crash.read { isFresh(it, monotonicMillis()) }

    fun readActive(): DiagnosticEvent? = active.read()

    fun bounded(events: List<DiagnosticEvent>, exitIds: List<UUID> = processExitIds): List<DiagnosticEvent> {
        val now = monotonicMillis()
        var size = diagnosticLossHeader(historyLosses).length + exitIdsHeader(exitIds).length
        return events.filter { isFresh(it, now) }.distinctBy(DiagnosticEvent::id).take(maxEntries).takeWhile {
            size += it.encode().toByteArray(Charsets.UTF_8).size + 1
            size <= maxBytes
        }
    }

    // Для прежних форматов и другой загрузки достоверного возраста нет. Их ограничивает квота.
    private fun isFresh(event: DiagnosticEvent, now: Long): Boolean {
        if (event.timeMillis < 0) return false
        val age = event.age?.takeIf { it.boot == bootMark } ?: return true
        val elapsed = now - age.elapsedMillis
        return elapsed < maxAgeMillis
    }

    fun write(events: List<DiagnosticEvent>, exitIds: List<UUID> = processExitIds) {
        val header = diagnosticLossHeader(historyLosses) + exitIdsHeader(exitIds)
        writeFile(journal, header, bounded(events, exitIds))
        processExitIds = exitIds
    }

    fun writeCrash(event: DiagnosticEvent) = crash.replace(event)
    fun markActive(event: DiagnosticEvent) = active.replace(event)
    fun clearCrash() = crash.replace(null)
    fun clearActive() = active.replace(null)

    private fun exitIdsHeader(ids: List<UUID>) = if (ids.isEmpty()) "" else "$EXIT_IDS_PREFIX${ids.joinToString(",")}\n"

    private fun readEvents(
        file: File,
        metadata: (Int, String) -> Boolean = { _, _ -> false },
    ): DiagnosticLines<DiagnosticEvent> {
        val attributes = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        } catch (_: NoSuchFileException) {
            return DiagnosticLines(emptyList(), 0, false)
        }
        check(attributes.isRegularFile)
        if (attributes.size() > maxBytes) return DiagnosticLines(emptyList(), 1, true)
        return readDiagnosticLines(file, ::decode, metadata)
    }

    private fun writeFile(file: File, header: String, events: List<DiagnosticEvent>) {
        val bytes = buildString {
            append(header)
            events.forEach { appendLine(it.encode()) }
        }.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes)
        writeDiagnosticFile(file, bytes)
    }

    // Счётчик потерь живёт в том же атомарном файле, что и запись слота.
    private inner class RecoverySlot(name: String) {
        private val file = File(directory, name)
        @Volatile var losses = 0L
            private set

        @Synchronized
        fun read(accept: (DiagnosticEvent) -> Boolean = { true }): DiagnosticEvent? {
            val rows = readEvents(file)
            val entry = rows.entries.firstOrNull()?.takeIf(accept)
            losses = rows.losses
            if (rows.damaged || rows.entries.isNotEmpty() && entry == null) runCatching { write(entry) }
            return entry
        }

        @Synchronized
        fun replace(event: DiagnosticEvent?) {
            losses = readEvents(file).losses
            write(event)
        }

        private fun write(event: DiagnosticEvent?) {
            if (event == null && losses == 0L) Files.deleteIfExists(file.toPath())
            else writeFile(file, diagnosticLossHeader(losses), listOfNotNull(event))
        }
    }

    private fun DiagnosticEvent.encode(): String {
        val base = listOf(
            if (age == null) "1" else "2", id, timeMillis, code.name, appVersion, androidApi, androidVersion,
            vpn.state.name, vpn.alwaysOn.name, vpn.lockdown.name, exceptionKind?.name ?: "-", acknowledged, notified,
            frames.joinToString(",") { "${it.site.name}:${it.line}" },
        )
        val tail = age?.let { listOf(cleanup?.encode() ?: "-", it.boot, it.elapsedMillis) } ?: listOfNotNull(cleanup?.encode())
        return (base + tail).joinToString("\t")
    }

    private fun decode(line: String): DiagnosticEvent {
        val parts = line.split('\t')
        val age = when (parts[0]) {
            "1" -> { require(parts.size in 14..15); null }
            "2" -> { require(parts.size == 17); DiagnosticAge(parts[15], parts[16].toLong()) }
            else -> error("Неизвестный формат записи")
        }
        return DiagnosticEvent(
            UUID.fromString(parts[1]),
            parts[2].toLong(),
            DiagnosticCode.valueOf(parts[3]),
            DiagnosticEvent.safeVersion(parts[4]),
            parts[5].toInt(),
            DiagnosticEvent.safeVersion(parts[6]),
            VpnSnapshot(VpnState.valueOf(parts[7]), KnownFlag.valueOf(parts[8]), KnownFlag.valueOf(parts[9])),
            parts[10].takeUnless { it == "-" }?.let(ExceptionKind::valueOf),
            parts[13].split(',').asSequence().filter(String::isNotEmpty).take(DiagnosticEvent.MAX_FRAMES).map {
                SafeFrame(StackSite.valueOf(it.substringBefore(':')), it.substringAfter(':').toInt())
            }.toList(),
            parts[11].toBooleanStrict(),
            parts[12].toBooleanStrict(),
            parts.getOrNull(14)?.takeUnless { it == "-" }?.let(VpnCleanupDetails::decode),
            age,
        )
    }

    private companion object {
        val processBootMark = UUID.randomUUID().toString()
        const val EXIT_IDS_PREFIX = "exits\t"
    }
}

private const val LOSS_PREFIX = "loss\t"

internal fun diagnosticLossHeader(count: Long) = if (count > 0) "$LOSS_PREFIX$count\n" else ""

internal data class DiagnosticLines<T>(val entries: List<T>, val losses: Long, val damaged: Boolean)

internal fun <T> readDiagnosticLines(
    file: File,
    decode: (String) -> T,
    metadata: (Int, String) -> Boolean = { _, _ -> false },
): DiagnosticLines<T> {
    var losses = 0L
    var damaged = false
    val entries = file.useLines(Charsets.UTF_8) { lines ->
        lines.mapIndexedNotNull { index, line ->
            val marker = if (index == 0 && line.startsWith(LOSS_PREFIX)) {
                line.removePrefix(LOSS_PREFIX).toLongOrNull()?.takeIf { it >= 0 }
            } else null
            if (marker != null) {
                losses = marker
                null
            } else if (line.isEmpty() || metadata(index, line)) {
                null
            } else {
                runCatching { decode(line) }.onFailure {
                    damaged = true
                    if (losses < Long.MAX_VALUE) losses++
                }.getOrNull()
            }
        }.toList()
    }
    return DiagnosticLines(entries, losses, damaged)
}

internal fun writeDiagnosticFile(file: File, bytes: ByteArray) {
    val directory = checkNotNull(file.parentFile)
    Files.createDirectories(directory.toPath())
    // У каждого файла свой временный слот для атомарной замены.
    val temporary = File(directory, "${file.name}.tmp")
    try {
        FileOutputStream(temporary).use {
            it.write(bytes)
            it.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary.toPath())
    }
}
