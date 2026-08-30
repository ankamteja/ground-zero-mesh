package org.groundzero.mesh.llm

import org.groundzero.mesh.agent.SensoryFlags
import org.groundzero.mesh.gateway.ResponderRanking
import org.groundzero.mesh.propagation.FirstHandGate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The corpus is cited, so it has to be true.
 *
 * The advisor answers from these documents and prints the source next to the answer, which
 * makes a stale sentence worse than no sentence: a responder is told something wrong *and*
 * given a reason to believe it. This is not hypothetical — bit 7 was reserved when the corpus
 * was written, became the structural-crack channel a few commits later, and the corpus went
 * on describing it as reserved while `SensoryFlags.describe` said otherwise.
 *
 * These tests pin the handful of claims that are really facts about the code. They are
 * deliberately shallow: prose is not machine-checkable, and pretending otherwise would just
 * make the corpus hard to edit. What they catch is a constant changing underneath it.
 */
class CorpusAccuracyTest {

    private val corpus = KnowledgeBase.bundled()

    private fun text(source: String): String =
        corpus.passages.filter { it.source == source }.joinToString("\n") { it.text }.lowercase()

    private fun whole(): String = corpus.passages.joinToString("\n") { it.text }.lowercase()

    @Test
    fun `every flag bit name the corpus lists is one the code emits`() {
        val board = text("board-and-fields.md")
        SensoryFlags.BIT_NAMES.forEach { name ->
            assertTrue(name.lowercase() in board, "corpus never mentions the flag '$name'")
        }
        // The specific drift that happened: bit 7 stopped being reserved.
        assertTrue(
            "reserved" !in board,
            "the corpus still calls a flag bit reserved; bit 7 is ${SensoryFlags.BIT_NAMES[7]}",
        )
    }

    @Test
    fun `the dispatch budget the corpus quotes is the one the ranker uses`() {
        assertTrue(
            ResponderRanking.BUDGET_ACTIONS.toString() in whole(),
            "corpus does not quote the real budget of ${ResponderRanking.BUDGET_ACTIONS}",
        )
    }

    @Test
    fun `the reporting floor the corpus quotes is the real one`() {
        assertTrue(
            FirstHandGate.FIRSTHAND_FLOOR.toString() in whole(),
            "corpus does not quote the real floor of ${FirstHandGate.FIRSTHAND_FLOOR}",
        )
    }

    @Test
    fun `every standing label the responder can see is explained somewhere`() {
        val board = text("board-and-fields.md")
        FirstHandGate.Standing.values().forEach { standing ->
            assertTrue(
                standing.label.lowercase() in board,
                "corpus never explains the standing '${standing.label}'",
            )
        }
    }

    @Test
    fun `the zone sentinel the corpus names is the code's own`() {
        assertTrue(
            org.groundzero.mesh.propagation.Envelope.UNSET_ZONE in whole(),
            "corpus does not name the real unset-zone sentinel",
        )
    }
}
