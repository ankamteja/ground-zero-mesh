package org.groundzero.mesh.agent

import org.groundzero.mesh.propagation.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HysteresisGateTest {

    @Test
    fun `a posture must be given up by a clear margin`() {
        val gate = HysteresisGate(watchThreshold = 0.35, alarmThreshold = 0.70, deadband = 0.05)
        gate.update(0.9)
        assertEquals(AgentState.ALARM, gate.state)

        // Just under the alarm threshold but inside the deadband: hold.
        gate.update(0.68)
        assertEquals(AgentState.ALARM, gate.state)

        // Clear of the deadband: stand down one step.
        gate.update(0.60)
        assertEquals(AgentState.WATCH, gate.state)
    }

    @Test
    fun `a score resting on a threshold does not flap`() {
        val gate = HysteresisGate()
        gate.update(0.9)
        val before = gate.transitions

        // Every one of these crosses 0.70 in the naive reading. Each crossing would be a
        // state change, and each state change would be a broadcast.
        repeat(20) { i -> gate.update(if (i % 2 == 0) 0.695 else 0.705) }

        assertEquals(before, gate.transitions, "expected no transitions inside the deadband")
    }

    @Test
    fun `escalation uses the plain thresholds, with no deadband on the way up`() {
        val gate = HysteresisGate(watchThreshold = 0.35, alarmThreshold = 0.70)
        assertEquals(AgentState.WATCH, gate.update(0.35), "at the threshold is over it")
        assertEquals(AgentState.ALARM, gate.update(0.70))
    }

    @Test
    fun `forceAlarm jumps straight to alarm for the override path`() {
        val gate = HysteresisGate()
        gate.forceAlarm()
        assertEquals(AgentState.ALARM, gate.state)
    }
}

class EventDetectorTest {

    @Test
    fun `a steady rise reads as gradual drift`() {
        val detector = EventDetector(windowSize = 6)
        var last: SensoryEvent? = null
        listOf(0.10, 0.18, 0.27, 0.36, 0.45, 0.55).forEach { last = detector.observe(it) ?: last }

        assertEquals(
            SensoryEvent.GRADUAL_DRIFT, last,
            "water rising is the pattern this system most needs to catch",
        )
    }

    @Test
    fun `a signal falling away reads as a sudden drop`() {
        val detector = EventDetector(windowSize = 6)
        var last: SensoryEvent? = null
        listOf(0.80, 0.82, 0.79, 0.30, 0.10, 0.05).forEach { last = detector.observe(it) ?: last }

        assertEquals(SensoryEvent.SUDDEN_DROP, last)
    }

    @Test
    fun `a flat run reads as a plateau`() {
        val detector = EventDetector(windowSize = 6)
        var last: SensoryEvent? = null
        repeat(6) { last = detector.observe(0.20) ?: last }

        assertEquals(SensoryEvent.PLATEAU, last)
    }

    @Test
    fun `nothing is reported until the window has filled`() {
        val detector = EventDetector(windowSize = 6)
        repeat(5) { assertNull(detector.observe(0.5)) }
        assertNotNull(detector.observe(0.5))
    }

    @Test
    fun `the retuned weights put water and silence above spikes`() {
        // The inversion is the whole point of the retune: in this domain a gradual drift is
        // water rising and a sudden drop is a person who stopped moving. Both outrank any
        // spike. Getting this backwards would make the agent quietest when it should be
        // loudest, so it is asserted rather than left to a constant nobody rereads.
        assertTrue(SensoryEvent.GRADUAL_DRIFT.dangerWeight > SensoryEvent.SUSTAINED_SPIKE.dangerWeight)
        assertTrue(SensoryEvent.SUDDEN_DROP.dangerWeight > SensoryEvent.SUSTAINED_SPIKE.dangerWeight)
        assertTrue(SensoryEvent.SUSTAINED_SPIKE.dangerWeight > SensoryEvent.OSCILLATION.dangerWeight)
        assertTrue(SensoryEvent.OSCILLATION.dangerWeight > SensoryEvent.PLATEAU.dangerWeight)
    }
}

class SensoryClassifierTest {

    private val classifier = DeterministicSensoryClassifier()

    @Test
    fun `channels fuse by max, so one witness is enough`() {
        // A phone pinned face-down in a dark basement has a blind camera and a deafened
        // microphone. Its IMU is the only witness. Summing or averaging would let the two
        // channels that saw nothing outvote the one that saw something.
        val summary = assertNotNull(
            classifier.classify(SensoryWindow(audioWater = 0.0, imuPinned = 0.95, ambientLight = 0.5)),
        )

        assertEquals(0.95, summary.fusedConfidence)
        assertEquals("imu", summary.decidingChannel?.name)
    }

    @Test
    fun `fusion never averages a strong channel away`() {
        val strong = assertNotNull(classifier.classify(SensoryWindow(imuPinned = 0.9)))
        val strongPlusSilence = assertNotNull(
            classifier.classify(SensoryWindow(imuPinned = 0.9, audioVoice = 0.0, ambientLight = 0.5)),
        )
        assertEquals(strong.fusedConfidence, strongPlusSilence.fusedConfidence)
    }

    @Test
    fun `a quiet window reports nothing rather than something empty`() {
        assertNull(classifier.classify(SensoryWindow(ambientLight = 0.5)))
    }

    @Test
    fun `darkness alone is not evidence`() {
        // A dark camera usually means a phone in a pocket. It only becomes evidence when
        // something else already suggests entrapment.
        assertNull(classifier.classify(SensoryWindow(ambientLight = 0.0)))

        val withPinned = assertNotNull(
            classifier.classify(SensoryWindow(ambientLight = 0.0, imuPinned = 0.8)),
        )
        assertTrue(withPinned.channels.any { it.token == "VIS:ENCLOSED" })
    }

    @Test
    fun `the wire summary fits the envelope budget`() {
        val summary = assertNotNull(
            classifier.classify(
                SensoryWindow(
                    audioWater = 0.95,
                    imuPinned = 0.9,
                    ambientLight = 0.0,
                    event = SensoryEvent.GRADUAL_DRIFT,
                ),
            ),
        )
        val wire = assertNotNull(summary.toWireString())

        assertTrue(
            wire.toByteArray(Charsets.UTF_8).size <= Envelope.MAX_SLM_SUMMARY_BYTES,
            "summary was " + wire.toByteArray(Charsets.UTF_8).size + " bytes: " + wire,
        )
        // And it must still be constructible as a real envelope field.
        assertTrue(wire.isNotBlank())
    }

    @Test
    fun `a truncated summary loses its weakest evidence, not its strongest`() {
        val summary = SensorySummary(
            channels = listOf(
                SensoryChannel("audio", 0.95, "AUDIO:RUSHING_WATER"),
                SensoryChannel("imu", 0.90, "IMU:PINNED"),
                SensoryChannel("pattern", 0.10, "EVENT:SOMETHING_VERY_LONG_INDEED"),
            ),
            event = null,
        )
        val wire = assertNotNull(summary.toWireString())

        assertTrue(wire.startsWith("AUDIO:RUSHING_WATER"), wire)
        assertTrue(wire.toByteArray(Charsets.UTF_8).size <= Envelope.MAX_SLM_SUMMARY_BYTES)
    }
}
