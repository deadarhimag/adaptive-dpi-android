package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.vpn.VpnState
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

internal class VpnEventStore(
    private val file: File,
    private val maxEntries: Int = 128,
    private val maxBytes: Int = 64 * 1024,
) {
    init {
        require(maxEntries > 0 && maxBytes > 0)
    }

    fun read(): List<VpnJournalEvent> {
        if (!file.exists()) return emptyList()
        check(file.isFile && file.length() <= maxBytes)
        return file.useLines(Charsets.UTF_8) { lines ->
            lines.mapNotNull { runCatching { decode(it) }.getOrNull() }
                .distinctBy(VpnJournalEvent::id).take(maxEntries).toList()
        }
    }

    fun bounded(events: List<VpnJournalEvent>): List<VpnJournalEvent> {
        var size = 0
        return events.distinctBy(VpnJournalEvent::id).take(maxEntries).takeWhile {
            size += encode(it).toByteArray(Charsets.UTF_8).size + 1
            size <= maxBytes
        }
    }

    fun write(events: List<VpnJournalEvent>) {
        val bytes = bounded(events).joinToString("") { encode(it) + "\n" }.toByteArray(Charsets.UTF_8)
        writeDiagnosticFile(file, bytes)
    }

    private fun encode(event: VpnJournalEvent) = with(event) {
        listOf("1", id, time, type.name, sessionId ?: "-", code?.name ?: "-", state?.name ?: "-").joinToString("\t")
    }

    private fun decode(line: String): VpnJournalEvent {
        val parts = line.split('\t')
        require(parts.size == 7 && parts[0] == "1")
        return VpnJournalEvent(
            UUID.fromString(parts[1]),
            OffsetDateTime.parse(parts[2]),
            VpnEventType.valueOf(parts[3]),
            parts[4].takeUnless { it == "-" }?.let(UUID::fromString),
            parts[5].takeUnless { it == "-" }?.let(DiagnosticCode::valueOf),
            parts[6].takeUnless { it == "-" }?.let(VpnState::valueOf),
        )
    }
}
