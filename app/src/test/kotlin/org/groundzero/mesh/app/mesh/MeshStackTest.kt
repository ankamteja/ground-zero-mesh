package org.groundzero.mesh.app.mesh

import org.groundzero.mesh.agent.NodeAgent
import org.groundzero.mesh.app.transport.GossipOriginTransport
import org.groundzero.mesh.propagation.Gossip
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import org.groundzero.mesh.transport.SimNetwork
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshStackTest {

    private val a = NodeId.parse("0000-0000-000a")
    private val b = NodeId.parse("0000-0000-000b")
    private val net = SimNetwork(latencyMs = 10)

    @After
    fun tearDown() = MeshStack.clear()

    private fun install() {
        net.link(a, b)
        val radio = net.transportFor(a)
        val gossip = Gossip(radio, clockMs = net::nowMs)
        radio.onReceive { from, frame -> MeshStack.ingest(frame, from) }
        radio.start()
        net.transportFor(b).start()
        MeshStack.install(
            gossip = gossip,
            agent = NodeAgent(
                nodeId = a,
                saltFingerprint = "0".repeat(32),
                addressZone = "unset",
                transport = GossipOriginTransport(radio, gossip),
                clockMs = net::nowMs,
            ),
            clockMs = net::nowMs,
        )
    }

    @Test
    fun `an uninstalled stack is inert, not broken`() {
        assertFalse(MeshStack.isInstalled)
        assertNull(MeshStack.raiseSos(Severity.DROWNING_IMMINENT))
        assertNull(MeshStack.heartbeatTick())
        assertNull(MeshStack.ingest(ByteArray(4), b))
        assertTrue(MeshStack.rankedBoard().isEmpty())
    }

    @Test
    fun `an SOS raised through the stack shows up on the board`() {
        install()
        assertNotNull(MeshStack.raiseSos(Severity.DROWNING_IMMINENT))
        net.runUntilIdle()

        val board = MeshStack.rankedBoard()
        assertEquals(1, board.size)
        assertEquals(a, board.single().cluster.origin)
        assertTrue(board.single().withinBudget)
    }

    @Test
    fun `clear leaves the stack inert again`() {
        install()
        MeshStack.raiseSos(Severity.DROWNING_IMMINENT)
        MeshStack.clear()

        assertFalse(MeshStack.isInstalled)
        assertTrue(MeshStack.rankedBoard().isEmpty())
    }
}
