package org.groundzero.mesh.transport

import org.groundzero.mesh.propagation.Codecs
import org.groundzero.mesh.propagation.Envelope
import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.Gossip
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Genuine multi-hop over real sockets: several relay *processes-worth* of wiring, each with
 * its own `Gossip`, chained by [CompositeTransport] the way `TcpRelayMain --link` chains
 * them.
 *
 * `TcpTransportTest` covers one hub — the star. This covers the thing a star cannot do: a
 * report crossing relay after relay, gaining a hop and spending TTL at each, which is the
 * only reason the hop count on a responder's board means anything.
 *
 * Everything here is a real socket on `localhost` with an ephemeral port. Nothing is faked,
 * because the failure this is guarding against — a frame that stops at the first relay — is
 * precisely what a fake would paper over.
 */
class RelayChainTest {

    private val victim = NodeId(0x01)
    private val responder = NodeId(0x02)

    private val started = ArrayList<Transport>()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun client(id: NodeId, port: Int) =
        TcpTransport("127.0.0.1", port, id, reconnectDelayMs = 100, connectTimeoutMs = 500)
            .also { started += it }

    @AfterTest
    fun tearDown() {
        started.forEach { runCatching { it.stop() } }
        started.clear()
    }

    private fun awaitTrue(message: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(10)
        }
        assertTrue(check(), message)
    }

    /**
     * One relay node: a hub of its own, optionally dialling the relay before it in the chain.
     *
     * Mirrors `TcpRelayMain` exactly — a bare [Gossip] over the transport, no `NodeAgent`,
     * and the same lock, because a relay only ever carries.
     */
    private inner class Relay(val id: NodeId, val port: Int, linkTo: Int? = null) {
        val server = TcpRelayServer(port, id).also { started += it }
        private val link = linkTo?.let { client(id, it) }
        val transport: Transport =
            if (link == null) server else CompositeTransport(id, listOf(server, link))
        val gossip = Gossip(transport, clockMs = System::currentTimeMillis)
        private val lock = Any()

        init {
            transport.onReceive { from, frame -> synchronized(lock) { gossip.ingest(frame, from) } }
        }

        fun start() = transport.start()
        fun relayed() = synchronized(lock) { gossip.relayed }
        fun duplicates() = synchronized(lock) { gossip.suppressedDuplicates }
        fun held() = synchronized(lock) { gossip.clusters().toList() }
    }

    private fun sos(ttl: Int = 8) = Envelope(
        nodeId = victim,
        saltFingerprint = "a".repeat(32),
        addressZone = "unset",
        tier = EpistemologyTier.PRATYAKSA,
        severity = Severity.STRUCTURAL_ENTRAPMENT,
        dangerScore = 1.0,
        timestamp = 1_700_000_000L,
        hops = 0,
        ttl = ttl,
    )

    /** What a phone would put on the wire: the codec the transport's budget selects. */
    private fun encode(envelope: Envelope, transport: Transport): ByteArray =
        Codecs.forFrameBudget(transport.maxFrameBytes).encode(envelope)

    // ------------------------------------------------------------------- the whole point

    @Test
    fun `an sos crosses two chained relays and arrives with a hop per link`() {
        val portA = freePort()
        val portB = freePort()

        val relayA = Relay(NodeId(0xA1), portA)
        val relayB = Relay(NodeId(0xB1), portB, linkTo = portA)
        relayA.start()
        relayB.start()
        awaitTrue("relay B links to relay A") { relayA.server.knownPeers().contains(NodeId(0xB1)) }

        // The victim hangs off relay A; the responder off relay B. There is exactly one path
        // between them, and it runs through both relays.
        val victimLink = client(victim, portA).also { it.start() }
        val responderLink = client(responder, portB).also { it.start() }
        awaitTrue("both endpoints connected") {
            relayA.server.knownPeers().contains(victim) &&
                relayB.server.knownPeers().contains(responder)
        }

        val delivered = ArrayList<Envelope>()
        responderLink.onReceive { _, frame ->
            synchronized(delivered) {
                delivered += Codecs.forFrameBudget(responderLink.maxFrameBytes).decode(frame)
            }
        }

        victimLink.send(encode(sos(), victimLink))

        awaitTrue("the report reaches the responder two relays away") {
            synchronized(delivered) { delivered.isNotEmpty() }
        }

        val arrived = synchronized(delivered) { delivered.first() }
        assertEquals(
            3, arrived.hops,
            "victim -> A -> B -> responder is three links; hops must say so",
        )
        assertEquals(
            EpistemologyTier.SABDA, arrived.effectiveTier,
            "anything that crossed a relay is testimony, never first-hand",
        )
        assertEquals(
            EpistemologyTier.PRATYAKSA, arrived.tier,
            "the victim's own claim survives the trip intact",
        )
        assertTrue(relayA.relayed() >= 1, "relay A forwarded")
        assertTrue(relayB.relayed() >= 1, "relay B forwarded")
    }

    @Test
    fun `a three-relay chain keeps counting, and ttl is what stops it`() {
        val ports = listOf(freePort(), freePort(), freePort())
        val relays = listOf(
            Relay(NodeId(0xA1), ports[0]),
            Relay(NodeId(0xB1), ports[1], linkTo = ports[0]),
            Relay(NodeId(0xC1), ports[2], linkTo = ports[1]),
        )
        relays.forEach { it.start() }
        awaitTrue("the chain is linked end to end") {
            relays[0].server.knownPeers().contains(NodeId(0xB1)) &&
                relays[1].server.knownPeers().contains(NodeId(0xC1))
        }

        val victimLink = client(victim, ports[0]).also { it.start() }
        val responderLink = client(responder, ports[2]).also { it.start() }
        awaitTrue("endpoints connected") {
            relays[0].server.knownPeers().contains(victim) &&
                relays[2].server.knownPeers().contains(responder)
        }

        val delivered = ArrayList<Envelope>()
        responderLink.onReceive { _, frame ->
            synchronized(delivered) {
                delivered += Codecs.forFrameBudget(responderLink.maxFrameBytes).decode(frame)
            }
        }

        victimLink.send(encode(sos(), victimLink))
        awaitTrue("the report crosses all three relays") {
            synchronized(delivered) { delivered.isNotEmpty() }
        }

        val arrived = synchronized(delivered) { delivered.first() }
        assertEquals(4, arrived.hops, "four links out from the victim")
        // TTL counts *forwards*, not links — the victim's own transmission spends none, so
        // three relays cost three. The same off-by-one MeshPropagationTest pins over
        // SimNetwork, re-pinned here over real sockets because this is the number a TTL
        // chosen in the field is judged by.
        assertEquals(5, arrived.ttl, "three relays forwarded, so a ttl of 8 arrives as 5")
    }

    @Test
    fun `a report dies where its ttl runs out rather than crossing the whole chain`() {
        // The property that makes reach a design parameter instead of a function of how many
        // relays someone happened to plug in. With ttl = 1 the report may be forwarded once.
        val ports = listOf(freePort(), freePort(), freePort())
        val relays = listOf(
            Relay(NodeId(0xA1), ports[0]),
            Relay(NodeId(0xB1), ports[1], linkTo = ports[0]),
            Relay(NodeId(0xC1), ports[2], linkTo = ports[1]),
        )
        relays.forEach { it.start() }
        awaitTrue("chain linked") {
            relays[0].server.knownPeers().contains(NodeId(0xB1)) &&
                relays[1].server.knownPeers().contains(NodeId(0xC1))
        }

        val victimLink = client(victim, ports[0]).also { it.start() }
        val responderLink = client(responder, ports[2]).also { it.start() }
        awaitTrue("endpoints connected") {
            relays[0].server.knownPeers().contains(victim) &&
                relays[2].server.knownPeers().contains(responder)
        }

        var reachedResponder = false
        responderLink.onReceive { _, _ -> reachedResponder = true }

        victimLink.send(encode(sos(ttl = 1), victimLink))

        awaitTrue("relay B holds it") { relays[1].held().isNotEmpty() }
        Thread.sleep(300)

        assertTrue(relays[1].held().isNotEmpty(), "one forward was affordable, so B has it")
        assertTrue(relays[2].held().isEmpty(), "C is past the ttl and must never see it")
        assertTrue(!reachedResponder, "the responder is beyond the report's reach")
    }

    @Test
    fun `a ring does not become an echo storm`() {
        // Relay A and relay B dial each other, so every frame arrives back where it started.
        // TcpRelayServer echoes to the connection a frame came in on by design, so this is the
        // shape that would loop forever if Gossip's dedup were not carrying the whole load.
        val portA = freePort()
        val portB = freePort()
        val relayA = Relay(NodeId(0xA1), portA, linkTo = portB)
        val relayB = Relay(NodeId(0xB1), portB, linkTo = portA)
        relayA.start()
        relayB.start()
        awaitTrue("the ring is closed") {
            relayA.server.knownPeers().isNotEmpty() && relayB.server.knownPeers().isNotEmpty()
        }

        val victimLink = client(victim, portA).also { it.start() }
        awaitTrue("victim connected") { relayA.server.knownPeers().contains(victim) }

        victimLink.send(encode(sos(), victimLink))
        awaitTrue("both relays hold it") {
            relayA.held().isNotEmpty() && relayB.held().isNotEmpty()
        }
        Thread.sleep(500)

        // Each relay forwards a given report at most once — everything after is a duplicate.
        assertEquals(1, relayA.relayed(), "relay A forwarded the report exactly once")
        assertEquals(1, relayB.relayed(), "relay B forwarded the report exactly once")
        assertTrue(
            relayA.duplicates() + relayB.duplicates() > 0,
            "the echo really did come back around and really was suppressed",
        )
    }

    // ------------------------------------------------------------- the composite itself

    @Test
    fun `the composite carries a frame onto every member`() {
        val portA = freePort()
        val portB = freePort()
        val hubA = TcpRelayServer(portA, NodeId(0xA1)).also { started += it; it.start() }
        val hubB = TcpRelayServer(portB, NodeId(0xB1)).also { started += it; it.start() }

        val id = NodeId(0x99)
        val toA = client(id, portA)
        val toB = client(id, portB)
        val composite = CompositeTransport(id, listOf(toA, toB)).also { started += it }
        composite.start()
        awaitTrue("both hubs have the composite") {
            hubA.knownPeers().contains(id) && hubB.knownPeers().contains(id)
        }

        var sawA = false
        var sawB = false
        hubA.onReceive { _, _ -> sawA = true }
        hubB.onReceive { _, _ -> sawB = true }

        composite.send("broadcast".toByteArray())

        awaitTrue("both members carried it") { sawA && sawB }
        awaitTrue("knownPeers is the union") {
            composite.knownPeers().containsAll(listOf(NodeId(0xA1), NodeId(0xB1)))
        }
    }

    @Test
    fun `the composite refuses members that disagree about who they are`() {
        // Two members with different NodeIds would mean one process advertising two identities
        // on the mesh — corroboration counting and the peer table both quietly break.
        val a = TcpTransport("127.0.0.1", 1, NodeId(1))
        val b = TcpTransport("127.0.0.1", 2, NodeId(2))
        assertFailsWith<IllegalArgumentException> { CompositeTransport(NodeId(1), listOf(a, b)) }
    }

    @Test
    fun `the composite takes the narrowest member's frame budget`() {
        // The codec is chosen from maxFrameBytes, so the widest member would encode frames the
        // narrowest physically cannot carry — failing at transmit time on the long-haul link.
        val id = NodeId(7)
        val wide = TcpTransport("127.0.0.1", 1, id)
        val narrow = object : Transport {
            override val localId = id
            override val maxFrameBytes = 233
            override fun start() = Unit
            override fun stop() = Unit
            override fun send(frame: ByteArray, to: NodeId?) = Unit
            override fun onReceive(listener: (NodeId, ByteArray) -> Unit) = Unit
            override fun knownPeers(): List<NodeId> = emptyList()
        }
        assertEquals(233, CompositeTransport(id, listOf(wide, narrow)).maxFrameBytes)
    }
}
