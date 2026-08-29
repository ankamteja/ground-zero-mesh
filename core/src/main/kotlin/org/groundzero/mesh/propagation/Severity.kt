package org.groundzero.mesh.propagation

/**
 * How fast this person dies if nobody comes.
 *
 * This is NOT [Envelope.dangerScore]. Danger score is *how confident am I that something
 * is wrong*; severity is *how little time is left*. They are orthogonal and must never be
 * collapsed into one number — a calm, certain observation of a submerging rooftop outranks
 * a panicking report from a dry stairwell.
 *
 * [rank] is ascending urgency-first order: 0 is the most urgent.
 */
enum class Severity(val rank: Int) {
    DROWNING_IMMINENT(0),
    STRUCTURAL_ENTRAPMENT(1),
    OTHER(2);

    companion object {
        fun byRank(rank: Int): Severity = entries.first { it.rank == rank }
    }
}
