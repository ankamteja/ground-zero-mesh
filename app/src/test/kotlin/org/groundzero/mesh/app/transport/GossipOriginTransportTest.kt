package org.groundzero.mesh.app.transport

import org.groundzero.mesh.agent.NodeAgent
import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.Gossip
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import org.groundzero.mesh.transport.SimNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Drives the decorator over a real [SimNetwork] rather than a mock, so the assertions are
 * about what actually crosses the wire.
 */
class GossipOriginTransportTest {

    private val a = NodeId.parse("0000-0000-000a")
    private val b = NodeId.parse("0000-0000-000b")
    private val salt = "0".repeat(32)

    private class Rig {
        lateinit var net: SimNetwork
        lateinit var gossipA: Gossip
        lateinit var gossipB: Gossip
        lateinit var agent: NodeAgent
        val framesAtB = ArrayList<ByteArray>()
    }

    private fun rig(): Rig = Rig().apply {
        net = SimNetwork(latencyMs = 10)
        net.link(a, b)
        val transportA = net.transportFor(a)
        val transportB = net.transportFor(b)
        gossipA = Gossip(transportA, clockMs = net::nowMs)
        gossipB = Gossip(transportB, clockMs = net::nowMs)
        transportA.onReceive { from, frame -> gossipA.ingest(frame, from) }
        transportB.onReceive { from, frame -> framesAtB += frame; gossipB.ingest(frame, from) }
        transportA.start()
        transportB.start()
        agent = NodeAgent(
            nodeId = a,
            saltFingerprint = salt,
            addressZone = "unset",
            transport = GossipOriginTransport(transportA, gossipA),
            clockMs = net::nowMs,
        )
    }

    @Test
    fun `an SOS crosses the wire exactly once`() {
        val r = rig()
        r.agent.raiseSos(Severity.DROWNING_IMMINENT)
        r.net.runUntilIdle()

        assertEquals(1, r.framesAtB.size)
    }

    @Test
    fun `the local node holds its own SOS in the cluster store`() {
        val r = rig()
        r.agent.raiseSos(Severity.DROWNING_IMMINENT)
        r.net.runUntilIdle()

        val local = r.gossipA.clusters().single()
        assertEquals(a, local.origin)
        assertEquals(Severity.DROWNING_IMMINENT, local.severity)
        // The origin never relayed it, so its own claim stays first-hand.
        assertEquals(EpistemologyTier.PRATYAKSA, local.tier)
        assertNotNull(r.gossipB.clusters().single())
    }

    @Test
    fun `an echo of the node's own report is not treated as news`() {
        val r = rig()
        r.agent.raiseSos(Severity.DROWNING_IMMINENT)
        r.net.runUntilIdle()

        // B relayed one hop back at A. A has the key marked seen from originate(), so it
        // suppresses instead of bouncing the report around the two-node loop forever.
        assertEquals(1, r.gossipA.suppressedDuplicates)
        assertEquals(0, r.gossipA.relayed)
        assertEquals(1, r.gossipA.clusters().size)
    }
}
