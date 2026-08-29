package org.groundzero.mesh.app.gateway

import fi.iki.elonen.NanoHTTPD
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
 * - `GET /snapshot`         — the current ranked clusters, once, as JSON
 * - `GET /events`           — Server-Sent Events; a `data:` line every [pushIntervalMs]
 *
 * The dashboard falls back to polling `/snapshot` if `/events` is unavailable, so the SSE
 * path is best-effort.
 */
class GatewayServer(
    port: Int = DEFAULT_PORT,
    private val advisor: AiAdvisor = NoopAiAdvisor,
    private val pushIntervalMs: Long = 2_000,
    private val readAsset: (String) -> ByteArray?,
    private val clustersNow: () -> List<SurvivorCluster>,
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

    override fun serve(session: IHTTPSession): Response = when (session.uri) {
        "/", "/index.html" -> asset("index.html", "text/html")
        "/snapshot" -> json(payload())
        "/events" -> sse()
        else -> {
            val name = session.uri.removePrefix("/")
            if (name.contains("..")) forbidden() else asset(name, mimeFor(name))
        }
    }

    private fun payload(): String {
        val clusters = clustersNow()
        val advice = advisor.summarise(clusters)
        return "{\"advice\":${quote(advice)},\"clusters\":${ClusterJson.array(clusters)}}"
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
