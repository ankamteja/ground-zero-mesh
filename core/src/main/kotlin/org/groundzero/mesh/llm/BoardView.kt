package org.groundzero.mesh.llm

import org.groundzero.mesh.agent.SensoryFlags
import org.groundzero.mesh.gateway.RankedIncident
import org.groundzero.mesh.gateway.TwinNode
import org.groundzero.mesh.gateway.TwinSnapshot

/**
 * One board row, as the advisor sees it.
 *
 * This mirrors `app`'s `ClusterJson` field for field. It is a *view*, not a second model:
 * every value here was decided by `core`'s `ResponderRanking` before the advisor existed,
 * and nothing downstream of this class can change one.
 *
 * [actionRank] is null past the dispatch budget — still on the board, still ordered, not
 * prioritised. [placed] false means the zone tag named no floor; the position is a parking
 * slot, not a location.
 */
data class BoardIncident(
    val clusterId: String,
    val origin: String,
    val zone: String,
    val severity: String,
    val tier: String,
    val standing: String,
    val dispatchable: Boolean,
    val actionRank: Int?,
    val priority: Double,
    val dangerScore: Double,
    val corroboration: Int,
    val reportCount: Int,
    val minHops: Int,
    val lastSeenSecondsAgo: Long,
    val evidence: List<String>,
    val reasons: List<String>,
    val floorLabel: String?,
    val placed: Boolean,
    val gpsLat: Double?,
    val gpsLon: Double?,
)

/**
 * The whole board the advisor is asked about.
 *
 * ### One input shape, two producers
 *
 * The advisor is fed from two places and must behave identically in both:
 *
 * - the **dashboard**, which already holds a live `/snapshot` from a real gateway phone and
 *   posts it to the advisor ([fromSnapshotJson]);
 * - a **JVM caller** — the headless gateway, a test, a CLI brief — which holds `core` types
 *   directly and should not have to serialise and re-parse them ([of]).
 *
 * Both land here, so there is exactly one definition of what the model is told.
 *
 * ### It is read-only by construction
 *
 * Same guarantee `TacticalSummarizer` already gives at the `core` seam: this type is handed
 * the board *after* ranking and carries no path back to it. Whatever a model generates
 * downstream, it cannot reorder, promote, hide or delay a row, because nothing it produces
 * is ever read by the ranker.
 */
