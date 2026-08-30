package org.groundzero.mesh.relay

import org.groundzero.mesh.propagation.Gossip
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.transport.CompositeTransport
import org.groundzero.mesh.transport.TcpRelayServer
import org.groundzero.mesh.transport.TcpTransport
import org.groundzero.mesh.transport.Transport
import java.io.File

/**
 * Runs this machine as a real relay node on the actual mesh — not a simulation. Point two
 * phones' `LanRelayTransport` (app module) at this process's host:port instead of Nearby,
 * and it carries frames between them exactly the way a real `MeshRole.RELAY` phone would,
 * for whenever one is not on hand. See `docs/architecture.md`'s LAN-relay ledger entries.
 *
 * Deliberately just [Gossip] wired to a transport — no `NodeAgent`. A relay only ever
 * carries, never originates ("every role keeps relaying" — see `MeshStack.setRole`'s doc in
 * the app module), so nothing here needs the agent at all.
 *
 * ### One relay is a star; several are a chain
 *
 * With no `--link` this is the original single hub: every phone connects to it, and the
 * responder is two hops from the victim. That is a star, and it is the whole reach a lone
 * hub can offer.
 *
 * `--link host:port` additionally dials *another* relay, which is what makes genuine
 * multi-hop possible without a phone per hop. Each relay in the chain runs its own
 * [TcpRelayServer] and dials the previous one with a [TcpTransport]; [CompositeTransport]
 * fans one `Gossip` over both. A frame then crosses relay after relay, spending TTL and
 * gaining a hop at each, exactly as it would across a corridor of phones:
 *
 * ```
 *   victim ──► relay A (7777) ──► relay B (7778) ──► relay C (7779) ──► responder
 *              hops=1            hops=2            hops=3            hops=4
 * ```
 *
 * Start them in any order — [TcpTransport] reconnects on its own, so a relay dialled before
 * it exists simply connects when it appears.
 *
 * Frames echo back the way they came ([TcpRelayServer] broadcasts to every connection
 * including the sender's), so a ring topology is safe: the echo is a duplicate by
 * `Gossip`'s `propagationKey` and dies at the first relay that already holds it.
 *
 * ### Running it
 *
 * ```
 * ./gradlew :core:runRelay                                   # hub on 7777
 * ./gradlew :core:runRelay -PrelayArgs="7778 --link localhost:7777"
 * ./gradlew :core:runRelay -PrelayArgs="7779 --link localhost:7778"
 * ```
 *
 * `Gossip` is documented as not thread-safe (see `MeshStack`'s own doc comment on why it
 * takes a lock), and both transports deliver frames on their own reader threads —
 * concurrently, when two links carry traffic at once. [gossipLock] is this program's
 * equivalent of `MeshStack`'s lock, serialising the one `Gossip` instance those threads
 * would otherwise race on.
 */
object TcpRelayMain {

    const val DEFAULT_PORT = 7777

    private val gossipLock = Any()

    @JvmStatic
    fun main(args: Array<String>) {
        val config = parseArgs(args)
        val localId = loadOrCreateRelayId(config.port)

        val server = TcpRelayServer(config.port, localId)
        val links = config.links.map { (host, port) -> TcpTransport(host, port, localId) }

        // One member is the old star hub; the composite only earns its keep from --link
        // onward, and wrapping a lone server in it would add a layer for nothing.
        val transport: Transport =
            if (links.isEmpty()) server
            else CompositeTransport(localId, listOf(server) + links)

        val gossip = Gossip(transport, clockMs = System::currentTimeMillis)
        transport.onReceive { from, frame -> synchronized(gossipLock) { gossip.ingest(frame, from) } }
        server.onPeerConnected { peer -> println("connected: $peer") }
        links.forEachIndexed { i, link ->
            val (host, port) = config.links[i]
            link.onPeerConnected { peer -> println("linked to relay $peer at $host:$port") }
        }
        transport.start()

        println("relay $localId listening on 0.0.0.0:${config.port}")
        if (config.links.isEmpty()) {
            println("no upstream links — this is a lone hub, so the mesh is two hops wide")
            println("add '--link host:port' to chain this relay onto another one")
        } else {
            config.links.forEach { (host, port) -> println("linking onward to $host:$port") }
        }
        println("point each phone's LAN relay host at this machine's IP and port ${config.port}")
        println("Ctrl+C to stop\n")

        while (true) {
            Thread.sleep(STATUS_INTERVAL_MS)
            synchronized(gossipLock) {
                println(
                    "peers=${transport.knownPeers().size} " +
                        "relayed=${gossip.relayed} " +
                        "duplicates=${gossip.suppressedDuplicates} " +
                        "dropped=${gossip.droppedUndecodable}",
                )
            }
        }
    }

