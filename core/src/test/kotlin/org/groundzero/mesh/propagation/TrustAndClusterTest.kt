package org.groundzero.mesh.propagation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrustConsensusTest {

    private val honest = NodeId(1)
    private val liar = NodeId(2)

    @Test
    fun `unknown peers start neutral`() {
        assertEquals(TrustConsensus.NEUTRAL_TRUST, TrustConsensus().trustOf(honest))
    }

    @Test
    fun `trust decays faster than it builds`() {
        // The asymmetry is the whole defence against a spoofed node. Assert the *behaviour*,
        // not just that the two constants were copied across accurately — copying a formula
        // right and wiring it up wrong is the failure that looks most like success.
        val gained = TrustConsensus().apply { reinforce(honest) }.trustOf(honest)
        val lost = TrustConsensus().apply { penalise(liar) }.trustOf(liar)

        val up = gained - TrustConsensus.NEUTRAL_TRUST
        val down = TrustConsensus.NEUTRAL_TRUST - lost
        assertTrue(down > up, "one bad report must cost more than one good report earns")
    }

    @Test
    fun `one contradicted report costs about seven clean relays`() {
        val steady = TrustConsensus().apply { repeat(7) { reinforce(honest) } }
        val betrayer = TrustConsensus().apply { penalise(liar) }

        val earned = steady.trustOf(honest) - TrustConsensus.NEUTRAL_TRUST
        val lost = TrustConsensus.NEUTRAL_TRUST - betrayer.trustOf(liar)
        assertTrue(
            lost >= earned,
            "a single conflicting report should cost at least what seven clean ones earn: " +
                "lost $lost vs earned $earned",
        )
    }

    @Test
    fun `a node cannot out-earn its own bad behaviour`() {
        val trust = TrustConsensus()
        repeat(20) { trust.reinforce(liar) }
        val earned = trust.trustOf(liar)

        // Roughly a third as many betrayals as it took to build the reputation.
        repeat(7) { trust.penalise(liar) }
        assertTrue(
            trust.trustOf(liar) < earned,
            "seven bad reports should undo more than twenty good ones built",
        )
    }

    @Test
    fun `an adversarial node loses its influence over the consensus`() {
        val trust = TrustConsensus()
        repeat(40) { trust.reinforce(honest) }
        repeat(40) { trust.penalise(liar) }

        // The liar screams; the honest node is calm. The consensus should follow the node
        // that has earned the right to be believed.
        val withLiar = trust.cooperativeScore(
            localScore = 0.2,
            peerScores = mapOf(honest to 0.2, liar to 1.0),
        )
        assertTrue(
            withLiar < 0.4,
            "a discredited node should barely move the consensus, got " + withLiar,
        )
    }

    @Test
    fun `a node keeps majority say over its own conclusion`() {
        val trust = TrustConsensus(localWeight = 0.6)
        repeat(40) { trust.reinforce(NodeId(9)) }

        // Every peer disagrees, loudly and credibly. The node still weights itself most.
        val score = trust.cooperativeScore(1.0, mapOf(NodeId(9) to 0.0))
        assertTrue(score >= 0.6, "local weight should dominate, got " + score)
    }

    @Test
    fun `no peers means the local conclusion stands unchanged`() {
        assertEquals(0.42, TrustConsensus().cooperativeScore(0.42, emptyMap()))
    }

    @Test
    fun `trust stays inside its range under sustained pressure`() {
        val trust = TrustConsensus()
        repeat(500) { trust.reinforce(honest) }
        repeat(500) { trust.penalise(liar) }

        assertTrue(trust.trustOf(honest) in 0.0..1.0)
        assertTrue(trust.trustOf(liar) in 0.0..1.0)
        assertTrue(trust.trustOf(honest) < 1.0, "nobody is ever fully believed")
    }

    @Test
    fun `a configuration where trust builds faster than it decays is refused`() {
        assertFailsWith<IllegalArgumentException> {
            TrustConsensus(trustGain = 0.10, trustLoss = 0.01)
        }
    }
}

class DedupClusterTest {

    private val victim = NodeId(1)
    private val relayA = NodeId(2)
    private val relayB = NodeId(3)

    private fun report(
        origin: NodeId = victim,
        severity: Severity = Severity.DROWNING_IMMINENT,
        score: Double = 0.9,
        timestamp: Long = 1_724_900_000L,
        hops: Int = 1,
        slm: String? = null,
        tier: EpistemologyTier = EpistemologyTier.PRATYAKSA,
        gpsLat: Float? = null,
        gpsLon: Float? = null,
    ) = Envelope(
        nodeId = origin,
        saltFingerprint = "0123456789abcdef0123456789abcdef",
        addressZone = "sector-7-roof",
        tier = tier,
        severity = severity,
        dangerScore = score,
        timestamp = timestamp,
        slmSummary = slm,
        hops = hops,
        gpsLat = gpsLat,
        gpsLon = gpsLon,
        // These predate provenance and all describe device fixes, which is what a
        // coordinate meant before there was another kind.
        gpsSource = gpsLat?.let { FixSource.SATELLITE },
    )

