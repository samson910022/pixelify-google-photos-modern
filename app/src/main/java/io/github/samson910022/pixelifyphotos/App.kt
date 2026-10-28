package io.github.samson910022.pixelifyphotos

import android.app.Application
import androidx.annotation.VisibleForTesting
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Service lifecycle phase. [READY] means remote settings are usable; diagnostics
 * token convergence is telemetry-only and its failure can coexist with [READY].
 */
internal enum class ServicePhase { DISCONNECTED, CONNECTED_SYNCING, READY, ERROR }

internal data class ServiceReadinessSnapshot(
    val service: XposedService?,
    val phase: ServicePhase,
    val version: Long,
) {
    val connected: Boolean get() = service != null
    val ready: Boolean get() = connected && phase == ServicePhase.READY
}

class App : Application(), XposedServiceHelper.OnServiceListener {

    @VisibleForTesting
    internal var testAfterConvergence: (() -> Unit)? = null

    @VisibleForTesting
    internal var testTokenConvergenceOverride: ((XposedService) -> Boolean)? = null

    @VisibleForTesting
    internal var testSettingsHandoffOverride: ((XposedService) -> Boolean)? = null

    override fun onCreate() {
        super.onCreate()
        applicationInstance = this
        val options = DynamicColorsOptions.Builder()
            .setPrecondition { _, _ -> AppThemePolicy.shouldApplyDynamicColors(this) }
            .build()
        DynamicColors.applyToActivitiesIfAvailable(this, options)
        // The module app is the single token creator. Hooked target processes read the
        // token only after onServiceBind synchronizes this private value to remote prefs.
        if (DiagnosticsStore.getOrCreateToken(this) == null) {
            android.util.Log.w(
                TAG,
                "Diagnostics IPC token could not be persisted; remote diagnostic results will remain unknown",
            )
        }
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        val (attemptGeneration, bindingState, bindingObservers) = synchronized(serviceStateLock) {
            serviceAttemptGeneration += 1L
            App.mService = service
            frameworkBindingObserved = true
            servicePhase = ServicePhase.CONNECTED_SYNCING
            serviceStateVersion += 1L
            Triple(serviceAttemptGeneration, readinessSnapshotLocked(), serviceStateListeners.toList())
        }
        notifyServiceStateListeners(bindingState, bindingObservers)
        convergeServiceAttempt(service, attemptGeneration)
    }

    private fun convergeServiceAttempt(service: XposedService, attemptGeneration: Long) {
        // Align local and remote copies of the per-install broadcast token now that
        // remote preferences are reachable, so hooked-process senders and this
        // process validate against the same canonical value. This runs a synchronous
        // convergence over the service binder BEFORE any listener fires (binder-backed
        // reads, plus commit() writes only when the stores diverge). It is normally
        // milliseconds — well inside the 3 s cold-start grace window
        // (SERVICE_BIND_GRACE_TIMEOUT_MS) — so draining listeners after convergence is
        // deliberate ordering, not an accident; only an anomalously slow LSPosed
        // provider could push past the window, and ModuleStateResolver's host-disposed
        // suppression already tolerates late bind events.
        //
        // Diagnostics token convergence and offline-settings convergence are
        // independent: a token failure only degrades diagnostics/telemetry and must
        // never block settings readiness, listener draining, or the settings UI.
        val tokenConverged = runCatching {
            testTokenConvergenceOverride?.invoke(service)
                ?: DiagnosticsStore.convergeBroadcastToken(this, service)
        }.getOrDefault(false)
        if (!tokenConverged) {
            android.util.Log.w(
                TAG,
                "Diagnostics IPC token is not synchronized to remote preferences; " +
                    "remote diagnostic results will remain unknown",
            )
        }
        val settingsConverged = runCatching {
            testSettingsHandoffOverride?.invoke(service)
                ?: OfflineSettingsHandoff.converge(this, service)
        }.getOrDefault(false)
        if (!settingsConverged) {
            android.util.Log.w(TAG, "Remote settings are not usable; offline handoff did not converge")
        }
        testAfterConvergence?.invoke()
        var readyState: ServiceReadinessSnapshot? = null
        var stateObservers: List<(ServiceReadinessSnapshot) -> Unit> = emptyList()
        val listeners = synchronized(serviceStateLock) {
            if (App.mService !== service || serviceAttemptGeneration != attemptGeneration) {
                emptyList()
            } else {
                // READY means "remote settings are usable". Diagnostics token state is
                // deliberately excluded so telemetry degradation cannot disable settings.
                servicePhase = if (settingsConverged) ServicePhase.READY else ServicePhase.ERROR
                serviceStateVersion += 1L
                readyState = readinessSnapshotLocked()
                stateObservers = serviceStateListeners.toList()
                if (settingsConverged) {
                    serviceBoundListeners.toList().also { serviceBoundListeners.clear() }
                } else {
                    emptyList()
                }
            }
        }
        readyState?.let { notifyServiceStateListeners(it, stateObservers) }
        // Notify observers after convergence so re-rendering consumers read aligned prefs.
        listeners.forEach { listener ->
            runCatching { listener() }.onFailure { t ->
                android.util.Log.w(TAG, "Service-bound listener failed: ${t.message}")
            }
        }
    }

