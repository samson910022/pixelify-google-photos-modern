package io.github.samson910022.pixelifyphotos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure staging half of configuration import.
 *
 * [Utils.buildConfigImportPlan] performs parsing and validation without touching
 * SharedPreferences, and [ConfigImportPlan.apply] only writes the pre-validated
 * values. These tests need no Android ContentResolver, so the import plan can be
 * exercised directly with [MemorySharedPreferences].
 */
class ConfigImportPlanTest {

    private fun applyPlan(
        jsonObject: JSONObject,
        initialValues: Map<String, Any?> = emptyMap(),
    ): Pair<ConfigImportPlan, MemorySharedPreferences> {
        val plan = Utils.buildConfigImportPlan(jsonObject)
        val preferences = MemorySharedPreferences(initialValues)
        plan.apply(preferences)
        return plan to preferences
    }

    @Test
    fun `plan applies every valid field and edited keys match applied keys`() {
        val features = setOf("Pixel 2020", "Pixel 2021")
        val json = JSONObject().apply {
            put(Constants.PREF_SPOOF_FEATURES_LIST, JSONArray(features.toList()))
            put(Constants.PREF_DEVICE_TO_SPOOF, "Pixel 9 Pro XL")
            put(Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS, true)
            put(Constants.PREF_ENABLE_VERBOSE_LOGS, false)
            put(Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, true)
            put(Constants.PREF_USE_CLASSIC_UI, false)
            put(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL, "Android 15")
        }

        val (plan, preferences) = applyPlan(json)

        assertFalse(plan.hasInvalidDevice)
        assertFalse(plan.hasInvalidAndroidVersion)
        assertEquals(
            setOf(
                Constants.PREF_SPOOF_FEATURES_LIST,
                Constants.PREF_DEVICE_TO_SPOOF,
                Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS,
                Constants.PREF_ENABLE_VERBOSE_LOGS,
                Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE,
                Constants.PREF_USE_CLASSIC_UI,
                Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL,
            ),
            plan.editedKeys,
        )
        assertEquals(
            "editedKeys must describe exactly the keys apply() writes",
            preferences.all.keys,
            plan.editedKeys,
        )
        assertEquals("Pixel 9 Pro XL", preferences.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertEquals(features, preferences.getStringSet(Constants.PREF_SPOOF_FEATURES_LIST, null)?.toSet())
        assertTrue(preferences.getBoolean(Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS, false))
        assertFalse(preferences.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, true))
        assertTrue(preferences.getBoolean(Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, false))
        assertFalse(preferences.getBoolean(Constants.PREF_USE_CLASSIC_UI, true))
        assertEquals("Android 15", preferences.getString(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL, null))
    }

