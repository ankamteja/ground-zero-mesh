package org.groundzero.mesh.app.transport

import org.groundzero.mesh.app.node.MeshRole
import org.groundzero.mesh.app.node.RelayHostStore
import org.groundzero.mesh.app.service.MeshForegroundService
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.transport.TcpTransport
import org.groundzero.mesh.transport.Transport

/**
 * [RadioTransport] over [TcpTransport] — the alternative to [NearbyTransport] for whenever
 * a real third [MeshRole.RELAY] phone is not on hand. Connects to a laptop running
 * `:core:runRelay` (`TcpRelayMain`) on the same Wi-Fi hotspot or LAN instead of discovering
 * peers over BLE / Wi-Fi Direct.
 *
 * Thin on purpose: [TcpTransport] already is a complete, tested [Transport]; this class
 * only bolts on the two things [MeshForegroundService] expects beyond that interface — a
 * [PeerTable] and a "just connected" signal — the same way `NearbyTransport` already
 * carries them, so the service does not need to know which radio it is holding.
 *
 * See [RelayHostStore] for where the host comes from, and `docs/architecture.md`'s LAN
 * relay ledger entry for the whole design (star topology, why TCP, why no auto-discovery).
 */
class LanRelayTransport(
    private val delegate: TcpTransport,
) : Transport by delegate, RadioTransport {

    override val peers = PeerTable()

    private var peerConnectedListener: ((NodeId) -> Unit)? = null

    override fun onReceive(listener: (from: NodeId, frame: ByteArray) -> Unit) {
        delegate.onReceive { from, frame ->
            peers.sawInbound(from, from.canonical())
            listener(from, frame)
        }
    }

    override fun onPeerConnected(listener: (NodeId) -> Unit) {
        peerConnectedListener = listener
        delegate.onPeerConnected { peer ->
            peers.sawInbound(peer, peer.canonical())
            peerConnectedListener?.invoke(peer)
        }
    }
}
