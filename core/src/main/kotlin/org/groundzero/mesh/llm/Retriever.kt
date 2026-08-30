package org.groundzero.mesh.llm

import kotlin.math.ln

/** A passage and why it was picked. [score] is BM25, comparable only within one query. */
data class Retrieved(val passage: Passage, val score: Double)

/**
 * Lexical (BM25) retrieval over the [KnowledgeBase].
 *
 * ### Why not embeddings
 *
 * A vector index is the reflex, and here it would be the wrong trade. It needs a second
 * model pulled and resident beside the chat model, on a laptop at a perimeter that may have
 * no bandwidth to fetch one; it makes retrieval non-deterministic across model versions, so
 * the same board can cite different documents on two runs; and it buys the least where this
 * corpus is — the query is built from the board's own vocabulary (`DROWNING_IMMINENT`,
 * `SABDA`, `unplaced`, zone tags), which is the exact vocabulary the corpus is written in.
 * Lexical overlap is unusually high here, and BM25 is auditable: you can read why a passage
 * ranked.
 *
 * Its real weakness is synonyms — a responder typing "is anyone stuck" would miss a passage
 * that only ever says "entrapment". [SYNONYMS] closes that for the domain terms that
 * actually recur; it is a small, visible, editable list rather than a claim to understand
 * language.
 *
 * Ranking is fully deterministic, ties broken by passage id, so the same board and question
 * always cite the same passages.
 */
class Retriever(private val knowledge: KnowledgeBase) {

    private val docs: List<Doc> = knowledge.passages.map { Doc(it, tokenise(it.heading + "\n" + it.text)) }
    private val averageLength: Double =
        if (docs.isEmpty()) 0.0 else docs.sumOf { it.tokens.size }.toDouble() / docs.size
    private val documentFrequency: Map<String, Int> = buildMap {
        docs.forEach { doc -> doc.tokens.toSet().forEach { merge(it, 1, Int::plus) } }
    }

    fun search(query: String, limit: Int = 5): List<Retrieved> {
        if (docs.isEmpty()) return emptyList()
        val terms = expand(tokenise(query))
        if (terms.isEmpty()) return emptyList()

        return docs.asSequence()
            .map { Retrieved(it.passage, score(terms, it)) }
            .filter { it.score > 0.0 }
            .sortedWith(compareByDescending<Retrieved> { it.score }.thenBy { it.passage.id })
            .take(limit)
            .toList()
    }

    private fun score(terms: List<String>, doc: Doc): Double {
        var total = 0.0
        val length = doc.tokens.size.toDouble()
        for (term in terms.distinct()) {
            val frequency = doc.counts[term] ?: continue
            val df = documentFrequency[term] ?: continue
            val idf = ln(1.0 + (docs.size - df + 0.5) / (df + 0.5))
            val denominator = frequency + K1 * (1 - B + B * length / averageLength)
            total += idf * (frequency * (K1 + 1)) / denominator
        }
        return total
    }

    /**
     * Expansions run back through [tokenise] rather than being trusted as written, so a
     * synonym list entry and a corpus token are normalised the same way — otherwise `hops`
     * in the list never matches the `hop` the tokeniser actually indexed.
     */
    private fun expand(terms: List<String>): List<String> =
        terms + terms.flatMap { tokenise(SYNONYMS[it].orEmpty().joinToString(" ")) }

    private class Doc(val passage: Passage, val tokens: List<String>) {
        val counts: Map<String, Int> = tokens.groupingBy { it }.eachCount()
    }

    companion object {
        const val K1 = 1.2
        const val B = 0.75

        private val STOPWORDS = setOf(
            "the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "be", "it", "that",
            "this", "for", "on", "as", "at", "by", "with", "from", "not", "no", "but", "if",
            "than", "then", "so", "was", "were", "has", "have", "had", "do", "does", "did",
            "what", "which", "who", "how", "why", "when", "where", "can", "will", "would",
            "i", "you", "we", "they", "there", "its", "their",
        )

        /**
         * Domain terms a responder may type that the corpus writes differently. Deliberately
         * one-directional and short: every entry here is a synonym someone can disagree with
         * out loud, which is the point.
         */
        val SYNONYMS: Map<String, List<String>> = mapOf(
            "drown" to listOf("drowning", "water", "swiftwater", "flood"),
            "drowning" to listOf("water", "swiftwater", "flood"),
            "stuck" to listOf("entrapment", "trapped", "pinned"),
            "trapped" to listOf("entrapment", "pinned"),
            "pinned" to listOf("entrapment", "crush"),
            "crush" to listOf("entrapment", "compartment"),
            "collapse" to listOf("structural", "rubble", "void"),
            "rubble" to listOf("structural", "collapse", "void"),
            "sabda" to listOf("relayed", "testimony", "hearsay"),
            "pratyaksa" to listOf("first", "hand", "direct", "observed"),
            "anumana" to listOf("inferred", "sensor", "derived"),
            "hop" to listOf("hops", "distance", "relay"),
            "battery" to listOf("power", "drain", "duty"),
            "gps" to listOf("location", "coordinate", "fix"),
            "location" to listOf("gps", "zone", "placed", "unplaced"),
            "lost" to listOf("silent", "gone", "quiet"),
            "quiet" to listOf("silent", "gone", "stale"),
            "who" to listOf("rank", "priority", "board"),
            "next" to listOf("rank", "priority", "dispatch"),
            "send" to listOf("dispatch", "team", "budget"),
            "team" to listOf("dispatch", "budget", "boat"),
            "trust" to listOf("corroboration", "corroborated", "standing"),
            "fake" to listOf("trust", "corroboration", "single", "uncorroborated"),
        )

        /**
         * Lowercase alphanumeric tokens, stopwords dropped, a single plural `s` stripped.
         *
         * A heavier stemmer (Porter) was not worth it: the corpus is a few thousand words of
         * controlled vocabulary, and aggressive stemming collapses distinctions that matter
         * here (`placed` / `place`, `standing` / `stand`).
         */
        fun tokenise(text: String): List<String> =
            Regex("[a-z0-9]+").findAll(text.lowercase())
                .map { it.value }
                .filter { it.length > 1 && it !in STOPWORDS }
                .map { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }
                .toList()
    }
}
