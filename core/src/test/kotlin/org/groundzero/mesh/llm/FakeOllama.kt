package org.groundzero.mesh.llm

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * A real HTTP server standing in for Ollama, on an ephemeral localhost port.
 *
 * Same principle as `TcpTransportTest`: drive the actual code path rather than a fake client.
 * The advisor's failure modes — server down, no models, non-200, empty content — are HTTP
 * facts, and a mocked client would not have them.
 */
class FakeOllama(
    private val models: List<String> = listOf("mistral:7b-instruct-q4_K_M"),
    private val reply: String = "Advisory text.",
    private val chatStatus: Int = 200,
    /** Models that advertise `thinking`, the way the real server reports qwen3. */
    private val thinking: Set<String> = emptySet(),
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0)

    /** The body of the last `/api/chat` request, so a test can assert what the model was told. */
    @Volatile var lastChatBody: String = ""
        private set

    @Volatile var chatCalls: Int = 0
        private set

    val baseUrl: String get() = "http://localhost:${server.address.port}"

    fun start(): FakeOllama {
        server.createContext("/api/version") { send(it, 200, """{"version":"0.0.0-fake"}""") }
        server.createContext("/api/tags") {
            val list = models.joinToString(",") { name ->
                val caps = if (name in thinking) """["completion","thinking"]""" else """["completion"]"""
                """{"name":${Json.quote(name)},"capabilities":$caps}"""
            }
            send(it, 200, """{"models":[$list]}""")
        }
        server.createContext("/api/chat") { exchange ->
            lastChatBody = exchange.requestBody.use { s -> s.readBytes().toString(Charsets.UTF_8) }
            chatCalls++
            if (chatStatus != 200) {
                send(exchange, chatStatus, """{"error":"fake failure"}""")
            } else {
                send(
                    exchange,
                    200,
                    """{"model":${Json.quote(models.firstOrNull() ?: "none")},""" +
                        """"message":{"role":"assistant","content":${Json.quote(reply)}},"done":true}""",
                )
            }
        }
        server.start()
        return this
    }

    fun stop() = server.stop(0)

    private fun send(exchange: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}

/** A port nothing is listening on — the "model server is not running" case, for real. */
fun deadPort(): Int = java.net.ServerSocket(0).use { it.localPort }
