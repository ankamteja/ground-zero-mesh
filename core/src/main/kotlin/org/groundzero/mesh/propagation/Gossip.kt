package org.groundzero.mesh.propagation

import org.groundzero.mesh.transport.Transport

/**
 * L2. Selective, bounded, hop-counted relay.
 *
 * Three properties, each of which is the difference between a mesh that survives a night
 * on battery and one that does not:
 *
 * - **Selective.** Only what has not been seen before moves, which is what stops a diamond
 *   topology turning one report into an exponential storm. Note that "seen before" is
 *   keyed on incident *plus changeable content*, not on incident alone — see
 *   [propagationKey], where the distinction is load-bearing.
 * - **Bounded.** TTL is spent on every forward, so reach is a design parameter rather than
 *   a function of how large the network happens to be.
 * - **Silent when there is nothing to say.** A quiet node transmits nothing at all.
 *
 * Every ingested envelope goes through [Envelope.asReceived] first, so anything that
 * crossed a radio is testimony to this node regardless of what the sender stamped.
 */
class Gossip(
    private val transport: Transport,
    private val clusters: DedupCluster = DedupCluster(),
    private val clockMs: () -> Long,
    private val maxSeen: Int = DEFAULT_SEEN_CAPACITY,
) {
    private val codec get() = Codecs.forFrameBudget(transport.maxFrameBytes)

    /** Insertion-ordered so the oldest key is the one evicted when the cap is hit. */
    private val seen = LinkedHashSet<String>()

    var relayed: Int = 0
        private set

    var suppressedDuplicates: Int = 0
        private set

    var droppedUndecodable: Int = 0
        private set

    /**
     * Take a frame off the wire.
     *
     * Returns the envelope if it was new, null if it was a duplicate or could not be
     * decoded. A malformed frame is counted and dropped: on a lossy radio a corrupt frame
     * is an expected event, not an exceptional one, and it must never take the agent down.
     */
    fun ingest(frame: ByteArray, from: NodeId?): Envelope? {
        val envelope = try {
            codec.decode(frame).asReceived()
        } catch (e: EnvelopeDecodeException) {
            droppedUndecodable++
            return null
        } catch (e: IllegalArgumentException) {
            // A frame that decodes structurally but violates the schema — an over-budget
            // envelope, a bad tier ordinal — is equally not our problem to salvage.
            droppedUndecodable++
            return null
        }

        if (!markSeen(envelope.propagationKey())) {
            suppressedDuplicates++
            // A second copy by another path is corroboration, not noise: record who else
            // carried it, then stop. It is still not re-transmitted.
            clusters.ingest(envelope, from, clockMs())
            return null
        }

        clusters.ingest(envelope, from, clockMs())
        forward(envelope)
        return envelope
    }

    /**
     * Put a locally-originated envelope on the wire and remember it, so this node does not
     * later treat an echo of its own report as news.
     */
    fun originate(envelope: Envelope) {
        markSeen(envelope.propagationKey())
        clusters.ingest(envelope, null, clockMs())
        transport.send(codec.encode(envelope))
    }

    /** Relay one hop further out, if there is budget left. Silently declines if not. */
    private fun forward(envelope: Envelope) {
        if (envelope.ttl <= 0) return
        transport.send(codec.encode(envelope.forwarded()))
        relayed++
    }

    fun clusters(): List<IncidentCluster> = clusters.clusters()

    fun dedup(): DedupCluster = clusters

    /**
     * What counts as "the same message" for the purpose of *not forwarding it again*.
     *
     * Deliberately **not** [Envelope.dedupKey]. That key identifies the incident, and the
     * Stage 3 enrichment reuses it on purpose so downstream layers update the same person
     * rather than inventing a second one. Suppressing on incident identity alone therefore
     * has a nasty consequence: the first relay has already seen the incident, so it drops
     * the refined report on the floor and the enrichment never reaches the responder — the
     * entire point of the sensory window, silently lost one hop from the victim.
     *
     * So propagation dedup keys on incident identity *plus the content that can change*.
     * Same incident with new evidence travels once more; a byte-identical repeat, however
     * many paths deliver it, does not. [hops] and [ttl] are excluded, because they change
     * on every forward and including them would defeat suppression entirely.
     */
    private fun Envelope.propagationKey(): String = buildString {
        append(dedupKey)
        append('#').append(severity.ordinal)
        append(':').append(Math.round(dangerScore * 1000))
        append(':').append(slmSummary ?: "")
        append(':').append(views.joinToString(","))
    }

    /** False if already present. Evicts oldest keys past [maxSeen] so memory stays bounded. */
    private fun markSeen(key: String): Boolean {
        if (!seen.add(key)) return false
        while (seen.size > maxSeen) {
            val oldest = seen.first()
            seen.remove(oldest)
        }
        return true
    }

    companion object {
        /**
         * How many incident keys a node remembers.
         *
         * Bounded on purpose: a node that remembers everything forever eventually cannot
         * remember anything, and the store must survive a long night in a pocket. Old keys
         * falling out means a very old report could circulate once more, which is a far
         * cheaper failure than running out of memory mid-incident.
         */
        const val DEFAULT_SEEN_CAPACITY = 512
    }
}
