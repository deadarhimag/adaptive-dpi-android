package org.adaptivedpi.android.vpn.diagnostics

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.adaptivedpi.android.vpn.VpnState
import java.io.File
import java.util.UUID

data class VpnEventHistoryState(
    val entries: List<VpnJournalEvent> = emptyList(),
    val loaded: Boolean = false,
    val storageError: Boolean = false,
)

object VpnEventHistory {
    private val current = MutableStateFlow(VpnEventHistoryState())
    val state = current.asStateFlow()
    @Volatile private var writer: VpnEventWriter? = null

    @Synchronized
    fun install(context: Context) {
        if (writer != null) return
        val directory = runCatching { checkNotNull(context.noBackupFilesDir) }.getOrElse {
            current.value = VpnEventHistoryState(loaded = true, storageError = true)
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        writer = VpnEventWriter(VpnEventStore(File(directory, "vpn-events/journal")), scope, current)
    }

    internal fun record(
        type: VpnEventType,
        sessionId: UUID? = null,
        code: DiagnosticCode? = null,
        state: VpnState? = null,
        id: UUID = UUID.randomUUID(),
    ) {
        record(VpnJournalEvent.create(type, sessionId, code, state, id))
    }

    internal fun record(event: VpnJournalEvent) {
        writer?.record(event)
    }

    suspend fun clear(): Boolean = writer?.clear() ?: false
}

internal class VpnEventWriter(
    private val store: VpnEventStore,
    scope: CoroutineScope,
    private val current: MutableStateFlow<VpnEventHistoryState> = MutableStateFlow(VpnEventHistoryState()),
) {
    private sealed interface Command {
        data class Append(val event: VpnJournalEvent) : Command
        data class Clear(val completed: CompletableDeferred<Boolean>) : Command
    }

    private val commands = Channel<Command>(capacity = 256)
    val state = current.asStateFlow()
    private var diskHistoryRead = false

    init {
        scope.launch {
            val stored = runCatching(store::read)
            diskHistoryRead = stored.isSuccess
            current.value = VpnEventHistoryState(stored.getOrDefault(emptyList()), true, stored.isFailure)
            for (command in commands) {
                when (command) {
                    is Command.Append -> append(command.event)
                    is Command.Clear -> {
                        val saved = persist(emptyList(), keepOnError = true)
                        if (saved) diskHistoryRead = true
                        command.completed.complete(saved)
                    }
                }
            }
        }
    }

    fun record(event: VpnJournalEvent): Boolean = commands.trySend(Command.Append(event)).isSuccess

    suspend fun clear(): Boolean {
        val completed = CompletableDeferred<Boolean>()
        commands.send(Command.Clear(completed))
        return completed.await()
    }

    private fun append(event: VpnJournalEvent) {
        val existing = current.value.entries
        val restored = if (diskHistoryRead) emptyList() else runCatching(store::read).getOrNull()
        if (restored == null) {
            current.value = VpnEventHistoryState(store.bounded(listOf(event) + existing), loaded = true, storageError = true)
            return
        }
        diskHistoryRead = true
        val history = existing + restored
        if (history.any { it.id == event.id } || event.type == VpnEventType.InterruptedSession && event.sessionId != null &&
            history.any {
                it.sessionId == event.sessionId && it.type in completedSessions
            }
        ) {
            if (restored.isNotEmpty()) persist(store.bounded(history))
            return
        }
        persist(store.bounded(listOf(event) + history))
    }

    private fun persist(entries: List<VpnJournalEvent>, keepOnError: Boolean = false): Boolean {
        val saved = runCatching { store.write(entries) }.isSuccess
        val visible = if (!saved && keepOnError) current.value.entries else entries
        current.value = VpnEventHistoryState(visible, loaded = true, storageError = !saved)
        return saved
    }

    private companion object {
        val completedSessions = setOf(VpnEventType.Stopped, VpnEventType.SystemRevoked, VpnEventType.UnexpectedStop)
    }
}
