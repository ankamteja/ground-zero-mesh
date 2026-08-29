package org.groundzero.mesh.agent

/**
 * The dense feature vector an on-device model is allowed to produce: 16 floats in `0..1`,
 * and nothing else.
 *
 * The restriction is the design. Autoregressive text generation on a phone at 4% battery is
 * slow, unbounded in time, and produces output no deterministic layer can check. A fixed
 * 16-float vector is bounded in time and memory, is trivially serialisable, and — the part
 * that matters — feeds a *linear projection* whose contribution to the score can be read off
 * by a human. A responder can be told which feature moved the number. That cannot be said of
 * a sentence a model wrote.
 *
 * Slots are named below. Unused slots stay zero and weigh nothing; they exist so a later
 * model can fill them without a wire-format change.
 */
class SlmFeatureVector(values: FloatArray) {

    val values: FloatArray = values.copyOf()

    init {
        require(values.size == LENGTH) { "v_SLM must hold $LENGTH floats, got ${values.size}" }
        require(values.all { it in 0f..1f }) { "every v_SLM element must be 0..1" }
    }

    operator fun get(index: Int): Float = values[index]

    fun toList(): List<Float> = values.toList()

    override fun equals(other: Any?): Boolean =
        this === other || (other is SlmFeatureVector && values.contentEquals(other.values))

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String = values.joinToString(",", "v_SLM[", "]") { "%.2f".format(it) }

    companion object {
        const val LENGTH = 16

        const val AUDIO_WATER = 0
        const val AUDIO_VOICE = 1
        const val AUDIO_STRUCTURAL = 2
        const val AUDIO_SILENCE = 3
        const val IMU_PINNED = 4
        const val IMU_SHOCK = 5
        const val IMU_MOTION = 6
        const val IMU_STILL = 7
        const val LIGHT_ENCLOSED = 8
        const val LIGHT_FLICKER = 9
        const val EVENT_WEIGHT = 10
        const val EVENT_PERSISTENCE = 11
        // 12..15 reserved.

        val ZERO = SlmFeatureVector(FloatArray(LENGTH))

        /** Build a vector from `slot to value` pairs; everything unnamed stays zero. */
        fun of(vararg slots: Pair<Int, Double>): SlmFeatureVector {
            val out = FloatArray(LENGTH)
            for ((slot, value) in slots) out[slot] = value.toFloat()
            return SlmFeatureVector(out)
        }

        /**
         * The deterministic stand-in for a real model: fill the slots a
         * [SensoryWindow] already describes.
         *
         * Same contract as [DeterministicSensoryClassifier] — the pipeline is real from day
         * one, and a quantised model that emits the same 16 floats drops in with nothing
         * downstream changing.
         */
        fun from(window: SensoryWindow): SlmFeatureVector = of(
            AUDIO_WATER to window.audioWater,
            AUDIO_VOICE to window.audioVoice,
            AUDIO_STRUCTURAL to window.audioStructural,
            IMU_PINNED to window.imuPinned,
            IMU_SHOCK to window.imuShock,
            LIGHT_ENCLOSED to (1.0 - window.ambientLight),
            EVENT_WEIGHT to (window.event?.dangerWeight ?: 0.0),
        )
    }
}

/**
 * The 8-bit sensory flag byte that rides on every envelope.
 *
 * One byte summarises what the device believed it was sensing, which is what a responder
 * board can actually act on — and it costs a single byte on a 233-byte LoRa frame, where the
 * full feature vector costs seventeen. The vector is for inspection; the flags are for
 * triage.
 *
 * Bit layout (bit 0 is least significant). The plan's illustrative value `0x8F` is
 * water + screaming + pinned + impact, with the reserved bit set.
 */
object SensoryFlags {

