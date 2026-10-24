package io.github.samson910022.pixelifyphotos

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Explicit allowlist for portable user configuration; runtime/internal prefs never leave the app. */
internal object ConfigExportPolicy {
    class EmptyConfigurationException : IllegalStateException("No allowlisted user settings are available to export")

    val USER_SETTING_KEYS: Set<String> = setOf(
        Constants.PREF_DEVICE_TO_SPOOF,
        Constants.PREF_SPOOF_FEATURES_LIST,
        Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS,
        Constants.PREF_ENABLE_VERBOSE_LOGS,
        Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE,
        Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL,
        Constants.PREF_USE_CLASSIC_UI,
    )

    fun mayExport(
        source: ServiceReadinessSnapshot,
        frameworkBindingObserved: Boolean,
        preferencesAvailable: Boolean,
    ): Boolean = preferencesAvailable && (
        source.ready ||
            (!source.connected && source.phase == ServicePhase.DISCONNECTED && !frameworkBindingObserved)
        )

    fun toJson(preferences: SharedPreferences?): JSONObject {
        val json = JSONObject()
        if (preferences == null) return json
        val values = preferences.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }

        fun putString(key: String) {
            when (val value = values[key]) {
                null -> Unit
                is String -> if (key in USER_SETTING_KEYS) json.put(key, value)
                else -> throw IllegalStateException("Configuration preference '$key' is not a String")
            }
        }

        fun putBoolean(key: String) {
            when (val value = values[key]) {
                null -> Unit
                is Boolean -> if (key in USER_SETTING_KEYS) json.put(key, value)
                else -> throw IllegalStateException("Configuration preference '$key' is not a Boolean")
            }
        }

        putString(Constants.PREF_DEVICE_TO_SPOOF)
        putBoolean(Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS)
        putBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS)
        putBoolean(Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE)
        putBoolean(Constants.PREF_USE_CLASSIC_UI)
        putString(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL)

        val featuresKey = Constants.PREF_SPOOF_FEATURES_LIST
        when (val value = values[featuresKey]) {
            null -> Unit
            is Set<*> -> {
                if (!value.all { it is String }) {
                    throw IllegalStateException("Configuration preference '$featuresKey' is not a StringSet")
                }
                if (featuresKey in USER_SETTING_KEYS) {
                    json.put(featuresKey, JSONArray(value.map { it as String }.sorted()))
                }
            }
            else -> throw IllegalStateException("Configuration preference '$featuresKey' is not a StringSet")
        }
        if (json.length() == 0) throw EmptyConfigurationException()
        return json
    }

    fun hasExportableSettings(json: JSONObject): Boolean = json.length() > 0

    /** Validate persisted exports as user settings only; internal/runtime keys fail closed. */
    fun isAllowlistedExportJson(json: String): Boolean = runCatching {
        val value = JSONObject(json)
        val keys = value.keys().asSequence().toSet()
        value.length() > 0 && USER_SETTING_KEYS.containsAll(keys) && keys.all { key ->
            when (key) {
                Constants.PREF_DEVICE_TO_SPOOF,
                Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL -> value.get(key) is String

                Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS,
                Constants.PREF_ENABLE_VERBOSE_LOGS,
                Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE,
                Constants.PREF_USE_CLASSIC_UI -> value.get(key) is Boolean

                Constants.PREF_SPOOF_FEATURES_LIST -> {
                    val features = value.get(key) as? JSONArray ?: return@all false
                    (0 until features.length()).all { index -> features.get(index) is String }
                }

                else -> false
            }
        }
    }.getOrDefault(false)
}
