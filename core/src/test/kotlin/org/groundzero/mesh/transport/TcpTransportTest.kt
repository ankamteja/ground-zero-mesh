package org.groundzero.mesh.transport

import org.groundzero.mesh.propagation.NodeId
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real sockets on `localhost`, an ephemeral port per test — no fakes. This is the one
 * transport in the project a JVM test can actually drive end to end without a device: BLE
 * and LoRa are not available in a JVM, but TCP is.
 */
class TcpTransportTest {

    private val relayId = NodeId(0xAAL)
    private val a = NodeId(1)
    private val b = NodeId(2)

    private val started = ArrayList<Transport>()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Fast: reconnect/connect timeouts short enough that a real failure test doesn't stall. */
    private fun client(id: NodeId, port: Int, host: String = "127.0.0.1") =
        TcpTransport(host, port, id, reconnectDelayMs = 100, connectTimeoutMs = 500)
            .also { started += it }

    private fun server(port: Int) = TcpRelayServer(port, relayId).also { started += it }

    @AfterTest
    fun tearDown() {
        started.forEach { runCatching { it.stop() } }
        started.clear()
    }

    /** Polls up to 2s — real threads and real sockets need a moment, but never this long. */
    private fun awaitTrue(message: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(10)
        }
        assertTrue(check(), message)
    }

    @Test
    fun `the handshake tells each side the other's real NodeId`() {
        val port = freePort()
        val srv = server(port).also { it.start() }
        val cli = client(a, port).also { it.start() }

        awaitTrue("client learns the relay's id") { cli.knownPeers() == listOf(relayId) }
        awaitTrue("relay learns the client's id") { srv.knownPeers() == listOf(a) }
    }

    @Test
    fun `a frame from the client reaches the relay with the right sender`() {
        val port = freePort()
        val srv = server(port).also { it.start() }
        val cli = client(a, port).also { it.start() }
        awaitTrue("connected") { cli.knownPeers().isNotEmpty() }

        val received = CountDownLatch(1)
        var from: NodeId? = null
        var payload: ByteArray? = null
        srv.onReceive { f, frame -> from = f; payload = frame; received.countDown() }

        cli.send("hello".toByteArray())

        assertTrue(received.await(2, TimeUnit.SECONDS), "the relay never got the frame")
        assertEquals(a, from)
        assertEquals("hello", payload?.toString(Charsets.UTF_8))
    }

    @Test
    fun `a frame from the relay reaches the client`() {
        val port = freePort()
        val srv = server(port).also { it.start() }
        val cli = client(a, port).also { it.start() }
        awaitTrue("connected") { srv.knownPeers().isNotEmpty() }

        val received = CountDownLatch(1)
        var payload: ByteArray? = null
        cli.onReceive { _, frame -> payload = frame; received.countDown() }

        srv.send("hi from the relay".toByteArray(), to = a)

        assertTrue(received.await(2, TimeUnit.SECONDS))
        assertEquals("hi from the relay", payload?.toString(Charsets.UTF_8))
    }

    @Test
    fun `broadcast from the relay reaches every connected phone`() {
        val port = freePort()
        val srv = server(port).also { it.start() }
        val cliA = client(a, port).also { it.start() }
        val cliB = client(b, port).also { it.start() }
        awaitTrue("both connected") { srv.knownPeers().toSet() == setOf(a, b) }

        val gotA = CountDownLatch(1)
        val gotB = CountDownLatch(1)
        cliA.onReceive { _, _ -> gotA.countDown() }
        cliB.onReceive { _, _ -> gotB.countDown() }

        srv.send("beacon".toByteArray(), to = null)

        assertTrue(gotA.await(2, TimeUnit.SECONDS), "A never got the broadcast")
        assertTrue(gotB.await(2, TimeUnit.SECONDS), "B never got the broadcast")
    }

    @Test
    fun `two phones on the same relay never talk to each other directly`() {
        // The whole point of the star topology: A and B are both connected to the relay,
        // but knownPeers on either client-side transport only ever shows the relay, never
        // the other phone — there is no path between them except through it.
        val port = freePort()
        server(port).also { it.start() }
        val cliA = client(a, port).also { it.start() }
        val cliB = client(b, port).also { it.start() }
        awaitTrue("A connected") { cliA.knownPeers().isNotEmpty() }
        awaitTrue("B connected") { cliB.knownPeers().isNotEmpty() }

        assertEquals(listOf(relayId), cliA.knownPeers())
        assertEquals(listOf(relayId), cliB.knownPeers())
    }

    @Test
    fun `a client started before the relay exists connects once the relay starts`() {
        val port = freePort()
        val cli = client(a, port).also { it.start() }
        Thread.sleep(150) // at least one failed connect attempt happens first

        val srv = server(port).also { it.start() }

        awaitTrue("connects once the relay is up") { cli.knownPeers().isNotEmpty() }
        awaitTrue("relay sees it too") { srv.knownPeers() == listOf(a) }
    }

    @Test
    fun `stopping the relay is visible to a connected client as a dropped connection`() {
        val port = freePort()
        val srv = server(port).also { it.start() }
        val cli = client(a, port).also { it.start() }
        awaitTrue("connected") { cli.knownPeers().isNotEmpty() }

        srv.stop()

        awaitTrue("client notices the connection is gone") { cli.knownPeers().isEmpty() }
    }

    @Test
    fun `an oversized frame is rejected rather than silently truncated`() {
        val port = freePort()
        server(port).also { it.start() }
        val cli = client(a, port).also { it.start() }
        awaitTrue("connected") { cli.knownPeers().isNotEmpty() }

        assertFailsWith<IllegalArgumentException> {
            cli.send(ByteArray(cli.maxFrameBytes + 1))
        }
    }

    @Test
    fun `a second phone connecting later does not disturb the first`() {
        val port = freePort()
        val srv = server(port).also { it.start() }
        val cliA = client(a, port).also { it.start() }
        awaitTrue("A connected") { srv.knownPeers().contains(a) }

        val aFrames = CopyOnWriteArrayList<ByteArray>()
        cliA.onReceive { _, frame -> aFrames.add(frame) }

        client(b, port).start()
        awaitTrue("B connected too") { srv.knownPeers().toSet() == setOf(a, b) }

        srv.send("only for A".toByteArray(), to = a)
        awaitTrue("A got its targeted frame") { aFrames.isNotEmpty() }
        assertEquals("only for A", aFrames.single().toString(Charsets.UTF_8))
    }
}
