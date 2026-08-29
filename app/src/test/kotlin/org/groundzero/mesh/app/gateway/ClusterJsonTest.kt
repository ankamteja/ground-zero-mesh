package org.groundzero.mesh.app.gateway

import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.Severity
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterJsonTest {

    private val cluster = SurvivorCluster(
        clusterId = "zone:sector-7",
        zone = "sector-7",
        severity = Severity.DROWNING_IMMINENT,
        effectiveTier = EpistemologyTier.SABDA,
        corroboration = 2,
        dangerScore = 0.6137,
        lastSeenSecondsAgo = 45,
        reportCount = 7,
        recommendedActionRank = 3,
    )

    @Test
    fun emitsEveryFieldTheDashboardReads() {
        val json = ClusterJson.obj(cluster)
        listOf(
            "\"clusterId\":\"zone:sector-7\"",
            "\"zone\":\"sector-7\"",
            "\"severity\":\"DROWNING_IMMINENT\"",
            "\"effectiveTier\":\"SABDA\"",
            "\"corroboration\":2",
            "\"dangerScore\":0.613",
            "\"lastSeenSecondsAgo\":45",
            "\"reportCount\":7",
            "\"recommendedActionRank\":3",
        ).forEach { assertTrue("missing $it in $json", json.contains(it)) }
    }

    @Test
    fun nullRankSerialisesAsJsonNull() {
        val json = ClusterJson.obj(cluster.copy(recommendedActionRank = null))
        assertTrue(json.contains("\"recommendedActionRank\":null"))
    }

    @Test
    fun arrayWraps() {
        val a = ClusterJson.array(listOf(cluster, cluster))
        assertTrue(a.startsWith("[{"))
        assertTrue(a.endsWith("}]"))
    }
}
