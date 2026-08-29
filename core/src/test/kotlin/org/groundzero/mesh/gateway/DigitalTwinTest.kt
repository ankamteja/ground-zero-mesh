package org.groundzero.mesh.gateway

import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.FirstHandGate
import org.groundzero.mesh.propagation.IncidentCluster
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DigitalTwinTest {

    private val victim = NodeId(1)
    private val relay = NodeId(2)

    private fun ranked(corroborators: Set<NodeId>) = RankedIncident(
        cluster = IncidentCluster(
            key = "k1",
            origin = victim,
            zone = "unset",
            severity = Severity.OTHER,
            dangerScore = 1.0,
            tier = EpistemologyTier.PRATYAKSA,
            corroborators = corroborators,
            minHops = 1,
            firstSeenMs = 0,
            lastUpdatedMs = 0,
        ),
        priority = 0.5,
        standing = FirstHandGate.Standing.SINGLE_UNCONFIRMED,
        withinBudget = true,
        reasons = emptyList(),
    )

    @Test
    fun `a direct victim to responder link draws no carrier for itself`() {
        // No relay in the topology: the peer that handed the responder this report is the
        // origin itself. corroborators therefore holds just {victim}, and the twin must not
        // draw the victim a second time as their own relay.
        val snapshot = DigitalTwin.snapshot(listOf(ranked(corroborators = setOf(victim))), nowMs = 0)
        assertTrue(snapshot.links.isEmpty(), "no genuine relay carried this — nothing to draw")
    }

    @Test
    fun `a real relay still draws a carrier`() {
        val snapshot = DigitalTwin.snapshot(listOf(ranked(corroborators = setOf(victim, relay))), nowMs = 0)
        assertEquals(listOf(relay), snapshot.links.map { it.carrier })
    }
}