data class BoardView(
    val incidents: List<BoardIncident>,
    /** The gateway's own deterministic one-liner, carried through so the model can be told what it already says. */
    val deterministicAdvice: String = "",
    /** The responder's own node id, when the gateway published one. */
    val selfNode: String? = null,
    /** Peers that handed this device a report — carriers, not measured radio links. */
    val carriers: List<String> = emptyList(),
) {

    val withinBudget: Int get() = incidents.count { it.actionRank != null }
    val dispatchableCount: Int get() = incidents.count { it.actionRank != null && it.dispatchable }
    val unplacedCount: Int get() = incidents.count { !it.placed }
    val singleSourced: Int get() = incidents.count { it.corroboration == 0 }

    /**
     * The advisory shown when no model answers — the panel is never blank and never waits.
     *
     * Same honesty rules as `RadminLlmSummarizer`: what is *not* known is stated as plainly
     * as what is, and the last line says the ranking was not touched.
     */
    fun deterministicBrief(): String {
        if (incidents.isEmpty()) return "No incidents on the board. The mesh is quiet."
        val lines = ArrayList<String>()
        val beyond = incidents.size - withinBudget
        lines += buildString {
            append(withinBudget).append(" incident").append(if (withinBudget == 1) "" else "s")
            append(" within dispatch capacity")
            if (beyond > 0) append(", ").append(beyond).append(" beyond it")
            append(".")
        }
        val drowning = incidents.count { it.severity == "DROWNING_IMMINENT" }
        if (drowning > 0) {
            lines += "$drowning drowning-imminent — these outrank everything else regardless of " +
                "how confident the other reports are."
        }
        incidents.firstOrNull()?.let { top ->
            val where = if (top.placed) "${top.zone}, ${top.floorLabel ?: "floor unknown"}"
            else "${top.zone} — no floor in the zone tag, location unknown"
            val evidence = top.evidence.ifEmpty { listOf("no sensory detail") }
            lines += "Top of board: ${top.origin} at $where. Evidence: ${evidence.joinToString(", ")}. " +
                "${top.standing}, ${top.corroboration} corroborating relay" +
                (if (top.corroboration == 1) "" else "s") + ", ${top.minHops} hop(s) away."
        }
        if (unplacedCount > 0) {
            lines += "$unplacedCount incident(s) could not be placed on a floor. Localisation is " +
                "not solved in this system — treat every position as a zone hint, not a coordinate."
        }
        if (singleSourced > 0) lines += "$singleSourced report(s) are single-sourced and uncorroborated."
        lines += "Advisory only. Ordering above was decided by the deterministic ranker; nothing here changed it."
        return lines.joinToString("\n")
    }

    companion object {

        /**
         * Parses the exact payload `GatewayServer.payload()` serves on `/snapshot` and
         * `/events` — the one the dashboard is already holding when a responder asks a
         * question, so nothing is re-fetched and the model is asked about the board on the
         * screen rather than a second, slightly later one.
         *
         * Missing fields degrade to honest defaults (no evidence, unplaced, no GPS) rather
         * than throwing: an older gateway build must still get an answer.
         */
        fun fromSnapshotJson(text: String): BoardView {
            val root = Json.asObject(Json.parse(text)) ?: return BoardView(emptyList())
            val incidents = Json.asList(root["clusters"]).mapNotNull { raw ->
                val c = Json.asObject(raw) ?: return@mapNotNull null
                BoardIncident(
                    clusterId = Json.str(c, "clusterId").orEmpty(),
                    origin = Json.str(c, "origin").orEmpty(),
                    zone = Json.str(c, "zone").orEmpty().ifBlank { "unset" },
                    severity = Json.str(c, "severity").orEmpty(),
                    tier = Json.str(c, "effectiveTier").orEmpty(),
                    standing = Json.str(c, "standing").orEmpty(),
                    dispatchable = Json.bool(c, "dispatchable") ?: false,
                    actionRank = Json.int(c, "recommendedActionRank"),
                    priority = Json.num(c, "priority") ?: 0.0,
                    dangerScore = Json.num(c, "dangerScore") ?: 0.0,
                    corroboration = Json.int(c, "corroboration") ?: 0,
                    reportCount = Json.int(c, "reportCount") ?: 0,
                    minHops = Json.int(c, "minHops") ?: 0,
                    lastSeenSecondsAgo = (Json.num(c, "lastSeenSecondsAgo") ?: 0.0).toLong(),
                    evidence = Json.strList(c, "evidence"),
                    reasons = Json.strList(c, "reasons"),
                    floorLabel = Json.str(c, "floorLabel"),
                    placed = Json.bool(c, "placed") ?: false,
                    gpsLat = Json.num(c, "gpsLat"),
                    gpsLon = Json.num(c, "gpsLon"),
                )
            }
            val carriers = Json.asList(root["links"])
                .mapNotNull { Json.str(Json.asObject(it), "carrier") }
                .distinct()
            return BoardView(
                incidents = incidents,
                deterministicAdvice = Json.str(root, "advice").orEmpty(),
                selfNode = Json.str(Json.asObject(root["self"]), "nodeId"),
                carriers = carriers,
            )
        }

        /** The JVM-side producer: `core`'s ranked board and its twin projection, with no JSON round trip. */
        fun of(
            ranked: List<RankedIncident>,
            twin: TwinSnapshot,
            nowMs: Long,
            deterministicAdvice: String = "",
            selfNode: String? = null,
        ): BoardView {
            val byKey: Map<String, TwinNode> = twin.nodes.associateBy { it.key }
            val incidents = ranked.mapIndexed { index, r ->
                val c = r.cluster
                val node = byKey[c.key]
                BoardIncident(
                    clusterId = c.key,
                    origin = c.origin.canonical(),
                    zone = c.zone,
                    severity = c.severity.name,
                    tier = c.tier.name,
                    standing = r.standing.label,
                    dispatchable = r.standing.dispatchable,
                    actionRank = if (r.withinBudget) index + 1 else null,
                    priority = r.priority,
                    dangerScore = c.dangerScore,
                    corroboration = c.corroborationCount,
                    reportCount = c.reportCount,
                    minHops = c.minHops,
                    lastSeenSecondsAgo = (c.ageMs(nowMs) / 1000).coerceAtLeast(0),
                    evidence = SensoryFlags.describe(c.flags),
                    reasons = r.reasons,
                    floorLabel = node?.floor?.label,
                    placed = node?.placed ?: false,
                    gpsLat = c.gpsLat?.toDouble(),
                    gpsLon = c.gpsLon?.toDouble(),
                )
            }
            return BoardView(
                incidents = incidents,
                deterministicAdvice = deterministicAdvice,
                selfNode = selfNode,
                carriers = twin.links.map { it.carrier.canonical() }.distinct(),
            )
        }
    }
}
