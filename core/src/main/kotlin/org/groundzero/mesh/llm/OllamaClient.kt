package org.groundzero.mesh.llm

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One turn of a chat. [role] is `system`, `user` or `assistant`. */
data class ChatMessage(val role: String, val content: String)

/** What a model actually returned, plus how long it took. */
data class ChatReply(val model: String, val content: String, val tookMs: Long)

/**
 * A model the server holds, and what it says the model can do.
 *
 * [capabilities] comes from the server rather than from a list of model names kept here.
 * The thing it settles — whether this model reasons before answering — is worth several
 * seconds per question, and a hardcoded name list would be wrong the first time someone
 * pulls a model nobody here had heard of.
 */
data class ModelInfo(val name: String, val capabilities: List<String>) {
    val thinks: Boolean get() = capabilities.any { it.equals("thinking", ignoreCase = true) }
}

/**
 * The local model server, over its HTTP API.
 *
 * ### Why hand-rolled HTTP
 *
 * `core` carries no third-party runtime dependency and that rule is worth more than the
 * convenience of an HTTP library: this module has to stay buildable and testable on a bare
 * JDK with no Android SDK. [HttpURLConnection] is also the one HTTP client that exists
 * unchanged on Android, so nothing here forecloses the app module calling the same code
 * later against an on-device server.
 *
 * ### Nothing here is required for the board to work
 *
 * Every call can fail — the server may not be running, the model may not be pulled, a
 * 7B/8B on CPU may take longer than the caller is willing to wait. Failure is returned as
 * an exception the caller is expected to catch and fall back from, never swallowed into a
 * plausible-looking answer. See [LlmAdvisor], which degrades to the deterministic brief.
 *
 * @param baseUrl root of the model server, e.g. `http://localhost:11434`. Ollama by
 *   default; anything speaking the same two routes works, including a server on another
 *   machine or a phone on the same LAN.
 */
class OllamaClient(
    val baseUrl: String = DEFAULT_BASE_URL,
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 180_000,
    /**
     * How long the server keeps the model resident after a request.
     *
     * The default unload is five minutes, which is shorter than the gap between a
     * responder's questions and longer than they will wait to find out: the first question
     * after a quiet spell then pays several seconds of reloading gigabytes into VRAM before
     * generation even starts. A perimeter laptop is doing nothing else with that memory, so
     * the model stays put.
     */
    private val keepAlive: String = DEFAULT_KEEP_ALIVE,
) {

    /** Model names the server currently holds, in the order it lists them. Empty if it is down. */
    fun models(): List<String> = catalogue().map { it.name }

    /** The same list with each model's advertised capabilities. Empty if the server is down. */
    fun catalogue(): List<ModelInfo> = try {
        Json.asList(Json.asObject(Json.parse(get("/api/tags")))?.get("models")).mapNotNull { entry ->
            val obj = Json.asObject(entry) ?: return@mapNotNull null
            Json.str(obj, "name")?.let { ModelInfo(it, Json.strList(obj, "capabilities")) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun isUp(): Boolean = try {
        get("/api/version"); true
    } catch (_: Exception) {
        false
    }

    /**
     * One non-streaming chat completion.
     *
     * Streaming is deliberately not used. The advisor renders one finished paragraph into a
     * panel, so a token stream would buy nothing but a second failure mode (a half-written
     * sentence that reads like a complete instruction is worse on this screen than a spinner).
     *
     * @throws IOException when the server is unreachable, returns non-200, or the reply has
     *   no content — never a fabricated or partial answer.
     */
    fun chat(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double = 0.2,
        maxTokens: Int = 260,
        contextTokens: Int = 8_192,
        /**
         * Send `think: false`. Only ever true for a model whose [ModelInfo.thinks] says it
         * supports the field — Ollama rejects the request outright for one that does not,
         * which would turn "this model is fast" into "this model never answers".
         */
        disableThinking: Boolean = false,
    ): ChatReply {
        val body = buildString {
            append('{')
            append(Json.field("model", model)).append(',')
            append(Json.raw("stream", "false")).append(',')
            if (disableThinking) append(Json.raw("think", "false")).append(',')
            append(Json.field("keep_alive", keepAlive)).append(',')
            append(Json.raw("messages", messages.joinToString(",", "[", "]") {
                "{" + Json.field("role", it.role) + "," + Json.field("content", it.content) + "}"
            })).append(',')
            append(
                Json.raw(
                    "options",
                    "{" + Json.raw("temperature", temperature.toString()) + "," +
                        Json.raw("num_predict", maxTokens.toString()) + "," +
                        Json.raw("num_ctx", contextTokens.toString()) + "}",
                ),
            )
            append('}')
        }

        val startedAt = System.currentTimeMillis()
        val reply = post("/api/chat", body)
        val obj = Json.asObject(Json.parse(reply)) ?: throw IOException("chat reply was not an object")
        val content = Json.str(Json.asObject(obj["message"]), "content")
            ?: throw IOException("chat reply carried no message.content")
        val clean = stripReasoning(content).trim()
        if (clean.isEmpty()) throw IOException("model returned an empty answer")
        return ChatReply(
            model = Json.str(obj, "model") ?: model,
            content = clean,
            tookMs = System.currentTimeMillis() - startedAt,
        )
    }

    private fun get(path: String): String = request("GET", path, null)

    private fun post(path: String, body: String): String = request("POST", path, body)

    private fun request(method: String, path: String, body: String?): String {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Accept", "application/json")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw IOException("$method $path returned $code${if (detail.isBlank()) "" else ": ${detail.take(300)}"}")
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Loads the model now, so the first responder question does not pay for it.
     *
     * An empty message list is Ollama's documented way to ask for a load and nothing else.
     * Best-effort: a failure here is not worth reporting, because the first real request
     * will load the model anyway and report its own failure properly.
     */
    fun warmUp(model: String): Boolean = try {
        post(
            "/api/chat",
            "{" + Json.field("model", model) + "," + Json.raw("messages", "[]") + "," +
                Json.field("keep_alive", keepAlive) + "}",
        )
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:11434"

        /** Long enough to cover a shift at a perimeter station, not "forever". */
        const val DEFAULT_KEEP_ALIVE = "30m"

        /**
         * Reasoning models (Qwen3 among them) emit a `<think>…</think>` preamble in the
         * content field. It is the model talking to itself, not to a responder, and it is
         * long. Dropping it here rather than in the UI means every consumer — panel, CLI,
         * test — sees the same answer.
         */
        fun stripReasoning(text: String): String =
            text.replace(Regex("(?s)<think>.*?</think>"), "")
                // An answer truncated by num_predict mid-thought leaves an unclosed tag, and
                // the whole remainder is then reasoning. Better an empty answer the caller
                // falls back from than a paragraph of the model's scratch work on the board.
                .replace(Regex("(?s)<think>.*$"), "")
    }
}
