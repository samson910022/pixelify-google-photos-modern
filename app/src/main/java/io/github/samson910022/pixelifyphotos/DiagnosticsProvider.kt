package io.github.samson910022.pixelifyphotos

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Lightweight [ContentProvider] that allows hooked target processes (e.g. Google Photos)
 * to safely send diagnostic telemetry and device-spoof VERIFY outcomes back to the module
 * manager application.
 *
 * Modern Xposed frameworks (LSPosed, Vector) make `XposedModule.getRemotePreferences`
 * read-only inside hooked target processes by specification. This provider acts as the
 * authoritative write pipeline, validating incoming keys against an immutable whitelist
 * before persisting them into the manager's preferences.
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
    }

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
        val ctx = context ?: return result.apply { putBoolean("success", false) }

        try {
            val prefs = PrefUtils.getPrefs(ctx) ?: return result.apply { putBoolean("success", false) }
            val editor = prefs.edit()

            when (method) {
                Constants.METHOD_RECORD_DIAGNOSTICS -> {
                    if (extras != null) {
                        for (key in extras.keySet()) {
                            if (key !in ALLOWED_DIAG_KEYS) continue
                            when (val value = extras.get(key)) {
                                is Long -> editor.putLong(key, value)
                                is String -> editor.putString(key, value)
                                is Boolean -> editor.putBoolean(key, value)
                                is Int -> editor.putInt(key, value)
                            }
                        }
                        editor.apply()
                        result.putBoolean("success", true)
                    }
                }

                Constants.METHOD_RECORD_VERIFY -> {
                    if (extras != null) {
                        for (key in extras.keySet()) {
                            if (key !in ALLOWED_DIAG_KEYS) continue
                            when (val value = extras.get(key)) {
                                is Long -> editor.putLong(key, value)
                                is String -> editor.putString(key, value)
                                is Boolean -> editor.putBoolean(key, value)
                                is Int -> editor.putInt(key, value)
                                is Array<*> -> {
                                    val stringSet = value.filterIsInstance<String>().toSet()
                                    editor.putStringSet(key, stringSet)
                                }
                                is ArrayList<*> -> {
                                    val stringSet = value.filterIsInstance<String>().toSet()
                                    editor.putStringSet(key, stringSet)
                                }
                            }
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
