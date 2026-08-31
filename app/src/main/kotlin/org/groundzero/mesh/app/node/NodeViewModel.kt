package org.groundzero.mesh.app.node

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import org.groundzero.mesh.agent.DangerScore
import org.groundzero.mesh.agent.ScoreExplanation
import org.groundzero.mesh.agent.SosInput
import org.groundzero.mesh.app.mesh.MeshStack
import org.groundzero.mesh.propagation.Envelope
import org.groundzero.mesh.propagation.Severity

/**
 * Screen state for the Node UI. Holds the `core` [DangerScore] and surfaces its
 * [DangerScore.explain] verbatim — there is no AI at this layer, just an EMA and two
 * thresholds, and showing that earns more credibility than hiding it.
 *
 * [raiseOnMesh] is the seam to the running stack; it is injected so this class stays a plain
 * JVM unit test subject.
 */
class NodeViewModel(
    private val danger: DangerScore = DangerScore(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val raiseOnMesh: (Severity) -> Envelope? = MeshStack::raiseSos,
    private val applyRole: (MeshRole) -> Unit = MeshStack::setRole,
    initialRole: () -> MeshRole = MeshStack::currentRole,
    private val applySelfPosition: (lat: Float?, lon: Float?, zone: String?) -> Unit =
        MeshStack::updateSelfReportedPosition,
) : ViewModel() {

    /**
     * Seeded from the running stack, not hardcoded to [MeshRole.NODE]. The role survives a
     * service restart via [RoleStore], so an Activity rebuilt after one would otherwise show
     * "victim" while the stack underneath is still serving as a gateway — the same UI/stack
     * divergence [RoleStore] exists to prevent, just from the other side.
     */
    var role by mutableStateOf(initialRole())
        private set

    var selectedSeverity by mutableStateOf(Severity.STRUCTURAL_ENTRAPMENT)
        private set

    /** Last SOS the user raised on this device, if any. */
    var lastSos by mutableStateOf<SosInput?>(null)
        private set

    var explanation by mutableStateOf(danger.explain())
        private set

    /**
     * Whether the last SOS actually went on the wire. False when the mesh service is not
     * running — a person who pressed the button must not be shown a screen that implies help
     * was called when nothing left the phone.
     */
    var lastSosBroadcast by mutableStateOf(false)
        private set

    fun selectRole(next: MeshRole) {
        role = next
        applyRole(next)
    }

    fun selectSeverity(next: Severity) { selectedSeverity = next }

    /**
     * Where the person marked themselves on the site plan, in plan space, plus the zone that
     * point fell in. Null until someone answers — never a default, never a centre-of-map
     * guess, because an unanswered question must not leave this phone looking answered.
     */
    var selfMark by mutableStateOf<SelfPositionStore.Mark?>(null)
        private set

    /**
     * Record a tap on the plan.
     *
     * The coordinate reaches the mesh stack immediately rather than waiting for the SOS: a
     * person may mark themselves and then be unable to press anything, and the heartbeat this
     * node is already sending will carry the position out on its own.
     */
    fun markSelfPosition(plan: SitePlan, planX: Float, planY: Float) {
        val clampedX = planX.coerceIn(0f, plan.width)
        val clampedY = planY.coerceIn(0f, plan.height)
        val zone = plan.zoneAt(clampedX, clampedY)?.name.orEmpty()
        selfMark = SelfPositionStore.Mark(clampedX, clampedY, zone)
        val (lat, lon) = plan.georeference.toLatLon(clampedX, clampedY)
        // A tap on open ground still yields a position; it just names no zone, and the zone
        // is left alone rather than blanked, since "not in a listed building" is not the same
        // as "no longer wherever they last said".
        applySelfPosition(lat.toFloat(), lon.toFloat(), zone.takeIf { it.isNotBlank() })
    }

    /** Seeds the marker from what was stored, so a restart shows what the person answered. */
    fun restoreSelfPosition(mark: SelfPositionStore.Mark?) {
        selfMark = mark
    }

    /** Take the mark back. The SOS then carries no self-reported position at all. */
    fun clearSelfPosition() {
        selfMark = null
        applySelfPosition(null, null, null)
    }

    /**
     * Raise an SOS. Pressing the button is itself a strong "something is wrong" signal, so
     * it feeds the score at full confidence. Severity rides alongside — it is not folded
     * into the score (see [Severity]).
     */
    fun raiseSos() {
        lastSos = SosInput(severity = selectedSeverity, atSeconds = now())
        // The agent broadcasts synchronously inside this call, before anything below runs.
        lastSosBroadcast = raiseOnMesh(selectedSeverity) != null
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
