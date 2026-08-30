package org.groundzero.mesh.agent

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The microphone channel of the sensory window, as pure arithmetic.
 *
 * Three of [SensoryWindow]'s channels — water, voice, structural — carry 0.45 of
 * [MathEngine]'s total weight mass and three of the five evidence bits in [SensoryFlags].
 * Until this existed nothing ever wrote to them, so a device could be submerged next to
 * someone screaming and score exactly what it scores in a silent drawer.
 *
 * ### What this is, honestly
 *
 * A heuristic spectral classifier. It is not a trained model and not the encoder trunk the
 * L1 design calls for — it reads three shapes out of one short buffer:
 *
 * - **water** — loud, *noise-like* (high spectral flatness), energy spread well above the
 *   voice band, and sustained rather than struck. Rushing water is broadband noise.
 * - **voice** — loud, *tonal* (low flatness), energy concentrated between [VOICE_LOW_HZ]
 *   and [VOICE_HIGH_HZ]. A scream is the loud end of this shape, not a separate one.
 * - **structural** — a sharp transient: high crest factor over a broadband spectrum. A
 *   crack or an impact is an onset, not a tone.
 *
 * It cannot tell rushing water from heavy rain or applause, or a scream from a shout. It is
 * *evidence*, which is all any channel here claims to be — [SensorySummary] fuses by max
 * precisely because every channel is near-blind somewhere. Nothing downstream treats one
 * channel as identification.
 *
 * Raw audio never leaves this function: samples in, three confidences out, and the buffer is
 * the caller's and is never retained. That is the rule [SensoryChannel] states for the wire,
 * applied one layer earlier.
 *
 * Pure JVM on purpose — no Android types — so it is testable against synthetic signals with
 * no device, which is how `AudioFeaturesTest` drives every branch below.
 */
object AudioFeatures {

    /**
     * Analyse one buffer of mono PCM in `-1..1`.
     *
     * Returns [AudioObservation.SILENT] for a buffer that is empty, too short, or below the
     * noise floor. No samples means no claim, which is not the same as a claim of nothing.
     */
    fun analyse(samples: FloatArray, sampleRateHz: Int): AudioObservation {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0, got $sampleRateHz" }
        if (samples.size < MIN_SAMPLES) return AudioObservation.SILENT

        val rms = rms(samples)
        val loudness = loudness(rms)
        // Below the floor the spectrum is dominated by the microphone's own noise, and every
        // ratio computed from it is meaningless rather than merely small.
        if (loudness <= 0.0) return AudioObservation.SILENT

        val frame = mostRecentPowerOfTwo(samples)
        val spectrum = powerSpectrum(frame)
        if (spectrum.isEmpty()) return AudioObservation.SILENT

        val flatness = spectralFlatness(spectrum)
        val crest = crestFactor(samples, rms)
        val binHz = sampleRateHz.toDouble() / frame.size

        val total = spectrum.sum().coerceAtLeast(EPSILON)
        val voiceFraction = bandEnergy(spectrum, binHz, VOICE_LOW_HZ, VOICE_HIGH_HZ) / total
        val highFraction = bandEnergy(spectrum, binHz, HIGH_LOW_HZ, HIGH_HIGH_HZ) / total

        val transience = transience(crest)
        // Sustained, not struck. A transient buffer is a crack, and reading it as water would
        // put "rising water" — the most urgent token this system has — on a slammed door.
        val sustained = 1.0 - transience

        val water = (loudness * flatness * highFraction.gained(WATER_BAND_GAIN) * sustained)
            .coerceIn(0.0, 1.0)
        val voice = (loudness * (1.0 - flatness) * voiceFraction.gained(VOICE_BAND_GAIN))
            .coerceIn(0.0, 1.0)
        // Peak loudness here, not RMS — the one place the two must differ. A crack is three
        // milliseconds inside a one-second buffer, so its RMS is near silence however violent
        // it was; gating it on RMS would double-penalise it for being brief and quietly lose
        // every impact. [transience] is what keeps steady noise out of this channel, so the
        // peak can be trusted to mean what it says.
        val structural = (loudness(peak(samples)) * transience * flatness).coerceIn(0.0, 1.0)

        return AudioObservation(
            water = water,
            voice = voice,
            structural = structural,
            rms = rms,
            flatness = flatness,
            crestFactor = crest,
        )
    }

