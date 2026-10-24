package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Dispatches diagnostic milestones and device-spoof VERIFY telemetry from hooked
 * target processes (like Google Photos) back to the module manager application via
 * [DiagnosticsProvider].
 *
 * Runs asynchronously on a single-thread daemon executor with FIFO ordering so that
 * target application startup and hook execution paths are never blocked and milestone
 * updates never race. Authentication or dispatch failures remain unknown and are logged;
 * a returned IPC dispatch call is never treated as proof of persistence.
 */
object DiagnosticsReporter {

    private const val TAG = "Pixelify"
    private const val MAX_PROVIDER_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 150L
    private const val MAX_QUEUED_EVENTS = 64
    private const val MAX_FALLBACK_ATTEMPTS = 3
    internal data class BroadcastDispatchResult(
        /** The sendBroadcast call returned; this is not receiver acceptance or persistence. */
        val callReturned: Boolean,
        /** True when API 34+ identity sharing was required for this dispatch attempt. */
        val identityPath: Boolean,
        val failure: Throwable? = null,
    ) {
        /** This API has no synchronous receiver-acceptance acknowledgement. */
        val receiverAcceptanceConfirmed: Boolean get() = false
    }

    private data class TargetRun(
        val origin: String,
        val id: String,
        val startedAt: Long,
        val generation: Long,
        val bootEpochHash: String?,
        val startFields: Map<String, Any>,
        var lastEventSequence: Long = 0L,
    ) {
        val handle = TargetRunHandle(runId = id, origin = origin, generation = generation)
    }

    /** Immutable identity captured by a lifecycle callback and all its telemetry. */
    data class TargetRunHandle(
        val runId: String,
        val origin: String,
        val generation: Long,
    )

    @Volatile
    private var targetRun: TargetRun? = null
    private var lastRunGeneration = 0L
    private var providerConfirmedRunId: String? = null

    private val runLock = Any()
    @Volatile
    internal var testEnqueueObserver: ((String, String?, Long?) -> Unit)? = null
    @Volatile
    internal var testBeforeDispatchLock: ((String) -> Unit)? = null
    @Volatile
    internal var testFallbackOutcomeObserver: ((String, BroadcastDispatchResult) -> Unit)? = null
    @Volatile
    internal var testEventSequenceObserver: ((String, String?, Long?) -> Unit)? = null
    @Volatile
    internal var testFallbackDispatchOverride: ((String, Bundle, Bundle?) -> BroadcastDispatchResult)? = null
    @Volatile
    internal var testStartIfAbsentObserver: (() -> Unit)? = null
    internal var testProviderUri: Uri? = null
    internal var testBroadcastToken: String? = null
    internal var testBootCount: Int? = null
    internal var testBootEpochResolver: (() -> String?)? = null
    internal var testResolvedContext: Context? = null
    private val PROVIDER_URI: Uri
        get() = testProviderUri ?: runCatching {
            Uri.parse("content://${Constants.DIAGNOSTICS_AUTHORITY}")
        }.getOrNull() ?: Uri.EMPTY

    private val executor: ExecutorService by lazy {
        val factory = ThreadFactory { runnable ->
            Thread(runnable, "pixelify-diag-dispatcher").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            // Bounded and non-blocking: if saturated, reject the newest event rather
            // than evicting an earlier run start. Later telemetry can replay the
            // retained start payload before sending any run-bound values.
            LinkedBlockingQueue(MAX_QUEUED_EVENTS),
            factory,
            ThreadPoolExecutor.AbortPolicy()
        )
    }

