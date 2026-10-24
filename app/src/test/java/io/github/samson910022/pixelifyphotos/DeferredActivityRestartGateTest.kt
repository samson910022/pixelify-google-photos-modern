package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredActivityRestartGateTest {
    @Test
    fun `restart requested while export is pending runs once after matching completion`() {
        val gate = DeferredActivityRestartGate()

        assertFalse(gate.requestRestart(configExportPending = true))
        assertTrue(gate.completeRequest("request-one", "request-one", configExportPending = false))
        assertFalse(gate.completeRequest("request-one", "request-one", configExportPending = false))
    }

    @Test
    fun `stale callback does not release restart while newer request is pending`() {
        val gate = DeferredActivityRestartGate()

        assertFalse(gate.requestRestart(configExportPending = true))
        assertFalse(gate.completeRequest("request-one", null, configExportPending = true))
        assertFalse(gate.completeRequest("request-two", "request-two", configExportPending = true))
        assertTrue(gate.completeRequest("request-two", "request-two", configExportPending = false))
    }

    @Test
    fun `restart remains immediate when no export is pending`() {
        val gate = DeferredActivityRestartGate()

        assertTrue(gate.requestRestart(configExportPending = false))
        assertFalse(gate.completeRequest("request-one", "request-one", configExportPending = false))
    }
}
