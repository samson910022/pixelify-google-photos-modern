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

    internal val PACKAGE_NAME_KEYS: Set<String> = setOf(
        Constants.PREF_DIAG_LAST_PACKAGE_LOADED,
        Constants.PREF_DIAG_LAST_PACKAGE_READY,
        Constants.PREF_DIAG_VERIFY_PACKAGE,
    )

    internal val VERIFY_KEYS_TO_CLEAR: Set<String> = setOf(
        Constants.PREF_DIAG_VERIFY_AT,
        Constants.PREF_DIAG_VERIFY_DEVICE,
        Constants.PREF_DIAG_VERIFY_PACKAGE,
        Constants.PREF_DIAG_VERIFY_OK,
        Constants.PREF_DIAG_VERIFY_FAILED,
        Constants.PREF_DIAG_VERIFY_NATIVE_READY,
        Constants.PREF_DIAG_VERIFY_SYSPROPS,
    )

    /**
     * Per-install random token for broadcast authentication.
     * Stored under [Constants.PREF_DIAG_BROADCAST_TOKEN] (never in [ALLOWED_DIAG_KEYS]).
     * Generated lazily in the module process.
     */
    fun getOrCreateToken(context: Context): String? {
        val prefs = PrefUtils.getPrefs(context) ?: return null
        val existing = prefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)
        if (!existing.isNullOrEmpty()) return existing
        val generated = java.util.UUID.randomUUID().toString()
        return try {
            // Use commit() for cross-process atomicity (apply() is async).
            val ok = prefs.edit().putString(Constants.PREF_DIAG_BROADCAST_TOKEN, generated).commit()
            if (ok) generated else existing ?: generated
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Resolves the currently acceptable broadcast token from the canonical store.
     * [PrefUtils.getPrefs] prefers LSPosed remote preferences once the Xposed
     * service is bound in this process and falls back to the file-backed store
     * otherwise, which matches exactly what hooked-process senders can resolve
     * after [convergeBroadcastToken] has aligned both stores.
     */
    fun getStoredToken(context: Context): String? {
        return try {
            PrefUtils.getPrefs(context)?.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Converges the per-install broadcast token across the local (file-backed) and
     * remote preference stores so sender and receiver agree on a single canonical
     * value regardless of which store was provisioned first. Remote prefs win:
     * hooked-process senders can only resolve the token from that store.
     * Called from [App.onServiceBind]; safe to call repeatedly.
     */
    fun convergeBroadcastToken(context: Context) {
        try {
            val service = App.mService ?: return
            val remote = service.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
            // Deliberately target the file-backed store directly: once the service is
            // bound, PrefUtils.getPrefs resolves to remote prefs, which would make a
            // PrefUtils-based "local" view a no-op alias of [remote].
            val fileStore = context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE)
            val localToken = fileStore.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)
            val remoteToken = remote.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)
            when {
                remoteToken.isNullOrEmpty() && !localToken.isNullOrEmpty() ->
                    remote.edit().putString(Constants.PREF_DIAG_BROADCAST_TOKEN, localToken).commit()
                !remoteToken.isNullOrEmpty() && remoteToken != localToken ->
                    fileStore.edit().putString(Constants.PREF_DIAG_BROADCAST_TOKEN, remoteToken).commit()
                else -> Unit
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Broadcast token convergence skipped: ${t.message}")
        }
    }

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

        // 1. Caller authorization when Binder UID is provided (ContentProvider IPC or API 34+ broadcast with shareIdentity)
        if (callingUid != null && myUid != null) {
            if (!isCallerAuthorized(callingUid, myUid, callingPackages, extras)) {
                Log.w(TAG, "Rejecting unauthorized diagnostics IPC call '$method' from UID $callingUid")
                return false
            }
        } else {
            // 2. Broadcast channel (no Binder UID) — require the per-install token, fail-closed
            //    otherwise. There is deliberately NO unauthenticated fallback: the receiver
            //    component is reachable from install time, so any window that accepts token-less
            //    broadcasts would let arbitrary apps write diagnostic state. Provisioning happens
            //    synchronously in App.onCreate (local prefs) and PixelifyModule.onModuleLoaded
            //    (remote prefs); App.onServiceBind converges both stores via
            //    [convergeBroadcastToken], so legitimate telemetry recovers automatically.
            val broadcastToken = extras?.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)
            val expectedToken = getStoredToken(context)
            if (expectedToken.isNullOrEmpty()) {
                Log.w(TAG, "Rejecting diagnostic broadcast '$method': no token provisioned yet")
                return false
            }
            if (broadcastToken.isNullOrEmpty() ||
                !java.security.MessageDigest.isEqual(
                    broadcastToken.toByteArray(),
                    expectedToken.toByteArray()
                )
            ) {
                Log.w(TAG, "Rejecting diagnostic broadcast without valid token for method '$method'")
                return false
            }
            // Defense in depth: even with a valid token, refuse reported packages the
            // scope policy would never spoof (keeps persisted state consistent with hooks).
            if (extras != null) {
                for (key in PACKAGE_NAME_KEYS) {
                    val reportedPkg = extras.getString(key)
                    if (!reportedPkg.isNullOrEmpty() && !ScopePolicy.shouldSpoof(reportedPkg)) {
                        Log.w(TAG, "Rejecting diagnostic update with denylisted reported package '${reportedPkg.take(100)}'")
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
