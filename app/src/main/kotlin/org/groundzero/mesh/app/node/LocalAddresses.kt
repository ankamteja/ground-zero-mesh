package org.groundzero.mesh.app.node

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The addresses a laptop could actually reach this phone's board on.
 *
 * The responder screen used to print the literal string `http://<this-phone-ip>:8080`, which
 * leaves the one person holding the phone to go and find their own IP in Settings — on a
 * hotspot they have just opened, under time pressure, in front of an audience. The phone
 * knows the answer; it should say it.
 *
 * IPv4 only, loopback excluded: what is wanted is the address another device on the same
 * network types into a browser, and neither `::1` nor a link-local IPv6 address is that.
 * Enumerating interfaces needs no permission.
 */
object LocalAddresses {

    /** Every non-loopback IPv4 address, hotspot interfaces first. Empty when offline. */
    fun ipv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { iface.name to it.hostAddress.orEmpty() }
            }
            .filter { it.second.isNotBlank() }
            // A phone serving its own hotspot is the documented setup, and its interface is
            // the one a laptop will actually be joined to — so it goes first rather than
            // being buried under a mobile-data address that no laptop can reach.
            .sortedByDescending { (name, _) -> name.startsWith("ap") || name.startsWith("swlan") }
            .map { it.second }
            .distinct()
    }.getOrDefault(emptyList())
}
