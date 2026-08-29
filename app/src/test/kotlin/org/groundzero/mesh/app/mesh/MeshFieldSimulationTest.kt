package org.groundzero.mesh.app.mesh

import org.groundzero.mesh.agent.NodeAgent
import org.groundzero.mesh.agent.SensoryWindow
import org.groundzero.mesh.app.transport.GossipOriginTransport
import org.groundzero.mesh.app.transport.StoreAndForward
import org.groundzero.mesh.gateway.ResponderRanking
import org.groundzero.mesh.propagation.DedupCluster
import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.Gossip
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import org.groundzero.mesh.transport.SimNetwork
import org.groundzero.mesh.transport.SimTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three-phone field test, minus the phones.
 *
 * The real gate is three devices in three rooms: A presses SOS, B relays from outside A's
 * range of C, C serves the board to a laptop on its hotspot. **That test has not been run —
 * there is no Android hardware on this machine, and Nearby Connections cannot work between
 * emulators because there are no radios to drive.** This stands in for it by wiring the same
 * components the service wires, over `SimNetwork` instead of a radio.
 *
 * What it therefore does and does not prove: the mesh logic, the store-and-forward replay and
 * the ranking are exercised end to end; Nearby's discovery, permission matrix and connection
 * lifecycle are not exercised at all, and those are where the demo is most likely to die.
 */
class MeshFieldSimulationTest {

    private val a = NodeId.parse("0000-0000-000a")
    private val b = NodeId.parse("0000-0000-000b")
    private val c = NodeId.parse("0000-0000-000c")
    private val salt = "0".repeat(32)

    /** One device: gossip over its radio, an outbox, and the same wiring the service does. */
    private class Device(
        val id: NodeId,
        net: SimNetwork,
        clock: () -> Long,
        salt: String,
        zone: String,
    ) {
        val transport: SimTransport = net.transportFor(id)
        val clusters = DedupCluster()
        val store = StoreAndForward(clock = clock)
        val gossip = Gossip(transport, clusters, clockMs = clock)
        val agent = NodeAgent(
            nodeId = id,
            saltFingerprint = salt,
            addressZone = zone,
            transport = GossipOriginTransport(transport, gossip) { envelope, frame ->
                store.offer(envelope.addressZone, envelope.dedupKey, frame)
            },
            clockMs = clock,
        )

        init {
            transport.onReceive { from, frame ->
                gossip.ingest(frame, from)?.let { store.offer(it.addressZone, it.dedupKey, frame) }
            }
            transport.start()
        }

        /** What the service does when a peer reconnects. */
        fun replayTo(peer: NodeId) = store.drainAll().forEach { transport.send(it, peer) }
    }

    @Test
    fun `an SOS two hops out reaches the gateway board as one testimony incident`() {
        val net = SimNetwork(latencyMs = 20)
        val clock = { net.nowMs() }
        net.link(a, b)
        net.link(b, c)
        // A and C are deliberately not linked: the gateway can only hear A through B.

        val victim = Device(a, net, clock, salt, "sector-7-roof")
        Device(b, net, clock, salt, "stairwell-3")
        val gateway = Device(c, net, clock, salt, "perimeter")

        victim.agent.raiseSos(Severity.DROWNING_IMMINENT)
        net.runUntilIdle()
        net.advance(2_000)
        victim.agent.completeSensoryWindow(SensoryWindow(audioWater = 0.9, imuPinned = 0.8))
        net.runUntilIdle()

        val board = ResponderRanking.rank(gateway.clusters.clusters(), net.nowMs())
        assertEquals("one person is one incident, two broadcasts and two hops", 1, board.size)

        val incident = board.single().cluster
        assertEquals(a, incident.origin)
        assertEquals(Severity.DROWNING_IMMINENT, incident.severity)
        assertEquals(
            "the gateway never heard the victim directly",
            EpistemologyTier.SABDA,
            incident.tier,
        )
        assertTrue(board.single().withinBudget)
        assertNotNull("the enriched broadcast arrived too", incident.slmSummary)
    }

    @Test
    fun `a relay that was out of range gets what it missed when it comes back`() {
        val net = SimNetwork(latencyMs = 20)
        val clock = { net.nowMs() }
        net.link(a, b)
        net.link(b, c)

        val victim = Device(a, net, clock, salt, "sector-7-roof")
        val relay = Device(b, net, clock, salt, "stairwell-3")
        val gateway = Device(c, net, clock, salt, "perimeter")

        // C drops out — the responder walked around the building.
        net.unlink(b, c)

        victim.agent.raiseSos(Severity.DROWNING_IMMINENT)
        net.runUntilIdle()

        assertTrue(
            "with the link down the gateway must know nothing",
            gateway.clusters.clusters().isEmpty(),
        )

        // C comes back. B replays its outbox to it, exactly as the service does on reconnect.
        net.link(b, c)
        relay.replayTo(c)
        net.runUntilIdle()

        assertEquals(
            "the missed report must arrive on reconnect, not be lost with the link",
            1,
            gateway.clusters.clusters().size,
        )
        assertEquals(a, gateway.clusters.clusters().single().origin)
    }

    @Test
    fun `a replay of what the gateway already holds does not double-count it`() {
        val net = SimNetwork(latencyMs = 20)
        val clock = { net.nowMs() }
        net.link(a, b)
        net.link(b, c)

        val victim = Device(a, net, clock, salt, "sector-7-roof")
        val relay = Device(b, net, clock, salt, "stairwell-3")
        val gateway = Device(c, net, clock, salt, "perimeter")

        victim.agent.raiseSos(Severity.DROWNING_IMMINENT)
        net.runUntilIdle()

        val before = gateway.clusters.clusters().single()
        relay.replayTo(c)
        net.runUntilIdle()

        val after = gateway.clusters.clusters().single()
        assertEquals("a replay is not a second casualty", before.key, after.key)
        assertEquals(1, gateway.clusters.size)
    }
}
