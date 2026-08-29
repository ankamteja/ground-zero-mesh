package org.groundzero.mesh.app.gateway

import org.groundzero.mesh.agent.SlmFeatureVector
import org.groundzero.mesh.gateway.DigitalTwin
import org.groundzero.mesh.gateway.ResponderRanking
import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.IncidentCluster
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard reads `core`'s [org.groundzero.mesh.gateway.RankedIncident] via
 * [ClusterJson] now. These feed hand-built [IncidentCluster] lists through the real
 * [ResponderRanking] and assert the JSON shape and the order the responder sees.
 */
class ClusterJsonTest {

    private val now = 1_000_000L

    private fun cluster(
        key: String,
        zone: String,
        severity: Severity,
        danger: Double,
        tier: EpistemologyTier = EpistemologyTier.SABDA,
        relayers: Int = 1,
        firstHand: Boolean = false,
        minHops: Int = 1,
        ageSeconds: Long = 30,
        slm: String? = null,
        flags: Byte = 0,
        vector: SlmFeatureVector? = null,
        reportCount: Int = relayers,
    ) = IncidentCluster(
        key = key,
        origin = NodeId(1L),
        zone = zone,
        severity = severity,
        dangerScore = danger,
        tier = tier,
        corroborators = (1..relayers).map { NodeId(it.toLong()) }.toSet(),
        minHops = minHops,
        firstSeenMs = now - ageSeconds * 1000,
        lastUpdatedMs = now - ageSeconds * 1000,
        slmSummary = slm,
        flags = flags,
        featureVector = vector,
        firstHandHeld = firstHand,
        reportCount = reportCount,
    )

    private fun json(
        clusters: List<IncidentCluster>,
        budget: Int = ResponderRanking.BUDGET_ACTIONS,
        withTwin: Boolean = false,
    ): String {
        val ranked = ResponderRanking.rank(clusters, now, budget)
        val twinNodes = if (withTwin) DigitalTwin.snapshot(ranked, now).nodes.associateBy { it.key } else emptyMap()
        return ClusterJson.array(ranked, now, twinNodes)
    }

    @Test
    fun emitsEveryFieldTheDashboardReads() {
        val j = json(
            listOf(
                cluster(
                    key = "n1-500",
                    zone = "sector-7",
                    severity = Severity.DROWNING_IMMINENT,
                    danger = 0.8,
                    tier = EpistemologyTier.PRATYAKSA,
                    relayers = 3,
                    firstHand = true,
                    minHops = 2,
                    ageSeconds = 45,
                    slm = "water rising",
                ),
            ),
        )
        listOf(
            "\"clusterId\":\"n1-500\"",
            "\"zone\":\"sector-7\"",
            "\"severity\":\"DROWNING_IMMINENT\"",
            "\"effectiveTier\":\"PRATYAKSA\"",
            "\"corroboration\":2",          // corroborators.size - 1
            "\"dangerScore\":0.8",
            "\"lastSeenSecondsAgo\":45",
            "\"reportCount\":3",            // corroborators.size
            "\"minHops\":2",
            "\"recommendedActionRank\":1",
            "\"standing\":\"confirmed — first-hand\"",
            "\"dispatchable\":true",
            "\"reasons\":[",
        ).forEach { assertTrue("missing $it in $j", j.contains(it)) }
    }

    @Test
    fun severityOrdersLexicographicallyNotByConfidence() {
        // A calm, corroborated, high-confidence report from a dry stairwell must still
        // sort below an uncertain drowning. Mirrors ResponderRankingTest in core.
        val j = json(
            listOf(
                cluster("a-1", "dry-stairwell", Severity.OTHER, danger = 0.95, relayers = 4, firstHand = true),
                cluster("b-1", "submerging-roof", Severity.DROWNING_IMMINENT, danger = 0.40, firstHand = true),
            ),
        )
        assertTrue(
            "drowning must appear before other-severity",
            j.indexOf("\"zone\":\"submerging-roof\"") < j.indexOf("\"zone\":\"dry-stairwell\""),
        )
        // The drowning row is rank 1, the stairwell row is rank 2 — both inside budget.
        assertTrue(j.contains("\"recommendedActionRank\":1"))
        assertTrue(j.contains("\"recommendedActionRank\":2"))
    }

