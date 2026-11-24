package org.adaptivedpi.android.vpn.diagnostics

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.adaptivedpi.android.vpn.VpnState
import java.io.File
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

data class VpnEventHistoryState(
    val entries: List<VpnJournalEvent> = emptyList(),
    val loaded: Boolean = false,
    val storageError: Boolean = false,
    val droppedInformational: Long = 0,
    val droppedCritical: Long = 0,
    /** Строки журнала на диске, которые не удалось прочитать; отдельно от вытеснения очереди. */
    val unreadableRecords: Long = 0,
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

    private val ordinarySlots = Semaphore(256)
    private val commands = Channel<Command>(capacity = 288)
    private val droppedInformational = AtomicLong()
    private val droppedCritical = AtomicLong()
    private val unreadableRecords = AtomicLong()
    private val unpersistedCritical = mutableSetOf<UUID>()
    val state = current.asStateFlow()
    private var diskHistoryRead = false

    init {
        scope.launch {
            val stored = runCatching(store::readStored)
            diskHistoryRead = stored.isSuccess
            stored.getOrNull()?.let { unreadableRecords.addAndGet(it.unreadable.toLong()) }
            publish(VpnEventHistoryState(stored.getOrNull()?.entries.orEmpty(), true, stored.isFailure))
            for (command in commands) {
                when (command) {
                    is Command.Append -> {
                        if (!command.event.critical) ordinarySlots.release()
                        append(command.event)
                    }
                    is Command.Clear -> {
                        ordinarySlots.release()
                        val saved = persist(emptyList(), keepOnError = true)
                        if (saved) diskHistoryRead = true
                        command.completed.complete(saved)
                    }
                }
            }
        }
    }

    fun record(event: VpnJournalEvent): Boolean {
        val ordinary = !event.critical
        if (ordinary && !ordinarySlots.tryAcquire()) return reject(event)
        if (commands.trySend(Command.Append(event)).isSuccess) return true
        if (ordinary) ordinarySlots.release()
        return reject(event)
    }

    private fun reject(event: VpnJournalEvent): Boolean {
        if (event.critical) droppedCritical.incrementAndGet() else droppedInformational.incrementAndGet()
        current.update { it.withLosses() }
        return false
    }

    private fun VpnEventHistoryState.withLosses() = copy(
        droppedInformational = this@VpnEventWriter.droppedInformational.get(),
        droppedCritical = this@VpnEventWriter.droppedCritical.get(),
        unreadableRecords = this@VpnEventWriter.unreadableRecords.get(),
    )

    private fun publish(next: VpnEventHistoryState) {
        current.update { next.withLosses() }
    }

    suspend fun clear(): Boolean {
        if (!ordinarySlots.tryAcquire()) return false
        val completed = CompletableDeferred<Boolean>()
        if (!commands.trySend(Command.Clear(completed)).isSuccess) {
            ordinarySlots.release()
            return false
        }
        return completed.await()
    }

    private fun append(event: VpnJournalEvent) {
        val existing = current.value.entries
        val restored = if (diskHistoryRead) emptyList() else runCatching(store::readStored).getOrNull()?.let {
            unreadableRecords.addAndGet(it.unreadable.toLong())
            it.entries
        }
        if (restored == null) {
            if (event.critical) unpersistedCritical.add(event.id)
            val visible = store.bounded(listOf(event) + existing)
            countEvictedCritical(visible)
            publish(VpnEventHistoryState(visible, loaded = true, storageError = true))
            return
        }
        diskHistoryRead = true
        val history = existing + restored
        val previous = history.firstOrNull { it.id == event.id }
        val supersedesInterruption = event.type == VpnEventType.ProcessCrash && previous?.type == VpnEventType.InterruptedSession
        if (previous != null && !supersedesInterruption || event.type == VpnEventType.InterruptedSession && event.sessionId != null &&
            history.any {
                it.sessionId == event.sessionId && it.type in completedSessions
            }
        ) {
            if (restored.isNotEmpty()) persist(store.bounded(history))
            return
        }
        if (event.critical) unpersistedCritical.add(event.id)
        persist(store.bounded(listOf(event) + history))
    }

    private fun persist(entries: List<VpnJournalEvent>, keepOnError: Boolean = false): Boolean {
        val saved = runCatching { store.write(entries) }.isSuccess
        val visible = if (!saved && keepOnError) current.value.entries else entries
        if (!keepOnError) countEvictedCritical(visible)
        if (saved) unpersistedCritical.clear()
        publish(VpnEventHistoryState(visible, loaded = true, storageError = !saved))
        return saved
    }

    private fun countEvictedCritical(entries: List<VpnJournalEvent>) {
        if (unpersistedCritical.isEmpty()) return
        val missing = unpersistedCritical.filter { id -> entries.none { it.id == id } }
        unpersistedCritical.removeAll(missing.toSet())
        droppedCritical.addAndGet(missing.size.toLong())
    }

    private companion object {
        val completedSessions = setOf(VpnEventType.Stopped, VpnEventType.SystemRevoked, VpnEventType.UnexpectedStop)
    }
}
