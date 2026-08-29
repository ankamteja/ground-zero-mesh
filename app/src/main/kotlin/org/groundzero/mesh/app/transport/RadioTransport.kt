package org.groundzero.mesh.app.transport

import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.transport.Transport

/**
 * The extra surface `MeshForegroundService` needs beyond the bare [Transport] contract: a
 * live [PeerTable] for the maintenance ticker's decay tick, and a "just connected" signal
 * for store-and-forward replay to a peer that was just out of range.
 *
 * [NearbyTransport] and [LanRelayTransport] both implement this — the two radios a phone
 * can actually pick between (`MeshPermissions`/`RelayHostStore` decide which). Nothing
 * `core`-only (`SimTransport`) needs it.
 */
interface RadioTransport : Transport {
    val peers: PeerTable

    fun onPeerConnected(listener: (NodeId) -> Unit)
}
