package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * Persists user setting edits made before the first framework bind and merges only
 * those edited keys into the manager store. Diagnostics and other process-private
 * state are intentionally outside this allowlist.
 */
internal object OfflineSettingsHandoff {
    const val PREFERENCES_FILE = "offline_settings_handoff"

    enum class RecordStatus {
        NO_CHANGE,
        PERSISTED,
        ACCEPTED_IN_MEMORY,
        REJECTED;

        val accepted: Boolean get() = this != REJECTED
    }

    private const val PENDING_KEYS = "pending_keys"
    private const val REMOVED_KEYS = "removed_keys"
    private const val RECOVERY_ACTIVE = "offline_recovery_active"
    private const val RECOVERY_PRESENT_KEYS = "offline_recovery_present_keys"
    private const val RECOVERY_EXPECTED_KEYS = "offline_recovery_expected_keys"
    private const val RECOVERY_VALUE_PREFIX = "offline_recovery_value:"

    /** Serializes local readers with the pre-edit journal and rollback transaction. */
    internal val recoveryLock = Any()

    private val stringKeys = setOf(
        Constants.PREF_DEVICE_TO_SPOOF,
        Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL,
    )
    private val booleanKeys = setOf(
        Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS,
        Constants.PREF_ENABLE_VERBOSE_LOGS,
        Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE,
        Constants.PREF_USE_CLASSIC_UI,
    )
    private val stringSetKeys = setOf(Constants.PREF_SPOOF_FEATURES_LIST)
    private val userSettingKeys = stringKeys + booleanKeys + stringSetKeys

    /** Snapshot only user-setting allowlist values; diagnostics/private data is never read. */
    fun snapshot(preferences: SharedPreferences): Map<String, Any?> = buildMap {
        stringKeys.forEach { key ->
            if (preferences.contains(key)) put(key, preferences.getString(key, null))
        }
        booleanKeys.forEach { key ->
            if (preferences.contains(key)) put(key, preferences.getBoolean(key, false))
        }
        stringSetKeys.forEach { key ->
            if (preferences.contains(key)) {
                put(key, preferences.getStringSet(key, mutableSetOf())?.toSet())
            }
        }
    }

    /** Records only allowlisted values that actually changed during one guarded offline write. */
    fun recordChanges(
        pending: SharedPreferences,
        before: Map<String, Any?>,
        local: SharedPreferences,
        explicitlyEditedKeys: Set<String> = emptySet(),
    ): Boolean = runCatching {
        recordSnapshotChanges(pending, before, snapshot(local), explicitlyEditedKeys)
    }.getOrDefault(false)

    fun recordSnapshotChanges(
        pending: SharedPreferences,
        before: Map<String, Any?>,
        after: Map<String, Any?>,
        explicitlyEditedKeys: Set<String> = emptySet(),
    ): Boolean = recordSnapshotChangesStatus(pending, before, after, explicitlyEditedKeys).accepted

    /**
     * Records changed settings, distinguishing durable success from Android's
     * commit=false case where SharedPreferences has still changed its live map.
     * That in-process intent is accepted (and must not trigger local rollback),
     * but callers should tell the user it may not survive process recreation.
     */
    fun recordSnapshotChangesStatus(
        pending: SharedPreferences,
        before: Map<String, Any?>,
        after: Map<String, Any?>,
        explicitlyEditedKeys: Set<String> = emptySet(),
    ): RecordStatus {
        return try {
        val changed = changedKeys(before, after, explicitlyEditedKeys)
        if (changed.isEmpty()) return RecordStatus.NO_CHANGE

        val pendingKeys = pending.getStringSet(PENDING_KEYS, emptySet()).orEmpty().toMutableSet()
        val removedKeys = pending.getStringSet(REMOVED_KEYS, emptySet()).orEmpty().toMutableSet()
        val editor = pending.edit()
        changed.forEach { key ->
            pendingKeys += key
            val value = after[key]
            if (value == null) {
                editor.remove(key)
                removedKeys += key
            } else {
                require(writeValue(editor, key, value)) { "Unsupported offline setting value for $key" }
                removedKeys -= key
            }
        }
        editor.putStringSet(PENDING_KEYS, pendingKeys)
        editor.putStringSet(REMOVED_KEYS, removedKeys)
        if (editor.commit()) {
            RecordStatus.PERSISTED
        } else if (matchesInMemoryIntent(pending, changed, after)) {
            RecordStatus.ACCEPTED_IN_MEMORY
        } else {
            RecordStatus.REJECTED
        }
    } catch (_: RuntimeException) {
        RecordStatus.REJECTED
        }
    }

