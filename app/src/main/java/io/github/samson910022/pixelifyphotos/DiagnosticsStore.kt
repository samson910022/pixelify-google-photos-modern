package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log

/**
 * Centralized persistence and security domain for diagnostics and verify telemetry.
 *
 * Both [DiagnosticsProvider] (ContentProvider IPC) and [DiagnosticsReceiver] (explicit Broadcast
 * fallback for Android 11+ AppsFilter package visibility) delegate their authorization,
 * key filtering, anti-spoofing validation, and SharedPreferences operations to this object.
 */
object DiagnosticsStore {

    private const val TAG = "PixelifyDiagStore"

    val ALLOWED_DIAG_KEYS: Set<String> = setOf(
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
    )

    val PACKAGE_NAME_KEYS: Set<String> = setOf(
        Constants.PREF_DIAG_LAST_PACKAGE_LOADED,
        Constants.PREF_DIAG_LAST_PACKAGE_READY,
        Constants.PREF_DIAG_VERIFY_PACKAGE,
    )

    val VERIFY_KEYS_TO_CLEAR: Set<String> = setOf(
        Constants.PREF_DIAG_VERIFY_AT,
        Constants.PREF_DIAG_VERIFY_DEVICE,
        Constants.PREF_DIAG_VERIFY_PACKAGE,
        Constants.PREF_DIAG_VERIFY_OK,
        Constants.PREF_DIAG_VERIFY_FAILED,
        Constants.PREF_DIAG_VERIFY_NATIVE_READY,
        Constants.PREF_DIAG_VERIFY_SYSPROPS,
    )

    /**
     * Checks if a Binder caller is authorized to invoke diagnostics IPC.
     * - Self module UID is always authorized.
     * - Remote callers must own at least one non-denylisted package passing [ScopePolicy.shouldSpoof].
     * - Any reported package names in [extras] must be owned by the calling UID and pass [ScopePolicy.shouldSpoof].
     */
    fun isCallerAuthorized(
        callingUid: Int,
        myUid: Int,
        callingPackages: Array<String>?,
        extras: Bundle? = null,
    ): Boolean {
        if (callingUid == myUid) return true
        if (callingPackages.isNullOrEmpty()) return false

        val callingPackageSet = callingPackages.toSet()
        val hasAllowedPackage = callingPackages.any { pkg ->
            pkg == Constants.PACKAGE_NAME_MODULE || ScopePolicy.shouldSpoof(pkg)
        }
        if (!hasAllowedPackage) return false

        if (extras != null) {
            for (key in PACKAGE_NAME_KEYS) {
                val reportedPkg = extras.getString(key)
                if (!reportedPkg.isNullOrEmpty()) {
                    if (reportedPkg !in callingPackageSet || !ScopePolicy.shouldSpoof(reportedPkg)) {
                        return false
                    }
                }
            }
        }
        return true
    }

    /**
     * Maps bundle extra values to SharedPreferences types safely.
     */
    fun applyExtra(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is CharSequence -> editor.putString(key, value.toString())
            is Array<*> -> {
                val stringSet = value.filterIsInstance<String>().toSet()
                editor.putStringSet(key, stringSet)
            }
            is Collection<*> -> {
                val stringSet = value.filterIsInstance<String>().toSet()
                editor.putStringSet(key, stringSet)
            }
            else -> {
                Log.w(TAG, "Unsupported preference value type for key '$key': ${value?.javaClass?.name}")
            }
        }
    }

    /**
     * Validates and applies diagnostics telemetry to SharedPreferences.
     */
    fun applyDiagnostics(
        context: Context,
        method: String?,
        extras: Bundle?,
        callingUid: Int? = null,
        callingPackages: Array<String>? = null,
        myUid: Int? = null,
    ): Boolean {
        if (method == null) return false

        // 1. Caller authorization when Binder UID is provided (ContentProvider IPC)
        if (callingUid != null && myUid != null) {
            if (!isCallerAuthorized(callingUid, myUid, callingPackages, extras)) {
                Log.w(TAG, "Rejecting unauthorized diagnostics IPC call '$method' from UID $callingUid")
                return false
            }
        } else {
            // 2. Anti-spoofing package validation when UID is absent (Broadcast channel)
            if (extras != null) {
                for (key in PACKAGE_NAME_KEYS) {
                    val reportedPkg = extras.getString(key)
                    if (!reportedPkg.isNullOrEmpty() && !ScopePolicy.shouldSpoof(reportedPkg)) {
                        Log.w(TAG, "Rejecting diagnostic update with denylisted reported package '$reportedPkg'")
                        return false
                    }
                }
            }
        }

        val prefs = PrefUtils.getPrefs(context) ?: return false
        val editor = prefs.edit()

        return try {
            when (method) {
                Constants.METHOD_RECORD_DIAGNOSTICS,
                Constants.METHOD_RECORD_VERIFY -> {
                    if (extras == null) return false
                    for (key in extras.keySet()) {
                        if (key !in ALLOWED_DIAG_KEYS) continue
                        applyExtra(editor, key, extras.get(key))
                    }
                    editor.apply()
                    true
                }
                Constants.METHOD_CLEAR_VERIFY -> {
                    VERIFY_KEYS_TO_CLEAR.forEach { editor.remove(it) }
                    editor.apply()
                    true
                }
                else -> {
                    Log.w(TAG, "Unknown diagnostics method '$method'")
                    false
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed applying diagnostics for method '$method'", t)
            false
        }
    }
}
