package org.groundzero.mesh.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MathEngineTest {

    private val engine = MathEngine()

    @Test
    fun `a silent vector projects to zero`() {
        assertEquals(0.0, engine.project(SlmFeatureVector.ZERO), 1e-9)
    }

    @Test
    fun `the projection is the weighted sum plus the IMU term`() {
        val v = SlmFeatureVector.of(
            SlmFeatureVector.AUDIO_WATER to 1.0,
            SlmFeatureVector.IMU_PINNED to 0.5,
        )
        // 0.20 * 1.0 + 0.20 * 0.5 + 0.25 * 0.4
        assertEquals(0.40, engine.project(v, accelMagnitude = 0.4), 1e-6)
    }

    @Test
    fun `a fully saturated device saturates the signal instead of running past one`() {
        val v = SlmFeatureVector(FloatArray(SlmFeatureVector.LENGTH) { 1f })
        assertEquals(1.0, engine.project(v, accelMagnitude = 1.0), 1e-9)
    }

    @Test
    fun `the explanation names the feature that moved the number`() {
        val v = SlmFeatureVector.of(
            SlmFeatureVector.AUDIO_VOICE to 0.4,
            SlmFeatureVector.IMU_PINNED to 0.9,
        )
        assertTrue(engine.explain(v).startsWith("imu:pinned"), engine.explain(v))
    }

    @Test
    fun `a vector of the wrong length is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { SlmFeatureVector(FloatArray(8)) }
    }

    @Test
    fun `an unnormalised accelerometer magnitude is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            engine.project(SlmFeatureVector.ZERO, accelMagnitude = 9.81)
        }
    }

    @Test
    fun `a window fills the slots it describes`() {
        val v = SlmFeatureVector.from(
            SensoryWindow(audioWater = 0.8, imuPinned = 0.6, ambientLight = 0.1),
        )
        assertEquals(0.8f, v[SlmFeatureVector.AUDIO_WATER], 1e-6f)
        assertEquals(0.6f, v[SlmFeatureVector.IMU_PINNED], 1e-6f)
        assertEquals(0.9f, v[SlmFeatureVector.LIGHT_ENCLOSED], 1e-6f)
    }
}

class SensoryFlagsTest {

    @Test
    fun `nothing asserted is a zero byte`() {
        assertEquals(0, SensoryFlags.encode(SlmFeatureVector.ZERO).toInt())
    }

    @Test
    fun `only slots above the threshold set their bit`() {
        val v = SlmFeatureVector.of(
            SlmFeatureVector.AUDIO_WATER to 0.9,
            SlmFeatureVector.AUDIO_VOICE to 0.1,
            SlmFeatureVector.IMU_PINNED to 0.5,
        )
        val flags = SensoryFlags.encode(v)
        assertTrue(SensoryFlags.isSet(flags, SensoryFlags.AUDIO_WATER))
        assertTrue(SensoryFlags.isSet(flags, SensoryFlags.IMU_PINNED))
        assertFalse(SensoryFlags.isSet(flags, SensoryFlags.AUDIO_SCREAMING))
    }

    @Test
    fun `the manual SOS bit comes from the human, not from a sensor`() {
        val flags = SensoryFlags.encode(SlmFeatureVector.ZERO, manualSos = true)
        assertTrue(SensoryFlags.isSet(flags, SensoryFlags.MANUAL_SOS))
        assertEquals("0x20", SensoryFlags.toHex(flags))
    }

    @Test
    fun `the plan's illustrative byte decodes to what it claims`() {
        val flags = 0x8F.toByte()
        val described = SensoryFlags.describe(flags)
        // Bit 7 is no longer reserved: it is the structural-crack channel, which the
        // projection has always weighted but the byte never reported.
        assertEquals(
            listOf("rushing water", "screaming", "structural crack", "impact", "pinned"),
            described,
        )
        assertEquals("0x8f", SensoryFlags.toHex(flags))
    }
}
