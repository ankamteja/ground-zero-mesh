package org.groundzero.mesh.propagation

import org.groundzero.mesh.transport.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Clearing the responder's board has to *stay* cleared.
 *
 * Emptying the cluster map alone does not survive contact with a live mesh, which is why the
 * dashboard's clear looked like it did nothing:
 *
 * - a peer reconnecting replays its `StoreAndForward` buffer, and those frames are up to
 *   fifteen minutes old;
 * - a victim whose incident is still open keeps heartbeating it;
 * - and both arrive on [Gossip]'s *duplicate* path, which still calls
 *   [DedupCluster.ingest] on purpose, because a second copy by another route is
 *   corroboration rather than noise.
 *
 * So the board repopulated within a second or two of being cleared, from frames that were
 * already in flight. These tests pin the fix and, just as importantly, pin that a genuinely
 * new incident is still allowed through — a clear that deafened the gateway would be far
 * worse than one that did nothing.
 */
class ClearBoardTest {

    private val victim = NodeId(0x0A01)
    private val other = NodeId(0x0A02)
    private val relay = NodeId(0x0B01)

    private fun envelope(
        from: NodeId = victim,
        at: Long = 1_700_000_000L,
        severity: Severity = Severity.STRUCTURAL_ENTRAPMENT,
        hops: Int = 0,
    ) = Envelope(
        nodeId = from,
        saltFingerprint = "a1b2c3d4".repeat(4),
        addressZone = "block-d",
        tier = EpistemologyTier.PRATYAKSA,
        severity = severity,
        dangerScore = 0.9,
        timestamp = at,
        hops = hops,
        ttl = 4,
    )

    @Test
    fun `clear empties the board`() {
        val dedup = DedupCluster()
        dedup.ingest(envelope(), from = null, nowMs = 1_000)
        assertEquals(1, dedup.clusters().size)

        dedup.clear()
        assertTrue(dedup.clusters().isEmpty(), "the board should be empty right after a clear")
    }

    @Test
    fun `a replayed frame does not resurrect a cleared incident`() {
        val dedup = DedupCluster()
        val sos = envelope()
        dedup.ingest(sos, from = null, nowMs = 1_000)
        dedup.clear()

        // Exactly what StoreAndForward hands a reconnecting peer, and what a still-open
        // incident keeps heartbeating: the same envelope, arriving again.
        dedup.ingest(sos, from = relay, nowMs = 2_000)

        assertTrue(
            dedup.clusters().isEmpty(),
            "a cleared incident came back from a replay — this is the bug the clear button had",
        )
    }

    @Test
    fun `corroboration of a cleared incident does not resurrect it`() {
        val dedup = DedupCluster()
        dedup.ingest(envelope(), from = null, nowMs = 1_000)
        dedup.clear()

        // The duplicate path in Gossip.ingest: a second copy by another carrier. It is
        // corroboration for an incident still held, and nothing at all for a cleared one.
        dedup.ingest(envelope(hops = 1), from = relay, nowMs = 2_000)
        dedup.ingest(envelope(hops = 2), from = other, nowMs = 3_000)

        assertTrue(dedup.clusters().isEmpty(), "corroboration resurrected a cleared incident")
    }

    @Test
    fun `a genuinely new incident still reaches a cleared board`() {
        val dedup = DedupCluster()
        dedup.ingest(envelope(), from = null, nowMs = 1_000)
        dedup.clear()

        // A new press after the incident was cleared mints a new `nodeId@timestamp`, which
        // is what makes it a different incident rather than an update to the old one.
        dedup.ingest(envelope(at = 1_700_000_900L), from = null, nowMs = 4_000)

        assertEquals(
            1,
            dedup.clusters().size,
            "a clear must not deafen the gateway to new reports",
        )
    }

    @Test
    fun `a different victim is unaffected by another's incident being cleared`() {
        val dedup = DedupCluster()
        dedup.ingest(envelope(), from = null, nowMs = 1_000)
        dedup.clear()

        dedup.ingest(envelope(from = other), from = relay, nowMs = 2_000)

        assertEquals(other, dedup.clusters().single().origin)
    }

    @Test
    fun `clearing through gossip leaves loop suppression intact`() {
        // seen is loop suppression, not board state. Dropping it on a clear would make this
        // node re-forward everything it still holds — a responder tidying their screen
        // would re-flood the mesh.
        val transport = RecordingTransport()
        val gossip = Gossip(transport, clockMs = { 1_000 })
        val frame = Codecs.forFrameBudget(transport.maxFrameBytes).encode(envelope())

        gossip.ingest(frame, from = relay)
        val forwardedBefore = transport.sent.size
        gossip.clearBoard()
        gossip.ingest(frame, from = relay)

        assertEquals(
            forwardedBefore,
            transport.sent.size,
            "the same frame was forwarded twice after a clear — loop suppression was lost",
        )
        assertTrue(gossip.clusters().isEmpty())
    }

    private class RecordingTransport : Transport {
        val sent = mutableListOf<ByteArray>()
        override val localId: NodeId = NodeId(0x0C01)
        override val maxFrameBytes: Int = 512
        override fun start() = Unit
        override fun stop() = Unit
        override fun send(frame: ByteArray, to: NodeId?) { sent += frame }
        override fun onReceive(listener: (from: NodeId, frame: ByteArray) -> Unit) = Unit
        override fun knownPeers(): List<NodeId> = emptyList()
    }
}
