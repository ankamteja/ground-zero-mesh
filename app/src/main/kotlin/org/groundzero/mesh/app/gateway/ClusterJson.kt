package org.groundzero.mesh.app.gateway

/** Hand-rolled JSON for the fixed [SurvivorCluster] shape the dashboard consumes. */
object ClusterJson {

    fun array(clusters: List<SurvivorCluster>): String =
        clusters.joinToString(",", "[", "]") { obj(it) }

    fun obj(c: SurvivorCluster): String = buildString {
        append('{')
        str("clusterId", c.clusterId); append(',')
        str("zone", c.zone); append(',')
        str("severity", c.severity.name); append(',')
        str("effectiveTier", c.effectiveTier.name); append(',')
        num("corroboration", c.corroboration.toString()); append(',')
        num("dangerScore", trim(c.dangerScore)); append(',')
        num("lastSeenSecondsAgo", c.lastSeenSecondsAgo.toString()); append(',')
        num("reportCount", c.reportCount.toString()); append(',')
        num("recommendedActionRank", c.recommendedActionRank?.toString() ?: "null")
        append('}')
    }

    private fun StringBuilder.str(k: String, v: String) {
        append('"').append(k).append("\":\"")
        for (ch in v) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> append(ch)
        }
        append('"')
    }

    private fun StringBuilder.num(k: String, raw: String) {
        append('"').append(k).append("\":").append(raw)
    }

    private fun trim(d: Double): String {
        val r = (d * 1000).toLong() / 1000.0
        return r.toString()
    }
}
