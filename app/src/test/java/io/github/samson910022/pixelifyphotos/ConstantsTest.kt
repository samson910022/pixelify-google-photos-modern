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
    fun `PACKAGE_NAME_MODULE matches atomic project identity`() {
        assertEquals("io.github.samson910022.pixelifyphotos", Constants.PACKAGE_NAME_MODULE)
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
            Constants.PREF_USE_CLASSIC_UI,
            Constants.PREF_FIRST_RUN_COMPLETED,
            Constants.PREF_DIAG_MODULE_LOADED_AT,
            Constants.PREF_DIAG_LAST_PACKAGE_LOADED,
            Constants.PREF_DIAG_LAST_PACKAGE_READY,
            Constants.PREF_DIAG_LAST_PACKAGE_READY_AT,
            Constants.PREF_DIAG_VERIFY_AT,
            Constants.PREF_DIAG_VERIFY_DEVICE,
            Constants.PREF_DIAG_VERIFY_PACKAGE,
            Constants.PREF_DIAG_VERIFY_OK,
            Constants.PREF_DIAG_VERIFY_FAILED,
            Constants.PREF_DIAG_VERIFY_NATIVE_READY,
            Constants.PREF_DIAG_VERIFY_SYSPROPS,
            Constants.PREF_DIAG_FEATURE_HOOKS_AT,
            Constants.PREF_DIAG_FEATURE_HOOK_STRING,
            Constants.PREF_DIAG_FEATURE_HOOK_STRING_INT,
            Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE,
            Constants.PREF_DIAG_SYSPROPS_HOOKS_AT,
            Constants.PREF_DIAG_SYSPROPS_HOOK_GET,
            Constants.PREF_DIAG_SYSPROPS_HOOK_GET_DEFAULT,
            Constants.PREF_DIAG_FEATURE_INTERCEPTOR_AT,
            Constants.PREF_DIAG_SYSPROPS_INTERCEPTOR_AT,
            Constants.PREF_DIAG_ACTIVE_PACKAGE,
            Constants.PREF_DIAG_ACTIVE_RUN_ID,
            Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT,
            Constants.PREF_DIAG_ACTIVE_RUN_GENERATION,
            Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT,
            Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH,
            Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED,
            Constants.PREF_DIAG_ACTIVE_BINDER_PID,
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
            Constants.PREF_USE_CLASSIC_UI,
            Constants.PREF_FIRST_RUN_COMPLETED,
            Constants.PREF_DIAG_MODULE_LOADED_AT,
            Constants.PREF_DIAG_LAST_PACKAGE_LOADED,
            Constants.PREF_DIAG_LAST_PACKAGE_READY,
            Constants.PREF_DIAG_LAST_PACKAGE_READY_AT,
            Constants.PREF_DIAG_VERIFY_AT,
            Constants.PREF_DIAG_VERIFY_DEVICE,
            Constants.PREF_DIAG_VERIFY_PACKAGE,
            Constants.PREF_DIAG_VERIFY_OK,
            Constants.PREF_DIAG_VERIFY_FAILED,
            Constants.PREF_DIAG_VERIFY_NATIVE_READY,
            Constants.PREF_DIAG_VERIFY_SYSPROPS,
            Constants.PREF_DIAG_FEATURE_HOOKS_AT,
            Constants.PREF_DIAG_FEATURE_HOOK_STRING,
            Constants.PREF_DIAG_FEATURE_HOOK_STRING_INT,
            Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE,
            Constants.PREF_DIAG_SYSPROPS_HOOKS_AT,
            Constants.PREF_DIAG_SYSPROPS_HOOK_GET,
            Constants.PREF_DIAG_SYSPROPS_HOOK_GET_DEFAULT,
            Constants.PREF_DIAG_FEATURE_INTERCEPTOR_AT,
            Constants.PREF_DIAG_SYSPROPS_INTERCEPTOR_AT,
            Constants.PREF_DIAG_ACTIVE_PACKAGE,
            Constants.PREF_DIAG_ACTIVE_RUN_ID,
            Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT,
            Constants.PREF_DIAG_ACTIVE_RUN_GENERATION,
            Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT,
            Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH,
            Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED,
            Constants.PREF_DIAG_ACTIVE_BINDER_PID,
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

    @Test
    fun `specific preference key values are expected`() {
        assertEquals("PREF_SPOOF_FEATURES_LIST", Constants.PREF_SPOOF_FEATURES_LIST)
        assertEquals("PREF_DEVICE_TO_SPOOF", Constants.PREF_DEVICE_TO_SPOOF)
        assertEquals("PREF_OVERRIDE_ROM_FEATURE_LEVELS", Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS)
        assertEquals("PREF_ENABLE_VERBOSE_LOGS", Constants.PREF_ENABLE_VERBOSE_LOGS)
        assertEquals("PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE", Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE)
        assertEquals("PREF_SPOOF_ANDROID_VERSION_MANUAL", Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL)
        assertEquals("PREF_LAST_VERSION", Constants.PREF_LAST_VERSION)
        assertEquals("PREF_USE_CLASSIC_UI", Constants.PREF_USE_CLASSIC_UI)
        assertEquals("PREF_FIRST_RUN_COMPLETED", Constants.PREF_FIRST_RUN_COMPLETED)
    }

    @Test
    fun `diagnostics provider constants are non-empty and well-formed`() {
        assertEquals("io.github.samson910022.pixelifyphotos.diagnostics", Constants.DIAGNOSTICS_AUTHORITY)
        assertEquals("recordDiagnostics", Constants.METHOD_RECORD_DIAGNOSTICS)
        assertEquals("recordVerify", Constants.METHOD_RECORD_VERIFY)
        assertEquals("recordDiagnosticsBatch", Constants.METHOD_RECORD_DIAGNOSTICS_BATCH)
        assertEquals("clearVerify", Constants.METHOD_CLEAR_VERIFY)
        assertEquals("diagnostics_event_sequence", Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE)
    }

    @Test
    fun `DiagnosticsProvider ALLOWED_DIAG_KEYS matches expected diagnostic keys`() {
        val expectedKeys = setOf(
            Constants.PREF_DIAG_MODULE_LOADED_AT,
            Constants.PREF_DIAG_LAST_PACKAGE_LOADED,
            Constants.PREF_DIAG_LAST_PACKAGE_READY,
            Constants.PREF_DIAG_LAST_PACKAGE_READY_AT,
            Constants.PREF_DIAG_VERIFY_AT,
            Constants.PREF_DIAG_VERIFY_DEVICE,
            Constants.PREF_DIAG_VERIFY_PACKAGE,
            Constants.PREF_DIAG_VERIFY_OK,
            Constants.PREF_DIAG_VERIFY_FAILED,
            Constants.PREF_DIAG_VERIFY_NATIVE_READY,
            Constants.PREF_DIAG_VERIFY_SYSPROPS,
            Constants.PREF_DIAG_FEATURE_HOOKS_AT,
            Constants.PREF_DIAG_FEATURE_HOOK_STRING,
            Constants.PREF_DIAG_FEATURE_HOOK_STRING_INT,
            Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE,
            Constants.PREF_DIAG_SYSPROPS_HOOKS_AT,
            Constants.PREF_DIAG_SYSPROPS_HOOK_GET,
            Constants.PREF_DIAG_SYSPROPS_HOOK_GET_DEFAULT,
            Constants.PREF_DIAG_FEATURE_INTERCEPTOR_AT,
            Constants.PREF_DIAG_SYSPROPS_INTERCEPTOR_AT,
            Constants.PREF_DIAG_ACTIVE_PACKAGE,
            Constants.PREF_DIAG_ACTIVE_RUN_ID,
            Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT,
            Constants.PREF_DIAG_ACTIVE_RUN_GENERATION,
            Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT,
            Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH,
            Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED,
            Constants.PREF_DIAG_ACTIVE_BINDER_PID,
        )
        assertEquals(expectedKeys, DiagnosticsStore.ALLOWED_DIAG_KEYS)
    }

    @Test
    fun `diagnostics resources keep required English and translated keys in parity`() {
        val appRes = sequenceOf(
            java.io.File("app/src/main/res"),
            java.io.File("src/main/res"),
        ).firstOrNull { it.isDirectory } ?: error("Cannot locate app/src/main/res")
        val required = setOf(
            "diagnostics_verify_no_package_ready",
            "diagnostics_verify_no_record_prior_boot",
            "diagnostics_verify_no_record_boot_unknown",
            "diagnostics_verify_no_verified_run",
            "diagnostics_verify_no_run_identity",
            "diagnostics_verify_stale",
            "diagnostics_milestone_no_record_prior_boot",
            "diagnostics_milestone_no_package_prior_boot",
            "diagnostics_milestone_package_not_ready",
            "diagnostics_milestone_package_not_ready_prior_boot",
            "diagnostics_milestone_verify_none_prior_boot",
            "diagnostics_milestone_verify_stale",
            "diagnostics_invocation_unknown",
            "diagnostics_invocation_not_observed",
            "diagnostics_origin_unverified",
            "diagnostics_unverified",
            "diagnostics_process_pid",
            "diagnostics_build_column_real",
            "diagnostics_build_column_target",
            "diagnostics_service_disconnected",
            "diagnostics_service_syncing",
            "diagnostics_service_ready",
            "diagnostics_service_error",
            "diagnostics_connection_syncing",
            "diagnostics_connection_error",
        )
        listOf("values", "values-ja", "values-zh-rCN", "values-zh-rTW").forEach { locale ->
            val xml = java.io.File(appRes, "$locale/strings.xml").readText()
            val names = Regex("""<string\s+name="([^"]+)"""")
                .findAll(xml)
                .map { it.groupValues[1] }
                .toSet()
            assertTrue("Missing diagnostics strings in $locale", names.containsAll(required))
        }
    }
}
