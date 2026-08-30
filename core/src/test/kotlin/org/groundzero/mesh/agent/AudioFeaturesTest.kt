package org.groundzero.mesh.agent

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [AudioFeatures] against synthetic signals whose spectral shape is known by construction.
 *
 * This is the only honest way to test a microphone classifier without a microphone: build
 * the exact waveform each branch claims to recognise, and assert the branch fires — and,
 * just as importantly, that the *other* branches do not. A classifier that answers "water"
 * to everything would pass a one-signal test suite and put "rising water" on every board in
 * the mesh.
 */
class AudioFeaturesTest {

    private val rate = 16_000

    // ------------------------------------------------------------------ signal builders

    private fun tone(hz: Double, amplitude: Double = 0.4, samples: Int = 4096) =
        FloatArray(samples) { (amplitude * sin(2.0 * PI * hz * it / rate)).toFloat() }

    private fun whiteNoise(amplitude: Double = 0.4, samples: Int = 4096, seed: Int = 7): FloatArray {
        val rng = Random(seed)
        return FloatArray(samples) { (amplitude * (rng.nextDouble() * 2.0 - 1.0)).toFloat() }
    }

    /** A single sharp click in an otherwise quiet buffer — a crack or an impact. */
    private fun click(samples: Int = 4096, at: Int = 2048): FloatArray {
        val rng = Random(11)
        // A little floor noise so RMS is non-zero and the crest factor is finite, the way a
        // real microphone's buffer always is.
        val out = FloatArray(samples) { (0.01 * (rng.nextDouble() * 2.0 - 1.0)).toFloat() }
        out[at] = 0.95f
        out[at + 1] = -0.85f
        out[at + 2] = 0.55f
        return out
    }

    // ---------------------------------------------------------------------------- FFT

    @Test
    fun `the fft puts a pure tone in the bin its frequency belongs to`() {
        val hz = 1000.0
        val frame = AudioFeatures.mostRecentPowerOfTwo(tone(hz))
        val spectrum = AudioFeatures.powerSpectrum(frame)

        val binHz = rate.toDouble() / frame.size
        // The spectrum drops DC, so bin index i holds frequency (i + 1) * binHz.
        val peak = spectrum.indices.maxByOrNull { spectrum[it] }!!
        val peakHz = (peak + 1) * binHz

        assertTrue(
            kotlin.math.abs(peakHz - hz) <= binHz * 2,
            "peak landed at $peakHz Hz, expected within two bins of $hz Hz",
        )
    }

    @Test
    fun `the fft rejects a length that is not a power of two`() {
        assertFailsWith<IllegalArgumentException> {
            AudioFeatures.fftInPlace(DoubleArray(6), DoubleArray(6))
        }
    }

    // ------------------------------------------------------------------ scalar features

    @Test
    fun `white noise is spectrally flat and a pure tone is not`() {
        val noise = AudioFeatures.spectralFlatness(
            AudioFeatures.powerSpectrum(AudioFeatures.mostRecentPowerOfTwo(whiteNoise())),
        )
        val pure = AudioFeatures.spectralFlatness(
            AudioFeatures.powerSpectrum(AudioFeatures.mostRecentPowerOfTwo(tone(1000.0))),
        )
        assertTrue(noise > 0.2, "white noise should read flat, got $noise")
        assertTrue(pure < 0.01, "a pure tone should read peaked, got $pure")
        assertTrue(noise > pure * 10, "flatness must separate noise from tone by a wide margin")
    }

    @Test
    fun `a click has a far higher crest factor than steady noise`() {
        val clickBuf = click()
        val noiseBuf = whiteNoise()
        val clickCrest = AudioFeatures.crestFactor(clickBuf, AudioFeatures.rms(clickBuf))
        val noiseCrest = AudioFeatures.crestFactor(noiseBuf, AudioFeatures.rms(noiseBuf))

        assertTrue(clickCrest > noiseCrest * 3, "click $clickCrest vs steady noise $noiseCrest")
        assertTrue(AudioFeatures.transience(clickCrest) > 0.5, "a click should read as an onset")
        assertEquals(0.0, AudioFeatures.transience(noiseCrest), "steady noise is not an onset")
    }