    @Test
    fun `two relays of one person are one incident`() {
        val clusters = DedupCluster()
        clusters.ingest(report(), relayA, 1_000)
        clusters.ingest(report(), relayB, 1_100)

        assertEquals(1, clusters.size, "one trapped person is one incident, however many relays")
        assertEquals(2, clusters.clusters().single().corroborators.size)
        assertEquals(1, clusters.clusters().single().corroborationCount)
    }

    @Test
    fun `reportCount folds every ingest, not just distinct relayers`() {
        val clusters = DedupCluster()
        clusters.ingest(report(), null, 1_000) // the origin's own send
        clusters.ingest(report(), relayA, 1_100)
        clusters.ingest(report(), relayA, 1_200) // relayA again — a genuine repeat, not new corroboration
        clusters.ingest(report(), relayB, 1_300)

        val incident = clusters.clusters().single()
        assertEquals(4, incident.reportCount, "every fold counts, including relayA's repeat")
        assertEquals(2, incident.corroborators.size, "but only two distinct relayers ever carried it")
    }

    @Test
    fun `a relay that corroborates what we hold earns trust`() {
        val clusters = DedupCluster()
        clusters.ingest(report(), relayA, 1_000)
        clusters.ingest(report(), relayB, 1_100)

        assertTrue(clusters.trustOf(relayB) > TrustConsensus.NEUTRAL_TRUST)
    }

    @Test
    fun `a relay that contradicts the severity we hold loses trust hard`() {
        val clusters = DedupCluster()
        clusters.ingest(report(severity = Severity.DROWNING_IMMINENT), relayA, 1_000)
        clusters.ingest(report(severity = Severity.OTHER), relayB, 1_100)

        val lost = TrustConsensus.NEUTRAL_TRUST - clusters.trustOf(relayB)
        assertTrue(lost > 0.15, "conflicting telemetry should cost real standing, lost $lost")
        // And the incident itself is unmoved: severity never walks back to a calmer claim.
        assertEquals(Severity.DROWNING_IMMINENT, clusters.clusters().single().severity)
    }

    @Test
    fun `the first sighting of an incident judges nobody`() {
        val clusters = DedupCluster()
        clusters.ingest(report(), relayA, 1_000)

        assertEquals(TrustConsensus.NEUTRAL_TRUST, clusters.trustOf(relayA))
    }

    @Test
    fun `the person in trouble is never judged for their own report`() {
        val clusters = DedupCluster()
        clusters.ingest(report(severity = Severity.DROWNING_IMMINENT), victim, 1_000)
        // Their own Stage 3 enrichment, arriving from themselves.
        clusters.ingest(report(severity = Severity.DROWNING_IMMINENT, slm = "IMU:PINNED"), victim, 2_000)

        assertEquals(TrustConsensus.NEUTRAL_TRUST, clusters.trustOf(victim))
    }

    @Test
    fun `the refined stage three report updates the incident in place`() {
        val clusters = DedupCluster()
        clusters.ingest(report(score = 1.0), relayA, 1_000)
        clusters.ingest(
            report(score = 0.85, slm = "AUDIO:RUSHING_WATER|IMU:PINNED"),
            relayA,
            2_000,
        )

        val cluster = clusters.clusters().single()
        assertEquals(1, clusters.size, "enrichment must not create a second casualty")
        assertEquals("AUDIO:RUSHING_WATER|IMU:PINNED", cluster.slmSummary)
        assertEquals(1.0, cluster.dangerScore, "the strongest score seen is kept")
    }

    @Test
    fun `an enrichment without a summary never blanks the one we have`() {
        val clusters = DedupCluster()
        clusters.ingest(report(slm = "IMU:PINNED"), relayA, 1_000)
        clusters.ingest(report(slm = null), relayB, 2_000)

        assertEquals("IMU:PINNED", clusters.clusters().single().slmSummary)
    }

    @Test
    fun `a later report without a GPS fix never blanks the one we have`() {
        val clusters = DedupCluster()
        clusters.ingest(report(gpsLat = 12.9716f, gpsLon = 77.5946f), relayA, 1_000)
        clusters.ingest(report(gpsLat = null, gpsLon = null), relayB, 2_000)

        val incident = clusters.clusters().single()
        assertEquals(12.9716f, incident.gpsLat)
        assertEquals(77.5946f, incident.gpsLon)
    }

