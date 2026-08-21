package io.github.samson910022.pixelifyphotos

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * Lightweight [ContentProvider] that allows hooked target processes (e.g. Google Photos)
 * to safely send diagnostic telemetry and device-spoof VERIFY outcomes back to the module
 * manager application.
 *
 * Modern Xposed frameworks (LSPosed, Vector) make `XposedModule.getRemotePreferences`
 * read-only inside hooked target processes by specification. This provider acts as the
 * authoritative write pipeline, validating incoming callers and keys against immutable
 * security boundaries before persisting them into the manager's preferences.
 */
class DiagnosticsProvider : ContentProvider() {

    companion object {
        private const val TAG = "PixelifyDiagProvider"

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

        private val PACKAGE_NAME_KEYS: Set<String> = setOf(
            Constants.PREF_DIAG_LAST_PACKAGE_LOADED,
            Constants.PREF_DIAG_LAST_PACKAGE_READY,
            Constants.PREF_DIAG_VERIFY_PACKAGE,
        )

        /**
         * Validates whether the IPC caller is authorized to write diagnostic data.
         * Pure logic to enable host JVM unit testing.
         *
         * @param callingUid The UID of the caller process from [Binder.getCallingUid].
         * @param myUid The UID of this module process from [Process.myUid].
         * @param callingPackages The package names owned by [callingUid].
         * @param extras The bundle containing diagnostic data, inspected for package name spoofing.
         */
        fun isCallerAuthorized(
            callingUid: Int,
            myUid: Int,
            callingPackages: Array<String>?,
            extras: Bundle? = null,
        ): Boolean {
            // Module's own process / UID is always authorized
            if (callingUid == myUid) return true

            if (callingPackages.isNullOrEmpty()) return false

            val callingPackageSet = callingPackages.toSet()

            // Verify that at least one package belonging to the caller UID is allowed
            val hasAllowedPackage = callingPackages.any { pkg ->
                pkg == Constants.PACKAGE_NAME_MODULE || ScopePolicy.shouldSpoof(pkg)
            }
            if (!hasAllowedPackage) return false

            // Anti-spoofing: if extras explicitly report a package name, verify it belongs
            // to the caller UID and passes ScopePolicy
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
         * Writes supported bundle value types into the preferences editor.
         * Handles Collections (Set, List, ArrayList, HashSet) alongside Arrays and primitives.
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
    }

    internal var testContext: Context? = null
    internal var testCallingUid: Int? = null
    internal var testMyUid: Int? = null

    private fun resolveContext(): Context? = testContext ?: context
    private fun resolveCallingUid(): Int = testCallingUid ?: runCatching { Binder.getCallingUid() }.getOrDefault(0)
    private fun resolveMyUid(): Int = testMyUid ?: runCatching { Process.myUid() }.getOrDefault(0)

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    @Suppress("DEPRECATION")
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val result = Bundle()
        val ctx = resolveContext() ?: return result.apply { putBoolean("success", false) }

        val callingUid = resolveCallingUid()
        val myUid = resolveMyUid()
        val callingPackages = runCatching { ctx.packageManager.getPackagesForUid(callingUid) }.getOrNull()

        if (!isCallerAuthorized(callingUid, myUid, callingPackages, extras)) {
            Log.w(TAG, "Rejecting unauthorized diagnostics IPC call '$method' from UID $callingUid")
            return result.apply { putBoolean("success", false) }
        }

        try {
            val prefs = PrefUtils.getPrefs(ctx) ?: return result.apply { putBoolean("success", false) }
            val editor = prefs.edit()

            when (method) {
                Constants.METHOD_RECORD_DIAGNOSTICS,
                Constants.METHOD_RECORD_VERIFY -> {
                    if (extras != null) {
                        for (key in extras.keySet()) {
                            if (key !in ALLOWED_DIAG_KEYS) continue
                            applyExtra(editor, key, extras.get(key))
                        }
                        editor.apply()
                        result.putBoolean("success", true)
                    }
                }

                Constants.METHOD_CLEAR_VERIFY -> {
                    editor.remove(Constants.PREF_DIAG_VERIFY_AT)
                        .remove(Constants.PREF_DIAG_VERIFY_DEVICE)
                        .remove(Constants.PREF_DIAG_VERIFY_PACKAGE)
                        .remove(Constants.PREF_DIAG_VERIFY_OK)
                        .remove(Constants.PREF_DIAG_VERIFY_FAILED)
                        .remove(Constants.PREF_DIAG_VERIFY_NATIVE_READY)
                        .remove(Constants.PREF_DIAG_VERIFY_SYSPROPS)
                        .apply()
                    result.putBoolean("success", true)
                }

                else -> {
                    result.putBoolean("success", false)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed handling diagnostics IPC call: $method", t)
            result.putBoolean("success", false)
        }

        return result
    }
}
