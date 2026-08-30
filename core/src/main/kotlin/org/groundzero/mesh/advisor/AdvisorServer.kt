package org.groundzero.mesh.advisor

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.groundzero.mesh.llm.Advisory
import org.groundzero.mesh.llm.BoardView
import org.groundzero.mesh.llm.Json
import org.groundzero.mesh.llm.LlmAdvisor
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * The perimeter station's advisor, as a small HTTP service on the responder's laptop.
 *
 * ### Why a sidecar rather than code on the phone
 *
 * The board is served by the *phone* (`app`'s `GatewayServer`); the responder reads it in a
 * browser on a laptop joined to that phone's hotspot. A 7B model runs on the laptop, not on
 * the phone. So the model has to live beside the browser, and the dashboard talks to it as a
 * separate origin.
 *
 * This keeps the phone build exactly as it was: no model, no corpus, no extra permission,
 * no battery cost, and a board that still works with this service switched off. The
 * advisory panel degrades to the gateway's own deterministic line and says so.
 *
 * ### Where the board comes from
 *
 * Two ways, both real:
 *
 * - **`POST /ask` with the snapshot in the body.** The dashboard already holds a live
 *   `/snapshot` from the phone, so it posts the exact board on the responder's screen. No
 *   second fetch, no chance of answering about a board a few seconds newer than the one
 *   being read, and — the part that matters for a real phone — the advisor needs no route to
 *   the phone at all, only the browser does.
 * - **`--gateway http://<phone-ip>:8080`.** The advisor polls the phone itself, which is
 *   what `GET /brief` and a headless/CLI use. Needs the laptop to be on the phone's hotspot.
 *
 * ### CORS, and the one that bites on real hardware
 *
 * The page's origin is the phone (`http://192.168.x.x:8080`) and this service is on
 * `localhost`, so every call is cross-origin: the handlers below answer the preflight and
 * allow any origin — this serves a local advisory over a private disaster network, and
 * pinning an allowlist to an IP the hotspot hands out at random helps nobody.
 *
 * A private-network preflight is answered too. Chrome treats a request from a private
 * address (the phone's page) to a loopback address (this service) as a Private Network
 * Access request and asks for `Access-Control-Allow-Private-Network` before it will send
 * the real one. Without that header the panel fails on a real phone while working perfectly
 * when the dashboard is opened through `adb forward` on localhost — a difference that would
 * otherwise only show up in the field.
 */
class AdvisorServer(
    val port: Int = DEFAULT_PORT,
    private val advisor: LlmAdvisor,
    /** Phone gateway root, e.g. `http://192.168.43.1:8080`. Null disables [brief]'s own fetch. */
    private val gatewayUrl: String? = null,
    private val fetchSnapshot: (String) -> String = ::httpGet,
) {

    private var server: HttpServer? = null

    fun start() {
        val http = HttpServer.create(InetSocketAddress(port), 0)
        http.executor = Executors.newFixedThreadPool(WORKERS)
        http.createContext("/health") { handle(it) { _, _ -> health() } }
        http.createContext("/ask") { handle(it) { ex, body -> ask(ex, body) } }
        http.createContext("/brief") { handle(it) { _, _ -> brief() } }
        http.createContext("/") { handle(it) { _, _ -> Response(404, "{\"error\":\"no such route\"}") } }
        http.start()
        server = http
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    /** The bound port — resolves `0` to whatever the OS handed out, which is what tests need. */
    fun boundPort(): Int = server?.address?.port ?: port

    private data class Response(val code: Int, val body: String)

    private fun handle(exchange: HttpExchange, action: (HttpExchange, String) -> Response) {
        try {
            cors(exchange)
            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                exchange.sendResponseHeaders(204, -1)
                return
            }
            val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val response = try {
                action(exchange, body)
            } catch (e: Exception) {
                Response(500, "{" + Json.field("error", e.message ?: e.javaClass.simpleName) + "}")
            }
            val bytes = response.body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(response.code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (_: IOException) {
            // The browser hung up mid-response. Nothing to report and nothing to retry.
        } finally {
            exchange.close()
        }
    }

    private fun cors(exchange: HttpExchange) {
        val headers = exchange.responseHeaders
        headers.add("Access-Control-Allow-Origin", "*")
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        headers.add("Access-Control-Allow-Headers", "Content-Type")
        headers.add("Access-Control-Max-Age", "600")
        if (exchange.requestHeaders.getFirst("Access-Control-Request-Private-Network") == "true") {
            headers.add("Access-Control-Allow-Private-Network", "true")
        }
    }

    private fun health(): Response {
        val status = advisor.status()
        val body = "{" +
            Json.raw("ok", "true") + "," +
            Json.raw("ollamaUp", status.ollamaUp.toString()) + "," +
            Json.raw("model", status.model?.let { Json.quote(it) } ?: "null") + "," +
            Json.raw("models", Json.array(status.models)) + "," +
            Json.raw("passages", status.passages.toString()) + "," +
            Json.raw("sources", Json.array(status.sources)) + "," +
            Json.field("baseUrl", status.baseUrl) + "," +
            Json.raw("gateway", gatewayUrl?.let { Json.quote(it) } ?: "null") +
            "}"
        return Response(200, body)
    }

    /**
     * `POST /ask` with `{"question": "...", "snapshot": <the gateway's /snapshot payload>}`.
     *
     * `GET /ask?q=...` is the same thing without a snapshot — it needs `--gateway` so the
     * advisor can fetch the board itself, and exists for `curl` and for a headless run.
     */
    private fun ask(exchange: HttpExchange, body: String): Response {
        val request = Json.asObject(Json.parse(if (body.isBlank()) "{}" else body))
        val queryQuestion = query(exchange.requestURI.rawQuery)["q"]
        val question = Json.str(request, "question")?.takeIf { it.isNotBlank() } ?: queryQuestion

        val snapshot = request?.get("snapshot")
        val board = when {
            snapshot is Map<*, *> || snapshot is String -> parseSnapshot(snapshot)
            else -> gatewayBoard() ?: return Response(
                400,
                "{" + Json.field(
                    "error",
                    "no board: POST a \"snapshot\" object, or start the advisor with " +
                        "--gateway http://<phone-ip>:8080",
                ) + "}",
            )
        }
        return Response(200, advisoryJson(advisor.advise(board, question), board))
    }

    private fun brief(): Response {
        val board = gatewayBoard() ?: return Response(
            400,
            "{" + Json.field("error", "no --gateway configured; use POST /ask with a snapshot") + "}",
        )
        return Response(200, advisoryJson(advisor.advise(board, null), board))
    }

    private fun parseSnapshot(snapshot: Any?): BoardView = when (snapshot) {
        // The dashboard can post the parsed object it already holds, or the raw text it
        // received. Both are the same payload; accepting only one would make the panel's
        // code depend on a detail of how it happened to store the last snapshot.
        is String -> BoardView.fromSnapshotJson(snapshot)
        else -> BoardView.fromSnapshotJson(reserialise(snapshot))
    }

    private fun gatewayBoard(): BoardView? {
        val url = gatewayUrl ?: return null
        return try {
            BoardView.fromSnapshotJson(fetchSnapshot(url.trimEnd('/') + "/snapshot"))
        } catch (_: Exception) {
            null
        }
    }

    private fun advisoryJson(advisory: Advisory, board: BoardView): String = "{" +
        Json.field("answer", advisory.text) + "," +
        Json.raw("grounded", advisory.grounded.toString()) + "," +
        Json.raw("model", advisory.model?.let { Json.quote(it) } ?: "null") + "," +
        Json.raw("sources", Json.array(advisory.sources)) + "," +
        Json.raw("tookMs", advisory.tookMs.toString()) + "," +
        Json.raw("note", advisory.note?.let { Json.quote(it) } ?: "null") + "," +
        Json.raw("incidents", board.incidents.size.toString()) +
        "}"

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null
            else URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }.toMap()
    }

    /**
     * Re-encodes a parsed JSON value so [BoardView.fromSnapshotJson] stays the single parser.
     *
     * The alternative — a second `BoardView` builder that walks maps — is two code paths
     * that must agree about every field forever, and they would drift on the first new one.
     */
    private fun reserialise(value: Any?): String = when (value) {
        null -> "null"
        is String -> Json.quote(value)
        is Boolean -> value.toString()
        is Long -> value.toString()
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") {
            Json.quote(it.key.toString()) + ":" + reserialise(it.value)
        }
        is List<*> -> value.joinToString(",", "[", "]") { reserialise(it) }
        else -> Json.quote(value.toString())
    }

    companion object {
        const val DEFAULT_PORT = 8787
        private const val WORKERS = 4

        /** Plain GET, used for the phone gateway's `/snapshot`. Short timeouts: it is on the LAN or it is not. */
        fun httpGet(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3_000
            conn.readTimeout = 5_000
            try {
                if (conn.responseCode !in 200..299) throw IOException("GET $url returned ${conn.responseCode}")
                return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
        }
    }
}
