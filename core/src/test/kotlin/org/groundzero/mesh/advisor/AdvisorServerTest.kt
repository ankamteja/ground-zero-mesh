package org.groundzero.mesh.advisor

import org.groundzero.mesh.llm.FakeOllama
import org.groundzero.mesh.llm.Json
import org.groundzero.mesh.llm.KnowledgeBase
import org.groundzero.mesh.llm.LlmAdvisor
import org.groundzero.mesh.llm.OllamaClient
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the real [AdvisorServer] over real HTTP against a real fake model server. The
 * things worth testing here — CORS preflight, the private-network header, a board arriving
 * as a posted object — are all HTTP facts that a direct method call would not exercise.
 */
class AdvisorServerTest {

    private val snapshot = """{"advice":"1 within budget.","clusters":[
        {"clusterId":"a@1","origin":"1122-3344-5566","zone":"block-d-roof",
         "severity":"DROWNING_IMMINENT","effectiveTier":"PRATYAKSA","corroboration":2,
         "dangerScore":0.61,"lastSeenSecondsAgo":25,"reportCount":3,"minHops":1,
         "recommendedActionRank":1,"priority":0.64,"standing":"confirmed — first-hand",
         "dispatchable":true,"evidence":["rushing water"],"placed":true,"floorLabel":"roof",
         "reasons":["drowning imminent — minutes, not hours"]}],
        "links":[],"self":{"nodeId":"355f-807d-59bb"}}"""

