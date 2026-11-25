package org.adaptivedpi.android.engine

import org.adaptivedpi.android.engine.flow.FlowFailure
import org.adaptivedpi.android.engine.flow.FlowHello
import org.adaptivedpi.android.engine.flow.FlowKind
import org.adaptivedpi.android.engine.flow.FlowObserver
import org.adaptivedpi.android.engine.flow.FlowOutcomeRecorder
import org.adaptivedpi.android.engine.flow.FlowRecord
import org.adaptivedpi.android.engine.flow.FlowStage
import org.adaptivedpi.android.engine.flow.UdpRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext

class FlowTelemetryTest {
    private val ip = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val policy = object : OutboundSocketPolicy {
        override fun prepare(socket: Socket) = Unit
        override fun prepare(socket: DatagramSocket) = Unit
    }

    private class Outcomes : FlowObserver {
        val queue = LinkedBlockingQueue<FlowRecord>()
        override fun onOutcome(outcome: FlowRecord) {
            queue.add(outcome)
        }
    }

    private fun record(
        kind: FlowKind = FlowKind.TLS,
        hello: FlowHello? = null,
        stage: FlowStage = FlowStage.EXCHANGED,
        failure: FlowFailure? = null,
        abandoned: Boolean = false,
        rate: Long? = null,
    ) = FlowRecord(1, 443, kind, hello, stage, failure, 5, null, 10, 20, abandoned, rate)

    @Test
    fun recorderCountsKindsStagesHelloSizesAndRatesWithoutNames() {
        val recorder = FlowOutcomeRecorder()
        recorder.onOutcome(record(hello = FlowHello(hasName = true, ech = false, length = 500, records = 1), rate = 1_000))
        recorder.onOutcome(record(hello = FlowHello(hasName = true, ech = true, length = 1_700, records = 2), rate = 70_000))
        recorder.onOutcome(record(hello = FlowHello(hasName = false, ech = false, length = 300, records = 3), stage = FlowStage.NO_RESPONSE))
        recorder.onOutcome(record(hello = null, stage = FlowStage.FIRST_CHUNK, abandoned = true))
        recorder.onOutcome(record(kind = FlowKind.HTTP))
        recorder.onOutcome(record(kind = FlowKind.OTHER))

        val total = recorder.snapshot()
        assertEquals(6, total.tcpFlows)
        assertEquals(1, total.tlsWithName)
        assertEquals(1, total.tlsWithEch)
        assertEquals(1, total.tlsWithoutName)
        assertEquals(1, total.tlsUnparsed)
        assertEquals(1, total.http)
        assertEquals(1, total.otherTcp)
        assertEquals(3, total.helloCount)
        assertEquals(300, total.helloMinLength)
        assertEquals(1_700, total.helloMaxLength)
        assertEquals(2_500, total.helloTotalLength)
        assertEquals(1, total.helloRecords1)
        assertEquals(1, total.helloRecords2)
        assertEquals(1, total.helloRecords3Plus)
        assertEquals(1L, total.stages[FlowStage.NO_RESPONSE])
        assertEquals(1L, total.stages[FlowStage.FIRST_CHUNK])
        assertEquals(4L, total.stages[FlowStage.EXCHANGED])
        assertEquals(1, total.clientAbandoned)
        assertEquals(2, total.rateFlows)
        assertEquals(1_000, total.rateMin())
        assertEquals(70_000, total.rateMax())
        assertEquals(1L, total.rateBuckets[9])
        assertEquals(1L, total.rateBuckets[16])
    }

    private fun org.adaptivedpi.android.engine.flow.FlowAggregate.rateMin() = rateMinBytesPerSecond
    private fun org.adaptivedpi.android.engine.flow.FlowAggregate.rateMax() = rateMaxBytesPerSecond

