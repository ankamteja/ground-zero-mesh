package org.groundzero.mesh.propagation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val SALT = "0123456789abcdef0123456789abcdef"

fun sampleEnvelope(
    hops: Int = 0,
    ttl: Int = 15,
    views: List<String> = emptyList(),
    peers: List<NodeId> = emptyList(),
    slm: String? = null,
) = Envelope(
    nodeId = NodeId.parse("a8f3-92b1-4c12"),
    saltFingerprint = SALT,
    addressZone = "sector-7-basement",
    tier = EpistemologyTier.PRATYAKSA,
    severity = Severity.STRUCTURAL_ENTRAPMENT,
    dangerScore = 0.82,
    timestamp = 1_724_900_000L,
    slmSummary = slm,
    views = views,
    peers = peers,
    hops = hops,
    ttl = ttl,
)

class EnvelopeTest {

    @Test
    fun buildsWithValidFields() {
        val e = sampleEnvelope()
        assertEquals("a8f3-92b1-4c12@1724900000", e.dedupKey)
    }

    @Test
    fun rejectsBadSaltFingerprint() {
        assertFailsWith<IllegalArgumentException> { sampleEnvelope().copy(saltFingerprint = "short") }
        assertFailsWith<IllegalArgumentException> {
            sampleEnvelope().copy(saltFingerprint = "z".repeat(32))
        }
    }

    @Test
    fun rejectsOverlongAddressZone() {
        assertFailsWith<IllegalArgumentException> { sampleEnvelope().copy(addressZone = "x".repeat(25)) }
    }

    @Test
    fun rejectsDangerScoreOutOfRange() {
        assertFailsWith<IllegalArgumentException> { sampleEnvelope().copy(dangerScore = 1.5) }
    }

    @Test
    fun rejectsOverlongSlmSummary() {
        assertFailsWith<IllegalArgumentException> { sampleEnvelope(slm = "x".repeat(51)) }
    }

    @Test
    fun rejectsTooManyPeers() {
        val nine = (0 until 9).map { NodeId(it.toLong()) }
        assertFailsWith<IllegalArgumentException> { sampleEnvelope(peers = nine) }
    }

    @Test
    fun rejectsHopsOverCeiling() {
        assertFailsWith<IllegalArgumentException> { sampleEnvelope(hops = 16) }
    }

    @Test
    fun oversizedEnvelopeFailsAtConstruction() {
        val ex = assertFailsWith<IllegalArgumentException> {
            sampleEnvelope(views = List(4) { "x".repeat(120) })
        }
        assertTrue(ex.message!!.contains("LoRa frame"), "message was: ${ex.message}")
    }

    @Test
    fun dedupKeyIsStableAcrossForwarding() {
        val origin = sampleEnvelope(hops = 0, ttl = 15)
        val relayed = origin.forwarded(addPeers = listOf(NodeId(7)))
        assertEquals(origin.dedupKey, relayed.dedupKey)
        assertEquals(1, relayed.hops)
        assertEquals(14, relayed.ttl)
    }

    @Test
    fun effectiveTierDowngradesWhenRelayed() {
        assertEquals(EpistemologyTier.PRATYAKSA, sampleEnvelope(hops = 0).effectiveTier)
        assertEquals(EpistemologyTier.SABDA, sampleEnvelope(hops = 1).effectiveTier)
    }

    @Test
    fun forwardingFailsWhenTtlExhausted() {
        assertFailsWith<IllegalStateException> { sampleEnvelope(hops = 3, ttl = 0).forwarded() }
    }

    @Test
    fun rejectsAPartialGpsFix() {
        assertFailsWith<IllegalArgumentException> { sampleEnvelope().copy(gpsLat = 12.9f) }
        assertFailsWith<IllegalArgumentException> { sampleEnvelope().copy(gpsLon = 77.6f) }
    }

    @Test
    fun rejectsGpsOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            sampleEnvelope().copy(gpsLat = 91f, gpsLon = 0f, gpsSource = FixSource.SATELLITE)
        }
        assertFailsWith<IllegalArgumentException> {
            sampleEnvelope().copy(gpsLat = 0f, gpsLon = 181f, gpsSource = FixSource.SATELLITE)
        }
    }

    @Test
    fun acceptsAValidGpsFix() {
        val e = sampleEnvelope().copy(gpsLat = 12.9716f, gpsLon = 77.5946f, gpsSource = FixSource.SATELLITE)
        assertEquals(12.9716f, e.gpsLat)
        assertEquals(77.5946f, e.gpsLon)
    }
}
