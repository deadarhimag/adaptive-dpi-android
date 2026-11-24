package org.adaptivedpi.android.vpn.diagnostics

import org.adaptivedpi.android.vpn.VpnState
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

internal data class StoredVpnEvents(val entries: List<VpnJournalEvent>, val unreadable: Int)

internal class VpnEventStore(
    private val file: File,
    private val maxEntries: Int = 128,
    private val maxBytes: Int = 64 * 1024,
) {
    init {
        require(maxEntries > 0 && maxBytes > 0)
    }

    fun read(): List<VpnJournalEvent> = readStored().entries

    /** Пригодные записи и число строк, которые не удалось разобрать: частичная потеря не должна выглядеть полной историей. */
    fun readStored(): StoredVpnEvents {
        if (!file.exists()) return StoredVpnEvents(emptyList(), 0)
        check(file.isFile && file.length() <= maxBytes)
        var unreadable = 0
        val entries = file.useLines(Charsets.UTF_8) { lines ->
            lines.filter(String::isNotEmpty).mapNotNull { line ->
                runCatching { decode(line) }.onFailure { unreadable++ }.getOrNull()
            }.toList()
        }
        return StoredVpnEvents(entries.distinctBy(VpnJournalEvent::id).take(maxEntries), unreadable)
    }

    fun bounded(events: List<VpnJournalEvent>): List<VpnJournalEvent> {
        val distinct = events.distinctBy(VpnJournalEvent::id)
        val selected = mutableSetOf<UUID>()
        var size = 0
        fun keep(event: VpnJournalEvent) {
            if (selected.size >= maxEntries || event.id in selected) return
            val bytes = encode(event).toByteArray(Charsets.UTF_8).size + 1
            if (size + bytes <= maxBytes) {
                selected.add(event.id)
                size += bytes
            }
        }
        distinct.asSequence().filter { it.critical }.take(minOf(maxEntries / 4, 16)).forEach(::keep)
        distinct.forEach(::keep)
        return distinct.filter { it.id in selected }
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