    @Test
    fun `loudness is floored at silence and saturates at full scale`() {
        assertEquals(0.0, AudioFeatures.loudness(0.0))
        assertEquals(0.0, AudioFeatures.loudness(AudioFeatures.SILENCE_FLOOR_RMS))
        assertEquals(1.0, AudioFeatures.loudness(AudioFeatures.FULL_SCALE_RMS))
        assertEquals(1.0, AudioFeatures.loudness(10.0), "a clipped buffer cannot exceed 1.0")
    }

    // ----------------------------------------------------------------- the three shapes

    @Test
    fun `loud broadband noise reads as water, not as voice`() {
        val observed = AudioFeatures.analyse(whiteNoise(amplitude = 0.5), rate)

        assertTrue(observed.water > 0.2, "rushing water should register, got ${observed.water}")
        assertTrue(
            observed.water > observed.voice,
            "broadband noise must not read as voice (water ${observed.water}, voice ${observed.voice})",
        )
    }

    @Test
    fun `a loud tone in the voice band reads as voice, not as water`() {
        val observed = AudioFeatures.analyse(tone(900.0, amplitude = 0.5), rate)

        assertTrue(observed.voice > 0.2, "a voice-band tone should register, got ${observed.voice}")
        assertTrue(
            observed.voice > observed.water,
            "a tone must not read as water (voice ${observed.voice}, water ${observed.water})",
        )
    }

    @Test
    fun `a tone above the voice band is not reported as voice`() {
        // 6 kHz is well past VOICE_HIGH_HZ. This is the check that the band edges are
        // actually applied rather than every loud tone counting as a person.
        val observed = AudioFeatures.analyse(tone(6000.0, amplitude = 0.5), rate)
        assertTrue(observed.voice < 0.05, "6 kHz is not speech, got ${observed.voice}")
    }

    @Test
    fun `a sharp click reads as structural, not as water`() {
        val observed = AudioFeatures.analyse(click(), rate)

        assertTrue(observed.structural > 0.3, "an impact should register, got ${observed.structural}")
        assertTrue(
            observed.structural > observed.water,
            "a transient must not read as water (structural ${observed.structural}, water ${observed.water})",
        )
    }

    @Test
    fun `a brief click is judged on its peak, not averaged into silence`() {
        // The regression this class was first written with: structural was gated on RMS, so a
        // three-millisecond crack inside a one-second buffer scored near zero however violent
        // it was. An impact is measured by its peak; only the steady channels use RMS.
        val brief = click(samples = 4096, at = 2048)
        assertTrue(
            AudioFeatures.rms(brief) < AudioFeatures.SILENCE_FLOOR_RMS * 8,
            "the buffer really is near-silent on average — that is the point",
        )
        assertTrue(
            AudioFeatures.analyse(brief, rate).structural > 0.3,
            "a near-silent buffer containing a violent transient is still an impact",
        )
    }

    @Test
    fun `speech is not mistaken for a structural impact`() {
        // TRANSIENT_FLOOR sits above speech's crest factor on purpose: a person talking
        // belongs to the voice channel, and reporting them as a collapsing building would put
        // the wrong token in front of a responder.
        val observed = AudioFeatures.analyse(tone(700.0, amplitude = 0.5), rate)
        assertTrue(observed.structural < 0.05, "a sustained tone is not an impact, got ${observed.structural}")
    }

    // ------------------------------------------------------------------- silence & edges

    @Test
    fun `silence asserts nothing at all`() {
        val observed = AudioFeatures.analyse(FloatArray(4096), rate)
        assertEquals(AudioObservation.SILENT, observed)
        assertEquals(0.0, observed.loudestChannel)
    }

