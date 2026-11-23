package org.adaptivedpi.android.vpn.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class DiagnosticCenter(
    private val journal: DiagnosticJournal,
    private val version: String?,
    private val api: Int,
    private val androidVersion: String?,
    private val current: MutableStateFlow<List<DiagnosticEvent>> = MutableStateFlow(emptyList()),
    private val notify: (DiagnosticEvent) -> Boolean,
) {
    val events = current.asStateFlow()
    private val collectingCrash = AtomicBoolean()
    private var historyRead = false

    init {
        current.value = readHistory().orEmpty()
    }

    val recovered = recover()

    @Synchronized
    fun record(code: DiagnosticCode, vpn: VpnSnapshot, error: Throwable? = null, cleanup: VpnCleanupDetails? = null): DiagnosticEvent {
        val entry = newEvent(code, vpn, error, cleanup = cleanup)
        store(listOf(entry) + current.value)
        notifyOnce(entry.id)
        return entry
    }

    @Synchronized
    fun persistCritical(code: DiagnosticCode, vpn: VpnSnapshot, error: Throwable?, activeId: UUID?): UUID {
        val entry = newEvent(code, vpn, error, activeId ?: UUID.randomUUID())
        if (current.value.none { it.id == entry.id }) {
            if (store(listOf(entry) + current.value) && activeId != null) clearActive(activeId)
        }
        return entry.id
    }

    @Synchronized
    fun prune() {
        val retained = journal.bounded(current.value)
        if (retained != current.value) store(retained)
    }

    @Synchronized
    fun acknowledge(id: UUID) {
        store(current.value.map { if (it.id == id && !it.acknowledged) it.copy(acknowledged = true) else it })
    }

    @Synchronized
    fun notifyOnce(id: UUID) {
        prune()
        val event = current.value.firstOrNull { it.id == id } ?: return
        if (!event.requiresNotification || event.notified || event.acknowledged) return
        val duplicate = current.value.any {
            it.id != id && it.notified && it.code == event.code && it.exceptionKind == event.exceptionKind &&
                journal.now() - it.timeMillis in 0 until ALERT_INTERVAL_MILLIS
        }
        if (duplicate || runCatching { notify(event) }.getOrDefault(false)) {
            store(current.value.map { if (it.id == id) it.copy(notified = true) else it })
        }
    }

    @Synchronized
    fun markActive(vpn: VpnSnapshot, id: UUID = UUID.randomUUID()) {
        runCatching {
            journal.markActive(newEvent(DiagnosticCode.VpnInterrupted, vpn, id = id))
        }
    }

    @Synchronized
    fun clearActive(id: UUID? = null) {
        runCatching {
            if (id == null || journal.readActive()?.id == id) journal.clearActive()
        }
    }

    fun captureCrash(vpn: VpnSnapshot, error: Throwable, activeId: UUID? = null) {
        // Отдельный слот не берёт монитор обычного журнала, который мог держать упавший поток.
        if (!collectingCrash.compareAndSet(false, true)) return
        runCatching {
            journal.writeCrash(newEvent(DiagnosticCode.AppCrash, vpn, error, activeId ?: UUID.randomUUID()))
        }
    }

    private fun recover(): List<Pair<DiagnosticEvent, UUID?>> {
        val crash = runCatching(journal::readCrash).getOrNull()
        val interrupted = runCatching(journal::readActive).getOrNull()
        if (crash == null && interrupted == null) return emptyList()
        val recovered = buildList {
            if (crash != null) add(crash)
            if (interrupted != null && crash?.vpn?.mayHaveActiveVpn != true) {
                add(interrupted.copy(timeMillis = journal.now()))
            }
        }
        // Уже прочитанные события сохраняют свои отметки, если удаление слота не удалось.
        val newEvents = recovered.filter { event ->
            val previous = current.value.firstOrNull { it.id == event.id }
            previous == null || event.code == DiagnosticCode.AppCrash && previous.code != DiagnosticCode.AppCrash
        }
        val merged = newEvents + current.value
        if (store(merged)) {
            if (crash != null && current.value.any { it.id == crash.id && it.code == DiagnosticCode.AppCrash }) {
                runCatching(journal::clearCrash)
            }
            if (interrupted != null && current.value.any { it.id == interrupted.id || it.id == crash?.id }) {
                runCatching(journal::clearActive)
            }
        }
        return newEvents.map { event ->
            val sessionId = interrupted?.id?.takeIf { it == event.id }
            event to sessionId
        }
    }

    private fun store(entries: List<DiagnosticEvent>): Boolean {
        val unread = if (historyRead) emptyList() else readHistory()
        val bounded = journal.bounded(entries + unread.orEmpty())
        current.value = bounded
        return historyRead && runCatching { journal.write(bounded) }.isSuccess
    }

    private fun readHistory() = runCatching(journal::read).onSuccess { historyRead = true }.getOrNull()

    private fun newEvent(
        code: DiagnosticCode,
        vpn: VpnSnapshot,
        error: Throwable? = null,
        id: UUID = UUID.randomUUID(),
        cleanup: VpnCleanupDetails? = null,
    ) = DiagnosticEvent.create(code, version, api, vpn, error, id, journal.now(), androidVersion, cleanup)

    private companion object {
        const val ALERT_INTERVAL_MILLIS = 60_000L
    }
}
