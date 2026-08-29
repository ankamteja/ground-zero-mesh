package org.groundzero.mesh.propagation

import org.groundzero.mesh.transport.SimNetwork
import org.groundzero.mesh.transport.SimTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Multi-hop propagation over [SimNetwork]: hop counts across line and diamond topologies,
 * TTL bounding, partition behaviour, and dedup of one incident arriving by two paths.
 *
 * `SimTransportTest` covers the transport as a byte pipe, which is the right scope for it.
 * These tests cover the layer above — what happens to an *envelope* as it crosses several
 * links — which is where hop counting, TTL exhaustion and the first-hand gate actually
 * live. HANDOVER §0 asks for exactly this: "unit tests over fully-connected / line /
 * partitioned topologies asserting hop counts".
 */
class MeshPropagationTest {

    private fun id(n: Int) = NodeId(n.toLong())

    private fun sos(origin: NodeId, ttl: Int = 15) = Envelope(
        nodeId = origin,
        saltFingerprint = "0123456789abcdef0123456789abcdef",
        addressZone = "sector-7-roof",
        tier = EpistemologyTier.PRATYAKSA,
        severity = Severity.DROWNING_IMMINENT,
        dangerScore = 1.0,
        timestamp = 1_724_900_000L,
        ttl = ttl,
    )

    /**
     * A minimal flooding relay, for tests only.
     *
     * It forwards every envelope it has not seen before, and dedup by [Envelope.dedupKey]
     * is the only thing stopping it looping forever. Phase 3's real `Gossip` is selective,
     * trust-weighted and silent on quiet ticks; this exists so the hop and TTL mechanics
     * can be exercised before that lands.
     */
    private class Relay(val transport: SimTransport) {
        val held = ArrayList<Envelope>()
        val duplicatesSuppressed = ArrayList<Envelope>()
        private val seen = HashSet<String>()
        private val codec = Codecs.forFrameBudget(transport.maxFrameBytes)

        init {
            transport.start()
            transport.onReceive { _, frame ->
                ingest(codec.decode(frame).asReceived())
            }
        }

        fun originate(envelope: Envelope) {
            seen += envelope.dedupKey
            transport.send(codec.encode(envelope))
        }

        private fun ingest(envelope: Envelope) {
            if (!seen.add(envelope.dedupKey)) {
                duplicatesSuppressed += envelope
                return
            }
            held += envelope
            if (envelope.ttl > 0) transport.send(codec.encode(envelope.forwarded()))
        }
    }

    private fun line(count: Int, latencyMs: Long = 5): Pair<SimNetwork, List<Relay>> {
        val net = SimNetwork(latencyMs = latencyMs)
        val ids = (1..count).map { id(it) }
        for (i in 0 until count - 1) net.link(ids[i], ids[i + 1])
        return net to ids.map { Relay(net.transportFor(it)) }
    }

    @Test
    fun `hop count grows with distance along a line`() {
        val (net, relays) = line(4)
        relays[0].originate(sos(id(1)))
        net.runUntilIdle()

        assertEquals(1, relays[1].held.single().hops, "B is one link from A")
        assertEquals(2, relays[2].held.single().hops, "C is two links from A")
        assertEquals(3, relays[3].held.single().hops, "D is three links from A")
    }

    @Test
    fun `everyone downstream of the origin holds testimony, not first-hand`() {
        val (net, relays) = line(3)
        relays[0].originate(sos(id(1)))
        net.runUntilIdle()

        for (i in 1..2) {
            assertEquals(
                EpistemologyTier.SABDA, relays[i].held.single().effectiveTier,
                "node " + i + " was told, not shown",
            )
            assertEquals(
                EpistemologyTier.PRATYAKSA, relays[i].held.single().tier,
                "the origin's own claim survives the trip intact",
            )
        }
    }

    @Test
    fun `gossip is bounded by ttl, not by the size of the network`() {
        val (net, relays) = line(6)
        relays[0].originate(sos(id(1), ttl = 2))
        net.runUntilIdle()

        // TTL counts *forwards*, not links. The origin's own first transmission does not
        // spend any, so a TTL of n reaches n+1 links out. Worth pinning explicitly: this
        // is the off-by-one that decides whether a TTL chosen in the field actually covers
        // the building it was meant to.
        assertEquals(1, relays[1].held.size, "one link out")
        assertEquals(1, relays[2].held.size, "two links out, one forward spent")
        assertEquals(1, relays[3].held.size, "three links out, both forwards spent")
        assertEquals(0, relays[4].held.size, "ttl exhausted, the report stops here")

        assertEquals(0, relays[3].held.single().ttl, "arrived with nothing left to spend")
        assertEquals(3, relays[3].held.single().hops)
    }

    @Test
    fun `a partition is a partition`() {
        val (net, relays) = line(4)
        net.unlink(id(2), id(3))
        relays[0].originate(sos(id(1)))
        net.runUntilIdle()

        assertEquals(1, relays[1].held.size, "still reachable")
        assertEquals(0, relays[2].held.size, "on the far side of the break")
        assertEquals(0, relays[3].held.size)
    }

    @Test
    fun `one incident arriving by two paths is not counted twice`() {
        // The diamond: A reaches D through B and through C. This is the shape that
        // produces duplicate alerts for a single trapped person if dedup is wrong, and it
        // is the logical form of the three-phone A-B-C field test.
        val net = SimNetwork(latencyMs = 5)
        net.link(id(1), id(2)); net.link(id(1), id(3))
        net.link(id(2), id(4)); net.link(id(3), id(4))
        val relays = (1..4).map { Relay(net.transportFor(id(it))) }

        relays[0].originate(sos(id(1)))
        net.runUntilIdle()

        assertEquals(1, relays[3].held.size, "D holds one incident, not two")
        assertTrue(
            relays[3].duplicatesSuppressed.isNotEmpty(),
            "and the second path really did deliver a copy that dedup absorbed",
        )
    }

    @Test
    fun `the refined stage three broadcast updates the same incident`() {
        // Addendum §2: the enriched envelope reuses the original incident timestamp so
        // downstream layers update the existing cluster instead of raising a second alert.
        val stage1 = sos(id(1))
        val stage3 = stage1.copy(
            dangerScore = 0.82,
            slmSummary = "AUDIO:RUSHING_WATER|IMU:PINNED",
        )
        assertEquals(stage1.dedupKey, stage3.dedupKey)
    }
}