    @Test
    fun internalRefusalsAreCountedSeparatelyFromKeyFailures() {
        val recorder = FlowOutcomeRecorder()
        recorder.onOutcome(record(failure = FlowFailure(TransportCategory.RESET, TransportCode.UNKNOWN, 104)))
        recorder.onOutcome(record(failure = FlowFailure(TransportCategory.TIMEOUT, TransportCode.TIMEOUT, null)))
        recorder.onOutcome(record(failure = FlowFailure(TransportCategory.LIMIT, TransportCode.SOCKET_LIMIT, null)))
        recorder.onOutcome(record(failure = FlowFailure(TransportCategory.CANCELLED, TransportCode.CANCELLED, null)))
        recorder.onInternalRefusal(TransportCode.SESSION_LIMIT)

        val total = recorder.snapshot()
        assertEquals(mapOf(TransportCategory.RESET to 1L, TransportCategory.TIMEOUT to 1L), total.failuresByCategory)
        assertEquals(mapOf(104 to 1L), total.failuresByErrno)
        assertEquals(
            mapOf(TransportCode.SOCKET_LIMIT to 1L, TransportCode.CANCELLED to 1L, TransportCode.SESSION_LIMIT to 1L),
            total.internalRefusals,
        )
    }

    @Test
    fun recorderSeparatesUdp443AndCountsUnansweredAssociationsAndPeak() {
        val recorder = FlowOutcomeRecorder()
        recorder.onUdp(UdpRecord(443, 6, 0))
        recorder.onUdp(UdpRecord(443, 6, 5))
        recorder.onUdp(UdpRecord(53, 4, 1))
        recorder.onUdp(UdpRecord(53, 2, 0))
        recorder.onActive(3)
        recorder.onActive(7)
        recorder.onActive(5)

        val total = recorder.snapshot()
        assertEquals(4, total.udpAssociations)
        assertEquals(18, total.udpOut)
        assertEquals(6, total.udpIn)
        assertEquals(2, total.udpUnanswered)
        assertEquals(2, total.udp443Associations)
        assertEquals(12, total.udp443Out)
        assertEquals(5, total.udp443In)
        assertEquals(1, total.udp443Unanswered)
        assertEquals(7, total.peakActive)
        val text = total.text(16)
        assertTrue(text, text.contains("Пик одновременных TCP-соединений: 7 из лимита 16"))
    }