    const val AUDIO_WATER: Int = 1 shl 0
    const val AUDIO_SCREAMING: Int = 1 shl 1
    const val IMU_PINNED: Int = 1 shl 2
    const val IMU_IMPACT: Int = 1 shl 3
    const val LOW_LIGHT: Int = 1 shl 4
    const val MANUAL_SOS: Int = 1 shl 5
    const val STAGE_2_ENRICHED: Int = 1 shl 6
    const val RESERVED: Int = 1 shl 7

    const val NONE: Byte = 0

    /** Above this a feature slot counts as asserted. Matches the classifier's report threshold. */
    const val ASSERT_THRESHOLD: Double = 0.35

    fun encode(
        vector: SlmFeatureVector,
        manualSos: Boolean = false,
        enriched: Boolean = false,
        threshold: Double = ASSERT_THRESHOLD,
    ): Byte {
        var bits = 0
        fun set(slot: Int, flag: Int) {
            if (vector[slot] >= threshold) bits = bits or flag
        }
        set(SlmFeatureVector.AUDIO_WATER, AUDIO_WATER)
        set(SlmFeatureVector.AUDIO_VOICE, AUDIO_SCREAMING)
        set(SlmFeatureVector.IMU_PINNED, IMU_PINNED)
        set(SlmFeatureVector.IMU_SHOCK, IMU_IMPACT)
        set(SlmFeatureVector.LIGHT_ENCLOSED, LOW_LIGHT)
        if (manualSos) bits = bits or MANUAL_SOS
        if (enriched) bits = bits or STAGE_2_ENRICHED
        return bits.toByte()
    }

    fun isSet(flags: Byte, flag: Int): Boolean = (flags.toInt() and flag) != 0

    /** `0x8f` — the form the dashboard inspector and the CLI simulation print. */
    fun toHex(flags: Byte): String = "0x%02x".format(flags.toInt() and 0xFF)

    /** Human-readable decode, strongest evidence first. Empty list means nothing asserted. */
    fun describe(flags: Byte): List<String> = buildList {
        if (isSet(flags, MANUAL_SOS)) add("manual SOS")
        if (isSet(flags, AUDIO_WATER)) add("rushing water")
        if (isSet(flags, AUDIO_SCREAMING)) add("screaming")
        if (isSet(flags, IMU_IMPACT)) add("impact")
        if (isSet(flags, IMU_PINNED)) add("pinned")
        if (isSet(flags, LOW_LIGHT)) add("enclosed / dark")
        if (isSet(flags, STAGE_2_ENRICHED)) add("enriched")
    }
}

/**
 * Stage 2 of the cascade: turn a feature vector plus one accelerometer magnitude into a
 * single risk signal, deterministically.
 *
 * ```
 * Signal_t = W · v_SLM + w_IMU · a_mag
 * ```
 *
 * A linear projection and nothing more. Every term is inspectable, so [explain] can name the
 * feature that moved the number — which is the whole reason the model is confined to
 * emitting a vector.
 *
 * ### Where the EMA is
 *
 * The specification writes the smoothing step next to the projection:
 * `DangerScore_t = DangerScore_{t-1}·(1−α) + Signal_t·α`. That EMA already exists, once, in
 * [DangerScore], which also owns the two thresholds and the human-readable explanation. It
 * is implemented there and *not* duplicated here: two EMAs over the same signal would double
 * the smoothing and halve the responsiveness the α was chosen for. This class produces
 * `Signal_t`; [DangerScore] with `alpha = ` [DangerScore.DEFAULT_ALPHA] consumes it.
 */
