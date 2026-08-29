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

    private var receiveListener: ((from: NodeId, frame: ByteArray) -> Unit)? = null
    private var peerConnectedListener: ((NodeId) -> Unit)? = null

    // Registered once, unconditionally, here rather than inside onReceive/onPeerConnected
    // below. TcpTransport.onReceive/onPeerConnected each hold a single overwriting slot, so
    // wiring peers.sawInbound as a side effect of *those setters* — as this class used to —
    // meant peer bookkeeping only happened if and when a caller registered its own listener,
    // and only for events after that registration. `NearbyTransport` does not have this gap:
    // its peer table is populated from its own SDK callbacks regardless of whether the app
    // ever calls onReceive/onPeerConnected. This constructor now matches that — peers is
    // always current, and a caller's listener is a separate, optional forward.
    init {
        delegate.onReceive { from, frame ->
            peers.sawInbound(from, from.canonical())
            receiveListener?.invoke(from, frame)
        }
        delegate.onPeerConnected { peer ->
            peers.sawInbound(peer, peer.canonical())
            peerConnectedListener?.invoke(peer)
        }
    }

    override fun onReceive(listener: (from: NodeId, frame: ByteArray) -> Unit) {
        receiveListener = listener
    }

    override fun onPeerConnected(listener: (NodeId) -> Unit) {
        peerConnectedListener = listener
    }
}
