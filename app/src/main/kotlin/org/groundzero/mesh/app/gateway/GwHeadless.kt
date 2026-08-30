package org.groundzero.mesh.app.gateway

import fi.iki.elonen.NanoHTTPD
import org.groundzero.mesh.agent.NodeAgent
import org.groundzero.mesh.gateway.ResponderRanking
import org.groundzero.mesh.propagation.Codecs
import org.groundzero.mesh.propagation.DedupCluster
import org.groundzero.mesh.propagation.Gossip
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import org.groundzero.mesh.transport.SimNetwork
import org.groundzero.mesh.transport.TcpTransport
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Runs the real L3 [GatewayServer] headless on a laptop instead of on a responder phone —
 * same NanoHTTPD server, same `assets/dashboard/`, same `core` ranking + digital twin.
 *
 *   (default)  a relay bridge: reads the board from a [TcpTransport] onto a
 *              `:core:runRelay` process. `args: [relayHost] [relayPort] [httpPort]`
 *
 *   --sim      an in-process simulated mesh over [SimNetwork]: a few victim nodes, a couple
 *              of relays and a gateway. Nothing is fabricated — the board is **empty** until
 *              an SOS is raised from the dashboard, every incident is a real gossip frame
 *              with a real hop count, and the zone is `unset` exactly as a real phone with
 *              no responder-entered zone reports it. This is the "emulation" the dashboard
 *              toggle switches to. `args: --sim [httpPort]`  (control API on httpPort+10)
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--sim") {
        runSim(args.getOrNull(1)?.toIntOrNull() ?: 8080)
    } else {
        runRelayBridge(
            args.getOrElse(0) { "localhost" },
            args.getOrElse(1) { "7802" }.toInt(),
            args.getOrElse(2) { "8080" }.toInt(),
        )
    }
}

private val ASSET_DIR = File("app/src/main/assets/dashboard")

private fun readAsset(name: String, sim: Boolean): ByteArray? {
    val f = File(ASSET_DIR, name).takeIf { it.isFile } ?: return null
    if (sim && name == "index.html") {
        // The page reads window.__SIM__ to default its source toggle to "emulation", show
        // the SOS/clear controls, and never fall back to fixtures.json. Nothing else changes.
        return f.readText()
            .replaceFirst("<head>", "<head>\n  <script>window.__SIM__ = true;</script>")
            .toByteArray()
    }
    return f.readBytes()
}

// --------------------------------------------------------------------------- relay bridge

private fun runRelayBridge(relayHost: String, relayPort: Int, httpPort: Int) {
    val responderId = NodeId.parse("0000-0000-00d5")
    val transport = TcpTransport(relayHost, relayPort, responderId)
    val gossip = Gossip(transport, clockMs = System::currentTimeMillis)
    val lock = Any()
    transport.onReceive { from, frame -> synchronized(lock) { gossip.ingest(frame, from) } }
    transport.start()

    val server = GatewayServer(
        port = httpPort,
        readAsset = { name -> readAsset(name, sim = false) },
        clustersNow = { synchronized(lock) { ResponderRanking.rank(gossip.clusters(), System.currentTimeMillis()) } },
        localNodeId = { responderId },
    )
    server.start()
    println("relay-bridge dashboard : http://localhost:$httpPort/   (relay $relayHost:$relayPort)")
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { server.stop() }; runCatching { transport.stop() } })
    CountDownLatch(1).await()
}

// ---------------------------------------------------------------------------------- sim

private const val EPOCH_MS = 1_700_000_000_000L
private const val PUMP_STEP_MS = 50L
private val GATEWAY_ID = NodeId.parse("0000-0000-00d5")
private val SIM_TRACE = System.getenv("SIM_TRACE") != null

/**
 * One instance of the simulated mesh — everything is rebuilt fresh by [runSim] on `/clear`,
 * so a cleared board really is a clean slate: not just the gateway's [DedupCluster] but
 * every relay's `seen` set and every victim's `NodeAgent` state. Without that, a re-pressed
 * SOS after a clear carries a `propagationKey` the relays still remember and gets silently
 * suppressed one hop from the gateway.
 *
 * Topology (three separate chains, no node with two routes to G, so the hop count is
 * exactly what the path says):
 *
 * ```
 *   vA ───────────────── G        direct     -> 1 hop
 *   vB ── R1 ─────────── G        one relay   -> 2 hops
 *   vC ── R2 ── R3 ───── G        two relays  -> 3 hops
 * ```
 *
 * Relays run a normal forwarding [Gossip], carry-only, like `TcpRelayMain`. The gateway is
 * a receive-only sink; it does not re-broadcast. Victims are real [NodeAgent]s with zone
 * `"unset"` — the same value `MeshForegroundService` uses when no responder has entered a
 * zone. Nothing about the traffic is scripted: the board is empty until a `/sos` call.
 */
private class SimMesh {
    val lock = Any()
    private val net = SimNetwork(latencyMs = 20)
    private val clock = { EPOCH_MS + net.nowMs() }
    private var board = DedupCluster()
    @Volatile private var alive = true
    private val agents: Map<String, NodeAgent>