    /** Parsed command line: a port to listen on, and zero or more relays to dial. */
    data class Config(val port: Int, val links: List<Pair<String, Int>>)

    /**
     * `[port] [--link host[:port]]...`
     *
     * A bad port or an unparseable link is a startup mistake worth failing loudly on: a
     * relay that silently listened somewhere other than where the phones were pointed would
     * look exactly like a mesh that is simply not carrying anything.
     */
    fun parseArgs(args: Array<String>): Config {
        var port = DEFAULT_PORT
        val links = mutableListOf<Pair<String, Int>>()
        var i = 0
        var portSeen = false
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--link" -> {
                    val value = args.getOrNull(i + 1)
                        ?: error("--link needs a host[:port]")
                    links += parseHostPort(value)
                    i += 2
                }
                arg.startsWith("--link=") -> {
                    links += parseHostPort(arg.removePrefix("--link="))
                    i += 1
                }
                !portSeen -> {
                    port = arg.toIntOrNull() ?: error("'$arg' is not a port number")
                    portSeen = true
                    i += 1
                }
                else -> error("unexpected argument '$arg'")
            }
        }
        require(port in 1..65535) { "port must be 1..65535, got $port" }
        return Config(port, links)
    }

    private fun parseHostPort(raw: String): Pair<String, Int> {
        val colon = raw.lastIndexOf(':')
        if (colon < 0) return raw to DEFAULT_PORT
        val port = raw.substring(colon + 1).toIntOrNull()
            ?: error("'$raw' has no usable port")
        require(port in 1..65535) { "port must be 1..65535, got $port" }
        return raw.substring(0, colon) to port
    }

    /**
     * Stable across restarts, matching the app's own `NodeIdStore` — a relay whose id keeps
     * changing is one every dashboard "who carried this" reading has to relearn each run.
     *
     * Keyed by port, because several relays chained on one machine are several *nodes*: if
     * they shared an id file they would share a [NodeId], and two nodes claiming one identity
     * breaks corroboration counting and the peer table alike. A pre-existing legacy file is
     * adopted by the default port so an established relay keeps the identity a board already
     * knows.
     */
    private fun loadOrCreateRelayId(port: Int): NodeId {
        val file = File("$ID_FILE_PREFIX$port")
        readId(file)?.let { return it }

        if (port == DEFAULT_PORT) {
            readId(File(LEGACY_ID_FILE))?.let { inherited ->
                runCatching { file.writeText(inherited.canonical()) }
                return inherited
            }
        }

        val fresh = NodeId.random()
        runCatching { file.writeText(fresh.canonical()) }
        return fresh
    }

    private fun readId(file: File): NodeId? = runCatching { file.readText().trim() }
        .getOrNull()
        ?.let { runCatching { NodeId.parse(it) }.getOrNull() }

    private const val ID_FILE_PREFIX = ".relay-node-id-"
    private const val LEGACY_ID_FILE = ".relay-node-id"
    private const val STATUS_INTERVAL_MS = 15_000L
}
