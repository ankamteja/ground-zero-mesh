package org.groundzero.mesh.transport

import org.groundzero.mesh.propagation.NodeId
import java.util.PriorityQueue
import kotlin.random.Random

/**
 * A deterministic in-memory network for developing mesh logic without a device.
 *
 * Virtual clock (no wall-clock sleeps), configurable adjacency, fixed per-hop latency and
 * a uniform packet-loss rate. Develop against this first, then swap in a real
 * [Transport].
 */
class SimNetwork(
    val latencyMs: Long = 20,
    val lossRate: Double = 0.0,
    private val rng: Random = Random(42),
) {
    init {
        require(latencyMs >= 0) { "latencyMs must be >= 0" }
        require(lossRate in 0.0..1.0) { "lossRate must be 0..1" }
    }

    private var nowMs: Long = 0
    private var seq: Long = 0
    private val nodes = LinkedHashMap<NodeId, SimTransport>()
    private val adjacency = LinkedHashMap<NodeId, MutableSet<NodeId>>()
    private val queue = PriorityQueue<Scheduled>(compareBy({ it.at }, { it.seq }))

    private class Scheduled(val at: Long, val seq: Long, val run: () -> Unit)

    fun nowMs(): Long = nowMs

    /** Get (or create) the transport endpoint for a node. */
    fun transportFor(id: NodeId): SimTransport = nodes.getOrPut(id) { SimTransport(id, this) }

    fun link(a: NodeId, b: NodeId) {
        require(a != b) { "cannot link a node to itself" }
        adjacency.getOrPut(a) { linkedSetOf() }.add(b)
        adjacency.getOrPut(b) { linkedSetOf() }.add(a)
        transportFor(a); transportFor(b)
    }

    fun unlink(a: NodeId, b: NodeId) {
        adjacency[a]?.remove(b)
        adjacency[b]?.remove(a)
    }

    fun peersOf(id: NodeId): Set<NodeId> = adjacency[id]?.toSet() ?: emptySet()

    internal fun transmit(from: NodeId, to: NodeId?, frame: ByteArray) {
        val reachable = peersOf(from)
        val targets = if (to != null) listOf(to) else reachable.toList()
        for (t in targets) {
            if (t !in reachable) continue                       // no link -> silently dropped
            if (rng.nextDouble() < lossRate) continue           // radio loss
            val copy = frame.copyOf()
            val at = nowMs + latencyMs
            queue.add(Scheduled(at, seq++) { nodes[t]?.deliver(from, copy) })
        }
    }

    /** Advance virtual time by [byMs], running every delivery that comes due. */
    fun advance(byMs: Long) {
        require(byMs >= 0)
        val until = nowMs + byMs
        while (queue.isNotEmpty() && queue.peek().at <= until) {
            val s = queue.poll()
            nowMs = maxOf(nowMs, s.at)
            s.run()
        }
        nowMs = maxOf(nowMs, until)
    }

    /** Run until nothing more is scheduled (bounded by [maxAdvanceMs] of virtual time). */
    fun runUntilIdle(maxAdvanceMs: Long = 60_000) {
        val deadline = nowMs + maxAdvanceMs
        while (queue.isNotEmpty() && queue.peek().at <= deadline) {
            val s = queue.poll()
            nowMs = maxOf(nowMs, s.at)
            s.run()
        }
    }
}
