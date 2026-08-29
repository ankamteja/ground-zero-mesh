package org.groundzero.mesh.agent

import java.util.Locale

/**
 * The honest answer to *how does it decide?* — at this layer there is no AI at all, just
 * an exponential moving average over signals in `0..1` and two thresholds.
 *
 * [explain] returns the score, the slow baseline, the last raw signal and a
 * human-readable reason; the UI surfaces it verbatim.
 */
class DangerScore(
    val alpha: Double = DEFAULT_ALPHA,
    val watchThreshold: Double = 0.35,
    val alarmThreshold: Double = 0.70,
    initial: Double = 0.0,
) {
    init {
        require(alpha in 0.0..1.0) { "alpha must be 0..1" }
        require(watchThreshold < alarmThreshold) { "watch threshold must be below alarm" }
        require(initial in 0.0..1.0) { "initial must be 0..1" }
    }

    var score: Double = initial
        private set

    /** Slow-moving reference the score is judged against; moves ~20x slower than [score]. */
    var baseline: Double = initial
        private set

    private var lastSignal: Double = initial

    /** Fold one observation in. Returns the updated [score]. */
    fun observe(signal: Double): Double {
        require(signal in 0.0..1.0) { "signal must be 0..1, got $signal" }
        lastSignal = signal
        score = alpha * signal + (1 - alpha) * score
        baseline = 0.05 * score + 0.95 * baseline
        return score
    }

    fun state(): AgentState = when {
        score >= alarmThreshold -> AgentState.ALARM
        score >= watchThreshold -> AgentState.WATCH
        else -> AgentState.CALM
    }

    fun explain(): ScoreExplanation {
        val s = state()
        val reason = when (s) {
            AgentState.ALARM -> fmt(
                "score %.2f at or above alarm %.2f (last signal %.2f)",
                score, alarmThreshold, lastSignal,
            )
            AgentState.WATCH -> fmt(
                "score %.2f at or above watch %.2f, below alarm %.2f",
                score, watchThreshold, alarmThreshold,
            )
            AgentState.CALM -> fmt(
                "score %.2f below watch %.2f",
                score, watchThreshold,
            )
        }
        return ScoreExplanation(
            score = score,
            baseline = baseline,
            lastSignal = lastSignal,
            state = s,
            reason = reason,
        )
    }

    private fun fmt(pattern: String, vararg args: Any) =
        String.format(Locale.ROOT, pattern, *args)

    companion object {
        /**
         * How much of each new signal the score takes on.
         *
         * 0.35 is a deliberate compromise: high enough that a violent IMU spike is visible
         * within two or three ticks, low enough that a single noisy reading cannot alarm the
         * node on its own. Raising it makes the mesh jumpy and chatty (every twitch crosses a
         * threshold and triggers a heartbeat); lowering it smooths a real collapse into
         * invisibility for the seconds that matter.
         *
         * Note that this is *not* on the SOS path — [NodeAgent.raiseSos] sets 1.00 directly
         * rather than feeding the EMA, precisely so no smoothing can delay a person who told
         * us themselves.
         */
        const val DEFAULT_ALPHA: Double = 0.35
    }
}
