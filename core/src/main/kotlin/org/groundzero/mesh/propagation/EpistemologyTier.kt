package org.groundzero.mesh.propagation

/**
 * How a claim came to be known. Named from the Nyaya pramanas.
 *
 * - [PRATYAKSA] direct perception — the reporting node observed it itself, first hand.
 * - [ANUMANA]   inference — derived from other signals, not observed directly.
 * - [SABDA]     testimony — heard it from someone else. Everything relayed lands here.
 *
 * Order in this enum is strongest-evidence first. A relayed first-hand claim is only
 * testimony to whoever holds it downstream: see [Envelope.effectiveTier].
 */
enum class EpistemologyTier {
    PRATYAKSA,
    ANUMANA,
    SABDA;

    /** What this tier degrades to once the claim has been relayed at least one hop. */
    fun relayed(): EpistemologyTier = SABDA

    companion object {
        /** Weakest tier — the floor every relayed claim falls to. */
        val TESTIMONY: EpistemologyTier = SABDA
    }
}
