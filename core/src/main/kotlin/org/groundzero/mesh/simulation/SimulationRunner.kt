package org.groundzero.mesh.simulation

import org.groundzero.mesh.agent.MathEngine
import org.groundzero.mesh.agent.NodeAgent
import org.groundzero.mesh.agent.SensoryFlags
import org.groundzero.mesh.agent.SensoryWindow
import org.groundzero.mesh.agent.SlmFeatureVector
import org.groundzero.mesh.gateway.DigitalTwin
import org.groundzero.mesh.gateway.RadminLlmSummarizer
import org.groundzero.mesh.gateway.RankedIncident
import org.groundzero.mesh.gateway.ResponderRanking
import org.groundzero.mesh.gateway.TwinSnapshot
import org.groundzero.mesh.propagation.DedupCluster
import org.groundzero.mesh.propagation.Gossip
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import org.groundzero.mesh.transport.SimNetwork
import java.io.File

/**
 * Runs the whole stack over a simulated mesh and prints what happened.
 *
 * This exists because the honest demo of a disaster mesh is a hard problem: the real thing
 * needs three phones, two rooms and a person willing to be trapped. The simulation runs the
 * *same* `core` code the phones run — the only substitution is [SimNetwork] in place of a
 * radio — so what it prints is the behaviour of the shipped logic, not a mock of it.
 *
 * `./gradlew :core:runSim` prints the report. `--json <path>` also writes the twin snapshot
 * for `docs/simulation_dashboard.html` to render.
 */
object SimulationRunner {

    private val victim = NodeId.parse("0000-0000-000a")
    private val neighbour = NodeId.parse("0000-0000-000d")
    private val relay = NodeId.parse("0000-0000-000b")
    private val gateway = NodeId.parse("0000-0000-000c")
    private const val SALT = "0123456789abcdef0123456789abcdef"

