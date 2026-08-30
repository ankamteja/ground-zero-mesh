package org.groundzero.mesh.propagation

/**
 * The one unit that crosses the mesh. Same object on every hop and every transport; it has
 * two wire projections ([JsonCodec] for phone-to-phone, [CompactCodec] for LoRa), chosen
 * from the transport's frame budget via [Codecs.forFrameBudget].
 *
 * Three things that bite if skipped:
 *
 * 1. [dedupKey] is `nodeId + timestamp`. A refined Stage-3 broadcast intentionally reuses
 *    the original incident timestamp, so downstream layers update the existing cluster
 *    instead of raising a second alert for the same trapped person. Do not make timestamps
 *    unique per broadcast.
 * 2. [effectiveTier] downgrades a relayed first-hand claim to testimony. [tier] keeps the
 *    origin's claim; anyone holding this at `hops > 0` only has hearsay. UIs show the
 *    effective tier.
 * 3. The byte ceilings are enforced here, in the constructor. An envelope that could not
 *    fit a LoRa frame fails at construction, not at transmit time on a rooftop. Hitting a
 *    `require` below is the design working — do not raise the limit.
 */
data class Envelope(
    val nodeId: NodeId,
    /** 32 hex chars. */
    val saltFingerprint: String,
    /** <= 24 chars. */
    val addressZone: String,
    val tier: EpistemologyTier,
    val severity: Severity,
    /** 0..1. */
    val dangerScore: Double,
    /** incident time, seconds — part of [dedupKey]. */
    val timestamp: Long,
    /** <= 50 bytes UTF-8, Stage 3 only. */
    val slmSummary: String? = null,
    /**
     * The 8-bit sensory summary. See [org.groundzero.mesh.agent.SensoryFlags] for the bit
     * layout; one byte, so it rides on every envelope including LoRa frames.
     */
    val flags: Byte = 0,
    /**
     * The full 16-float feature vector, quantised to a byte per slot on the wire.
     *
     * Optional because it costs 17 bytes of a 233-byte LoRa frame to carry evidence a
     * responder does not triage on — [flags] is what the board acts on. Stage 3 attaches it
     * so the dashboard's inspector can show what the device actually saw; a heartbeat does
     * not.
     */
    val featureVector: org.groundzero.mesh.agent.SlmFeatureVector? = null,
    /** <= 4 entries. */
    val views: List<String> = emptyList(),
    /** <= 8 entries. */
    val peers: List<NodeId> = emptyList(),
    /** <= 15. */
    val hops: Int = 0,
    /** <= 15. */
    val ttl: Int = 15,
    /**
     * A GPS fix taken at broadcast time, when one was available. Both null or both set —
     * never partial.
     *
     * Deliberately not required and not waited for: GPS fails exactly where a victim most
     * needs the mesh — indoors, underground, under rubble — and Stage 0 is synchronous, no
     * waiting on anything before the SOS goes out. When present this is a real fix, never a
     * fallback or an estimate; the zone tag / hop count remain the honest proxy for anyone
     * without one. See the localisation entry in `TODO.md`'s open assumptions.
     */
    val gpsLat: Float? = null,
    val gpsLon: Float? = null,
) {
    init {
        require(saltFingerprint.length == 32 && saltFingerprint.all { it.isHexChar() }) {
            "saltFingerprint must be 32 hex chars, got '${saltFingerprint}'"
        }
        require(addressZone.length <= MAX_ADDRESS_ZONE_CHARS) {
            "addressZone must be <= $MAX_ADDRESS_ZONE_CHARS chars, got ${addressZone.length}"
        }
        require(dangerScore in 0.0..1.0) { "dangerScore must be 0..1, got $dangerScore" }
        require(timestamp >= 0L) { "timestamp must be >= 0, got $timestamp" }
        slmSummary?.let {
            require(it.utf8Size() <= MAX_SLM_SUMMARY_BYTES) {
                "slmSummary must be <= $MAX_SLM_SUMMARY_BYTES bytes, got ${it.utf8Size()}"
            }
        }
        require(views.size <= MAX_VIEWS) { "views must be <= $MAX_VIEWS, got ${views.size}" }
        require(views.all { it.utf8Size() <= MAX_VIEW_BYTES }) {
            "each view must be <= $MAX_VIEW_BYTES bytes"
        }
        require(peers.size <= MAX_PEERS) { "peers must be <= $MAX_PEERS, got ${peers.size}" }
        require(hops in 0..MAX_HOPS) { "hops must be 0..$MAX_HOPS, got $hops" }
        require(ttl in 0..MAX_TTL) { "ttl must be 0..$MAX_TTL, got $ttl" }
        require((gpsLat == null) == (gpsLon == null)) { "gpsLat and gpsLon must both be null or both be set" }
        gpsLat?.let { require(it in -90f..90f) { "gpsLat must be -90..90, got $it" } }
        gpsLon?.let { require(it in -180f..180f) { "gpsLon must be -180..180, got $it" } }

        // Against the *usable* payload, not the raw 233. The LoRa link spends
        // CompactCodec.LORA_LINK_HEADER_RESERVE bytes of that on its own framing, so an
        // envelope sized to the raw figure is one the radio can never actually carry — and
        // LoRaBridgeTransport.send() rejected exactly those at the last possible moment,
        // after the report had already been built and the sensory window spent.
        val frame = CompactCodec.frameSize(this)
        require(frame <= CompactCodec.LORA_USABLE_FRAME) {
            "envelope does not fit a LoRa frame: $frame > ${CompactCodec.LORA_USABLE_FRAME} bytes " +
                "(${CompactCodec.LORA_MAX_FRAME} on air, less ${CompactCodec.LORA_LINK_HEADER_RESERVE} for link framing)"
        }
    }

    /** Stable across the refined re-broadcast on purpose — see the class doc. */
    val dedupKey: String get() = "${nodeId.canonical()}@$timestamp"

    /** The tier a holder can actually rely on. Relayed => testimony. */
    val effectiveTier: EpistemologyTier
        get() = if (hops > 0) tier.relayed() else tier

    /**
     * What this node holds after taking the envelope off a link.
     *
     * Guarantees `hops >= 1`, and therefore that [effectiveTier] is testimony. Call it
     * once, on ingest, before the envelope reaches any layer that reasons about tiers.
     *
     * The guarantee cannot come from [hops] on its own, because [hops] is a
     * sender-controlled field. A node that is faulty, spoofed, or simply never increments
     * it arrives looking like direct observation — and the first-hand gate is precisely
     * the tier a bad actor wants to get past, since it is the one that authorises the
     * irreversible action. So the receiver decides its own epistemic position instead of
     * trusting a number the sender chose.
     *
     * Idempotent: an envelope that already crossed hops is returned unchanged.
     */
    fun asReceived(): Envelope = if (hops > 0) this else copy(hops = 1)

    /** One hop further out: hop count up, ttl down. Fails if ttl is exhausted. */
    fun forwarded(addPeers: List<NodeId> = emptyList()): Envelope {
        check(ttl > 0) { "ttl exhausted, cannot forward $dedupKey" }
        val merged = (peers + addPeers).distinct().take(MAX_PEERS)
        return copy(hops = minOf(hops + 1, MAX_HOPS), ttl = ttl - 1, peers = merged)
    }

    companion object {
        const val MAX_ADDRESS_ZONE_CHARS = 24

        /**
         * The zone tag a node sends when nobody has entered one.
         *
         * A sentinel rather than an empty string because it has to survive a round trip
         * through both codecs and be readable on a board. [isZoneKnown] is the one place
         * that decides what it means, so no downstream layer has to know the literal.
         */
        const val UNSET_ZONE = "unset"

        /** Whether a zone tag carries any location information at all. */
        fun isZoneKnown(zone: String): Boolean =
            zone.isNotBlank() && !zone.equals(UNSET_ZONE, ignoreCase = true)
        const val MAX_SLM_SUMMARY_BYTES = 50
        const val MAX_VIEWS = 4
        const val MAX_VIEW_BYTES = 120
        const val MAX_PEERS = 8
        const val MAX_HOPS = 15
        const val MAX_TTL = 15

        private fun Char.isHexChar() =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        internal fun String.utf8Size() = toByteArray(Charsets.UTF_8).size
    }
}
