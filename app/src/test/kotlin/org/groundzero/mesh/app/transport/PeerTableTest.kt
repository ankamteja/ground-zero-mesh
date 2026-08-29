package org.groundzero.mesh.app.transport

import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Peer
import org.groundzero.mesh.propagation.PeerLiveness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTableTest {

    private var now = 0L
    private val table = PeerTable { now }
    private val a = NodeId(1)

    @Test
    fun inboundAddsAlivePeer() {
        table.sawInbound(a, "ep-a")
        assertEquals(PeerLiveness.ALIVE, table.liveness(a))
        assertEquals(listOf(a), table.alive())
    }

    @Test
    fun silenceBecomesSilentNeverGoneNeverDropped() {
        table.sawInbound(a, "ep-a")
        now += Peer.SILENT_AFTER_MS
        assertEquals(PeerLiveness.SILENT, table.liveness(a))
        now += Peer.SILENT_AFTER_MS * 100
        assertEquals(PeerLiveness.SILENT, table.liveness(a))
        assertTrue("peer row must survive silence", a in table.known())
    }

    @Test
    fun goneOnlyOnExplicitSignal() {
        table.sawInbound(a, "ep-a")
        table.markGone(a)
        assertEquals(PeerLiveness.GONE, table.liveness(a))
    }

    @Test
    fun decayNeverRemovesRows() {
        table.sawInbound(a, "ep-a")
        repeat(50) { table.decayTick() }
        assertTrue(a in table.known())
    }

    @Test
    fun unknownPeerHasNoLiveness() {
        assertNull(table.liveness(NodeId(999)))
    }
}