    @Test
    fun `a later, better GPS fix replaces the earlier one`() {
        val clusters = DedupCluster()
        clusters.ingest(report(gpsLat = 12.9716f, gpsLon = 77.5946f), relayA, 1_000)
        clusters.ingest(report(gpsLat = 12.9720f, gpsLon = 77.5950f), relayA, 2_000)

        val incident = clusters.clusters().single()
        assertEquals(12.9720f, incident.gpsLat)
        assertEquals(77.5950f, incident.gpsLon)
    }

    @Test
    fun `severity only ever escalates`() {
        val clusters = DedupCluster()
        clusters.ingest(report(severity = Severity.DROWNING_IMMINENT), relayA, 1_000)
        clusters.ingest(report(severity = Severity.OTHER), relayB, 2_000)

        assertEquals(
            Severity.DROWNING_IMMINENT, clusters.clusters().single().severity,
            "a calmer later relay must not walk back the person's own statement",
        )
    }

    @Test
    fun `different incidents from the same node stay separate`() {
        val clusters = DedupCluster()
        clusters.ingest(report(timestamp = 1_724_900_000L), relayA, 1_000)
        clusters.ingest(report(timestamp = 1_724_900_600L), relayA, 2_000)

        assertEquals(2, clusters.size, "the same person trapped twice is two incidents")
    }

    @Test
    fun `the closest approach is remembered`() {
        val clusters = DedupCluster()
        clusters.ingest(report(hops = 4), relayA, 1_000)
        clusters.ingest(report(hops = 2), relayB, 1_100)

        assertEquals(2, clusters.clusters().single().minHops)
    }

    @Test
    fun `a first-hand report marks the incident as first-hand held`() {
        val clusters = DedupCluster()
        clusters.ingest(report(hops = 1), relayA, 1_000)
        assertTrue(!clusters.clusters().single().firstHandHeld, "hops=1 is testimony")

        clusters.ingest(report(hops = 0), null, 1_100)
        assertTrue(clusters.clusters().single().firstHandHeld)
    }
}

class FirstHandGateTest2 {

    private fun cluster(
        firstHand: Boolean,
        score: Double = 0.9,
        corroborators: Int = 1,
    ) = IncidentCluster(
        key = "k",
        origin = NodeId(1),
        zone = "sector-7",
        severity = Severity.DROWNING_IMMINENT,
        dangerScore = score,
        tier = if (firstHand) EpistemologyTier.PRATYAKSA else EpistemologyTier.SABDA,
        corroborators = (1..corroborators).map { NodeId(it.toLong() + 10) }.toSet(),
        minHops = if (firstHand) 0 else 2,
        firstSeenMs = 0,
        lastUpdatedMs = 0,
        firstHandHeld = firstHand,
    )

    @Test
    fun `only first-hand observation may confirm critical`() {
        assertTrue(FirstHandGate.canConfirmCritical(cluster(firstHand = true)))
        assertTrue(!FirstHandGate.canConfirmCritical(cluster(firstHand = false)))
    }

    @Test
    fun `a report below the floor cannot confirm even when first-hand`() {
        assertTrue(!FirstHandGate.canConfirmCritical(cluster(firstHand = true, score = 0.4)))
    }

    @Test
    fun `no amount of testimony can reach the tier that commits a team`() {
        // The cap is structural, not a high threshold. A sufficiently chatty cluster of
        // nodes must not be able to arithmetically tip hearsay into dispatch.
        val loud = cluster(firstHand = false, score = 1.0, corroborators = 50)
        assertTrue(FirstHandGate.cappedPriority(loud, 1.0) <= FirstHandGate.ADVISORY_CAP)
    }

    @Test
    fun `first-hand incidents are not capped`() {
        assertEquals(1.0, FirstHandGate.cappedPriority(cluster(firstHand = true), 1.0))
    }

    @Test
    fun `standing distinguishes heard-from, heard-about and corroborated`() {
        assertEquals(
            FirstHandGate.Standing.CONFIRMED_FIRST_HAND,
            FirstHandGate.standing(cluster(firstHand = true)),
        )
        assertEquals(
            FirstHandGate.Standing.CORROBORATED_TESTIMONY,
            FirstHandGate.standing(cluster(firstHand = false, corroborators = 3)),
        )
        assertEquals(
            FirstHandGate.Standing.SINGLE_UNCONFIRMED,
            FirstHandGate.standing(cluster(firstHand = false, corroborators = 1)),
        )
        assertEquals(
            FirstHandGate.Standing.BELOW_FLOOR,
            FirstHandGate.standing(cluster(firstHand = true, score = 0.1)),
        )
    }

    @Test
    fun `only confirmed first-hand standing is dispatchable`() {
        assertTrue(FirstHandGate.Standing.CONFIRMED_FIRST_HAND.dispatchable)
        assertTrue(!FirstHandGate.Standing.CORROBORATED_TESTIMONY.dispatchable)
        assertTrue(!FirstHandGate.Standing.SINGLE_UNCONFIRMED.dispatchable)
    }
}
