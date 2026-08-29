package org.groundzero.mesh.agent

import org.groundzero.mesh.propagation.Severity

/**
 * A person pressing the SOS button. Severity is picked by the human; the danger score is
 * computed, not entered — the two are orthogonal (see [Severity]).
 */
data class SosInput(
    val severity: Severity,
    /** incident time, seconds. */
    val atSeconds: Long,
    val note: String? = null,
) {
    init {
        require(atSeconds >= 0) { "atSeconds must be >= 0" }
        require(note == null || note.length <= 200) { "note must be <= 200 chars" }
    }
}
