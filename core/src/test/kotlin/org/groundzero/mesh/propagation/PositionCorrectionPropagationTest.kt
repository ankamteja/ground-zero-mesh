package org.groundzero.mesh.propagation

import org.groundzero.mesh.transport.SimNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A corrected position has to survive the first relay.
 *
 * Nobody marks where they are before pressing SOS, so the correction always arrives as a
 * second envelope for an incident every relay has already seen, identical in severity, score
 * and views. When propagation dedup ignored the position, that envelope died one hop from the
 * victim and the board kept showing wherever the SOS happened to leave with — while the phone
 * that knew better sat there repeating it to a relay that would not pass it on.
 */
class PositionCorrectionPropagationTest {

    private val victim = NodeId.parse("0000-0000-000a")
    private val relay = NodeId.parse("0000-0000-000b")

    private fun sos(
        zone: String = Envelope.UNSET_ZONE,
        lat: Float? = null,
        lon: Float? = null,
        source: FixSource? = null,
    ) = Envelope(
        nodeId = victim,
        saltFingerprint = "0123456789abcdef0123456789abcdef",
        addressZone = zone,
        tier = EpistemologyTier.PRATYAKSA,
        severity = Severity.DROWNING_IMMINENT,
        dangerScore = 1.0,
        timestamp = 1_724_900_000L,
        views = listOf("OVERRIDE_ACTIVE"),
        gpsLat = lat,
        gpsLon = lon,
        gpsSource = source,
        ttl = 8,
    )

    private fun gossip(): Pair<Gossip, (Envelope) -> ByteArray> {
        val net = SimNetwork(latencyMs = 1)
        net.link(victim, relay)
        val transport = net.transportFor(relay).also { it.start() }
        net.transportFor(victim).start()
        val g = Gossip(transport, clockMs = { 0L })
        val codec = Codecs.forFrameBudget(transport.maxFrameBytes)
        return g to { e -> codec.encode(e) }
    }

    @Test
    fun `a mark added after the sos is forwarded, not suppressed as a duplicate`() {
        val (g, encode) = gossip()

        assertNotNull(g.ingest(encode(sos()), victim), "the sos itself is new")

        // Same incident, same severity, same score, same views — only the position differs.
        val corrected = sos(
            zone = "block-b-west",
            lat = 9.0932f,
            lon = 76.4903f,
            source = FixSource.SELF_REPORTED,
        )
        val forwarded = assertNotNull(
            g.ingest(encode(corrected), victim),
            "the correction must travel; suppressing it strands the search at the old location",
        )

        assertEquals("block-b-west", forwarded.addressZone)
        assertEquals(FixSource.SELF_REPORTED, forwarded.gpsSource)
        assertEquals(0, g.suppressedDuplicates, "neither frame was a repeat, so nothing is suppressed")
    }

    @Test
    fun `a byte-identical repeat is still suppressed`() {
        val (g, encode) = gossip()
        val marked = sos(
            zone = "block-b-west",
            lat = 9.0932f,
            lon = 76.4903f,
            source = FixSource.SELF_REPORTED,
        )

        assertNotNull(g.ingest(encode(marked), victim))
        assertNull(
            g.ingest(encode(marked), victim),
            "widening the key must not turn every second copy into another broadcast",
        )
    }
}
