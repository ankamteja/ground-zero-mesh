package org.groundzero.mesh.agent

import kotlin.math.abs

/**
 * The shape a run of observations has taken.
 *
 * The taxonomy is ported structurally from the reference implementation. **The weights are
 * deliberately not.** In a neighbourhood-watch mesh a sustained spike is the loud event and
 * a gradual drift is background noise. Invert that here:
 *
 * - A **gradual drift** upward is water rising. It is the single most time-critical thing
 *   this system can observe, and it is exactly the pattern an adaptive baseline is most
 *   likely to absorb into "normal".
 * - A **sudden drop** is a device that died or a person who stopped moving. Both are worse
 *   news than any spike.
 *
 * Getting these weights the wrong way round would make the agent quietest precisely when
 * it should be loudest, so the retune is logged in the port ledger rather than left as a
 * silent constant change.
 */
enum class SensoryEvent(val dangerWeight: Double, val meaning: String) {
    /** Steady rise. Water. The most urgent pattern in this domain. */
    GRADUAL_DRIFT(0.90, "a steady rise — consistent with water rising"),

    /** Signal fell away and stayed down. Device died, or the person stopped moving. */
    SUDDEN_DROP(0.85, "signal fell away — device died, or movement stopped"),

    /** Loud and sustained. Urgent, but less so than the two above. */
    SUSTAINED_SPIKE(0.75, "a high reading that has held"),

    /** Swinging back and forth. Often structural movement or intermittent contact. */
    OSCILLATION(0.45, "readings swinging back and forth"),

    /** Flat and unremarkable. */
    PLATEAU(0.15, "readings flat and unremarkable"),
}

/**
 * Classifies a sliding window of observations into a [SensoryEvent].
 *
 * Entirely deterministic and entirely explainable: every branch below is a threshold a
 * responder could be walked through. Nothing here is a model.
 */
class EventDetector(
    private val windowSize: Int = DEFAULT_WINDOW,
    private val driftThreshold: Double = 0.25,
    private val dropThreshold: Double = 0.30,
    private val spikeLevel: Double = 0.60,
    private val oscillationTurns: Int = 3,
    private val plateauSpread: Double = 0.08,
) {
    init {
        require(windowSize >= 3) { "a window needs at least three readings to have a shape" }
    }

    private val window = ArrayDeque<Double>()

    fun observe(value: Double): SensoryEvent? {
        window.addLast(value)
        while (window.size > windowSize) window.removeFirst()
        return if (window.size < windowSize) null else classify()
    }

    fun reset() = window.clear()

    /** Current window contents, oldest first. Exposed for the "why" panel. */
    fun readings(): List<Double> = window.toList()

    private fun classify(): SensoryEvent {
        val values = window.toList()
        val first = values.first()
        val last = values.last()
        val net = last - first
        val spread = values.max() - values.min()
        val mean = values.average()

        // Order matters: the two most time-critical shapes are tested first, so a run that
        // could be read as either a drift or a spike is reported as the drift.
        return when {
            net >= driftThreshold && isMostlyMonotonic(values, rising = true) -> SensoryEvent.GRADUAL_DRIFT
            net <= -dropThreshold && last <= first - dropThreshold -> SensoryEvent.SUDDEN_DROP
            mean >= spikeLevel && spread <= 0.35 -> SensoryEvent.SUSTAINED_SPIKE
            turningPoints(values) >= oscillationTurns -> SensoryEvent.OSCILLATION
            spread <= plateauSpread -> SensoryEvent.PLATEAU
            else -> SensoryEvent.PLATEAU
        }
    }

    /**
     * Tolerates one step against the trend. Real sensor data is never perfectly monotonic,
     * and demanding that it be would mean never detecting the rise that matters most.
     */
    private fun isMostlyMonotonic(values: List<Double>, rising: Boolean): Boolean {
        var against = 0
        for (i in 1 until values.size) {
            val delta = values[i] - values[i - 1]
            if (rising && delta < -0.01) against++
            if (!rising && delta > 0.01) against++
        }
        return against <= 1
    }

    private fun turningPoints(values: List<Double>): Int {
        var turns = 0
        for (i in 1 until values.size - 1) {
            val before = values[i] - values[i - 1]
            val after = values[i + 1] - values[i]
            if (abs(before) > 0.02 && abs(after) > 0.02 && (before > 0) != (after > 0)) turns++
        }
        return turns
    }

    companion object {
        const val DEFAULT_WINDOW = 6
    }
}
