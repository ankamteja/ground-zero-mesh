package org.groundzero.mesh.app.transport

import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Peer
import org.groundzero.mesh.propagation.PeerLiveness
import java.util.concurrent.ConcurrentHashMap

/**
 * The live peer table for a running transport. Thin state on top of `core`'s [Peer]:
 *
 * - peers are **never removed on silence** — they decay toward neutral and go
 *   [PeerLiveness.SILENT], which is recoverable
 * - [PeerLiveness.GONE] is only ever set by an explicit signal ([markGone]), never a timer
 * - health rule (`fail < 5 || ok > 0`) lives in [Peer]
 *
 * All methods are safe to call from Nearby's callback threads.
 */
class PeerTable(private val clock: () -> Long = System::currentTimeMillis) {

    private val peers = ConcurrentHashMap<NodeId, Peer>()

    fun sawInbound(nodeId: NodeId, address: String) {
        peers.compute(nodeId) { _, existing ->
            (existing ?: Peer(nodeId, address, clock())).copy(address = address).sawInbound(clock())
        }
    }

    fun sendFailed(nodeId: NodeId) {
        peers.computeIfPresent(nodeId) { _, p -> p.sendFailed() }
    }

    /** An explicit report that this peer is gone — a shutdown notice, not a timeout. */
    fun markGone(nodeId: NodeId) {
        peers.computeIfPresent(nodeId) { _, p -> p.markGone() }
    }

    /** Periodic tick — pulls trust counters toward neutral. Never drops a row. */
    fun decayTick() {
        peers.replaceAll { _, p -> p.decayedToward(0) }
    }

    fun liveness(nodeId: NodeId): PeerLiveness? = peers[nodeId]?.liveness(clock())

    fun snapshot(): List<Peer> = peers.values.toList()

    /** NodeIds currently [PeerLiveness.ALIVE] — the ones a broadcast should target. */
    fun alive(): List<NodeId> {
        val now = clock()
        return peers.values.filter { it.liveness(now) == PeerLiveness.ALIVE }.map { it.nodeId }
    }

    fun known(): List<NodeId> = peers.keys.toList()
}
