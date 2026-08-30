package org.groundzero.mesh.agent

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The failure modes the Math Engine has to survive, as opposed to the arithmetic it has to
 * get right (that is `MathEngineTest`).
 *
 * Every case here is something that would otherwise degrade *silently*: a score that pins at
 * 1.0 forever, a flag byte claiming evidence nobody sensed, a "why" line a responder in a
 * comma-decimal locale cannot read. None of them throw on their own, which is exactly why
 * they need pinning.
 */
class MathEngineRobustnessTest {

    private val defaultLocale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    // --- W cannot be mutated out from under a live engine ---

    @Test
    fun `mutating the array a caller passed in cannot re-tune the engine`() {
        val mine = MathEngine.defaultWeights()
        val engine = MathEngine(mine)
        val before = engine.project(saturated())

        mine[SlmFeatureVector.AUDIO_WATER] = 0.0

        assertEquals(before, engine.project(saturated()), 1e-12)
    }

    @Test
    fun `mutating what the engine hands back cannot re-tune it either`() {
        val engine = MathEngine()
        val before = engine.project(saturated())

        engine.weights[SlmFeatureVector.AUDIO_WATER] = 0.0

        assertEquals(before, engine.project(saturated()), 1e-12)
    }

    @Test
    fun `the shared defaults survive an engine whose weights were mutated`() {
        val first = MathEngine()
        first.weights[SlmFeatureVector.AUDIO_WATER] = 99.0
        MathEngine.defaultWeights()[SlmFeatureVector.IMU_PINNED] = 99.0

        // A later engine, constructed from the defaults, is unaffected.
        assertEquals(0.20, MathEngine().weights[SlmFeatureVector.AUDIO_WATER], 1e-12)
        assertEquals(0.20, MathEngine().weights[SlmFeatureVector.IMU_PINNED], 1e-12)
    }

    // --- the saturation contract every downstream threshold is calibrated against ---

    @Test
    fun `weights summing above one are rejected rather than pinning every score at one`() {
        val tooHeavy = DoubleArray(SlmFeatureVector.LENGTH).also { it[0] = 0.9; it[1] = 0.9 }
        val error = assertFailsWith<IllegalArgumentException> { MathEngine(tooHeavy) }
        assertTrue(error.message!!.contains("saturates"), error.message!!)
    }

    @Test
    fun `the engine's own defaults are accepted despite not summing to exactly one`() {
        // 0.20+0.10+0.15+0.20+0.15+0.05+0.05+0.10 is 1.0000000000000002 in IEEE 754.
        assertTrue(MathEngine.defaultWeights().sum() > 1.0)
        MathEngine() // must not throw
    }

    @Test
    fun `a sub-unit weighting is still allowed`() {
        val partial = DoubleArray(SlmFeatureVector.LENGTH).also { it[SlmFeatureVector.AUDIO_WATER] = 0.5 }
        assertEquals(0.5, MathEngine(partial, imuWeight = 0.0).project(saturated()), 1e-12)
    }

    // --- non-finite inputs ---

    @Test
    fun `an infinite weight is rejected, not silently saturating every projection`() {
        val infinite = DoubleArray(SlmFeatureVector.LENGTH).also { it[0] = Double.POSITIVE_INFINITY }
        val error = assertFailsWith<IllegalArgumentException> { MathEngine(infinite) }
        assertTrue(error.message!!.contains("not finite"), error.message!!)
    }

    @Test
    fun `a NaN weight is reported as not finite, not as negative`() {
        val nan = DoubleArray(SlmFeatureVector.LENGTH).also { it[SlmFeatureVector.IMU_PINNED] = Double.NaN }
        val error = assertFailsWith<IllegalArgumentException> { MathEngine(nan) }
        assertTrue(error.message!!.contains("not finite"), error.message!!)
        assertTrue(error.message!!.contains("imu:pinned"), error.message!!)
    }

