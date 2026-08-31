package org.groundzero.mesh.propagation

/**
 * Where a coordinate came from — the difference between a machine's measurement and a
 * frightened person's best guess.
 *
 * [Envelope.gpsLat] is documented as a real fix or nothing, and that promise is what makes it
 * safe to act on. But GPS fails exactly where victims are — indoors, underground, under
 * rubble — so on most real incidents that field is null and a rescue team gets no location at
 * all. A trapped person usually still *knows* roughly where they are, and the map picker on
 * the victim screen lets them say so.
 *
 * That answer is worth carrying and must never be mistaken for the other kind. A satellite
 * fix is accurate to metres and is nobody's opinion; a tap on a map is a self-report that can
 * be confidently, sincerely wrong — the person may have the wrong building, or be describing
 * where they *entered* rather than where they ended up. `structural-collapse.md` puts the
 * cost plainly: a casualty drawn confidently in the wrong place costs a search, and that
 * search costs someone else their window.
 *
 * So provenance rides with the coordinate rather than being inferred at the far end. Every
 * layer that renders a position renders where it came from, and no layer has to guess.
 *
 * On the wire this is the value of the GPS header byte that [CompactCodec] already spends —
 * `0x00` absent, `0x01` satellite, `0x02` self-reported — so telling the truth about a
 * coordinate's origin costs nothing on a LoRa frame.
 */
enum class FixSource {
    /** A real GNSS lock from the reporting device. Never estimated, never derived. */
    SATELLITE,

    /**
     * A location the person marked on a map themselves. Requires no satellites and no
     * network, which is exactly why it exists — but it is testimony about a position, and
     * carries the same epistemic weight as any other thing a person says about their own
     * situation.
     */
    SELF_REPORTED,
}
