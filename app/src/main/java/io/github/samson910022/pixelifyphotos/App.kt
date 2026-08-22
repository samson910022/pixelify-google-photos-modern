package io.github.samson910022.pixelifyphotos

import android.app.Application
import androidx.annotation.VisibleForTesting
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        val options = DynamicColorsOptions.Builder()
            .setPrecondition { _, _ ->
                val prefs = getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, MODE_PRIVATE)
                !prefs.getBoolean(Constants.PREF_USE_CLASSIC_UI, false)
            }
            .build()
        DynamicColors.applyToActivitiesIfAvailable(this, options)
        XposedServiceHelper.registerListener(this)
        // Ensure per-install broadcast token is provisioned for diagnostics fallback auth.
        try {
            DiagnosticsStore.getOrCreateToken(this)
        } catch (_: Throwable) {
        }
    }

    override fun onServiceBind(service: XposedService) {
        val listeners = synchronized(serviceStateLock) {
            App.mService = service
            serviceBoundListeners.toList().also { serviceBoundListeners.clear() }
        }
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
        try {
            DiagnosticsStore.convergeBroadcastToken(this)
        } catch (t: Throwable) {
            android.util.Log.d(TAG, "Broadcast token convergence failed: ${t.message}")
        }
        // Notify observers after convergence so re-rendering consumers read aligned prefs.
        listeners.forEach { listener ->
            runCatching { listener() }.onFailure { t ->
                android.util.Log.w(TAG, "Service-bound listener failed: ${t.message}")
            }
        }
    }

    override fun onServiceDied(service: XposedService) {
        synchronized(serviceStateLock) {
            App.mService = null
        }
    }

    companion object {
        private const val TAG = "Pixelify"

        @Volatile
        var mService: XposedService? = null
            private set

        // The LSPosed service binder arrives asynchronously (inbound provider IPC on a
        // Binder thread), so registration and bind events can race. All state below is
        // guarded by [serviceStateLock]; listeners are drained and invoked outside the
        // lock to keep callbacks free of lock-ordering constraints. A plain list is
        // sufficient: every read and write already holds the lock.
        private val serviceStateLock = Any()
        private val serviceBoundListeners = ArrayList<() -> Unit>()

        /**
         * Registers a one-shot callback notified exactly once when the Xposed service
         * binds. If the service is already bound, the callback runs synchronously on
         * the caller's thread; otherwise it is invoked after [onServiceBind] completes
         * token convergence. UI callers must marshal to the main thread themselves.
         */
        fun addOnServiceBoundListener(listener: () -> Unit) {
            var invokeNow = false
            synchronized(serviceStateLock) {
                if (mService != null) {
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
    }
}