    private fun matchesInMemoryIntent(
        pending: SharedPreferences,
        changed: Set<String>,
        after: Map<String, Any?>,
    ): Boolean {
        val pendingKeys = pending.getStringSet(PENDING_KEYS, emptySet()).orEmpty()
        val removedKeys = pending.getStringSet(REMOVED_KEYS, emptySet()).orEmpty()
        val values = pending.all
        return changed.all { key ->
            key in pendingKeys && when (val expected = after[key]) {
                null -> key in removedKeys && key !in values
                else -> key !in removedKeys && values[key] == expected
            }
        }
    }

    fun changedKeys(
        before: Map<String, Any?>,
        after: Map<String, Any?>,
        explicitlyEditedKeys: Set<String> = emptySet(),
    ): Set<String> = userSettingKeys.filterTo(mutableSetOf()) { key ->
        before[key] != after[key] || key in explicitlyEditedKeys
    }

    /** Restores only allowlisted user settings after an offline intent could not persist. */
    fun restoreSnapshot(
        local: SharedPreferences,
        before: Map<String, Any?>,
        keys: Set<String>,
    ): Boolean = runCatching {
        val editor = local.edit()
        for (key in keys.intersect(userSettingKeys)) {
            val value = before[key]
            if (value == null) {
                editor.remove(key)
            } else if (!writeValue(editor, key, value)) {
                return false
            }
        }
        editor.commit()
    }.getOrDefault(false)

    /**
     * Durably journals the current local user settings before an offline edit is
     * allowed to run. If the later handoff write is rejected and its rollback
     * cannot be persisted, this snapshot lets a recreated process restore before
     * exposing the local preferences to settings screens or configuration export.
     */
    fun prepareLocalRecovery(local: SharedPreferences, before: Map<String, Any?>): Boolean = runCatching {
        val editor = local.edit()
            .putBoolean(RECOVERY_ACTIVE, true)
            .putStringSet(RECOVERY_PRESENT_KEYS, before.filterValues { it != null }.keys.toMutableSet())
            .putStringSet(RECOVERY_EXPECTED_KEYS, mutableSetOf())
        for (key in userSettingKeys) {
            val backupKey = recoveryValueKey(key)
            val value = before[key]
            if (value == null) {
                editor.remove(backupKey)
            } else if (!writeRecoveryValue(editor, backupKey, value)) {
                return false
            }
        }
        editor.commit()
    }.getOrDefault(false)

    /** Records which post-edit values must be present in the pending intent to authorize recovery. */
    fun setLocalRecoveryExpectedKeys(local: SharedPreferences, keys: Set<String>): Boolean = runCatching {
        if (!local.getBoolean(RECOVERY_ACTIVE, false)) return false
        local.edit().putStringSet(RECOVERY_EXPECTED_KEYS, keys.intersect(userSettingKeys).toMutableSet()).commit()
    }.getOrDefault(false)

    /**
     * Resolves an interrupted/rejected offline edit before local settings can be
     * read. A matching pending intent is kept; otherwise the complete pre-edit
     * allowlist snapshot is restored and committed. An unresolved marker fails
     * closed, so possibly-ghost values cannot be read or exported.
     */
    fun recoverLocalSettings(
        local: SharedPreferences,
        pending: SharedPreferences?,
    ): Boolean = runCatching {
        if (!local.getBoolean(RECOVERY_ACTIVE, false)) return true
        val before = readRecoverySnapshot(local) ?: return false
        val expectedKeys = local.getStringSet(RECOVERY_EXPECTED_KEYS, emptySet()).orEmpty().toSet()
        if (!expectedKeys.all(userSettingKeys::contains)) return false
        if (expectedKeys.isNotEmpty() && pending != null && pendingMatchesLocalIntent(pending, local, expectedKeys)) {
            // The live pending map may represent a commit=false write. Keep the
            // durable recovery record until remote convergence makes the intent
            // durable in the manager store.
            return true
        }

        if (!restoreSnapshot(local, before, userSettingKeys)) return false
        if (snapshot(local) != before) return false
        clearLocalRecovery(local)
    }.getOrDefault(false)

    /** Read the classic-theme flag only after the same journal recovery gate as settings screens. */
    fun readClassicUiSetting(local: SharedPreferences, pending: SharedPreferences): Boolean? =
        synchronized(recoveryLock) {
            if (!recoverLocalSettings(local, pending)) return@synchronized null
            runCatching { local.getBoolean(Constants.PREF_USE_CLASSIC_UI, false) }.getOrNull()
        }

    private fun pendingMatchesLocalIntent(
        pending: SharedPreferences,
        local: SharedPreferences,
        expectedKeys: Set<String>,
    ): Boolean {
        val pendingKeys = pending.getStringSet(PENDING_KEYS, emptySet()).orEmpty()
        val removedKeys = pending.getStringSet(REMOVED_KEYS, emptySet()).orEmpty()
        val pendingValues = pending.all
        val localValues = snapshot(local)
        return expectedKeys.all { key ->
            key in pendingKeys && when (val expected = localValues[key]) {
                null -> key in removedKeys && key !in pendingValues
                else -> key !in removedKeys && pendingValues[key] == expected
            }
        }
    }

