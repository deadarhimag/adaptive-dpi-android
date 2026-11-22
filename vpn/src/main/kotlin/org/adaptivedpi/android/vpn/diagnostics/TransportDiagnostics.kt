package org.adaptivedpi.android.vpn.diagnostics

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportFailure
import org.adaptivedpi.android.vpn.VpnState
import java.io.File
import java.io.Writer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as withThreadLock

object TransportDiagnostics {
    private val current = MutableStateFlow(TransportJournalView())
    val state = current.asStateFlow()
    @Volatile private var journal: TransportJournal? = null

    @Synchronized
    fun install(context: Context) {
        if (journal != null) return
        val application = context.applicationContext
        journal = TransportJournal(CoroutineScope(SupervisorJob() + Dispatchers.IO), current = current) {
            TransportLogStore(File(application.noBackupFilesDir, "transport-diagnostics"))
        }
    }

    internal fun newSession(id: UUID): TransportSessionDiagnostics =
        TransportSessionDiagnostics(id, journal).also { journal?.useSession(it) }

    suspend fun export(writer: Writer) = withContext(Dispatchers.IO) {
        checkNotNull(journal) { "Хранилище журнала транспорта недоступно" }.export(writer)
    }
}

internal class TransportSessionDiagnostics(val id: UUID, private val journal: TransportJournal?) {
    private val counts = AtomicLongArray(TransportCategory.entries.size)
    private val operations = AtomicLong()
    private val merged = AtomicLong()
    private val dropped = AtomicLong()
    private val last = AtomicLong(Long.MIN_VALUE)
    private val finished = AtomicBoolean()
    private val vpnState = AtomicReference(VpnState.Starting)
    val total get() = operations.get()
    val state get() = vpnState.get()

    fun stateChanged(state: VpnState) { vpnState.set(state) }

    fun record(state: VpnState, failure: TransportFailure, millis: Long = System.currentTimeMillis()) {
        recordBatch(state, failure, millis, millis, 1)
    }

    fun recordBatch(state: VpnState, failure: TransportFailure, first: Long, end: Long, count: Long) {
        incrementCounts(failure.category, end, count)
        journal?.let {
            merge(count - 1)
            it.offer(this, state, failure, first, end, count)
        }
    }

    fun recordDropped(category: TransportCategory, end: Long, count: Long) {
        incrementCounts(category, end, count)
        drop(count)
        journal?.requestFlush()
    }

    private fun incrementCounts(category: TransportCategory, end: Long, count: Long) {
        counts.addAndGet(category.ordinal, count)
        operations.addAndGet(count)
        last.accumulateAndGet(end, ::maxOf)
    }

    fun merge(count: Long) { if (count > 0) merged.addAndGet(count) }
    fun drop(count: Long = 1) { dropped.addAndGet(count) }

    fun finish() {
        if (finished.compareAndSet(false, true)) journal?.completed(this)
    }

    fun summary() = TransportSessionSummary(
        id,
        buildMap {
            for (category in TransportCategory.entries) {
                val count = counts.get(category.ordinal)
                if (count > 0) put(category, count)
            }
        },
        merged.get(), dropped.get(), last.get().takeUnless { it == Long.MIN_VALUE }?.let(Instant::ofEpochMilli), finished.get(),
    )
}

