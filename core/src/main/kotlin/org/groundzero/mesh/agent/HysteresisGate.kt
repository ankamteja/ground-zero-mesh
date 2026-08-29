package org.groundzero.mesh.agent

/**
 * Turns a continuous score into a posture, with a deadband so it cannot flap.
 *
 * [DangerScore.state] maps the score to a posture with bare thresholds, which is fine for
 * reading the score at an instant. It is not enough to *drive* anything: a score resting
 * near a threshold crosses it repeatedly, and every crossing is a state transition that
 * the agent would gossip. Flapping at 0.70 turns one event into a broadcast storm, on
 * battery, in a blackout.
 *
 * So transitions use the plain thresholds on the way **up** and `threshold - deadband` on
 * the way **down**. A posture has to be given up by a clear margin, never by a rounding
 * error. The thresholds are the reference implementation's; the deadband is ours.
 */
class HysteresisGate(
    private val watchThreshold: Double = 0.35,
    private val alarmThreshold: Double = 0.70,
    private val deadband: Double = DEFAULT_DEADBAND,
    initial: AgentState = AgentState.CALM,
) {
    init {
        require(watchThreshold < alarmThreshold) { "watch threshold must sit below alarm" }
        require(deadband >= 0.0) { "deadband must not be negative" }
        require(watchThreshold - deadband >= 0.0) { "deadband must not push the watch threshold below zero" }
    }

    var state: AgentState = initial
        private set

    /** Number of transitions so far. A flapping gate shows up here immediately. */
    var transitions: Int = 0
        private set

    /** Fold the current score in and return the posture the agent should act on. */
    fun update(score: Double): AgentState {
        val next = when (state) {
            AgentState.CALM -> when {
                score >= alarmThreshold -> AgentState.ALARM
                score >= watchThreshold -> AgentState.WATCH
                else -> AgentState.CALM
            }
            AgentState.WATCH -> when {
                score >= alarmThreshold -> AgentState.ALARM
                score < watchThreshold - deadband -> AgentState.CALM
                else -> AgentState.WATCH
            }
            AgentState.ALARM -> when {
                score < watchThreshold - deadband -> AgentState.CALM
                score < alarmThreshold - deadband -> AgentState.WATCH
                else -> AgentState.ALARM
            }
        }
        if (next != state) {
            state = next
            transitions++
            return next
        }
        return state
    }

    /**
     * Force the gate straight to [AgentState.ALARM].
     *
     * The immediate-override path uses this. A first-hand SOS must not have to climb
     * through the score machine to be taken seriously — see [NodeAgent.raiseSos].
     */
    fun forceAlarm() {
        if (state != AgentState.ALARM) {
            state = AgentState.ALARM
            transitions++
        }
    }

    companion object {
        /** Ours, not the reference implementation's. Enough to absorb ordinary jitter. */
        const val DEFAULT_DEADBAND = 0.05
    }
}
