package org.groundzero.mesh.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdvisorPromptTest {

    private val top = BoardIncident(
        clusterId = "112233445566@2",
        origin = "1122-3344-5566",
        zone = "block-d-roof",
        severity = "DROWNING_IMMINENT",
        tier = "PRATYAKSA",
        standing = "confirmed — first-hand",
        dispatchable = true,
        actionRank = 1,
        priority = 0.636,
        dangerScore = 0.61,
        corroboration = 2,
        reportCount = 3,
        minHops = 1,
        lastSeenSecondsAgo = 25,
        evidence = listOf("manual SOS", "rushing water"),
        reasons = listOf("drowning imminent — minutes, not hours"),
        floorLabel = "roof",
        placed = true,
        gpsLat = null,
        gpsLon = null,
        gpsSource = null,
    )
    private val unplaced = top.copy(
        clusterId = "778899aabbcc@3",
        origin = "7788-99aa-bbcc",
        zone = "unset",
        severity = "OTHER",
        tier = "SABDA",
        standing = "single unconfirmed report",
        dispatchable = false,
        actionRank = null,
        corroboration = 0,
        evidence = emptyList(),
        floorLabel = "unplaced",
        placed = false,
    )
    private val board = BoardView(listOf(top, unplaced), deterministicAdvice = "1 within budget.")

    @Test
    fun `the facts block carries the fields the responder can see`() {
        val facts = AdvisorPrompt.facts(board)
        assertTrue(facts.contains("#1 1122-3344-5566"), facts)
        assertTrue(facts.contains("severity=DROWNING_IMMINENT"))
        assertTrue(facts.contains("standing=confirmed — first-hand"))
        assertTrue(facts.contains("distance=1 hop(s)"))
        assertTrue(facts.contains("evidence: manual SOS, rushing water"))
        assertTrue(facts.contains("ranked here because:"))
    }

    @Test
    fun `a row past the budget is marked as such, not hidden`() {
        val facts = AdvisorPrompt.facts(board)
        assertTrue(facts.contains("#— (beyond budget)"), facts)
        assertTrue(facts.contains("floor=UNPLACED"), facts)
        assertTrue(facts.contains("evidence: none reported"), facts)
    }

    @Test
    fun `an absent position is stated as none rather than omitted`() {
        assertTrue(AdvisorPrompt.facts(board).contains("position=none"))
        val withFix = board.copy(
            incidents = listOf(top.copy(gpsLat = 12.97, gpsLon = 77.59, gpsSource = "SATELLITE")),
        )
        assertTrue(AdvisorPrompt.facts(withFix).contains("position=12.97,77.59 (satellite fix)"))
    }

    /**
     * The model has to be able to tell a responder that a position is the person's own
     * estimate. A bare enum name in the facts would invite it to report a guess as a fix.
     */
    @Test
    fun `a self-reported position is spelled out as the person's own estimate`() {
        val marked = board.copy(
            incidents = listOf(top.copy(gpsLat = 12.97, gpsLon = 77.59, gpsSource = "SELF_REPORTED")),
        )
        val facts = AdvisorPrompt.facts(marked)
        assertTrue(facts.contains("marked by the person themselves"), facts)
        assertTrue(facts.contains("may be wrong"), facts)
    }

    @Test
    fun `an empty board says the mesh is quiet`() {
        assertTrue(AdvisorPrompt.facts(BoardView(emptyList())).contains("mesh is quiet"))
    }

    @Test
    fun `the system prompt forbids reordering and forbids filling gaps`() {
        val system = AdvisorPrompt.SYSTEM
        assertTrue(system.contains("never rank"))
        assertTrue(system.contains("not in the board data"))
        assertTrue(system.contains("Never fill a gap"))
        assertTrue(system.contains("never coordinates"))
    }

    @Test
    fun `the query uses the responder's words, plus the board's`() {
        val q = AdvisorPrompt.query(board, "who do I send the boat to")
        assertTrue(q.startsWith("who do I send the boat to"))
        assertTrue(q.contains("DROWNING_IMMINENT"))
        assertTrue(q.contains("single-sourced uncorroborated"), q)
    }

    @Test
    fun `with no question the board itself is the query`() {
        val q = AdvisorPrompt.query(board, null)
        assertTrue(q.contains("responder briefing"))
        assertTrue(q.contains("block-d-roof"))
    }

    @Test
    fun `retrieved passages arrive with the citation the answer must use`() {
        val hits = Retriever(KnowledgeBase.bundled()).search("drowning water rescue", 2)
        val context = AdvisorPrompt.context(hits)
        hits.forEach { assertTrue(context.contains("[${it.passage.citation}]"), context) }
    }

    @Test
    fun `no retrieval hit is stated as such rather than left to the model to fill`() {
        val context = AdvisorPrompt.context(emptyList())
        assertTrue(context.contains("no reference passage matched"))
        assertTrue(context.contains("cite nothing"))
    }

    @Test
    fun `the messages are system-then-user, with the question in the task`() {
        val messages = AdvisorPrompt.messages(board, "is anyone unplaced", emptyList())
        assertEquals(listOf("system", "user"), messages.map { it.role })
        assertTrue(messages[1].content.contains("BOARD FACTS"))
        assertTrue(messages[1].content.contains("REFERENCE"))
        assertTrue(messages[1].content.contains("The responder asks: is anyone unplaced"))
    }
}
