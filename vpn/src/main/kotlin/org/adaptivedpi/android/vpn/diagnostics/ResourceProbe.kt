package org.adaptivedpi.android.vpn.diagnostics

import android.security.NetworkSecurityPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.adaptivedpi.android.engine.TransportCategory
import org.adaptivedpi.android.engine.TransportDirection
import org.adaptivedpi.android.engine.TransportFailure
import org.adaptivedpi.android.engine.TransportFailureReporter
import org.adaptivedpi.android.engine.TransportOperation
import org.adaptivedpi.android.engine.TransportProtocol
import org.adaptivedpi.android.engine.TransportSite
import org.adaptivedpi.android.engine.TransportSocketException
import org.adaptivedpi.android.engine.TransportSubsystem
import org.adaptivedpi.android.vpn.analysis.IpFamily
import org.adaptivedpi.android.vpn.errnoOfCause
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal enum class ProbeStage { DNS, TCP, TLS, HTTP, CONTENT }

internal enum class ProbeStatus {
    OK, DNS_ERROR, NETWORK_ERROR, TLS_ERROR, HTTP_ERROR,
    CONTENT_MISMATCH, TIMEOUT, INDETERMINATE, BUSY, REDIRECT,
    /** Незашифрованный HTTP запрещён политикой Android на устройстве: сеть и сервер не участвовали. */
    CLEARTEXT_BLOCKED,
    /** Цепочка сертификатов не проверена платформенным `TrustManager` или имя не совпало. */
    TLS_CERTIFICATE,
    /** Получен alert. Источник не аутентифицирован: это мог отправить и посредник. */
    TLS_ALERT,
    /** Сброс соединения после отправки ClientHello. */
    TLS_RESET,
    /** Нет ни одного байта от сервера после ClientHello дольше порога. */
    TLS_SILENCE,
    /** Сброс на этапе установления TCP (включая отказ в подключении). */
    TCP_RESET,
    TCP_TIMEOUT,
    TCP_UNREACHABLE,
    /** Юридическая причина отказа (RFC 7725). О географии не говорит. */
    HTTP_451,
    /** Код 403: отказ сервера, вывода о причине нет. */
    HTTP_FORBIDDEN,
    /** Редирект на другой домен второго уровня: только наблюдение, не вывод о заглушке. */
    REDIRECT_FOREIGN,
    /** Фактический адрес недопустим для пробы ([ENGINE.md], раздел 18); сокет не открывался. */
    ADDRESS_NOT_ALLOWED,
    /** Попытка не выполнена: истёк общий срок пробы. */
    NOT_RUN_DEADLINE,
    /** Проба непригодна (отказ `protect`, потеря физической сети): на гипотезы не влияет. */
    PROBE_UNAVAILABLE,
}

internal data class ProbeResult(
    val stage: ProbeStage,
    val status: ProbeStatus,
    val elapsedMillis: Long,
    val httpStatus: Int? = null,
    val hop: Int = 0,
    val attempt: Int = 1,
    /** Время установления TCP этой попытки. */
    val rttMillis: Long? = null,
    /** Время до отказа, делённое на RTT, одна десятичная. */
    val failureAfterRtt: Double? = null,
    val errno: Int? = null,
    /** Только хост цели чужого редиректа: путь и запрос не сохраняются. */
    val redirectHost: String? = null,
)

/**
 * Проверяет указанный URL один раз. Вызывающий управляет отменой coroutine.
 * DNS, `protect` и привязка к физической сети - через [network]; успешное рукопожатие
 * не считается доступностью ресурса.
 */