    // ------------------------------------------------------------------ scalar features

    internal fun rms(samples: FloatArray): Double {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / samples.size)
    }

    /**
     * `0..1` loudness, linear between [SILENCE_FLOOR_RMS] and [FULL_SCALE_RMS].
     *
     * Deliberately not decibels: every consumer downstream wants a `0..1` confidence, and a
     * log scale here would only have to be undone one call later.
     */
    internal fun loudness(rms: Double): Double =
        ((rms - SILENCE_FLOOR_RMS) / (FULL_SCALE_RMS - SILENCE_FLOOR_RMS)).coerceIn(0.0, 1.0)

    /** Largest absolute sample in the buffer. What a transient is actually measured by. */
    internal fun peak(samples: FloatArray): Double {
        var peak = 0.0
        for (s in samples) peak = maxOf(peak, abs(s.toDouble()))
        return peak
    }

    /**
     * Peak-to-RMS. A sine sits at 1.41, uniform noise near 1.7, speech around 3–5, and a lone
     * click far above all of them — which is the whole reason this discriminates transients.
     */
    internal fun crestFactor(samples: FloatArray, rms: Double): Double {
        if (rms <= EPSILON) return 0.0
        return peak(samples) / rms
    }

    /** `0..1`: how much this buffer looks like an onset rather than a steady sound. */
    internal fun transience(crestFactor: Double): Double =
        ((crestFactor - TRANSIENT_FLOOR) / TRANSIENT_SPAN).coerceIn(0.0, 1.0)

    /**
     * Wiener entropy: geometric mean over arithmetic mean of the power spectrum.
     *
     * 1.0 is white noise, near 0 is a pure tone. Computed in the log domain because the
     * product of a few thousand small powers underflows a `Double` long before it finishes.
     */
    internal fun spectralFlatness(spectrum: DoubleArray): Double {
        if (spectrum.isEmpty()) return 0.0
        var logSum = 0.0
        var sum = 0.0
        for (p in spectrum) {
            val v = p + EPSILON
            logSum += ln(v)
            sum += v
        }
        val geometric = exp(logSum / spectrum.size)
        val arithmetic = sum / spectrum.size
        return if (arithmetic <= EPSILON) 0.0 else (geometric / arithmetic).coerceIn(0.0, 1.0)
    }

    /** Summed power between two frequencies. Bins past the end of the spectrum are absent. */
    internal fun bandEnergy(
        spectrum: DoubleArray,
        binHz: Double,
        lowHz: Double,
        highHz: Double,
    ): Double {
        // The spectrum starts at bin 1 (DC dropped), so a frequency's index is one lower.
        val from = ((lowHz / binHz).toInt() - 1).coerceIn(0, spectrum.size)
        val to = min(spectrum.size, (highHz / binHz).toInt())
        var sum = 0.0
        for (i in from until to) sum += spectrum[i]
        return sum
    }

    // ---------------------------------------------------------------------------- FFT

    /**
     * The most recent power-of-two run of samples, Hann-windowed.
     *
     * Most recent rather than the whole buffer: a radix-2 FFT needs a power of two, the tail
     * is the freshest evidence, and capping at [MAX_FFT] keeps one analysis inside a few
     * milliseconds on a phone that may be at 4% battery.
     */
    internal fun mostRecentPowerOfTwo(samples: FloatArray): DoubleArray {
        var n = 1
        while (n * 2 <= samples.size && n * 2 <= MAX_FFT) n *= 2
        val start = samples.size - n
        val out = DoubleArray(n)
        val denominator = (n - 1).coerceAtLeast(1)
        for (i in 0 until n) {
            // Hann. Without it the discontinuity at the buffer edges leaks broadband energy
            // into every bin, which reads as high flatness — that is, as water.
            val w = 0.5 * (1.0 - cos(2.0 * Math.PI * i / denominator))
            out[i] = samples[start + i].toDouble() * w
        }
        return out
    }

    /** Magnitude-squared per bin, DC excluded, up to Nyquist. */
    internal fun powerSpectrum(frame: DoubleArray): DoubleArray {
        val n = frame.size
        if (n < 4) return DoubleArray(0)
        val re = frame.copyOf()
        val im = DoubleArray(n)
        fftInPlace(re, im)
        // Bin 0 is DC — a microphone's own offset, never evidence — so the spectrum starts at
        // bin 1 and every ratio above is over AC energy only.
        val bins = n / 2
        val out = DoubleArray(bins - 1)
        for (i in 1 until bins) out[i - 1] = re[i] * re[i] + im[i] * im[i]
        return out
    }

    /**
     * Iterative radix-2 Cooley–Tukey, in place.
     *
     * [re] and [im] must share a power-of-two length. Hand-rolled rather than pulled from a
     * DSP library because `core` carries no dependencies at all — that is what lets it stay
     * a plain JVM module the Android side merely borrows.
     */
    internal fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size) { "re and im must be the same length" }
        require(n > 0 && (n and (n - 1)) == 0) { "FFT length must be a power of two, got $n" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            val half = len / 2
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                    val vIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextRe = curRe * stepRe - curIm * stepIm
                    curIm = curRe * stepIm + curIm * stepRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** A band fraction is never near 1.0 in practice; this lifts the useful range onto `0..1`. */
    private fun Double.gained(gain: Double) = (this * gain).coerceIn(0.0, 1.0)

    // ---------------------------------------------------------------------- constants

    /** Below this many samples there is not enough signal to transform. */
    const val MIN_SAMPLES = 256

    /** Cap on one FFT. 4096 at 16 kHz is a quarter-second — plenty of shape, little cost. */
    const val MAX_FFT = 4096

    /** RMS below this is microphone self-noise, not a sound. */
    const val SILENCE_FLOOR_RMS = 0.004

    /** RMS at which loudness saturates. Shouting into a handset clears this comfortably. */
    const val FULL_SCALE_RMS = 0.25

    /** The telephony band — where human voice energy actually lives. */
    const val VOICE_LOW_HZ = 300.0
    const val VOICE_HIGH_HZ = 3400.0

    /** Above the voice band. Rushing water puts real energy here; speech mostly does not. */
    const val HIGH_LOW_HZ = 2000.0
    const val HIGH_HIGH_HZ = 8000.0

    /**
     * Crest factor below which nothing counts as an onset.
     *
     * Set above speech (3–5) rather than above noise, because the channel must not fire on a
     * person talking — the voice channel is where that belongs.
     */
    const val TRANSIENT_FLOOR = 4.0

    /** Crest-factor span from "steady" to "unmistakably a click". */
    const val TRANSIENT_SPAN = 8.0

    /** Band fractions are ratios of total AC energy; these lift the useful range to `0..1`. */
    const val WATER_BAND_GAIN = 2.0
    const val VOICE_BAND_GAIN = 1.6

    private const val EPSILON = 1e-12
}

/**
 * What one buffer of audio looked like.
 *
 * The three confidences map straight onto [SensoryWindow]'s audio channels; [rms],
 * [flatness] and [crestFactor] are the "why" behind them, kept so a debug view can show what
 * the microphone actually heard rather than only what it concluded.
 */
data class AudioObservation(
    /** Broadband, noise-like and sustained — consistent with rushing water. */
    val water: Double,
    /** Tonal energy in the voice band — speech, crying, screaming. */
    val voice: Double,
    /** A sharp broadband onset — an impact or a structural crack. */
    val structural: Double,
    val rms: Double,
    val flatness: Double,
    val crestFactor: Double,
) {
    init {
        listOf(water, voice, structural).forEach {
            require(it in 0.0..1.0) { "audio confidences must be 0..1, got $it" }
        }
    }

    /** The strongest of the three — what [SensorySummary]'s max fusion would select. */
    val loudestChannel: Double get() = maxOf(water, voice, structural)

    companion object {
        /** No samples, or nothing above the noise floor. Asserts nothing about the world. */
        val SILENT = AudioObservation(0.0, 0.0, 0.0, rms = 0.0, flatness = 0.0, crestFactor = 0.0)
    }
}
