package org.adaptivedpi.android.vpn.diagnostics

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Допустимый адрес для пробы ([ENGINE.md], раздел 18): не частный, не link-local, не loopback и не служебный.
 * Проверяется фактический адрес сокета перед каждым `connect()`.
 */
internal fun isProbeAddressAllowed(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return false
    val bytes = address.address.map { it.toInt() and 255 }
    return if (address is Inet4Address) {
        !(bytes[0] == 0 ||
            bytes[0] == 100 && bytes[1] in 64..127 ||
            bytes[0] == 192 && bytes[1] == 0 && bytes[2] in 0..2 ||
            bytes[0] == 192 && bytes[1] == 88 && bytes[2] == 99 ||
            bytes[0] == 198 && bytes[1] in 18..19 ||
            bytes[0] == 198 && bytes[1] == 51 && bytes[2] == 100 ||
            bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113 ||
            bytes[0] >= 240)
    } else {
        !(bytes[0] and 0xFE == 0xFC || bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0D && bytes[3] == 0xB8)
    }
}