/** Один писатель. Производители только агрегируют в ограниченных слотах и будят писателя. */
internal class TransportJournal(
    scope: CoroutineScope,
    private val batchMillis: Long = 1_000,
    private val current: MutableStateFlow<TransportJournalView> = MutableStateFlow(TransportJournalView()),
    private val storeFactory: () -> TransportLogStore,
) {
    private data class Key(val session: UUID, val state: VpnState, val failure: TransportFailure)
    private data class Bucket(val session: TransportSessionDiagnostics, var first: Long, var last: Long, var repeats: Long)
    private val pendingLock = ReentrantLock()
    private val pending = LinkedHashMap<Key, Bucket>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val io = Mutex()
    private val selected = AtomicReference<TransportSessionDiagnostics?>()
    private val completed = AtomicReference<TransportSessionDiagnostics?>()
    private var store: TransportLogStore? = null
    private var lastSummary: TransportSessionSummary? = null
    private var storageError = false
    val state = current.asStateFlow()

    init {
        scope.launch {
            io.withLock { ensureStore(); publish() }
            for (signal in wake) {
                delay(batchMillis)
                flush()
            }
        }
    }

    fun useSession(session: TransportSessionDiagnostics) {
        selected.set(session)
        requestFlush()
    }

    fun completed(session: TransportSessionDiagnostics) {
        completed.set(session)
        requestFlush()
    }

    fun requestFlush() { wake.trySend(Unit) }

    fun offer(session: TransportSessionDiagnostics, state: VpnState, failure: TransportFailure, first: Long, last: Long, count: Long) {
        if (!pendingLock.tryLock()) {
            session.drop(count)
        } else {
            try {
                val key = Key(session.id, state, failure)
                val bucket = pending[key]
                when {
                    bucket != null -> {
                        bucket.repeats += count
                        bucket.first = minOf(bucket.first, first)
                        bucket.last = maxOf(bucket.last, last)
                        session.merge(1)
                    }
                    pending.size < PENDING_LIMIT -> pending[key] = Bucket(session, first, last, count)
                    else -> session.drop(count)
                }
            } finally {
                pendingLock.unlock()
            }
        }
        requestFlush()
    }

    suspend fun flush() = io.withLock { flushLocked() }

    suspend fun export(writer: Writer) = io.withLock {
        flushLocked()
        val snapshot = current.value
        writer.appendLine("Журнал транспорта adaptive-dpi-android")
        writer.appendLine("На диске ${snapshot.bytes} байт. Сохранено записей ${snapshot.storedRecords}.")
        if (snapshot.storageError) writer.appendLine("В журнале обнаружена ошибка хранения. Некоторые записи недоступны.")
        snapshot.session?.let { writer.appendLine(it.text()) }
        writer.appendLine()
        store?.forEachEntry { writer.appendLine(it.text()) }
    }

    private fun flushLocked() {
        val disk = ensureStore()
        val finished = completed.getAndSet(null)
        val batch = pendingLock.withThreadLock {
            ArrayDeque(pending.entries.map { it.key to it.value }).also { pending.clear() }
        }
        while (batch.isNotEmpty()) {
            val (key, bucket) = batch.removeFirst()
            if (disk == null) {
                bucket.session.drop(bucket.repeats)
                continue
            }
            val entry = TransportLogEntry(key.session, Instant.ofEpochMilli(bucket.first), Instant.ofEpochMilli(bucket.last),
                key.state, key.failure, bucket.repeats)
            runCatching { disk.append(entry) }.onFailure { storageError = true; bucket.session.drop(bucket.repeats) }
        }
        finished?.let { session ->
            lastSummary = session.summary()
            if (disk != null) runCatching { disk.saveSummary(checkNotNull(lastSummary)) }.onFailure { storageError = true }
        }
        publish()
    }

    private fun ensureStore(): TransportLogStore? {
        store?.let { return it }
        return runCatching(storeFactory).onSuccess {
            store = it
            if (lastSummary == null) lastSummary = it.readSummary()
        }.onFailure { storageError = true }.getOrNull()
    }

    private fun publish() {
        val disk = store
        current.value = TransportJournalView(
            disk?.entries.orEmpty(), selected.get()?.summary() ?: lastSummary, disk?.bytes ?: 0,
            disk?.storedRecords ?: 0, ready = true, storageError = storageError || disk?.storageError == true,
        )
    }

    companion object {
        const val MAX_RAM_RECORDS = 500
        const val PENDING_LIMIT = 64
        const val NATIVE_LIMIT = 16
        // Каждый слой может принимать новые записи, пока его предыдущий пакет обрабатывается.
        const val VISIBLE_LIMIT = MAX_RAM_RECORDS - 2 * PENDING_LIMIT - 2 * NATIVE_LIMIT
    }
}
