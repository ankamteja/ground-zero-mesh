package org.groundzero.mesh.app.gateway

import fi.iki.elonen.NanoHTTPD
import org.groundzero.mesh.agent.MathEngine
import org.groundzero.mesh.agent.SensoryFlags
import org.groundzero.mesh.gateway.DigitalTwin
import org.groundzero.mesh.gateway.RankedIncident
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The L3 responder gateway HTTP server. Runs on the gateway phone; a responder's laptop
 * joins the phone's own Wi-Fi hotspot and browses in — nothing to install on the viewing
 * machine.
 *
 * Routes:
 * - `GET /` , `/index.html` — the static dashboard (from `assets/dashboard/`)
 * - `GET /snapshot`         — the current ranked incidents, once, as JSON
 * - `GET /events`           — Server-Sent Events; a `data:` line every [pushIntervalMs]
 * - `POST /resolve?peer=<nodeId>` — a responder confirmed `peer` found/safe (see [onMarkFound])
 *
 * The dashboard falls back to polling `/snapshot` if `/events` is unavailable, so the SSE
 * path is best-effort.
 *
 * The board is served straight from `core`'s [RankedIncident] — [clustersNow] is expected
 * to be `ResponderRanking.rank(gossip.clusters(), now)`. There is no app-side ranking any
 * more. The advisory line is a deterministic one-liner built here; it annotates, it never
 * reorders (see [advise]). Each cluster also carries `core`'s [DigitalTwin] projection
 * (floor / position / placed), so the one dashboard has both the ranked list and the
 * schematic 3D view from a single fetch — see [payload].
 */
