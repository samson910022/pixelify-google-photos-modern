package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
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
    internal const val MAX_EVENT_SEQUENCE = 1_000_000_000L
    private const val EVENT_SEQUENCE_PREF_PREFIX = "PREF_DIAG_EVENT_SEQUENCE:"
    private const val MAX_VERIFY_FAILED_ENTRIES = 32
    private const val MAX_VERIFY_FAILED_ENTRY_LENGTH = 64
    private const val MAX_VERIFY_FAILED_TOTAL_BYTES = 256
    private const val MAX_VERIFY_TIMESTAMP = 253_402_300_799_000L // 9999-12-31T23:59:59Z

    /** Test seam for exercising remote writes without a live Xposed service. */
    @Volatile
    internal var testRemotePreferencesResolver: (() -> SharedPreferences?)? = null

    private enum class StoreAuthority { MANAGER, REMOTE_TARGET }

    private interface PreferenceReader {
        fun getString(key: String, defaultValue: String?): String?
        fun getLong(key: String, defaultValue: Long): Long
        fun getInt(key: String, defaultValue: Int): Int
        fun getBoolean(key: String, defaultValue: Boolean): Boolean
    }

    /** Immutable, fully unparcelled payload used after the schema gate succeeds. */
    private class DiagnosticPayload(values: Map<String, Any>) {
        private val values = values.toMap()
        fun keySet(): Set<String> = values.keys
        fun get(key: String): Any? = values[key]
        fun getString(key: String): String? = values[key] as? String
        fun getLong(key: String, defaultValue: Long): Long = values[key] as? Long ?: defaultValue
        fun getInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue
    }

    private class SharedPreferenceReader(private val prefs: SharedPreferences) : PreferenceReader {
        override fun getString(key: String, defaultValue: String?) = prefs.getString(key, defaultValue)
        override fun getLong(key: String, defaultValue: Long) = prefs.getLong(key, defaultValue)
        override fun getInt(key: String, defaultValue: Int) = prefs.getInt(key, defaultValue)
        override fun getBoolean(key: String, defaultValue: Boolean) = prefs.getBoolean(key, defaultValue)
    }

    /**
     * Deferred editor used only while validating a fallback batch. Calls to apply()
     * are intentionally inert; the accumulated changes reach SharedPreferences
     * together through one real editor after both requests have passed validation.
     */
    private class StagedEditor : SharedPreferences.Editor {
        object Removed
        private val changes = linkedMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            stage(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            stage(key, values?.toSet())

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = stage(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = stage(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = stage(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = stage(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) changes[key] = Removed
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            changes.clear()
            return this
        }

        override fun apply() = Unit
        override fun commit(): Boolean = false

        private fun stage(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) changes[key] = if (value == null) Removed else value
            return this
        }

        fun containsChange(key: String): Boolean = key in changes

        fun stagedValue(key: String): Any? = changes[key]

        fun wasCleared(): Boolean = clearRequested

        fun applyTo(editor: SharedPreferences.Editor) {
            if (clearRequested) editor.clear()
            changes.forEach { (key, value) ->
                when (value) {
                    Removed -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> throw IllegalStateException("Unsupported staged diagnostics preference value")
                }
            }
            editor.apply()
        }
    }

    private class OverlayPreferenceReader(
        private val base: PreferenceReader,
        private val staged: StagedEditor,
    ) : PreferenceReader {
        private fun stagedValue(key: String): Pair<Boolean, Any?> = when {
            staged.containsChange(key) -> true to staged.stagedValue(key)
            staged.wasCleared() -> true to null
            else -> false to null
        }

        override fun getString(key: String, defaultValue: String?): String? {
            val (isStaged, value) = stagedValue(key)
            if (!isStaged) return base.getString(key, defaultValue)
            return when (value) {
                StagedEditor.Removed, null -> defaultValue
                is String -> value
                else -> throw ClassCastException("Preference '$key' is not a String")
            }
        }

        override fun getLong(key: String, defaultValue: Long): Long {
            val (isStaged, value) = stagedValue(key)
            if (!isStaged) return base.getLong(key, defaultValue)
            return when (value) {
                StagedEditor.Removed, null -> defaultValue
                is Long -> value
                else -> throw ClassCastException("Preference '$key' is not a Long")
            }
        }

        override fun getInt(key: String, defaultValue: Int): Int {
            val (isStaged, value) = stagedValue(key)
            if (!isStaged) return base.getInt(key, defaultValue)
            return when (value) {
                StagedEditor.Removed, null -> defaultValue
                is Int -> value
                else -> throw ClassCastException("Preference '$key' is not an Int")
            }
        }

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            val (isStaged, value) = stagedValue(key)
            if (!isStaged) return base.getBoolean(key, defaultValue)
            return when (value) {
                StagedEditor.Removed, null -> defaultValue
                is Boolean -> value
                else -> throw ClassCastException("Preference '$key' is not a Boolean")
            }
        }
    }

    internal data class RunClock(
        val bootCount: Int?,
        val elapsedRealtimeNanos: Long,
        val bootEpochHash: String?,
    )

    @Volatile
    internal var testRunClock: RunClock? = null

    private fun currentRunClock(context: Context): RunClock? {
        testRunClock?.let { return it }
        return runCatching {
            val bootCount = runCatching {
                Settings.Global.getInt(
                    context.contentResolver,
                    Settings.Global.BOOT_COUNT,
                    -1,
                ).takeIf { it >= 0 }
            }.getOrNull()
            val elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            val bootEpochHash = DiagnosticsBootEpoch.currentHash()
            if ((bootCount == null && bootEpochHash == null) || elapsedRealtimeNanos <= 0L) null
            else RunClock(bootCount, elapsedRealtimeNanos, bootEpochHash)
        }.getOrNull()
    }

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

    internal val PACKAGE_NAME_KEYS: Set<String> = setOf(
        Constants.PREF_DIAG_LAST_PACKAGE_LOADED,
        Constants.PREF_DIAG_LAST_PACKAGE_READY,
        Constants.PREF_DIAG_VERIFY_PACKAGE,
        Constants.EXTRA_DIAGNOSTICS_ORIGIN,
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

    private val RUN_STATE_KEYS_TO_CLEAR: Set<String> = setOf(
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
    )

    private val EVENT_SEQUENCE_PREF_KEYS: Set<String> = RUN_STATE_KEYS_TO_CLEAR.mapTo(mutableSetOf()) {
        eventSequencePreferenceKey(it)
    }

    private val SEQUENCED_METHODS: Set<String> = setOf(
        Constants.METHOD_RECORD_DIAGNOSTICS,
        Constants.METHOD_RECORD_VERIFY,
        Constants.METHOD_CLEAR_VERIFY,
    )

    private fun eventSequencePreferenceKey(diagnosticKey: String): String =
        "$EVENT_SEQUENCE_PREF_PREFIX$diagnosticKey"

    /**
     * Per-install random token for remote diagnostics authentication.
     * Stored under [Constants.PREF_DIAG_BROADCAST_TOKEN] (never in [ALLOWED_DIAG_KEYS]).
     * Provisioned by the module app process before the Xposed service syncs it to
     * remote preferences. Hooked target processes never mint competing tokens.
     */
    @Synchronized
    fun getOrCreateToken(context: Context): String? {
        return try {
            // Always provision in the module app's private store. PrefUtils may
            // resolve to Xposed remote prefs after binding, which would reintroduce
            // a second token-creation path.
            val prefs = context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)
            if (!existing.isNullOrBlank()) return existing
            val generated = java.util.UUID.randomUUID().toString()
            // Use commit() for cross-process atomicity (apply() is async).
            val ok = prefs.edit().putString(Constants.PREF_DIAG_BROADCAST_TOKEN, generated).commit()
            if (ok) generated else {
                Log.w(TAG, "Diagnostics IPC token provisioning did not persist; remote reports remain unknown")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Diagnostics IPC token provisioning failed; remote reports remain unknown", t)
            null
        }
    }

    /**
     * Resolves the currently acceptable diagnostics token from the canonical store.
     * [PrefUtils.getPrefs] prefers LSPosed remote preferences while the service is
     * ready and uses the file-backed store only before this process has observed a
     * framework binding, matching the stores aligned by [convergeBroadcastToken].
     */
    fun getStoredToken(context: Context): String? {
        return try {
            PrefUtils.getPrefs(context)?.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Synchronizes the module-owned app-private token into Xposed remote preferences.
     * A pre-existing remote token wins for upgrade compatibility; otherwise the token
     * generated in the module app's private store is copied to remote preferences.
     * No token from a diagnostic payload or other remote caller is adopted.
     * Called from [App.onServiceBind]; safe to call repeatedly.
     */
    fun convergeBroadcastToken(context: Context, service: io.github.libxposed.service.XposedService? = App.mService): Boolean {
        val boundService = service ?: run {
            Log.w(TAG, "Diagnostics IPC token sync skipped: Xposed service is not bound")
            return false
        }
        return try {
            val remote = boundService.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
            // Deliberately target the file-backed store directly: once the service is
            // bound, PrefUtils.getPrefs resolves to remote prefs, which would make a
            // PrefUtils-based "local" view a no-op alias of [remote].
            val fileStore = context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE)
            val localToken = fileStore.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)
            val remoteToken = remote.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)
            when {
                remoteToken.isNullOrBlank() && localToken.isNullOrBlank() -> {
                    Log.w(TAG, "Diagnostics IPC token sync found no provisioned token; reports remain unknown")
                    false
                }
                remoteToken.isNullOrBlank() -> {
                    val committed = remote.edit()
                        .putString(Constants.PREF_DIAG_BROADCAST_TOKEN, localToken)
                        .commit()
                    if (!committed) {
                        Log.w(TAG, "Diagnostics IPC token sync to remote preferences did not persist")
                    }
                    committed
                }
                remoteToken != localToken -> {
                    val committed = fileStore.edit()
                        .putString(Constants.PREF_DIAG_BROADCAST_TOKEN, remoteToken)
                        .commit()
                    if (!committed) {
                        Log.w(TAG, "Diagnostics IPC token sync to module preferences did not persist")
                    }
                    committed
                }
                else -> true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Diagnostics IPC token sync failed; remote reports remain unknown", t)
            false
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

    private fun isPayloadCallerAuthorized(
        callingUid: Int,
        myUid: Int,
        callingPackages: Array<String>?,
        extras: DiagnosticPayload?,
    ): Boolean {
        if (callingUid == myUid) return true
        if (callingPackages.isNullOrEmpty()) return false
        if (callingPackages.none { it == Constants.PACKAGE_NAME_MODULE || ScopePolicy.shouldSpoof(it) }) return false
        for (key in PACKAGE_NAME_KEYS) {
            val reportedPkg = extras?.getString(key)
            if (!reportedPkg.isNullOrEmpty() &&
                (reportedPkg !in callingPackages || !ScopePolicy.shouldSpoof(reportedPkg))
            ) return false
        }
        return true
    }

    private fun preferencesFor(context: Context, authority: StoreAuthority): SharedPreferences? {
        return when (authority) {
            StoreAuthority.MANAGER -> PrefUtils.getPrefs(context)
            StoreAuthority.REMOTE_TARGET -> {
                val testResolver = testRemotePreferencesResolver
                if (testResolver != null) {
                    runCatching { testResolver() }.getOrNull()
                } else {
                    val service = App.readyService() ?: return null
                    runCatching { service.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME) }.getOrNull()
                }
            }
        }
    }

    private fun hasValidToken(prefs: PreferenceReader, extras: DiagnosticPayload?): Boolean {
        val supplied = extras?.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)
        val expected = prefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)
        return !supplied.isNullOrEmpty() && !expected.isNullOrEmpty() &&
            java.security.MessageDigest.isEqual(
                supplied.toByteArray(Charsets.UTF_8),
                expected.toByteArray(Charsets.UTF_8),
            )
    }

    /**
     * Authenticates remote telemetry consistently across provider and broadcast IPC.
     * The install token proves the sender has Xposed remote-preference access; origin
     * validation prevents an update for one package being presented as another.
     */
    private fun isRemoteSourceAuthorized(
        extras: DiagnosticPayload?,
        callingPackages: Array<String>?,
        prefs: PreferenceReader,
    ): Boolean {
        if (!hasValidToken(prefs, extras)) return false
        val origin = extras?.getString(Constants.EXTRA_DIAGNOSTICS_ORIGIN)
            ?: return false
        if (!ScopePolicy.shouldSpoof(origin)) return false
        if (callingPackages != null && origin !in callingPackages) return false

        for (key in PACKAGE_NAME_KEYS) {
            val reportedPackage = extras.getString(key)
            if (!reportedPackage.isNullOrEmpty() &&
                (reportedPackage != origin || !ScopePolicy.shouldSpoof(reportedPackage))
            ) {
                return false
            }
        }
        return true
    }

    private fun runIdentityMatches(prefs: PreferenceReader, extras: DiagnosticPayload?): Boolean {
        val origin = extras?.getString(Constants.EXTRA_DIAGNOSTICS_ORIGIN) ?: return false
        val runId = extras.getString(Constants.EXTRA_DIAGNOSTICS_RUN_ID) ?: return false
        val runStartedAt = extras.getLong(Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT, 0L)
        val runGeneration = extras.getLong(Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION, 0L)
        val runBootEpoch = extras.getString(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH)
        return origin == prefs.getString(Constants.PREF_DIAG_ACTIVE_PACKAGE, null) &&
            runId == prefs.getString(Constants.PREF_DIAG_ACTIVE_RUN_ID, null) &&
            runStartedAt > 0L &&
            runStartedAt == prefs.getLong(Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT, 0L) &&
            runGeneration > 0L &&
            runGeneration == prefs.getLong(Constants.PREF_DIAG_ACTIVE_RUN_GENERATION, 0L) &&
            !runBootEpoch.isNullOrBlank() &&
            runBootEpoch == prefs.getString(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH, null)
    }

    /** Run-bound telemetry must belong to both the persisted run and this OS boot. */
    private fun currentBootRunIdentityMatches(
        context: Context,
        prefs: PreferenceReader,
        extras: DiagnosticPayload?,
    ): Boolean {
        val currentBootEpoch = currentRunClock(context)?.bootEpochHash ?: return false
        val payloadBootEpoch = extras?.getString(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH)
        return !payloadBootEpoch.isNullOrBlank() &&
            payloadBootEpoch == currentBootEpoch && runIdentityMatches(prefs, extras)
    }

    private fun startRun(
        prefs: PreferenceReader,
        editor: SharedPreferences.Editor,
        extras: DiagnosticPayload,
        verifiedOrigin: String?,
        callingPid: Int?,
        tokenOnlyBroadcastAllowed: Boolean,
        allowPriorBootUnverifiedReplacement: Boolean,
        runClock: RunClock,
    ): Boolean {
        val origin = extras.getString(Constants.EXTRA_DIAGNOSTICS_ORIGIN) ?: return false
        val runId = extras.getString(Constants.EXTRA_DIAGNOSTICS_RUN_ID) ?: return false
        val startedAt = extras.getLong(Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT, 0L)
        val generation = extras.getLong(Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION, 0L)
        val bootCount = extras.getInt(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT, -1)
        val bootEpoch = extras.getString(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH)
        if (!ScopePolicy.shouldSpoof(origin) || runId.length !in 1..128 ||
            !runId.matches(Regex("[A-Za-z0-9._-]+")) || startedAt <= 0L || generation <= 0L ||
            (runClock.bootEpochHash == null && runClock.bootCount != null && bootCount != runClock.bootCount) ||
            generation > runClock.elapsedRealtimeNanos ||
            (runClock.bootEpochHash != null && bootEpoch != runClock.bootEpochHash) ||
            (runClock.bootEpochHash == null && !bootEpoch.isNullOrBlank())
        ) {
            return false
        }

        // Without a unique OS epoch a start is safe only when it cannot replace
        // existing data. Subsequent run-bound events also fail closed as unknown.
        if (runClock.bootEpochHash == null &&
            !prefs.getString(Constants.PREF_DIAG_ACTIVE_RUN_ID, null).isNullOrBlank()
        ) return false

        val activeGeneration = prefs.getLong(Constants.PREF_DIAG_ACTIVE_RUN_GENERATION, 0L)
        val activeBootEpoch = prefs.getString(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH, null)
        val activeId = prefs.getString(Constants.PREF_DIAG_ACTIVE_RUN_ID, null)
        val activeOrigin = prefs.getString(Constants.PREF_DIAG_ACTIVE_PACKAGE, null)
        val activeOriginVerified = prefs.getBoolean(Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED, false)
        val incomingOriginVerified = verifiedOrigin == origin
        // Compare epochs before preserving verified origin: an explicitly gated
        // API 30-33 token-only fallback may replace verified state from a known
        // prior boot. Unknown epochs cannot prove that the active run is historical.
        val sameEpoch = !bootEpoch.isNullOrBlank() && activeBootEpoch == bootEpoch
        val knownPriorEpoch = !activeId.isNullOrBlank() &&
            !bootEpoch.isNullOrBlank() &&
            !activeBootEpoch.isNullOrBlank() && activeBootEpoch != bootEpoch
        if (activeOriginVerified && !incomingOriginVerified &&
            !(allowPriorBootUnverifiedReplacement && tokenOnlyBroadcastAllowed && knownPriorEpoch)
        ) {
            Log.w(TAG, "Rejecting unverified run start to preserve active verified diagnostics")
            return false
        }
        // The current OS epoch is authoritative before any numeric ordering.
        // A different prior epoch can be replaced even when BOOT_COUNT regresses.
        val incomingIsOlder = sameEpoch && activeGeneration > generation
        val sameGenerationDifferentRun = sameEpoch &&
            activeGeneration == generation && activeGeneration > 0L &&
            (activeId != runId || activeOrigin != origin ||
                prefs.getLong(Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT, 0L) != startedAt)
        if (incomingIsOlder || sameGenerationDifferentRun) {
            val sameRun = sameEpoch && activeGeneration == generation &&
                activeId == runId && activeOrigin == origin &&
                prefs.getLong(Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT, 0L) == startedAt
            if (sameRun && incomingOriginVerified) {
                editor.putBoolean(Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED, true)
                if (callingPid != null && callingPid > 0) {
                    editor.putInt(Constants.PREF_DIAG_ACTIVE_BINDER_PID, callingPid)
                }
                for (key in listOf(Constants.PREF_DIAG_MODULE_LOADED_AT, Constants.PREF_DIAG_LAST_PACKAGE_LOADED)) {
                    if (key in extras.keySet()) applyExtra(editor, key, extras.get(key))
                }
            }
            return sameRun
        }
        if (sameEpoch && activeGeneration == generation &&
            activeId == runId && activeOrigin == origin &&
            prefs.getLong(Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT, 0L) == startedAt
        ) return true

        RUN_STATE_KEYS_TO_CLEAR.forEach { editor.remove(it) }
        EVENT_SEQUENCE_PREF_KEYS.forEach { editor.remove(it) }
        editor.putString(Constants.PREF_DIAG_ACTIVE_PACKAGE, origin)
        editor.putString(Constants.PREF_DIAG_ACTIVE_RUN_ID, runId)
        editor.putLong(Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT, startedAt)
        editor.putLong(Constants.PREF_DIAG_ACTIVE_RUN_GENERATION, generation)
        if (runClock.bootCount != null) editor.putInt(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT, runClock.bootCount)
        else editor.remove(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT)
        if (bootEpoch == null) editor.remove(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH)
        else editor.putString(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH, bootEpoch)
        editor.putBoolean(Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED, incomingOriginVerified)
        editor.remove(Constants.PREF_DIAG_ACTIVE_BINDER_PID)
        if (incomingOriginVerified && callingPid != null && callingPid > 0) {
            editor.putInt(Constants.PREF_DIAG_ACTIVE_BINDER_PID, callingPid)
        }

        // Only an OS-bound origin may seed package-specific lifecycle observations.
        if (incomingOriginVerified) {
            for (key in listOf(Constants.PREF_DIAG_MODULE_LOADED_AT, Constants.PREF_DIAG_LAST_PACKAGE_LOADED)) {
                    if (key in extras.keySet()) applyExtra(editor, key, extras.get(key))
            }
        }
        return true
    }

    /**
     * Maps bundle extra values to SharedPreferences types safely.
     */
    fun applyExtra(editor: SharedPreferences.Editor, key: String, value: Any?): Boolean {
        when (value) {
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is CharSequence -> editor.putString(key, value.toString())
            is Array<*> -> {
                if (!value.all { it is String }) return false
                val stringSet = value.map { it as String }.toSet()
                editor.putStringSet(key, stringSet)
            }
            is Collection<*> -> {
                if (!value.all { it is String }) return false
                val stringSet = value.map { it as String }.toSet()
                editor.putStringSet(key, stringSet)
            }
            else -> {
                Log.w(TAG, "Unsupported preference value type for key '$key': ${value?.javaClass?.name}")
                return false
            }
        }
        return true
    }

    private fun validatedEventSequence(extras: DiagnosticPayload?): Long? {
        val sequence = extras?.get(Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE) as? Long
        return sequence?.takeIf { it in 1L..MAX_EVENT_SEQUENCE }
    }

    private fun applySequencedExtra(
        prefs: PreferenceReader,
        editor: SharedPreferences.Editor,
        key: String,
        value: Any?,
        sequence: Long?,
    ): Boolean {
        if (sequence == null) return applyExtra(editor, key, value)
        val sequenceKey = eventSequencePreferenceKey(key)
        if (sequence <= prefs.getLong(sequenceKey, 0L)) return false
        if (!applyExtra(editor, key, value)) return false
        editor.putLong(sequenceKey, sequence)
        return true
    }

    /** Validates and applies a single diagnostics request to SharedPreferences. */
    @Synchronized
    fun applyDiagnostics(
        context: Context,
        method: String?,
        extras: Bundle?,
        callingUid: Int? = null,
        callingPackages: Array<String>? = null,
        myUid: Int? = null,
        callingPid: Int? = null,
        verifiedBroadcastOrigin: String? = null,
        requireVerifiedOrigin: Boolean = false,
        allowTokenOnlyUnverifiedOrigin: Boolean = false,
        allowPriorBootUnverifiedReplacement: Boolean = false,
        remoteTargetWrite: Boolean? = null,
    ): Boolean {
        if (method == null) return false
        val payload = decodeAndValidatePayload(extras, method, requireBatchIdentity = false) ?: run {
            Log.w(TAG, "Rejecting diagnostics request with invalid payload schema")
            return false
        }
        val sameUid = callingUid != null && myUid != null && callingUid == myUid
        val isRemoteTargetWrite = remoteTargetWrite ?: !sameUid
        val authority = if (isRemoteTargetWrite) StoreAuthority.REMOTE_TARGET else StoreAuthority.MANAGER
        val prefs = preferencesFor(context, authority) ?: run {
            Log.w(TAG, "Rejecting diagnostics write: ${authority.name.lowercase()} preference store unavailable")
            return false
        }
        return applyDiagnosticsAgainstPreferences(
            context = context,
            method = method,
            extras = payload,
            callingUid = callingUid,
            callingPackages = callingPackages,
            myUid = myUid,
            callingPid = callingPid,
            verifiedBroadcastOrigin = verifiedBroadcastOrigin,
            requireVerifiedOrigin = requireVerifiedOrigin,
            allowTokenOnlyUnverifiedOrigin = allowTokenOnlyUnverifiedOrigin,
            allowPriorBootUnverifiedReplacement = allowPriorBootUnverifiedReplacement,
            prefs = SharedPreferenceReader(prefs),
            editor = prefs.edit(),
            commitChanges = true,
            // Remote IPC must not acknowledge an event whose sequence replayed
            // every diagnostic value. Local manager writes have no event sequence.
            strictSequence = isRemoteTargetWrite,
        )
    }

    /**
     * Validates a fallback run-start and dependent event as one preference
     * transaction. Both nested bundles are fully decoded before planning; event
     * validation sees the staged post-start state. Only after both pass is the
     * complete change set submitted through one real editor apply() call.
     */
    @Synchronized
    fun applyDiagnosticsBatch(
        context: Context,
        start: Bundle,
        eventMethod: String,
        event: Bundle,
        callingUid: Int? = null,
        callingPackages: Array<String>? = null,
        myUid: Int? = null,
        callingPid: Int? = null,
        verifiedBroadcastOrigin: String? = null,
        requireVerifiedOrigin: Boolean = false,
        allowTokenOnlyUnverifiedOrigin: Boolean = false,
        allowPriorBootUnverifiedReplacement: Boolean = false,
        remoteTargetWrite: Boolean? = null,
    ): Pair<Boolean, Boolean> {
        if (eventMethod !in setOf(
                Constants.METHOD_RECORD_DIAGNOSTICS,
                Constants.METHOD_RECORD_VERIFY,
                Constants.METHOD_CLEAR_VERIFY,
            )
        ) return false to false

        return try {
            val startPayload = decodeAndValidatePayload(
                start,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                requireBatchIdentity = true,
            ) ?: return false to false
            val eventPayload = decodeAndValidatePayload(
                event,
                eventMethod,
                requireBatchIdentity = true,
            ) ?: return false to false

            val sameUid = callingUid != null && myUid != null && callingUid == myUid
            val isRemoteTargetWrite = remoteTargetWrite ?: !sameUid
            val authority = if (isRemoteTargetWrite) StoreAuthority.REMOTE_TARGET else StoreAuthority.MANAGER
            val prefs = preferencesFor(context, authority) ?: run {
                Log.w(TAG, "Rejecting diagnostics batch: ${authority.name.lowercase()} preference store unavailable")
                return false to false
            }

            val stagedEditor = StagedEditor()
            val stagedReader = OverlayPreferenceReader(SharedPreferenceReader(prefs), stagedEditor)
            val startAccepted = applyDiagnosticsAgainstPreferences(
                context = context,
                method = Constants.METHOD_START_DIAGNOSTICS_RUN,
                extras = startPayload,
                callingUid = callingUid,
                callingPackages = callingPackages,
                myUid = myUid,
                callingPid = callingPid,
                verifiedBroadcastOrigin = verifiedBroadcastOrigin,
                requireVerifiedOrigin = requireVerifiedOrigin,
                allowTokenOnlyUnverifiedOrigin = allowTokenOnlyUnverifiedOrigin,
                allowPriorBootUnverifiedReplacement = allowPriorBootUnverifiedReplacement,
                prefs = stagedReader,
                editor = stagedEditor,
                commitChanges = false,
                strictSequence = true,
            )
            if (!startAccepted) return false to false

            val eventAccepted = applyDiagnosticsAgainstPreferences(
                context = context,
                method = eventMethod,
                extras = eventPayload,
                callingUid = callingUid,
                callingPackages = callingPackages,
                myUid = myUid,
                callingPid = callingPid,
                verifiedBroadcastOrigin = verifiedBroadcastOrigin,
                requireVerifiedOrigin = requireVerifiedOrigin,
                allowTokenOnlyUnverifiedOrigin = allowTokenOnlyUnverifiedOrigin,
                allowPriorBootUnverifiedReplacement = allowPriorBootUnverifiedReplacement,
                prefs = stagedReader,
                editor = stagedEditor,
                commitChanges = false,
                strictSequence = true,
            )
            if (!eventAccepted) return false to false

            stagedEditor.applyTo(prefs.edit())
            true to true
        } catch (_: RuntimeException) {
            Log.w(TAG, "Rejected malformed or invalid diagnostics batch")
            false to false
        }
    }

    private val DIAGNOSTIC_METADATA_KEYS = setOf(
        Constants.EXTRA_DIAGNOSTICS_TOKEN,
        Constants.EXTRA_DIAGNOSTICS_ORIGIN,
        Constants.EXTRA_DIAGNOSTICS_RUN_ID,
        Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT,
        Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION,
        Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT,
        Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH,
        Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE,
    )

    private val START_DIAGNOSTIC_KEYS = setOf(
        Constants.PREF_DIAG_MODULE_LOADED_AT,
        Constants.PREF_DIAG_LAST_PACKAGE_LOADED,
    )

    private val LONG_DIAGNOSTIC_KEYS = setOf(
        Constants.PREF_DIAG_MODULE_LOADED_AT,
        Constants.PREF_DIAG_LAST_PACKAGE_READY_AT,
        Constants.PREF_DIAG_VERIFY_AT,
        Constants.PREF_DIAG_FEATURE_HOOKS_AT,
        Constants.PREF_DIAG_SYSPROPS_HOOKS_AT,
        Constants.PREF_DIAG_FEATURE_INTERCEPTOR_AT,
        Constants.PREF_DIAG_SYSPROPS_INTERCEPTOR_AT,
    )

    private val STRING_DIAGNOSTIC_KEYS = setOf(
        Constants.PREF_DIAG_LAST_PACKAGE_LOADED,
        Constants.PREF_DIAG_LAST_PACKAGE_READY,
        Constants.PREF_DIAG_VERIFY_DEVICE,
        Constants.PREF_DIAG_VERIFY_PACKAGE,
    )

    private val BOOLEAN_DIAGNOSTIC_KEYS = setOf(
        Constants.PREF_DIAG_VERIFY_OK,
        Constants.PREF_DIAG_VERIFY_NATIVE_READY,
        Constants.PREF_DIAG_VERIFY_SYSPROPS,
        Constants.PREF_DIAG_FEATURE_HOOK_STRING,
        Constants.PREF_DIAG_FEATURE_HOOK_STRING_INT,
        Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE,
        Constants.PREF_DIAG_SYSPROPS_HOOK_GET,
        Constants.PREF_DIAG_SYSPROPS_HOOK_GET_DEFAULT,
    )

    private val EVENT_DIAGNOSTIC_KEYS = ALLOWED_DIAG_KEYS - setOf(
        Constants.PREF_DIAG_VERIFY_AT,
        Constants.PREF_DIAG_VERIFY_DEVICE,
        Constants.PREF_DIAG_VERIFY_PACKAGE,
        Constants.PREF_DIAG_VERIFY_OK,
        Constants.PREF_DIAG_VERIFY_FAILED,
        Constants.PREF_DIAG_VERIFY_NATIVE_READY,
        Constants.PREF_DIAG_VERIFY_SYSPROPS,
        Constants.PREF_DIAG_ACTIVE_PACKAGE,
        Constants.PREF_DIAG_ACTIVE_RUN_ID,
        Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT,
        Constants.PREF_DIAG_ACTIVE_RUN_GENERATION,
        Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT,
        Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH,
        Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED,
        Constants.PREF_DIAG_ACTIVE_BINDER_PID,
    )

    private val VERIFY_DIAGNOSTIC_KEYS = VERIFY_KEYS_TO_CLEAR

    /** Field identifiers that DeviceSpoofer/DiagnosticsActivity can actually verify. */
    private val VERIFY_FIELD_NAMES: Set<String> = buildSet {
        DeviceProps.allDevices.forEach { addAll(it.props.keys) }
        DeviceProps.allAndroidVersions.forEach { addAll(it.getAsMap().keys) }
    }

    private fun materializeVerifyFailed(value: Any): Set<String>? {
        val entries: List<*> = when (value) {
            is Array<*> -> value.asList()
            is Collection<*> -> {
                if (value.size > MAX_VERIFY_FAILED_ENTRIES) return null
                value.toList()
            }
            else -> return null
        }
        if (entries.size > MAX_VERIFY_FAILED_ENTRIES) return null
        var totalBytes = 0
        val fields = ArrayList<String>(entries.size)
        for (entry in entries) {
            val field = entry as? String ?: return null
            if (field.length > MAX_VERIFY_FAILED_ENTRY_LENGTH || field !in VERIFY_FIELD_NAMES) return null
            totalBytes += field.toByteArray(Charsets.UTF_8).size
            if (totalBytes > MAX_VERIFY_FAILED_TOTAL_BYTES) return null
            fields += field
        }
        return fields.toSet()
    }

    /**
     * Reads every Bundle value before any editor is created, enforces the same
     * schema for provider, receiver, and batch calls, and returns defensive values.
     */
    @Suppress("DEPRECATION")
    private fun decodeAndValidatePayload(
        bundle: Bundle?,
        method: String,
        requireBatchIdentity: Boolean,
    ): DiagnosticPayload? {
        return try {
        val isStart = method == Constants.METHOD_START_DIAGNOSTICS_RUN
        if (method !in setOf(
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                Constants.METHOD_RECORD_VERIFY,
                Constants.METHOD_CLEAR_VERIFY,
            )
        ) return null
        if (bundle == null && method != Constants.METHOD_CLEAR_VERIFY) return null

        val keys = bundle?.keySet()?.toSet().orEmpty()
        val allowedDiagnosticKeys = when {
            isStart -> START_DIAGNOSTIC_KEYS
            method == Constants.METHOD_RECORD_VERIFY -> VERIFY_DIAGNOSTIC_KEYS
            method == Constants.METHOD_CLEAR_VERIFY -> emptySet()
            else -> EVENT_DIAGNOSTIC_KEYS
        }
        if (!keys.all { it in DIAGNOSTIC_METADATA_KEYS || it in allowedDiagnosticKeys }) return null
        if ((method == Constants.METHOD_RECORD_DIAGNOSTICS || method == Constants.METHOD_RECORD_VERIFY) &&
            keys.none { it in allowedDiagnosticKeys }
        ) return null

        // Bundle.get() forces lazy unparcelling. Copy String collections now so no
        // caller-owned or parcel-backed mutable value is consulted after staging.
        val values = linkedMapOf<String, Any>()
        for (key in keys) {
            val value = bundle?.get(key) ?: return null
            val materialized = when (key) {
                Constants.EXTRA_DIAGNOSTICS_TOKEN -> (value as? String)
                    ?.takeIf { it.isNotEmpty() && it.length <= 512 }
                Constants.EXTRA_DIAGNOSTICS_ORIGIN -> (value as? String)
                    ?.takeIf { it.isNotEmpty() && it.length <= 255 }
                Constants.EXTRA_DIAGNOSTICS_RUN_ID -> (value as? String)
                    ?.takeIf { it.length in 1..128 && it.matches(Regex("[A-Za-z0-9._-]+")) }
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION -> (value as? Long)?.takeIf { it > 0L }
                // -1 is the reporter's explicit "numeric boot counter unavailable" sentinel.
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT -> (value as? Int)?.takeIf { it >= -1 }
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH -> (value as? String)
                    ?.takeIf { it.isNotEmpty() && it.length <= 128 }
                Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE -> (value as? Long)
                    ?.takeIf { it in 1L..MAX_EVENT_SEQUENCE }
                in LONG_DIAGNOSTIC_KEYS -> (value as? Long)?.takeIf {
                    it > 0L && (key != Constants.PREF_DIAG_VERIFY_AT || it <= MAX_VERIFY_TIMESTAMP)
                }
                in STRING_DIAGNOSTIC_KEYS -> (value as? String)?.takeIf {
                    it.isNotBlank() && it.length <= if (key == Constants.PREF_DIAG_VERIFY_PACKAGE) 255 else 512
                }
                in BOOLEAN_DIAGNOSTIC_KEYS -> value as? Boolean
                Constants.PREF_DIAG_VERIFY_FAILED -> materializeVerifyFailed(value)
                else -> null
            } ?: return null
            values[key] = materialized
        }

        if (isStart && Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE in keys) return null
        if (method == Constants.METHOD_RECORD_VERIFY) {
            val requiredVerifyIdentity = setOf(
                Constants.EXTRA_DIAGNOSTICS_ORIGIN,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID,
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH,
            )
            if (!keys.containsAll(VERIFY_KEYS_TO_CLEAR + requiredVerifyIdentity)) return null
            val origin = values[Constants.EXTRA_DIAGNOSTICS_ORIGIN] as? String ?: return null
            val verifyPackage = values[Constants.PREF_DIAG_VERIFY_PACKAGE] as? String ?: return null
            val verifyOk = values[Constants.PREF_DIAG_VERIFY_OK] as? Boolean ?: return null
            val failedFields = values[Constants.PREF_DIAG_VERIFY_FAILED] as? Set<*> ?: return null
            if (!ScopePolicy.shouldSpoof(origin) || verifyPackage != origin ||
                verifyOk != failedFields.isEmpty()
            ) return null
        }
        if (requireBatchIdentity) {
            val required = setOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID,
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION,
            )
            if (!keys.containsAll(required)) return null
            if (!isStart && Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE !in keys) return null
        }
        DiagnosticPayload(values)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun applyDiagnosticsAgainstPreferences(
        context: Context,
        method: String?,
        extras: DiagnosticPayload,
        callingUid: Int?,
        callingPackages: Array<String>?,
        myUid: Int?,
        callingPid: Int?,
        verifiedBroadcastOrigin: String?,
        requireVerifiedOrigin: Boolean,
        allowTokenOnlyUnverifiedOrigin: Boolean,
        allowPriorBootUnverifiedReplacement: Boolean,
        prefs: PreferenceReader,
        editor: SharedPreferences.Editor,
        commitChanges: Boolean,
        strictSequence: Boolean,
    ): Boolean {
        if (method == null) return false
        return try {
            val sameUid = callingUid != null && myUid != null && callingUid == myUid
            val claimedOrigin = extras.getString(Constants.EXTRA_DIAGNOSTICS_ORIGIN)
            val providerOrigin = if (callingUid != null && myUid != null && callingUid != myUid) {
                DiagnosticsOriginPolicy.providerVerifiedOrigin(claimedOrigin, callingPackages)
            } else {
                null
            }
            val verifiedOrigin = when {
                verifiedBroadcastOrigin != null && verifiedBroadcastOrigin == claimedOrigin &&
                    callingPackages?.contains(verifiedBroadcastOrigin) == true -> verifiedBroadcastOrigin
                providerOrigin != null -> providerOrigin
                else -> null
            }
            val tokenOnlyBroadcastAllowed = allowTokenOnlyUnverifiedOrigin &&
                callingUid == null && myUid == null && verifiedBroadcastOrigin == null
            if (requireVerifiedOrigin && verifiedOrigin != claimedOrigin) {
                Log.w(TAG, "Rejecting diagnostics broadcast with mismatched OS sender origin")
                return false
            }
            if (!sameUid) {
                if (callingUid != null && myUid != null &&
                    !isPayloadCallerAuthorized(callingUid, myUid, callingPackages, extras)
                ) {
                    Log.w(TAG, "Rejecting unauthorized diagnostics IPC call")
                    return false
                }
                if (!isRemoteSourceAuthorized(extras, callingPackages, prefs)) {
                    Log.w(TAG, "Rejecting unauthenticated or invalid-origin diagnostics call")
                    return false
                }
            }
            if (!sameUid && verifiedOrigin != claimedOrigin && !tokenOnlyBroadcastAllowed) {
                Log.w(TAG, "Rejecting diagnostics write with unverified origin")
                return false
            }

            val eventSequence = if (!sameUid && method in SEQUENCED_METHODS) {
                validatedEventSequence(extras) ?: run {
                    Log.w(TAG, "Rejecting diagnostics event with missing, mistyped, or out-of-range sequence")
                    return false
                }
            } else {
                null
            }

            when (method) {
                Constants.METHOD_START_DIAGNOSTICS_RUN -> {
                    val runClock = currentRunClock(context) ?: run {
                        Log.w(TAG, "Rejecting diagnostics run start: OS boot/elapsed clock unavailable")
                        return false
                    }
                    val accepted = startRun(
                        prefs,
                        editor,
                        extras,
                        verifiedOrigin,
                        callingPid,
                        tokenOnlyBroadcastAllowed,
                        allowPriorBootUnverifiedReplacement,
                        runClock,
                    )
                    if (accepted && commitChanges) editor.apply()
                    accepted
                }
                Constants.METHOD_RECORD_DIAGNOSTICS,
                Constants.METHOD_RECORD_VERIFY -> {
                    if ((method == Constants.METHOD_RECORD_VERIFY || !sameUid) &&
                        !currentBootRunIdentityMatches(context, prefs, extras)
                    ) {
                        Log.w(TAG, "Rejecting diagnostics update for inactive target run")
                        return false
                    }
                    if (method == Constants.METHOD_RECORD_VERIFY) {
                        val verifyPackage = extras.getString(Constants.PREF_DIAG_VERIFY_PACKAGE)
                        val activePackage = prefs.getString(Constants.PREF_DIAG_ACTIVE_PACKAGE, null)
                        if (verifyPackage == null || verifyPackage != activePackage ||
                            !ScopePolicy.shouldSpoof(verifyPackage)
                        ) {
                            Log.w(TAG, "Rejecting VERIFY payload whose package does not match the active run")
                            return false
                        }
                        if (strictSequence && eventSequence != null && VERIFY_KEYS_TO_CLEAR.any { key ->
                                eventSequence <= prefs.getLong(eventSequencePreferenceKey(key), 0L)
                            }
                        ) {
                            Log.w(TAG, "Rejecting stale or partially replayed VERIFY event")
                            return false
                        }
                    }
                    val activeOriginVerified = prefs.getBoolean(
                        Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED,
                        false,
                    )
                    if (!sameUid && activeOriginVerified && verifiedOrigin != claimedOrigin) {
                        Log.w(TAG, "Rejecting unverified update to preserve active verified diagnostics")
                        return false
                    }
                    if (!sameUid && verifiedOrigin == claimedOrigin) {
                        editor.putBoolean(Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED, true)
                        if (callingPid != null && callingPid > 0) {
                            editor.putInt(Constants.PREF_DIAG_ACTIVE_BINDER_PID, callingPid)
                        }
                    }
                    var anySequencedValueApplied = false
                    var hasSequencedValues = false
                    for (key in extras.keySet()) {
                        if (key !in ALLOWED_DIAG_KEYS) continue
                        if (key == Constants.PREF_DIAG_ACTIVE_PACKAGE ||
                            key == Constants.PREF_DIAG_ACTIVE_RUN_ID ||
                            key == Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT ||
                            key == Constants.PREF_DIAG_ACTIVE_RUN_GENERATION ||
                            key == Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT ||
                            key == Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH ||
                            key == Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED ||
                            key == Constants.PREF_DIAG_ACTIVE_BINDER_PID
                        ) continue
                        hasSequencedValues = true
                        val applied = applySequencedExtra(prefs, editor, key, extras.get(key), eventSequence)
                        if (applied) anySequencedValueApplied = true
                    }
                    // Keep per-key ordering: stale values do not overwrite newer ones,
                    // while other fresh keys in the same event may still advance. If
                    // every sequenced value is a replay, reject the batch so its start
                    // is not committed by itself.
                    if (strictSequence && eventSequence != null && hasSequencedValues &&
                        !anySequencedValueApplied
                    ) return false
                    if (commitChanges) editor.apply()
                    true
                }
                Constants.METHOD_CLEAR_VERIFY -> {
                    if (!sameUid && !currentBootRunIdentityMatches(context, prefs, extras)) {
                        Log.w(TAG, "Rejecting VERIFY clear for inactive target run")
                        return false
                    }
                    if (!sameUid && prefs.getBoolean(
                            Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED,
                            false,
                        ) && verifiedOrigin != claimedOrigin
                    ) {
                        Log.w(TAG, "Rejecting unverified VERIFY clear to preserve active verified diagnostics")
                        return false
                    }
                    var anyCleared = false
                    VERIFY_KEYS_TO_CLEAR.forEach { key ->
                        if (eventSequence == null) {
                            editor.remove(key)
                            anyCleared = true
                        } else {
                            val sequenceKey = eventSequencePreferenceKey(key)
                            if (eventSequence > prefs.getLong(sequenceKey, 0L)) {
                                editor.remove(key)
                                editor.putLong(sequenceKey, eventSequence)
                                anyCleared = true
                            }
                        }
                    }
                    if (strictSequence && eventSequence != null && !anyCleared) return false
                    if (commitChanges) editor.apply()
                    true
                }
                else -> {
                    Log.w(TAG, "Unknown diagnostics method")
                    false
                }
            }
        } catch (_: RuntimeException) {
            Log.w(TAG, "Failed applying diagnostics request")
            false
        }
    }
}
