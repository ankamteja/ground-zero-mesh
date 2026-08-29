package org.groundzero.mesh.propagation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerTest {

    private fun peer(ok: Int = 0, fail: Int = 0, lastSeenMs: Long = 0) =
        Peer(NodeId(1), "addr", lastSeenMs, ok, fail)

    @Test
    fun healthRule() {
        assertTrue(peer(ok = 0, fail = 4).healthy)
        assertFalse(peer(ok = 0, fail = 5).healthy)
        assertTrue(peer(ok = 1, fail = 99).healthy)
    }

    @Test
    fun silenceThresholdIsNotTheReferenceRepoSixSeconds() {
        // Regression guard: ANW used 6s. A trapped, throttled phone is silent for minutes.
        assertTrue(Peer.SILENT_AFTER_MS >= 60_000, "SILENT_AFTER_MS must be minutes, not seconds")
    }

    @Test
    fun aliveWithinThreshold() {
        val p = peer(lastSeenMs = 0)
        assertEquals(PeerLiveness.ALIVE, p.liveness(Peer.SILENT_AFTER_MS - 1))
    }

    @Test
    fun silentAfterThresholdButNeverGoneByTimer() {
        val p = peer(lastSeenMs = 0)
        assertEquals(PeerLiveness.SILENT, p.liveness(Peer.SILENT_AFTER_MS))
        assertEquals(PeerLiveness.SILENT, p.liveness(Peer.SILENT_AFTER_MS * 1000))
    }

    @Test
    fun goneOnlyWhenExplicit() {
        val p = peer(lastSeenMs = 0).markGone()
        assertEquals(PeerLiveness.GONE, p.liveness(1))
    }

    @Test
    fun inboundRefreshesAndHeals() {
        val p = peer(ok = 0, fail = 3, lastSeenMs = 0).sawInbound(5_000)
        assertEquals(5_000, p.lastSeenMs)
        assertEquals(1, p.ok)
        assertEquals(2, p.fail)
    }

    @Test
    fun decayPullsTowardNeutralNeverDrops() {
        var p = peer(ok = 3, fail = 8)
        repeat(20) { p = p.decayedToward(0) }
        assertEquals(0, p.ok)
        assertEquals(0, p.fail)
    }
}
