package org.groundzero.mesh.propagation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule this whole field exists for: a position a person guessed must never reach a
 * responder looking like a position a satellite measured.
 */
class FixProvenanceTest {

    private fun envelope(
        lat: Float? = null,
        lon: Float? = null,
        source: FixSource? = null,
        node: String = "0000-0000-0001",
        timestamp: Long = 1_700_000_000L,
    ) = Envelope(
        nodeId = NodeId.parse(node),
        saltFingerprint = "a".repeat(32),
        addressZone = "block-a-north",
        tier = EpistemologyTier.PRATYAKSA,
        severity = Severity.STRUCTURAL_ENTRAPMENT,
        dangerScore = 0.7,
        timestamp = timestamp,
        gpsLat = lat,
        gpsLon = lon,
        gpsSource = source,
    )

    @Test
    fun `a coordinate without a source is rejected at construction`() {
        val e = assertFailsWith<IllegalArgumentException> {
            envelope(lat = 9.09f, lon = 76.49f, source = null)
        }
        assertTrue(e.message!!.contains("must say where it came from"))
    }

    @Test
    fun `a source without a coordinate is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            envelope(lat = null, lon = null, source = FixSource.SATELLITE)
        }
    }

    @Test
    fun `provenance survives a compact round trip`() {
        for (source in FixSource.entries) {
            val decoded = CompactCodec.decode(
                CompactCodec.encode(envelope(lat = 9.0928f, lon = 76.4906f, source = source)),
            )
            assertEquals(source, decoded.gpsSource)
            assertEquals(9.0928f, decoded.gpsLat!!, absoluteTolerance = 1e-4f)
        }
    }

    @Test
    fun `provenance survives a json round trip`() {
        for (source in FixSource.entries) {
            val decoded = JsonCodec.decode(
                JsonCodec.encode(envelope(lat = 9.0928f, lon = 76.4906f, source = source)),
            )
            assertEquals(source, decoded.gpsSource)
        }
    }

    @Test
    fun `no coordinate means no source, both ways round`() {
        val decoded = CompactCodec.decode(CompactCodec.encode(envelope()))
        assertNull(decoded.gpsLat)
        assertNull(decoded.gpsSource)
    }

    /**
     * The whole point of reusing the GPS header byte: saying where a coordinate came from
     * costs nothing on a frame that has 233 bytes in total.
     */
    @Test
    fun `provenance costs no extra bytes on the wire`() {
        val satellite = CompactCodec.encode(envelope(lat = 9.09f, lon = 76.49f, source = FixSource.SATELLITE))
        val marked = CompactCodec.encode(envelope(lat = 9.09f, lon = 76.49f, source = FixSource.SELF_REPORTED))
        assertEquals(satellite.size, marked.size)
    }

    /**
     * An older phone's frame still has to be readable by an updated gateway — that is the
     * direction a deployment can actually fix mid-operation.
     */
    @Test
    fun `a format 0x03 frame decodes as a satellite fix`() {
        val current = CompactCodec.encode(envelope(lat = 9.0928f, lon = 76.4906f, source = FixSource.SATELLITE))
        val legacy = current.copyOf().also { it[0] = 0x03 }
        val decoded = CompactCodec.decode(legacy)
        assertEquals(FixSource.SATELLITE, decoded.gpsSource)
        assertEquals(9.0928f, decoded.gpsLat!!, absoluteTolerance = 1e-4f)
    }

    @Test
    fun `an unknown gps header is a decode failure, not a silent misparse`() {
        val frame = CompactCodec.encode(envelope(lat = 9.09f, lon = 76.49f, source = FixSource.SATELLITE))
        frame[38] = 0x7F
        assertFailsWith<EnvelopeDecodeException> { CompactCodec.decode(frame) }
    }

    // --- the merge rule ---

    private fun clusterAfter(vararg envelopes: Envelope): IncidentCluster {
        val dedup = DedupCluster()
        envelopes.forEach { dedup.ingest(it, from = null, nowMs = 1_000L) }
        return dedup.clusters().single()
    }

    @Test
    fun `a marked position never displaces a satellite fix`() {
        val cluster = clusterAfter(
            envelope(lat = 9.0900f, lon = 76.4900f, source = FixSource.SATELLITE),
            envelope(lat = 9.0999f, lon = 76.4999f, source = FixSource.SELF_REPORTED),
        )
        assertEquals(FixSource.SATELLITE, cluster.gpsSource)
        assertEquals(9.0900f, cluster.gpsLat!!, absoluteTolerance = 1e-4f)
    }

    @Test
    fun `a satellite fix does displace a marked position`() {
        val cluster = clusterAfter(
            envelope(lat = 9.0999f, lon = 76.4999f, source = FixSource.SELF_REPORTED),
            envelope(lat = 9.0900f, lon = 76.4900f, source = FixSource.SATELLITE),
        )
        assertEquals(FixSource.SATELLITE, cluster.gpsSource)
        assertEquals(9.0900f, cluster.gpsLat!!, absoluteTolerance = 1e-4f)
    }

    @Test
    fun `a report with no position never blanks one already held`() {
        val cluster = clusterAfter(
            envelope(lat = 9.0900f, lon = 76.4900f, source = FixSource.SELF_REPORTED),
            envelope(),
        )
        assertEquals(FixSource.SELF_REPORTED, cluster.gpsSource)
        assertEquals(9.0900f, cluster.gpsLat!!, absoluteTolerance = 1e-4f)
    }

    @Test
    fun `a newer fix of the same kind wins`() {
        val cluster = clusterAfter(
            envelope(lat = 9.0900f, lon = 76.4900f, source = FixSource.SATELLITE),
            envelope(lat = 9.0950f, lon = 76.4950f, source = FixSource.SATELLITE),
        )
        assertEquals(9.0950f, cluster.gpsLat!!, absoluteTolerance = 1e-4f)
    }
}
