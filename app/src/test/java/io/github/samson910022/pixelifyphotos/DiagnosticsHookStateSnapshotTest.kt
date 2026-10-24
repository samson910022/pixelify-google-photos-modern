package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsHookStateSnapshotTest {
    @Test
    fun `HookState is derived from one immutable all snapshot`() {
        val backing = mutableMapOf<String, Any?>(
            Constants.PREF_DIAG_MODULE_LOADED_AT to 100L,
            Constants.PREF_DIAG_FEATURE_HOOKS_AT to 200L,
            Constants.PREF_DIAG_FEATURE_HOOK_STRING to false,
        )
        var reads = 0

        val state = DiagnosticsHookStateSnapshot.capture(
            readAll = {
                reads++
                val captured = backing.toMap()
                backing[Constants.PREF_DIAG_MODULE_LOADED_AT] = 101L
                backing[Constants.PREF_DIAG_FEATURE_HOOK_STRING] = true
                captured
            },
            currentBootCount = 7,
            currentBootEpoch = "current-epoch",
        )

        assertEquals(1, reads)
        assertEquals(100L, state.moduleLoadedAt)
        assertEquals(200L, state.featureHooksAt)
        assertEquals(false, state.featureHookHasSystemFeature)
        assertEquals(7, state.currentBootCount)
    }

    @Test
    fun `wrong SharedPreferences type is unavailable instead of readable empty telemetry`() {
        val state = DiagnosticsHookStatePolicy.capture(
            profileSettingsAvailable = true,
            telemetryPreferencesAvailable = true,
            readTelemetry = {
                DiagnosticsHookStateSnapshot.capture(
                    readAll = { mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to "true") },
                    currentBootCount = null,
                    currentBootEpoch = null,
                )
            },
        )

        assertFalse(state.diagnosticsAvailable)
        assertTrue(state.profileSettingsAvailable)
        assertFalse(state.anyRecorded)
    }
}
