package org.groundzero.mesh.propagation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a relay's standing may and may not be docked for.
 *
 * Trust decays about seven times faster than it builds and feeds `ResponderRanking`'s
 * corroboration weighting, so an unjust penalty is both expensive and self-defeating: it
 * demotes the relay *and* the ranking of the incident that relay carried. Every case here is
 * a relay behaving exactly as the design intends.
 */
class RelayTrustTest {

    private val victim = NodeId(0x0A01)
    private val relay = NodeId(0x0B01)
    private val neutral = TrustConsensus.NEUTRAL_TRUST

    private fun envelope(severity: Severity) = Envelope(
        nodeId = victim,
        saltFingerprint = "a1b2c3d4".repeat(4),
        addressZone = "block-d",
        tier = EpistemologyTier.PRATYAKSA,
        severity = severity,
        dangerScore = 0.7,
        timestamp = 1_700_000_000_000L,
        flags = 0x20.toByte(),
        hops = 1,
        ttl = 4,
    )

    @Test
    fun `a relay is not punished for carrying the victim's own severity upgrade`() {
        // The exact sequence a real incident produces: OTHER pressed first, then the truth.
        // Stage 3 reuses the dedup key so it lands as an update, and the merge escalates.
        // The old rule read that as the relay contradicting itself and cost it 0.5 -> 0.325.
        val dedup = DedupCluster()
        dedup.ingest(envelope(Severity.OTHER), from = relay, nowMs = 1_000)
        dedup.ingest(envelope(Severity.DROWNING_IMMINENT), from = relay, nowMs = 2_000)

        assertTrue(
            dedup.trustOf(relay) >= neutral,
            "carrying an escalation cost the relay standing: ${dedup.trustOf(relay)}",
        )
        assertEquals(Severity.DROWNING_IMMINENT, dedup.clusters().single().severity)
    }

    @Test
    fun `downplaying an emergency still costs standing`() {
        // The spoof defence stays intact: a peer reporting something calmer than what is
        // held is the case the asymmetric decay exists for.
        val dedup = DedupCluster()
        dedup.ingest(envelope(Severity.DROWNING_IMMINENT), from = relay, nowMs = 1_000)
        dedup.ingest(envelope(Severity.OTHER), from = relay, nowMs = 2_000)

        assertTrue(dedup.trustOf(relay) < neutral)
        // And the board still holds the worst statement the victim ever made.
        assertEquals(Severity.DROWNING_IMMINENT, dedup.clusters().single().severity)
    }

    @Test
    fun `a relay that keeps agreeing earns standing`() {
        val dedup = DedupCluster()
        repeat(5) { i ->
            dedup.ingest(envelope(Severity.STRUCTURAL_ENTRAPMENT), from = relay, nowMs = 1_000L * (i + 1))
        }
        assertTrue(dedup.trustOf(relay) > neutral, "steady agreement earned nothing")
    }

    @Test
    fun `the origin is never judged for restating its own severity`() {
        val dedup = DedupCluster()
        dedup.ingest(envelope(Severity.OTHER), from = victim, nowMs = 1_000)
        dedup.ingest(envelope(Severity.DROWNING_IMMINENT), from = victim, nowMs = 2_000)
        assertEquals(neutral, dedup.trustOf(victim))
    }

    @Test
    fun `an escalation is neither rewarded nor punished`() {
        // Not punished, because the relay told the truth. Not rewarded either, or a peer
        // could farm standing simply by escalating everything it forwards.
        val dedup = DedupCluster()
        dedup.ingest(envelope(Severity.OTHER), from = relay, nowMs = 1_000)
        val before = dedup.trustOf(relay)
        dedup.ingest(envelope(Severity.DROWNING_IMMINENT), from = relay, nowMs = 2_000)
        assertEquals(before, dedup.trustOf(relay))
    }

    @Test
    fun `a caller with real grounds can still penalise`() {
        val dedup = DedupCluster()
        dedup.judge(relay, corroborated = false)
        assertTrue(dedup.trustOf(relay) < neutral)
    }
}
