package org.groundzero.mesh.agent

import org.groundzero.mesh.propagation.Codecs
import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import org.groundzero.mesh.transport.SimNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeAgentTest {

    private val me = NodeId.parse("0000-0000-000a")
    private val peer = NodeId.parse("0000-0000-000b")
    private val salt = "0123456789abcdef0123456789abcdef"

    private class Clock(var nowMs: Long = 0L) {
        fun advance(ms: Long) { nowMs += ms }
    }

    private fun agent(
        clock: Clock = Clock(),
        classifier: SensoryClassifier = DeterministicSensoryClassifier(),
    ): Pair<NodeAgent, SimNetwork> {
        val net = SimNetwork(latencyMs = 1)
        net.link(me, peer)
        val transport = net.transportFor(me).also { it.start() }
        net.transportFor(peer).start()
        return NodeAgent(
            nodeId = me,
            saltFingerprint = salt,
            addressZone = "sector-7-roof",
            transport = transport,
            clockMs = { clock.nowMs },
            classifier = classifier,
        ) to net
    }

    // -------------------------------------------------------------------- the SOS path

    @Test
    fun `an sos broadcasts immediately at full danger, without waiting on the score`() {
        val (node, _) = agent()

        val envelope = node.raiseSos(Severity.DROWNING_IMMINENT, atSeconds = 1_724_900_000L)

        assertEquals(NodeAgent.OVERRIDE_SCORE, envelope.dangerScore)
        assertEquals(EpistemologyTier.PRATYAKSA, envelope.tier)
        assertEquals(Severity.DROWNING_IMMINENT, envelope.severity)
        assertTrue("OVERRIDE_ACTIVE" in envelope.views)
        assertEquals(AgentState.ALARM, node.state, "the override drives the posture directly")
    }

    @Test
    fun `the sos does not have to climb the ema first`() {
        val (node, _) = agent()

        // Ambient sensing has to climb. With this alpha a maximal reading reaches WATCH on
        // the first tick but needs three before the agent is in ALARM, and for someone
        // going under water those ticks are the whole margin.
        val ticksToAlarm = generateSequence(1) { it + 1 }
            .first { node.senseTick(1.0) == AgentState.ALARM }
        assertTrue(ticksToAlarm > 1, "escalation took " + ticksToAlarm + " tick(s)")
    }

    @Test
    fun `the override reaches alarm on the first tick, from cold`() {
        val (node, _) = agent()

        assertEquals(AgentState.CALM, node.state)
        node.raiseSos(Severity.DROWNING_IMMINENT)
        assertEquals(
            AgentState.ALARM, node.state,
            "a first-hand SOS must not have to earn its way up the score machine",
        )
    }

    @Test
    fun `the refined broadcast updates the same incident, it does not raise a second one`() {
        val clock = Clock()
        val (node, _) = agent(clock)

        val stage1 = node.raiseSos(Severity.DROWNING_IMMINENT, atSeconds = 1_724_900_000L)
        clock.advance(20_000)
        val stage3 = node.completeSensoryWindow(
            SensoryWindow(audioWater = 0.9, imuPinned = 0.7, ambientLight = 0.05),
        )

        assertNotNull(stage3)
        assertEquals(
            stage1.dedupKey, stage3.dedupKey,
            "same node, same incident timestamp — downstream must update, not duplicate",
        )
        assertNotNull(stage3.slmSummary)
        assertTrue(stage3.slmSummary!!.contains("RUSHING_WATER"))
    }

    @Test
    fun `pressing sos again while one is already active updates it, does not raise a second incident`() {
        val clock = Clock()
        val (node, _) = agent(clock)

        val first = node.raiseSos(Severity.STRUCTURAL_ENTRAPMENT, atSeconds = 1_724_900_000L)
        clock.advance(3_000)
        // A person re-pressing SOS a few seconds later, before the sensory window closes and
        // before anyone cleared the incident, is restating urgency about the same emergency —
        // not reporting a second one.
        val second = node.raiseSos(Severity.DROWNING_IMMINENT, atSeconds = 1_724_900_003L)

        assertEquals(
            first.dedupKey, second.dedupKey,
            "same person, same incident — a re-press must update the board entry, not multiply it",
        )
        assertEquals(1_724_900_000L, second.timestamp, "the original incident's anchor wins, not the new press time")
        assertEquals(Severity.DROWNING_IMMINENT, second.severity, "the latest severity the person chose still ships")
    }

    @Test
    fun `sos after the incident is cleared starts a genuinely new one`() {
        val clock = Clock()
        val (node, _) = agent(clock)

        val first = node.raiseSos(Severity.STRUCTURAL_ENTRAPMENT, atSeconds = 1_724_900_000L)
        node.clearIncident()
        clock.advance(60_000)
        val second = node.raiseSos(Severity.DROWNING_IMMINENT, atSeconds = 1_724_900_060L)

        assertTrue(first.dedupKey != second.dedupKey, "rescued and pressing again is a new incident")
    }

    @Test
    fun `a classifier that throws cannot take the agent down or undo the override`() {
        val clock = Clock()
        val exploding = SensoryClassifier { error("model failed to load") }
        val (node, _) = agent(clock, exploding)

        val stage1 = node.raiseSos(Severity.STRUCTURAL_ENTRAPMENT)
        clock.advance(5_000)

        // Stage 2 is advisory. Failing costs enrichment and nothing else.
        assertNull(node.completeSensoryWindow(SensoryWindow(imuPinned = 0.9)))
        assertEquals(AgentState.ALARM, node.state)
        assertEquals(1, node.emitted.size, "the override broadcast still stands")
        assertEquals(NodeAgent.OVERRIDE_SCORE, node.emitted.single().dangerScore)
        assertEquals(stage1.dedupKey, node.emitted.single().dedupKey)
    }

    @Test
    fun `the sensory window closes and sensors are not asked again`() {
        val clock = Clock()
        val (node, _) = agent(clock)
        node.raiseSos(Severity.DROWNING_IMMINENT)

        assertTrue(node.sensoryWindowOpen())
        clock.advance(NodeAgent.SENSORY_WINDOW_MS + 1)
        assertTrue(!node.sensoryWindowOpen(), "the window must close so sensors can sleep")
        assertNull(node.completeSensoryWindow(SensoryWindow(audioWater = 0.9)))
    }

    @Test
    fun `enrichment happens once, not on every call`() {
        val clock = Clock()
        val (node, _) = agent(clock)
        node.raiseSos(Severity.DROWNING_IMMINENT)
        clock.advance(1_000)

        assertNotNull(node.completeSensoryWindow(SensoryWindow(audioWater = 0.9)))
        assertNull(node.completeSensoryWindow(SensoryWindow(audioWater = 0.9)))
    }

    @Test
    fun `an enriched report never falls below the sos floor`() {
        val clock = Clock()
        val (node, _) = agent(clock)
        node.raiseSos(Severity.DROWNING_IMMINENT)
        clock.advance(1_000)

        // Weak sensory evidence must not walk back a person's own statement that they are
        // in trouble. The classifier can add confidence; it cannot subtract testimony.
        val refined = node.completeSensoryWindow(SensoryWindow(imuPinned = 0.4))
        assertNotNull(refined)
        assertTrue(
            refined.dangerScore >= NodeAgent.REFINED_FLOOR,
            "refined score was " + refined.dangerScore,
        )
    }

    // ------------------------------------------------------------------ the tickers

    @Test
    fun `a quiet node says nothing at all`() {
        val clock = Clock()
        val (node, _) = agent(clock)

        repeat(20) { node.senseTick(0.0) }
        repeat(5) {
            clock.advance(NodeAgent.HEARTBEAT_INTERVAL_MS)
            assertNull(node.heartbeatTick(), "silence is the correct output of a calm ticker")
        }
        assertTrue(node.emitted.isEmpty())
    }

    @Test
    fun `sensing does not transmit - the two tickers are independent`() {
        val (node, _) = agent()
        repeat(30) { node.senseTick(1.0) }

        assertTrue(
            node.emitted.isEmpty(),
            "sensing must not imply talking, or the radio cadence cannot be tuned separately",
        )
    }

    @Test
    fun `a posture change is worth a heartbeat`() {
        val clock = Clock()
        val (node, _) = agent(clock)

        repeat(30) { node.senseTick(1.0) }
        val beat = node.heartbeatTick()

        assertNotNull(beat)
        assertTrue(beat.views.any { it.startsWith("STATE:") })
    }

    @Test
    fun `the same posture does not get repeated every tick`() {
        val clock = Clock()
        val (node, _) = agent(clock)
        repeat(30) { node.senseTick(1.0) }

        assertNotNull(node.heartbeatTick())
        assertNull(node.heartbeatTick(), "nothing changed and the interval has not elapsed")
    }

    @Test
    fun `an envelope actually crosses the link and arrives as testimony`() {
        val clock = Clock()
        val net = SimNetwork(latencyMs = 1)
        net.link(me, peer)
        val mine = net.transportFor(me).also { it.start() }
        val theirs = net.transportFor(peer).also { it.start() }

        val node = NodeAgent(me, salt, "sector-7-roof", mine, { clock.nowMs })
        var received: org.groundzero.mesh.propagation.Envelope? = null
        theirs.onReceive { _, frame ->
            received = Codecs.forFrameBudget(theirs.maxFrameBytes).decode(frame).asReceived()
        }

        node.raiseSos(Severity.DROWNING_IMMINENT)
        net.runUntilIdle()

        val got = assertNotNull(received)
        assertEquals(EpistemologyTier.PRATYAKSA, got.tier)
        assertEquals(EpistemologyTier.SABDA, got.effectiveTier)
        assertEquals(Severity.DROWNING_IMMINENT, got.severity)
    }

    @Test
    fun `explain surfaces the score, the baseline and the reason`() {
        val (node, _) = agent()
        repeat(10) { node.senseTick(0.9) }

        val explanation = node.explain()
        assertEquals(node.state, explanation.state)
        assertTrue(explanation.reason.isNotBlank())
    }

    @Test
    fun `explain says so while an override is active`() {
        val (node, _) = agent()
        node.raiseSos(Severity.DROWNING_IMMINENT)
        assertTrue(node.explain().reason.contains("override", ignoreCase = true))
    }

    // ------------------------------------------------------------------------------ GPS

    @Test
    fun `a GPS fix taken before the SOS reaches the broadcast envelope`() {
        val (node, _) = agent()
        node.updateGpsFix(37.7749f, -122.4194f)

        val envelope = node.raiseSos(Severity.DROWNING_IMMINENT)

        assertEquals(37.7749f, envelope.gpsLat)
        assertEquals(-122.4194f, envelope.gpsLon)
    }

    @Test
    fun `no GPS fix means the envelope honestly carries none`() {
        val (node, _) = agent()

        val envelope = node.raiseSos(Severity.DROWNING_IMMINENT)

        assertNull(envelope.gpsLat)
        assertNull(envelope.gpsLon)
    }

    // ------------------------------------------------------------------ the math engine

    @Test
    fun `stage 0 stamps the manual SOS flag and carries no vector`() {
        val (node, _) = agent()
        val envelope = node.raiseSos(Severity.DROWNING_IMMINENT)

        assertTrue(SensoryFlags.isSet(envelope.flags, SensoryFlags.MANUAL_SOS))
        assertNull(envelope.featureVector)
    }

    @Test
    fun `a projected vector moves the score without transmitting`() {
        val (node, _) = agent()
        // Water and a pinned device project to 0.625 — enough to watch, deliberately not
        // enough to alarm on its own. No single pair of channels can alarm the node.
        val drowning = SlmFeatureVector.of(
            SlmFeatureVector.AUDIO_WATER to 1.0,
            SlmFeatureVector.IMU_PINNED to 1.0,
        )
        repeat(10) { node.senseVector(drowning, accelMagnitude = 0.9) }

        assertEquals(AgentState.WATCH, node.state)
        assertTrue(node.emitted.isEmpty())
    }

    @Test
    fun `evidence across audio, IMU and light does alarm the node`() {
        val (node, _) = agent()
        val collapse = SlmFeatureVector.of(
            SlmFeatureVector.AUDIO_WATER to 1.0,
            SlmFeatureVector.AUDIO_STRUCTURAL to 1.0,
            SlmFeatureVector.IMU_PINNED to 1.0,
            SlmFeatureVector.IMU_SHOCK to 1.0,
            SlmFeatureVector.LIGHT_ENCLOSED to 1.0,
        )
        repeat(10) { node.senseVector(collapse, accelMagnitude = 0.9) }

        assertEquals(AgentState.ALARM, node.state)
    }

    @Test
    fun `stage 3 carries the vector and the enriched flag`() {
        val clock = Clock()
        val (node, _) = agent(clock)
        node.raiseSos(Severity.STRUCTURAL_ENTRAPMENT)
        clock.advance(5_000)

        val enriched = node.completeSensoryWindow(
            SensoryWindow(imuPinned = 0.9, ambientLight = 0.05, audioStructural = 0.6),
        )
        assertNotNull(enriched)
        assertTrue(SensoryFlags.isSet(enriched.flags, SensoryFlags.STAGE_2_ENRICHED))
        assertTrue(SensoryFlags.isSet(enriched.flags, SensoryFlags.IMU_PINNED))
        assertTrue(SensoryFlags.isSet(enriched.flags, SensoryFlags.LOW_LIGHT))
        assertEquals(0.9f, enriched.featureVector!![SlmFeatureVector.IMU_PINNED], 1e-6f)
        // Same incident, updated in place — not a second alert for the same person.
        assertEquals(node.emitted.first().dedupKey, enriched.dedupKey)
    }

    @Test
    fun `the enriched envelope still fits a LoRa frame with the vector aboard`() {
        val clock = Clock()
        val (node, _) = agent(clock)
        node.raiseSos(Severity.DROWNING_IMMINENT)
        clock.advance(1_000)

        val enriched = node.completeSensoryWindow(
            SensoryWindow(audioWater = 0.95, imuPinned = 0.8, ambientLight = 0.1),
        )
        assertNotNull(enriched)
        assertTrue(org.groundzero.mesh.propagation.CompactCodec.fits(enriched))
    }
}
