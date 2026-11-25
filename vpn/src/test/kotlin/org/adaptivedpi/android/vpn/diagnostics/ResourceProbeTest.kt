package org.adaptivedpi.android.vpn.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportCode
import org.adaptivedpi.android.engine.TransportOperation
import org.adaptivedpi.android.engine.TransportSocketException
import org.adaptivedpi.android.vpn.analysis.IpFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

class ResourceProbeTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val loopback2 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))
    private val loopback3 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 3))

    private class TestNetwork(
        private val resolver: suspend (String) -> List<InetAddress> = { error("DNS не должен вызываться") },
        private val onPrepare: (Socket) -> Unit = {},
    ) : ProbeNetwork {
        override suspend fun resolve(host: String, family: IpFamily) = resolver(host)

        override fun prepare(socket: Socket) = onPrepare(socket)

        // Только для JVM-тестов: на Android тайм-аут задаёт ядро (SO_RCVTIMEO).
        override fun configureReadTimeout(socket: Socket, timeoutMillis: Int) {
            socket.soTimeout = timeoutMillis
        }
    }

    /** На JVM нет `ErrnoException`: тест подставляет errno по типизированным признакам, как это делает Android. */
    private val jvmErrno: (Throwable) -> Int? = { error ->
        generateSequence(error, Throwable::cause).take(8).firstNotNullOfOrNull {
            when {
                it is ConnectException && it.message?.startsWith("Connection refused") == true -> 111
                it is SocketException && it.message?.startsWith("Connection reset") == true -> 104
                else -> null
            }
        }
    }

    private fun newProbe(
        network: ProbeNetwork,
        tls: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
        allowed: (InetAddress) -> Boolean = { true },
        cleartext: Boolean = true,
    ) = ResourceProbe(network, tls, { cleartext }, allowed, minSilenceMillis = 300, errnoOf = jvmErrno)

    private fun dns(vararg addresses: InetAddress) = TestNetwork({ addresses.toList() })

    @Test
    fun distinguishesCorrectContentFromMismatchAndServerRefusals() = runBlocking<Unit> {
        val probe = newProbe(dns(loopback))
        for ((code, body, status) in listOf(
            Triple(200, "expected", ProbeStatus.OK),
            Triple(200, "different", ProbeStatus.CONTENT_MISMATCH),
            Triple(451, "restricted", ProbeStatus.HTTP_451),
            Triple(403, "forbidden", ProbeStatus.HTTP_FORBIDDEN),
        )) {
            serve(ServerSocket(0, 1, loopback), { respond(it, code, body) }) { port ->
                val result = probe.check(URI("http://localhost:$port/resource"), sha256("expected"))
                assertEquals(listOf(ProbeStage.DNS, ProbeStage.TCP, ProbeStage.HTTP), result.take(3).map { it.stage })
                assertEquals(code, result[2].httpStatus)
                assertEquals(status, result.last().status)
            }
        }
    }

    @Test
    fun localCleartextPolicyIsNotReportedAsAServerRestriction() = runBlocking<Unit> {
        val probe = newProbe(TestNetwork(onPrepare = { throw AssertionError("Сокет не должен открываться") }), cleartext = false)
        val result = probe.check(URI("http://localhost/resource")).single()
        assertEquals(ProbeStatus.CLEARTEXT_BLOCKED, result.status)
        assertEquals(null, result.httpStatus)
    }

    @Test
    fun reportsDnsFailureAndUsesAnIndependentHeaderDeadline() = runBlocking<Unit> {
        val unresolved = newProbe(TestNetwork({ throw UnknownHostException() }))
        assertEquals(ProbeStatus.DNS_ERROR, unresolved.check(URI("http://localhost/")).single().status)

        val probe = newProbe(dns(loopback))
        serve(ServerSocket(0, 1, loopback), {
            requestHeaders(it)
            assertEquals(-1, it.getInputStream().read())
        }) { port ->
            val result = probe.check(URI("http://localhost:$port"), timeouts = mapOf(ProbeStage.HTTP to 600))
            assertEquals(ProbeStage.HTTP, result.last().stage)
            assertEquals(ProbeStatus.TIMEOUT, result.last().status)
        }
    }

    @Test
    fun cancellationClosesSocketWhileResponseIsPendingAndReturnsQuickly() = runBlocking<Unit> {
        val headers = CompletableDeferred<Unit>()
        val probe = newProbe(dns(loopback))
        serve(ServerSocket(0, 1, loopback), {
            requestHeaders(it)
            headers.complete(Unit)
            assertEquals(-1, it.getInputStream().read())
        }) { port ->
            val pending = async { probe.check(URI("http://localhost:$port")) }
            withTimeout(3_000) { headers.await() }
            val started = System.nanoTime()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 200)
        }
    }

    @Test
    fun cancellationDuringDnsReturnsControlWithoutWaitingForTheResolver() = runBlocking<Unit> {
        val probe = newProbe(TestNetwork({ awaitCancellation() }))
        val pending = async { probe.check(URI("http://localhost/")) }
        delay(100)
        val started = System.nanoTime()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 200)
    }

    @Test
    fun untrustedOrMismatchedCertificatesAreReportedSeparatelyFromTlsFailures() = runBlocking<Unit> {
        val (server, client) = testTls()
        val trusted = newProbe(dns(loopback), client.socketFactory)
        serve(server.serverSocketFactory.createServerSocket(0, 1, loopback) as ServerSocket, { respond(it, 200, "tls") }) { port ->
            val result = trusted.check(URI("https://localhost:$port"), sha256("tls"))
            assertEquals(ProbeStage.entries, result.map { it.stage })
            assertTrue(result.all { it.status == ProbeStatus.OK })
        }
        for ((host, probe) in listOf(
            "different.invalid" to trusted,
            "localhost" to newProbe(dns(loopback)),
        )) {
            serve(server.serverSocketFactory.createServerSocket(0, 1, loopback) as ServerSocket, {
                try {
                    assertEquals(-1, it.getInputStream().read())
                } catch (_: java.io.IOException) {
                    // Клиент прервал TLS до HTTP.
                }
            }) { port ->
                val result = probe.check(URI("https://$host:$port"))
                assertEquals(listOf(ProbeStage.DNS, ProbeStage.TCP, ProbeStage.TLS), result.map { it.stage })
                assertEquals(ProbeStatus.TLS_CERTIFICATE, result.last().status)
            }
        }
    }

    @Test
    fun serverAlertResetAndSilenceAfterClientHelloGetTheirOwnStatuses() = runBlocking<Unit> {
        val probe = newProbe(dns(loopback))
        val alert = byteArrayOf(0x15, 3, 3, 0, 2, 2, 40)
        serve(ServerSocket(0, 1, loopback), {
            readClientHello(it)
            it.getOutputStream().write(alert)
            it.shutdownOutput()
            runCatching { it.getInputStream().read() }
        }) { port ->
            assertEquals(ProbeStatus.TLS_ALERT, probe.check(URI("https://localhost:$port")).last().status)
        }
        serve(ServerSocket(0, 1, loopback), {
            readClientHello(it)
            it.setSoLinger(true, 0)
        }) { port ->
            val result = probe.check(URI("https://localhost:$port"))
            assertEquals(ProbeStatus.TLS_RESET, result.last().status)
            assertEquals(104, result.last().errno)
        }
        serve(ServerSocket(0, 1, loopback), {
            readClientHello(it)
            runCatching { it.getInputStream().read() }
        }) { port ->
            val result = probe.check(URI("https://localhost:$port"))
            val tls = result.last()
            assertEquals(ProbeStage.TLS, tls.stage)
            assertEquals(ProbeStatus.TLS_SILENCE, tls.status)
            val rtt = checkNotNull(tls.rttMillis)
            assertTrue(rtt >= 1)
            assertEquals(Math.round(tls.elapsedMillis * 10.0 / rtt) / 10.0, tls.failureAfterRtt)
            assertEquals(rtt, result.first { it.stage == ProbeStage.TCP }.rttMillis)
        }
    }

    @Test
    fun tcpFailuresKeepResetTimeoutAndUnreachableApart() = runBlocking<Unit> {
        val closed = ServerSocket(0, 1, loopback).use { it.localPort }
        val refused = newProbe(dns(loopback)).check(URI("http://localhost:$closed/"))
        assertEquals(ProbeStatus.TCP_RESET, refused.last().status)
        assertEquals(111, refused.last().errno)

        for ((failure, expected) in listOf<Pair<Exception, ProbeStatus>>(
            SocketTimeoutException() to ProbeStatus.TCP_TIMEOUT,
            NoRouteToHostException() to ProbeStatus.TCP_UNREACHABLE,
            (TransportSocketException(TransportCode.PROTECT_REJECTED, TransportCategory.PROTECTION, TransportOperation.PROTECT)
                to ProbeStatus.PROBE_UNAVAILABLE),
        )) {
            val probe = newProbe(TestNetwork({ listOf(loopback) }, { throw failure }))
            assertEquals(expected, probe.check(URI("http://localhost/")).last().status)
        }
    }

    @Test
    fun foreignRedirectIsReportedWithOnlyTheTargetHost() = runBlocking<Unit> {
        val network = TestNetwork({ listOf(loopback) })
        serve(ServerSocket(0, 1, loopback), {
            requestHeaders(it)
            it.getOutputStream().write("HTTP/1.0 302 Redirect\r\nLocation: http://Outside.invalid/path?secret=1\r\n\r\n".toByteArray())
        }) { port ->
            val result = newProbe(network).check(URI("http://localhost:$port"))
            assertEquals(ProbeStatus.REDIRECT_FOREIGN, result.last().status)
            assertEquals("outside.invalid", result.last().redirectHost)
            assertEquals(1, result.count { it.stage == ProbeStage.DNS })
            assertFalse(result.toString().contains("secret"))
        }
    }

    @Test
    fun followsSameOriginRedirect() = runBlocking<Unit> {
        val probe = newProbe(dns(loopback))
        ServerSocket(0, 2, loopback).use { listener ->
            listener.soTimeout = 3_000
            val replies = FutureTask {
                listener.accept().use { client ->
                    client.soTimeout = 3_000
                    requestHeaders(client)
                    client.getOutputStream().write("HTTP/1.0 302 Redirect\r\nLocation: /resource\r\n\r\n".toByteArray())
                }
                listener.accept().use { client ->
                    client.soTimeout = 3_000
                    respond(client, 200, "expected")
                }
            }
            Thread(replies, "Probe redirect fixture").apply { isDaemon = true; start() }
            val result = probe.check(URI("http://localhost:${listener.localPort}/"), sha256("expected"))
            assertEquals(listOf(ProbeStatus.REDIRECT, ProbeStatus.OK), result.filter { it.stage == ProbeStage.HTTP }.map { it.status })
            assertEquals(1, result.last().hop)
            replies.get(3, TimeUnit.SECONDS)
        }
    }

    @Test
    fun specialAddressIsRejectedBeforeAnySocketIsOpenedIncludingAfterARedirect() = runBlocking<Unit> {
        val direct = newProbe(
            TestNetwork({ listOf(loopback) }, { throw AssertionError("Сокет не должен открываться") }),
            allowed = ::isProbeAddressAllowed,
        )
        val rejected = direct.check(URI("http://localhost/"))
        assertEquals(listOf(ProbeStage.DNS, ProbeStage.TCP), rejected.map { it.stage })
        assertEquals(ProbeStatus.ADDRESS_NOT_ALLOWED, rejected.last().status)

        val lookups = AtomicInteger()
        val prepared = AtomicInteger()
        val network = TestNetwork(
            { if (lookups.getAndIncrement() == 0) listOf(loopback) else listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 5))) },
            { prepared.incrementAndGet() },
        )
        serve(ServerSocket(0, 1, loopback), {
            requestHeaders(it)
            it.getOutputStream().write("HTTP/1.0 302 Redirect\r\nLocation: /next\r\n\r\n".toByteArray())
        }) { port ->
            val probe = newProbe(network, allowed = { it == loopback || isProbeAddressAllowed(it) })
            val result = probe.check(URI("http://localhost:$port/"))
            assertEquals(ProbeStatus.ADDRESS_NOT_ALLOWED, result.last().status)
            assertEquals(1, result.last().hop)
            assertEquals(1, prepared.get())
        }
    }

    @Test
    fun secondAddressGivesTheContentAfterTheFirstRejectsTls() = runBlocking<Unit> {
        val (server, client) = testTls()
        val probe = newProbe(dns(loopback, loopback2, loopback3), client.socketFactory)
        serve(ServerSocket(0, 1, loopback), { readClientHello(it) }) { port ->
            val listener = server.serverSocketFactory.createServerSocket(port, 1, loopback2) as ServerSocket
            serve(listener, { respond(it, 200, "expected") }) {
                val result = probe.check(URI("https://localhost:$port/"), sha256("expected"))
                assertEquals(listOf(1, 2), result.filter { it.stage == ProbeStage.TCP }.map { it.attempt })
                val firstTls = result.first { it.stage == ProbeStage.TLS && it.attempt == 1 }
                assertTrue(firstTls.status != ProbeStatus.OK)
                assertEquals(ProbeStatus.OK, result.first { it.stage == ProbeStage.TLS && it.attempt == 2 }.status)
                assertEquals(ProbeStage.CONTENT, result.last().stage)
                assertEquals(ProbeStatus.OK, result.last().status)
                assertEquals(2, result.last().attempt)
            }
        }
    }

    @Test
    fun overallDeadlineEndingOnTheSecondAddressDoesNotStartTheThird() = runBlocking<Unit> {
        val probe = newProbe(dns(loopback, loopback2, loopback3))
        serve(ServerSocket(0, 1, loopback2), {
            requestHeaders(it)
            runCatching { it.getInputStream().read() }
        }) { port ->
            ServerSocket(port, 1, loopback3).use { third ->
                third.soTimeout = 1_500
                val result = probe.check(URI("http://localhost:$port/"), totalTimeoutMillis = 900)
                assertEquals(ProbeStatus.TCP_RESET, result.first { it.attempt == 1 && it.stage == ProbeStage.TCP }.status)
                assertEquals(ProbeStatus.TIMEOUT, result.first { it.attempt == 2 && it.stage == ProbeStage.HTTP }.status)
                val skipped = result.last()
                assertEquals(3, skipped.attempt)
                assertEquals(ProbeStatus.NOT_RUN_DEADLINE, skipped.status)
                assertFalse(runCatching { third.accept().close() }.isSuccess)
            }
        }
    }

    @Test
    fun onlyAddressModeSkipsDnsAndDoesNotFollowRedirects() = runBlocking<Unit> {
        val probe = newProbe(TestNetwork())
        serve(ServerSocket(0, 1, loopback), {
            requestHeaders(it)
            it.getOutputStream().write("HTTP/1.0 302 Redirect\r\nLocation: /elsewhere\r\n\r\n".toByteArray())
        }) { port ->
            val result = probe.check(URI("http://localhost:$port/"), onlyAddress = loopback)
            assertEquals(listOf(ProbeStage.TCP, ProbeStage.HTTP), result.map { it.stage })
            assertEquals(ProbeStatus.REDIRECT, result.last().status)
            assertNull(result.firstOrNull { it.stage == ProbeStage.DNS })
        }
    }

    @Test
    fun usesOverallDeadlineWhenStageBudgetIsLonger() = runBlocking<Unit> {
        val probe = newProbe(dns(loopback))
        serve(ServerSocket(0, 1, loopback), {
            requestHeaders(it)
            assertEquals(-1, it.getInputStream().read())
        }) { port ->
            val result = probe.check(URI("http://localhost:$port"), totalTimeoutMillis = 700)
            assertEquals(ProbeStage.HTTP, result.last().stage)
            assertEquals(ProbeStatus.TIMEOUT, result.last().status)
            assertTrue(result.last().elapsedMillis < 2_000)
        }
    }

    @Test
    fun probeAddressPolicyRejectsPrivateAndServiceRanges() {
        fun allowed(vararg bytes: Int) = isProbeAddressAllowed(InetAddress.getByAddress(ByteArray(bytes.size) { bytes[it].toByte() }))
        for (address in listOf(
            intArrayOf(127, 0, 0, 1), intArrayOf(10, 1, 2, 3), intArrayOf(172, 16, 0, 1), intArrayOf(192, 168, 1, 1),
            intArrayOf(169, 254, 1, 1), intArrayOf(100, 64, 0, 1), intArrayOf(0, 0, 0, 0), intArrayOf(224, 0, 0, 1),
            intArrayOf(198, 18, 0, 1), intArrayOf(240, 0, 0, 1), intArrayOf(255, 255, 255, 255),
        )) assertFalse(address.joinToString("."), allowed(*address))
        assertTrue(allowed(93, 184, 216, 34))
        assertTrue(allowed(8, 8, 8, 8))
        assertFalse(isProbeAddressAllowed(InetAddress.getByName("::1")))
        assertFalse(isProbeAddressAllowed(InetAddress.getByName("fd00::1")))
        assertFalse(isProbeAddressAllowed(InetAddress.getByName("fe80::1")))
        assertFalse(isProbeAddressAllowed(InetAddress.getByName("2001:db8::1")))
        assertTrue(isProbeAddressAllowed(InetAddress.getByName("2606:4700:4700::1111")))
    }

    private fun readClientHello(socket: Socket) {
        val input = socket.getInputStream()
        assertTrue(input.read(ByteArray(2048)) > 0)
        Thread.sleep(50)
        while (input.available() > 0) input.read(ByteArray(2048))
    }

    private suspend fun serve(listener: ServerSocket, responder: (Socket) -> Unit, check: suspend (Int) -> Unit) {
        listener.use {
            listener.soTimeout = 5_000
            val worker = FutureTask { listener.accept().use { client -> client.soTimeout = 5_000; responder(client) } }
            Thread(worker, "Probe test server").apply { isDaemon = true; start() }
            check(listener.localPort)
            worker.get(5, TimeUnit.SECONDS)
        }
    }

    private fun requestHeaders(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        assertTrue(reader.readLine().startsWith("GET "))
        while (reader.readLine()?.isNotEmpty() == true) continue
    }

    private fun respond(socket: Socket, code: Int, body: String) {
        requestHeaders(socket)
        socket.getOutputStream().write(
            ("HTTP/1.0 $code Test\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray(Charsets.US_ASCII),
        )
    }

    private fun testTls(): Pair<SSLContext, SSLContext> {
        val directory = Files.createTempDirectory("resource-probe-tls").toFile()
        val store = File(directory, "ephemeral.p12")
        val output = File(directory, "keytool.log")
        val password = "probe-test".toCharArray()
        try {
            val name = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "keytool.exe" else "keytool"
            val keytool = File(File(System.getProperty("java.home"), "bin"), name)
            val process = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "probe", "-keyalg", "EC",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", store.path, "-storepass", String(password))
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Тестовый сертификат не создан")
            }
            assertEquals(0, process.exitValue())
            val keys = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, password) } }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
            val trust = KeyStore.getInstance("PKCS12").apply { load(null); setCertificateEntry("probe", keys.getCertificate("probe")) }
            val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) } to
                SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }
        } finally {
            store.delete()
            output.delete()
            directory.delete()
        }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
