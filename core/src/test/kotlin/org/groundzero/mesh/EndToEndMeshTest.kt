package org.groundzero.mesh

import org.groundzero.mesh.agent.DeterministicSensoryClassifier
import org.groundzero.mesh.agent.NodeAgent
import org.groundzero.mesh.agent.SensoryWindow
import org.groundzero.mesh.gateway.ResponderRanking
import org.groundzero.mesh.propagation.Codecs
import org.groundzero.mesh.propagation.Envelope
import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.Gossip
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import org.groundzero.mesh.transport.SimNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole stack, end to end, over a simulated mesh: a trapped person presses SOS, the
 * report crosses several links, gets enriched, merges into one incident, and arrives at a
 * gateway that ranks it for a responder.
 *
 * This is the scenario the demo is, so it is worth having as a test rather than only as a
 * thing three people do with phones in a stairwell.
 */
class EndToEndMeshTest {

    private val victim = NodeId.parse("0000-0000-000a")
    private val relay = NodeId.parse("0000-0000-000b")
    private val gateway = NodeId.parse("0000-0000-000c")
    private val impostor = NodeId.parse("0000-0000-00ff")
    private val salt = "0123456789abcdef0123456789abcdef"

    private class Clock(var nowMs: Long = 0L)

    @Test
    fun `an sos crosses two hops, gets enriched, and lands on the responder board as one incident`() {
        val clock = Clock()
        val net = SimNetwork(latencyMs = 10)
        // A line: the victim cannot reach the gateway directly. This is the whole point of
        // a mesh, and the shape of the three-phone field test.
        net.link(victim, relay)
        net.link(relay, gateway)

        val victimTransport = net.transportFor(victim).also { it.start() }
        val relayTransport = net.transportFor(relay).also { it.start() }
        val gatewayTransport = net.transportFor(gateway).also { it.start() }

        val relayGossip = Gossip(relayTransport, clockMs = { clock.nowMs })
        val gatewayGossip = Gossip(gatewayTransport, clockMs = { clock.nowMs })
        relayTransport.onReceive { from, frame -> relayGossip.ingest(frame, from) }
        gatewayTransport.onReceive { from, frame -> gatewayGossip.ingest(frame, from) }

        val agent = NodeAgent(
            nodeId = victim,
            saltFingerprint = salt,
            addressZone = "sector-7-roof",
            transport = victimTransport,
            clockMs = { clock.nowMs },
            classifier = DeterministicSensoryClassifier(),
        )

        // t = 0: the button is pressed. This must go out before anything is inferred.
        agent.raiseSos(Severity.DROWNING_IMMINENT, atSeconds = 1_724_900_000L)
        net.runUntilIdle()

        assertEquals(1, gatewayGossip.clusters().size, "the gateway has heard about one person")
        val early = gatewayGossip.clusters().single()
        assertEquals(Severity.DROWNING_IMMINENT, early.severity)
        assertEquals(2, early.minHops, "it reached the gateway two links out")
        assertEquals(
            EpistemologyTier.SABDA, early.tier,
            "the gateway was told, not shown — it holds testimony",
        )

        // t = 20s: the sensory window closes and the refined report goes out, reusing the
        // incident timestamp.
        clock.nowMs = 20_000
        val refined = agent.completeSensoryWindow(
            SensoryWindow(audioWater = 0.95, imuPinned = 0.85, ambientLight = 0.1),
        )
        assertNotNull(refined)
        net.runUntilIdle()

        assertEquals(
            1, gatewayGossip.clusters().size,
            "enrichment updates the person we already know about — it does not add a casualty",
        )
        val enriched = gatewayGossip.clusters().single()
        assertNotNull(enriched.slmSummary)
        assertTrue(enriched.slmSummary!!.contains("RUSHING_WATER"), enriched.slmSummary!!)

        // And the responder board ranks it.
        val ranked = ResponderRanking.rank(gatewayGossip.clusters(), clock.nowMs)
        assertEquals(1, ranked.size)
        assertTrue(ranked.single().reasons.any { it.contains("drowning") })
        assertTrue(ranked.single().withinBudget)
    }

    @Test
    fun `one report arriving by two paths is one person on the board`() {
        val clock = Clock()
        val net = SimNetwork(latencyMs = 5)
        // A diamond: the victim reaches the gateway through two independent relays.
        val relay2 = NodeId.parse("0000-0000-000d")
        net.link(victim, relay); net.link(victim, relay2)
        net.link(relay, gateway); net.link(relay2, gateway)

        val transports = listOf(victim, relay, relay2, gateway).associateWith {
            net.transportFor(it).also { t -> t.start() }
        }
        val gossips = listOf(relay, relay2, gateway).associateWith { id ->
            Gossip(transports.getValue(id), clockMs = { clock.nowMs })
        }
        gossips.forEach { (id, g) ->
            transports.getValue(id).onReceive { from, frame -> g.ingest(frame, from) }
        }

        val agent = NodeAgent(victim, salt, "sector-7-roof", transports.getValue(victim), { clock.nowMs })
        agent.raiseSos(Severity.STRUCTURAL_ENTRAPMENT, atSeconds = 1_724_900_000L)
        net.runUntilIdle()

        val gatewayGossip = gossips.getValue(gateway)
        assertEquals(
            1, gatewayGossip.clusters().size,
            "two paths to the same person is still one person",
        )
        assertTrue(
            gatewayGossip.suppressedDuplicates > 0,
            "and the second path really did deliver a copy that dedup absorbed",
        )
        assertEquals(
            2, gatewayGossip.clusters().single().corroborators.size,
            "both relays are recorded as having carried it",
        )
    }

