package org.groundzero.mesh.agent

/**
 * Why the danger score is what it is. Rendered as-is in the "why" panel — this is the
 * strong demo beat: at this layer the decision is fully legible.
 */
data class ScoreExplanation(
    val score: Double,
    val baseline: Double,
    val lastSignal: Double,
    val state: AgentState,
    val reason: String,
)