    private fun readRecoverySnapshot(local: SharedPreferences): Map<String, Any?>? = runCatching {
        val presentKeys = local.getStringSet(RECOVERY_PRESENT_KEYS, emptySet()).orEmpty()
        if (!presentKeys.all(userSettingKeys::contains)) return null
        buildMap {
            for (key in userSettingKeys) {
                if (key !in presentKeys) continue
                val value = local.all[recoveryValueKey(key)] ?: return null
                if (!isValidUserSettingValue(key, value)) return null
                put(key, if (value is Set<*>) value.toSet() else value)
            }
        }
    }.getOrNull()

    private fun clearLocalRecovery(local: SharedPreferences): Boolean = runCatching {
        val editor = local.edit()
            .remove(RECOVERY_ACTIVE)
            .remove(RECOVERY_PRESENT_KEYS)
            .remove(RECOVERY_EXPECTED_KEYS)
        userSettingKeys.forEach { editor.remove(recoveryValueKey(it)) }
        editor.commit()
    }.getOrDefault(false)

    private fun recoveryValueKey(key: String): String = "$RECOVERY_VALUE_PREFIX$key"

    private fun writeRecoveryValue(editor: SharedPreferences.Editor, key: String, value: Any): Boolean = when (value) {
        is String -> { editor.putString(key, value); true }
        is Boolean -> { editor.putBoolean(key, value); true }
        is Set<*> -> if (value.all { it is String }) {
            @Suppress("UNCHECKED_CAST")
            editor.putStringSet(key, (value as Set<String>).toMutableSet())
            true
        } else false
        else -> false
    }

    private fun isValidUserSettingValue(key: String, value: Any): Boolean = when {
        key in stringKeys -> value is String
        key in booleanKeys -> value is Boolean
        key in stringSetKeys -> value is Set<*> && value.all { it is String }
        else -> false
    }

    /**
     * Applies pending offline intent to the remote store. The remote is changed only
     * for locally edited allowlisted keys; all unrelated remote values are untouched.
     * The durable marker is cleared only after the remote commit succeeds.
     */
    fun converge(context: Context, service: XposedService): Boolean = runCatching {
        val pending = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
        val local = runCatching {
            context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
        if (local != null && !synchronized(recoveryLock) { recoverLocalSettings(local, pending) }) return false
        val pendingKeys = pending.getStringSet(PENDING_KEYS, emptySet()).orEmpty().toSet()
        if (pendingKeys.isEmpty()) return true

        val keys = pendingKeys.intersect(userSettingKeys)
        // Unknown keys may have been written by a newer app version or indicate a
        // corrupt marker. Keep them durable and fail closed rather than silently
        // discarding user intent we cannot safely interpret.
        if (keys.isEmpty()) return false
        val removedKeys = pending.getStringSet(REMOVED_KEYS, emptySet()).orEmpty().toSet()
        val remote = service.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME) ?: return false
        val editor = remote.edit()
        keys.forEach { key ->
            if (key in removedKeys) {
                editor.remove(key)
            } else {
                val value = pending.all[key] ?: return false
                if (!writeValue(editor, key, value)) return false
            }
        }
        if (!editor.commit()) return false
        if (local != null && local.getBoolean(RECOVERY_ACTIVE, false) && !clearLocalRecovery(local)) {
            // Keep the pending marker if recovery cleanup is not durable. A retry
            // can safely repeat the already-successful remote write.
            return false
        }
        val remainingKeys = pendingKeys - keys
        val remainingRemovedKeys = removedKeys - keys
        val pendingEditor = pending.edit()
        keys.forEach(pendingEditor::remove)
        if (remainingKeys.isEmpty()) {
            pendingEditor.remove(PENDING_KEYS).remove(REMOVED_KEYS)
        } else {
            pendingEditor.putStringSet(PENDING_KEYS, remainingKeys)
            pendingEditor.putStringSet(REMOVED_KEYS, remainingRemovedKeys)
        }
        if (!pendingEditor.commit()) return false
        remainingKeys.isEmpty()
    }.getOrDefault(false)

    /** Testable contract: diagnostic/private keys can never enter this migration. */
    internal fun isUserSettingKey(key: String): Boolean = key in userSettingKeys

    internal fun userSettingKeysForRollback(): Set<String> = userSettingKeys

    private fun writeValue(editor: SharedPreferences.Editor, key: String, value: Any): Boolean = when {
        key in stringKeys && value is String -> {
            editor.putString(key, value)
            true
        }
        key in booleanKeys && value is Boolean -> {
            editor.putBoolean(key, value)
            true
        }
        key in stringSetKeys && value is Set<*> && value.all { it is String } -> {
            @Suppress("UNCHECKED_CAST")
            editor.putStringSet(key, value as Set<String>)
            true
        }
        else -> false
    }
}
