package org.groundzero.mesh.relay

import org.groundzero.mesh.propagation.Gossip
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.transport.TcpRelayServer
import java.io.File

/**
 * Runs this machine as a real relay node on the actual mesh — not a simulation. Point two
 * phones' `LanRelayTransport` (app module) at this process's host:port instead of Nearby,
 * and it carries frames between them exactly the way a real third `MeshRole.RELAY` phone
 * would, for whenever one is not on hand. See `docs/architecture.md`'s LAN-relay ledger
 * entry.
 *
 * Deliberately just [Gossip] wired to a [TcpRelayServer] — no `NodeAgent`. A relay only
 * ever carries, never originates ("every role keeps relaying" — see `MeshStack.setRole`'s
 * doc in the app module), so nothing here needs the agent at all.
 *
 * `Gossip` is documented as not thread-safe (see `MeshStack`'s own doc comment on why it
 * takes a lock), and [TcpRelayServer] delivers each connection's frames on that
 * connection's own reader thread — concurrently, when two phones send at once. [gossipLock]
 * is this program's equivalent of `MeshStack`'s lock, serialising the one `Gossip` instance
 * two threads would otherwise race on.
 *
 * `./gradlew :core:runRelay` (default port [DEFAULT_PORT]), or
 * `./gradlew :core:runRelay -PrelayArgs="7778"` for a different one.
 */
object TcpRelayMain {

    const val DEFAULT_PORT = 7777

    private val gossipLock = Any()

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT
        val localId = loadOrCreateRelayId()

        val transport = TcpRelayServer(port, localId)
        val gossip = Gossip(transport, clockMs = System::currentTimeMillis)
        transport.onReceive { from, frame -> synchronized(gossipLock) { gossip.ingest(frame, from) } }
        transport.onPeerConnected { peer -> println("connected: $peer") }
        transport.start()

        println("relay $localId listening on 0.0.0.0:$port")
        println("point each phone's LAN relay host at this machine's IP and port $port")
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

    /** Stable across restarts, matching the app's own `NodeIdStore` — a relay whose id keeps
     *  changing is one every dashboard "who carried this" reading has to relearn each run. */
    private fun loadOrCreateRelayId(): NodeId {
        val file = File(ID_FILE)
        val existing = runCatching { file.readText().trim() }.getOrNull()
            ?.let { runCatching { NodeId.parse(it) }.getOrNull() }
        if (existing != null) return existing
        val fresh = NodeId.random()
        runCatching { file.writeText(fresh.canonical()) }
        return fresh
    }

    private const val ID_FILE = ".relay-node-id"
    private const val STATUS_INTERVAL_MS = 15_000L
}
