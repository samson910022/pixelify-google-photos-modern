package io.github.samson910022.pixelifyphotos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the JSON config serialization/deserialization logic
 * used by [Utils.writeConfigFile] and [Utils.readConfigFile].
 *
 * Since those methods require Android Context and ContentResolver,
 * we test the JSON construction and parsing logic directly.
 *
 * This validates:
 * - fieldsNotToCopy exclusion
 * - PREF_SPOOF_FEATURES_LIST stored as JSONArray
 * - Round-trip: write → read preserves all expected keys
 * - Malformed JSON handling
 * - Missing/extra keys in JSON
 */
class UtilsConfigTest {

    // =========================================================================
    // The fieldsNotToCopy list from Utils.writeConfigFile
    // =========================================================================

    private val fieldsNotToCopy = listOf(
        Constants.PREF_LAST_VERSION,
        Constants.PREF_SPOOF_FEATURES_LIST,
    )

    // =========================================================================
    // Config export logic: JSON construction
    // =========================================================================

    /**
     * Simulates the export logic from Utils.writeConfigFile.
     * Takes a map of pref keys → values, returns a JSONObject.
     */
    private fun buildExportJson(allPrefs: Map<String, Any?>): JSONObject {
        val jsonObject = JSONObject()

        // First pass: copy everything except fieldsNotToCopy
        for ((key, value) in allPrefs) {
            if (key !in fieldsNotToCopy) {
                if (value != null) {
                    jsonObject.put(key, value)
                }
            }
        }

        // Second pass: store PREF_SPOOF_FEATURES_LIST as JSONArray
        val featuresList = allPrefs[Constants.PREF_SPOOF_FEATURES_LIST]
        if (featuresList is Set<*>) {
            @Suppress("UNCHECKED_CAST")
            val jsonArray = JSONArray((featuresList as Set<String>).toTypedArray())
            jsonObject.put(Constants.PREF_SPOOF_FEATURES_LIST, jsonArray)
        }

        return jsonObject
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
    // Export: fieldsNotToCopy exclusion
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
    fun `export preserves all non-excluded keys`() {
        val prefs = mapOf(
            Constants.PREF_LAST_VERSION to 5,  // should be excluded
            Constants.PREF_DEVICE_TO_SPOOF to "Pixel 5",
            Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS to false,
            Constants.PREF_ENABLE_VERBOSE_LOGS to true,
            Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE to false,
            Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL to "Android 14",
        )
        val json = buildExportJson(prefs)

        assertFalse(json.has(Constants.PREF_LAST_VERSION))
        assertEquals("Pixel 5", json.getString(Constants.PREF_DEVICE_TO_SPOOF))
        assertFalse(json.getBoolean(Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS))
        assertTrue(json.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS))
        assertFalse(json.getBoolean(Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE))
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
        )
        val json = buildExportJson(prefs)
        val imported = parseImportJson(json)

        assertEquals(false, imported[Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS])
        assertEquals(true, imported[Constants.PREF_ENABLE_VERBOSE_LOGS])
        assertEquals(true, imported[Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE])
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
    fun `export of empty prefs produces valid JSON`() {
        val json = buildExportJson(emptyMap())
        assertEquals("{}", json.toString())
    }

    @Test
    fun `export of null values excludes null entries`() {
        val prefs = mapOf(
            Constants.PREF_DEVICE_TO_SPOOF to null,
            Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL to null,
        )
        val json = buildExportJson(prefs)
        assertFalse(json.has(Constants.PREF_DEVICE_TO_SPOOF))
        assertFalse(json.has(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL))
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
}
