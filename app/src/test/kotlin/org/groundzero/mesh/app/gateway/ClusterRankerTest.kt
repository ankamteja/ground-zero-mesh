package org.groundzero.mesh.app.gateway

import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClusterRankerTest {

    private val now = 10_000L

    private fun report(
        node: String,
        zone: String,
        severity: Severity,
        tier: EpistemologyTier = EpistemologyTier.PRATYAKSA,
        danger: Double = 0.5,
        receivedSecondsAgo: Long = 30,
    ) = SurvivorReport(
        originNodeId = node,
        zone = zone,
        severity = severity,
        effectiveTier = tier,
        dangerScore = danger,
        incidentSeconds = now - receivedSecondsAgo,
        receivedSeconds = now - receivedSecondsAgo,
    )

    @Test
    fun severityOutranksConfidenceAndCorroboration() {
        val reports = listOf(
            report("a", "dry-stairwell", Severity.OTHER, danger = 0.95),
            report("b", "dry-stairwell", Severity.OTHER, danger = 0.95),
            report("c", "dry-stairwell", Severity.OTHER, danger = 0.95),
            report("d", "submerging-roof", Severity.DROWNING_IMMINENT, danger = 0.40),
        )
        val ranked = ClusterRanker().rank(reports, now)
        assertEquals("submerging-roof", ranked.first().zone)
        assertEquals(1, ranked.first().recommendedActionRank)
    }

    @Test
    fun withinSeverityMoreCorroborationRanksHigher() {
        val reports = listOf(
            report("a", "z1", Severity.STRUCTURAL_ENTRAPMENT),
            report("b", "z2", Severity.STRUCTURAL_ENTRAPMENT),
            report("c", "z2", Severity.STRUCTURAL_ENTRAPMENT),
            report("d", "z2", Severity.STRUCTURAL_ENTRAPMENT),
        )
        val ranked = ClusterRanker().rank(reports, now)
        assertEquals("z2", ranked.first().zone)
        assertEquals(3, ranked.first().corroboration)
    }

    @Test
    fun withinSeverityAndTrustFresherRanksHigher() {
        val reports = listOf(
            report("a", "stale", Severity.OTHER, receivedSecondsAgo = 800),
            report("b", "fresh", Severity.OTHER, receivedSecondsAgo = 20),
        )
        val ranked = ClusterRanker().rank(reports, now)
        assertEquals("fresh", ranked.first().zone)
    }

    @Test
    fun actionBudgetBoundsTheRecommendations() {
        val reports = (1..20).map { report("n$it", "zone$it", Severity.OTHER) }
        val ranked = ClusterRanker(actionBudget = 14).rank(reports, now)
        assertEquals(20, ranked.size)
        assertEquals(14, ranked.count { it.recommendedActionRank != null })
        assertEquals(1, ranked[0].recommendedActionRank)
        assertEquals(14, ranked[13].recommendedActionRank)
        assertNull(ranked[14].recommendedActionRank)
    }

    @Test
    fun foldingAggregatesReports() {
        val reports = listOf(
            report("a", "z", Severity.OTHER, tier = EpistemologyTier.SABDA, danger = 0.2, receivedSecondsAgo = 300),
            report("a", "z", Severity.STRUCTURAL_ENTRAPMENT, tier = EpistemologyTier.ANUMANA, danger = 0.6, receivedSecondsAgo = 90),
            report("b", "z", Severity.OTHER, tier = EpistemologyTier.PRATYAKSA, danger = 0.4, receivedSecondsAgo = 40),
        )
        val c = ClusterRanker().rank(reports, now).single()
        assertEquals(3, c.reportCount)
        assertEquals(2, c.corroboration)
        assertEquals(Severity.STRUCTURAL_ENTRAPMENT, c.severity)   // most urgent present
        assertEquals(EpistemologyTier.PRATYAKSA, c.effectiveTier)  // strongest present
        assertEquals(0.6, c.dangerScore, 1e-9)
        assertEquals(40L, c.lastSeenSecondsAgo)                    // freshest report
    }
}
