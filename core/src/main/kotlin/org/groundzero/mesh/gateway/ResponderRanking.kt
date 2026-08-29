package org.groundzero.mesh.gateway

import org.groundzero.mesh.propagation.FirstHandGate
import org.groundzero.mesh.propagation.IncidentCluster
import org.groundzero.mesh.propagation.Severity

/**
 * One row of the responder board.
 *
 * [reasons] exists because a ranked list a responder cannot interrogate is a ranked list
 * they will not trust. Every entry can say why it sits where it sits.
 */
data class RankedIncident(
    val cluster: IncidentCluster,
    val priority: Double,
    val standing: FirstHandGate.Standing,
    /** True while this fits inside the dispatch budget. */
    val withinBudget: Boolean,
    val reasons: List<String>,
)

/**
 * Deterministic triage ordering for the perimeter dashboard.
 *
 * ### Severity first, and not as a weight
 *
 * Ordering is **lexicographic**, not a weighted sum: severity, then how the incident is
 * known, then confidence, then recency. Severity is a time-to-death ordering, so no amount
 * of confidence about a structural entrapment may outrank a drowning. A weighted sum would
 * permit exactly that trade, and it is not a trade anyone should be allowed to make
 * implicitly in a scoring function.
 *
 * ### The scarcity budget
 *
 * Responders have a finite number of boats and teams. Ranking under an explicit budget is
 * more honest than emitting an unbounded alert list: the boundary is drawn where the
 * resources actually run out, and everything past it is still shown, still ordered, and
 * labelled as beyond current capacity rather than quietly dropped.
 *
 * ### Advisory only
 *
 * This is deterministic and complete on its own. `AiAdvisor` may re-summarise or annotate
 * what comes out, but it never decides what appears here, and the dashboard is fully
 * functional with no model present and no internet at the perimeter.
 */
object ResponderRanking {

    /** Ported: rolling window and action budget from the reference risk-manager. */
    const val BUDGET_WINDOW = 42
    const val BUDGET_ACTIONS = 14

    /** Beyond this a report is stale enough to rank below fresher ones. */
    const val RECENCY_HORIZON_MS = 15L * 60L * 1000L

    fun rank(
        clusters: List<IncidentCluster>,
        nowMs: Long,
        budget: Int = BUDGET_ACTIONS,
    ): List<RankedIncident> {
        val ordered = clusters.sortedWith(
            compareBy<IncidentCluster> { it.severity.rank }
                .thenBy { standingOrder(it) }
                .thenByDescending { it.dangerScore }
                .thenByDescending { it.corroborationCount }
                .thenBy { it.ageMs(nowMs) }
                .thenBy { it.key },
        )

        return ordered.mapIndexed { index, cluster ->
            RankedIncident(
                cluster = cluster,
                priority = FirstHandGate.cappedPriority(cluster, rawPriority(cluster, nowMs)),
                standing = FirstHandGate.standing(cluster),
                withinBudget = index < budget,
                reasons = reasonsFor(cluster, nowMs),
            )
        }
    }

    /** Only the incidents a responder can actually act on right now. */
    fun dispatchable(ranked: List<RankedIncident>): List<RankedIncident> =
        ranked.filter { it.withinBudget && it.standing.dispatchable }

    private fun standingOrder(cluster: IncidentCluster): Int =
        when (FirstHandGate.standing(cluster)) {
            FirstHandGate.Standing.CONFIRMED_FIRST_HAND -> 0
            FirstHandGate.Standing.CORROBORATED_TESTIMONY -> 1
            FirstHandGate.Standing.SINGLE_UNCONFIRMED -> 2
            FirstHandGate.Standing.BELOW_FLOOR -> 3
        }

    /**
     * A continuous 0..1 priority for display and for the AI advisor to annotate.
     *
     * The ordering above does not depend on this number — it is derived from the same
     * facts, for humans. Ranking on a float and displaying a different float is how a board
     * starts disagreeing with itself.
     */
    private fun rawPriority(cluster: IncidentCluster, nowMs: Long): Double {
        val severityTerm = when (cluster.severity) {
            Severity.DROWNING_IMMINENT -> 1.00
            Severity.STRUCTURAL_ENTRAPMENT -> 0.80
            Severity.OTHER -> 0.55
        }
        val knowledgeTerm = if (cluster.firstHandHeld) 1.0 else 0.75
        val corroborationTerm = 1.0 + (0.05 * cluster.corroborationCount).coerceAtMost(0.15)
        val staleness = (cluster.ageMs(nowMs).toDouble() / RECENCY_HORIZON_MS).coerceIn(0.0, 1.0)
        val recencyTerm = 1.0 - 0.25 * staleness

        return (severityTerm * knowledgeTerm * corroborationTerm * recencyTerm * cluster.dangerScore)
            .coerceIn(0.0, 1.0)
    }

    private fun reasonsFor(cluster: IncidentCluster, nowMs: Long): List<String> = buildList {
        add(
            when (cluster.severity) {
                Severity.DROWNING_IMMINENT -> "drowning imminent — minutes, not hours"
                Severity.STRUCTURAL_ENTRAPMENT -> "structural entrapment"
                Severity.OTHER -> "reported in distress"
            },
        )
        add(FirstHandGate.standing(cluster).label)
        if (cluster.corroborationCount > 0) {
            add("corroborated by " + cluster.corroborationCount + " other node(s)")
        }
        add("nearest report " + cluster.minHops + " hop(s) away in " + cluster.zone)
        val ageSeconds = cluster.ageMs(nowMs) / 1000
        add("last heard " + ageSeconds + "s ago")
        cluster.slmSummary?.let { add("on-device summary: " + it) }
    }
}
