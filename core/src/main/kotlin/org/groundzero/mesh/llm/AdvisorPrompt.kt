package org.groundzero.mesh.llm

/**
 * Everything the model is allowed to see, and the rules it is held to.
 *
 * ### The prompt is the safety boundary that is *visible*
 *
 * The structural guarantee — a model cannot reorder the board because it is handed the board
 * after ranking and returns text — is made by [BoardView] and the `TacticalSummarizer` seam,
 * not by these words. What the prompt adds is the second, weaker but still useful boundary:
 * it tells the model to answer from the facts and the retrieved passages only, to say
 * plainly when something is unknown, and never to state a position as a coordinate. A model
 * that ignores all of it still cannot touch the board; it can only produce a bad paragraph,
 * which is why the paragraph is labelled advisory everywhere it is shown.
 *
 * ### Facts, then context, then the question
 *
 * The board goes in as a compact table of the same fields the responder is looking at, so a
 * mismatch between panel and screen is visible immediately rather than being an invisible
 * paraphrase. Retrieved passages follow, each with the citation the answer must use.
 */
object AdvisorPrompt {

    const val SYSTEM = """You are the perimeter station advisor for Ground-Zero Mesh, a disaster mesh network.
A rescue responder is reading a triage board on a screen. You annotate it. You never rank it.

Hard rules:
- Answer ONLY from the BOARD FACTS and the REFERENCE passages given below. If they do not
  contain the answer, say "not in the board data" and stop. Never fill a gap with training
  knowledge or a plausible guess.
- Never propose a different order for the board. The order is decided by a deterministic
  ranker: severity first, then how the incident is known, then confidence, then recency. You
  may explain that order. You may not contest it or suggest skipping a row.
- Positions are schematic. Zone tags and hop counts are coarse proxies, never coordinates.
  Only a GPS field that is present is a real location, and it is usually absent.
- Say what is NOT known as plainly as what is: unplaced, single-sourced, stale, no evidence.
- Cite the reference you used by copying its bracketed label exactly as it appears above,
  file name included — [board-and-fields.md § Standing], not [source § Standing]. Never
  cite a passage you were not given.
- NEVER list the board back row by row. The responder is looking at it on the same screen;
  repeating it wastes the only seconds they have. Name at most TWO incidents, and only to
  say something the row itself does not already say.
- Be brief and flat: at most 4 short lines, no headings, no markdown, no bullet characters,
  no preamble, no restating the question. A tired responder at 3am reads the first line and
  acts.
- End with nothing about being an AI. The panel already says the advisory is non-binding."""

    /** How many corpus passages go into one prompt. Enough to answer, short enough to stay read. */
    const val PASSAGE_LIMIT = 4

