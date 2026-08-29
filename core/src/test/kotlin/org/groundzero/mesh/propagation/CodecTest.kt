package org.groundzero.mesh.propagation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CodecTest {

    private val full = Envelope(
        nodeId = NodeId.parse("a8f3-92b1-4c12"),
        saltFingerprint = "0123456789abcdef0123456789abcdef",
        addressZone = "rooftop-block-D",
        tier = EpistemologyTier.ANUMANA,
        severity = Severity.DROWNING_IMMINENT,
        dangerScore = 0.6137,
        timestamp = 1_724_900_123L,
        slmSummary = "water at chest, 3 adults 1 child",
        views = listOf("tilt:rising", "sound:water"),
        peers = listOf(NodeId(1), NodeId(2), NodeId(0xABCDEF)),
        hops = 2,
        ttl = 9,
    )

    @Test
    fun jsonRoundTrips() {
        val decoded = JsonCodec.decode(JsonCodec.encode(full))
        assertEquals(full, decoded)
    }

    @Test
    fun jsonRoundTripsWithNullSlm() {
        val e = full.copy(slmSummary = null, views = emptyList())
        assertEquals(e, JsonCodec.decode(JsonCodec.encode(e)))
    }

    @Test
    fun compactRoundTrips() {
        val decoded = CompactCodec.decode(CompactCodec.encode(full))
        assertEquals(full.nodeId, decoded.nodeId)
        assertEquals(full.saltFingerprint, decoded.saltFingerprint)
        assertEquals(full.addressZone, decoded.addressZone)
        assertEquals(full.tier, decoded.tier)
        assertEquals(full.severity, decoded.severity)
        assertEquals(full.timestamp, decoded.timestamp)
        assertEquals(full.slmSummary, decoded.slmSummary)
        assertEquals(full.views, decoded.views)
        assertEquals(full.peers, decoded.peers)
        assertEquals(full.hops, decoded.hops)
        assertEquals(full.ttl, decoded.ttl)
        assertTrue(kotlin.math.abs(full.dangerScore - decoded.dangerScore) < 1e-3)
    }

    @Test
    fun compactFrameFitsLoRa() {
        val size = CompactCodec.encode(full).size
        assertEquals(size, CompactCodec.frameSize(full))
        assertTrue(size <= CompactCodec.LORA_MAX_FRAME, "compact frame $size > ${CompactCodec.LORA_MAX_FRAME}")
    }

    @Test
    fun frameBudgetSelectsProjection() {
        assertSame(CompactCodec, Codecs.forFrameBudget(233))
        assertSame(CompactCodec, Codecs.forFrameBudget(237))
        assertSame(JsonCodec, Codecs.forFrameBudget(1 shl 20))
    }

    @Test
    fun compactRejectsTruncatedFrame() {
        val bytes = CompactCodec.encode(full)
        assertFailsWithDecode { CompactCodec.decode(bytes.copyOf(bytes.size - 3)) }
    }

    private inline fun assertFailsWithDecode(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected EnvelopeDecodeException")
        } catch (_: EnvelopeDecodeException) {
            // expected
        }
    }
}