    init {
        val ids = mapOf(
            "A" to NodeId.parse("0000-0000-0a01"),
            "B" to NodeId.parse("0000-0000-0a02"),
            "C" to NodeId.parse("0000-0000-0a03"),
        )
        val r1 = NodeId.parse("0000-0000-0b01")
        val r2 = NodeId.parse("0000-0000-0b02")
        val r3 = NodeId.parse("0000-0000-0b03")

        net.link(ids.getValue("A"), GATEWAY_ID)
        net.link(ids.getValue("B"), r1); net.link(r1, GATEWAY_ID)
        net.link(ids.getValue("C"), r2); net.link(r2, r3); net.link(r3, GATEWAY_ID)

        for (id in listOf(r1, r2, r3)) {
            val t = net.transportFor(id).also { it.start() }
            val gs = Gossip(t, clockMs = clock)
            t.onReceive { from, frame ->
                synchronized(lock) {
                    val before = gs.relayed
                    gs.ingest(frame, from)
                    log("relay ${id.canonical().takeLast(4)} <- ${from?.canonical()?.takeLast(4)}  relayed $before->${gs.relayed}")
                }
            }
        }

        val gt = net.transportFor(GATEWAY_ID).also { it.start() }
        gt.onReceive { from, frame ->
            synchronized(lock) {
                runCatching {
                    val env = Codecs.forFrameBudget(gt.maxFrameBytes).decode(frame).asReceived()
                    board.ingest(env, from, clock())
                    log("GATEWAY <- ${from?.canonical()?.takeLast(4)}  ${env.dedupKey.takeLast(8)} hops=${env.hops}  board=${board.clusters().size}")
                }.onFailure { log("GATEWAY decode failed: $it") }
            }
        }

        agents = ids.mapValues { (_, id) ->
            NodeAgent(
                nodeId = id,
                saltFingerprint = "0".repeat(32),
                addressZone = "unset",
                transport = net.transportFor(id).also { it.start() },
                clockMs = clock,
            )
        }

        Thread({
            while (alive) {
                synchronized(lock) { net.advance(PUMP_STEP_MS) }
                Thread.sleep(PUMP_STEP_MS)
            }
        }, "sim-pump").apply { isDaemon = true; start() }
    }

    /** Raise an SOS from victim A / B / C. Returns false for an unknown key. */
    fun sos(v: String): Boolean {
        val agent = agents[v.take(1).uppercase()] ?: return false
        synchronized(lock) {
            val env = agent.raiseSos(Severity.STRUCTURAL_ENTRAPMENT)
            log("SOS $v -> ${env.nodeId.canonical().takeLast(4)}  ${env.dedupKey.takeLast(8)}")
        }
        return true
    }

    fun rankedBoard() = synchronized(lock) { ResponderRanking.rank(board.clusters(), clock()) }

    /** Stop this instance's pump so [runSim] can drop it and build a fresh one. */
    fun retire() { alive = false }

    private fun log(m: String) { if (SIM_TRACE) System.err.println("[sim] $m") }
}

private fun runSim(httpPort: Int) {
    var mesh = SimMesh()

    val server = GatewayServer(
        port = httpPort,
        readAsset = { name -> readAsset(name, sim = true) },
        clustersNow = { mesh.rankedBoard() },
        localNodeId = { GATEWAY_ID },
    )
    server.start()

    // Control API for the dashboard's SOS / clear buttons — a second tiny NanoHTTPD on
    // httpPort+10 (NanoHTTPD is already on the classpath; com.sun.net.httpserver is not,
    // Android's android.jar omits it).
    val control = object : NanoHTTPD(httpPort + 10) {
        override fun serve(session: IHTTPSession): Response {
            fun ok(body: String) = newFixedLengthResponse(Response.Status.OK, "text/plain", body)
                .apply { addHeader("Access-Control-Allow-Origin", "*") }
            when {
                session.method == Method.OPTIONS ->
                    return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
                        .apply {
                            addHeader("Access-Control-Allow-Origin", "*")
                            addHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
                            addHeader("Access-Control-Allow-Headers", "Content-Type")
                        }
                session.uri == "/sos" -> {
                    val v = session.parms["v"].orEmpty()
                    return if (mesh.sos(v)) ok("sos raised from ${v.take(1).uppercase()}")
                    else newFixedLengthResponse(
                        Response.Status.BAD_REQUEST, "text/plain", "unknown victim '$v' (use v=A|B|C)",
                    ).apply { addHeader("Access-Control-Allow-Origin", "*") }
                }
                session.uri == "/clear" -> {
                    mesh.retire()
                    mesh = SimMesh()
                    return ok("board cleared — fresh mesh")
                }
                else -> return ok("""{"nodes":["vA","vB","vC","R1","R2","R3","G"],"hops":{"A":1,"B":2,"C":3}}""")
            }
        }
    }
    control.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)

    println("emulation dashboard : http://localhost:$httpPort/")
    println("emulation control   : http://localhost:${httpPort + 10}/  (POST /sos?v=A|B|C, POST /clear)")
    println("nodes: vA vB vC (victims), R1 R2 R3 (relays), G (gateway).  board starts empty.")
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { server.stop() }; runCatching { control.stop() } })
    CountDownLatch(1).await()
}
