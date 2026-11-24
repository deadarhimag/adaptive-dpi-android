package org.adaptivedpi.android.vpn.diagnostics

import android.security.NetworkSecurityPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal enum class ProbeStage { DNS, TCP, TLS, HTTP, CONTENT }

internal enum class ProbeStatus {
    OK, DNS_ERROR, NETWORK_ERROR, TLS_ERROR, HTTP_RESTRICTION, HTTP_ERROR,
    CONTENT_MISMATCH, TIMEOUT, INDETERMINATE, BUSY, REDIRECT,
}

internal data class ProbeResult(
    val stage: ProbeStage,
    val status: ProbeStatus,
    val elapsedMillis: Long,
    val httpStatus: Int? = null,
    val hop: Int = 0,
    val attempt: Int = 1,
)

/** Проверяет указанный URL один раз. Вызывающий управляет отменой coroutine. */
internal class ResourceProbe(
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    private val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val cleartextAllowed: (String) -> Boolean = { NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(it) },
) {
    suspend fun check(
        uri: URI,
        expectedSha256: String? = null,
        timeouts: Map<ProbeStage, Int> = emptyMap(),
        totalTimeoutMillis: Int = 15_000,
    ): List<ProbeResult> {
        require(isValidTarget(uri))
        require(expectedSha256 == null || expectedSha256.matches(Regex("[0-9a-fA-F]{64}")))
        require(timeouts.values.all { it in 1..30_000 })
        require(totalTimeoutMillis in 1..60_000)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMillis.toLong())
        val results = mutableListOf<ProbeResult>()
        var target = uri
        val visited = mutableSetOf(uri.normalize())
        for (hop in 0..MAX_REDIRECTS) {
            val redirect = checkHop(target, expectedSha256, timeouts, deadline, hop, results) ?: return results
            val next = runCatching { target.resolve(redirect).normalize() }.getOrNull()
            if (hop == MAX_REDIRECTS || next == null || !isValidTarget(next) ||
                !sameOrigin(uri, next) || !visited.add(next)
            ) {
                results.add(ProbeResult(ProbeStage.HTTP, ProbeStatus.INDETERMINATE, 0, hop = hop))
                break
            }
            target = next
        }
        return results
    }

    private suspend fun checkHop(
        uri: URI,
        expectedSha256: String?,
        timeouts: Map<ProbeStage, Int>,
        deadline: Long,
        hop: Int,
        results: MutableList<ProbeResult>,
    ): String? {
        val host = requireNotNull(uri.host)
        val https = uri.scheme == "https"
        if (!https && !cleartextAllowed(host)) {
            results.add(ProbeResult(ProbeStage.HTTP, ProbeStatus.HTTP_RESTRICTION, 0, hop = hop))
            return null
        }
        val port = effectivePort(uri)
        val asciiUri = URI(uri.toASCIIString())
        val path = asciiUri.rawPath.ifEmpty { "/" } + (asciiUri.rawQuery?.let { "?$it" } ?: "")
        var attempt = 1
        var socket: Socket? = null
        fun closeSocket() { runCatching { socket?.close() } }
        suspend fun <T : Any> stage(
            stage: ProbeStage,
            ioError: ProbeStatus,
            action: (Int) -> T,
            status: (T) -> ProbeStatus = { ProbeStatus.OK },
            httpStatus: (T) -> Int? = { null },
        ): T? {
            val start = System.nanoTime()
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - start)
            val timeout = minOf((timeouts[stage] ?: DEFAULT_TIMEOUT).toLong(), remaining).toInt()
            val activeSocket = socket
            fun finish(result: ProbeStatus, code: Int? = null) {
                results.add(ProbeResult(stage, result, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), code, hop, attempt))
            }
            if (timeout <= 0) {
                finish(ProbeStatus.TIMEOUT)
                return null
            }
            return try {
                val value = withTimeoutOrNull(timeout.toLong()) {
                    runIo({ runCatching { activeSocket?.close() } }) { action(timeout) }
                }
                if (value == null) {
                    finish(ProbeStatus.TIMEOUT)
                    null
                } else {
                    val result = status(value)
                    finish(result, httpStatus(value))
                    value.takeIf { result == ProbeStatus.OK || result == ProbeStatus.REDIRECT }
                }
            } catch (_: SocketTimeoutException) {
                finish(ProbeStatus.TIMEOUT)
                null
            } catch (_: RejectedExecutionException) {
                finish(ProbeStatus.BUSY)
                null
            } catch (_: InvalidHttpResponse) {
                finish(ProbeStatus.INDETERMINATE)
                null
            } catch (_: IOException) {
                finish(ioError)
                null
            }
        }

        try {
            val addresses = stage(ProbeStage.DNS, ProbeStatus.DNS_ERROR, {
                val resolved = resolve(host).distinct()
                if (resolved.isEmpty()) throw IOException("Нет адреса для пробы")
                val first = resolved.first()
                val otherFamily = resolved.firstOrNull { it.address.size != first.address.size }
                (listOfNotNull(first, otherFamily) + resolved).distinct().take(MAX_ADDRESSES)
            }) ?: return null
            var connected: Socket? = null
            for ((index, address) in addresses.withIndex()) {
                attempt = index + 1
                val candidate = Socket(Proxy.NO_PROXY).also { socket = it }
                connected = stage(ProbeStage.TCP, ProbeStatus.NETWORK_ERROR, { timeout ->
                    candidate.connect(InetSocketAddress(address, port), timeout)
                    candidate
                })
                if (connected != null) break
                closeSocket()
                if (results.last().status == ProbeStatus.BUSY || System.nanoTime() >= deadline) break
            }
            val raw = connected ?: return null
            val wire = if (https) {
                stage(ProbeStage.TLS, ProbeStatus.TLS_ERROR, { timeout ->
                    val tls = tlsFactory.createSocket(raw, host, port, true) as SSLSocket
                    try {
                        tls.soTimeout = timeout
                        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        tls.startHandshake()
                        tls
                    } catch (error: IOException) {
                        runCatching(tls::close)
                        throw error
                    }
                }) ?: return null
            } else raw
            try {
                val response = stage(ProbeStage.HTTP, ProbeStatus.NETWORK_ERROR, { timeout ->
                    wire.soTimeout = timeout
                    val authority = asciiUri.rawAuthority
                    wire.getOutputStream().write(
                        ("GET $path HTTP/1.0\r\nHost: $authority\r\nConnection: close\r\nAccept-Encoding: identity\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII),
                    )
                    readResponse(BufferedInputStream(wire.getInputStream()))
                }, status = {
                    when (it.code) {
                        403, 451 -> ProbeStatus.HTTP_RESTRICTION
                        in REDIRECT_CODES -> if (it.location == null) ProbeStatus.INDETERMINATE else ProbeStatus.REDIRECT
                        in 200..299 -> ProbeStatus.OK
                        else -> ProbeStatus.HTTP_ERROR
                    }
                }, httpStatus = Response::code) ?: return null
                if (response.code in REDIRECT_CODES) return response.location
                if (expectedSha256 == null) {
                    results.add(ProbeResult(ProbeStage.CONTENT, ProbeStatus.INDETERMINATE, 0, hop = hop, attempt = attempt))
                } else {
                    stage(ProbeStage.CONTENT, ProbeStatus.NETWORK_ERROR, { timeout ->
                        wire.soTimeout = timeout
                        response.hash().equals(expectedSha256, ignoreCase = true)
                    }, status = { if (it) ProbeStatus.OK else ProbeStatus.CONTENT_MISMATCH })
                }
            } finally {
                closeSocket()
                if (wire !== raw) runCatching(wire::close)
            }
            return null
        } finally {
            closeSocket()
        }
    }

    private fun readResponse(input: BufferedInputStream): Response {
        var remaining = MAX_HEADER_BYTES
        fun readLine(): String {
            val line = StringBuilder()
            while (remaining-- > 0) {
                val value = input.read()
                if (value == -1) throw InvalidHttpResponse()
                if (value == '\n'.code) {
                    if (line.lastOrNull() != '\r') throw InvalidHttpResponse()
                    return line.dropLast(1).toString()
                }
                line.append(value.toChar())
            }
            throw InvalidHttpResponse()
        }
        val status = STATUS_LINE.matchEntire(readLine()) ?: throw InvalidHttpResponse()
        var length: Long? = null
        var location: String? = null
        var supportedBody = true
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) throw InvalidHttpResponse()
            val name = line.substring(0, separator).lowercase()
            val value = line.substring(separator + 1).trim()
            when (name) {
                "content-length" -> {
                    val parsed = value.toLongOrNull()?.takeIf { it >= 0 } ?: throw InvalidHttpResponse()
                    if (length != null && parsed != length) throw InvalidHttpResponse()
                    length = parsed
                }
                "location" -> {
                    if (location != null || value.isEmpty()) throw InvalidHttpResponse()
                    location = value
                }
                "transfer-encoding" -> supportedBody = false
                "content-encoding" -> if (!value.equals("identity", ignoreCase = true)) supportedBody = false
            }
        }
        val code = status.groupValues[1].toInt()
        return Response(code, location, if (code == 204 || code == 205) 0 else length, supportedBody, input)
    }

    private class Response(
        val code: Int,
        val location: String?,
        private val length: Long?,
        private val supportedBody: Boolean,
        private val input: BufferedInputStream,
    ) {
        fun hash(): String {
            if (!supportedBody || length != null && length > MAX_BODY_BYTES) throw InvalidHttpResponse()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(4096)
            var readBytes = 0
            while (length == null || readBytes.toLong() < length) {
                val limit = minOf(buffer.size, (length ?: (MAX_BODY_BYTES + 1L)).toInt() - readBytes)
                val read = input.read(buffer, 0, limit)
                if (read == -1) {
                    if (length != null) throw InvalidHttpResponse()
                    break
                }
                readBytes += read
                if (readBytes > MAX_BODY_BYTES) throw InvalidHttpResponse()
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }

    private class InvalidHttpResponse : IOException()

    private companion object {
        const val DEFAULT_TIMEOUT = 5_000
        const val MAX_ADDRESSES = 3
        const val MAX_REDIRECTS = 2
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun effectivePort(uri: URI): Int = when {
            uri.port != -1 -> uri.port
            uri.scheme == "https" -> 443
            else -> 80
        }

        fun isValidTarget(uri: URI): Boolean =
            uri.scheme in setOf("http", "https") && uri.host != null &&
                uri.rawUserInfo == null && uri.rawFragment == null && effectivePort(uri) in 1..65535 &&
                uri.toASCIIString().length <= MAX_HEADER_BYTES

        fun sameOrigin(first: URI, next: URI): Boolean =
            first.scheme == next.scheme && first.host.equals(next.host, ignoreCase = true) &&
                effectivePort(first) == effectivePort(next)
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 64 * 1024
        val STATUS_LINE = Regex("HTTP/1\\.[01] ([1-5][0-9]{2})(?: .*)?")
        // Системный DNS может пережить interrupt. Очереди нет, число таких вызовов ограничено.
        val workers = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue()) { task ->
            Thread(task, "Resource probe").apply { isDaemon = true }
        }

        suspend fun <T> runIo(close: () -> Unit, action: () -> T): T = suspendCancellableCoroutine { result ->
            try {
                val task = workers.submit { result.resumeWith(runCatching(action)) }
                result.invokeOnCancellation { close(); task.cancel(true) }
            } catch (failure: RejectedExecutionException) {
                result.resumeWith(Result.failure(failure))
            }
        }
    }
}