    private var ollama: FakeOllama? = null
    private var server: AdvisorServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
        ollama?.stop()
        server = null
        ollama = null
    }

    private fun start(gateway: String? = null, reply: String = "Send the boat to the roof."): AdvisorServer {
        val fake = FakeOllama(reply = reply).start()
        ollama = fake
        val advisor = LlmAdvisor(
            client = OllamaClient(fake.baseUrl, connectTimeoutMs = 1_000, readTimeoutMs = 5_000),
            knowledge = KnowledgeBase.bundled(),
        )
        val srv = AdvisorServer(
            port = 0,
            advisor = advisor,
            gatewayUrl = gateway,
            fetchSnapshot = { snapshot },
        )
        srv.start()
        server = srv
        return srv
    }

    @Test
    fun `health reports the model and the corpus`() {
        val srv = start()
        val body = Json.asObject(Json.parse(get(srv, "/health")))
        assertEquals(true, Json.bool(body, "ok"))
        assertEquals(true, Json.bool(body, "ollamaUp"))
        assertEquals("mistral:7b-instruct-q4_K_M", Json.str(body, "model"))
        assertTrue((Json.int(body, "passages") ?: 0) > 20)
        assertTrue(Json.strList(body, "sources").contains("water-rescue.md"))
    }

    @Test
    fun `a posted snapshot is answered against the board on the responder's screen`() {
        val srv = start()
        val request = """{"question":"who first","snapshot":$snapshot}"""
        val body = Json.asObject(Json.parse(post(srv, "/ask", request)))

        assertEquals("Send the boat to the roof.", Json.str(body, "answer"))
        assertEquals(true, Json.bool(body, "grounded"))
        assertEquals(1, Json.int(body, "incidents"))
        assertTrue(Json.strList(body, "sources").isNotEmpty())
        assertTrue(ollama!!.lastChatBody.contains("The responder asks: who first"))
    }

    @Test
    fun `a snapshot posted as raw text is the same board as one posted as an object`() {
        val srv = start()
        val asText = """{"question":"who first","snapshot":${Json.quote(snapshot)}}"""
        val body = Json.asObject(Json.parse(post(srv, "/ask", asText)))
        assertEquals(1, Json.int(body, "incidents"))
        assertEquals(true, Json.bool(body, "grounded"))
    }

    @Test
    fun `with no board and no gateway it says so instead of answering about nothing`() {
        val srv = start(gateway = null)
        val (code, body) = postRaw(srv, "/ask", """{"question":"who first"}""")
        assertEquals(400, code)
        assertTrue(Json.str(Json.asObject(Json.parse(body)), "error")!!.contains("--gateway"))
    }

    @Test
    fun `brief fetches the phone's board when a gateway is configured`() {
        val srv = start(gateway = "http://192.168.43.1:8080")
        val body = Json.asObject(Json.parse(get(srv, "/brief")))
        assertEquals(1, Json.int(body, "incidents"))
        assertEquals(true, Json.bool(body, "grounded"))
        // No question: the brief prompt, not a responder's words.
        assertTrue(ollama!!.lastChatBody.contains("Brief the responder"))
    }

    @Test
    fun `brief without a gateway is an honest 400, not an empty advisory`() {
        val srv = start(gateway = null)
        val (code, _) = getRaw(srv, "/brief")
        assertEquals(400, code)
    }

    @Test
    fun `a GET ask uses the query string, for curl and a runbook`() {
        val srv = start(gateway = "http://192.168.43.1:8080")
        get(srv, "/ask?q=is%20anyone%20unplaced")
        assertTrue(ollama!!.lastChatBody.contains("The responder asks: is anyone unplaced"))
    }

    @Test
    fun `the preflight a phone-hosted page actually sends is answered`() {
        val srv = start()
        val conn = open(srv, "/ask")
        conn.requestMethod = "OPTIONS"
        conn.setRequestProperty("Origin", "http://192.168.43.1:8080")
        conn.setRequestProperty("Access-Control-Request-Method", "POST")
        conn.setRequestProperty("Access-Control-Request-Headers", "content-type")
        // Chrome adds this when a private-address page calls loopback. Without the matching
        // response header the panel dies on a real phone and works over `adb forward`.
        conn.setRequestProperty("Access-Control-Request-Private-Network", "true")

        assertEquals(204, conn.responseCode)
        assertEquals("*", conn.getHeaderField("Access-Control-Allow-Origin"))
        assertTrue(conn.getHeaderField("Access-Control-Allow-Methods").contains("POST"))
        assertEquals("true", conn.getHeaderField("Access-Control-Allow-Private-Network"))
        conn.disconnect()
    }

    @Test
    fun `the private-network header is not sent when it was not asked for`() {
        val srv = start()
        val conn = open(srv, "/health")
        conn.requestMethod = "OPTIONS"
        assertEquals(204, conn.responseCode)
        assertEquals(null, conn.getHeaderField("Access-Control-Allow-Private-Network"))
        conn.disconnect()
    }

    @Test
    fun `an unknown route is a 404, not a stack trace`() {
        val srv = start()
        val (code, body) = getRaw(srv, "/nope")
        assertEquals(404, code)
        assertNotNull(Json.str(Json.asObject(Json.parse(body)), "error"))
    }

    @Test
    fun `malformed request JSON is a 500 with a message, and the server keeps serving`() {
        val srv = start()
        val (code, _) = postRaw(srv, "/ask", "{not json")
        assertTrue(code >= 400)
        // Still alive for the next responder question.
        assertEquals(true, Json.bool(Json.asObject(Json.parse(get(srv, "/health"))), "ok"))
    }

    @Test
    fun `a dead model server still answers, with grounded false`() {
        val fake = FakeOllama(chatStatus = 500).start()
        ollama = fake
        val srv = AdvisorServer(
            port = 0,
            advisor = LlmAdvisor(
                client = OllamaClient(fake.baseUrl, connectTimeoutMs = 500, readTimeoutMs = 2_000),
                knowledge = KnowledgeBase.bundled(),
            ),
            fetchSnapshot = { snapshot },
        )
        srv.start()
        server = srv

        val body = Json.asObject(Json.parse(post(srv, "/ask", """{"snapshot":$snapshot}""")))
        assertEquals(false, Json.bool(body, "grounded"))
        assertFalse(Json.str(body, "answer").isNullOrBlank())
        assertNotNull(Json.str(body, "note"))
    }

    // --- plumbing ---

    private fun open(srv: AdvisorServer, path: String): HttpURLConnection =
        (URL("http://localhost:${srv.boundPort()}$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_000
            readTimeout = 10_000
        }

    private fun get(srv: AdvisorServer, path: String): String = getRaw(srv, path).second

    private fun getRaw(srv: AdvisorServer, path: String): Pair<Int, String> {
        val conn = open(srv, path)
        return try {
            val code = conn.responseCode
            code to read(conn, code)
        } finally {
            conn.disconnect()
        }
    }

    private fun post(srv: AdvisorServer, path: String, body: String): String = postRaw(srv, path, body).second

    private fun postRaw(srv: AdvisorServer, path: String, body: String): Pair<Int, String> {
        val conn = open(srv, path)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return try {
            val code = conn.responseCode
            code to read(conn, code)
        } finally {
            conn.disconnect()
        }
    }

    private fun read(conn: HttpURLConnection, code: Int): String =
        (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
}
