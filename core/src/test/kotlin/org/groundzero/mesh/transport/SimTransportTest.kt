package org.groundzero.mesh.transport

import org.groundzero.mesh.propagation.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimTransportTest {

    private val a = NodeId(1)
    private val b = NodeId(2)
    private val c = NodeId(3)

    @Test
    fun linkedNodesExchangeAFrameAcrossVirtualTime() {
        val net = SimNetwork(latencyMs = 20)
        net.link(a, b)
        val ta = net.transportFor(a).also { it.start() }
        val tb = net.transportFor(b).also { it.start() }

        var got: ByteArray? = null
        var from: NodeId? = null
        tb.onReceive { f, frame -> from = f; got = frame }

        ta.send("hello".toByteArray())
        assertNull(got, "delivery is scheduled, not immediate")

        net.advance(20)
        assertEquals("hello", got?.toString(Charsets.UTF_8))
        assertEquals(a, from)
    }

    @Test
    fun totalLossDropsEverything() {
        val net = SimNetwork(latencyMs = 5, lossRate = 1.0)
        net.link(a, b)
        val ta = net.transportFor(a).also { it.start() }
        val tb = net.transportFor(b).also { it.start() }
        var got: ByteArray? = null
        tb.onReceive { _, frame -> got = frame }

        ta.send("x".toByteArray())
        net.runUntilIdle()
        assertNull(got)
    }

    @Test
    fun unlinkedNodesDoNotDeliver() {
        val net = SimNetwork()
        net.link(a, b) // c is isolated
        val ta = net.transportFor(a).also { it.start() }
        val tc = net.transportFor(c).also { it.start() }
        var got: ByteArray? = null
        tc.onReceive { _, frame -> got = frame }

        ta.send("x".toByteArray(), to = c)
        net.runUntilIdle()
        assertNull(got)
    }

    @Test
    fun broadcastReachesEveryLinkedPeer() {
        val net = SimNetwork(latencyMs = 1)
        net.link(a, b); net.link(a, c)
        val ta = net.transportFor(a).also { it.start() }
        val tb = net.transportFor(b).also { it.start() }
        val tc = net.transportFor(c).also { it.start() }
        var nb = 0; var nc = 0
        tb.onReceive { _, _ -> nb++ }
        tc.onReceive { _, _ -> nc++ }

        ta.send("beacon".toByteArray(), to = null)
        net.runUntilIdle()
        assertEquals(1, nb)
        assertEquals(1, nc)
        assertTrue(a in net.peersOf(b))
    }

    @Test
    fun sendBeforeStartFails() {
        val net = SimNetwork()
        net.link(a, b)
        assertFailsWith<IllegalStateException> { net.transportFor(a).send("x".toByteArray()) }
    }

    @Test
    fun knownPeersReflectsAdjacency() {
        val net = SimNetwork()
        net.link(a, b); net.link(a, c)
        assertEquals(setOf(b, c), net.transportFor(a).knownPeers().toSet())
    }
}
