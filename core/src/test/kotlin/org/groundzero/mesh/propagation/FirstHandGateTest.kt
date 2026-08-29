package org.groundzero.mesh.propagation

import org.groundzero.mesh.transport.SimNetwork
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The first-hand gate is the load-bearing safety property of the epistemology: relayed
 * testimony may raise a cluster's rank, but only first-hand observation may mark it
 * confirmed-critical. These tests exercise it across a real link rather than by
 * constructing an envelope with the hop count already set.
 *
 * That distinction matters. `EnvelopeTest.effectiveTierDowngradesWhenRelayed` asserts the
 * property on a hand-built envelope, which is necessary but not sufficient: it never asks
 * what hop count an envelope *actually arrives with*.
 */
class FirstHandGateTest {

    private val alice = NodeId.parse("0000-0000-000a")
    private val bob = NodeId.parse("0000-0000-000b")

    private fun sos(hops: Int = 0) = Envelope(
        nodeId = alice,
        saltFingerprint = "0123456789abcdef0123456789abcdef",
        addressZone = "sector-7-roof",
        tier = EpistemologyTier.PRATYAKSA,
        severity = Severity.DROWNING_IMMINENT,
        dangerScore = 1.0,
        timestamp = 1_724_900_000L,
        hops = hops,
    )

    @Test
    fun `a report that crossed a link is testimony to whoever received it`() {
        val net = SimNetwork(latencyMs = 10)
        net.link(alice, bob)
        val ta = net.transportFor(alice).also { it.start() }
        val tb = net.transportFor(bob).also { it.start() }

        var heldByBob: Envelope? = null
        tb.onReceive { _, frame -> heldByBob = Codecs.forFrameBudget(tb.maxFrameBytes).decode(frame).asReceived() }

        // Alice observed this herself, so her own copy is genuinely first-hand.
        val alicesOwn = sos()
        assertEquals(EpistemologyTier.PRATYAKSA, alicesOwn.effectiveTier)

        ta.send(Codecs.forFrameBudget(ta.maxFrameBytes).encode(alicesOwn))
        net.runUntilIdle()

        val bobs = requireNotNull(heldByBob)
        assertEquals(
            EpistemologyTier.PRATYAKSA, bobs.tier,
            "Alice's own claim about her own situation is preserved verbatim",
        )
        assertEquals(
            EpistemologyTier.SABDA, bobs.effectiveTier,
            "but Bob was told, not shown — he must not hold this at first-hand strength",
        )
    }

    @Test
    fun `a peer cannot forge first-hand standing by pinning its hop count`() {
        // hops is a sender-controlled field. A node that is faulty, spoofed, or simply
        // never increments it would otherwise arrive looking like direct observation, and
        // the first-hand gate is exactly what a bad actor wants to get past — it is the
        // tier that authorises the irreversible action.
        val net = SimNetwork(latencyMs = 1)
        net.link(alice, bob)
        val ta = net.transportFor(alice).also { it.start() }
        val tb = net.transportFor(bob).also { it.start() }

        var heldByBob: Envelope? = null
        tb.onReceive { _, frame -> heldByBob = Codecs.forFrameBudget(tb.maxFrameBytes).decode(frame).asReceived() }

        ta.send(Codecs.forFrameBudget(ta.maxFrameBytes).encode(sos(hops = 0)))
        net.runUntilIdle()

        assertEquals(EpistemologyTier.SABDA, requireNotNull(heldByBob).effectiveTier)
    }

    @Test
    fun `asReceived is idempotent, so a relay chain does not inflate the hop count`() {
        val once = sos(hops = 3).asReceived()
        assertEquals(3, once.hops)
        assertEquals(once, once.asReceived())
    }

    @Test
    fun `a node's own observation is never downgraded`() {
        // asReceived is only for envelopes taken off a link. An agent's own report stays
        // first-hand, which is what lets it trigger the immediate override.
        assertEquals(EpistemologyTier.PRATYAKSA, sos().effectiveTier)
    }
}
