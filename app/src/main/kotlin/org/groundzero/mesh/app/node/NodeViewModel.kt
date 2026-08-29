package org.groundzero.mesh.app.node

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import org.groundzero.mesh.agent.DangerScore
import org.groundzero.mesh.agent.ScoreExplanation
import org.groundzero.mesh.agent.SosInput
import org.groundzero.mesh.propagation.Severity

/**
 * Screen state for the Node UI. Holds the `core` [DangerScore] and surfaces its
 * [DangerScore.explain] verbatim — there is no AI at this layer, just an EMA and two
 * thresholds, and showing that earns more credibility than hiding it.
 */
class NodeViewModel(
    private val danger: DangerScore = DangerScore(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {

    var role by mutableStateOf(MeshRole.NODE)
        private set

    var selectedSeverity by mutableStateOf(Severity.STRUCTURAL_ENTRAPMENT)
        private set

    /** Last SOS the user raised on this device, if any. */
    var lastSos by mutableStateOf<SosInput?>(null)
        private set

    var explanation by mutableStateOf(danger.explain())
        private set

    fun selectRole(next: MeshRole) { role = next }

    fun selectSeverity(next: Severity) { selectedSeverity = next }

    /**
     * Raise an SOS. Pressing the button is itself a strong "something is wrong" signal, so
     * it feeds the score at full confidence. Severity rides alongside — it is not folded
     * into the score (see [Severity]).
     */
    fun raiseSos() {
        lastSos = SosInput(severity = selectedSeverity, atSeconds = now())
        danger.observe(1.0)
        explanation = danger.explain()
    }

    /** A sensory reading in 0..1 (demo: a slider; later: the L1 classifier). */
    fun feedSignal(signal: Double) {
        danger.observe(signal)
        explanation = danger.explain()
    }

    fun currentExplanation(): ScoreExplanation = explanation
}
