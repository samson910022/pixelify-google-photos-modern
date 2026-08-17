package io.github.samson910022.pixelifyphotos

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for preference defaults, onboarding flag lifecycle, and UI mode switching logic.
 */
class PreferenceFlowTest {

    /**
     * In-memory mock SharedPreferences for unit testing state transitions.
     */
    private class InMemorySharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        fun getBoolean(key: String, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue

        fun getString(key: String, defValue: String?): String? =
            map[key] as? String ?: defValue

        fun getInt(key: String, defValue: Int): Int =
            map[key] as? Int ?: defValue

        fun putBoolean(key: String, value: Boolean) {
            map[key] = value
        }

        fun putString(key: String, value: String?) {
            map[key] = value
        }

        fun putInt(key: String, value: Int) {
            map[key] = value
        }
    }

    @Test
    fun `first-run onboarding defaults to false and flips to true upon completion`() {
        val prefs = InMemorySharedPreferences()
        assertFalse(
            "New installation should have PREF_FIRST_RUN_COMPLETED = false",
            prefs.getBoolean(Constants.PREF_FIRST_RUN_COMPLETED, false),
        )

        // Simulate user dismissing or completing onboarding
        prefs.putBoolean(Constants.PREF_FIRST_RUN_COMPLETED, true)
        assertTrue(
            "Completed onboarding should persist PREF_FIRST_RUN_COMPLETED = true",
            prefs.getBoolean(Constants.PREF_FIRST_RUN_COMPLETED, false),
        )
    }

    @Test
    fun `UI mode toggle toggles PREF_USE_CLASSIC_UI state cleanly`() {
        val prefs = InMemorySharedPreferences()
        // Default is Modern UI (PREF_USE_CLASSIC_UI == false)
        val defaultClassic = prefs.getBoolean(Constants.PREF_USE_CLASSIC_UI, false)
        assertFalse("Default UI mode should be Modern (false)", defaultClassic)

        // Toggle to Classic UI
        prefs.putBoolean(Constants.PREF_USE_CLASSIC_UI, !defaultClassic)
        assertTrue("Toggled UI mode should be Classic (true)", prefs.getBoolean(Constants.PREF_USE_CLASSIC_UI, false))

        // Toggle back to Modern UI
        prefs.putBoolean(Constants.PREF_USE_CLASSIC_UI, false)
        assertFalse("Toggled back UI mode should be Modern (false)", prefs.getBoolean(Constants.PREF_USE_CLASSIC_UI, false))
    }

    @Test
    fun `default device is Pixel XL with Pixel 2016 honest feature level`() {
        assertEquals("Pixel XL", DeviceProps.defaultDeviceName)
        val features = DeviceProps.defaultFeatures.map { it.displayName }.toSet()
        assertTrue("Default features should include Pixel 2016", features.contains("Pixel 2016"))
        assertFalse("Default features should not include Pixel 2025 by default", features.contains("Pixel 2025"))
    }
}