class GatewayServer(
    port: Int = DEFAULT_PORT,
    private val pushIntervalMs: Long = 2_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val readAsset: (String) -> ByteArray?,
    private val clustersNow: () -> List<RankedIncident>,
    private val onMarkFound: (NodeId) -> Unit = {},
    private val localNodeId: () -> NodeId? = { null },
) : NanoHTTPD(port) {

    private val subscribers = CopyOnWriteArrayList<PipedOutputStream>()
    @Volatile private var pusher: Thread? = null

    override fun start() {
        super.start(SOCKET_READ_TIMEOUT, false)
        pusher = Thread({ pushLoop() }, "gateway-sse").apply { isDaemon = true; start() }
    }

    override fun stop() {
        pusher?.interrupt()
        subscribers.forEach { runCatching { it.close() } }
        subscribers.clear()
        super.stop()
    }

    /**
     * NanoHTTPD auto-gzips any `text/*` or `*/json` response once the client's
     * `Accept-Encoding` allows it — every browser does this by default; `curl` without
     * `--compressed` does not, which is why this looked fine from a shell and dead in a
     * browser. `GZIPOutputStream` only flushes on `finish()`, which NanoHTTPD calls after
     * the response body stream reaches EOF — `/events`' [source] never does, by design, so
     * every SSE push sat compressed and unflushed forever. None of these responses are
     * large enough for gzip to matter; disabling it outright is simpler than special-casing
     * the streaming one and safer than the next streaming response hitting the same bug.
     */
    override fun useGzipWhenAccepted(r: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response = when {
        session.uri == "/resolve" && session.method == Method.POST -> resolve(session)
        session.uri == "/" || session.uri == "/index.html" -> asset("index.html", "text/html")
        session.uri == "/snapshot" -> json(payload())
        session.uri == "/events" -> sse()
        else -> {
            val name = session.uri.removePrefix("/")
            if (name.contains("..")) forbidden() else asset(name, mimeFor(name))
        }
    }

    /**
     * A responder confirmed a peer found/safe. [onMarkFound] is `MeshStack::markPeerFound`
     * in the app — see that method's doc for what this can and cannot reach (this device's
     * own peer table only; it is not a mesh-wide resolution).
     */
    private fun resolve(session: IHTTPSession): Response {
        val raw = session.parms["peer"] ?: return badRequest("missing peer")
        val nodeId = runCatching { NodeId.parse(raw) }.getOrElse { return badRequest("bad peer id") }
        onMarkFound(nodeId)
        return json("{\"ok\":true}")
    }

    /**
     * One payload for both `/snapshot` and `/events`: the ranked board, each cluster's
     * spatial projection folded in by [DigitalTwin] (the same schematic 3D view the CLI
     * simulation renders, now reaching the real dashboard — see `docs/architecture.md`),
     * and the flag-bit / `v_SLM` slot labels the inspector needs, sourced from `core` rather
     * than a second hardcoded copy.
     */
    private fun payload(): String {
        val nowMs = now()
        val ranked = clustersNow()
        val twin = DigitalTwin.snapshot(ranked, nowMs)
        val twinByKey = twin.nodes.associateBy { it.key }
        val links = twin.links.joinToString(",") {
            "{\"carrier\":${quote(it.carrier.canonical())},\"incidentKey\":${quote(it.incidentKey)}}"
        }
        val flagBits = SensoryFlags.BIT_NAMES.joinToString(",") { quote(it) }
        val slotNames = MathEngine.slotNames().joinToString(",") { quote(it) }
        // The GATEWAY itself never appears as an incident — it doesn't sense or originate —
        // but a responder looking at the board still needs to know which device is serving
        // it. Not a TwinNode: it never ranks, never carries a report, isn't part of the
        // board at all, just a fixed "you are here" marker at the schematic centre.
        val self = localNodeId()?.let { "{\"nodeId\":${quote(it.canonical())}}" } ?: "null"
        return "{\"advice\":${quote(advise(ranked))}," +
            "\"flagBits\":[$flagBits]," +
            "\"slotNames\":[$slotNames]," +
            "\"clusters\":${ClusterJson.array(ranked, nowMs, twinByKey)}," +
            "\"links\":[$links]," +
            "\"self\":$self}"
    }

    /**
     * A short, plain-language line for the responder. Deterministic, offline, no model.
     * It summarises what the ranking already decided — it can never delay, reorder, or veto
     * an entry. This is the "AI is advisory only" rule holding at L3.
     */
    private fun advise(ranked: List<RankedIncident>): String {
        if (ranked.isEmpty()) return "No incidents yet."
        val inBudget = ranked.count { it.withinBudget }
        val dispatchable = ranked.count { it.withinBudget && it.standing.dispatchable }
        val drowning = ranked.count { it.cluster.severity == Severity.DROWNING_IMMINENT }
        val top = ranked.first().cluster
        return buildString {
            append(inBudget).append(" incident(s) within the action budget")
            if (dispatchable != inBudget) {
                append(", ").append(dispatchable).append(" first-hand and dispatchable")
            }
            if (drowning > 0) append("; ").append(drowning).append(" at imminent-drowning severity")
            append(". Highest: ").append(top.zone)
            append(" (").append(top.severity.name.lowercase().replace('_', ' ')).append(").")
        }
    }

    private fun pushLoop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val line = "data: ${payload()}\n\n".toByteArray()
                subscribers.forEach { out ->
                    runCatching { out.write(line); out.flush() }
                        .onFailure { subscribers.remove(out); runCatching { out.close() } }
                }
                Thread.sleep(pushIntervalMs)
            }
        } catch (_: InterruptedException) {
            // stopping
        }
    }

    private fun sse(): Response {
        val sink = PipedOutputStream()
        val source = PipedInputStream(sink, 64 * 1024)
        subscribers.add(sink)
        runCatching { sink.write("data: ${payload()}\n\n".toByteArray()); sink.flush() }
        return newChunkedResponse(Response.Status.OK, "text/event-stream", source).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "keep-alive")
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    private fun asset(name: String, mime: String): Response {
        val bytes = readAsset(name) ?: return notFound()
        return newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
    }

    private fun json(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", body).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
    private fun forbidden() = newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "no")
    private fun badRequest(msg: String) = newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", msg)

    private fun mimeFor(name: String) = when {
        name.endsWith(".html") -> "text/html"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".js") -> "application/javascript"
        name.endsWith(".json") -> "application/json"
        name.endsWith(".svg") -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    companion object {
        const val DEFAULT_PORT = 8080
    }
}