    /** So printed incident timestamps look like wall-clock seconds, not "0". */
    private const val EPOCH_MS = 1_756_400_000_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val report = run()
        println(report.text)
        val jsonFlag = args.indexOf("--json")
        if (jsonFlag >= 0 && jsonFlag + 1 < args.size) {
            val out = File(args[jsonFlag + 1])
            out.parentFile?.mkdirs()
            out.writeText(report.json)
            println("\nwrote ${out.path}")
        }
    }

    class Report(val text: String, val json: String)

    fun run(): Report {
        val log = StringBuilder()
        fun say(line: String = "") = log.appendLine(line)

        val net = SimNetwork(latencyMs = 20)
        net.link(victim, relay)
        net.link(neighbour, relay)
        net.link(relay, gateway)

        val victimTransport = net.transportFor(victim).also { it.start() }
        val neighbourTransport = net.transportFor(neighbour).also { it.start() }
        val relayTransport = net.transportFor(relay).also { it.start() }
        val gatewayTransport = net.transportFor(gateway).also { it.start() }

        val relayGossip = Gossip(relayTransport, clockMs = { EPOCH_MS + net.nowMs() })
        relayTransport.onReceive { from, frame -> relayGossip.ingest(frame, from) }

        val boardClusters = DedupCluster()
        val gatewayGossip = Gossip(gatewayTransport, boardClusters, clockMs = { EPOCH_MS + net.nowMs() })
        gatewayTransport.onReceive { from, frame -> gatewayGossip.ingest(frame, from) }

        val trapped = NodeAgent(
            nodeId = victim,
            saltFingerprint = SALT,
            addressZone = "sector-7-roof",
            transport = victimTransport,
            clockMs = { EPOCH_MS + net.nowMs() },
        )
        val nearby = NodeAgent(
            nodeId = neighbour,
            saltFingerprint = SALT,
            addressZone = "floor-2-east",
            transport = neighbourTransport,
            clockMs = { EPOCH_MS + net.nowMs() },
        )

        say("GROUND-ZERO MESH — simulation")
        say("topology  A(victim) — B(relay) — C(gateway), D(neighbour) — B")
        say("A and C cannot hear each other. Everything C holds came through B.")
        say()

        say("--- Stage 0: the button, t=0 ---")
        val sos = trapped.raiseSos(Severity.DROWNING_IMMINENT)
        net.runUntilIdle()
        say("A broadcast at score ${"%.2f".format(sos.dangerScore)}, " +
            "tier ${sos.tier}, flags ${SensoryFlags.toHex(sos.flags)} " +
            "(${SensoryFlags.describe(sos.flags).joinToString(", ")})")
        say("nothing was inferred first; the classifier had not run")
        say()

        say("--- Stages 1-2: the window, and the Math Engine ---")
        val window = SensoryWindow(
            audioWater = 0.95,
            imuPinned = 0.88,
            ambientLight = 0.04,
            audioStructural = 0.4,
        )
        val vector = SlmFeatureVector.from(window)
        val engine = MathEngine()
        val signal = engine.project(vector, accelMagnitude = 0.7)
        trapped.senseVector(vector, accelMagnitude = 0.7)
        say("v_SLM     ${vector.toList().joinToString(" ") { "%.2f".format(it) }}")
        say("Signal_t  %.3f  (W . v_SLM + w_IMU . a_mag)".format(signal))
        say("why       ${engine.explain(vector, accelMagnitude = 0.7)}")
        say("EMA       alpha = 0.35, score now %.3f".format(trapped.explain().score))
        say()

        say("--- Stage 3: the enriched re-broadcast ---")
        net.advance(1_000)
        val enriched = trapped.completeSensoryWindow(window)
        net.runUntilIdle()
        if (enriched != null) {
            say("A re-broadcast on the SAME incident timestamp (${enriched.timestamp})")
            say("flags     ${SensoryFlags.toHex(enriched.flags)} " +
                "(${SensoryFlags.describe(enriched.flags).joinToString(", ")})")
            say("summary   ${enriched.slmSummary}")
            say("v_SLM aboard: ${enriched.featureVector != null}, " +
                "frame ${org.groundzero.mesh.propagation.CompactCodec.frameSize(enriched)} bytes " +
                "of ${org.groundzero.mesh.propagation.CompactCodec.LORA_MAX_FRAME}")
        }
        say()

        say("--- A second casualty, one floor down ---")
        nearby.raiseSos(Severity.STRUCTURAL_ENTRAPMENT)
        net.advance(500)
        nearby.completeSensoryWindow(SensoryWindow(imuPinned = 0.7, ambientLight = 0.1))
        net.runUntilIdle()
        say("D reported structural entrapment from floor-2-east")
        say()

        say("--- L2: asymmetric trust ---")
        val before = boardClusters.trustOf(relay)
        val incidentKey = boardClusters.clusters().first { it.origin == victim }.key
        boardClusters.ingest(
            org.groundzero.mesh.propagation.Envelope(
                nodeId = victim,
                saltFingerprint = SALT,
                addressZone = "sector-7-roof",
                tier = org.groundzero.mesh.propagation.EpistemologyTier.SABDA,
                severity = Severity.OTHER,
                dangerScore = 0.1,
                timestamp = incidentKey.substringAfter('@').toLong(),
                hops = 2,
            ),
            relay,
            EPOCH_MS + net.nowMs(),
        )
        say("B relayed a contradicting severity for A's incident")
        say("trust(B)  %.3f -> %.3f  (gain +0.05, loss -0.35)"
            .format(before, boardClusters.trustOf(relay)))
        say("severity  still ${boardClusters.clusters().first { it.origin == victim }.severity} " +
            "— never walked back to a calmer claim")
        say()

        say("--- L3: the responder board ---")
        val board = ResponderRanking.rank(boardClusters.clusters(), EPOCH_MS + net.nowMs())
        for ((i, entry) in board.withIndex()) {
            say("${i + 1}. ${entry.cluster.origin} ${entry.cluster.severity} " +
                "zone=${entry.cluster.zone} tier=${entry.cluster.tier} " +
                "flags=${SensoryFlags.toHex(entry.cluster.flags)} " +
                "dispatchable=${entry.withinBudget}")
            for (reason in entry.reasons) say("     - $reason")
        }
        say()

        say("--- L3: the digital twin ---")
        val twin = DigitalTwin.snapshot(board, EPOCH_MS + net.nowMs())
        for (node in twin.nodes) {
            say("${node.origin} ${node.floor.label} " +
                "pos(%.1f, %.1f, %.1f) placed=${node.placed} risk=%.2f"
                    .format(node.position.x, node.position.y, node.position.z, node.risk))
        }
        say("links: ${twin.links.size} carried-by edges. " +
            "Positions are schematic — derived from the zone tag, never measured.")
        say()

        say("--- Radmin advisory (deterministic stand-in for the 8B model) ---")
        say(RadminLlmSummarizer().summarise(board, twin))

        return Report(log.toString(), toJson(board, twin))
    }

    /**
     * The twin as JSON for `docs/simulation_dashboard.html`.
     *
     * Hand-rolled because `core` carries no third-party runtime dependency and this is the
     * only place in the module that emits JSON for something other than the wire.
     */
    private fun toJson(board: List<RankedIncident>, twin: TwinSnapshot): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val nodes = twin.nodes.joinToString(",\n") { node ->
            val ranked = board.first { it.cluster.key == node.key }
            """    {
      "key": "${esc(node.key)}",
      "origin": "${node.origin.canonical()}",
      "zone": "${esc(node.zone)}",
      "floor": ${node.floor.index},
      "floorLabel": "${esc(node.floor.label)}",
      "placed": ${node.placed},
      "position": { "x": ${"%.3f".format(node.position.x)}, "y": ${"%.3f".format(node.position.y)}, "z": ${"%.3f".format(node.position.z)} },
      "severity": "${node.severity.name}",
      "risk": ${"%.4f".format(node.risk)},
      "flags": ${node.flags.toInt() and 0xFF},
      "flagsHex": "${SensoryFlags.toHex(node.flags)}",
      "evidence": [${node.evidence.joinToString(",") { "\"${esc(it)}\"" }}],
      "corroborators": ${node.corroborators},
      "firstHandHeld": ${node.firstHandHeld},
      "ageMs": ${node.ageMs},
      "standing": "${ranked.standing.name}",
      "dispatchable": ${ranked.withinBudget},
      "priority": ${"%.4f".format(ranked.priority)},
      "reasons": [${ranked.reasons.joinToString(",") { "\"${esc(it)}\"" }}],
      "vector": [${ranked.cluster.featureVector?.toList()?.joinToString(",") { "%.4f".format(it) } ?: ""}]
    }"""
        }
        val links = twin.links.joinToString(",\n") {
            """    { "carrier": "${it.carrier.canonical()}", "incidentKey": "${esc(it.incidentKey)}" }"""
        }
        val advisory = esc(RadminLlmSummarizer().summarise(board, twin)).replace("\n", "\\n")
        return """{
  "nowMs": ${twin.nowMs},
  "schematic": true,
  "nodes": [
$nodes
  ],
  "links": [
$links
  ],
  "advisory": "$advisory"
}
"""
    }
}
