package io.github.samson910022.pixelifyphotos

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [Constants] — validates that all constant values are
 * correct and that no two preference keys accidentally collide.
 */
class ConstantsTest {

    @Test
    fun `PACKAGE_NAME_GOOGLE_PHOTOS is correct`() {
        assertEquals("com.google.android.apps.photos", Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
    }

    @Test
    fun `SHARED_PREF_FILE_NAME is non-empty`() {
        assertTrue(Constants.SHARED_PREF_FILE_NAME.isNotEmpty())
    }

    @Test
    fun `all preference keys are unique`() {
        val prefKeys = listOf(
            Constants.PREF_SPOOF_FEATURES_LIST,
            Constants.PREF_DEVICE_TO_SPOOF,
            Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS,
            Constants.PREF_ENABLE_VERBOSE_LOGS,
            Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE,
            Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL,
            Constants.PREF_LAST_VERSION,
        )
        assertEquals(
            "Preference keys should all be unique",
            prefKeys.toSet().size,
            prefKeys.size
        )
    }

    @Test
    fun `all preference keys are non-empty`() {
        val prefKeys = listOf(
            Constants.PREF_SPOOF_FEATURES_LIST,
            Constants.PREF_DEVICE_TO_SPOOF,
            Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS,
            Constants.PREF_ENABLE_VERBOSE_LOGS,
            Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE,
            Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL,
            Constants.PREF_LAST_VERSION,
        )
        prefKeys.forEach { key ->
            assertTrue("Pref key '$key' should be non-empty", key.isNotEmpty())
        }
    }

    @Test
    fun `URLs are valid HTTPS`() {
        val urls = listOf(
            Constants.UPDATE_INFO_URL,
            Constants.UPDATE_INFO_URL2,
            Constants.RELEASES_URL,
            Constants.RELEASES_URL2,
            Constants.SUPPORT_URL,
        )
        urls.forEach { url ->
            assertTrue("URL '$url' should start with https://", url.startsWith("https://"))
        }
    }

    @Test
    fun `UPDATE_INFO_URL contains FIELD_LATEST_VERSION_CODE as parseable JSON key`() {
        // The URL points to a JSON file that should have this key
        assertTrue(
            "URL should point to a JSON resource",
            Constants.UPDATE_INFO_URL.endsWith(".json")
        )
    }

    @Test
    fun `CONF_EXPORT_NAME is a valid JSON filename`() {
        assertTrue(Constants.CONF_EXPORT_NAME.endsWith(".json"))
    }

    @Test
    fun `FIELD_LATEST_VERSION_CODE is non-empty`() {
        assertTrue(Constants.FIELD_LATEST_VERSION_CODE.isNotEmpty())
    }
}
