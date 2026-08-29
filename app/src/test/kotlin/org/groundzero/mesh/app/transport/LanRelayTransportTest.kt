package org.groundzero.mesh.app.transport

import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.PeerLiveness
import org.groundzero.mesh.transport.TcpRelayServer
import org.groundzero.mesh.transport.TcpTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [LanRelayTransport] has no Android dependency of its own — it is a thin wrapper around
 * `core`'s [TcpTransport] — so this runs the same way `core`'s own `TcpTransportTest` does:
 * real sockets, `localhost`, an ephemeral port. What this test covers that `core`'s does
 * not is the one thing this class actually adds: [PeerTable] bookkeeping riding on top of
 * the raw frame/connection events.
 */
class LanRelayTransportTest {

    private val relayId = NodeId(0xAAL)
    private val a = NodeId(1)

    private val teardowns = ArrayList<() -> Unit>()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @After
    fun tearDown() {
        teardowns.forEach { runCatching { it() } }
        teardowns.clear()
    }

    private fun awaitTrue(message: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(10)
        }
        assertTrue(message, check())
    }

    @Test
    fun `connecting to the relay populates the peer table`() {
        val port = freePort()
        val srv = TcpRelayServer(port, relayId).also { it.start() }
        teardowns += srv::stop
        val lan = LanRelayTransport(TcpTransport("127.0.0.1", port, a)).also { it.start() }
        teardowns += lan::stop

        awaitTrue("peer table gets the relay") { lan.peers.known() == listOf(relayId) }
        awaitTrue("liveness reads ALIVE for a just-connected peer") {
            lan.peers.liveness(relayId) == PeerLiveness.ALIVE
        }
    }

    @Test
    fun `onPeerConnected fires with the relay's id`() {
        val port = freePort()
        val srv = TcpRelayServer(port, relayId).also { it.start() }
        teardowns += srv::stop
        val lan = LanRelayTransport(TcpTransport("127.0.0.1", port, a))
        teardowns += lan::stop

        val connected = CountDownLatch(1)
        var seen: NodeId? = null
        lan.onPeerConnected { peer -> seen = peer; connected.countDown() }
        lan.start()

        assertTrue("onPeerConnected never fired", connected.await(2, TimeUnit.SECONDS))
        assertEquals(relayId, seen)
    }

    @Test
    fun `an inbound frame also refreshes the peer table, not just the connect event`() {
        val port = freePort()
        val srv = TcpRelayServer(port, relayId).also { it.start() }
        teardowns += srv::stop
        val lan = LanRelayTransport(TcpTransport("127.0.0.1", port, a)).also { it.start() }
        teardowns += lan::stop
        awaitTrue("connected") { lan.peers.known().isNotEmpty() }

        val received = CountDownLatch(1)
        lan.onReceive { _, _ -> received.countDown() }
        srv.send("hello".toByteArray(), to = a)

        assertTrue("frame never arrived", received.await(2, TimeUnit.SECONDS))
        assertEquals(PeerLiveness.ALIVE, lan.peers.liveness(relayId))
    }
}
