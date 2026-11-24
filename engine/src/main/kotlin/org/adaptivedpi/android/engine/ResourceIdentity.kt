package org.adaptivedpi.android.engine

import java.net.InetAddress

/** Контекст наблюдения внутри сеанса. Модель не предназначена для журналов. */
internal class ResourceEndpoint(
    val address: InetAddress,
    val port: Int,
    val protocol: TransportProtocol,
    val network: Long,
    val ownerUid: Int?,
) {
    init { require(port in 1..65535 && protocol != TransportProtocol.NONE) }
}

internal class ResourceDnsObservation(
    val name: String,
    val addresses: Set<InetAddress>,
    val network: Long,
    val ownerUid: Int?,
    val expiresAtMillis: Long,
)

internal enum class ResourceConfidence { OBSERVED_TLS_NAME, CORRELATED_DNS, AMBIGUOUS, ENDPOINT_ONLY }

internal class ResourceIdentity(
    val endpoint: ResourceEndpoint,
    val name: String?,
    val confidence: ResourceConfidence,
)

internal fun identifyResource(
    endpoint: ResourceEndpoint,
    hello: ClientHelloInspector.Result.Hello?,
    dns: List<ResourceDnsObservation>,
    nowMillis: Long,
): ResourceIdentity {
    if (hello?.encryptedHello == true) return ResourceIdentity(endpoint, null, ResourceConfidence.ENDPOINT_ONLY)
    if (endpoint.protocol == TransportProtocol.TCP && hello?.serverName != null) {
        return ResourceIdentity(endpoint, hello.serverName, ResourceConfidence.OBSERVED_TLS_NAME)
    }

    val names = dns.asSequence().filter {
        it.network == endpoint.network && it.ownerUid != null && it.ownerUid == endpoint.ownerUid &&
            nowMillis < it.expiresAtMillis && endpoint.address in it.addresses
    }.map { it.name.removeSuffix(".").lowercase() }.filter { it.isNotBlank() }.distinct().take(2).toList()
    return when (names.size) {
        0 -> ResourceIdentity(endpoint, null, ResourceConfidence.ENDPOINT_ONLY)
        1 -> ResourceIdentity(endpoint, names.single(), ResourceConfidence.CORRELATED_DNS)
        else -> ResourceIdentity(endpoint, null, ResourceConfidence.AMBIGUOUS)
    }
}
