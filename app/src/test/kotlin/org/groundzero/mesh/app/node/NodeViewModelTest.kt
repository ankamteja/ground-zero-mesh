package org.groundzero.mesh.app.node

import org.groundzero.mesh.agent.AgentState
import org.groundzero.mesh.propagation.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeViewModelTest {

    private val vm = NodeViewModel(now = { 42L })

    @Test
    fun startsCalmWithNoSos() {
        assertEquals(AgentState.CALM, vm.currentExplanation().state)
        assertEquals(null, vm.lastSos)
    }

    @Test
    fun raisingSosRecordsSeverityAndDrivesTheScore() {
        vm.selectSeverity(Severity.DROWNING_IMMINENT)
        repeat(6) { vm.raiseSos() }
        assertNotNull(vm.lastSos)
        assertEquals(Severity.DROWNING_IMMINENT, vm.lastSos!!.severity)
        assertEquals(42L, vm.lastSos!!.atSeconds)
        assertEquals(AgentState.ALARM, vm.currentExplanation().state)
    }

    @Test
    fun explanationStaysHumanReadable() {
        repeat(6) { vm.raiseSos() }
        val reason = vm.currentExplanation().reason
        assertTrue(reason.contains("alarm"))
    }

    @Test
    fun roleSwitchIsRuntime() {
        vm.selectRole(MeshRole.GATEWAY)
        assertEquals(MeshRole.GATEWAY, vm.role)
    }
}
