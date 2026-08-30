package org.groundzero.mesh.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnowledgeBaseTest {

    @Test
    fun `the bundled corpus is on the classpath and every indexed file loaded`() {
        val kb = KnowledgeBase.bundled()
        assertTrue(kb.size > 20, "expected a real corpus, got ${kb.size} passage(s)")
        // Every file named in index.txt must have produced at least one passage — a typo in
        // the index is otherwise a silently smaller corpus, which is the failure this
        // project's whole "say what you don't know" stance is about.
        val expected = listOf(
            "board-and-fields.md", "triage-and-dispatch.md", "water-rescue.md",
            "structural-collapse.md", "mesh-operations.md", "limits-and-cautions.md",
        )
        assertEquals(expected.sorted(), kb.sources.sorted())
    }

    @Test
    fun `every passage carries a traceable citation`() {
        KnowledgeBase.bundled().passages.forEach {
            assertTrue(it.text.isNotBlank(), "blank passage ${it.id}")
            assertTrue(it.citation.contains("§"), "no citation on ${it.id}")
        }
    }

    @Test
    fun `chunks split at headings and keep the heading with its body`() {
        val passages = KnowledgeBase.chunk(
            "sop.md",
            """
            # Title
            intro line

            ## Reach throw row go
            never enter moving water on foot

            ## Shoring
            support the load before removing it
            """.trimIndent(),
        )
        assertEquals(listOf("Title", "Reach throw row go", "Shoring"), passages.map { it.heading })
        assertTrue(passages[1].text.contains("moving water"))
        assertTrue(passages[2].text.contains("support the load"))
    }

    @Test
    fun `an over-long section is split at a paragraph boundary, not mid-sentence`() {
        val paragraph = "word ".repeat(120).trim()
        val passages = KnowledgeBase.chunk("long.md", "## Big\n$paragraph\n\n$paragraph\n\n$paragraph")
        assertTrue(passages.size > 1, "expected the section to split")
        passages.forEach { assertTrue(it.text.length <= KnowledgeBase.MAX_CHUNK_CHARS + paragraph.length) }
        // Same heading on every part, so a citation still points somewhere a human can find.
        assertTrue(passages.all { it.heading == "Big" })
    }

    @Test
    fun `an empty or missing directory is empty, not an error`() {
        val kb = KnowledgeBase.fromDirectory(java.io.File("no/such/place"))
        assertEquals(0, kb.size)
    }
}
