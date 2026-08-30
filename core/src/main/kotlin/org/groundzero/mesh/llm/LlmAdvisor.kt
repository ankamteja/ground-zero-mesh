package org.groundzero.mesh.llm

/**
 * One advisory, and an honest account of where it came from.
 *
 * [grounded] false means no model answered and this is the deterministic brief — the panel
 * must say so rather than presenting the two as the same thing. [note] carries the reason.
 */
data class Advisory(
    val text: String,
    val grounded: Boolean,
    val model: String?,
    val sources: List<String>,
    val tookMs: Long,
    val note: String? = null,
)

/** What the advisor can do right now, for the panel's status line. */
data class AdvisorStatus(
    val ollamaUp: Boolean,
    val model: String?,
    val models: List<String>,
    val passages: Int,
    val sources: List<String>,
    val baseUrl: String,
)

/**
 * Retrieval-augmented advisory over the responder board.
 *
 * ### What it does, in order
 *
 * 1. Build a retrieval query from the responder's question and the board itself.
 * 2. Retrieve the top passages from the local corpus ([Retriever] — lexical, deterministic).
 * 3. Prompt a local model with the board facts, those passages, and the rules.
 * 4. Return the answer with its citations — or, on any failure at all, the deterministic
 *    brief with [Advisory.grounded] false and a note saying what went wrong.
 *
 * ### Failure is a first-class path, not an error case
 *
 * The model server is a laptop process that may not be running; the model is several
 * gigabytes that may not be pulled; a 7B on CPU at a flooded perimeter may take longer than
 * anyone will wait. Every one of those ends at the deterministic brief, which needs no
 * model, no network and no corpus. Nothing about the board's function depends on any of
 * this succeeding — that is the whole reason the advisory sits behind a seam that returns
 * text.
 */
class LlmAdvisor(
    private val client: OllamaClient = OllamaClient(),
    val knowledge: KnowledgeBase = KnowledgeBase.bundled(),
    /** Pin a model by name. Null lets [MODEL_PREFERENCE] choose from what the server holds. */
    private val preferredModel: String? = null,
    private val passageLimit: Int = AdvisorPrompt.PASSAGE_LIMIT,
) {

    private val retriever = Retriever(knowledge)

    @Volatile private var cachedModels: List<ModelInfo> = emptyList()
    @Volatile private var cachedAt: Long = 0

    fun status(): AdvisorStatus {
        val models = catalogue(force = true)
        return AdvisorStatus(
            ollamaUp = models.isNotEmpty() || client.isUp(),
            model = choose(models.map { it.name }),
            models = models.map { it.name },
            passages = knowledge.size,
            sources = knowledge.sources,
            baseUrl = client.baseUrl,
        )
    }

    /** Retrieval on its own, for a caller that wants to show what would be cited. */
    fun retrieve(board: BoardView, question: String? = null): List<Retrieved> =
        retriever.search(AdvisorPrompt.query(board, question), passageLimit)

    fun advise(board: BoardView, question: String? = null): Advisory {
        val startedAt = System.currentTimeMillis()
        val hits = retrieve(board, question)
        val catalogue = catalogue(force = false)
        val model = choose(catalogue.map { it.name })
            ?: return fallback(board, question, startedAt, "no model available on ${client.baseUrl}")

        return try {
            val reply = client.chat(
                model = model,
                messages = AdvisorPrompt.messages(board, question, hits),
                // A reasoning model spends most of its wall time on a monologue that is
                // stripped before anyone sees it. Measured on one RTX 4060 with qwen3:8b and
                // a real board: 33s with reasoning, 1.6s without, same answer quality against
                // this prompt. That is the difference between a panel a responder uses and
                // one they stop waiting for.
                disableThinking = catalogue.firstOrNull { it.name == model }?.thinks == true,
            )
            Advisory(
                text = reply.content,
                grounded = true,
                model = reply.model,
                sources = hits.map { it.passage.citation },
                tookMs = System.currentTimeMillis() - startedAt,
            )
        } catch (e: Exception) {
            // Any failure at all — server down, model missing, timeout, empty generation.
            // The board still gets an advisory; it is simply the one that needs no model.
            fallback(board, question, startedAt, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fallback(board: BoardView, question: String?, startedAt: Long, why: String): Advisory {
        val note = if (question.isNullOrBlank()) {
            "no model answered ($why) — deterministic brief shown instead"
        } else {
            // Saying "here is a brief" in answer to a question the brief cannot address is
            // the failure mode worth being loud about: it looks like an answer.
            "no model answered ($why) — this is the deterministic brief, not an answer to the question"
        }
        return Advisory(
            text = board.deterministicBrief(),
            grounded = false,
            model = null,
            sources = emptyList(),
            tookMs = System.currentTimeMillis() - startedAt,
            note = note,
        )
    }

    private fun catalogue(force: Boolean): List<ModelInfo> {
        val now = System.currentTimeMillis()
        if (force || now - cachedAt > MODEL_CACHE_MS || cachedModels.isEmpty()) {
            cachedModels = client.catalogue()
            cachedAt = now
        }
        return cachedModels
    }

    /**
     * Pinned name if it is actually there, else the first [MODEL_PREFERENCE] entry the server
     * holds, else whatever it does hold. A pinned name that is absent falls through rather
     * than failing every request: an advisory from the second-choice model beats none.
     */
    fun choose(available: List<String>): String? {
        if (available.isEmpty()) return null
        preferredModel?.let { pin ->
            available.firstOrNull { it == pin || it.startsWith("$pin:") }?.let { return it }
        }
        for (family in MODEL_PREFERENCE) {
            available.firstOrNull { it.lowercase().contains(family) }?.let { return it }
        }
        return available.first()
    }

    companion object {
        private const val MODEL_CACHE_MS = 30_000L

        /**
         * Preference order, most-wanted first — set by measurement, not by parameter count.
         *
         * Both local candidates were run against a real eight-row board on one RTX 4060,
         * same prompt, same question:
         *
         * - `qwen3:8b` with reasoning switched off — 1.6s, held the format, cited its
         *   passages, and did not restate the board as its own ranking.
         * - `mistral:7b-instruct` — 55s, ignored the length limit, hit the token ceiling
         *   mid-sentence, and enumerated its own "first / second / third priority" list,
         *   which is the one behaviour the prompt explicitly forbids. It cannot actually
         *   reorder anything (see [BoardView]), but a responder reading a model's priority
         *   list beside the real board is exactly the confusion this system is built to
         *   avoid.
         *
         * So the instruction-following model leads, and reasoning is turned off per-request
         * for any model that advertises it. Ordering after the first two is a guess about
         * models nobody here has run; revisit it by measuring rather than by reputation.
         */
        val MODEL_PREFERENCE = listOf("qwen3", "qwen2.5", "llama3", "mistral", "gemma", "phi")
    }
}