    /**
     * A compact, complete rendering of the board.
     *
     * Every row carries the fields a responder can see on their own screen, in the same
     * words, so the model has no reason to invent a summary of a field it was not given, and
     * a wrong answer is checkable against the panel next to it.
     */
    fun facts(board: BoardView): String {
        if (board.incidents.isEmpty()) {
            return "BOARD FACTS\nNo incidents on the board. The mesh is quiet.\n" +
                (board.selfNode?.let { "This responder device: $it\n" } ?: "")
        }
        val sb = StringBuilder("BOARD FACTS\n")
        sb.append(board.incidents.size).append(" incident(s); ")
            .append(board.withinBudget).append(" within the dispatch budget (")
            .append(board.dispatchableCount).append(" of those first-hand and dispatchable); ")
            .append(board.unplacedCount).append(" unplaced; ")
            .append(board.singleSourced).append(" single-sourced.\n")
        board.selfNode?.let { sb.append("This responder device: ").append(it).append('\n') }
        if (board.carriers.isNotEmpty()) {
            sb.append("Reports reached this device through: ")
                .append(board.carriers.joinToString(", ")).append(
                    " (these peers handed us the report; it is not a measured radio topology).\n",
                )
        }
        if (board.deterministicAdvice.isNotBlank()) {
            sb.append("The board's own deterministic line: ").append(board.deterministicAdvice).append('\n')
        }
        sb.append('\n')
        board.incidents.forEach { i ->
            sb.append(if (i.actionRank != null) "#${i.actionRank}" else "#— (beyond budget)")
                .append(" ").append(i.origin)
                .append(" | severity=").append(i.severity)
                .append(" | zone=").append(i.zone)
                .append(" | floor=").append(if (i.placed) i.floorLabel ?: "unknown" else "UNPLACED")
                .append(" | evidence-tier=").append(i.tier)
                .append(" | standing=").append(i.standing)
                .append(" | dispatchable=").append(i.dispatchable)
                .append(" | corroborating relays=").append(i.corroboration)
                .append(" | reports folded=").append(i.reportCount)
                .append(" | distance=").append(i.minHops).append(" hop(s)")
                .append(" | danger=").append(round(i.dangerScore))
                .append(" | priority=").append(round(i.priority))
                .append(" | last heard ").append(i.lastSeenSecondsAgo).append("s ago")
            // Spelled out rather than left as an enum name: the model has to be able to say
            // "they marked this themselves" to a responder, and a bare SELF_REPORTED invites
            // it to read a person's guess as a measurement.
            sb.append(" | position=").append(
                when {
                    i.gpsLat == null || i.gpsLon == null -> "none"
                    i.gpsSource == "SELF_REPORTED" ->
                        "${i.gpsLat},${i.gpsLon} (marked by the person themselves, not a satellite fix — may be wrong)"
                    else -> "${i.gpsLat},${i.gpsLon} (satellite fix)"
                },
            )
            sb.append('\n')
            sb.append("    evidence: ")
                .append(i.evidence.ifEmpty { listOf("none reported") }.joinToString(", ")).append('\n')
            if (i.reasons.isNotEmpty()) {
                sb.append("    ranked here because: ").append(i.reasons.joinToString("; ")).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * The retrieval query.
     *
     * With a question, the responder's own words drive it, with the board's top row appended
     * so "what do I do next" retrieves against the incident that actually leads the board.
     * With no question — the one-press brief — the board *is* the query: its severities,
     * evidence tokens and standing labels are the vocabulary the corpus is written in.
     */
    fun query(board: BoardView, question: String?): String {
        val top = board.incidents.firstOrNull()
        val boardTerms = buildString {
            board.incidents.take(3).forEach { i ->
                append(i.severity).append(' ').append(i.zone).append(' ')
                    .append(i.standing).append(' ').append(i.tier).append(' ')
                    .append(i.evidence.joinToString(" ")).append(' ')
                if (!i.placed) append("unplaced location unknown ")
                if (i.corroboration == 0) append("single-sourced uncorroborated ")
            }
            if (board.incidents.isEmpty()) append("quiet mesh no incidents waiting")
        }
        return if (question.isNullOrBlank()) {
            "responder briefing dispatch priority $boardTerms"
        } else {
            question + " " + (top?.severity ?: "") + " " + (top?.zone ?: "") + " " + boardTerms
        }
    }

    /** The reference block, or an honest empty marker when retrieval found nothing. */
    fun context(hits: List<Retrieved>): String {
        if (hits.isEmpty()) {
            return "REFERENCE\n(no reference passage matched — answer from the board facts alone, " +
                "and cite nothing.)\n"
        }
        val sb = StringBuilder("REFERENCE\n")
        hits.forEach { hit ->
            sb.append("[").append(hit.passage.citation).append("]\n")
                .append(hit.passage.text.trim()).append("\n\n")
        }
        return sb.toString()
    }

    fun messages(board: BoardView, question: String?, hits: List<Retrieved>): List<ChatMessage> {
        val task = if (question.isNullOrBlank()) {
            "Brief the responder on the board as it stands. Lead with what to act on and why " +
                "it sits where it does, then the single most important uncertainty. Do not " +
                "enumerate the rows."
        } else {
            "The responder asks: " + question.trim() +
                "\nAnswer that question directly. Do not enumerate the rows."
        }
        val user = buildString {
            append(facts(board)).append('\n')
            append(context(hits)).append('\n')
            append("TASK\n").append(task)
        }
        return listOf(ChatMessage("system", SYSTEM), ChatMessage("user", user))
    }

    private fun round(d: Double): String = ((d * 100).toLong() / 100.0).toString()
}
