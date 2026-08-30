package org.groundzero.mesh.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetrieverTest {

    private val retriever = Retriever(KnowledgeBase.bundled())

    @Test
    fun `a water question retrieves the water document`() {
        val hits = retriever.search("someone is drowning, do I go into the water", 3)
        assertTrue(hits.isNotEmpty())
        assertTrue(
            hits.any { it.passage.source == "water-rescue.md" },
            "got ${hits.map { it.passage.citation }}",
        )
    }

    @Test
    fun `a question about a board term retrieves the glossary`() {
        val hits = retriever.search("what does SABDA mean on this row", 3)
        assertTrue(
            hits.any { it.passage.source == "board-and-fields.md" },
            "got ${hits.map { it.passage.citation }}",
        )
    }

    @Test
    fun `synonyms bridge the responder's words and the corpus's`() {
        // A responder types "stuck". The corpus writes it as "entrapment" — and the one
        // place the literal word does occur is the glossary, in an unrelated sense ("a row
        // that looks stuck at a high severity"). So a purely literal search answers a
        // trapped-casualty question out of the wrong document. This is the case the synonym
        // list exists for.
        val corpus = KnowledgeBase.bundled()
        assertEquals(
            listOf("board-and-fields.md"),
            corpus.passages.filter { it.text.lowercase().contains("stuck") }.map { it.source }.distinct(),
        )
        val hits = retriever.search("is anyone stuck", 3)
        assertTrue(hits.isNotEmpty(), "synonym expansion found nothing")
        assertTrue(
            hits.any { it.passage.source == "structural-collapse.md" },
            "expansion did not reach the entrapment document: ${hits.map { it.passage.citation }}",
        )
    }

    @Test
    fun `ranking is deterministic across runs`() {
        val query = "who do I send the boat to first"
        val a = retriever.search(query, 4).map { it.passage.id }
        val b = retriever.search(query, 4).map { it.passage.id }
        assertEquals(a, b)
    }

    @Test
    fun `a query of only stopwords retrieves nothing rather than everything`() {
        assertEquals(emptyList(), retriever.search("the and of to", 5))
    }

    @Test
    fun `an empty corpus retrieves nothing rather than failing`() {
        assertEquals(emptyList(), Retriever(KnowledgeBase(emptyList())).search("drowning", 3))
    }

    @Test
    fun `the limit is honoured`() {
        assertTrue(retriever.search("water rescue collapse board relay", 2).size <= 2)
    }
}
