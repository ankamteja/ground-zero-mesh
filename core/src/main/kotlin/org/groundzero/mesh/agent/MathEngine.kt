package org.groundzero.mesh.agent

import java.util.Locale

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

    /**
     * A private copy, so a caller mutating the array it passed in cannot change a vector
     * that has already been validated, encoded, or put on the wire.
     */
    private val slots: FloatArray

    init {
        require(values.size == LENGTH) { "v_SLM must hold $LENGTH floats, got ${values.size}" }
        // NaN and the infinities are called out separately from the range check. All three
        // fail `in 0f..1f` anyway, but "element 4 is NaN" tells whoever is reading a crash
        // report that a sensor produced a division by zero, while "must be 0..1" sends them
        // looking for a scaling bug that isn't there.
        values.forEachIndexed { i, v ->
            require(v.isFinite()) { "v_SLM[$i] (${MathEngine.slotName(i)}) is not finite: $v" }
            require(v in 0f..1f) { "v_SLM[$i] (${MathEngine.slotName(i)}) must be 0..1, got $v" }
        }
        // -0.0f passes the range check and then compares unequal to 0.0f under
        // FloatArray.contentEquals, so two numerically identical vectors could differ in
        // equals(), hashCode(), and the dedup key built from them. Normalising here means
        // that cannot happen further downstream.
        slots = FloatArray(LENGTH) { if (values[it] == 0f) 0f else values[it] }
    }

    val values: FloatArray get() = slots.copyOf()

    operator fun get(index: Int): Float {
        require(index in 0 until LENGTH) { "v_SLM slot must be 0..${LENGTH - 1}, got $index" }
        return slots[index]
    }

    fun toList(): List<Float> = slots.toList()

    override fun equals(other: Any?): Boolean =
        this === other || (other is SlmFeatureVector && slots.contentEquals(other.slots))

    override fun hashCode(): Int = slots.contentHashCode()

    /** [Locale.ROOT] so a phone set to a comma-decimal locale prints `0.25`, not `0,25`. */
    override fun toString(): String =
        slots.joinToString(",", "v_SLM[", "]") { String.format(Locale.ROOT, "%.2f", it) }

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
            for ((slot, value) in slots) {
                // Without this an off-by-one slot constant throws
                // ArrayIndexOutOfBoundsException with no clue which caller was wrong.
                require(slot in 0 until LENGTH) { "v_SLM slot must be 0..${LENGTH - 1}, got $slot" }
                out[slot] = value.toFloat()
            }
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
 * water + screaming + pinned + impact + structural crack.
 *
 * ### Bit 7 was reserved and is now structural audio
 *
 * `AUDIO_STRUCTURAL` is the third-heaviest channel in the projection (0.15 — above voice),
 * so a structural crack has always moved the danger score. It had no bit here, which meant
 * it moved the score and then vanished: [describe] never named it, so it never reached the
 * responder's board as evidence. The full `v_SLM` carries it, but the vector is optional and
 * rides only on the Stage 3 enriched broadcast — on a LoRa hop that drops it, a collapse
 * signature was reaching the perimeter as an unexplained number.
 *
 * Allocating the reserved bit costs nothing on the wire (the byte was already sent) and is
 * forward-compatible: a receiver built before this change ignores bit 7 exactly as it did
 * when the bit was reserved.
 */
object SensoryFlags {

    const val AUDIO_WATER: Int = 1 shl 0
    const val AUDIO_SCREAMING: Int = 1 shl 1
    const val IMU_PINNED: Int = 1 shl 2
    const val IMU_IMPACT: Int = 1 shl 3
    const val LOW_LIGHT: Int = 1 shl 4
    const val MANUAL_SOS: Int = 1 shl 5
    const val STAGE_2_ENRICHED: Int = 1 shl 6

    /** Was `RESERVED`. See the bit-7 note on this object. */
    const val AUDIO_STRUCTURAL: Int = 1 shl 7

    const val NONE: Byte = 0

    /** Above this a feature slot counts as asserted. Matches the classifier's report threshold. */
    const val ASSERT_THRESHOLD: Double = 0.35

