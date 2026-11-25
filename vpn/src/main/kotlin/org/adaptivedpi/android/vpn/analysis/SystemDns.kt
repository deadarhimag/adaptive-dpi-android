package org.adaptivedpi.android.vpn.analysis

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class IpFamily(val dnsType: Int, val addressSize: Int) {
    V4(DnsResolver.TYPE_A, 4),
    V6(DnsResolver.TYPE_AAAA, 16),
}

/**
 * Разрешение имени системным DNS выбранной физической сети (`DnsResolver`, API 29).
 * Внешних запросов не делает: используется резолвер, который Android назначил этой сети.
 */
internal class SystemDns(private val network: Network) {
    /** Адреса одного семейства. Отмена корутины отменяет запрос и возвращает управление сразу. */
    suspend fun resolve(host: String, family: IpFamily = IpFamily.V4): List<InetAddress> =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            DnsResolver.getInstance().query(
                network, host, family.dnsType, DnsResolver.FLAG_EMPTY, Runnable::run, signal,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        continuation.resume(answer)
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        continuation.resumeWithException(UnknownHostException().apply { initCause(error) })
                    }
                },
            )
        }
}
