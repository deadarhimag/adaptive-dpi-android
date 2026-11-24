package org.adaptivedpi.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class ResourceIdentityTest {
    private val ip = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))

    @Test
    fun sharedAddressRemainsAmbiguousAndExpiredRecordsDoNotApply() {
        val endpoint = ResourceEndpoint(ip, 443, TransportProtocol.TCP, 1, 201)
        val matches = listOf(
            ResourceDnsObservation("first.example", setOf(ip), 1, 201, 10),
            ResourceDnsObservation("second.example", setOf(ip), 1, 201, 11),
            ResourceDnsObservation("old-network.example", setOf(ip), 2, 201, 20),
            ResourceDnsObservation("other-app.example", setOf(ip), 1, 202, 20),
        )
        val ambiguous = identifyResource(endpoint, null, matches, 9)
        assertEquals(ResourceConfidence.AMBIGUOUS, ambiguous.confidence)
        assertNull(ambiguous.name)
        val correlated = identifyResource(endpoint, null, matches, 10)
        assertEquals(ResourceConfidence.CORRELATED_DNS, correlated.confidence)
        assertEquals("second.example", correlated.name)
        assertEquals(ResourceConfidence.ENDPOINT_ONLY, identifyResource(endpoint, null, matches, 11).confidence)
    }

    @Test
    fun echAndAnUnknownOwnerDoNotImplyAName() {
        val address = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 })
        val endpoint = ResourceEndpoint(address, 443, TransportProtocol.TCP, 1, null)
        val observations = listOf(ResourceDnsObservation("fixture.example", setOf(address), 1, null, 10))
        assertNull(identifyResource(endpoint, null, observations, 0).name)
        val visible = ClientHelloInspector.Result.Hello("visible.example", emptyList(), false)
        assertEquals(ResourceConfidence.OBSERVED_TLS_NAME, identifyResource(endpoint, visible, observations, 0).confidence)
        val outer = ClientHelloInspector.Result.Hello("outer.example", emptyList(), true)
        assertNull(identifyResource(endpoint, outer, observations, 0).name)
    }
}