    /**
     * Retries convergence on the already-bound Binder; does not reconnect. Both
     * diagnostics token and offline-settings convergence run again, but the retry
     * succeeds (and the service becomes READY) only when settings convergence
     * succeeds; a still-failing diagnostics token is telemetry degradation only.
     */
    internal fun retryServiceConvergence(): Boolean {
        val service: XposedService
        val generation: Long
        val syncingState: ServiceReadinessSnapshot
        val observers: List<(ServiceReadinessSnapshot) -> Unit>
        synchronized(serviceStateLock) {
            service = mService ?: return false
            if (servicePhase != ServicePhase.ERROR) return false
            serviceAttemptGeneration += 1L
            generation = serviceAttemptGeneration
            servicePhase = ServicePhase.CONNECTED_SYNCING
            serviceStateVersion += 1L
            syncingState = readinessSnapshotLocked()
            observers = serviceStateListeners.toList()
        }
        notifyServiceStateListeners(syncingState, observers)
        convergeServiceAttempt(service, generation)
        return synchronized(serviceStateLock) {
            mService === service && serviceAttemptGeneration == generation &&
                servicePhase == ServicePhase.READY
        }
    }

    private fun requestServiceConvergenceRetry() {
        val shouldRetry = synchronized(serviceStateLock) {
            mService != null && servicePhase == ServicePhase.ERROR
        }
        if (!shouldRetry) return
        Thread({ retryServiceConvergence() }, "pixelify-diagnostics-convergence-retry").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Clears the bound service. Deliberately leaves [serviceBoundListeners]
     * untouched: that registry only holds one-shot callbacks queued while unbound,
     * so any entry present when death lands has not yet observed its bind event
     * and must stay pending until the next [onServiceBind] following an LSPosed
     * restart — that rebind wait is exactly what the unit tests pin down.
     * Touching them now would be wrong either way — invoking would fire against
     * an unready service and hand consumers a spurious bound verdict, while
     * clearing without notifying would silently strand their callers until
     * process death. Registrations racing with this method are safe because
     * [addOnServiceBoundListener] re-checks [mService] and service readiness under
     * this same lock before choosing whether to queue or invoke.
     */
    override fun onServiceDied(service: XposedService) {
        var changedState: ServiceReadinessSnapshot? = null
        var stateObservers: List<(ServiceReadinessSnapshot) -> Unit> = emptyList()
        synchronized(serviceStateLock) {
            if (App.mService === service) {
                serviceAttemptGeneration += 1L
                App.mService = null
                servicePhase = ServicePhase.DISCONNECTED
                serviceStateVersion += 1L
                changedState = readinessSnapshotLocked()
                stateObservers = serviceStateListeners.toList()
            }
        }
        changedState?.let { notifyServiceStateListeners(it, stateObservers) }
    }

    private fun notifyServiceStateListeners(
        snapshot: ServiceReadinessSnapshot,
        listeners: List<(ServiceReadinessSnapshot) -> Unit>,
    ) {
        listeners.forEach { listener ->
            runCatching { listener(snapshot) }.onFailure { t ->
                android.util.Log.w(TAG, "Service-state listener failed: ${t.message}")
            }
        }
    }

    companion object {
        private const val TAG = "Pixelify"

        @Volatile
        private var applicationInstance: App? = null

        @Volatile
        var mService: XposedService? = null
            private set

        // The LSPosed service binder arrives asynchronously (inbound provider IPC on a
        // Binder thread), so registration and bind events can race. All state below is
        // guarded by [serviceStateLock]; listeners are drained and invoked outside the
        // lock to keep callbacks free of lock-ordering constraints. A plain list is
        // sufficient: every read and write already holds the lock.
        private val serviceStateLock = Any()
        private val serviceBoundListeners: MutableList<() -> Unit> = ArrayList()
        private val serviceStateListeners: MutableList<(ServiceReadinessSnapshot) -> Unit> = ArrayList()
        private var servicePhase = ServicePhase.DISCONNECTED
        private var serviceStateVersion = 0L
        private var serviceAttemptGeneration = 0L
        private var frameworkBindingObserved = false

        private fun readinessSnapshotLocked(): ServiceReadinessSnapshot =
            ServiceReadinessSnapshot(mService, servicePhase, serviceStateVersion)

        /**
         * Registers a one-shot callback notified exactly once when the Xposed service
         * binds and its remote settings are ready. If the service is already ready,
         * the callback runs synchronously on the caller's thread; otherwise it is
         * invoked after [onServiceBind] completes settings convergence. UI callers
         * must marshal to the main thread themselves.
         */
        fun addOnServiceBoundListener(listener: () -> Unit) {
            var invokeNow = false
            synchronized(serviceStateLock) {
                if (servicePhase == ServicePhase.READY && mService != null) {
                    invokeNow = true
                } else {
                    serviceBoundListeners.add(listener)
                }
            }
            if (invokeNow) {
                runCatching { listener() }.onFailure { t ->
                    android.util.Log.w(TAG, "Service-bound listener failed: ${t.message}")
                }
            }
        }

        /** Returns only a service whose remote settings finished convergence ([ServicePhase.READY]). */
        internal fun readyService(): XposedService? = synchronized(serviceStateLock) {
            mService.takeIf { servicePhase == ServicePhase.READY }
        }

        /**
         * Runs a settings read/write against the preference store authorized by the
         * current service state. The service lock is held through the operation, so
         * a death/rebind transition cannot make a cached remote handle authorize a
         * later mutation. Local preferences are writable only before this process
         * has ever observed a framework binding.
         *
         * Lock contract: callers run on the main thread, operations must stay short,
         * must not call back into [App] service-state APIs, and operations must not
         * dispatch listeners that wait for this thread. [onOfflinePersistenceUncertain]
         * is invoked synchronously while both [serviceStateLock] and (on the offline
         * path) [OfflineSettingsHandoff.recoveryLock] are held, so it must only flip
         * flags.
         * The offline path takes [OfflineSettingsHandoff.recoveryLock] inside this lock;
         * that ordering (serviceStateLock -> recoveryLock) must never be reversed.
         * Holding the lock through the operation is intentional because an `apply()`
         * write offers no failure signal to roll back after a rebind. Callers must
         * finish any document I/O, parsing, and validation before calling (for
         * example the staged [ConfigImportPlan]); only short in-memory writes belong
         * inside the operation.
         */
        internal fun withGuardedSettingsPreferences(
            context: android.content.Context,
            expectedStateVersion: Long? = null,
            editedKeys: Set<String> = emptySet(),
            onOfflinePersistenceUncertain: () -> Unit = {},
            operation: (android.content.SharedPreferences) -> Boolean,
        ): Boolean = synchronized(serviceStateLock) {
            if (expectedStateVersion != null && expectedStateVersion != serviceStateVersion) {
                return@synchronized false
            }
            val currentService = mService
            val isOfflineLocalWrite = currentService == null && !frameworkBindingObserved
            val prefs = when {
                currentService != null && servicePhase == ServicePhase.READY ->
                    runCatching {
                        currentService.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
                    }.getOrNull()
                currentService != null -> null
                frameworkBindingObserved -> null
                else -> runCatching {
                    context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, android.content.Context.MODE_PRIVATE)
                }.getOrNull()
            } ?: return@synchronized false
            if (isOfflineLocalWrite) {
                return@synchronized synchronized(OfflineSettingsHandoff.recoveryLock) localWrite@{
                    val pending = runCatching {
                        context.getSharedPreferences(
                            OfflineSettingsHandoff.PREFERENCES_FILE,
                            android.content.Context.MODE_PRIVATE,
                        )
                    }.getOrNull() ?: return@localWrite false
                    if (!OfflineSettingsHandoff.recoverLocalSettings(prefs, pending)) return@localWrite false
                    val before = runCatching { OfflineSettingsHandoff.snapshot(prefs) }.getOrNull()
                        ?: return@localWrite false
                    // This commit must be durable before the user's offline edit
                    // runs; otherwise a later failed handoff could leave a disk-only
                    // ghost that no pending marker can identify.
                    if (!OfflineSettingsHandoff.prepareLocalRecovery(prefs, before)) return@localWrite false

                    fun rollbackOffline(keys: Set<String> = OfflineSettingsHandoff.userSettingKeysForRollback()) {
                        if (keys.isNotEmpty()) {
                            runCatching { OfflineSettingsHandoff.setLocalRecoveryExpectedKeys(prefs, keys) }
                        }
                        runCatching { OfflineSettingsHandoff.restoreSnapshot(prefs, before, keys) }
                        // Retain the durable recovery journal unless both the
                        // rollback and journal cleanup can be committed.
                        OfflineSettingsHandoff.recoverLocalSettings(prefs, pending)
                    }

                    val updated = runCatching { operation(prefs) }.getOrDefault(false)
                    if (!updated) {
                        val afterFailure = runCatching { OfflineSettingsHandoff.snapshot(prefs) }.getOrNull()
                        val changedOnFailure = afterFailure?.let {
                            OfflineSettingsHandoff.changedKeys(before, it, editedKeys)
                        } ?: OfflineSettingsHandoff.userSettingKeysForRollback()
                        rollbackOffline(changedOnFailure)
                        return@localWrite false
                    }

                    val after = runCatching { OfflineSettingsHandoff.snapshot(prefs) }.getOrNull()
                    if (after == null) {
                        rollbackOffline()
                        return@localWrite false
                    }
                    val changedKeys = OfflineSettingsHandoff.changedKeys(
                        before,
                        after,
                        explicitlyEditedKeys = editedKeys,
                    )
                    if (changedKeys.isEmpty()) {
                        OfflineSettingsHandoff.recoverLocalSettings(prefs, pending)
                        return@localWrite true
                    }
                    if (!OfflineSettingsHandoff.setLocalRecoveryExpectedKeys(prefs, changedKeys)) {
                        rollbackOffline(changedKeys)
                        return@localWrite false
                    }
                    val recordStatus = runCatching {
                        OfflineSettingsHandoff.recordSnapshotChangesStatus(
                            pending,
                            before,
                            after,
                            explicitlyEditedKeys = changedKeys,
                        )
                    }.getOrDefault(OfflineSettingsHandoff.RecordStatus.REJECTED)
                    if (recordStatus == OfflineSettingsHandoff.RecordStatus.ACCEPTED_IN_MEMORY) {
                        // Keep the matching live intent paired with the local edit.
                        // Its durable pre-edit journal lets a fresh process roll
                        // back if neither write survived recreation.
                        runCatching { onOfflinePersistenceUncertain() }
                        return@localWrite true
                    }
                    if (!recordStatus.accepted) {
                        rollbackOffline(changedKeys)
                        return@localWrite false
                    }
                    // A durable pending marker authorizes the edited values. If
                    // journal cleanup fails, recovery can verify that exact intent.
                    OfflineSettingsHandoff.recoverLocalSettings(prefs, pending)
                    true
                }
            }
            runCatching { operation(prefs) }.getOrDefault(false)
        }

        /** Distinguishes a service binding in progress from a ready manager connection. */
        fun isServiceReady(): Boolean = synchronized(serviceStateLock) {
            servicePhase == ServicePhase.READY && mService != null
        }

        /** Remembers a bind for this app process so later Activities cannot edit stale local prefs. */
        internal fun hasObservedFrameworkBinding(): Boolean = synchronized(serviceStateLock) {
            frameworkBindingObserved
        }

        /**
         * Safe UI entry point; retries convergence on the current Binder on a daemon
         * worker, never the main thread. A missing or already-READY service is a no-op.
         * Token-only degradation cannot block settings readiness, so it is repaired by
         * the next bind rather than by this settings-oriented retry.
         */
        fun requestServiceConvergenceRetry() {
            applicationInstance?.requestServiceConvergenceRetry()
        }

        /** Registers a persistent listener for ready, binding, and death transitions. */
        internal fun addOnServiceStateListener(listener: (ServiceReadinessSnapshot) -> Unit) {
            val snapshot = synchronized(serviceStateLock) {
                serviceStateListeners.add(listener)
                readinessSnapshotLocked()
            }
            runCatching { listener(snapshot) }.onFailure { t ->
                android.util.Log.w(TAG, "Service-state listener failed: ${t.message}")
            }
        }

        internal fun removeOnServiceStateListener(listener: (ServiceReadinessSnapshot) -> Unit) {
            synchronized(serviceStateLock) {
                serviceStateListeners.remove(listener)
            }
        }

        /**
         * Removes a pending callback registered via [addOnServiceBoundListener].
         * Removal races with the bind-time drain by design: a callback already
         * captured for delivery may still run, so consumers must tolerate one
         * post-removal invocation (UI callers re-validate lifecycle state on the
         * main thread before acting).
         */
        fun removeOnServiceBoundListener(listener: () -> Unit) {
            synchronized(serviceStateLock) {
                serviceBoundListeners.remove(listener)
            }
        }

        /**
         * Clears pending service-bound callbacks; test isolation only, no
         * production callers.
         */
        @VisibleForTesting
        internal fun clearServiceBoundListeners() {
            synchronized(serviceStateLock) {
                serviceBoundListeners.clear()
            }
        }

        @VisibleForTesting
        internal fun clearServiceStateListenersForTests() {
            synchronized(serviceStateLock) {
                serviceStateListeners.clear()
            }
        }

        @VisibleForTesting
        internal fun serviceStateListenerCountForTests(): Int = synchronized(serviceStateLock) {
            serviceStateListeners.size
        }

        internal fun serviceReadinessSnapshot(): ServiceReadinessSnapshot = synchronized(serviceStateLock) {
            readinessSnapshotLocked()
        }

        @VisibleForTesting
        internal fun setServiceStateForTests(
            service: XposedService?,
            ready: Boolean,
            phase: ServicePhase? = null,
        ) {
            synchronized(serviceStateLock) {
                serviceAttemptGeneration += 1L
                mService = service
                frameworkBindingObserved = service != null
                servicePhase = when {
                    service == null -> ServicePhase.DISCONNECTED
                    phase != null -> phase
                    ready -> ServicePhase.READY
                    else -> ServicePhase.CONNECTED_SYNCING
                }
                serviceStateVersion += 1L
            }
        }
    }
}

/** Theme selection fails safe to modern/dynamic colors if local recovery cannot be completed. */
internal object AppThemePolicy {
    fun shouldApplyDynamicColors(context: android.content.Context): Boolean = runCatching {
        val local = context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, android.content.Context.MODE_PRIVATE)
        val pending = context.getSharedPreferences(
            OfflineSettingsHandoff.PREFERENCES_FILE,
            android.content.Context.MODE_PRIVATE,
        )
        shouldApplyDynamicColors(local, pending)
    }.getOrDefault(true)

    fun shouldApplyDynamicColors(
        local: android.content.SharedPreferences?,
        pending: android.content.SharedPreferences?,
    ): Boolean {
        if (local == null || pending == null) return true
        val classicUi = OfflineSettingsHandoff.readClassicUiSetting(local, pending) ?: return true
        return !classicUi
    }
}
