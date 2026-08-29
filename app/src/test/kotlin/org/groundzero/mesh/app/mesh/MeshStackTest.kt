package org.groundzero.mesh.app.mesh

import org.groundzero.mesh.agent.NodeAgent
import org.groundzero.mesh.app.node.MeshRole
import org.groundzero.mesh.app.transport.GossipOriginTransport
import org.groundzero.mesh.app.transport.PeerTable
import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.Gossip
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.PeerLiveness
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

    private fun install(peers: PeerTable? = null) {
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
            peers = peers,
        )
    }

    /** A frame from b, encoded exactly as b's own transport would put it on the wire. */
    private fun frameFromB(severity: Severity = Severity.STRUCTURAL_ENTRAPMENT): ByteArray {
        val sent = ArrayList<ByteArray>()
        val capture = object : org.groundzero.mesh.transport.Transport {
            override val localId = b
            override val maxFrameBytes = net.transportFor(b).maxFrameBytes
            override fun start() = Unit
            override fun stop() = Unit
            override fun send(frame: ByteArray, to: NodeId?) { sent += frame }
            override fun onReceive(listener: (NodeId, ByteArray) -> Unit) = Unit
            override fun knownPeers(): List<NodeId> = emptyList()
        }
        NodeAgent(
            nodeId = b,
            saltFingerprint = "0".repeat(32),
            addressZone = "floor-2-east",
            transport = capture,
            clockMs = net::nowMs,
        ).raiseSos(severity)
        return sent.single()
    }

    @Test
    fun `an uninstalled stack is inert, not broken`() {
        assertFalse(MeshStack.isInstalled)
        assertNull(MeshStack.raiseSos(Severity.DROWNING_IMMINENT))
        assertNull(MeshStack.heartbeatTick())
        assertNull(MeshStack.ingest(ByteArray(4), b))
        assertTrue(MeshStack.rankedBoard().isEmpty())
        assertTrue(MeshStack.recentActivity().isEmpty())
        assertEquals(MeshActivityCounts(0, 0, 0, 0, 0), MeshStack.activityCounts())
    }

    @Test
    fun `a new frame is logged with what the envelope actually said`() {
        install()
        MeshStack.ingest(frameFromB(Severity.DROWNING_IMMINENT), b)

        val entry = MeshStack.recentActivity().single()
        assertEquals(MeshActivityOutcome.RECEIVED_NEW, entry.outcome)
        assertEquals(b, entry.from)
        assertEquals("floor-2-east", entry.zone)
        assertEquals(Severity.DROWNING_IMMINENT, entry.severity)
        // It crossed a radio, so this node holds testimony however the sender stamped it.
        assertEquals(EpistemologyTier.SABDA, entry.effectiveTier)
    }

    @Test
    fun `a repeat of the same frame is logged as a duplicate, not as news`() {
        install()
        val frame = frameFromB()
        MeshStack.ingest(frame, b)
        MeshStack.ingest(frame, b)

        val outcomes = MeshStack.recentActivity().map { it.outcome }
        assertEquals(
            listOf(MeshActivityOutcome.RECEIVED_NEW, MeshActivityOutcome.DUPLICATE),
            outcomes,
        )
        // A duplicate is not decoded twice, so it carries no zone to show.
        assertNull(MeshStack.recentActivity().last().zone)
        assertEquals(1, MeshStack.activityCounts().duplicates)
    }

    @Test
    fun `an undecodable frame is logged as dropped rather than lost silently`() {
        install()
        MeshStack.ingest(byteArrayOf(9, 9, 9, 9), b)

        val entry = MeshStack.recentActivity().single()
        assertEquals(MeshActivityOutcome.DROPPED, entry.outcome)
        assertEquals(1, MeshStack.activityCounts().dropped)
    }

    @Test
    fun `the activity log is capped so a long-running relay cannot grow it forever`() {
        install()
        repeat(MeshStack.ACTIVITY_LOG_CAPACITY + 10) {
            MeshStack.ingest(byteArrayOf(9, 9, 9, 9), b)
        }
        assertEquals(MeshStack.ACTIVITY_LOG_CAPACITY, MeshStack.recentActivity().size)
        // The counter is not capped — it is the honest total, not a window.
        assertEquals(MeshStack.ACTIVITY_LOG_CAPACITY + 10, MeshStack.activityCounts().received)
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
    fun `only a node originates`() {
        install()
        MeshStack.setRole(MeshRole.RELAY)
        assertNull(MeshStack.raiseSos(Severity.DROWNING_IMMINENT))
        assertNull(MeshStack.heartbeatTick())

        MeshStack.setRole(MeshRole.GATEWAY)
        assertNull(MeshStack.raiseSos(Severity.DROWNING_IMMINENT))

        MeshStack.setRole(MeshRole.NODE)
        assertNotNull(MeshStack.raiseSos(Severity.DROWNING_IMMINENT))
    }

    @Test
    fun `a relay still carries other people's reports`() {
        install()
        MeshStack.setRole(MeshRole.RELAY)
        net.runUntilIdle()

        // A report from elsewhere, arriving on the radio. A relay must still hold and pass it.
        val elsewhere = NodeAgent(
            nodeId = b,
            saltFingerprint = "0".repeat(32),
            addressZone = "floor-2-east",
            transport = net.transportFor(b),
            clockMs = net::nowMs,
        )
        elsewhere.raiseSos(Severity.STRUCTURAL_ENTRAPMENT)
        net.runUntilIdle()

        assertEquals(1, MeshStack.rankedBoard().size)
    }

    @Test
    fun `the role change listener fires outside the stack's lock`() {
        install()
        val seen = ArrayList<MeshRole>()
        MeshStack.onRoleChange { role ->
            // Re-entering the stack from the listener would deadlock if it held the lock.
            MeshStack.currentRole()
            seen += role
        }
        MeshStack.setRole(MeshRole.RELAY)
        MeshStack.setRole(MeshRole.RELAY)
        MeshStack.setRole(MeshRole.GATEWAY)

        assertEquals(listOf(MeshRole.RELAY, MeshRole.GATEWAY), seen)
    }

    @Test
    fun `markPeerFound reaches this device's own peer table`() {
        val table = PeerTable(clock = net::nowMs)
        table.sawInbound(b, "addr")
        install(peers = table)

        MeshStack.markPeerFound(b)

        assertEquals(PeerLiveness.GONE, table.liveness(b))
    }

    @Test
    fun `markPeerFound on an uninstalled stack is a safe no-op`() {
        MeshStack.markPeerFound(b)
    }

    @Test
    fun `a GPS fix reaches the agent through the stack`() {
        install()
        MeshStack.updateGpsFix(12.9716f, 77.5946f)

        val envelope = MeshStack.raiseSos(Severity.DROWNING_IMMINENT)

        assertEquals(12.9716f, envelope?.gpsLat)
        assertEquals(77.5946f, envelope?.gpsLon)
    }

    @Test
    fun `a GPS fix on an uninstalled stack is a safe no-op`() {
        MeshStack.updateGpsFix(12.9716f, 77.5946f)
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
