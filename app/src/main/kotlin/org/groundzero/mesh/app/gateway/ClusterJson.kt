package org.groundzero.mesh.app.gateway

import org.groundzero.mesh.agent.SensoryFlags
import org.groundzero.mesh.gateway.RankedIncident
import org.groundzero.mesh.gateway.TwinNode

/**
 * Hand-rolled JSON for the responder dashboard.
 *
 * The dashboard now consumes core's [RankedIncident] directly — there is no app-side
 * survivor cluster or app-side ranker any more, `core`'s `ResponderRanking` is the one
 * canonical ordering. The field names below are exactly what `assets/dashboard/index.html`
 * reads; the two must move together.
 *
 * `reportCount` is [org.groundzero.mesh.propagation.IncidentCluster.reportCount] — every
 * `DedupCluster.ingest` fold, including a repeat from a relay that already reported this
 * incident. `corroboration` is a different, smaller number: distinct relayers beyond the
 * first (see [org.groundzero.mesh.propagation.IncidentCluster.corroborationCount]).
 */
object ClusterJson {

    /**
     * [nowMs] is required for the relative `lastSeenSecondsAgo` and does not come from
     * [RankedIncident], which carries no clock. Pass the same `now` used to rank.
     *
     * [twinNodes] is [org.groundzero.mesh.gateway.DigitalTwin]'s spatial projection of this
     * same board, keyed by [org.groundzero.mesh.propagation.IncidentCluster.key] — the same
     * shape `SimulationRunner.toJson()` already folds per node for the CLI demo. Omitted, a
     * cluster serialises with no `floor`/`placed`/`position` fields rather than fake ones.
     */
    fun array(
        ranked: List<RankedIncident>,
        nowMs: Long,
        twinNodes: Map<String, TwinNode> = emptyMap(),
    ): String =
        ranked.mapIndexed { index, incident -> obj(incident, index, nowMs, twinNodes[incident.cluster.key]) }
            .joinToString(",", "[", "]")

    fun obj(r: RankedIncident, index: Int, nowMs: Long, twin: TwinNode? = null): String = buildString {
        val c = r.cluster
        append('{')
        str("clusterId", c.key); append(',')
        // Which peer a "found / safe" action should target — see GatewayServer's /resolve.
        str("origin", c.origin.canonical()); append(',')
        str("zone", c.zone); append(',')
        str("severity", c.severity.name); append(',')
        // The tier a downstream holder can actually rely on — already relay-downgraded.
        str("effectiveTier", c.tier.name); append(',')
        num("corroboration", c.corroborationCount.toString()); append(',')
        num("dangerScore", trim(c.dangerScore)); append(',')
        num("lastSeenSecondsAgo", (c.ageMs(nowMs) / 1000).coerceAtLeast(0).toString()); append(',')
        num("reportCount", c.reportCount.toString()); append(',')
        // The nearest this incident has ever reached us over the radio — the honest proxy
        // for "how far away" that a mesh with no GPS/RSSI ranging actually has. See the
        // localisation entry in TODO.md's open assumptions.
        num("minHops", c.minHops.toString()); append(',')
        // A position for this incident, when there is one — distinct from both the hop-count
        // proxy above and the schematic `position` below. Null (not omitted) when absent,
        // matching `recommendedActionRank`'s and `floor`'s own null-vs-fake convention just
        // below.
        //
        // `gpsSource` is not decoration: a satellite fix is a measurement and a self-reported
        // one is the person's own estimate of where they are, and a responder deciding where
        // to send a team must be able to tell those apart. Anything rendering these two
        // numbers is expected to render this too.
        num("gpsLat", c.gpsLat?.let { gps(it) } ?: "null"); append(',')
        num("gpsLon", c.gpsLon?.let { gps(it) } ?: "null"); append(',')
        num("gpsSource", c.gpsSource?.let { "\"${it.name}\"" } ?: "null"); append(',')
        // index+1 while inside the dispatch budget; null past it — still listed, not prioritised.
        num("recommendedActionRank", if (r.withinBudget) (index + 1).toString() else "null"); append(',')
        num("priority", trim(r.priority)); append(',')
        str("standing", r.standing.label); append(',')
        // The first-hand gate: only a first-hand, above-floor incident may commit a team.
        bool("dispatchable", r.standing.dispatchable); append(',')
        str("flags", SensoryFlags.toHex(c.flags)); append(',')
        strArray("evidence", SensoryFlags.describe(c.flags)); append(',')
        num("vector", "[" + (c.featureVector?.toList()?.joinToString(",") { trim(it.toDouble()) } ?: "") + "]"); append(',')
        if (twin != null) {
            // TwinFloor's unplaced sentinel is Int.MIN_VALUE — a real index for internal
            // comparisons, not a number a JSON consumer should ever see or use.
            num("floor", if (twin.placed) twin.floor.index.toString() else "null"); append(',')
            str("floorLabel", twin.floor.label); append(',')
            bool("placed", twin.placed); append(',')
            num(
                "position",
                "{\"x\":${trim(twin.position.x)},\"y\":${trim(twin.position.y)},\"z\":${trim(twin.position.z)}}",
            )
            append(',')
        }
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

    /**
     * Six decimal places (~11cm at the equator) rather than [trim]'s three (~111m) — the
     * wire codec stores GPS as an exact `f32` specifically to avoid the feature vector's
     * quantisation loss (see `CompactCodec`'s layout doc), and rounding it away here on the
     * last hop to the dashboard would throw away the precision that survived the whole trip.
     */
    private fun gps(v: Float): String = String.format(java.util.Locale.ROOT, "%.6f", v)
}
