package org.groundzero.mesh.app.transport

import org.groundzero.mesh.app.node.MeshRole
import org.groundzero.mesh.app.node.RelayHostStore
import org.groundzero.mesh.app.service.MeshForegroundService
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.transport.TcpTransport
import org.groundzero.mesh.transport.Transport
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    /**
     * Writes leave the caller's thread here.
     *
     * [TcpTransport.send] writes to a socket and flushes, synchronously. On a phone the
     * caller is often the main thread -- `VictimScreen`'s SOS button goes straight through
     * `NodeViewModel` and `NodeAgent.raiseSos` into this transport -- and Android kills a
     * process that touches a socket there:
     *
     *     android.os.NetworkOnMainThreadException
     *         at TcpFraming.writeFrame ... at NodeAgent.raiseSos ... at VictimScreen
     *
     * No JVM test can catch that: `SimNetwork` has no sockets and StrictMode is an Android
     * runtime policy. It reproduces on a phone every time the relay path is used.
     *
     * A single thread, not a pool: [Transport.send] returns Unit and is already
     * fire-and-forget, so nothing observes completion, but frame *order* on one link still
     * has to hold. `NearbyTransport` needs none of this -- the Nearby SDK is already
     * asynchronous -- which is why the fix belongs here rather than at the call site.
     */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LanRelayTransport-write").apply { isDaemon = true }
    }

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

    /** Hand the frame to [writer] and return; see its doc for why. */
    override fun send(frame: ByteArray, to: NodeId?) {
        val copy = frame.copyOf()  // the caller may reuse its array once send() returns
        writer.execute {
            // A dead relay must not take the writer thread with it: TcpTransport already
            // reconnects on its own, and the next frame should still get a chance.
            runCatching { delegate.send(copy, to) }
        }
    }

    /**
     * Let whatever is already queued reach the socket before closing it.
     *
     * Now that [send] returns before the write happens, a frame handed over just before
     * shutdown is still sitting in the queue -- and the last frame before a service stops is
     * the one most likely to matter, since the service usually stops because the phone is
     * dying. A second is long enough for a queue that only ever holds a few small frames and
     * short enough not to hang `onDestroy`; past that the relay is unreachable anyway and
     * waiting longer changes nothing.
     */
    override fun stop() {
        writer.shutdown()
        runCatching { writer.awaitTermination(1, TimeUnit.SECONDS) }
        delegate.stop()
    }

    override fun onReceive(listener: (from: NodeId, frame: ByteArray) -> Unit) {
        receiveListener = listener
    }

    override fun onPeerConnected(listener: (NodeId) -> Unit) {
        peerConnectedListener = listener
    }
}
