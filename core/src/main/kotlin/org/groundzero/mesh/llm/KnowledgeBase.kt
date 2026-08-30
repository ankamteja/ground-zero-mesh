package org.groundzero.mesh.llm

import java.io.File

/**
 * One retrievable chunk of the corpus.
 *
 * [source] and [heading] travel with the text all the way to the panel, because an advisory
 * a responder cannot trace back to a document is an advisory they have no way to check.
 */
data class Passage(
    val id: String,
    val source: String,
    val heading: String,
    val text: String,
) {
    /** What the panel and the prompt both cite. */
    val citation: String get() = "$source § $heading"
}

/**
 * The corpus the advisor retrieves from.
 *
 * ### Why a corpus at all
 *
 * A model asked "what does SABDA mean on this row" or "what do I do about a crush-injury
 * casualty" has two ways to answer: from whatever it absorbed in training, or from this
 * project's own written rules and a responder handbook. The first is where an offline 7B
 * invents a protocol that sounds right. Retrieval makes the second one cheap, and makes the
 * answer checkable — every claim in the panel points at a file and a heading.
 *
 * ### Where it lives
 *
 * `core/src/main/resources/knowledge/` — on the classpath, so the advisor works from a bare
 * `java -cp` with no repo checkout and no path guessing. [index] lists the files rather than
 * scanning the directory: classpath directory listing works from a build folder and silently
 * returns nothing from inside a jar, which is exactly the kind of difference that shows up
 * only in the deployment nobody tested.
 *
 * [fromDirectory] additionally loads a folder of local Markdown — an incident-specific
 * annex, a building's floor plan notes, a unit's own SOP — without a rebuild.
 */
class KnowledgeBase(val passages: List<Passage>) {

    val size: Int get() = passages.size

    val sources: List<String> get() = passages.map { it.source }.distinct()

    operator fun plus(other: KnowledgeBase): KnowledgeBase = KnowledgeBase(passages + other.passages)

    companion object {

        const val RESOURCE_ROOT = "knowledge"
        const val INDEX_RESOURCE = "$RESOURCE_ROOT/index.txt"

        /** Longest chunk handed to a model before it is split at a paragraph boundary. */
        const val MAX_CHUNK_CHARS = 1_100

        /** The corpus bundled with `core`. Empty only if the resources were stripped from the build. */
        fun bundled(): KnowledgeBase {
            val loader = KnowledgeBase::class.java.classLoader
            val index = loader.getResourceAsStream(INDEX_RESOURCE)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return KnowledgeBase(emptyList())
            val passages = index.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .flatMap { name ->
                    val text = loader.getResourceAsStream("$RESOURCE_ROOT/$name")
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: return@flatMap emptySequence()
                    chunk(name, text).asSequence()
                }
                .toList()
            return KnowledgeBase(passages)
        }

        /** Every `.md` / `.txt` directly inside [dir], sorted by name so ordering is stable. */
        fun fromDirectory(dir: File): KnowledgeBase {
            if (!dir.isDirectory) return KnowledgeBase(emptyList())
            val files = dir.listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".md") || it.name.endsWith(".txt")) }
                ?.sortedBy { it.name }
                ?: return KnowledgeBase(emptyList())
            return KnowledgeBase(files.flatMap { chunk(it.name, it.readText()) })
        }

        /**
         * Splits Markdown into passages at `##` headings, then again at paragraph boundaries
         * for anything over [MAX_CHUNK_CHARS].
         *
         * Heading-aligned chunks rather than a fixed window: a section of a rescue SOP is
         * already the unit its author meant to be read together, and a window that cuts
         * "do not" from the action it forbids is worse than a slightly uneven chunk.
         */
        fun chunk(source: String, markdown: String): List<Passage> {
            val out = ArrayList<Passage>()
            var heading = "preamble"
            val body = StringBuilder()

            fun flush() {
                val text = body.toString().trim()
                body.setLength(0)
                if (text.isEmpty()) return
                split(text).forEachIndexed { part, chunkText ->
                    val suffix = if (part == 0) "" else "#${part + 1}"
                    out += Passage(
                        id = "$source:${out.size}$suffix",
                        source = source,
                        heading = heading,
                        text = chunkText,
                    )
                }
            }

            for (line in markdown.lines()) {
                val h = Regex("""^#{1,3}\s+(.*)$""").find(line.trim())
                if (h != null) {
                    flush()
                    heading = h.groupValues[1].trim()
                } else {
                    body.append(line).append('\n')
                }
            }
            flush()
            return out
        }

        private fun split(text: String): List<String> {
            if (text.length <= MAX_CHUNK_CHARS) return listOf(text)
            val parts = ArrayList<String>()
            val current = StringBuilder()
            for (paragraph in text.split(Regex("\n\\s*\n"))) {
                if (current.isNotEmpty() && current.length + paragraph.length > MAX_CHUNK_CHARS) {
                    parts += current.toString().trim()
                    current.setLength(0)
                }
                current.append(paragraph).append("\n\n")
            }
            if (current.isNotBlank()) parts += current.toString().trim()
            return parts
        }
    }
}
