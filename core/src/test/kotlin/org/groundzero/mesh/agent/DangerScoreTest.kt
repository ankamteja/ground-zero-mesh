package org.groundzero.mesh.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DangerScoreTest {

    @Test
    fun startsCalm() {
        assertEquals(AgentState.CALM, DangerScore().state())
    }

    @Test
    fun sustainedHighSignalReachesAlarm() {
        val d = DangerScore()
        repeat(10) { d.observe(1.0) }
        assertEquals(AgentState.ALARM, d.state())
        assertTrue(d.score > d.baseline, "score should lead the slow baseline")
    }

    @Test
    fun climbsThroughWatchBeforeAlarm() {
        val d = DangerScore(alpha = 0.4, watchThreshold = 0.35, alarmThreshold = 0.70)
        repeat(3) { d.observe(0.5) }
        assertEquals(AgentState.WATCH, d.state())
        repeat(20) { d.observe(0.5) }
        assertEquals(AgentState.WATCH, d.state(), "EMA of 0.5 must never reach alarm")
    }

    @Test
    fun decaysBackToCalm() {
        val d = DangerScore()
        repeat(10) { d.observe(1.0) }
        repeat(20) { d.observe(0.0) }
        assertEquals(AgentState.CALM, d.state())
    }

    @Test
    fun explanationNamesThresholdsAndSignal() {
        val d = DangerScore()
        repeat(10) { d.observe(1.0) }
        val ex = d.explain()
        assertEquals(AgentState.ALARM, ex.state)
        assertTrue(ex.reason.contains("alarm"))
        assertTrue(ex.reason.contains("last signal"))
        assertEquals(1.0, ex.lastSignal)
    }
}