    @Test
    fun `plan skips invalid device and version while applying remaining valid fields`() {
        val json = JSONObject().apply {
            put(Constants.PREF_DEVICE_TO_SPOOF, "Definitely Not A Pixel")
            put(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL, "Android 99")
            put(Constants.PREF_ENABLE_VERBOSE_LOGS, true)
            put(Constants.PREF_USE_CLASSIC_UI, false)
        }

        val (plan, preferences) = applyPlan(json)

        assertTrue(plan.hasInvalidDevice)
        assertTrue(plan.hasInvalidAndroidVersion)
        assertFalse(preferences.contains(Constants.PREF_DEVICE_TO_SPOOF))
        assertFalse(preferences.contains(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL))
        assertTrue(preferences.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false))
        assertFalse(preferences.getBoolean(Constants.PREF_USE_CLASSIC_UI, true))
        assertEquals(
            setOf(Constants.PREF_ENABLE_VERBOSE_LOGS, Constants.PREF_USE_CLASSIC_UI),
            plan.editedKeys,
        )
        assertEquals(preferences.all.keys, plan.editedKeys)
    }

    @Test
    fun `plan writes booleans only when the JSON value is typed boolean`() {
        val json = JSONObject().apply {
            put(Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS, "true")
            put(Constants.PREF_ENABLE_VERBOSE_LOGS, true)
            put(Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, 1)
            put(Constants.PREF_USE_CLASSIC_UI, JSONObject.NULL)
        }

        val (plan, preferences) = applyPlan(json)

        assertTrue(preferences.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false))
        assertFalse(preferences.contains(Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS))
        assertFalse(preferences.contains(Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE))
        assertFalse(preferences.contains(Constants.PREF_USE_CLASSIC_UI))
        assertEquals(setOf(Constants.PREF_ENABLE_VERBOSE_LOGS), plan.editedKeys)
        assertEquals(preferences.all.keys, plan.editedKeys)
    }

    @Test
    fun `plan stages a features array and an empty features array`() {
        val populated = JSONObject().apply {
            put(Constants.PREF_SPOOF_FEATURES_LIST, JSONArray(listOf("Pixel 2020", "Pixel 2021")))
        }
        val (populatedPlan, populatedPreferences) = applyPlan(populated)
        assertEquals(
            setOf("Pixel 2020", "Pixel 2021"),
            populatedPreferences.getStringSet(Constants.PREF_SPOOF_FEATURES_LIST, null)?.toSet(),
        )
        assertEquals(setOf(Constants.PREF_SPOOF_FEATURES_LIST), populatedPlan.editedKeys)

        val empty = JSONObject().apply {
            put(Constants.PREF_SPOOF_FEATURES_LIST, JSONArray())
        }
        val (emptyPlan, emptyPreferences) = applyPlan(empty)
        assertEquals(
            emptySet<String>(),
            emptyPreferences.getStringSet(Constants.PREF_SPOOF_FEATURES_LIST, null)?.toSet(),
        )
        assertEquals(setOf(Constants.PREF_SPOOF_FEATURES_LIST), emptyPlan.editedKeys)
    }

    @Test
    fun `plan ignores unknown and missing keys and never invents defaults`() {
        val json = JSONObject().apply {
            put("UNKNOWN_KEY", "value")
        }

        val (plan, preferences) = applyPlan(json)

        assertTrue(plan.editedKeys.isEmpty())
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun `plan apply preserves existing values for missing keys`() {
        val existing = mapOf(
            Constants.PREF_DEVICE_TO_SPOOF to "Pixel 5",
            Constants.PREF_SPOOF_FEATURES_LIST to setOf("Pixel 2020"),
            Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS to true,
            Constants.PREF_ENABLE_VERBOSE_LOGS to false,
            Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE to true,
            Constants.PREF_USE_CLASSIC_UI to false,
            Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL to "Android 14",
        )
        val json = JSONObject().apply {
            put(Constants.PREF_ENABLE_VERBOSE_LOGS, true)
        }

        val (plan, preferences) = applyPlan(json, existing)

        assertEquals(setOf(Constants.PREF_ENABLE_VERBOSE_LOGS), plan.editedKeys)
        assertTrue(preferences.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false))
        assertEquals("Pixel 5", preferences.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertEquals(
            setOf("Pixel 2020"),
            preferences.getStringSet(Constants.PREF_SPOOF_FEATURES_LIST, null)?.toSet(),
        )
        assertTrue(preferences.getBoolean(Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS, false))
        assertTrue(preferences.getBoolean(Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, false))
        assertFalse(preferences.getBoolean(Constants.PREF_USE_CLASSIC_UI, true))
        assertEquals("Android 14", preferences.getString(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL, null))
    }

    @Test
    fun `plan treats empty device and version values as not provided`() {
        val json = JSONObject().apply {
            put(Constants.PREF_DEVICE_TO_SPOOF, "")
            put(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL, "")
        }

        val (plan, preferences) = applyPlan(json)

        assertFalse(plan.hasInvalidDevice)
        assertFalse(plan.hasInvalidAndroidVersion)
        assertTrue(plan.editedKeys.isEmpty())
        assertTrue(preferences.all.isEmpty())
    }
}
