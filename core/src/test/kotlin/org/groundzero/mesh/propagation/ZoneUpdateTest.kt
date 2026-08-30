package org.groundzero.mesh.propagation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A zone tag entered after the SOS has to reach the board.
 *
 * Nobody types a zone before pressing the button, so the first envelope almost always
 * carries [Envelope.UNSET_ZONE]. The Stage 3 enrichment deliberately reuses the same dedup
 * key so it lands as an *update* — and `zone` was the one field that update silently could
 * not change, because it was missing from the merge. Every incident stayed "unset" for life,
 * which is precisely the state a real two-phone run shows on the board.
 */
class ZoneUpdateTest {

    private val victim = NodeId(0x0A01)
    private val relay = NodeId(0x0B01)

    private fun envelope(zone: String, score: Double = 0.6, hops: Int = 0) = Envelope(
        nodeId = victim,
        saltFingerprint = "a1b2c3d4".repeat(4),
        addressZone = zone,
        tier = EpistemologyTier.PRATYAKSA,
        severity = Severity.STRUCTURAL_ENTRAPMENT,
        dangerScore = score,
        timestamp = 1_700_000_000_000L,
        flags = 0x20.toByte(),
        hops = hops,
        ttl = 4,
    )

    @Test
    fun `a zone entered after the SOS reaches the board`() {
        val dedup = DedupCluster()
        dedup.ingest(envelope(Envelope.UNSET_ZONE), from = null, nowMs = 1_000)
        assertEquals(Envelope.UNSET_ZONE, dedup.clusters().single().zone)

        dedup.ingest(envelope("block-d-roof"), from = relay, nowMs = 2_000)
        assertEquals("block-d-roof", dedup.clusters().single().zone)
    }

    @Test
    fun `a later unset never blanks a zone already known`() {
        val dedup = DedupCluster()
        dedup.ingest(envelope("sector-7-basement"), from = null, nowMs = 1_000)
        dedup.ingest(envelope(Envelope.UNSET_ZONE), from = relay, nowMs = 2_000)
        assertEquals("sector-7-basement", dedup.clusters().single().zone)
    }

    @Test
    fun `a corrected zone replaces an earlier one`() {
        val dedup = DedupCluster()
        dedup.ingest(envelope("floor-1"), from = null, nowMs = 1_000)
        dedup.ingest(envelope("floor-3"), from = relay, nowMs = 2_000)
        assertEquals("floor-3", dedup.clusters().single().zone)
    }

    @Test
    fun `it stays one incident, not two`() {
        val dedup = DedupCluster()
        dedup.ingest(envelope(Envelope.UNSET_ZONE), from = null, nowMs = 1_000)
        dedup.ingest(envelope("block-d-roof"), from = relay, nowMs = 2_000)
        assertEquals(1, dedup.clusters().size)
        assertEquals(2, dedup.clusters().single().reportCount)
    }

    @Test
    fun `the sentinel is what decides, not a literal spread through the codebase`() {
        assertFalse(Envelope.isZoneKnown(Envelope.UNSET_ZONE))
        assertFalse(Envelope.isZoneKnown("UNSET"))
        assertFalse(Envelope.isZoneKnown("   "))
        assertFalse(Envelope.isZoneKnown(""))
        assertTrue(Envelope.isZoneKnown("block-d-roof"))
    }
}
