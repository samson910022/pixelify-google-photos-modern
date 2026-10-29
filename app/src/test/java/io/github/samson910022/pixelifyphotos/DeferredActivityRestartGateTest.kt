package io.github.samson910022.pixelifyphotos

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

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

    /**
     * Integration regression test requested by maintainer samson1357924:
     * "launch import picker -> save/recreate Activity -> service source/UI mode changes -> restart remains deferred -> picker result delivered to recreated registry -> flag clears -> deferred restart executes once"
     */
    @Test
    fun `launch import picker - save and recreate Activity - service source or UI mode changes - restart remains deferred - picker result delivered to recreated registry - flag clears - deferred restart executes once`() {
        val bundle = createMockBundle()

        // 1. Activity 1 launches import picker (configImportInFlight = true).
        val activity1 = SimulatedActivity()
        assertFalse(activity1.configImportInFlight)
        activity1.launchImportPicker()
        assertTrue(activity1.configImportInFlight)

        // 2. onSaveInstanceState saves STATE_CONFIG_IMPORT_IN_FLIGHT to bundle.
        activity1.onSaveInstanceState(bundle)
        assertTrue(bundle.getBoolean(STATE_CONFIG_IMPORT_IN_FLIGHT, false))

        // 3. Activity 2 recreates from saved state and restores configImportInFlight.
        val activity2 = SimulatedActivity(savedInstanceState = bundle)
        assertTrue(
            "configImportInFlight must be restored from saved state after activity recreation",
            activity2.configImportInFlight,
        )

        // 4. Service binding / UI mode change requests restart while picker is in flight:
        // restart is deferred (immediate restart is rejected).
        val immediateAllowed = activity2.requestRestart()
        assertFalse("immediate restart must be rejected while import is in flight", immediateAllowed)
        assertFalse(activity2.immediateRestartExecuted)

        // While import is still in flight, release attempts must keep the restart deferred.
        assertFalse(
            "restart must remain deferred while picker is still in flight",
            activity2.releaseDeferredRestart(),
        )
        assertEquals(0, activity2.deferredRestartExecutedCount)

        // 5. Picker result is delivered to recreated registry: configImportInFlight = false.
        activity2.deliverPickerResultToRegistry()
        assertFalse("configImportInFlight must clear when picker result arrives", activity2.configImportInFlight)

        // 6. releaseDeferredRestart executes the deferred restart exactly once.
        assertTrue("deferred restart must execute once picker result is delivered", activity2.releaseDeferredRestart())
        assertEquals(1, activity2.deferredRestartExecutedCount)

        assertFalse("deferred restart must not execute more than once", activity2.releaseDeferredRestart())
        assertEquals(1, activity2.deferredRestartExecutedCount)

        // 7. Contrast/assert that without restoring configImportInFlight from saved state,
        // requestRestart would incorrectly immediately fire before the picker result arrives.
        val unretrievedStateActivity = SimulatedActivity(savedInstanceState = bundle, restoreSavedState = false)
        assertFalse(unretrievedStateActivity.configImportInFlight)
        val prematureRestartAllowed = unretrievedStateActivity.requestRestart()
        assertTrue(
            "without restoring configImportInFlight from saved state, requestRestart would incorrectly immediately fire before picker result arrives",
            prematureRestartAllowed,
        )
        assertTrue(unretrievedStateActivity.immediateRestartExecuted)
    }

    private companion object {
        private const val STATE_CONFIG_IMPORT_IN_FLIGHT = "config_import_in_flight"

        fun createMockBundle(map: MutableMap<String, Any?> = mutableMapOf()): Bundle {
            val bundle = mock<Bundle>()
            whenever(bundle.putBoolean(any(), any())).thenAnswer { invocation ->
                map[invocation.getArgument<String>(0)] = invocation.getArgument<Boolean>(1)
                null
            }
            whenever(bundle.getBoolean(any(), any())).thenAnswer { invocation ->
                map[invocation.getArgument<String>(0)] as? Boolean ?: invocation.getArgument(1)
            }
            whenever(bundle.getBoolean(any())).thenAnswer { invocation ->
                map[invocation.getArgument<String>(0)] as? Boolean ?: false
            }
            return bundle
        }
    }

    /**
     * Simulated activity session modeling the ActivityMain restart gate and SAF import persistence lifecycle:
     * - Preserves [configImportInFlight] into saved instance state bundle.
     * - Restores [configImportInFlight] in onCreate when recreated.
     * - Defers restart when service binding or UI mode change triggers restart while import is in-flight.
     * - Releases deferred restart exactly once when the registry delivers the result.
     */
    private class SimulatedActivity(
        savedInstanceState: Bundle? = null,
        val hostActive: Boolean = true,
        restoreSavedState: Boolean = true,
    ) {
        val restartGate = DeferredActivityRestartGate()
        var configImportInFlight: Boolean = false
            private set
        var immediateRestartExecuted: Boolean = false
            private set
        var deferredRestartExecutedCount: Int = 0
            private set

        init {
            if (restoreSavedState) {
                configImportInFlight = savedInstanceState?.getBoolean(STATE_CONFIG_IMPORT_IN_FLIGHT, false) ?: false
            }
        }

        fun launchImportPicker() {
            configImportInFlight = true
        }

        fun onSaveInstanceState(outState: Bundle) {
            outState.putBoolean(STATE_CONFIG_IMPORT_IN_FLIGHT, configImportInFlight)
        }

        fun configExportPending(): Boolean = configImportInFlight

        fun requestRestart(): Boolean {
            val allowedImmediate = restartGate.requestRestart(
                configExportPending = configExportPending(),
                hostActive = hostActive,
            )
            if (allowedImmediate) {
                immediateRestartExecuted = true
            }
            return allowedImmediate
        }

        fun deliverPickerResultToRegistry() {
            configImportInFlight = false
        }

        fun releaseDeferredRestart(): Boolean {
            val released = restartGate.releaseDeferredRestart(
                configExportPending = configExportPending(),
                hostActive = hostActive,
            )
            if (released) {
                deferredRestartExecutedCount++
            }
            return released
        }
    }
}
