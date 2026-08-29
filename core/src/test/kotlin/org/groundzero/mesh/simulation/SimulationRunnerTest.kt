package org.groundzero.mesh.simulation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The simulation is a demo artefact, but it runs shipped `core` code, so a break in it is a
 * break in the stack. Cheap to assert; expensive to discover on a projector.
 */
class SimulationRunnerTest {

    private val report = SimulationRunner.run()

    @Test
    fun `the run covers every stage of the cascade`() {
        listOf("Stage 0", "Math Engine", "Stage 3", "asymmetric trust", "responder board", "digital twin")
            .forEach { assertTrue(report.text.contains(it), "missing section: $it") }
    }

    @Test
    fun `the enriched frame still fits a LoRa payload`() {
        assertTrue(report.text.contains("v_SLM aboard: true"))
        val frameLine = report.text.lines().first { it.startsWith("v_SLM aboard") }
        val bytes = Regex("""frame (\d+) bytes""").find(frameLine)!!.groupValues[1].toInt()
        assertTrue(bytes <= 233, "enriched frame $bytes exceeds the LoRa payload")
    }

    @Test
    fun `the json snapshot is well formed enough for the dashboard`() {
        listOf("\"nodes\"", "\"links\"", "\"advisory\"", "\"flagsHex\"", "\"placed\"")
            .forEach { assertTrue(report.json.contains(it), "missing json field: $it") }
        assertTrue(report.json.trim().startsWith("{") && report.json.trim().endsWith("}"))
    }

    @Test
    fun `the advisory never claims to have changed the ordering`() {
        assertTrue(report.text.contains("Advisory only"))
    }
}
