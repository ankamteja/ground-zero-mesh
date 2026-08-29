package org.groundzero.mesh.propagation

/**
 * What a given tier of knowledge is allowed to authorise.
 *
 * Ported from the reference risk-manager's first-hand gate, remapped from "freeze a
 * transaction" to "commit a rescue team". The shape is identical and so is the reason for
 * it: testimony may raise suspicion and add friction, but only first-hand observation may
 * take the irreversible action.
 *
 * Here the irreversible action is dispatch. Boats and teams are finite, and sending one to
 * a hearsay report is not a neutral act — it is a boat that is not at the rooftop where
 * somebody actually is.
 */
object FirstHandGate {

    /** Below this, a report is not actionable at any tier. */
    const val FIRSTHAND_FLOOR = 0.45

    /**
     * Ceiling on anything a relayed report can reach on its own, deliberately just under
     * the level that commits a team.
     *
     * The 0.98 is ported: it exists so that no amount of corroboration can arithmetically
     * tip testimony into the tier that acts. It has to be a structural ceiling rather than
     * a high threshold, or a sufficiently chatty cluster of nodes eventually crosses it.
     */
    const val ADVISORY_CAP = 0.98

    /** Whether this incident may be marked confirmed-critical and dispatched against. */
    fun canConfirmCritical(cluster: IncidentCluster): Boolean =
        cluster.firstHandHeld && cluster.dangerScore >= FIRSTHAND_FLOOR

    /**
     * The priority an incident may reach given how it is known.
     *
     * First-hand incidents are uncapped. Everything else is held below [ADVISORY_CAP] no
     * matter how many nodes repeat it — corroboration raises rank, it does not change what
     * kind of knowledge it is.
     */
    fun cappedPriority(cluster: IncidentCluster, rawPriority: Double): Double {
        val clamped = rawPriority.coerceIn(0.0, 1.0)
        return if (canConfirmCritical(cluster)) clamped else minOf(clamped, ADVISORY_CAP)
    }

    /** How an incident should be labelled for a responder reading the board. */
    fun standing(cluster: IncidentCluster): Standing = when {
        cluster.dangerScore < FIRSTHAND_FLOOR -> Standing.BELOW_FLOOR
        cluster.firstHandHeld -> Standing.CONFIRMED_FIRST_HAND
        cluster.corroborationCount > 0 -> Standing.CORROBORATED_TESTIMONY
        else -> Standing.SINGLE_UNCONFIRMED
    }

    /**
     * The distinction a responder needs at a glance: is this someone we heard from, or
     * someone we heard *about*, and did more than one node say so.
     */
    enum class Standing(val label: String, val dispatchable: Boolean) {
        CONFIRMED_FIRST_HAND("confirmed — first-hand", true),
        CORROBORATED_TESTIMONY("corroborated — testimony", false),
        SINGLE_UNCONFIRMED("single unconfirmed report", false),
        BELOW_FLOOR("below reporting floor", false),
    }
}