internal class ResourceProbe(
    private val network: ProbeNetwork,
    private val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val cleartextAllowed: (String) -> Boolean = { NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(it) },
    /** Тестовый параметр: серверы стенда живут на loopback. Проверку сертификата он не меняет. */
    private val addressAllowed: (InetAddress) -> Boolean = ::isProbeAddressAllowed,
    private val minSilenceMillis: Int = MIN_SILENCE_MILLIS,
    private val errnoOf: (Throwable) -> Int? = ::errnoOfCause,
) {
    /**
     * [onlyAddress] - режим «только этот адрес»: DNS не вызывается, редиректы не выполняются.
     * [family] - семейство IP, по умолчанию IPv4 (как у приложения в VPN).
     */
    suspend fun check(
        uri: URI,
        expectedSha256: String? = null,
        timeouts: Map<ProbeStage, Int> = emptyMap(),
        totalTimeoutMillis: Int = 15_000,
        family: IpFamily = IpFamily.V4,
        onlyAddress: InetAddress? = null,
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
            val redirect = checkHop(target, expectedSha256, timeouts, deadline, hop, family, onlyAddress, results) ?: return results
            if (onlyAddress != null) return results
            val next = runCatching { target.resolve(redirect).normalize() }.getOrNull()
            val foreign = next != null && isValidTarget(next) && !sameOrigin(uri, next) &&
                secondLevelDomain(uri.host) != secondLevelDomain(next.host)
            if (foreign) {
                results.add(ProbeResult(ProbeStage.HTTP, ProbeStatus.REDIRECT_FOREIGN, 0, hop = hop, redirectHost = next!!.host.lowercase()))
                break
            }
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

    private class Attempt(val finished: Boolean, val redirect: String? = null)

    private suspend fun checkHop(
        uri: URI,
        expectedSha256: String?,
        timeouts: Map<ProbeStage, Int>,
        deadline: Long,
        hop: Int,
        family: IpFamily,
        onlyAddress: InetAddress?,
        results: MutableList<ProbeResult>,
    ): String? {
        val host = requireNotNull(uri.host)
        val https = uri.scheme == "https"
        if (!https && !cleartextAllowed(host)) {
            results.add(ProbeResult(ProbeStage.HTTP, ProbeStatus.CLEARTEXT_BLOCKED, 0, hop = hop))
            return null
        }
        val port = effectivePort(uri)
        val addresses = onlyAddress?.let { listOf(it) } ?: resolveAddresses(host, family, timeouts, deadline, hop, results) ?: return null
        for ((index, address) in addresses.withIndex()) {
            val attempt = index + 1
            if (TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) <= 0) {
                for (skipped in index until addresses.size) {
                    results.add(ProbeResult(ProbeStage.TCP, ProbeStatus.NOT_RUN_DEADLINE, 0, hop = hop, attempt = skipped + 1))
                }
                return null
            }
            val outcome = attemptOnce(uri, address, port, https, expectedSha256, timeouts, deadline, hop, attempt, results)
            if (outcome.redirect != null) return outcome.redirect
            if (outcome.finished || results.last().status == ProbeStatus.BUSY ||
                results.last().status == ProbeStatus.PROBE_UNAVAILABLE
            ) return null
        }
        return null
    }

    private suspend fun resolveAddresses(
        host: String,
        family: IpFamily,
        timeouts: Map<ProbeStage, Int>,
        deadline: Long,
        hop: Int,
        results: MutableList<ProbeResult>,
    ): List<InetAddress>? {
        val start = System.nanoTime()
        fun finish(status: ProbeStatus, error: Throwable? = null) {
            results.add(
                ProbeResult(
                    ProbeStage.DNS, status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), hop = hop,
                    errno = error?.let { errnoOf(it) },
                ),
            )
        }
        val timeout = minOf((timeouts[ProbeStage.DNS] ?: DEFAULT_TIMEOUT).toLong(), TimeUnit.NANOSECONDS.toMillis(deadline - start))
        if (timeout <= 0) {
            finish(ProbeStatus.TIMEOUT)
            return null
        }
        return try {
            val resolved = withTimeoutOrNull(timeout) {
                if (isLiteral(host)) listOf(InetAddress.getByName(host)) else network.resolve(host, family)
            }
            val usable = resolved?.filter { it.address.size == family.addressSize }?.distinct()?.take(MAX_ADDRESSES).orEmpty()
            when {
                resolved == null -> { finish(ProbeStatus.TIMEOUT); null }
                usable.isEmpty() -> { finish(ProbeStatus.DNS_ERROR); null }
                else -> { finish(ProbeStatus.OK); usable }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            finish(ProbeStatus.DNS_ERROR, error)
            null
        }
    }

    private suspend fun attemptOnce(
        uri: URI,
        address: InetAddress,
        port: Int,
        https: Boolean,
        expectedSha256: String?,
        timeouts: Map<ProbeStage, Int>,
        deadline: Long,
        hop: Int,
        attempt: Int,
        results: MutableList<ProbeResult>,
    ): Attempt {
        val host = requireNotNull(uri.host)
        val asciiUri = URI(uri.toASCIIString())
        val path = asciiUri.rawPath.ifEmpty { "/" } + (asciiUri.rawQuery?.let { "?$it" } ?: "")
        if (!addressAllowed(address)) {
            results.add(ProbeResult(ProbeStage.TCP, ProbeStatus.ADDRESS_NOT_ALLOWED, 0, hop = hop, attempt = attempt))
            return Attempt(finished = false)
        }
        var socket: Socket? = null
        var rtt: Long? = null
        fun closeSocket() { runCatching { socket?.close() } }

        suspend fun <T : Any> stage(
            stage: ProbeStage,
            action: suspend (Int) -> T,
            status: (T) -> ProbeStatus = { ProbeStatus.OK },
            httpStatus: (T) -> Int? = { null },
        ): T? {
            val start = System.nanoTime()
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - start)
            val timeout = minOf((timeouts[stage] ?: DEFAULT_TIMEOUT).toLong(), remaining).toInt()
            fun finish(result: ProbeStatus, code: Int? = null, errno: Int? = null) {
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                val known = rtt
                val ratio = if (known != null && known > 0 && result != ProbeStatus.OK && result != ProbeStatus.REDIRECT) {
                    Math.round(elapsed * 10.0 / known) / 10.0
                } else null
                results.add(ProbeResult(stage, result, elapsed, code, hop, attempt, known, ratio, errno))
            }
            if (timeout <= 0) {
                finish(ProbeStatus.TIMEOUT)
                return null
            }
            return try {
                val value = withTimeoutOrNull(timeout.toLong()) { action(timeout) }
                if (value == null) {
                    finish(if (stage == ProbeStage.TCP) ProbeStatus.TCP_TIMEOUT else ProbeStatus.TIMEOUT)
                    null
                } else {
                    val result = status(value)
                    finish(result, httpStatus(value))
                    value.takeIf { result == ProbeStatus.OK || result == ProbeStatus.REDIRECT }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: RejectedExecutionException) {
                finish(ProbeStatus.BUSY)
                null
            } catch (_: InvalidHttpResponse) {
                finish(ProbeStatus.INDETERMINATE)
                null
            } catch (error: IOException) {
                val (result, errno) = classify(stage, error)
                finish(result, errno = errno)
                null
            }
        }

        try {
            val candidate = Socket(Proxy.NO_PROXY).also { socket = it }
            val connectStart = System.nanoTime()
            val raw = stage(ProbeStage.TCP, { timeout ->
                runIo({ closeSocket() }) {
                    network.prepare(candidate)
                    candidate.connect(InetSocketAddress(address, port), timeout)
                    candidate
                }
            }) ?: return Attempt(finished = false)
            val connectMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectStart).coerceAtLeast(1)
            rtt = connectMillis
            results[results.lastIndex] = results.last().copy(rttMillis = connectMillis)
            val silence = maxOf(3 * connectMillis, minSilenceMillis.toLong()).coerceAtMost(MAX_SILENCE_MILLIS).toInt()
            val wire = if (https) {
                stage(ProbeStage.TLS, { _ ->
                    runIo({ closeSocket() }) {
                        network.configureReadTimeout(raw, silence)
                        val tls = tlsFactory.createSocket(raw, host, port, true) as SSLSocket
                        try {
                            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                            tls.startHandshake()
                            tls
                        } catch (error: IOException) {
                            runCatching(tls::close)
                            throw error
                        }
                    }
                }) ?: return Attempt(finished = false)
            } else raw
            try {
                val response = stage(ProbeStage.HTTP, { timeout ->
                    runIo({ closeSocket() }) {
                        network.configureReadTimeout(raw, timeout)
                        val authority = asciiUri.rawAuthority
                        wire.getOutputStream().write(
                            ("GET $path HTTP/1.0\r\nHost: $authority\r\nConnection: close\r\nAccept-Encoding: identity\r\n\r\n")
                                .toByteArray(Charsets.US_ASCII),
                        )
                        readResponse(BufferedInputStream(wire.getInputStream()))
                    }
                }, status = {
                    when (it.code) {
                        403 -> ProbeStatus.HTTP_FORBIDDEN
                        451 -> ProbeStatus.HTTP_451
                        in REDIRECT_CODES -> if (it.location == null) ProbeStatus.INDETERMINATE else ProbeStatus.REDIRECT
                        in 200..299 -> ProbeStatus.OK
                        else -> ProbeStatus.HTTP_ERROR
                    }
                }, httpStatus = Response::code) ?: return Attempt(finished = results.last().status in HTTP_ANSWERED)
                if (response.code in REDIRECT_CODES) return Attempt(finished = true, redirect = response.location)
                if (expectedSha256 == null) {
                    results.add(ProbeResult(ProbeStage.CONTENT, ProbeStatus.INDETERMINATE, 0, hop = hop, attempt = attempt, rttMillis = rtt))
                } else {
                    stage(ProbeStage.CONTENT, { timeout ->
                        runIo({ closeSocket() }) {
                            network.configureReadTimeout(raw, timeout)
                            response.hash().equals(expectedSha256, ignoreCase = true)
                        }
                    }, status = { if (it) ProbeStatus.OK else ProbeStatus.CONTENT_MISMATCH })
                }
            } finally {
                closeSocket()
                if (wire !== raw) runCatching(wire::close)
            }
            return Attempt(finished = true)
        } finally {
            closeSocket()
        }
    }

    private fun classify(stage: ProbeStage, error: Throwable): Pair<ProbeStatus, Int?> {
        if (error is TransportSocketException) return ProbeStatus.PROBE_UNAVAILABLE to null
        val site = TransportSite(TransportSubsystem.SOCKET, TransportOperation.CONNECT, TransportProtocol.TCP, TransportDirection.OUTBOUND)
        var failure = TransportFailure(site, TransportCategory.UNKNOWN)
        TransportFailureReporter(errnoOf) { failure = it }.recordError(site, error)
        val causes = generateSequence(error, Throwable::cause).take(8).toList()
        val timedOut = failure.category == TransportCategory.TIMEOUT || causes.any { it is java.net.SocketTimeoutException }
        val status = when (stage) {
            ProbeStage.TCP -> when {
                failure.category == TransportCategory.TIMEOUT || timedOut -> ProbeStatus.TCP_TIMEOUT
                failure.category == TransportCategory.RESET || failure.category == TransportCategory.REFUSED ||
                    failure.category == TransportCategory.CLOSED -> ProbeStatus.TCP_RESET
                failure.category == TransportCategory.UNREACHABLE -> ProbeStatus.TCP_UNREACHABLE
                else -> ProbeStatus.NETWORK_ERROR
            }
            ProbeStage.TLS -> when {
                causes.any { it is CertificateException || it is SSLPeerUnverifiedException } -> ProbeStatus.TLS_CERTIFICATE
                // В JSSE и Conscrypt у полученного alert нет типизированного признака: используется текст
                // сообщения библиотеки. Текст в результат не попадает; источник alert не аутентифицирован.
                causes.any { it is SSLException && it.message?.contains("alert", ignoreCase = true) == true } -> ProbeStatus.TLS_ALERT
                failure.category == TransportCategory.RESET || failure.category == TransportCategory.CLOSED -> ProbeStatus.TLS_RESET
                timedOut -> ProbeStatus.TLS_SILENCE
                else -> ProbeStatus.TLS_ERROR
            }
            else -> if (timedOut) ProbeStatus.TIMEOUT else ProbeStatus.NETWORK_ERROR
        }
        return status to failure.errno
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
        /** Порог «тишины после ClientHello»: max(3 RTT, 2 с), не больше 5 с ([ENGINE.md], раздел 21). */
        const val MIN_SILENCE_MILLIS = 2_000
        const val MAX_SILENCE_MILLIS = 5_000L
        const val MAX_ADDRESSES = 3
        const val MAX_REDIRECTS = 2
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        /** Сервер ответил на HTTP-запрос: другие адреса пробовать незачем. */
        val HTTP_ANSWERED = setOf(ProbeStatus.HTTP_451, ProbeStatus.HTTP_FORBIDDEN, ProbeStatus.HTTP_ERROR)

        fun effectivePort(uri: URI): Int = when {
            uri.port != -1 -> uri.port
            uri.scheme == "https" -> 443
            else -> 80
        }

        fun isValidTarget(uri: URI): Boolean =
            uri.scheme in setOf("http", "https") && uri.host != null &&
                uri.rawUserInfo == null && uri.rawFragment == null && effectivePort(uri) in 1..65535 &&
                uri.toASCIIString().length <= MAX_HEADER_BYTES

        fun isLiteral(host: String): Boolean = host.contains(':') || host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))

        /** Домен второго уровня без списка публичных суффиксов (он появится в 2.1): две последние метки. */
        fun secondLevelDomain(host: String): String {
            val labels = host.lowercase().trimEnd('.').split('.')
            return if (isLiteral(host) || labels.size <= 2) host.lowercase() else labels.takeLast(2).joinToString(".")
        }

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