    @Test
    fun budgetBoundaryNullsTheRankPastCapacity() {
        val clusters = (1..5).map {
            cluster("k$it-1", "zone$it", Severity.OTHER, danger = 0.6)
        }
        val j = json(clusters, budget = 2)
        assertEquals(2, "\"recommendedActionRank\":1|\"recommendedActionRank\":2".occurrencesIn(j))
        assertEquals(3, "\"recommendedActionRank\":null".occurrencesIn(j))
        assertFalse("nothing past the budget gets a number", j.contains("\"recommendedActionRank\":3"))
    }

    @Test
    fun reportCountIsTheRawFoldCountNotJustDistinctRelayers() {
        // 5 distinct relayers, but 9 folds — a repeat relay bumps reportCount without adding
        // a new corroborator. See IncidentCluster.reportCount / DedupClusterTest in core.
        val j = json(
            listOf(cluster("k-1", "z", Severity.STRUCTURAL_ENTRAPMENT, danger = 0.7, relayers = 5, reportCount = 9)),
        )
        assertTrue(j.contains("\"reportCount\":9"))
        assertTrue(j.contains("\"corroboration\":4"))
    }

    @Test
    fun standingReflectsTheFirstHandGate() {
        val testimony = json(
            listOf(cluster("k-1", "z", Severity.OTHER, danger = 0.7, relayers = 3, firstHand = false)),
        )
        assertTrue(testimony.contains("\"standing\":\"corroborated — testimony\""))
        assertTrue(testimony.contains("\"dispatchable\":false"))

        val belowFloor = json(
            listOf(cluster("k-2", "z", Severity.OTHER, danger = 0.20, firstHand = true)),
        )
        assertTrue(belowFloor.contains("\"standing\":\"below reporting floor\""))
    }

    @Test
    fun serialisesSensoryFlagsAndEvidenceForTheRealDashboard() {
        // MANUAL_SOS (0x20) | AUDIO_WATER (0x01) = 0x21 — the flag byte the on-phone gateway
        // must forward, not just the CLI simulation's dashboard.
        val j = json(listOf(cluster("k-1", "z", Severity.OTHER, danger = 0.7, flags = 0x21)))
        assertTrue(j.contains("\"flags\":\"0x21\""))
        assertTrue(j.contains("\"evidence\":[\"manual SOS\",\"rushing water\"]"))
    }

    @Test
    fun serialisesTheFeatureVectorWhenTheClusterHasOne() {
        val withVector = json(listOf(cluster("k-1", "z", Severity.OTHER, danger = 0.7, vector = SlmFeatureVector.ZERO)))
        assertTrue(withVector.contains("\"vector\":[0.0,0.0"))

        val withoutVector = json(listOf(cluster("k-2", "z", Severity.OTHER, danger = 0.6)))
        assertTrue(withoutVector.contains("\"vector\":[]"))
    }

    @Test
    fun foldsInTheDigitalTwinProjectionWhenGiven() {
        val withTwin = json(
            listOf(cluster("k-1", "floor-2-east", Severity.OTHER, danger = 0.6)),
            withTwin = true,
        )
        assertTrue(withTwin.contains("\"floor\":2"))
        assertTrue(withTwin.contains("\"floorLabel\":\"floor 2\""))
        assertTrue(withTwin.contains("\"placed\":true"))
        assertTrue(withTwin.contains("\"position\":{"))

        val withoutTwin = json(listOf(cluster("k-2", "floor-2-east", Severity.OTHER, danger = 0.6)))
        assertFalse("no twin passed in means no spatial fields, not fake ones", withoutTwin.contains("\"floor\":"))
    }

    @Test
    fun arrayWrapsAndEmptyIsJsonArray() {
        assertEquals("[]", ClusterJson.array(emptyList(), now))
        val j = json(listOf(cluster("k-1", "z", Severity.OTHER, danger = 0.6)))
        assertTrue(j.startsWith("[{"))
        assertTrue(j.endsWith("}]"))
    }

    /** Count non-overlapping occurrences of any of the `|`-separated needles. */
    private fun String.occurrencesIn(haystack: String): Int =
        split("|").sumOf { needle ->
            var i = 0; var n = 0
            while (true) {
                val at = haystack.indexOf(needle, i)
                if (at < 0) break
                n++; i = at + needle.length
            }
            n
        }
}
