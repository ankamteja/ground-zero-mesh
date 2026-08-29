package org.groundzero.mesh.app.gateway

/**
 * Advisory summarisation layered *on top of* the deterministic ranking. It never gates,
 * delays, or reorders what [ClusterRanker] produced — the dashboard renders the ranked
 * list first and folds an advice string in if one is available.
 *
 * Must work with zero internet. [NoopAiAdvisor] is the default and the offline fallback.
 */
interface AiAdvisor {
    /** A short, plain-language line for the responder. Never null-gates the UI. */
    fun summarise(clusters: List<SurvivorCluster>): String
}

/** Deterministic, offline, no model. Always available. */
object NoopAiAdvisor : AiAdvisor {
    override fun summarise(clusters: List<SurvivorCluster>): String {
        if (clusters.isEmpty()) return "No clusters yet."
        val inBudget = clusters.count { it.recommendedActionRank != null }
        val drowning = clusters.count { it.severity.name == "DROWNING_IMMINENT" }
        val top = clusters.firstOrNull()
        return buildString {
            append("$inBudget cluster(s) within the action budget")
            if (drowning > 0) append(", $drowning with imminent-drowning severity")
            append(".")
            if (top != null) append(" Highest: ${top.zone} (${top.severity.name.lowercase()}, x${top.corroboration}).")
        }
    }
}
