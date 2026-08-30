package org.groundzero.mesh.llm

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmAdvisorTest {

    private var fake: FakeOllama? = null

    @AfterTest
    fun tearDown() {
        fake?.stop()
        fake = null
    }

    private fun board() = BoardView.fromSnapshotJson(
        """{"advice":"1 within budget.","clusters":[
             {"clusterId":"a@1","origin":"1122-3344-5566","zone":"block-d-roof",
              "severity":"DROWNING_IMMINENT","effectiveTier":"PRATYAKSA","corroboration":2,
              "dangerScore":0.61,"lastSeenSecondsAgo":25,"reportCount":3,"minHops":1,
              "recommendedActionRank":1,"priority":0.64,"standing":"confirmed — first-hand",
              "dispatchable":true,"evidence":["rushing water"],"placed":true,"floorLabel":"roof",
              "reasons":["drowning imminent — minutes, not hours"]}],
            "links":[],"self":{"nodeId":"355f-807d-59bb"}}""",
    )

    private fun advisorAgainst(server: FakeOllama, model: String? = null) = LlmAdvisor(
        client = OllamaClient(server.baseUrl, connectTimeoutMs = 1_000, readTimeoutMs = 5_000),
        knowledge = KnowledgeBase.bundled(),
        preferredModel = model,
    )

    @Test
    fun `a model answer is returned grounded, with its citations`() {
        val server = FakeOllama(reply = "Send the boat to 1122-3344-5566 on the roof.").start()
        fake = server
        val advisory = advisorAgainst(server).advise(board(), "who first")

        assertTrue(advisory.grounded)
        assertEquals("Send the boat to 1122-3344-5566 on the roof.", advisory.text)
        assertEquals("mistral:7b-instruct-q4_K_M", advisory.model)
        assertTrue(advisory.sources.isNotEmpty(), "a grounded answer with no citations")
        assertNull(advisory.note)
    }

    @Test
    fun `the model is told the board facts and the retrieved passages`() {
        val server = FakeOllama().start()
        fake = server
        advisorAgainst(server).advise(board(), "is this dispatchable")

        val sent = server.lastChatBody
        assertTrue(sent.contains("BOARD FACTS"), sent.take(200))
        assertTrue(sent.contains("DROWNING_IMMINENT"))
        assertTrue(sent.contains("REFERENCE"))
        assertTrue(sent.contains("The responder asks: is this dispatchable"))
        // Non-streaming: one finished paragraph, not a token stream (see OllamaClient).
        assertTrue(sent.contains("\"stream\":false"))
        // The model stays in VRAM between questions; the default five-minute unload would
        // make every question after a quiet spell pay a reload.
        assertTrue(sent.contains("\"keep_alive\":\"30m\""), sent.take(200))
    }

    @Test
    fun `a dead model server falls back to the deterministic brief and says so`() {
        val advisor = LlmAdvisor(
            client = OllamaClient("http://localhost:${deadPort()}", connectTimeoutMs = 300, readTimeoutMs = 500),
            knowledge = KnowledgeBase.bundled(),
        )
        val advisory = advisor.advise(board(), null)

        assertFalse(advisory.grounded)
        assertNull(advisory.model)
        assertEquals(emptyList(), advisory.sources)
        assertTrue(advisory.text.contains("within dispatch capacity"), advisory.text)
        assertTrue(advisory.note!!.contains("no model answered"), advisory.note!!)
    }

    @Test
    fun `a failed question is not passed off as an answer`() {
        val advisor = LlmAdvisor(
            client = OllamaClient("http://localhost:${deadPort()}", connectTimeoutMs = 300, readTimeoutMs = 500),
            knowledge = KnowledgeBase.bundled(),
        )
        val advisory = advisor.advise(board(), "which floor is the second casualty on")
        assertFalse(advisory.grounded)
        assertTrue(
            advisory.note!!.contains("not an answer to the question"),
            "a brief must not be presented as an answer: ${advisory.note}",
        )
    }

    @Test
    fun `a model that errors mid-generation falls back rather than returning nothing`() {
        val server = FakeOllama(chatStatus = 500).start()
        fake = server
        val advisory = advisorAgainst(server).advise(board(), null)
        assertFalse(advisory.grounded)
        assertTrue(advisory.text.isNotBlank())
    }

    @Test
    fun `an empty generation is a failure, not an empty advisory`() {
        val server = FakeOllama(reply = "   ").start()
        fake = server
        val advisory = advisorAgainst(server).advise(board(), null)
        assertFalse(advisory.grounded)
        assertTrue(advisory.text.contains("dispatch capacity"))
    }

    @Test
    fun `a reasoning model's think block never reaches the board`() {
        val server = FakeOllama(
            models = listOf("qwen3:8b"),
            reply = "<think>The ranker already decided. I should not reorder.</think>\nTop row is a drowning.",
        ).start()
        fake = server
        val advisory = advisorAgainst(server).advise(board(), null)
        assertEquals("Top row is a drowning.", advisory.text)
        assertFalse(advisory.text.contains("<think>"))
    }

    @Test
    fun `an unclosed think block is dropped rather than shown as an answer`() {
        val server = FakeOllama(models = listOf("qwen3:8b"), reply = "<think>cut off mid-thou").start()
        fake = server
        val advisory = advisorAgainst(server).advise(board(), null)
        assertFalse(advisory.grounded, "truncated reasoning must not be served as an advisory")
    }

    @Test
    fun `model choice follows the measured preference and honours a pin`() {
        val advisor = LlmAdvisor(knowledge = KnowledgeBase(emptyList()))
        val available = listOf("hf.co/x/DeepHat-V1-7B-GGUF:Q4_K_M", "mistral:7b-instruct-q4_K_M", "qwen3:8b")
        assertEquals("qwen3:8b", advisor.choose(available))

        val pinned = LlmAdvisor(knowledge = KnowledgeBase(emptyList()), preferredModel = "mistral")
        assertEquals("mistral:7b-instruct-q4_K_M", pinned.choose(available))

        // A pin that is not installed falls through to the preference order rather than
        // failing every request.
        val missing = LlmAdvisor(knowledge = KnowledgeBase(emptyList()), preferredModel = "llama4:70b")
        assertEquals("qwen3:8b", missing.choose(available))
        assertNull(missing.choose(emptyList()))

        // Nothing recognised: use what is actually there rather than refusing.
        assertEquals("hf.co/x/DeepHat-V1-7B-GGUF:Q4_K_M", advisor.choose(listOf("hf.co/x/DeepHat-V1-7B-GGUF:Q4_K_M")))
    }

    @Test
    fun `reasoning is switched off for a model that advertises it, and only for that model`() {
        val thinker = FakeOllama(models = listOf("qwen3:8b"), thinking = setOf("qwen3:8b")).start()
        fake = thinker
        advisorAgainst(thinker).advise(board(), null)
        assertTrue(thinker.lastChatBody.contains("\"think\":false"), thinker.lastChatBody.take(200))
        thinker.stop()

        // Ollama rejects `think` outright for a model without the capability, so sending it
        // anyway would turn every request into a 400.
        val plain = FakeOllama(models = listOf("mistral:7b-instruct-q4_K_M")).start()
        fake = plain
        advisorAgainst(plain).advise(board(), null)
        assertFalse(plain.lastChatBody.contains("\"think\""), plain.lastChatBody.take(200))
    }

    @Test
    fun `status reports what is actually available`() {
        val server = FakeOllama(models = listOf("mistral:7b-instruct-q4_K_M", "qwen3:8b")).start()
        fake = server
        val status = advisorAgainst(server).status()
        assertTrue(status.ollamaUp)
        assertEquals("qwen3:8b", status.model, "status must report the model that would actually answer")
        assertEquals(2, status.models.size)
        assertTrue(status.passages > 20)
    }

    @Test
    fun `the summarizer seam degrades to the deterministic stand-in`() {
        val summarizer = LlmTacticalSummarizer(
            advisor = LlmAdvisor(
                client = OllamaClient("http://localhost:${deadPort()}", connectTimeoutMs = 300, readTimeoutMs = 500),
                knowledge = KnowledgeBase.bundled(),
            ),
        )
        val text = summarizer.summarise(emptyList(), org.groundzero.mesh.gateway.TwinSnapshot(emptyList(), emptyList(), emptyList(), 0))
        assertTrue(text.contains("mesh is quiet"), text)
    }
}