    @Test
    fun `a whisper below the noise floor is silence, not faint evidence`() {
        // The floor exists so microphone self-noise is never reported as a finding. Below it
        // every spectral ratio is computed from noise and would be arbitrary, not merely small.
        val observed = AudioFeatures.analyse(whiteNoise(amplitude = 0.001), rate)
        assertEquals(AudioObservation.SILENT, observed)
    }

    @Test
    fun `a buffer too short to transform is silence rather than a crash`() {
        assertEquals(AudioObservation.SILENT, AudioFeatures.analyse(FloatArray(0), rate))
        assertEquals(AudioObservation.SILENT, AudioFeatures.analyse(whiteNoise(samples = 64), rate))
    }

    @Test
    fun `an impossible sample rate is rejected rather than producing nonsense`() {
        assertFailsWith<IllegalArgumentException> { AudioFeatures.analyse(whiteNoise(), 0) }
    }

    @Test
    fun `every output stays inside the 0 to 1 the sensory window requires`() {
        // SensoryWindow's own require() would throw on anything outside 0..1, which on a real
        // device means a crashed sensing tick rather than a bad number. Cover the loud and
        // clipped cases that are most likely to run over.
        val signals = listOf(
            whiteNoise(amplitude = 1.0),
            tone(440.0, amplitude = 1.0),
            tone(50.0, amplitude = 1.0),
            click(),
            FloatArray(4096) { if (it % 2 == 0) 1f else -1f },
        )
        for (signal in signals) {
            val observed = AudioFeatures.analyse(signal, rate)
            // Constructing the window is the real assertion: it validates the range itself.
            SensoryWindow(
                audioWater = observed.water,
                audioVoice = observed.voice,
                audioStructural = observed.structural,
            )
        }
    }

    @Test
    fun `analysis does not modify the caller's buffer`() {
        // The bridge reuses one read buffer for the life of the recording loop, so a mutating
        // analyse() would corrupt the next window rather than fail visibly.
        val signal = whiteNoise()
        val before = signal.copyOf()
        AudioFeatures.analyse(signal, rate)
        assertTrue(signal.contentEquals(before), "analyse must not mutate its input")
    }

    // ------------------------------------------------------ what the mesh does with it

    @Test
    fun `a water reading reaches the flag byte and the wire summary`() {
        // The point of the whole channel: audio has to arrive as a triage bit a responder
        // sees, not merely as a number inside the agent.
        val observed = AudioFeatures.analyse(whiteNoise(amplitude = 0.9), rate)
        val window = SensoryWindow(
            audioWater = observed.water,
            audioVoice = observed.voice,
            audioStructural = observed.structural,
            imuPinned = 0.9,
        )
        val flags = SensoryFlags.encode(SlmFeatureVector.from(window))

        assertTrue(
            SensoryFlags.isSet(flags, SensoryFlags.AUDIO_WATER),
            "loud broadband noise should set the water bit, flags ${SensoryFlags.toHex(flags)}",
        )
        val summary = DeterministicSensoryClassifier().classify(window)
        assertTrue(
            summary?.toWireString()?.contains("AUDIO:RUSHING_WATER") == true,
            "the water token should reach the wire summary, got ${summary?.toWireString()}",
        )
    }

    @Test
    fun `the audio channels move the danger score they are weighted for`() {
        // 0.45 of MathEngine's weight mass sits on the three audio slots. Before this class
        // existed that mass was unreachable; this pins that it is now actually spendable.
        val silent = SensoryWindow(imuPinned = 1.0)
        val loud = SensoryWindow(
            audioWater = 0.9,
            audioVoice = 0.7,
            audioStructural = 0.5,
            imuPinned = 1.0,
        )
        val engine = MathEngine()

        val quiet = engine.project(SlmFeatureVector.from(silent))
        val heard = engine.project(SlmFeatureVector.from(loud))

        assertTrue(heard > quiet + 0.3, "audio evidence should move the signal ($quiet -> $heard)")
        assertTrue(
            quiet < 0.35,
            "a still, silent phone must stay below the watch threshold, got $quiet",
        )
    }
}
