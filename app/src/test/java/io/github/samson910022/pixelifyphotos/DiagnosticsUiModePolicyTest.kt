package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsUiModePolicyTest {
    @Test
    fun `throwing classic UI preference uses non-classic unavailable fallback`() {
        val result = DiagnosticsUiModePolicy.readClassicUiPreference(
            preferencesAvailable = true,
            readPreference = { throw IllegalStateException("preference read failed") },
        )

        assertFalse(result.useClassicUi)
        assertFalse(result.available)
    }

    @Test
    fun `available classic UI preference preserves selected mode`() {
        val result = DiagnosticsUiModePolicy.readClassicUiPreference(
            preferencesAvailable = true,
            readPreference = { true },
        )

        assertTrue(result.useClassicUi)
        assertTrue(result.available)
    }
}
