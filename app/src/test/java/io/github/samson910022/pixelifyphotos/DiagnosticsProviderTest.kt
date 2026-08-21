package io.github.samson910022.pixelifyphotos

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [DiagnosticsProvider] key validation and security boundaries.
 */
class DiagnosticsProviderTest {

    @Test
    fun `ALLOWED_DIAG_KEYS only contains PREF_DIAG keys`() {
        DiagnosticsProvider.ALLOWED_DIAG_KEYS.forEach { key ->
            assertTrue("Key '$key' must start with PREF_DIAG_", key.startsWith("PREF_DIAG_"))
        }
    }

    @Test
    fun `sensitive configuration keys are strictly excluded from ALLOWED_DIAG_KEYS`() {
        val forbiddenKeys = listOf(
            Constants.PREF_DEVICE_TO_SPOOF,
            Constants.PREF_SPOOF_FEATURES_LIST,
            Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS,
            Constants.PREF_ENABLE_VERBOSE_LOGS,
            Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE,
            Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL,
            Constants.PREF_LAST_VERSION,
            Constants.PREF_USE_CLASSIC_UI,
            Constants.PREF_FIRST_RUN_COMPLETED,
            "custom_arbitrary_key",
            "password",
            "token"
        )

        forbiddenKeys.forEach { key ->
            assertFalse(
                "Forbidden key '$key' must never be writable via DiagnosticsProvider",
                DiagnosticsProvider.ALLOWED_DIAG_KEYS.contains(key)
            )
        }
    }

    @Test
    fun `DiagnosticsReporter resolveContext handles null safely`() {
        val resolved = DiagnosticsReporter.resolveContext(null)
        // In JVM unit tests without ActivityThread, resolveContext should return null without crashing
        assertNull(resolved)
    }
}