    @Test
    fun `a node spamming false reports loses its influence, and cannot confirm anything`() {
        val clock = Clock()
        val net = SimNetwork(latencyMs = 5)
        net.link(impostor, gateway)

        val impostorTransport = net.transportFor(impostor).also { it.start() }
        val gatewayTransport = net.transportFor(gateway).also { it.start() }
        val gossip = Gossip(gatewayTransport, clockMs = { clock.nowMs })
        gatewayTransport.onReceive { from, frame -> gossip.ingest(frame, from) }

        val codec = Codecs.forFrameBudget(impostorTransport.maxFrameBytes)

        // Twenty fabricated maximal-severity reports, each a distinct "incident".
        repeat(20) { i ->
            clock.nowMs = i * 1_000L
            impostorTransport.send(
                codec.encode(
                    Envelope(
                        nodeId = impostor,
                        saltFingerprint = salt,
                        addressZone = "sector-9",
                        tier = EpistemologyTier.PRATYAKSA,
                        severity = Severity.DROWNING_IMMINENT,
                        dangerScore = 1.0,
                        timestamp = 1_724_900_000L + i,
                    ),
                ),
            )
            net.runUntilIdle()
        }

        // Every fabricated report arrived over a link, so none of it is first-hand to the
        // gateway, and none of it can be dispatched against however loud it is.
        val ranked = ResponderRanking.rank(gossip.clusters(), clock.nowMs)
        assertEquals(20, ranked.size, "the reports are visible — we do not hide what we heard")
        assertTrue(
            ResponderRanking.dispatchable(ranked).isEmpty(),
            "but not one of them may commit a rescue team on hearsay alone",
        )
        assertTrue(
            ranked.all { it.priority <= org.groundzero.mesh.propagation.FirstHandGate.ADVISORY_CAP },
            "and all of it stays under the advisory cap",
        )

        // The trust machinery independently discredits it once the gateway judges it.
        val dedup = gossip.dedup()
        repeat(10) { dedup.judge(impostor, corroborated = false) }
        assertTrue(
            dedup.trustOf(impostor) < 0.3,
            "a node caught fabricating should lose its influence, got " + dedup.trustOf(impostor),
        )
    }

    @Test
    fun `a partitioned victim is not lost, only unheard`() {
        val clock = Clock()
        val net = SimNetwork(latencyMs = 5)
        net.link(victim, relay)
        net.link(relay, gateway)

        val victimTransport = net.transportFor(victim).also { it.start() }
        val relayTransport = net.transportFor(relay).also { it.start() }
        val gatewayTransport = net.transportFor(gateway).also { it.start() }
        val gatewayGossip = Gossip(gatewayTransport, clockMs = { clock.nowMs })
        val relayGossip = Gossip(relayTransport, clockMs = { clock.nowMs })
        relayTransport.onReceive { from, frame -> relayGossip.ingest(frame, from) }
        gatewayTransport.onReceive { from, frame -> gatewayGossip.ingest(frame, from) }

        // The relay is carried out of range mid-incident.
        net.unlink(relay, gateway)

        val agent = NodeAgent(victim, salt, "sector-7-roof", victimTransport, { clock.nowMs })
        agent.raiseSos(Severity.DROWNING_IMMINENT, atSeconds = 1_724_900_000L)
        net.runUntilIdle()

        assertEquals(0, gatewayGossip.clusters().size, "the gateway cannot know yet")
        assertEquals(
            1, relayGossip.clusters().size,
            "but the report is not lost — the relay is holding it, which is what makes " +
                "store-and-forward worth building in Phase 2",
        )
    }

    @Test
    fun `a corrupt frame is dropped without taking the node down`() {
        val clock = Clock()
        val net = SimNetwork(latencyMs = 1)
        net.link(victim, gateway)
        net.transportFor(victim).start()
        val gatewayTransport = net.transportFor(gateway).also { it.start() }
        val gossip = Gossip(gatewayTransport, clockMs = { clock.nowMs })

        // On a lossy radio a corrupt frame is an expected event, not an exceptional one.
        assertEquals(null, gossip.ingest(byteArrayOf(9, 9, 9, 9), victim))
        assertEquals(1, gossip.droppedUndecodable)
        assertEquals(0, gossip.clusters().size)
    }
}