    /**
     * Resolves the current application context if available in the process.
     */
    fun resolveContext(explicitContext: Context? = null): Context? {
        if (explicitContext != null) return explicitContext
        testResolvedContext?.let { return it }
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getDeclaredMethod("currentApplication")
            currentAppMethod.invoke(null) as? Context
        }.getOrNull()
    }

    /**
     * Asynchronously records lifecycle milestones (e.g. module loaded, package loaded, package ready).
     */
    fun recordMilestone(
        context: Context? = null,
        runHandle: TargetRunHandle? = null,
        block: (Bundle) -> Unit,
    ) {
        val bundle = Bundle()
        block(bundle)
        dispatch(context, Constants.METHOD_RECORD_DIAGNOSTICS, bundle, runHandle = runHandle)
    }

    /** Starts a package/process-scoped diagnostics run and invalidates previous state in the store. */
    fun startTargetRun(
        packageName: String,
        moduleLoadedAt: Long?,
        packageLoadCallbackObserved: Boolean = true,
    ): TargetRunHandle? {
        if (!ScopePolicy.shouldSpoof(packageName)) return null
        return synchronized(runLock) {
            startTargetRunLocked(packageName, moduleLoadedAt, packageLoadCallbackObserved).handle
        }
    }

    /**
     * Atomically creates the initial run if absent, otherwise returns the existing
     * run's immutable identity without replacing it. In particular, racing ready
     * callbacks in one process cannot each publish a new origin.
     */
    internal fun startTargetRunIfAbsent(
        packageName: String,
        moduleLoadedAt: Long?,
        packageLoadCallbackObserved: Boolean = false,
    ): TargetRunHandle? {
        if (!ScopePolicy.shouldSpoof(packageName)) return null
        testStartIfAbsentObserver?.invoke()
        return synchronized(runLock) {
            (targetRun ?: startTargetRunLocked(
                packageName = packageName,
                moduleLoadedAt = moduleLoadedAt,
                packageLoadCallbackObserved = packageLoadCallbackObserved,
            )).handle
        }
    }

    /** Caller must hold [runLock]. */
    private fun startTargetRunLocked(
        packageName: String,
        moduleLoadedAt: Long?,
        packageLoadCallbackObserved: Boolean,
    ): TargetRun {
        val timestamp = System.currentTimeMillis()
        // Generation, not wall-clock time, defines run ordering.
        val generation = maxOf(SystemClock.elapsedRealtimeNanos(), lastRunGeneration + 1L)
        lastRunGeneration = generation
        val runId = buildString {
            append(generation.toString().padStart(20, '0'))
            append('-')
            append(java.util.UUID.randomUUID())
        }
        val fields = buildTargetRunStartFields(
            packageName = packageName,
            runId = runId,
            startedAt = timestamp,
            generation = generation,
            bootEpochHash = resolveBootEpochHash(),
            moduleLoadedAt = moduleLoadedAt,
            packageLoadCallbackObserved = packageLoadCallbackObserved,
        )
        val bootEpochHash = fields[Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH] as? String
        val run = TargetRun(packageName, runId, timestamp, generation, bootEpochHash, fields)
        targetRun = run
        // dispatch re-enters runLock (a JVM monitor is reentrant) and submits
        // the start before another event can snapshot the newly published run.
        dispatch(null, Constants.METHOD_START_DIAGNOSTICS_RUN, Bundle(), runSnapshot = run)
        return run
    }

    /** Pure start-run payload builder used to keep lifecycle claims tied to observed callbacks. */
    internal fun buildTargetRunStartFields(
        packageName: String,
        runId: String,
        startedAt: Long,
        generation: Long,
        bootEpochHash: String? = null,
        moduleLoadedAt: Long?,
        packageLoadCallbackObserved: Boolean,
    ): Map<String, Any> = buildMap {
        put(Constants.EXTRA_DIAGNOSTICS_ORIGIN, packageName)
        put(Constants.EXTRA_DIAGNOSTICS_RUN_ID, runId)
        put(Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT, startedAt)
        put(Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION, generation)
        bootEpochHash?.let { put(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH, it) }
        moduleLoadedAt?.let { put(Constants.PREF_DIAG_MODULE_LOADED_AT, it) }
        if (packageLoadCallbackObserved) {
            put(Constants.PREF_DIAG_LAST_PACKAGE_LOADED, packageName)
        }
    }

    internal fun currentTargetRunHandle(): TargetRunHandle? = synchronized(runLock) {
        targetRun?.handle
    }

    /** Returns false for a secondary package callback in a process owned by another run origin. */
    internal fun supportsCallbackPackage(
        packageName: String,
        runHandle: TargetRunHandle? = null,
    ): Boolean = synchronized(runLock) {
        val run = targetRun ?: return@synchronized false
        if (runHandle != null && run.handle != runHandle) return@synchronized false
        DiagnosticsOriginPolicy.supportsTargetRunCallback(run.origin, packageName)
    }

    private fun currentRunHandleForPackage(packageName: String?): TargetRunHandle? = synchronized(runLock) {
        val run = targetRun ?: return@synchronized null
        if (packageName == null || run.origin != packageName) return@synchronized null
        run.handle
    }

    private fun supportsVerifyPackage(
        packageName: String?,
        runHandle: TargetRunHandle?,
    ): Boolean = synchronized(runLock) {
        val run = targetRun ?: return@synchronized false
        (runHandle == null || run.handle == runHandle) &&
            packageName != null && ScopePolicy.shouldSpoof(packageName) && run.origin == packageName
    }

    internal fun clearTargetRunForTests() {
        synchronized(runLock) {
            targetRun = null
            lastRunGeneration = 0L
            providerConfirmedRunId = null
        }
    }

    internal fun attachIpcToken(bundle: Bundle, token: String) {
        bundle.putString(Constants.EXTRA_DIAGNOSTICS_TOKEN, token)
    }

    /**
     * Asynchronously records device-spoof VERIFY results.
     */
    fun recordVerify(
        context: Context? = null,
        deviceName: String,
        failed: List<String>,
        packageName: String?,
        nativeReady: Boolean,
        syspropsHooked: Boolean,
        runHandle: TargetRunHandle? = null,
    ) {
        val pinnedRunHandle = runHandle ?: currentRunHandleForPackage(packageName)
        if (pinnedRunHandle == null || !supportsVerifyPackage(packageName, pinnedRunHandle)) {
            Log.w(TAG, "VERIFY telemetry has no supported current-run package attribution; report remains unknown")
            return
        }
        val attributedPackage = checkNotNull(packageName)
        val failedFieldNames = verifyFailureFieldNames(failed)
        val bundle = Bundle().apply {
            putLong(Constants.PREF_DIAG_VERIFY_AT, System.currentTimeMillis())
            putString(Constants.PREF_DIAG_VERIFY_DEVICE, deviceName)
            putString(Constants.PREF_DIAG_VERIFY_PACKAGE, attributedPackage)
            putBoolean(Constants.PREF_DIAG_VERIFY_OK, failedFieldNames.isEmpty())
            // Persist identifiers only; actual values and error detail stay local
            // to the target log/UI and cannot enter the bounded IPC field schema.
            putStringArrayList(Constants.PREF_DIAG_VERIFY_FAILED, ArrayList(failedFieldNames))
            putBoolean(Constants.PREF_DIAG_VERIFY_NATIVE_READY, nativeReady)
            putBoolean(Constants.PREF_DIAG_VERIFY_SYSPROPS, syspropsHooked)
        }
        dispatch(context, Constants.METHOD_RECORD_VERIFY, bundle, runHandle = pinnedRunHandle)
    }

    internal fun verifyFailureFieldNames(failed: List<String>): ArrayList<String> =
        ArrayList(failed.map { it.substringBefore('(') })

    /** Records the individual feature-interceptor registration results. */
    fun recordFeatureHookRegistration(
        hasSystemFeature: Boolean,
        hasSystemFeatureWithFlags: Boolean,
        getSystemAvailableFeatures: Boolean,
        runHandle: TargetRunHandle? = null,
    ) {
        val bundle = Bundle().apply {
            putLong(Constants.PREF_DIAG_FEATURE_HOOKS_AT, System.currentTimeMillis())
            putBoolean(Constants.PREF_DIAG_FEATURE_HOOK_STRING, hasSystemFeature)
            putBoolean(Constants.PREF_DIAG_FEATURE_HOOK_STRING_INT, hasSystemFeatureWithFlags)
            putBoolean(Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE, getSystemAvailableFeatures)
        }
        dispatch(null, Constants.METHOD_RECORD_DIAGNOSTICS, bundle, runHandle = runHandle)
    }

    /** Records individual SystemProperties overload registrations when attempted. */
    fun recordSystemPropertiesHookRegistration(
        get: Boolean,
        getWithDefault: Boolean,
        runHandle: TargetRunHandle? = null,
    ) {
        val bundle = Bundle().apply {
            putLong(Constants.PREF_DIAG_SYSPROPS_HOOKS_AT, System.currentTimeMillis())
            putBoolean(Constants.PREF_DIAG_SYSPROPS_HOOK_GET, get)
            putBoolean(Constants.PREF_DIAG_SYSPROPS_HOOK_GET_DEFAULT, getWithDefault)
        }
        dispatch(null, Constants.METHOD_RECORD_DIAGNOSTICS, bundle, runHandle = runHandle)
    }

    /** Records only the first interceptor entry, attributed to its captured target run. */
    fun recordInterceptorInvocation(
        featureInterceptor: Boolean,
        runHandle: TargetRunHandle? = null,
    ) {
        if (runHandle == null) {
            Log.w(TAG, "Interceptor invocation has no captured target run; dropping diagnostics event")
            return
        }
        val key = if (featureInterceptor) {
            Constants.PREF_DIAG_FEATURE_INTERCEPTOR_AT
        } else {
            Constants.PREF_DIAG_SYSPROPS_INTERCEPTOR_AT
        }
        dispatch(null, Constants.METHOD_RECORD_DIAGNOSTICS, Bundle().apply {
            putLong(key, System.currentTimeMillis())
        }, runHandle = runHandle)
    }

    /**
     * Asynchronously clears persisted VERIFY diagnostics when spoofing is disabled.
     */
    fun clearVerify(
        context: Context? = null,
        packageName: String? = null,
        runHandle: TargetRunHandle? = null,
    ) {
        val pinnedRunHandle = if (packageName != null) {
            runHandle ?: currentRunHandleForPackage(packageName)
        } else {
            runHandle
        }
        if (packageName != null &&
            (pinnedRunHandle == null || !supportsCallbackPackage(packageName, pinnedRunHandle))
        ) {
            Log.w(TAG, "VERIFY clear for a secondary or stale same-process package is unsupported; report remains unknown")
            return
        }
        dispatch(context, Constants.METHOD_CLEAR_VERIFY, Bundle(), runHandle = pinnedRunHandle)
    }

    /**
     * Resolves the per-install diagnostics token for provider and broadcast authentication.
     *
     * Only the Xposed remote-preferences store is consulted: this reporter runs inside
     * a hooked target process, where the module's MODE_PRIVATE preference file is not
     * readable cross-UID, and [PrefUtils.getPrefs] would resolve to the host application's
     * own preferences instead of the module's. The module process keeps its local copy
     * aligned with this store via [DiagnosticsStore.convergeBroadcastToken].
     */
    private fun resolveBroadcastToken(): String? {
        testBroadcastToken?.let { return it.takeIf(String::isNotEmpty) }
        return try {
            val module = PixelifyModule.instance ?: run {
                Log.w(TAG, "Diagnostics IPC token unavailable: module remote-preference source is not initialized")
                return null
            }
            val prefs = module.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
            val token = prefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)
            token?.takeIf { it.isNotEmpty() } ?: run {
                Log.w(TAG, "Diagnostics IPC token unavailable: module app has not synchronized a token to remote prefs")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Diagnostics IPC token lookup failed; remote report will remain unknown", t)
            null
        }
    }

    private fun resolveBootCount(context: Context): Int? {
        testBootCount?.let { return it.takeIf { count -> count >= 0 } }
        return runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.BOOT_COUNT,
                -1,
            )
        }.getOrNull()?.takeIf { it >= 0 }
    }

    private fun resolveBootEpochHash(): String? {
        val testResolver = testBootEpochResolver
        return if (testResolver != null) testResolver() else DiagnosticsBootEpoch.currentHash()
    }

    /**
     * Sends [intent] with sender identity shared so a receiver on API 34+ can verify
     * the caller via getSentFromUid()/getSentFromPackage(). Isolated in its own method,
     * guarded by an SDK_INT >= 34 check at the call site, so newer-API references are
     * never verified on older runtimes (no reflection needed).
     */
    @androidx.annotation.RequiresApi(34)
    private fun sendBroadcastSharingIdentity(context: Context, intent: android.content.Intent) {
        val options = android.app.BroadcastOptions.makeBasic()
        options.setShareIdentityEnabled(true)
        context.sendBroadcast(intent, null, options.toBundle())
    }

    /**
     * Selects the authenticated fallback send path. API 34+ must not retry without
     * sender identity, because the receiver cannot bind package-specific data then.
     */
    internal fun dispatchBroadcastFallback(
        sdkInt: Int,
        sendIdentityShared: () -> Unit,
        sendWithoutIdentity: () -> Unit,
    ): BroadcastDispatchResult = if (sdkInt >= 34) {
        try {
            sendIdentityShared()
            BroadcastDispatchResult(callReturned = true, identityPath = true)
        } catch (t: Throwable) {
            BroadcastDispatchResult(callReturned = false, identityPath = true, failure = t)
        }
    } else {
        try {
            sendWithoutIdentity()
            BroadcastDispatchResult(callReturned = true, identityPath = false)
        } catch (t: Throwable) {
            BroadcastDispatchResult(callReturned = false, identityPath = false, failure = t)
        }
    }

    private fun dispatch(
        explicitContext: Context?,
        method: String,
        extras: Bundle,
        runSnapshot: TargetRun? = null,
        runHandle: TargetRunHandle? = null,
    ) {
        try {
            testBeforeDispatchLock?.invoke(method)
            synchronized(runLock) {
                if (runHandle != null && targetRun?.handle != runHandle) {
                    Log.w(TAG, "Dropping stale diagnostics event '$method' for inactive target run")
                    return
                }
                // Capture the active run and enqueue while holding the same lock used
                // by startTargetRun, preserving start-before-event queue order.
                val run = runSnapshot ?: targetRun
                val eventSequence = if (method != Constants.METHOD_START_DIAGNOSTICS_RUN && run != null) {
                    if (run.lastEventSequence >= DiagnosticsStore.MAX_EVENT_SEQUENCE) {
                        Log.w(TAG, "Diagnostics event sequence exhausted; run-bound event remains unknown")
                        return
                    }
                    (++run.lastEventSequence)
                } else {
                    null
                }
                val payload = if (method == Constants.METHOD_START_DIAGNOSTICS_RUN && run != null) {
                    bundleFromFields(run.startFields)
                } else {
                    Bundle(extras)
                }
                if (method != Constants.METHOD_START_DIAGNOSTICS_RUN) {
                    run?.let {
                        payload.putString(Constants.EXTRA_DIAGNOSTICS_ORIGIN, it.origin)
                        payload.putString(Constants.EXTRA_DIAGNOSTICS_RUN_ID, it.id)
                        payload.putLong(Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT, it.startedAt)
                        payload.putLong(Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION, it.generation)
                        it.bootEpochHash?.let { epoch ->
                            payload.putString(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH, epoch)
                        }
                        eventSequence?.let { sequence ->
                            payload.putLong(Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE, sequence)
                        }
                    }
                }
                eventSequence?.let { sequence ->
                    testEventSequenceObserver?.invoke(method, run?.id, sequence)
                }
                testEnqueueObserver?.let { observer ->
                    observer(method, run?.id, run?.generation)
                    return
                }
                executor.execute {
                    try {
                        dispatchQueued(explicitContext, method, payload, run)
                    } catch (t: Throwable) {
                        Log.d(TAG, "Diagnostics dispatch skipped or failed: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to schedule diagnostics dispatch; result remains unknown", t)
        }
    }

    private fun bundleFromFields(fields: Map<String, Any>): Bundle = Bundle().apply {
        fields.forEach { (key, value) ->
            when (value) {
                is String -> putString(key, value)
                is Long -> putLong(key, value)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
            }
        }
    }

    private fun dispatchQueued(
        explicitContext: Context?,
        method: String,
        payload: Bundle,
        run: TargetRun?,
    ) {
        val context = resolveContextWithRetries(explicitContext) ?: run {
            Log.w(TAG, "Diagnostics report not dispatched: process context unavailable; persistence is unknown")
            return
        }
        val token = resolveBroadcastToken() ?: run {
            Log.w(TAG, "Diagnostics report not dispatched: no synchronized auth token; persistence is unknown")
            return
        }
        val bootCount = resolveBootCount(context)

        val eventPayload = Bundle(payload).apply {
            bootCount?.let { putInt(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT, it) }
            attachIpcToken(this, token)
        }

        if (method != Constants.METHOD_START_DIAGNOSTICS_RUN && run != null && !isProviderConfirmed(run.id)) {
            val replayPayload = bundleFromFields(run.startFields).apply {
                bootCount?.let { putInt(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT, it) }
                attachIpcToken(this, token)
            }
            if (!providerWriteWithRetries(context, Constants.METHOD_START_DIAGNOSTICS_RUN, replayPayload)) {
                Log.w(TAG, "Target run start remains unconfirmed; retrying start plus dependent telemetry in one receiver batch")
                dispatchFallbackWithRetries(
                    context = context,
                    method = method,
                    payload = eventPayload,
                    batchStartPayload = replayPayload,
                )
                return
            }
            markProviderConfirmed(run.id)
        }

        if (providerWriteWithRetries(context, method, eventPayload)) {
            if (method == Constants.METHOD_START_DIAGNOSTICS_RUN && run != null) {
                markProviderConfirmed(run.id)
            }
            return
        }

        dispatchFallbackWithRetries(context, method, eventPayload)
    }

    private fun resolveContextWithRetries(explicitContext: Context?): Context? {
        repeat(MAX_PROVIDER_ATTEMPTS) { attempt ->
            resolveContext(explicitContext)?.let { return it }
            if (attempt < MAX_PROVIDER_ATTEMPTS - 1 && !sleepBeforeRetry()) return null
        }
        return null
    }

    private fun providerWriteWithRetries(context: Context, method: String, payload: Bundle): Boolean {
        repeat(MAX_PROVIDER_ATTEMPTS) { attempt ->
            val succeeded = try {
                val result = context.contentResolver.call(PROVIDER_URI, method, null, payload)
                result?.getBoolean("success", false) == true
            } catch (t: Throwable) {
                Log.d(TAG, "ContentProvider call failed for '$method': ${t.message}")
                false
            }
            if (succeeded) return true
            if (attempt < MAX_PROVIDER_ATTEMPTS - 1 && !sleepBeforeRetry()) return false
        }
        return false
    }

    private fun dispatchFallbackWithRetries(
        context: Context,
        method: String,
        payload: Bundle,
        batchStartPayload: Bundle? = null,
    ) {
        val wireMethod = if (batchStartPayload != null) {
            Constants.METHOD_RECORD_DIAGNOSTICS_BATCH
        } else {
            method
        }
        repeat(MAX_FALLBACK_ATTEMPTS) { attempt ->
            val outcome = dispatchFallback(context, method, payload, batchStartPayload)
                ?: BroadcastDispatchResult(callReturned = false, identityPath = Build.VERSION.SDK_INT >= 34)
            logFallbackOutcome(wireMethod, outcome, attempt + 1)
            if (attempt < MAX_FALLBACK_ATTEMPTS - 1 && !sleepBeforeRetry()) return
        }
        Log.w(
            TAG,
            "Diagnostics fallback retry budget exhausted for '$wireMethod'; receiver acceptance and persistence remain unknown",
        )
    }

    private fun dispatchFallback(
        context: Context,
        method: String,
        payload: Bundle,
        batchStartPayload: Bundle? = null,
    ): BroadcastDispatchResult? {
        testFallbackDispatchOverride?.let { override ->
            return override(
                if (batchStartPayload != null) Constants.METHOD_RECORD_DIAGNOSTICS_BATCH else method,
                Bundle(payload),
                batchStartPayload?.let { Bundle(it) },
            )
        }
        return try {
            val broadcastIntent = android.content.Intent(Constants.ACTION_RECORD_DIAGNOSTICS).apply {
                setPackage(Constants.PACKAGE_NAME_MODULE)
                component = android.content.ComponentName(
                    Constants.PACKAGE_NAME_MODULE,
                    DiagnosticsReceiver::class.java.name,
                )
                if (batchStartPayload == null) {
                    putExtra(Constants.EXTRA_DIAGNOSTICS_METHOD, method)
                    putExtras(payload)
                } else {
                    putExtra(Constants.EXTRA_DIAGNOSTICS_METHOD, Constants.METHOD_RECORD_DIAGNOSTICS_BATCH)
                    putExtra(Constants.EXTRA_DIAGNOSTICS_BATCH_START, Bundle(batchStartPayload))
                    putExtra(Constants.EXTRA_DIAGNOSTICS_BATCH_EVENT_METHOD, method)
                    putExtra(Constants.EXTRA_DIAGNOSTICS_BATCH_EVENT, Bundle(payload))
                }
                addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
            }
            dispatchBroadcastFallback(
                sdkInt = Build.VERSION.SDK_INT,
                sendIdentityShared = {
                    if (Build.VERSION.SDK_INT >= 34) {
                        sendBroadcastSharingIdentity(context, broadcastIntent)
                    } else {
                        throw IllegalStateException("Sender identity sharing requires API 34+")
                    }
                },
                sendWithoutIdentity = { context.sendBroadcast(broadcastIntent) },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Diagnostics broadcast construction/dispatch failed; persistence is unknown", t)
            null
        }
    }

    private fun logFallbackOutcome(method: String, outcome: BroadcastDispatchResult, attempt: Int) {
        testFallbackOutcomeObserver?.invoke(method, outcome)
        when {
            outcome.callReturned -> Log.w(
                TAG,
                "Diagnostics fallback '$method' dispatched (attempt $attempt/$MAX_FALLBACK_ATTEMPTS); receiver acceptance/persistence remains unknown",
            )
            outcome.identityPath -> Log.w(
                TAG,
                "API 34+ identity-shared diagnostics dispatch failed; plain broadcast was not attempted; persistence is unknown",
                outcome.failure,
            )
            else -> Log.w(TAG, "Legacy diagnostics broadcast dispatch failed; persistence is unknown", outcome.failure)
        }
    }

    private fun isProviderConfirmed(runId: String): Boolean = synchronized(runLock) {
        providerConfirmedRunId == runId
    }

    private fun markProviderConfirmed(runId: String) {
        synchronized(runLock) { providerConfirmedRunId = runId }
    }

    private fun sleepBeforeRetry(): Boolean = try {
        Thread.sleep(RETRY_DELAY_MS)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
