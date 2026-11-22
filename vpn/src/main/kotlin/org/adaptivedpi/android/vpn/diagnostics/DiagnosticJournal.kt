package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.vpn.VpnState
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class DiagnosticJournal(
    private val directory: File,
    private val maxEntries: Int = 32,
    private val maxBytes: Int = 32 * 1024,
    private val maxAgeMillis: Long = 7 * 24 * 60 * 60 * 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val journal = File(directory, "journal")
    private val crash = File(directory, "last-crash")
    private val active = File(directory, "active-vpn")

    init {
        require(maxEntries > 0 && maxBytes > 0 && maxAgeMillis > 0)
    }

    fun now() = clock()

    fun read(): List<DiagnosticEvent> {
        val entries = readEvents(journal)
        val retained = bounded(entries)
        if (retained != entries) runCatching { write(retained) }
        return retained
    }

    fun readCrash(): DiagnosticEvent? {
        val entry = readEvents(crash).firstOrNull() ?: return null
        if (isFresh(entry, now())) return entry
        runCatching(::clearCrash)
        return null
    }
    fun readActive(): DiagnosticEvent? = readEvents(active).firstOrNull()

    fun bounded(events: List<DiagnosticEvent>): List<DiagnosticEvent> {
        val now = now()
        var size = 0
        return events.filter { isFresh(it, now) }.distinctBy(DiagnosticEvent::id).take(maxEntries).takeWhile {
            size += it.encode().toByteArray(Charsets.UTF_8).size + 1
            size <= maxBytes
        }
    }

    private fun isFresh(event: DiagnosticEvent, now: Long) =
        event.timeMillis in 0..now && now - event.timeMillis < maxAgeMillis

    fun write(events: List<DiagnosticEvent>) = writeFile(journal, bounded(events))
    fun writeCrash(event: DiagnosticEvent) = writeFile(crash, listOf(event))
    fun markActive(event: DiagnosticEvent) = writeFile(active, listOf(event))
    fun clearCrash() = Files.deleteIfExists(crash.toPath())
    fun clearActive() = Files.deleteIfExists(active.toPath())

    private fun readEvents(file: File): List<DiagnosticEvent> {
        if (!file.isFile || file.length() > maxBytes) return emptyList()
        return file.useLines { lines ->
            lines.take(maxEntries).mapNotNull { line -> runCatching { decode(line) }.getOrNull() }.toList()
        }
    }

    private fun writeFile(file: File, events: List<DiagnosticEvent>) {
        val bytes = events.joinToString(separator = "") { it.encode() + "\n" }.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes)
        writeDiagnosticFile(file, bytes)
    }

    private fun DiagnosticEvent.encode(): String = listOf(
        "1", id, timeMillis, code.name, appVersion, androidApi, androidVersion, vpn.state.name, vpn.alwaysOn.name, vpn.lockdown.name,
        exceptionKind?.name ?: "-", acknowledged, notified,
        frames.joinToString(",") { "${it.site.name}:${it.line}" }, cleanup?.encode(),
    ).filterNotNull().joinToString("\t")

    private fun decode(line: String): DiagnosticEvent {
        val parts = line.split('\t')
        require(parts.size in 14..15 && parts[0] == "1")
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
            parts.getOrNull(14)?.let(VpnCleanupDetails::decode),
        )
    }
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