    fun encode(
        vector: SlmFeatureVector,
        manualSos: Boolean = false,
        enriched: Boolean = false,
        threshold: Double = ASSERT_THRESHOLD,
    ): Byte {
        require(threshold.isFinite() && threshold in 0.0..1.0) {
            "assert threshold must be a finite 0..1, got $threshold"
        }
        var bits = 0
        fun set(slot: Int, flag: Int) {
            // `> 0f` as well as the threshold: at threshold 0.0 every slot in an all-zero
            // vector would otherwise satisfy `>= threshold` and the byte would claim water,
            // screaming, pinned, impact and darkness on a phone that sensed nothing at all.
            // Absence of evidence must never encode as evidence.
            if (vector[slot] > 0f && vector[slot] >= threshold) bits = bits or flag
        }
        set(SlmFeatureVector.AUDIO_WATER, AUDIO_WATER)
        set(SlmFeatureVector.AUDIO_VOICE, AUDIO_SCREAMING)
        set(SlmFeatureVector.AUDIO_STRUCTURAL, AUDIO_STRUCTURAL)
        set(SlmFeatureVector.IMU_PINNED, IMU_PINNED)
        set(SlmFeatureVector.IMU_SHOCK, IMU_IMPACT)
        set(SlmFeatureVector.LIGHT_ENCLOSED, LOW_LIGHT)
        if (manualSos) bits = bits or MANUAL_SOS
        if (enriched) bits = bits or STAGE_2_ENRICHED
        return bits.toByte()
    }

    fun isSet(flags: Byte, flag: Int): Boolean = (flags.toInt() and flag) != 0

    /** `0x8f` — the form the dashboard inspector and the CLI simulation print. */
    fun toHex(flags: Byte): String =
        String.format(Locale.ROOT, "0x%02x", flags.toInt() and 0xFF)

    /** Human-readable decode, strongest evidence first. Empty list means nothing asserted. */
    fun describe(flags: Byte): List<String> = buildList {
        if (isSet(flags, MANUAL_SOS)) add("manual SOS")
        if (isSet(flags, AUDIO_WATER)) add("rushing water")
        if (isSet(flags, AUDIO_SCREAMING)) add("screaming")
        if (isSet(flags, AUDIO_STRUCTURAL)) add("structural crack")
        if (isSet(flags, IMU_IMPACT)) add("impact")
        if (isSet(flags, IMU_PINNED)) add("pinned")
        if (isSet(flags, LOW_LIGHT)) add("enclosed / dark")
        if (isSet(flags, STAGE_2_ENRICHED)) add("enriched")
    }

