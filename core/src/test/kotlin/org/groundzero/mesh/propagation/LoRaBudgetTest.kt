package org.groundzero.mesh.propagation

import org.groundzero.mesh.agent.SlmFeatureVector
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The LoRa byte budget has to close, and it is the kind of thing that only stops closing
 * when someone adds a field.
 *
 * Before [CompactCodec.LORA_USABLE_FRAME] existed, `Envelope` validated against the raw 233
 * and the largest envelope the schema can express was *exactly* 233 — leaving nothing for
 * the link's own framing. `LoRaBridgeTransport`, which does subtract its header, then
 * rejected every envelope above 225 inside `send()`: after the sensory window had been
 * spent, the report built and the radio ready. These tests fail the moment that gap reopens.
 */
class LoRaBudgetTest {

    private fun build(zoneLen: Int, viewLen: Int, viewCount: Int, peers: Int): Envelope? = try {
        Envelope(
            nodeId = NodeId(0xAABB_CCDD_EEFFL),
            saltFingerprint = "a1b2c3d4".repeat(4),
            addressZone = "z".repeat(zoneLen),
            tier = EpistemologyTier.PRATYAKSA,
            severity = Severity.DROWNING_IMMINENT,
            dangerScore = 0.87,
            timestamp = 1_700_000_000_000L,
            slmSummary = "S".repeat(Envelope.MAX_SLM_SUMMARY_BYTES),
            flags = 0x3F.toByte(),
            featureVector = SlmFeatureVector(FloatArray(SlmFeatureVector.LENGTH) { 0.5f }),
            views = (1..viewCount).map { "v".repeat(viewLen) },
            peers = (1..peers).map { NodeId(it.toLong()) },
            hops = 3, ttl = 2, gpsLat = 12.97f, gpsLon = 77.59f,
        )
    } catch (e: IllegalArgumentException) { null }

    /** The largest envelope the schema can express, found by search rather than assumed. */
    private fun largestConstructible(): Int {
        var best = 0
        for (zone in 0..Envelope.MAX_ADDRESS_ZONE_CHARS)
            for (viewLen in 0..40)
                for (viewCount in 0..Envelope.MAX_VIEWS)
                    for (peers in 0..Envelope.MAX_PEERS) {
                        val e = build(zone, viewLen, viewCount, peers) ?: continue
                        best = maxOf(best, CompactCodec.frameSize(e))
                    }
        return best
    }

    @Test
    fun `the budget closes - usable payload plus link framing fits the radio`() {
        assertTrue(
            CompactCodec.LORA_USABLE_FRAME + CompactCodec.LORA_LINK_HEADER_RESERVE <=
                CompactCodec.LORA_MAX_FRAME,
            "usable ${CompactCodec.LORA_USABLE_FRAME} + reserve " +
                "${CompactCodec.LORA_LINK_HEADER_RESERVE} > ${CompactCodec.LORA_MAX_FRAME}",
        )
    }

    @Test
    fun `no constructible envelope can exceed what a LoRa link can carry`() {
        val largest = largestConstructible()
        assertTrue(largest > 0, "the search built nothing — the fixture is broken, not the budget")
        assertTrue(
            largest <= CompactCodec.LORA_USABLE_FRAME,
            "largest constructible envelope is $largest bytes, but a LoRa link can only carry " +
                "${CompactCodec.LORA_USABLE_FRAME}. An envelope that cannot cross the link it " +
                "was sized for must not be constructible.",
        )
    }

    @Test
    fun `the reserve is actually used, not slack nobody needs`() {
        // If this ever fails the reserve is bigger than any framing needs and is costing
        // payload for nothing.
        assertTrue(CompactCodec.LORA_LINK_HEADER_RESERVE in 1..16)
    }
}
