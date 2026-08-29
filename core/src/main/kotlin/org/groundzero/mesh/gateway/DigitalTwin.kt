package org.groundzero.mesh.gateway

import org.groundzero.mesh.agent.SensoryFlags
import org.groundzero.mesh.propagation.IncidentCluster
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a floor sits in the model. `0` is ground, positive is up, negative is below ground.
 */
data class TwinFloor(val index: Int, val label: String) {
    /** Metres above ground for the visualiser. Nominal storey height, not a measurement. */
    val elevation: Double get() = index * STOREY_HEIGHT_M

    companion object {
        const val STOREY_HEIGHT_M = 3.0
    }
}

/** A schematic position. See [DigitalTwin] on why these are not coordinates. */
data class TwinPosition(val x: Double, val y: Double, val z: Double)

/**
 * One incident placed in the model.
 *
 * [placed] is the field that keeps this honest. False means the zone tag said nothing this
 * model could interpret, the position is a parking slot, and the visualiser must show it as
 * unplaced rather than dropping it or drawing it on the ground floor. An unplaced casualty
 * shown as unplaced costs a responder a question; an unplaced casualty drawn confidently in
 * the wrong place costs them a search.
 */
data class TwinNode(
    val key: String,
    val origin: NodeId,
    val zone: String,
    val floor: TwinFloor,
    val position: TwinPosition,
    val placed: Boolean,
    val severity: Severity,
    val risk: Double,
    val flags: Byte,
    val corroborators: Int,
    val firstHandHeld: Boolean,
    val ageMs: Long,
) {
    /** Plain-language decode of [flags], strongest evidence first. */
    val evidence: List<String> get() = SensoryFlags.describe(flags)
}

/**
 * One node carried one incident to us.
 *
 * Deliberately *not* called a radio link. All that is actually known is that this peer handed
 * us this report; whether it heard it directly from the origin or from three hops away is not
 * in the envelope. Drawing it as a measured topology would be a claim the data does not
 * support.
 */
data class TwinLink(val carrier: NodeId, val incidentKey: String)

data class TwinSnapshot(
    val nodes: List<TwinNode>,
    val links: List<TwinLink>,
    val floors: List<TwinFloor>,
    val nowMs: Long,
) {
    val placedCount: Int get() = nodes.count { it.placed }
    val unplacedCount: Int get() = nodes.count { !it.placed }
}

/**
 * The spatial state model behind the responder gateway's 3D view.
 *
 * ### It is schematic, and says so
 *
 * There is no trilateration, no RSSI ranging and no GPS anywhere in this project. Positions
 * here are **derived from the zone tag alone** — a coarse, human-entered string — and then
 * spread deterministically around that zone's ring so two incidents in one zone do not stack
 * on top of each other. Refreshing produces the same layout for the same data, which makes
 * the view readable; it does not make it surveyed.
 *
 * Consumers must present it as a schematic. The moment a responder believes the dot is
 * *where the person is*, this model has done harm rather than good, and the honest failure —
 * "we know they are somewhere on floor 2" — is the useful one.
 *
 * ### It decides nothing
 *
 * The twin is a projection of what [ResponderRanking] already ordered. It cannot reorder,
 * promote or hide an incident. Feed it the ranked board and it renders exactly that.
 */
object DigitalTwin {

    /** Zone tags that name a floor. Anything else is unplaced. */
    private val FLOOR_PATTERNS = listOf(
        Regex("""\bbasement[-\s]?(\d+)?\b""") to { m: MatchResult ->
            -(m.groupValues[1].toIntOrNull() ?: 1)
        },
        Regex("""\bb(\d+)\b""") to { m: MatchResult -> -(m.groupValues[1].toInt()) },
        Regex("""\bground\b""") to { _: MatchResult -> 0 },
        Regex("""\bfloor[-\s]?(\d+)\b""") to { m: MatchResult -> m.groupValues[1].toInt() },
        Regex("""\bf(\d+)\b""") to { m: MatchResult -> m.groupValues[1].toInt() },
        Regex("""\broof\b""") to { _: MatchResult -> ROOF_INDEX },
    )

    /** Where a roof sits when the tag says "roof" and nothing about how tall the building is. */
    const val ROOF_INDEX = 3

    /** Radius of the ring incidents are spread around, in metres. */
    const val ZONE_RADIUS_M = 8.0

    fun snapshot(board: List<RankedIncident>, nowMs: Long): TwinSnapshot {
        val perZoneSeen = HashMap<String, Int>()

        val nodes = board.map { ranked ->
            val cluster = ranked.cluster
            val floorIndex = floorOf(cluster.zone)
            val slot = perZoneSeen.merge(cluster.zone, 1, Int::plus)!! - 1
            TwinNode(
                key = cluster.key,
                origin = cluster.origin,
                zone = cluster.zone,
                floor = floor(floorIndex),
                position = position(cluster.zone, floorIndex, slot),
                placed = floorIndex != null,
                severity = cluster.severity,
                risk = cluster.dangerScore,
                flags = cluster.flags,
                corroborators = cluster.corroborationCount,
                firstHandHeld = cluster.firstHandHeld,
                ageMs = cluster.ageMs(nowMs),
            )
        }

        val links = board.flatMap { ranked ->
            ranked.cluster.corroborators.map { TwinLink(it, ranked.cluster.key) }
        }

        val floors = nodes.map { it.floor }.distinctBy { it.index }.sortedBy { it.index }

        return TwinSnapshot(nodes = nodes, links = links, floors = floors, nowMs = nowMs)
    }

    /** The floor a zone tag names, or null when it names none. */
    fun floorOf(zone: String): Int? {
        val text = zone.lowercase()
        for ((pattern, extract) in FLOOR_PATTERNS) {
            pattern.find(text)?.let { return extract(it) }
        }
        return null
    }

    private fun floor(index: Int?): TwinFloor = when (index) {
        null -> TwinFloor(UNPLACED_INDEX, "unplaced")
        ROOF_INDEX -> TwinFloor(ROOF_INDEX, "roof")
        0 -> TwinFloor(0, "ground")
        in Int.MIN_VALUE..-1 -> TwinFloor(index, "basement ${abs(index)}")
        else -> TwinFloor(index, "floor $index")
    }

    /**
     * A stable slot on the zone's ring.
     *
     * The angle comes from the zone tag's hash and the slot index, so the same data always
     * lays out the same way — a view that reshuffles on every refresh cannot be read under
     * pressure. Unplaced incidents are parked off to one side at ground level rather than
     * being mixed into a floor they were never assigned to.
     */
    private fun position(zone: String, floorIndex: Int?, slot: Int): TwinPosition {
        if (floorIndex == null) {
            return TwinPosition(x = PARKING_X_M, y = 0.0, z = slot * PARKING_SPACING_M)
        }
        val base = (zone.hashCode().toDouble() % 360.0) * Math.PI / 180.0
        val angle = base + slot * (Math.PI / 4.0)
        return TwinPosition(
            x = ZONE_RADIUS_M * cos(angle),
            y = floorIndex * TwinFloor.STOREY_HEIGHT_M,
            z = ZONE_RADIUS_M * sin(angle),
        )
    }

    private const val UNPLACED_INDEX = Int.MIN_VALUE

    /** Well clear of the building, so an unplaced incident cannot be misread as inside it. */
    private const val PARKING_X_M = 30.0
    private const val PARKING_SPACING_M = 3.0
}
