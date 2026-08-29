package org.groundzero.mesh.gateway

import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.IncidentCluster
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponderRankingTest {

    private var next = 1L

    private fun cluster(
        severity: Severity,
        score: Double = 0.9,
        firstHand: Boolean = true,
        corroborators: Int = 1,
        ageMs: Long = 0,
        zone: String = "sector-7",
        key: String = "k" + next++,
    ) = IncidentCluster(
        key = key,
        origin = NodeId(next),
        zone = zone,
        severity = severity,
        dangerScore = score,
        tier = if (firstHand) EpistemologyTier.PRATYAKSA else EpistemologyTier.SABDA,
        corroborators = (1..corroborators).map { NodeId(it.toLong() + 100) }.toSet(),
        minHops = if (firstHand) 0 else 2,
        firstSeenMs = 0,
        lastUpdatedMs = -ageMs,
        firstHandHeld = firstHand,
    )

    @Test
    fun `drowning outranks entrapment, whatever the confidence`() {
        // The ordering is lexicographic on severity precisely so this trade cannot be made
        // implicitly. A weighted sum would let a very confident entrapment outrank a less
        // confident drowning, and that is not a trade a scoring function should be allowed
        // to make on a responder's behalf.
        val ranked = ResponderRanking.rank(
            listOf(
                cluster(Severity.STRUCTURAL_ENTRAPMENT, score = 1.0),
                cluster(Severity.DROWNING_IMMINENT, score = 0.5),
            ),
            nowMs = 0,
        )

        assertEquals(Severity.DROWNING_IMMINENT, ranked.first().cluster.severity)
    }

    @Test
    fun `first-hand outranks testimony at equal severity`() {
        val ranked = ResponderRanking.rank(
            listOf(
                cluster(Severity.DROWNING_IMMINENT, firstHand = false, corroborators = 5),
                cluster(Severity.DROWNING_IMMINENT, firstHand = true),
            ),
            nowMs = 0,
        )

        assertTrue(ranked.first().cluster.firstHandHeld)
    }

    @Test
    fun `corroboration breaks a tie between two testimony reports`() {
        val ranked = ResponderRanking.rank(
            listOf(
                cluster(Severity.OTHER, firstHand = false, corroborators = 1, score = 0.8),
                cluster(Severity.OTHER, firstHand = false, corroborators = 4, score = 0.8),
            ),
            nowMs = 0,
        )

        assertEquals(3, ranked.first().cluster.corroborationCount)
    }

    @Test
    fun `the scarcity budget marks where the boats run out`() {
        val many = (1..20).map { cluster(Severity.DROWNING_IMMINENT) }
        val ranked = ResponderRanking.rank(many, nowMs = 0, budget = ResponderRanking.BUDGET_ACTIONS)

        assertEquals(20, ranked.size, "everything is still shown")
        assertEquals(
            ResponderRanking.BUDGET_ACTIONS, ranked.count { it.withinBudget },
            "but only what there is capacity for is marked actionable",
        )
        assertTrue(ranked.take(ResponderRanking.BUDGET_ACTIONS).all { it.withinBudget })
        assertTrue(ranked.drop(ResponderRanking.BUDGET_ACTIONS).none { it.withinBudget })
    }

    @Test
    fun `nothing is silently dropped for being beyond capacity`() {
        val many = (1..30).map { cluster(Severity.OTHER) }
        assertEquals(30, ResponderRanking.rank(many, nowMs = 0).size)
    }

    @Test
    fun `only first-hand incidents inside the budget are dispatchable`() {
        val ranked = ResponderRanking.rank(
            listOf(
                cluster(Severity.DROWNING_IMMINENT, firstHand = true),
                cluster(Severity.DROWNING_IMMINENT, firstHand = false, corroborators = 9),
            ),
            nowMs = 0,
        )

        val dispatchable = ResponderRanking.dispatchable(ranked)
        assertEquals(1, dispatchable.size)
        assertTrue(dispatchable.single().cluster.firstHandHeld)
    }

    @Test
    fun `testimony priority stays under the advisory cap`() {
        val ranked = ResponderRanking.rank(
            listOf(cluster(Severity.DROWNING_IMMINENT, firstHand = false, score = 1.0, corroborators = 20)),
            nowMs = 0,
        )
        assertTrue(ranked.single().priority <= org.groundzero.mesh.propagation.FirstHandGate.ADVISORY_CAP)
    }

    @Test
    fun `every row can say why it is where it is`() {
        val ranked = ResponderRanking.rank(
            listOf(cluster(Severity.DROWNING_IMMINENT, firstHand = true, corroborators = 3)),
            nowMs = 0,
        )
        val reasons = ranked.single().reasons

        assertTrue(reasons.any { it.contains("drowning") })
        assertTrue(reasons.any { it.contains("first-hand") })
        assertTrue(reasons.any { it.contains("corroborated by") })
        assertTrue(reasons.any { it.contains("hop") })
    }

    @Test
    fun `ranking is stable for identical inputs`() {
        val clusters = listOf(
            cluster(Severity.DROWNING_IMMINENT, key = "a"),
            cluster(Severity.DROWNING_IMMINENT, key = "b"),
            cluster(Severity.DROWNING_IMMINENT, key = "c"),
        )
        val first = ResponderRanking.rank(clusters, nowMs = 0).map { it.cluster.key }
        val second = ResponderRanking.rank(clusters.reversed(), nowMs = 0).map { it.cluster.key }

        assertEquals(first, second, "a board that reorders itself between refreshes is unusable")
    }

    @Test
    fun `a fresher report outranks a stale one, all else equal`() {
        val ranked = ResponderRanking.rank(
            listOf(
                cluster(Severity.OTHER, ageMs = 10L * 60L * 1000L, key = "stale"),
                cluster(Severity.OTHER, ageMs = 0, key = "fresh"),
            ),
            nowMs = 0,
        )
        assertEquals("fresh", ranked.first().cluster.key)
    }

    @Test
    fun `an empty board is an empty list, not an error`() {
        assertTrue(ResponderRanking.rank(emptyList(), nowMs = 0).isEmpty())
    }
}
