package org.groundzero.mesh.gateway

import org.groundzero.mesh.agent.SensoryFlags
import org.groundzero.mesh.propagation.Severity

/**
 * A plain-language situation report for the perimeter station.
 *
 * ### Advisory only, and structurally so
 *
 * This interface takes an **already-ranked** board and returns **text**. It has no way to
 * reorder, promote, hide or delay anything, because it is handed the decision after it has
 * been made and can only describe it. That is a stronger guarantee than a comment asking a
 * future implementer to behave: a model dropped in behind this seam cannot misbehave in the
 * one way that would matter, no matter what it generates.
 *
 * The board is fully functional with no summarizer present, no model loaded and no internet
 * at the perimeter. This is a convenience for a tired human reading a screen at 3am, not a
 * component the rescue depends on.
 */
fun interface TacticalSummarizer {
    fun summarise(board: List<RankedIncident>, snapshot: TwinSnapshot): String
}

/**
 * The deterministic stand-in for the perimeter station's 8B model.
 *
 * Same role the [org.groundzero.mesh.agent.DeterministicSensoryClassifier] plays at L1: the
 * pipeline is real and demonstrable today, and a real model implements the same interface
 * later with nothing downstream changing. It reads the flag byte and the twin, and writes
 * the sentences a dispatcher would write.
 *
 * It states what is *not* known as plainly as what is. An unplaced casualty is reported as
 * unplaced; single-sourced reports are called single-sourced. A summary that reads as more
 * certain than the data is worse than no summary.
 */
class RadminLlmSummarizer : TacticalSummarizer {

    override fun summarise(board: List<RankedIncident>, snapshot: TwinSnapshot): String {
        if (board.isEmpty()) return "No incidents on the board. The mesh is quiet."

        val dispatchable = board.count { it.withinBudget }
        val beyond = board.size - dispatchable
        val lines = ArrayList<String>()

        lines += buildString {
            append("$dispatchable incident")
            if (dispatchable != 1) append("s")
            append(" within dispatch capacity")
            if (beyond > 0) append(", $beyond beyond it")
            append(".")
        }

        val drowning = board.count { it.cluster.severity == Severity.DROWNING_IMMINENT }
        if (drowning > 0) {
            lines += "$drowning drowning-imminent — these outrank everything else regardless " +
                "of how confident the other reports are."
        }

        board.firstOrNull()?.let { top ->
            val node = snapshot.nodes.firstOrNull { it.key == top.cluster.key }
            val where = when {
                node == null -> "location not modelled"
                !node.placed -> "no floor in the zone tag — location unknown"
                else -> "${node.zone}, ${node.floor.label}"
            }
            val evidence = SensoryFlags.describe(top.cluster.flags)
                .ifEmpty { listOf("no sensory detail") }
            lines += "Top of board: ${top.cluster.origin} at $where. " +
                "Evidence: ${evidence.joinToString(", ")}. " +
                "${top.standing.name.lowercase().replace('_', ' ')}, " +
                "${top.cluster.corroborationCount} corroborating relay" +
                (if (top.cluster.corroborationCount == 1) "" else "s") + "."
        }

        if (snapshot.unplacedCount > 0) {
            lines += "${snapshot.unplacedCount} incident(s) could not be placed on a floor. " +
                "Localisation is not solved in this system — treat every position as a zone " +
                "hint, not a coordinate."
        }

        val singleSourced = board.count { it.cluster.corroborationCount == 0 }
        if (singleSourced > 0) {
            lines += "$singleSourced report(s) are single-sourced and uncorroborated."
        }

        lines += "Advisory only. Ordering above was decided by the deterministic ranker; " +
            "nothing here changed it."

        return lines.joinToString("\n")
    }
}
