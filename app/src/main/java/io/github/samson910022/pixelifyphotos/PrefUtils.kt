package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.content.SharedPreferences

/**
 * Utility to retrieve SharedPreferences for the module.
 *
 * Prefers converged remote preferences in the manager app. The module's local
 * preferences are available only before this process has ever observed a
 * framework binding; after a disconnect or remote-store error, reads fail closed
 * rather than falling back to a potentially stale local copy.
 */
object PrefUtils {

    internal data class DiagnosticsPreferenceSources(
        val profileSettings: SharedPreferences?,
        val telemetry: SharedPreferences?,
        /** Readability plus canonical-token presence; readable prefs alone are not transport. */
        val telemetryTransport: DiagnosticsTelemetryTransport,
    )

    fun getPrefs(context: Context): SharedPreferences? {
        val snapshot = App.serviceReadinessSnapshot()
        return getPrefs(context, snapshot)
    }

    /** Resolves preferences against a captured service state without re-reading App state. */
    internal fun getPrefs(context: Context, snapshot: ServiceReadinessSnapshot): SharedPreferences? {
        if (snapshot.ready) {
            return getRemotePrefs(snapshot)
        }
        if (snapshot.connected || App.hasObservedFrameworkBinding()) return null
        return try {
            val local = context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE)
            val pending = runCatching {
                context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE)
            }.getOrNull()
            synchronized(OfflineSettingsHandoff.recoveryLock) {
                if (OfflineSettingsHandoff.recoverLocalSettings(local, pending)) local else null
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Telemetry is authoritative only in the manager's remote Xposed preference store. */
    internal fun getRemotePrefs(snapshot: ServiceReadinessSnapshot): SharedPreferences? {
        if (!snapshot.ready) return null
        return try {
            snapshot.service?.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Cold local preferences may supply the profile, but never stand in for hook telemetry. */
    internal fun diagnosticsSources(
        context: Context,
        snapshot: ServiceReadinessSnapshot,
    ): DiagnosticsPreferenceSources {
        val remote = getRemotePrefs(snapshot)
        val settings = if (snapshot.ready) remote else getPrefs(context, snapshot)
        val transport = DiagnosticsTelemetrySourcePolicy.classify(preferencesAvailable = remote != null) {
            remote?.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)
        }
        return DiagnosticsPreferenceSources(
            profileSettings = settings,
            telemetry = remote,
            telemetryTransport = transport,
        )
    }
}
