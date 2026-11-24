package org.adaptivedpi.android

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.adaptivedpi.android.vpn.ExperimentalTarget
import org.adaptivedpi.android.vpn.TransportSnapshot
import org.adaptivedpi.android.vpn.TransportTestHooks
import org.adaptivedpi.android.vpn.VpnController
import org.adaptivedpi.android.vpn.VpnState
import org.adaptivedpi.android.vpn.diagnostics.TransportDiagnostics
import org.adaptivedpi.android.vpn.diagnostics.VpnEventHistory
import org.adaptivedpi.android.vpn.diagnostics.VpnEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExtendedTunTransportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = checkNotNull(InstrumentationRegistry.getArguments().getString("transportTarget"))
    private val ip = checkNotNull(ExperimentalTarget.parse(target))
    private val connectivity = checkNotNull(instrumentation.targetContext.getSystemService(ConnectivityManager::class.java))
    private val fixture = TunTransportInstrumentedTest()

    @Test(timeout = 120_000)
    fun tcpDownloadSurvivesSeventySecondsThroughTun() = withSession {
        val before = snapshot()
        connect(LONG_TCP).use { socket ->
            socket.getOutputStream().write('D'.code)
            val started = SystemClock.elapsedRealtime()
            val input = DataInputStream(socket.getInputStream())
            repeat(SAMPLES) { assertEquals("Порядок данных загрузки", it, input.readInt()) }
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue("Данные должны продолжаться после 60 с, получено за $elapsed мс", elapsed >= 65_000)
            Log.i(TAG, "Загрузка TCP: $elapsed мс, сверены $SAMPLES контрольных чисел")
        }
        verifyCounters(before, 1, SAMPLES * 4)
    }

    @Test(timeout = 120_000)
    fun tcpUploadSurvivesSeventySecondsThroughTun() = withSession {
        val before = snapshot()
        connect(LONG_TCP).use { socket ->
            val output = DataOutputStream(socket.getOutputStream())
            output.write('U'.code)
            val started = SystemClock.elapsedRealtime()
            runBlocking {
                repeat(SAMPLES) { sample ->
                    val scheduled = started + sample * 250L
                    delay((scheduled - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                    output.writeInt(sample)
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue(elapsed >= 65_000)
            Log.i(TAG, "Отправка TCP: $elapsed мс, переданы $SAMPLES контрольных чисел")
            val response = ByteArray(2).also { DataInputStream(socket.getInputStream()).readFully(it) }
            assertEquals("Сервер сверил все данные отправки", "OK", response.decodeToString())
        }
        verifyCounters(before, 1 + SAMPLES * 4, 2)
    }

    @Test(timeout = 120_000)
    fun fullyIdleTcpClosesThroughTunAndReleasesTheConnection() {
        val session = withSession { session ->
            val before = snapshot()
            connect(LONG_TCP, readTimeout = 80_000).use { socket ->
                socket.getOutputStream().write('I'.code)
                assertEquals('I'.code, socket.getInputStream().read())
                verifyCounters(before, 1, 1)
                val started = SystemClock.elapsedRealtime()
                try {
                    assertEquals(-1, socket.getInputStream().read())
                } catch (_: SocketException) {
                    // Android может вернуть RST. Отдельно подтверждаем закрытие исходящего сокета.
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                assertTrue("Простой закрыл TCP через $elapsed мс", elapsed in 55_000..80_000)
                awaitSnapshot { it.sessionId == session && it.openedTcp > before.openedTcp && it.connections == 0 }
                Log.i(TAG, "Idle TCP: $elapsed мс, исходящий сокет закрыт")
            }
            session
        }
        val timeouts = awaitTransportJournal(session).filter { it.contains("TIMEOUT") || it.contains("SocketTimeoutException") }
        Log.i(TAG, "Idle TCP: записей о тайм-ауте " + timeouts.size)
    }

    @Test(timeout = 55_000)
    fun stopClosesTcpHeldInPrepareBeforeServerAccepts() {
        val acceptedBefore = acceptedByFixture()
        val session = withSession { raceStopWithTcp(LONG_TCP, holdPreparation = true); it }
        assertEquals("Сервер не должен принять TCP, остановленный в prepare", acceptedBefore, acceptedByFixture())
        assertTrue(awaitTransportJournal(session).any { it.contains("SESSION_CANCELLED") })
    }

    @Test(timeout = 55_000)
    fun stopCancelsAnAlreadyBlockingSystemConnect() {
        val session = withSession { raceStopWithTcp(BLOCKED_TCP, holdPreparation = false); it }
        assertTrue(awaitTransportJournal(session).any { it.contains("SESSION_CANCELLED") })
    }

    @Test(timeout = 60_000)
    fun delayedNativePollWarnsAndStopKeepsResourcesUntilMutexReturns() = withActivity { activity ->
        val session = start(activity)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val hold = executor.submit { TransportTestHooks.holdNativePollLock(entered, release) }
        try {
            assertTrue("Native mutex не захвачен", entered.await(3, TimeUnit.SECONDS))
            runBlocking {
                withTimeout(8_000) {
                    VpnEventHistory.state.first { history ->
                        history.entries.any { it.sessionId == session && it.type == VpnEventType.TransportPollDelayed }
                    }
                }
            }
            assertEquals(VpnState.EngineRunning, VpnController.state.value)
            assertFalse(hold.isDone)
            requestStop()
            awaitState(VpnState.Stopping)
            runBlocking { withTimeout(7_000) { VpnController.cleanupDelay.first { it != null } } }
            assertFalse(VpnController.state.value.canStart)
            assertEquals(session, VpnController.currentSessionId)
            assertFalse(hold.isDone)
            fixture.assertOnlyFixtureRoute(connectivity, ip)
        } finally {
            release.countDown()
            try {
                hold.get(4, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
                stop()
            }
        }
        assertEquals(1, VpnEventHistory.state.value.entries.count { it.sessionId == session && it.type == VpnEventType.TransportPollDelayed })
        assertTrue(session != start(activity))
        stop()
    }

    @Test(timeout = 180_000)
    fun repeatedSessionsCarryTcpUdpAndDnsWithoutAccumulatingResources() {
        withActivity { activity ->
            val pool = Executors.newFixedThreadPool(4)
            try {
                stopWhileUdpResponseIsWaiting(activity)
                repeat(2) { exerciseSession(activity, pool, exchangesPerWorker = 1) }
                val baseline = resources()
                assertEquals(0, baseline.transportThreads)
                repeat(10) {
                    exerciseSession(activity, pool, exchangesPerWorker = 3)
                    awaitCondition(5_000) {
                        val now = resources()
                        now.transportThreads == 0 && now.sockets <= baseline.sockets + 1 &&
                            now.tun <= baseline.tun && now.total <= baseline.total + 4
                    }
                }
                Log.i(TAG, "10 сеансов, по 120 TCP, UDP и DNS обменов. Ресурсы $baseline → " + resources())
            } finally {
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    private fun stopWhileUdpResponseIsWaiting(activity: ActivityScenario<TransportTestActivity>) {
        val session = start(activity)
        DatagramSocket().use { socket ->
            socket.soTimeout = 5_000
            // Отправляем вторую датаграмму, пока поток ответа ждёт на том же сокете.
            for (value in listOf(42.toByte(), 43.toByte())) {
                val payload = byteArrayOf(value)
                socket.send(DatagramPacket(payload, payload.size, ip, UDP_ECHO))
                val reply = DatagramPacket(ByteArray(8), 8)
                socket.receive(reply)
                assertEquals(1, reply.length)
                assertEquals(value, reply.data[reply.offset])

                awaitCondition(5_000) {
                    Thread.getAllStackTraces().any { (thread, stack) ->
                        thread.name.startsWith("HEV response ") &&
                            stack.any { it.methodName.startsWith("poll") || it.methodName == "recvfrom" } &&
                            stack.any { it.className.endsWith("OutboundTransport\$UdpConnection") && it.methodName == "receive" }
                    }
                }
            }
            awaitSnapshot { it.sessionId == session && it.openedUdp == 1L }
            stop()
            assertEquals(1L, snapshot().closedUdp)
        }
        val journal = awaitTransportJournal(session)
        // Нативное завершение HEV может закрыть поток до вызова relay.close().
        assertTrue("Ожидалась отмена UDP RECEIVE при Stop. Журнал: $journal", journal.any {
            it.contains("UDP / RECEIVE /") && it.contains("(CANCELLED, EXPECTED)") &&
                (it.contains("SESSION_CANCELLED") || it.contains("FLOW_CANCELLED"))
        })
        Log.i(TAG, "UDP: Stop завершил активное ожидание ответа, соединения освобождены")
    }

    private fun exerciseSession(
        activity: ActivityScenario<TransportTestActivity>, pool: ExecutorService, exchangesPerWorker: Int,
    ) {
        start(activity)
        try {
            val before = snapshot()
            val workloads = List(4) {
                pool.submit<Pair<Int, Int>> {
                    var sent = 0
                    var received = 0
                    repeat(exchangesPerWorker) {
                        fixture.exchangeAll(ip) { _, up, down -> sent += up; received += down }
                    }
                    sent to received
                }
            }
            val totals = workloads.map { it.get(15, TimeUnit.SECONDS) }
            verifyCounters(before, totals.sumOf { it.first }, totals.sumOf { it.second })
        } finally {
            stop()
        }
    }

    private fun raceStopWithTcp(port: Int, holdPreparation: Boolean) {
        val before = snapshot()
        val prepared = CompletableFuture<Pair<Socket, Thread>>()
        val resume = CountDownLatch(1)
        TransportTestHooks.afterTcpPrepare = { socket ->
            if (prepared.complete(socket to Thread.currentThread()) && holdPreparation) {
                // Stop прерывает рабочие потоки релея. Удержание переживает interrupt,
                // иначе поток выходит сразу и проверка состояния Stopping становится гонкой.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
                var interrupted = false
                try {
                    while (true) {
                        try {
                            check(resume.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS))
                            break
                        } catch (_: InterruptedException) {
                            interrupted = true
                        }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val dial = Socket(Proxy.NO_PROXY)
        val attempt = executor.submit {
            try {
                dial.connect(InetSocketAddress(ip, port), 10_000)
            } catch (_: IOException) {
                // Проверка относится к исходящему сокету релея.
            }
        }
        try {
            val (outbound, worker) = prepared.get(6, TimeUnit.SECONDS)
            if (!holdPreparation) {
                awaitCondition(3_000) {
                    !outbound.isConnected && worker.stackTrace.any {
                        it.className == "java.net.Socket" && it.methodName == "connect"
                    }
                }
                // Кадр Socket.connect появляется раньше системного вызова. SYN_SENT в ядре
                // гарантирует, что SYN ушёл и стенд на хосте его посчитает.
                awaitSynSent(port)
            }
            assertFalse(outbound.isConnected)
            requestStop()
            awaitCondition(6_000) { outbound.isClosed }
            assertFalse(outbound.isConnected)
            if (holdPreparation) assertEquals(VpnState.Stopping, VpnController.state.value)
        } finally {
            TransportTestHooks.afterTcpPrepare = null
            resume.countDown()
            try {
                stop()
            } finally {
                dial.close()
                executor.shutdown()
                attempt.get(6, TimeUnit.SECONDS)
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            }
        }
        assertEquals(before.openedTcp, snapshot().openedTcp)
    }

    private fun <T> withSession(block: (UUID) -> T): T = withActivity { activity ->
        val session = start(activity)
        try {
            block(session)
        } finally {
            stop()
        }
    }

    private fun <T> withActivity(block: (ActivityScenario<TransportTestActivity>) -> T): T {
        assertNull("CI должен пройти системное согласие в этом AVD", VpnService.prepare(instrumentation.targetContext))
        return ActivityScenario.launch(TransportTestActivity::class.java).use { activity ->
            try {
                block(activity)
            } finally {
                if (VpnController.currentSessionId != null && VpnController.state.value != VpnState.Idle) {
                    requestStop()
                    awaitState(VpnState.Idle)
                }
            }
        }
    }

    private fun start(activity: ActivityScenario<TransportTestActivity>): UUID {
        activity.onActivity {
            assertEquals(VpnState.Idle, VpnController.state.value)
            val consent = VpnController.requestStart(it, target)
            if (consent != null) VpnController.abandonConsent(consent.id)
            assertNull(consent)
        }
        awaitState(VpnState.EngineRunning)
        fixture.assertOnlyFixtureRoute(connectivity, ip)
        return checkNotNull(VpnController.currentSessionId)
    }

    private fun stop() {
        if (VpnController.state.value != VpnState.Idle) {
            requestStop()
            awaitState(VpnState.Idle)
        }
        val final = snapshot()
        assertTrue(final.finalized)
        assertFalse(final.running)
        assertEquals(0, final.connections)
        assertEquals(final.openedTcp, final.closedTcp)
        assertEquals(final.openedUdp, final.closedUdp)
    }

    private fun requestStop() = instrumentation.runOnMainSync { VpnController.stop() }

    private fun awaitState(state: VpnState) = runBlocking {
        withTimeout(20_000) { VpnController.state.first { it == state } }
    }

    private fun awaitSnapshot(predicate: (TransportSnapshot) -> Boolean) = runBlocking {
        withTimeout(8_000) { VpnController.transport.filterNotNull().first(predicate) }
    }

    private fun awaitTransportJournal(sessionId: UUID) = runBlocking {
        withTimeout(8_000) {
            TransportDiagnostics.state.first { it.session?.sessionId == sessionId && it.session?.finished == true }
                .entries.filter { it.sessionId == sessionId }.map { it.text() }
        }
    }

    private fun verifyCounters(before: TransportSnapshot, sent: Int, received: Int) {
        awaitSnapshot {
            it.sessionId == before.sessionId && it.tunSentPackets > before.tunSentPackets &&
                it.tunReceivedPackets > before.tunReceivedPackets && it.sentBytes == before.sentBytes + sent &&
                it.receivedBytes == before.receivedBytes + received
        }
    }

    private fun snapshot() = checkNotNull(VpnController.transport.value)

    private fun acceptedByFixture() = connect(COUNTER).use { DataInputStream(it.getInputStream()).readInt() }

    private fun connect(port: Int, readTimeout: Int = 8_000) = Socket(Proxy.NO_PROXY).apply {
        try {
            soTimeout = readTimeout
            connect(InetSocketAddress(ip, port), 5_000)
        } catch (error: IOException) {
            close()
            throw error
        }
    }

    private fun awaitSynSent(port: Int) {
        val remotePort = ":%04X".format(port)
        awaitCondition(3_000) {
            listOf("/proc/net/tcp", "/proc/net/tcp6").any { path ->
                shell("cat $path").lineSequence().drop(1).any { line ->
                    val fields = line.trim().split(WHITESPACE)
                    // Сокет теста в TUN имеет адрес 192.0.2.1 и тот же удалённый порт: исключаем его,
                    // чтобы засчитать только исходящий сокет релея в физической сети.
                    fields.size > 3 && fields[2].endsWith(remotePort) && fields[3] == TCP_SYN_SENT &&
                        !fields[1].substringBefore(':').endsWith(TUN_ADDRESS_HEX)
                }
            }
        }
    }

    /** /proc/net недоступен приложению, читаем его от имени shell через UiAutomation. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun awaitCondition(timeoutMillis: Long, condition: () -> Boolean) = runBlocking {
        withTimeout(timeoutMillis) { while (!condition()) delay(25) }
    }

    private data class Resources(val total: Int, val sockets: Int, val tun: Int, val transportThreads: Int)

    private fun resources(): Resources {
        val paths = checkNotNull(File("/proc/self/fd").listFiles())
        val descriptors = paths.mapNotNull { path ->
            try { Os.readlink(path.path) } catch (_: ErrnoException) { null }
        }
        val workers = Thread.getAllStackTraces().keys.count { it.name.startsWith("HEV ") }
        return Resources(
            descriptors.size,
            descriptors.count { it.startsWith("socket:[") },
            descriptors.count { it.startsWith("/dev/tun") || it.startsWith("/dev/net/tun") },
            workers,
        )
    }

    private companion object {
        const val TAG = "ExtendedTunTest"
        const val UDP_ECHO = 39_052
        const val LONG_TCP = 39_055
        const val BLOCKED_TCP = 39_056
        const val TCP_SYN_SENT = "02"
        /** 192.0.2.1 в формате /proc/net/tcp: байты адреса в порядке хоста. */
        const val TUN_ADDRESS_HEX = "010200C0"
        val WHITESPACE = Regex("\\s+")
        const val COUNTER = 39_057
        const val SAMPLES = 281
    }
}
