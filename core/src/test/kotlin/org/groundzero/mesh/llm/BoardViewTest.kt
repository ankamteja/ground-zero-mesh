package org.groundzero.mesh.llm

import org.groundzero.mesh.gateway.DigitalTwin
import org.groundzero.mesh.gateway.ResponderRanking
import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.IncidentCluster
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardViewTest {

    /** The exact payload shape `GatewayServer.payload()` serves, trimmed to two rows. */
    private val snapshot = """
      {"advice":"2 incident(s) within the action budget. Highest: block-d-roof (drowning imminent).",
       "flagBits":["rushing water"],"slotNames":["audio:water"],
       "clusters":[
        {"clusterId":"112233445566@2","origin":"1122-3344-5566","zone":"block-d-roof",
         "severity":"DROWNING_IMMINENT","effectiveTier":"PRATYAKSA","corroboration":2,
         "dangerScore":0.61,"lastSeenSecondsAgo":25,"reportCount":3,"minHops":1,
         "gpsLat":12.971891,"gpsLon":77.594623,"recommendedActionRank":1,"priority":0.636,
         "standing":"confirmed — first-hand","dispatchable":true,"flags":"0x23",
         "evidence":["manual SOS","rushing water"],"vector":[],
         "floor":3,"floorLabel":"roof","placed":true,"position":{"x":8.0,"y":9.0,"z":-0.4},
         "reasons":["drowning imminent — minutes, not hours"]},
        {"clusterId":"778899aabbcc@3","origin":"7788-99aa-bbcc","zone":"unset",
         "severity":"OTHER","effectiveTier":"SABDA","corroboration":0,
         "dangerScore":0.3,"lastSeenSecondsAgo":190,"reportCount":1,"minHops":2,
         "gpsLat":null,"gpsLon":null,"recommendedActionRank":null,"priority":0.2,
         "standing":"single unconfirmed report","dispatchable":false,"flags":"0x00",
         "evidence":[],"vector":[],
         "floor":null,"floorLabel":"unplaced","placed":false,"position":{"x":30.0,"y":0.0,"z":0.0},
         "reasons":["reported in distress"]}],
       "links":[{"carrier":"0000-0000-0b01","incidentKey":"778899aabbcc@3"}],
       "self":{"nodeId":"355f-807d-59bb"}}
    """.trimIndent()

    @Test
    fun `parses the gateway snapshot the dashboard is already holding`() {
        val board = BoardView.fromSnapshotJson(snapshot)
        assertEquals(2, board.incidents.size)
        assertEquals("355f-807d-59bb", board.selfNode)
        assertEquals(listOf("0000-0000-0b01"), board.carriers)
        assertTrue(board.deterministicAdvice.startsWith("2 incident(s)"))

        val top = board.incidents.first()
        assertEquals(1, top.actionRank)
        assertEquals("DROWNING_IMMINENT", top.severity)
        assertEquals("confirmed — first-hand", top.standing)
        assertTrue(top.dispatchable)
        assertTrue(top.placed)
        assertEquals("roof", top.floorLabel)
        assertEquals(12.971891, top.gpsLat)
        assertEquals(listOf("manual SOS", "rushing water"), top.evidence)
    }

    @Test
    fun `a row past the budget has no action rank, and an absent GPS stays absent`() {
        val second = BoardView.fromSnapshotJson(snapshot).incidents[1]
        assertNull(second.actionRank)
        assertNull(second.gpsLat)
        assertNull(second.gpsLon)
        assertFalse(second.placed)
        assertFalse(second.dispatchable)
    }

    @Test
    fun `derived counts match the rows`() {
        val board = BoardView.fromSnapshotJson(snapshot)
        assertEquals(1, board.withinBudget)
        assertEquals(1, board.dispatchableCount)
        assertEquals(1, board.unplacedCount)
        assertEquals(1, board.singleSourced)
    }

    @Test
    fun `the JVM producer and the JSON producer describe the same board`() {
        val now = 1_000_000L
        val clusters = listOf(
            cluster("a", NodeId(0x0a01), "floor 2", Severity.DROWNING_IMMINENT, 0.8, now - 5_000),
            cluster("b", NodeId(0x0a02), "unset", Severity.OTHER, 0.2, now - 60_000),
        )
        val ranked = ResponderRanking.rank(clusters, now)
        val twin = DigitalTwin.snapshot(ranked, now)
        val board = BoardView.of(ranked, twin, now, selfNode = "355f-807d-59bb")

        assertEquals(2, board.incidents.size)
        assertEquals("DROWNING_IMMINENT", board.incidents.first().severity)
        assertEquals(1, board.incidents.first().actionRank)
        assertEquals("floor 2", board.incidents.first().floorLabel)
        assertEquals(5L, board.incidents.first().lastSeenSecondsAgo)
        // The second cluster is under the first-hand floor, so it is not dispatchable — the
        // gate, not the advisor, decided that, and the view only reports it.
        assertFalse(board.incidents[1].dispatchable)
        assertFalse(board.incidents[1].placed)
    }

    @Test
    fun `the deterministic brief says what is unknown as plainly as what is`() {
        val brief = BoardView.fromSnapshotJson(snapshot).deterministicBrief()
        assertTrue(brief.contains("1 incident within dispatch capacity"), brief)
        assertTrue(brief.contains("could not be placed"), brief)
        assertTrue(brief.contains("single-sourced"), brief)
        assertTrue(brief.contains("nothing here changed it"), brief)
    }

    @Test
    fun `an empty board is quiet, not blank`() {
        val board = BoardView.fromSnapshotJson("""{"clusters":[],"links":[],"self":null}""")
        assertEquals(0, board.incidents.size)
        assertTrue(board.deterministicBrief().contains("mesh is quiet"))
    }

    @Test
    fun `a payload from an older gateway degrades instead of throwing`() {
        val board = BoardView.fromSnapshotJson("""{"clusters":[{"clusterId":"x@1","origin":"o"}]}""")
        val row = board.incidents.single()
        assertEquals("unset", row.zone)
        assertFalse(row.placed)
        assertEquals(emptyList(), row.evidence)
        assertNull(row.actionRank)
    }

    private fun cluster(
        key: String,
        origin: NodeId,
        zone: String,
        severity: Severity,
        danger: Double,
        lastUpdatedMs: Long,
    ) = IncidentCluster(
        key = key,
        origin = origin,
        zone = zone,
        severity = severity,
        dangerScore = danger,
        tier = EpistemologyTier.PRATYAKSA,
        corroborators = emptySet(),
        minHops = 1,
        firstSeenMs = lastUpdatedMs,
        lastUpdatedMs = lastUpdatedMs,
    )
}