    @Test
    fun `a non-finite IMU weight is rejected`() {
        assertFailsWith<IllegalArgumentException> { MathEngine(imuWeight = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { MathEngine(imuWeight = Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `a non-finite accelerometer magnitude is rejected by both project and explain`() {
        val engine = MathEngine()
        assertFailsWith<IllegalArgumentException> { engine.project(SlmFeatureVector.ZERO, Double.NaN) }
        assertFailsWith<IllegalArgumentException> { engine.explain(SlmFeatureVector.ZERO, Double.NaN) }
    }

    @Test
    fun `explain refuses the same out-of-range magnitude project refuses`() {
        // An explanation must never describe an input the score itself would have rejected.
        assertFailsWith<IllegalArgumentException> { MathEngine().explain(SlmFeatureVector.ZERO, 9.81) }
    }

    @Test
    fun `a NaN feature names the slot that carried it`() {
        val values = FloatArray(SlmFeatureVector.LENGTH)
        values[SlmFeatureVector.AUDIO_WATER] = Float.NaN
        val error = assertFailsWith<IllegalArgumentException> { SlmFeatureVector(values) }
        assertTrue(error.message!!.contains("not finite"), error.message!!)
        assertTrue(error.message!!.contains("audio:water"), error.message!!)
    }

    // --- vector identity ---

    @Test
    fun `negative zero does not split a vector's identity`() {
        val positive = SlmFeatureVector(FloatArray(SlmFeatureVector.LENGTH))
        val negative = SlmFeatureVector(FloatArray(SlmFeatureVector.LENGTH) { -0.0f })
        assertEquals(positive, negative)
        assertEquals(positive.hashCode(), negative.hashCode())
        assertTrue(negative.toString().contains("0.00"))
        assertTrue(!negative.toString().contains("-0.00"), negative.toString())
    }

    @Test
    fun `a vector cannot be mutated through the array it was built from or hands back`() {
        val raw = FloatArray(SlmFeatureVector.LENGTH)
        raw[SlmFeatureVector.AUDIO_WATER] = 0.5f
        val vector = SlmFeatureVector(raw)

        raw[SlmFeatureVector.AUDIO_WATER] = 1.0f
        vector.values[SlmFeatureVector.IMU_PINNED] = 1.0f

        assertEquals(0.5f, vector[SlmFeatureVector.AUDIO_WATER])
        assertEquals(0.0f, vector[SlmFeatureVector.IMU_PINNED])
    }

    @Test
    fun `an out-of-range slot is named rather than throwing an array index error`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SlmFeatureVector.of(SlmFeatureVector.LENGTH to 1.0)
        }
        assertTrue(error.message!!.contains("slot"), error.message!!)
        assertFailsWith<IllegalArgumentException> { SlmFeatureVector.ZERO[-1] }
        assertFailsWith<IllegalArgumentException> { SlmFeatureVector.ZERO[SlmFeatureVector.LENGTH] }
    }

    // --- the flag byte ---

    @Test
    fun `a zero vector asserts nothing even at a zero threshold`() {
        // Absence of evidence must never encode as evidence: at threshold 0.0 every slot
        // satisfies `>= threshold`, and the byte would claim water, screaming, pinned,
        // impact and darkness on a phone that sensed nothing.
        val flags = SensoryFlags.encode(SlmFeatureVector.ZERO, threshold = 0.0)
        assertEquals(SensoryFlags.NONE, flags)
        assertEquals(emptyList(), SensoryFlags.describe(flags))
    }

    @Test
    fun `a manual SOS still rides on an otherwise empty byte`() {
        val flags = SensoryFlags.encode(SlmFeatureVector.ZERO, manualSos = true, threshold = 0.0)
        assertTrue(SensoryFlags.isSet(flags, SensoryFlags.MANUAL_SOS))
        assertTrue(!SensoryFlags.isSet(flags, SensoryFlags.AUDIO_WATER))
    }

    @Test
    fun `an out-of-range assert threshold is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SensoryFlags.encode(SlmFeatureVector.ZERO, threshold = -0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            SensoryFlags.encode(SlmFeatureVector.ZERO, threshold = Double.NaN)
        }
    }

    // --- evidence must survive the trip to the responder ---

    @Test
    fun `a structural crack reaches the flag byte, not just the score`() {
        // It was weighted 0.15 in the projection and had no bit, so it moved the danger
        // score and then disappeared: the responder saw a raised number with no evidence
        // naming what raised it.
        val crack = SlmFeatureVector.of(SlmFeatureVector.AUDIO_STRUCTURAL to 0.9)
        assertTrue(MathEngine().project(crack) > 0.0)

        val flags = SensoryFlags.encode(crack)
        assertTrue(SensoryFlags.isSet(flags, SensoryFlags.AUDIO_STRUCTURAL))
        assertTrue(SensoryFlags.describe(flags).contains("structural crack"))
    }

    @Test
    fun `every channel the projection weights can also be named on the board`() {
        // A weighted-but-unnameable channel is the exact defect bit 7 fixed. Slots that
        // carry weight must either set a flag or be deliberately listed here.
        val weights = MathEngine.defaultWeights()
        val nameable = mapOf(
            SlmFeatureVector.AUDIO_WATER to SensoryFlags.AUDIO_WATER,
            SlmFeatureVector.AUDIO_VOICE to SensoryFlags.AUDIO_SCREAMING,
            SlmFeatureVector.AUDIO_STRUCTURAL to SensoryFlags.AUDIO_STRUCTURAL,
            SlmFeatureVector.IMU_PINNED to SensoryFlags.IMU_PINNED,
            SlmFeatureVector.IMU_SHOCK to SensoryFlags.IMU_IMPACT,
            SlmFeatureVector.LIGHT_ENCLOSED to SensoryFlags.LOW_LIGHT,
        )
        // IMU_STILL (0.05) and EVENT_WEIGHT (0.10) are deliberately score-only: stillness is
        // not evidence a responder can act on, and the event weight is a shape, not a thing
        // that was heard or felt.
        val scoreOnly = setOf(SlmFeatureVector.IMU_STILL, SlmFeatureVector.EVENT_WEIGHT)

        (0 until SlmFeatureVector.LENGTH)
            .filter { weights[it] > 0.0 && it !in scoreOnly }
            .forEach { slot ->
                val flag = nameable[slot]
                assertTrue(flag != null, "slot ${MathEngine.slotName(slot)} is weighted but has no flag bit")
                val only = SlmFeatureVector.of(slot to 1.0)
                assertTrue(
                    SensoryFlags.isSet(SensoryFlags.encode(only), flag!!),
                    "slot ${MathEngine.slotName(slot)} does not set its flag",
                )
            }
    }

    @Test
    fun `bit names still cover all eight bits`() {
        assertEquals(8, SensoryFlags.BIT_NAMES.size)
        assertEquals("structural crack", SensoryFlags.BIT_NAMES[7])
    }

    // --- locale ---

    @Test
    fun `numbers render the same on a comma-decimal phone`() {
        Locale.setDefault(Locale.GERMANY)
        val engine = MathEngine()
        val vector = SlmFeatureVector.of(SlmFeatureVector.AUDIO_WATER to 1.0)

        assertTrue(engine.explain(vector).contains("0.20"), engine.explain(vector))
        assertTrue(!engine.explain(vector).contains("0,20"), engine.explain(vector))
        assertTrue(vector.toString().contains("1.00"), vector.toString())
        assertEquals("0x21", SensoryFlags.toHex(SensoryFlags.encode(vector, manualSos = true)))
    }

    @Test
    fun `the IMU explanation is locale-independent too`() {
        Locale.setDefault(Locale.GERMANY)
        val text = MathEngine().explain(SlmFeatureVector.ZERO, accelMagnitude = 1.0)
        assertTrue(text.contains("0.25"), text)
    }

    // --- determinism ---

    @Test
    fun `tied contributions always name the same feature`() {
        // audio:structural and imu:shock both weigh 0.15; the lower slot index must win every
        // time, or two identical boards explain themselves differently.
        val tied = SlmFeatureVector.of(
            SlmFeatureVector.AUDIO_STRUCTURAL to 1.0,
            SlmFeatureVector.IMU_SHOCK to 1.0,
        )
        val engine = MathEngine()
        val first = engine.explain(tied)
        repeat(20) { assertEquals(first, engine.explain(tied)) }
        assertTrue(first.contains("audio:structural"), first)
    }

    @Test
    fun `two engines built from the defaults agree exactly`() {
        val a = MathEngine()
        val b = MathEngine(MathEngine.defaultWeights())
        val vector = SlmFeatureVector.of(
            SlmFeatureVector.AUDIO_WATER to 0.7,
            SlmFeatureVector.IMU_PINNED to 0.4,
        )
        assertEquals(a.project(vector, 0.3), b.project(vector, 0.3), 0.0)
        assertNotEquals(0.0, a.project(vector, 0.3))
    }

    private fun saturated() = SlmFeatureVector(FloatArray(SlmFeatureVector.LENGTH) { 1f })
}
