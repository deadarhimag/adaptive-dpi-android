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
    private val crash = File(directory, "last-crash")
    private val active = File(directory, "active-vpn")
    private val bootMark = bootCount?.toString() ?: processBootMark
    @Volatile var unreadableRecords: Long = 0
        private set

    init {
        require(maxEntries > 0 && maxBytes > 0 && maxAgeMillis > 0)
    }

    fun now() = clock()

    fun stamp(event: DiagnosticEvent) = event.copy(age = DiagnosticAge(bootMark, monotonicMillis()))

    fun read(): List<DiagnosticEvent> {
        val entries = readEvents(journal)
        val retained = bounded(entries)
        if (retained != entries) runCatching { write(retained) }
        return retained
    }

    fun readCrash(): DiagnosticEvent? {
        val entry = readEvents(crash).firstOrNull() ?: return null
        if (isFresh(entry, monotonicMillis())) return entry
        runCatching(::clearCrash)
        return null
    }
    fun readActive(): DiagnosticEvent? = readEvents(active).firstOrNull()

    fun bounded(events: List<DiagnosticEvent>): List<DiagnosticEvent> {
        val now = monotonicMillis()
        var size = diagnosticLossHeader(unreadableRecords).length
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

    /** Событие с таким временем журнал ещё хранит. Старше срока хранения событие не записывается вовсе. */
    fun retains(timeMillis: Long) = isRetained(timeMillis, now())

    private fun isRetained(timeMillis: Long, now: Long) =
        timeMillis >= 0 && (timeMillis > now || now - timeMillis < maxAgeMillis)

    fun write(events: List<DiagnosticEvent>) = writeFile(journal, bounded(events))
    fun writeCrash(event: DiagnosticEvent) = writeFile(crash, listOf(event))
    fun markActive(event: DiagnosticEvent) = writeFile(active, listOf(event))
    fun clearCrash() = Files.deleteIfExists(crash.toPath())
    fun clearActive() = Files.deleteIfExists(active.toPath())

    private fun readEvents(file: File): List<DiagnosticEvent> {
        val attributes = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        } catch (_: NoSuchFileException) {
            return emptyList()
        }
        check(attributes.isRegularFile)
        if (attributes.size() > maxBytes) {
            if (file == journal) unreadableRecords = maxOf(1L, unreadableRecords)
            return emptyList()
        }
        val (entries, damaged) = readDiagnosticLines(file, ::decode)
        if (file == journal) unreadableRecords = damaged
        return entries.take(maxEntries)
    }

    private fun writeFile(file: File, events: List<DiagnosticEvent>) {
        val bytes = buildString {
            if (file == journal) append(diagnosticLossHeader(unreadableRecords))
            events.forEach { appendLine(it.encode()) }
        }.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes)
        writeDiagnosticFile(file, bytes)
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
    }
}

private const val LOSS_PREFIX = "loss\t"

internal fun diagnosticLossHeader(count: Long) = if (count > 0) "$LOSS_PREFIX$count\n" else ""

internal fun <T> readDiagnosticLines(file: File, decode: (String) -> T): Pair<List<T>, Long> {
    var losses = 0L
    val entries = file.useLines(Charsets.UTF_8) { lines ->
        lines.mapIndexedNotNull { index, line ->
            val marker = if (index == 0 && line.startsWith(LOSS_PREFIX)) {
                line.removePrefix(LOSS_PREFIX).toLongOrNull()?.takeIf { it >= 0 }
            } else null
            if (marker != null) {
                losses = marker
                null
            } else if (line.isEmpty()) {
                null
            } else {
                runCatching { decode(line) }.onFailure { if (losses < Long.MAX_VALUE) losses++ }.getOrNull()
            }
        }.toList()
    }
    return entries to losses
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
