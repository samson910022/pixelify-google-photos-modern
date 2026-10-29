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

    @Test
    fun `restart requested while stopped waits for onStart`() {
        val gate = DeferredActivityRestartGate()

        assertFalse(gate.requestRestart(configExportPending = false, hostActive = false))
        assertFalse(gate.completeRequest(
            "request-one",
            "request-one",
            configExportPending = false,
            hostActive = false,
        ))
        assertTrue(gate.takeResumedRestart(configExportPending = false))
        assertFalse("a consumed restart must not fire twice", gate.takeResumedRestart(configExportPending = false))
    }

    @Test
    fun `deferred restart stays deferred while an export is still pending`() {
        val gate = DeferredActivityRestartGate()

        assertFalse(gate.requestRestart(configExportPending = true, hostActive = true))
        assertFalse(gate.takeResumedRestart(configExportPending = true))
        assertFalse(gate.completeRequest(
            "request-one",
            "request-one",
            configExportPending = false,
            hostActive = false,
        ))
        assertTrue(gate.takeResumedRestart(configExportPending = false))
    }

    @Test
    fun `completion callback releases a restart deferred for an in-flight import`() {
        val gate = DeferredActivityRestartGate()

        assertFalse(gate.requestRestart(configExportPending = true))
        assertFalse(
            "a still-pending operation must keep the restart deferred",
            gate.releaseDeferredRestart(configExportPending = true),
        )
        assertTrue(gate.releaseDeferredRestart(configExportPending = false))
        assertFalse(
            "a released restart must not fire twice",
            gate.releaseDeferredRestart(configExportPending = false),
        )
    }

    @Test
    fun `completion callback leaves a deferred restart for onStart when the host is stopped`() {
        val gate = DeferredActivityRestartGate()

        assertFalse(gate.requestRestart(configExportPending = true, hostActive = false))
        assertFalse(gate.releaseDeferredRestart(configExportPending = false, hostActive = false))
        assertTrue(gate.takeResumedRestart(configExportPending = false))
    }

    @Test
    fun `completion callback does not start a restart that was never requested`() {
        val gate = DeferredActivityRestartGate()

        assertFalse(gate.releaseDeferredRestart(configExportPending = false))
    }
}
