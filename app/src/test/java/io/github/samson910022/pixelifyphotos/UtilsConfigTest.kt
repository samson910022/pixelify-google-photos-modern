package io.github.samson910022.pixelifyphotos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the JSON config serialization/deserialization logic
 * used by [Utils.writeConfigFile] and [Utils.readConfigFile].
 *
 * JSON export policy and import parsing are tested directly; this keeps the
 * security allowlist exercised without needing an Android ContentResolver.
 *
 * This validates:
 * - explicit user-settings export allowlist
 * - PREF_SPOOF_FEATURES_LIST stored as JSONArray
 * - Round-trip: write → read preserves all expected keys
 * - Malformed JSON handling
 * - Missing/extra keys in JSON
 */
class UtilsConfigTest {

    // =========================================================================
    // Config export logic: the same policy called by both save and share flows
    // =========================================================================

    private fun buildExportJson(allPrefs: Map<String, Any?>): JSONObject {
        return ConfigExportPolicy.toJson(MemorySharedPreferences(allPrefs))
    }

    /**
     * Simulates the import logic from Utils.readConfigFile.
     * Reads a JSONObject and returns the extracted preference values.
     */
    private fun parseImportJson(jsonObject: JSONObject): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()

        // Read features list
        jsonObject.optJSONArray(Constants.PREF_SPOOF_FEATURES_LIST)?.let { jsonArray ->
            val list = ArrayList<String>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray[i].toString())
            }
            result[Constants.PREF_SPOOF_FEATURES_LIST] = list.toSet()
        }

        // Read device name
        jsonObject.optString(Constants.PREF_DEVICE_TO_SPOOF, "")?.let {
            if (it.isNotEmpty()) result[Constants.PREF_DEVICE_TO_SPOOF] = it
        }

        // Read boolean prefs only when explicitly present and correctly typed.
        listOf(
            Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS,
            Constants.PREF_ENABLE_VERBOSE_LOGS,
            Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE,
            Constants.PREF_USE_CLASSIC_UI,
        ).forEach { key ->
            val value = jsonObject.opt(key)
            if (value is Boolean) result[key] = value
        }

        // Read string pref
        jsonObject.optString(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL, "")?.let {
            if (it.isNotEmpty()) result[Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL] = it
        }

        return result
    }

    // =========================================================================
    // Export: explicit allowlist
    // =========================================================================

    @Test
    fun `export excludes PREF_LAST_VERSION`() {
        val prefs = mapOf(
            Constants.PREF_LAST_VERSION to 5,
            Constants.PREF_DEVICE_TO_SPOOF to "Pixel 5",
        )
        val json = buildExportJson(prefs)
        assertFalse(json.has(Constants.PREF_LAST_VERSION))
    }

    @Test
    fun `export includes PREF_DEVICE_TO_SPOOF`() {
        val prefs = mapOf(
            Constants.PREF_DEVICE_TO_SPOOF to "Pixel 5",
        )
        val json = buildExportJson(prefs)
        assertEquals("Pixel 5", json.getString(Constants.PREF_DEVICE_TO_SPOOF))
    }

    @Test
    fun `export stores PREF_SPOOF_FEATURES_LIST as JSONArray`() {
        val features = setOf("Pixel 2020", "Pixel 2019")
        val prefs = mapOf(
            Constants.PREF_SPOOF_FEATURES_LIST to features,
        )
        val json = buildExportJson(prefs)
        assertTrue(json.has(Constants.PREF_SPOOF_FEATURES_LIST))
        val jsonArray = json.getJSONArray(Constants.PREF_SPOOF_FEATURES_LIST)
        assertEquals(2, jsonArray.length())
    }

    @Test
    fun `export preserves explicitly allowlisted settings`() {
        val prefs = mapOf(
            Constants.PREF_LAST_VERSION to 5,  // should be excluded
            Constants.PREF_DEVICE_TO_SPOOF to "Pixel 5",
            Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS to false,
            Constants.PREF_ENABLE_VERBOSE_LOGS to true,
            Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE to false,
            Constants.PREF_USE_CLASSIC_UI to true,
            Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL to "Android 14",
        )
        val json = buildExportJson(prefs)

        assertFalse(json.has(Constants.PREF_LAST_VERSION))
        assertEquals("Pixel 5", json.getString(Constants.PREF_DEVICE_TO_SPOOF))
        assertFalse(json.getBoolean(Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS))
        assertTrue(json.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS))
        assertFalse(json.getBoolean(Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE))
        assertTrue(json.getBoolean(Constants.PREF_USE_CLASSIC_UI))
        assertEquals("Android 14", json.getString(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL))
    }

    // =========================================================================
    // Round-trip: export → import
    // =========================================================================

    @Test
    fun `round-trip preserves device name`() {
        val prefs = mapOf(
            Constants.PREF_DEVICE_TO_SPOOF to "Pixel 9 Pro XL",
        )
        val json = buildExportJson(prefs)
        val imported = parseImportJson(json)
        assertEquals("Pixel 9 Pro XL", imported[Constants.PREF_DEVICE_TO_SPOOF])
    }

    @Test
    fun `round-trip preserves feature list`() {
        val features = setOf("Pixel 2020", "Pixel 2021", "Pixel 2022")
        val prefs = mapOf(
            Constants.PREF_SPOOF_FEATURES_LIST to features,
        )
        val json = buildExportJson(prefs)
        val imported = parseImportJson(json)
        @Suppress("UNCHECKED_CAST")
        val importedFeatures = imported[Constants.PREF_SPOOF_FEATURES_LIST] as Set<String>
        assertEquals(features, importedFeatures)
    }

    @Test
    fun `round-trip preserves all boolean preferences`() {
        val prefs = mapOf(
            Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS to false,
            Constants.PREF_ENABLE_VERBOSE_LOGS to true,
            Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE to true,
            Constants.PREF_USE_CLASSIC_UI to false,
        )
        val json = buildExportJson(prefs)
        val imported = parseImportJson(json)

        assertEquals(false, imported[Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS])
        assertEquals(true, imported[Constants.PREF_ENABLE_VERBOSE_LOGS])
        assertEquals(true, imported[Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE])
        assertEquals(false, imported[Constants.PREF_USE_CLASSIC_UI])
    }

    @Test
    fun `round-trip preserves android version manual`() {
        val prefs = mapOf(
            Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL to "Android 14",
        )
        val json = buildExportJson(prefs)
        val imported = parseImportJson(json)
        assertEquals("Android 14", imported[Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL])
    }

    // =========================================================================
    // Import: malformed / missing JSON
    // =========================================================================

    @Test
    fun `import handles empty JSONObject gracefully`() {
        val json = JSONObject()
        val imported = parseImportJson(json)
        // Missing keys must preserve existing preferences rather than invent defaults.
        assertFalse(imported.containsKey(Constants.PREF_DEVICE_TO_SPOOF))
        assertFalse(imported.containsKey(Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS))
        assertFalse(imported.containsKey(Constants.PREF_ENABLE_VERBOSE_LOGS))
        assertFalse(imported.containsKey(Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE))
    }

    @Test
    fun `import handles JSON with extra unknown keys`() {
        val json = JSONObject().apply {
            put("UNKNOWN_KEY", "value")
            put(Constants.PREF_DEVICE_TO_SPOOF, "Pixel 5")
        }
        val imported = parseImportJson(json)
        assertEquals("Pixel 5", imported[Constants.PREF_DEVICE_TO_SPOOF])
        // Unknown key should be silently ignored
        assertFalse(imported.containsKey("UNKNOWN_KEY"))
    }

    @Test
    fun `import handles malformed features list`() {
        val json = JSONObject().apply {
            put(Constants.PREF_SPOOF_FEATURES_LIST, "not an array")
        }
        // optJSONArray returns null for non-array, so features list is just absent
        val imported = parseImportJson(json)
        assertFalse(imported.containsKey(Constants.PREF_SPOOF_FEATURES_LIST))
    }

    @Test
    fun `import handles empty features array`() {
        val json = JSONObject().apply {
            put(Constants.PREF_SPOOF_FEATURES_LIST, JSONArray())
        }
        val imported = parseImportJson(json)
        @Suppress("UNCHECKED_CAST")
        val features = imported[Constants.PREF_SPOOF_FEATURES_LIST] as? Set<String>
        assertNotNull(features)
        assertTrue(features!!.isEmpty())
    }

    // =========================================================================
    // Export: empty preferences
    // =========================================================================

    @Test
    fun `export rejects readable prefs with no allowlisted settings`() {
        try {
            buildExportJson(emptyMap())
            fail("empty user configuration must not be exported")
        } catch (_: ConfigExportPolicy.EmptyConfigurationException) {
            // The Activity reports a localized empty-config message and starts no export flow.
        }
    }

    @Test
    fun `export of null values excludes null entries`() {
        val prefs = mapOf(
            Constants.PREF_DEVICE_TO_SPOOF to null,
            Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL to null,
            Constants.PREF_ENABLE_VERBOSE_LOGS to true,
        )
        val json = buildExportJson(prefs)
        assertFalse(json.has(Constants.PREF_DEVICE_TO_SPOOF))
        assertFalse(json.has(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL))
        assertTrue(json.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS))
    }

    @Test
    fun `export rejects a readable preference snapshot with a wrong allowlisted value type`() {
        val malformed = MemorySharedPreferences(
            mapOf(Constants.PREF_ENABLE_VERBOSE_LOGS to "true"),
        )
        try {
            ConfigExportPolicy.toJson(malformed)
            fail("wrong SharedPreferences value types must not serialize as an empty configuration")
        } catch (_: IllegalStateException) {
            // The Activity reports this as an unavailable export source.
        }
    }

    @Test
    fun `export contains user settings only and excludes diagnostics and internal preferences`() {
        val userSettings = mapOf(
            Constants.PREF_DEVICE_TO_SPOOF to "Pixel XL",
            Constants.PREF_SPOOF_FEATURES_LIST to setOf("Pixel 2020"),
            Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS to true,
            Constants.PREF_ENABLE_VERBOSE_LOGS to false,
            Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE to true,
            Constants.PREF_USE_CLASSIC_UI to true,
            Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL to "Android 15",
        )
        val internalValues = mapOf<String, Any?>(
            Constants.PREF_DIAG_BROADCAST_TOKEN to "secret-test-token",
            Constants.PREF_DIAG_ACTIVE_RUN_ID to "private-run-id",
            Constants.PREF_DIAG_ACTIVE_RUN_GENERATION to 72L,
            Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT to 3,
            Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH to "private-epoch",
            Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            Constants.PREF_DIAG_ACTIVE_BINDER_PID to 12345,
            Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT to 123456L,
            Constants.PREF_DIAG_MODULE_LOADED_AT to 123455L,
            Constants.PREF_DIAG_VERIFY_AT to 123457L,
            Constants.PREF_DIAG_VERIFY_DEVICE to "Pixel XL",
            Constants.PREF_DIAG_VERIFY_OK to true,
            Constants.PREF_DIAG_FEATURE_HOOKS_AT to 123458L,
            Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
            Constants.PREF_LAST_VERSION to 99,
            Constants.PREF_FIRST_RUN_COMPLETED to true,
            "PREF_DIAG_EVENT_SEQUENCE:internal" to 77L,
            "PREF_OFFLINE_SETTINGS_HANDOFF_PENDING" to true,
            "private_internal_pref" to "do-not-export",
        )

        val json = buildExportJson(userSettings + internalValues)

        assertEquals(ConfigExportPolicy.USER_SETTING_KEYS, json.keySet())
        assertEquals("Pixel XL", json.getString(Constants.PREF_DEVICE_TO_SPOOF))
        assertEquals(listOf("Pixel 2020"), (0 until json.getJSONArray(Constants.PREF_SPOOF_FEATURES_LIST).length())
            .map { json.getJSONArray(Constants.PREF_SPOOF_FEATURES_LIST).getString(it) })
        assertTrue(json.getBoolean(Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS))
        assertFalse(json.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS))
        assertTrue(json.getBoolean(Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE))
        assertTrue(json.getBoolean(Constants.PREF_USE_CLASSIC_UI))
        assertEquals("Android 15", json.getString(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL))
        internalValues.keys.forEach { key -> assertFalse("Unexpected export key: $key", json.has(key)) }
        assertFalse(json.toString().contains("secret-test-token"))
    }

    // =========================================================================
    // Export: all default feature levels
    // =========================================================================

    @Test
    fun `export all default features round-trips correctly`() {
        val defaultFeatureNames = DeviceProps.defaultFeatures.map { it.displayName }.toSet()
        val prefs = mapOf(
            Constants.PREF_SPOOF_FEATURES_LIST to defaultFeatureNames,
        )
        val json = buildExportJson(prefs)
        val imported = parseImportJson(json)
        @Suppress("UNCHECKED_CAST")
        val importedFeatures = imported[Constants.PREF_SPOOF_FEATURES_LIST] as Set<String>
        assertEquals(defaultFeatureNames, importedFeatures)
    }

    @Test
    fun `export all 12 feature levels round-trips correctly`() {
        val allNames = DeviceProps.allFeatures.map { it.displayName }.toSet()
        val prefs = mapOf(
            Constants.PREF_SPOOF_FEATURES_LIST to allNames,
        )
        val json = buildExportJson(prefs)
        val imported = parseImportJson(json)
        @Suppress("UNCHECKED_CAST")
        val importedFeatures = imported[Constants.PREF_SPOOF_FEATURES_LIST] as Set<String>
        assertEquals(allNames, importedFeatures)
    }

    @Test
    fun `imported legacy Pixel configuration resolves to UNLIMITED_ORIGINAL entitlement`() {
        val json = JSONObject().apply {
            put(Constants.PREF_DEVICE_TO_SPOOF, "Pixel")
        }
        val imported = parseImportJson(json)
        val deviceName = imported[Constants.PREF_DEVICE_TO_SPOOF] as? String
        assertEquals("Pixel", deviceName)
        assertEquals(
            DeviceProps.BackupEntitlement.UNLIMITED_ORIGINAL,
            DeviceProps.getBackupEntitlement(deviceName)
        )
    }
}