    @Test(timeout = 120_000)
    fun thousandRandomFlowsPassThroughTheRelayByteForByte() {
        val outcomes = Outcomes()
        val failures = ConcurrentLinkedQueue<TransportFailure>()
        val pool = Executors.newFixedThreadPool(8)
        try {
            relay(outcomes, failures = TransportFailureReporter(sink = failures::add), captureMillis = 30, maxSessions = 16) { relay ->
                val tasks = (0 until 8).map { worker ->
                    pool.submit<Unit> {
                        val random = Random(1_000L + worker)
                        ServerSocket(0, 4, ip).use { listener ->
                            listener.soTimeout = 10_000
                            repeat(125) {
                                val payload = randomPayload(random)
                                echoThroughRelay(relay, listener, payload)
                            }
                        }
                    }
                }
                tasks.forEach { it.get(100, TimeUnit.SECONDS) }
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1_000, outcomes.queue.size)
        assertTrue(failures.toString(), failures.none { it.category == TransportCategory.INTERNAL })
    }

    @Test(timeout = 20_000)
    fun nonTlsStreamIsForwardedWithoutWaitingForTheCaptureDeadline() {
        val outcomes = Outcomes()
        ServerSocket(0, 1, ip).use { listener ->
            listener.soTimeout = 5_000
            relay(outcomes, captureMillis = 5_000) { relay ->
                connect(relay, listener.localPort).use { client ->
                    val started = System.nanoTime()
                    client.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    listener.accept().use { peer ->
                        peer.soTimeout = 5_000
                        assertEquals('G'.code, peer.getInputStream().read())
                        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        assertTrue("первый байт дошёл за $elapsedMillis мс при сроке захвата 5000 мс", elapsedMillis < 2_000)
                    }
                }
            }
        }
        assertEquals(FlowKind.HTTP, outcomes.queue.poll(5, TimeUnit.SECONDS)?.kind)
    }

    @Test(timeout = 20_000)
    fun helloSentInThreePiecesIsClassifiedOnceAndReachesTheServerUnchanged() {
        val hello = realHello("fixture.example")
        val recognized = AtomicInteger()
        val outcomes = Outcomes()
        ServerSocket(0, 1, ip).use { listener ->
            listener.soTimeout = 5_000
            relay(
                outcomes, captureMillis = 2_000,
                classify = { bytes, length ->
                    ClientHelloInspector.inspect(bytes, length).also { if (it is ClientHelloInspector.Result.Hello) recognized.incrementAndGet() }
                },
            ) { relay ->
                connect(relay, listener.localPort).use { client ->
                    val first = hello.size / 3
                    val second = 2 * hello.size / 3
                    val output = client.getOutputStream()
                    output.write(hello, 0, first)
                    output.flush()
                    Thread.sleep(30)
                    output.write(hello, first, second - first)
                    output.flush()
                    Thread.sleep(30)
                    output.write(hello, second, hello.size - second)
                    client.shutdownOutput()
                    listener.accept().use { peer ->
                        peer.soTimeout = 5_000
                        assertArrayEquals(hello, peer.getInputStream().readAllBytes())
                    }
                }
            }
        }
        val outcome = checkNotNull(outcomes.queue.poll(5, TimeUnit.SECONDS))
        assertEquals(1, recognized.get())
        assertEquals(FlowKind.TLS, outcome.kind)
        assertEquals(true, outcome.hello?.hasName)
        assertEquals(hello.size - 5, outcome.hello?.length)
    }

    @Test(timeout = 20_000)
    fun portionsSlowerThanTheDeadlineStopTheCaptureAndKeepByteOrder() {
        val hello = realHello("slow.example")
        val outcomes = Outcomes()
        ServerSocket(0, 1, ip).use { listener ->
            listener.soTimeout = 5_000
            relay(outcomes, captureMillis = 100) { relay ->
                connect(relay, listener.localPort).use { client ->
                    val output = client.getOutputStream()
                    output.write(hello, 0, 40)
                    output.flush()
                    Thread.sleep(400)
                    output.write(hello, 40, hello.size - 40)
                    client.shutdownOutput()
                    listener.accept().use { peer ->
                        peer.soTimeout = 5_000
                        assertArrayEquals(hello, peer.getInputStream().readAllBytes())
                    }
                }
            }
        }
        val outcome = checkNotNull(outcomes.queue.poll(5, TimeUnit.SECONDS))
        assertEquals(FlowKind.TLS, outcome.kind)
        assertNull("захват оборвался по сроку, имени нет", outcome.hello)
    }

    @Test(timeout = 20_000)
    fun stopDuringCaptureReleasesTheConnection() {
        val outcomes = Outcomes()
        val transport = OutboundTransport(readTimeoutMillis = 15_000).apply { updateNetwork(policy) }
        val relay = HevLoopbackRelay(
            transport, ip, 4, TransportFailureReporter(), { _, thread -> thread.start() },
            flows = outcomes, flowCaptureMillis = 10_000,
        ) { error("Неожиданная остановка релея") }
        ServerSocket(0, 1, ip).use { listener ->
            listener.soTimeout = 5_000
            val client = connect(relay, listener.localPort)
            client.use {
                it.getOutputStream().write(byteArrayOf(0x16, 3, 1))
                it.getOutputStream().flush()
                Thread.sleep(300)
                relay.close()
                assertTrue(relay.awaitShutdown())
            }
        }
        assertTrue(relay.isTerminated)
        assertEquals(0, transport.statistics().activeTcp + transport.statistics().activeUdp)
        transport.close()
    }

    @Test(timeout = 20_000)
    fun classifierFailureIsReportedButTheConnectionKeepsWorking() {
        val failures = ConcurrentLinkedQueue<TransportFailure>()
        val outcomes = Outcomes()
        val payload = ByteArray(5_000) { (it * 31).toByte() }.also { it[0] = 0x16 }
        ServerSocket(0, 1, ip).use { listener ->
            listener.soTimeout = 5_000
            relay(
                outcomes, failures = TransportFailureReporter(sink = failures::add), captureMillis = 500,
                classify = { _, _ -> throw IllegalStateException("Тестовый отказ классификатора") },
            ) { relay ->
                connect(relay, listener.localPort).use { client ->
                    client.getOutputStream().write(payload)
                    client.shutdownOutput()
                    listener.accept().use { peer ->
                        peer.soTimeout = 5_000
                        assertArrayEquals(payload, peer.getInputStream().readAllBytes())
                    }
                }
            }
        }
        assertFalse("отказ классификатора должен попасть в журнал", failures.isEmpty())
        assertNotNull(outcomes.queue.poll(5, TimeUnit.SECONDS))
    }

    private fun randomPayload(random: Random): ByteArray {
        val payload = ByteArray(1 + random.nextInt(40_000)).also(random::nextBytes)
        when (random.nextInt(3)) {
            0 -> payload[0] = 0x16
            1 -> "GET /".toByteArray().copyInto(payload, 0, 0, minOf(5, payload.size))
        }
        return payload
    }

    // Клиент отправляет payload целиком и закрывает запись; сервер сверяет байты и отвечает тем же.
    private fun echoThroughRelay(relay: HevLoopbackRelay, listener: ServerSocket, payload: ByteArray) {
        connect(relay, listener.localPort).use { client ->
            val writer = Thread {
                client.getOutputStream().write(payload)
                client.shutdownOutput()
            }.apply { start() }
            listener.accept().use { peer ->
                peer.soTimeout = 10_000
                val received = peer.getInputStream().readAllBytes()
                assertArrayEquals(payload, received)
                peer.getOutputStream().write(received)
                peer.shutdownOutput()
            }
            client.soTimeout = 10_000
            assertArrayEquals(payload, client.getInputStream().readAllBytes())
            writer.join(10_000)
        }
    }

    private fun relay(
        flows: FlowObserver,
        failures: TransportFailureReporter = TransportFailureReporter(),
        captureMillis: Long,
        maxSessions: Int = 16,
        classify: ((ByteArray, Int) -> ClientHelloInspector.Result)? = null,
        test: (HevLoopbackRelay) -> Unit,
    ) {
        val transport = OutboundTransport(readTimeoutMillis = 15_000, failures = failures).apply { updateNetwork(policy) }
        val relay = HevLoopbackRelay(
            transport, ip, maxSessions, failures, { _, thread -> thread.start() },
            flows = flows, flowCaptureMillis = captureMillis, flowClassify = classify,
        ) { error("Неожиданная остановка релея") }
        relay.use { test(it) }
        assertTrue(relay.awaitShutdown())
        assertEquals(0, transport.statistics().activeTcp + transport.statistics().activeUdp)
        transport.close()
    }

    // Подключение клиента к релею и SOCKS-команда CONNECT на порт сервера.
    private fun connect(relay: HevLoopbackRelay, port: Int): Socket {
        val client = Socket(ip, relay.port).apply { soTimeout = 5_000 }
        try {
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())
            output.write(byteArrayOf(5, 1, 2))
            assertArrayEquals(byteArrayOf(5, 2), ByteArray(2).also(input::readFully))
            output.write(byteArrayOf(1, 8))
            output.write("adaptive".toByteArray())
            output.writeByte(relay.configSecret.length)
            output.write(relay.configSecret.toByteArray())
            assertEquals(1, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            output.write(byteArrayOf(5, 1, 0, 1))
            output.write(ip.address)
            output.writeShort(port)
            assertEquals(0, ByteArray(10).also(input::readFully)[1].toInt())
            return client
        } catch (error: Throwable) {
            client.close()
            throw error
        }
    }

    private fun realHello(name: String): ByteArray {
        val engine = SSLContext.getDefault().createSSLEngine(name, 443).apply {
            useClientMode = true
            sslParameters = sslParameters.apply { serverNames = listOf(SNIHostName(name)) }
            beginHandshake()
        }
        val packet = ByteBuffer.allocate(engine.session.packetBufferSize)
        assertTrue(engine.wrap(ByteBuffer.allocate(0), packet).bytesProduced() > 9)
        return packet.array().copyOf(packet.position())
    }
}