class MathEngine(
    val weights: DoubleArray = DEFAULT_WEIGHTS,
    val imuWeight: Double = DEFAULT_IMU_WEIGHT,
) {
    init {
        require(weights.size == SlmFeatureVector.LENGTH) {
            "W must hold ${SlmFeatureVector.LENGTH} weights, got ${weights.size}"
        }
        require(weights.all { it >= 0.0 }) { "a negative weight would let evidence lower risk" }
        require(imuWeight >= 0.0) { "w_IMU must be >= 0" }
    }

    /**
     * `Signal_t`, clamped to `0..1`.
     *
     * The clamp is not a formality: the weights sum to 1.0 and `w_IMU` adds another 0.25, so
     * a device that is simultaneously drowning, pinned, in the dark and being shaken can
     * project above 1.0. Saturating there is correct — past "maximum danger" there is
     * nothing further to say, and letting the raw number run would silently re-scale every
     * threshold downstream.
     *
     * [accelMagnitude] is normalised free-fall-to-violent-impact in `0..1`, not m/s².
     */
    fun project(vector: SlmFeatureVector, accelMagnitude: Double = 0.0): Double {
        require(accelMagnitude in 0.0..1.0) { "a_mag must be normalised 0..1, got $accelMagnitude" }
        var sum = 0.0
        for (i in 0 until SlmFeatureVector.LENGTH) sum += weights[i] * vector[i]
        return (sum + imuWeight * accelMagnitude).coerceIn(0.0, 1.0)
    }

    /** Which slot contributed most, and how much — the "why" behind a projected signal. */
    fun explain(vector: SlmFeatureVector, accelMagnitude: Double = 0.0): String {
        val contributions = (0 until SlmFeatureVector.LENGTH)
            .map { it to weights[it] * vector[it] }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
        val imu = imuWeight * accelMagnitude
        val top = contributions.firstOrNull()
        return when {
            top == null && imu <= 0.0 -> "no feature above zero"
            top == null || imu > top.second -> "IMU magnitude contributed %.2f".format(imu)
            else -> "%s contributed %.2f".format(slotName(top.first), top.second)
        }
    }

    private fun slotName(slot: Int): String = when (slot) {
        SlmFeatureVector.AUDIO_WATER -> "audio:water"
        SlmFeatureVector.AUDIO_VOICE -> "audio:voice"
        SlmFeatureVector.AUDIO_STRUCTURAL -> "audio:structural"
        SlmFeatureVector.AUDIO_SILENCE -> "audio:silence"
        SlmFeatureVector.IMU_PINNED -> "imu:pinned"
        SlmFeatureVector.IMU_SHOCK -> "imu:shock"
        SlmFeatureVector.IMU_MOTION -> "imu:motion"
        SlmFeatureVector.IMU_STILL -> "imu:still"
        SlmFeatureVector.LIGHT_ENCLOSED -> "light:enclosed"
        SlmFeatureVector.LIGHT_FLICKER -> "light:flicker"
        SlmFeatureVector.EVENT_WEIGHT -> "event:weight"
        SlmFeatureVector.EVENT_PERSISTENCE -> "event:persistence"
        else -> "slot:$slot"
    }

    companion object {
        /**
         * Weights sum to exactly 1.0, so a vector saturated on every channel projects to 1.0
         * before the IMU term. They are ordered by how specific the evidence is to a person
         * in danger, not by how loud it is: rushing water and a pinned device are strong,
         * a voice is weaker (a voice is also a rescuer's voice), enclosure is weakest because
         * a phone in a pocket is dark too.
         */
        val DEFAULT_WEIGHTS: DoubleArray = DoubleArray(SlmFeatureVector.LENGTH).apply {
            this[SlmFeatureVector.AUDIO_WATER] = 0.20
            this[SlmFeatureVector.AUDIO_VOICE] = 0.10
            this[SlmFeatureVector.AUDIO_STRUCTURAL] = 0.15
            this[SlmFeatureVector.IMU_PINNED] = 0.20
            this[SlmFeatureVector.IMU_SHOCK] = 0.15
            this[SlmFeatureVector.IMU_STILL] = 0.05
            this[SlmFeatureVector.LIGHT_ENCLOSED] = 0.05
            this[SlmFeatureVector.EVENT_WEIGHT] = 0.10
        }

        /**
         * The IMU term is added outside the vector because it is the one channel that stays
         * honest when every other sensor is blind — a phone buried in rubble hears nothing
         * and sees nothing, and its accelerometer is the only witness left.
         */
        const val DEFAULT_IMU_WEIGHT: Double = 0.25
    }
}
