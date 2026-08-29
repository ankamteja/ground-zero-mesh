package org.groundzero.mesh.app.gateway

import org.groundzero.mesh.gateway.RankedIncident

/**
 * Hand-rolled JSON for the responder dashboard.
 *
 * The dashboard now consumes core's [RankedIncident] directly — there is no app-side
 * survivor cluster or app-side ranker any more, `core`'s `ResponderRanking` is the one
 * canonical ordering. The field names below are exactly what `assets/dashboard/index.html`
 * reads; the two must move together.
 *
 * `reportCount` has no true source yet: [org.groundzero.mesh.propagation.IncidentCluster]
 * counts distinct relayers, not raw reports folded in. `corroborators.size` is the closest
 * honest proxy (every distinct node that carried this incident, including the first). A
 * real fold count on `IncidentCluster` is flagged to Claude A.
 */
object ClusterJson {

    /**
     * [nowMs] is required for the relative `lastSeenSecondsAgo` and does not come from
     * [RankedIncident], which carries no clock. Pass the same `now` used to rank.
     */
    fun array(ranked: List<RankedIncident>, nowMs: Long): String =
        ranked.mapIndexed { index, incident -> obj(incident, index, nowMs) }
            .joinToString(",", "[", "]")

    fun obj(r: RankedIncident, index: Int, nowMs: Long): String = buildString {
        val c = r.cluster
        append('{')
        str("clusterId", c.key); append(',')
        str("zone", c.zone); append(',')
        str("severity", c.severity.name); append(',')
        // The tier a downstream holder can actually rely on — already relay-downgraded.
        str("effectiveTier", c.tier.name); append(',')
        num("corroboration", c.corroborationCount.toString()); append(',')
        num("dangerScore", trim(c.dangerScore)); append(',')
        num("lastSeenSecondsAgo", (c.ageMs(nowMs) / 1000).coerceAtLeast(0).toString()); append(',')
        num("reportCount", c.corroborators.size.toString()); append(',')
        // index+1 while inside the dispatch budget; null past it — still listed, not prioritised.
        num("recommendedActionRank", if (r.withinBudget) (index + 1).toString() else "null"); append(',')
        num("priority", trim(r.priority)); append(',')
        str("standing", r.standing.label); append(',')
        // The first-hand gate: only a first-hand, above-floor incident may commit a team.
        bool("dispatchable", r.standing.dispatchable); append(',')
        strArray("reasons", r.reasons)
        append('}')
    }

    private fun StringBuilder.str(k: String, v: String) {
        append('"').append(k).append("\":")
        quoted(v)
    }

    private fun StringBuilder.num(k: String, raw: String) {
        append('"').append(k).append("\":").append(raw)
    }

    private fun StringBuilder.bool(k: String, v: Boolean) {
        append('"').append(k).append("\":").append(if (v) "true" else "false")
    }

    private fun StringBuilder.strArray(k: String, values: List<String>) {
        append('"').append(k).append("\":[")
        values.forEachIndexed { i, v ->
            if (i > 0) append(',')
            quoted(v)
        }
        append(']')
    }

    private fun StringBuilder.quoted(v: String) {
        append('"')
        for (ch in v) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
        append('"')
    }

    /** Three decimal places, no trailing-zero fuss — matches what the dashboard shows. */
    private fun trim(d: Double): String {
        val r = (d * 1000).toLong() / 1000.0
        return r.toString()
    }
}
