package org.groundzero.mesh.agent

import org.groundzero.mesh.propagation.Envelope

/**
 * One channel's reading during the post-SOS sensory window, normalised to `0..1`.
 *
 * Raw audio, images and video never appear here and never cross the wire. A channel
 * reduces to a confidence and a token before it leaves the device — that rule is what
 * keeps the radio budget survivable, and it is also the reason none of this is a privacy
 * problem.
 */
data class SensoryChannel(
    val name: String,
    val confidence: Double,
    val token: String,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be 0..1, got " + confidence }
        require(name.isNotBlank()) { "a channel needs a name" }
    }
}

/**
 * What the sensory window observed, after fusion.
 *
 * [fusedConfidence] is the **maximum** across channels, never the sum or the mean. This is
 * a measured finding from the reference risk-manager, not a stylistic choice: each channel
 * is near-blind exactly where another is strong, so summing lets two channels that saw
 * nothing outvote the one channel that saw something. A phone pinned face-down in a dark
 * basement has a blind camera and a deafened microphone; its IMU is the only witness, and
 * averaging would silence it.
 */
data class SensorySummary(
    val channels: List<SensoryChannel>,
    val event: SensoryEvent?,
) {
    val fusedConfidence: Double =
        channels.maxOfOrNull { it.confidence } ?: 0.0

    /** The channel that actually carried the finding — the one the fusion selected. */
    val decidingChannel: SensoryChannel? = channels.maxByOrNull { it.confidence }

    /**
     * The compact wire form, capped at [Envelope.MAX_SLM_SUMMARY_BYTES].
     *
     * Tokens are emitted strongest-confidence first and the string is truncated at a token
     * boundary, so a summary that does not fit loses its weakest evidence rather than
     * arriving corrupt. Returns null when nothing was observed, which keeps the field
     * absent from the envelope instead of present and empty.
     */
    fun toWireString(limit: Int = Envelope.MAX_SLM_SUMMARY_BYTES): String? {
        if (channels.isEmpty()) return null
        val ordered = channels.sortedByDescending { it.confidence }
        val out = StringBuilder()
        for (channel in ordered) {
            val piece = (if (out.isEmpty()) "" else "|") + channel.token
            if (out.toString().toByteArray(Charsets.UTF_8).size +
                piece.toByteArray(Charsets.UTF_8).size > limit
            ) break
            out.append(piece)
        }
        return out.toString().ifEmpty { null }
    }
}

/**
 * Stage 2 of the two-stage pipeline: whatever can be learned in the ~30 seconds after an
 * SOS, from microphone, IMU and camera.
 *
 * **This interface is the whole point of the design.** Stage 2 is the only part of the
 * pipeline that might need a quantised model, and it is the part most likely to run out of
 * RAM, battery or time on a hackathon schedule. Putting it behind a seam means the
 * deterministic implementation ships now and a real model drops in later without touching
 * the protocol, the envelope, or anything downstream.
 *
 * An implementation may fail, block, or return null. [NodeAgent] treats all three as "no
 * enrichment" and carries on — Stage 2 can never delay or gate Stage 1.
 */
fun interface SensoryClassifier {
    /** Null means nothing worth reporting. Must not throw, but callers must assume it might. */
    fun classify(window: SensoryWindow): SensorySummary?
}

/**
 * Raw-ish inputs captured during the window. Values are normalised confidences, not signal
 * data: the conversion from microphone samples or accelerometer axes happens on the device
 * before this type exists.
 */
data class SensoryWindow(
    /** Loudness/spectral match for rushing water, in `0..1`. */
    val audioWater: Double = 0.0,
    /** Match for human voice or crying, in `0..1`. */
    val audioVoice: Double = 0.0,
    /** Match for structural cracking or impact, in `0..1`. */
    val audioStructural: Double = 0.0,
    /** How close to horizontal and still the device is, in `0..1`. */
    val imuPinned: Double = 0.0,
    /** Free-fall or violent movement, in `0..1`. */
    val imuShock: Double = 0.0,
    /** Ambient light: 0 is pitch dark, 1 is open sky. */
    val ambientLight: Double = 0.5,
    /** The shape the danger signal took over the window, if the detector produced one. */
    val event: SensoryEvent? = null,
) {
    init {
        listOf(audioWater, audioVoice, audioStructural, imuPinned, imuShock, ambientLight)
            .forEach { require(it in 0.0..1.0) { "sensory inputs must be 0..1, got " + it } }
    }
}

/**
 * The deterministic Stage 2 — thresholds, no model, no inference.
 *
 * This exists so the two-stage protocol is real from day one. It is roughly fifty lines of
 * comparisons, it cannot fail to load, it cannot exhaust memory, and the demo narrative is
 * identical to the one a real model would produce. If a quantised model lands later it
 * implements the same interface and everything downstream is unchanged.
 */
class DeterministicSensoryClassifier(
    private val reportThreshold: Double = 0.35,
) : SensoryClassifier {

    override fun classify(window: SensoryWindow): SensorySummary? {
        val channels = buildList {
            if (window.audioWater >= reportThreshold) {
                add(SensoryChannel("audio", window.audioWater, "AUDIO:RUSHING_WATER"))
            } else if (window.audioVoice >= reportThreshold) {
                add(SensoryChannel("audio", window.audioVoice, "AUDIO:VOICE"))
            } else if (window.audioStructural >= reportThreshold) {
                add(SensoryChannel("audio", window.audioStructural, "AUDIO:STRUCTURAL"))
            }

            if (window.imuShock >= reportThreshold) {
                add(SensoryChannel("imu", window.imuShock, "IMU:SHOCK"))
            } else if (window.imuPinned >= reportThreshold) {
                add(SensoryChannel("imu", window.imuPinned, "IMU:PINNED"))
            }

            // Darkness is only evidence when something else already suggests entrapment.
            // On its own a dark camera means a phone in a pocket, which is not news.
            val enclosed = 1.0 - window.ambientLight
            if (enclosed >= 0.8 && window.imuPinned >= reportThreshold) {
                add(SensoryChannel("visual", enclosed, "VIS:ENCLOSED"))
            }

            window.event?.let {
                add(SensoryChannel("pattern", it.dangerWeight, "EVENT:" + it.name))
            }
        }
        return if (channels.isEmpty()) null else SensorySummary(channels, window.event)
    }
}
