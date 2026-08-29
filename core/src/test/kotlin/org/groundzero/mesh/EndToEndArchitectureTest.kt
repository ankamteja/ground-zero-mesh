package org.groundzero.mesh

import org.groundzero.mesh.agent.NodeAgent
import org.groundzero.mesh.agent.SensoryFlags
import org.groundzero.mesh.agent.SensoryWindow
import org.groundzero.mesh.agent.SlmFeatureVector
import org.groundzero.mesh.gateway.DigitalTwin
import org.groundzero.mesh.gateway.RadminLlmSummarizer
import org.groundzero.mesh.gateway.ResponderRanking
import org.groundzero.mesh.propagation.DedupCluster
import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.Gossip
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import org.groundzero.mesh.propagation.TrustConsensus
import org.groundzero.mesh.transport.SimNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole finalized architecture over one simulated mesh: Stage 0 override, the Math
 * Engine's projection and flag byte, L2 propagation and asymmetric trust, L3 ranking, the
 * Digital Twin's spatial state, and the Radmin advisory.
 *
 * Topology is A — B — C. A is the victim, B relays, C is the gateway. A and C cannot hear
 * each other, so anything C holds arrived through B.
 */
class EndToEndArchitectureTest {

    private val victim = NodeId.parse("0000-0000-000a")
    private val relay = NodeId.parse("0000-0000-000b")
    private val gateway = NodeId.parse("0000-0000-000c")
    private val salt = "0123456789abcdef0123456789abcdef"

    private class Mesh {
        lateinit var net: SimNetwork
        lateinit var agent: NodeAgent
        lateinit var gatewayClusters: DedupCluster
        lateinit var gatewayGossip: Gossip
    }

    private fun mesh(zone: String = "sector-7-roof"): Mesh = Mesh().apply {
        net = SimNetwork(latencyMs = 5)
        net.link(victim, relay)
        net.link(relay, gateway)

        val victimTransport = net.transportFor(victim).also { it.start() }
        val relayTransport = net.transportFor(relay).also { it.start() }
        val gatewayTransport = net.transportFor(gateway).also { it.start() }

        val relayGossip = Gossip(relayTransport, clockMs = net::nowMs)
        relayTransport.onReceive { from, frame -> relayGossip.ingest(frame, from) }

        gatewayClusters = DedupCluster()
        gatewayGossip = Gossip(gatewayTransport, gatewayClusters, clockMs = net::nowMs)
        gatewayTransport.onReceive { from, frame -> gatewayGossip.ingest(frame, from) }

        agent = NodeAgent(
            nodeId = victim,
            saltFingerprint = salt,
            addressZone = zone,
            transport = victimTransport,
            clockMs = net::nowMs,
        )
    }

    @Test
    fun `the full cascade reaches the responder board as one incident`() {
        val m = mesh()

        // Stage 0 — the button, at t=0, before anything is inferred.
        m.agent.raiseSos(Severity.DROWNING_IMMINENT)
        m.net.runUntilIdle()

        // Stages 1 and 2 — the window fills and the engine projects it.
        val window = SensoryWindow(audioWater = 0.95, imuPinned = 0.85, ambientLight = 0.05)
        m.agent.senseVector(SlmFeatureVector.from(window), accelMagnitude = 0.7)

        // Stage 3 — the enriched re-broadcast, on the original incident timestamp.
        m.net.advance(1_000)
        assertNotNull(m.agent.completeSensoryWindow(window))
        m.net.runUntilIdle()

        val clusters = m.gatewayClusters.clusters()
        assertEquals(1, clusters.size, "one person is one incident, two broadcasts and two hops")

        val incident = clusters.single()
        assertEquals(victim, incident.origin)
        assertEquals(Severity.DROWNING_IMMINENT, incident.severity)
        assertEquals(
            EpistemologyTier.SABDA,
            incident.tier,
            "the gateway never heard the victim directly — it holds testimony, not observation",
        )
        assertTrue(SensoryFlags.isSet(incident.flags, SensoryFlags.MANUAL_SOS))
        assertTrue(SensoryFlags.isSet(incident.flags, SensoryFlags.AUDIO_WATER))
        assertTrue(SensoryFlags.isSet(incident.flags, SensoryFlags.STAGE_2_ENRICHED))
        assertNotNull(incident.featureVector, "the enriched broadcast carries v_SLM")
    }

    @Test
    fun `the board, the twin and the advisory all agree with the ranker`() {
        val m = mesh()
        m.agent.raiseSos(Severity.DROWNING_IMMINENT)
        m.net.runUntilIdle()
        m.net.advance(1_000)
        m.agent.completeSensoryWindow(SensoryWindow(audioWater = 0.9, imuPinned = 0.8))
        m.net.runUntilIdle()

        val board = ResponderRanking.rank(m.gatewayClusters.clusters(), m.net.nowMs())
        val twin = DigitalTwin.snapshot(board, m.net.nowMs())
        val advisory = RadminLlmSummarizer().summarise(board, twin)

        assertEquals(board.size, twin.nodes.size, "the twin renders the board, nothing more")
        assertEquals(board.first().cluster.key, twin.nodes.first().key)
        assertEquals(1, twin.placedCount, "sector-7-roof names a floor")
        assertEquals(TwinRoofIndex, twin.nodes.first().floor.index)
        assertTrue(advisory.contains("Advisory only"))
        assertTrue(advisory.contains("rushing water"), advisory)
    }

    @Test
    fun `an unplaceable zone tag is reported as unplaced, never drawn on the ground floor`() {
        val m = mesh(zone = "unset")
        m.agent.raiseSos(Severity.STRUCTURAL_ENTRAPMENT)
        m.net.runUntilIdle()

        val board = ResponderRanking.rank(m.gatewayClusters.clusters(), m.net.nowMs())
        val twin = DigitalTwin.snapshot(board, m.net.nowMs())

        assertEquals(1, twin.unplacedCount)
        assertFalse(twin.nodes.single().placed)
        assertTrue(
            RadminLlmSummarizer().summarise(board, twin).contains("Localisation is not solved"),
        )
    }

    @Test
    fun `a relay that contradicts the severity loses standing at the gateway`() {
        val m = mesh()
        m.agent.raiseSos(Severity.DROWNING_IMMINENT)
        m.net.runUntilIdle()

        // The relay's own contradicting claim about the same incident.
        val forged = m.gatewayClusters.clusters().single()
        m.gatewayClusters.ingest(
            org.groundzero.mesh.propagation.Envelope(
                nodeId = victim,
                saltFingerprint = salt,
                addressZone = "sector-7-roof",
                tier = EpistemologyTier.SABDA,
                severity = Severity.OTHER,
                dangerScore = 0.1,
                timestamp = forged.key.substringAfter('@').toLong(),
                hops = 2,
            ),
            relay,
            m.net.nowMs(),
        )

        assertTrue(
            m.gatewayClusters.trustOf(relay) < TrustConsensus.NEUTRAL_TRUST,
            "conflicting telemetry must cost the relay standing",
        )
        assertEquals(
            Severity.DROWNING_IMMINENT,
            m.gatewayClusters.clusters().single().severity,
            "and the incident must not be walked back to a calmer claim",
        )
    }

    private val TwinRoofIndex get() = DigitalTwin.ROOF_INDEX
}
