package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ensures the changelog marker is authoritative-only and never mutates cold local settings. */
class AppVersionMarkerPolicyTest {
    private val versionCode = 42

    @Test
    fun `cold local marker is neither shown nor mutated`() {
        val local = MemorySharedPreferences(
            mapOf(Constants.PREF_LAST_VERSION to versionCode - 1),
        )

        assertFalse(AppVersionMarkerPolicy.shouldRecordVersion(
            local,
            versionMarkerAuthoritative = false,
            versionCode = versionCode,
        ))
        assertEquals(
            "local preferences must stay untouched",
            versionCode - 1,
            local.getInt(Constants.PREF_LAST_VERSION, 0),
        )
        assertEquals("policy must not open an editor", 0, local.editCount)
    }

    @Test
    fun `authoritative remote marker is recorded once and not repeated after the bind`() {
        val remote = MemorySharedPreferences(
            mapOf(Constants.PREF_LAST_VERSION to versionCode - 1),
        )

        assertTrue(AppVersionMarkerPolicy.shouldRecordVersion(
            remote,
            versionMarkerAuthoritative = true,
            versionCode = versionCode,
        ))
        remote.edit().putInt(Constants.PREF_LAST_VERSION, versionCode).apply()

        assertFalse(AppVersionMarkerPolicy.shouldRecordVersion(
            remote,
            versionMarkerAuthoritative = true,
            versionCode = versionCode,
        ))
    }

    @Test
    fun `missing marker is an upgrade only for the authoritative source`() {
        val fresh = MemorySharedPreferences()

        assertFalse(AppVersionMarkerPolicy.shouldRecordVersion(
            fresh,
            versionMarkerAuthoritative = false,
            versionCode = versionCode,
        ))
        assertTrue(AppVersionMarkerPolicy.shouldRecordVersion(
            fresh,
            versionMarkerAuthoritative = true,
            versionCode = versionCode,
        ))
    }

    @Test
    fun `null preferences never show the changelog`() {
        assertFalse(AppVersionMarkerPolicy.shouldRecordVersion(
            preferences = null,
            versionMarkerAuthoritative = true,
            versionCode = versionCode,
        ))
        assertFalse(AppVersionMarkerPolicy.shouldRecordVersion(
            preferences = null,
            versionMarkerAuthoritative = false,
            versionCode = versionCode,
        ))
    }

    @Test
    fun `first run initializes marker so changelog does not trigger subsequently on same version`() {
        val prefs = MemorySharedPreferences()
        // On first run, firstRunDone is false, so ActivityMain records versionCode into PREF_LAST_VERSION
        prefs.edit().putInt(Constants.PREF_LAST_VERSION, versionCode).apply()

        // Subsequent launch: firstRunDone is now true, shouldRecordVersion returns false
        assertFalse(
            AppVersionMarkerPolicy.shouldRecordVersion(
                preferences = prefs,
                versionMarkerAuthoritative = true,
                versionCode = versionCode,
            ),
        )
    }

    @Test
    fun `upgrading existing user with completed onboarding triggers changelog`() {
        val prefs = MemorySharedPreferences(
            mapOf(
                Constants.PREF_FIRST_RUN_COMPLETED to true,
                Constants.PREF_LAST_VERSION to versionCode - 1,
            ),
        )

        assertTrue(
            AppVersionMarkerPolicy.shouldRecordVersion(
                preferences = prefs,
                versionMarkerAuthoritative = true,
                versionCode = versionCode,
            ),
        )
    }
}
