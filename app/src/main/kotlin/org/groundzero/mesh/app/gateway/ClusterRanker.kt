package org.groundzero.mesh.app.gateway

import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.Severity

/**
 * Folds raw reports into clusters and ranks them for a responder who has finite boats and
 * teams.
 *
 * Order: severity first (a certain, calm observation of a submerging rooftop still outranks
 * a panicking dry-stairwell report), then trust (corroboration + how first-hand the
 * evidence is), then recency, then confidence as a tiebreak.
 *
 * The top [actionBudget] clusters get `recommendedActionRank = 1..N`; the rest are still
 * listed but carry `null` — an unbounded alert list is not a triage tool.
 */
class ClusterRanker(
    private val actionBudget: Int = DEFAULT_ACTION_BUDGET,
) {

    fun rank(reports: List<SurvivorReport>, nowSeconds: Long): List<SurvivorCluster> {
        val clusters = reports
            .groupBy { it.zone }
            .map { (zone, group) -> fold(zone, group, nowSeconds) }
            .sortedWith(rankComparator)

        return clusters.mapIndexed { index, c ->
            c.copy(recommendedActionRank = if (index < actionBudget) index + 1 else null)
        }
    }

    private fun fold(zone: String, group: List<SurvivorReport>, nowSeconds: Long): SurvivorCluster {
        val severity = group.minByOrNull { it.severity.rank }!!.severity
        val tier = group.map { it.effectiveTier }.minByOrNull { tierStrength(it) }
            ?: EpistemologyTier.SABDA
        val corroboration = group.map { it.originNodeId }.distinct().size
        val danger = group.maxOf { it.dangerScore }
        val lastSeen = group.minOf { nowSeconds - it.receivedSeconds }.coerceAtLeast(0)
        return SurvivorCluster(
            clusterId = "zone:$zone",
            zone = zone,
            severity = severity,
            effectiveTier = tier,
            corroboration = corroboration,
            dangerScore = danger,
            lastSeenSecondsAgo = lastSeen,
            reportCount = group.size,
            recommendedActionRank = null,
        )
    }

    private val rankComparator: Comparator<SurvivorCluster> = compareBy<SurvivorCluster>(
        { it.severity.rank },                       // urgency first
        { -trustScore(it) },                        // then corroborated / first-hand
        { it.lastSeenSecondsAgo },                  // then freshest
        { -it.dangerScore },                        // then most confident
    )

    private fun trustScore(c: SurvivorCluster): Int =
        c.corroboration + tierWeight(c.effectiveTier)

    companion object {
        const val DEFAULT_ACTION_BUDGET = 14

        /** Higher = stronger evidence. */
        private fun tierStrength(t: EpistemologyTier): Int = when (t) {
            EpistemologyTier.PRATYAKSA -> 0   // sorts first in minByOrNull => "best"
            EpistemologyTier.ANUMANA -> 1
            EpistemologyTier.SABDA -> 2
        }

        private fun tierWeight(t: EpistemologyTier): Int = when (t) {
            EpistemologyTier.PRATYAKSA -> 2
            EpistemologyTier.ANUMANA -> 1
            EpistemologyTier.SABDA -> 0
        }
    }
}