    /**
     * Bit position (0 = LSB) -> label, same wording [describe] uses. The one shared source
     * for anything that needs to label all 8 bits regardless of whether they're asserted —
     * a bit-grid visualiser, say — so it cannot drift from [describe] the way a second
     * hand-rolled copy would.
     */
    val BIT_NAMES: List<String> = listOf(
        "rushing water", // bit 0 AUDIO_WATER
        "screaming", // bit 1 AUDIO_SCREAMING
        "pinned", // bit 2 IMU_PINNED
        "impact", // bit 3 IMU_IMPACT
        "enclosed / dark", // bit 4 LOW_LIGHT
        "manual SOS", // bit 5 MANUAL_SOS
        "enriched", // bit 6 STAGE_2_ENRICHED
        "structural crack", // bit 7 AUDIO_STRUCTURAL
    )
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
    weights: DoubleArray = defaultWeights(),
    val imuWeight: Double = DEFAULT_IMU_WEIGHT,
) {
    /**
     * The engine's own copy of W.
     *
     * [DEFAULT_WEIGHTS] used to be handed out by reference and stored by reference, so
     * `MathEngine().weights[0] = 5.0` permanently re-tuned every engine constructed
     * afterwards in the same process — including ones in other tests, and on a phone, for
     * the remaining life of the service. Copying in and copying out closes that.
     */
    private val w: DoubleArray = weights.copyOf()

    /** A copy: mutating what this returns cannot re-tune a live engine. */
    val weights: DoubleArray get() = w.copyOf()

    init {
        require(w.size == SlmFeatureVector.LENGTH) {
            "W must hold ${SlmFeatureVector.LENGTH} weights, got ${w.size}"
        }
        w.forEachIndexed { i, weight ->
            // NaN already fails `>= 0.0`, but it fails it while *reading* as a negative
            // weight, which is a misleading thing to tell someone. POSITIVE_INFINITY passes
            // `>= 0.0` outright and then saturates every projection to 1.0 forever — the
            // engine keeps running and every score is maximal, which is worse than a crash.
            require(weight.isFinite()) { "W[$i] (${slotName(i)}) is not finite: $weight" }
            require(weight >= 0.0) { "W[$i] (${slotName(i)}) is negative: a negative weight would let evidence lower risk" }
        }
        require(imuWeight.isFinite() && imuWeight >= 0.0) { "w_IMU must be finite and >= 0, got $imuWeight" }

        // The saturation contract, now actually enforced.
        //
        // The class doc says a fully saturated vector projects to exactly 1.0 before the IMU
        // term, and every threshold downstream — DangerScore's watch at 0.35 and alarm at
        // 0.70, the first-hand floor at 0.45 — is calibrated against that. Weights summing to
        // 3.0 do not fail: they quietly pin ordinary readings at 1.0, so every node alarms,
        // every incident ranks maximal, and the board stops discriminating at exactly the
        // moment it matters. Nothing downstream can detect that, so it is caught here.
        val sum = w.sum()
        require(sum <= 1.0 + WEIGHT_SUM_TOLERANCE) {
            "W sums to $sum; above 1.0 the projection saturates before the IMU term and every " +
                "threshold downstream loses its meaning"
        }
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
        // Deliberately strict, and staying strict. An out-of-range a_mag is the units
        // mistake SensorNormalisation's doc calls the one that "silently makes the whole
        // pipeline wrong" — an m/s² reading reaching an input expecting 0..1 saturates every
        // threshold forever. Clamping here would hide exactly that, and a saturated score
        // that never recovers is worse on a responder's board than a caller that fails
        // loudly. The app-side normalisers already clamp (SensorNormalisation.shock), so a
        // correct caller never reaches this.
        require(accelMagnitude.isFinite()) { "a_mag is not finite: $accelMagnitude" }
        require(accelMagnitude in 0.0..1.0) {
            "a_mag must be normalised 0..1, got $accelMagnitude — is this a raw m/s² reading?"
        }
        var sum = 0.0
        for (i in 0 until SlmFeatureVector.LENGTH) sum += w[i] * vector[i]
        return (sum + imuWeight * accelMagnitude).coerceIn(0.0, 1.0)
    }

    /** Which slot contributed most, and how much — the "why" behind a projected signal. */
    fun explain(vector: SlmFeatureVector, accelMagnitude: Double = 0.0): String {
        // Same contract as project(), so an explanation can never describe an input the
        // score itself would have refused.
        require(accelMagnitude.isFinite()) { "a_mag is not finite: $accelMagnitude" }
        require(accelMagnitude in 0.0..1.0) {
            "a_mag must be normalised 0..1, got $accelMagnitude — is this a raw m/s² reading?"
        }
        // Ties break toward the lower slot index: sortedByDescending is stable, and the
        // source list is in slot order. That makes the "why" line reproducible for identical
        // input, which matters when a responder asks why two incidents read the same.
        val contributions = (0 until SlmFeatureVector.LENGTH)
            .map { it to w[it] * vector[it] }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
        val imu = imuWeight * accelMagnitude
        val top = contributions.firstOrNull()
        return when {
            top == null && imu <= 0.0 -> "no feature above zero"
            top == null || imu > top.second -> fmt("IMU magnitude contributed %.2f", imu)
            else -> fmt("%s contributed %.2f", slotName(top.first), top.second)
        }
    }

    /** [Locale.ROOT]: a phone in a comma-decimal locale must not render `contributed 0,20`. */
    private fun fmt(pattern: String, vararg args: Any) = String.format(Locale.ROOT, pattern, *args)

    companion object {
        /** Which feature a `v_SLM` slot index is — the one shared source for anything that
         *  labels the vector (this class's [explain], a dashboard's slot list). */
        fun slotName(slot: Int): String = when (slot) {
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

        /** [slotName] for every slot index in [SlmFeatureVector.LENGTH] order. */
        fun slotNames(): List<String> = (0 until SlmFeatureVector.LENGTH).map(::slotName)

        /**
         * Weights sum to exactly 1.0, so a vector saturated on every channel projects to 1.0
         * before the IMU term. They are ordered by how specific the evidence is to a person
         * in danger, not by how loud it is: rushing water and a pinned device are strong,
         * a voice is weaker (a voice is also a rescuer's voice), enclosure is weakest because
         * a phone in a pocket is dark too.
         */
        /**
         * How far the weight sum may exceed 1.0 and still be accepted.
         *
         * The defaults below are written to sum to 1.0 and sum to 1.0000000000000002 in
         * binary floating point — the class doc's "exactly 1.0" is a statement about intent,
         * not about IEEE 754. An exact check would reject the engine's own defaults.
         */
        const val WEIGHT_SUM_TOLERANCE: Double = 1e-9

        /** A fresh copy of W each call, so the defaults cannot be mutated in place. */
        fun defaultWeights(): DoubleArray = DEFAULT_WEIGHTS.copyOf()

        private val DEFAULT_WEIGHTS: DoubleArray = DoubleArray(SlmFeatureVector.LENGTH).apply {
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
