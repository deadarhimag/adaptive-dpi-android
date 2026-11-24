package org.adaptivedpi.android.vpn.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class ResourceProbeTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test
    fun distinguishesCorrectContentFromMismatchAndHttpRestriction() = runBlocking<Unit> {
        val probe = ResourceProbe(resolve = { arrayOf(loopback) }, cleartextAllowed = { true })
        for ((code, body, status) in listOf(
            Triple(200, "expected", ProbeStatus.OK),
            Triple(200, "different", ProbeStatus.CONTENT_MISMATCH),
            Triple(451, "restricted", ProbeStatus.HTTP_RESTRICTION),
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
    fun reportsDnsFailureAndUsesAnIndependentHeaderDeadline() = runBlocking<Unit> {
        val unresolved = ResourceProbe(resolve = { throw UnknownHostException() }, cleartextAllowed = { true })
        assertEquals(ProbeStatus.DNS_ERROR, unresolved.check(URI("http://localhost/")).single().status)

        val probe = ResourceProbe(resolve = { arrayOf(loopback) }, cleartextAllowed = { true })
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
    fun cancellationClosesSocketWhileResponseIsPending() = runBlocking<Unit> {
        val headers = CompletableDeferred<Unit>()
        val probe = ResourceProbe(resolve = { arrayOf(loopback) }, cleartextAllowed = { true })
        serve(ServerSocket(0, 1, loopback), {
            requestHeaders(it)
            headers.complete(Unit)
            assertEquals(-1, it.getInputStream().read())
        }) { port ->
            val pending = async { probe.check(URI("http://localhost:$port")) }
            withTimeout(3_000) { headers.await() }
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
    }

    @Test
    fun onlyTrustedCertificatesWithMatchingHostReachHttp() = runBlocking<Unit> {
        val (server, client) = testTls()
        val trusted = ResourceProbe({ arrayOf(loopback) }, client.socketFactory)
        serve(server.serverSocketFactory.createServerSocket(0, 1, loopback) as ServerSocket, { respond(it, 200, "tls") }) { port ->
            val result = trusted.check(URI("https://localhost:$port"), sha256("tls"))
            assertEquals(ProbeStage.entries, result.map { it.stage })
            assertTrue(result.all { it.status == ProbeStatus.OK })
        }
        for ((host, probe) in listOf(
            "different.invalid" to trusted,
            "localhost" to ResourceProbe(resolve = { arrayOf(loopback) }),
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
                assertEquals(ProbeStatus.TLS_ERROR, result.last().status)
            }
        }
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
