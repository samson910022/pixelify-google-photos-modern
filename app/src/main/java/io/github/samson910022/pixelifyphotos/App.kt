package io.github.samson910022.pixelifyphotos

import android.app.Application
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
        App.mService = service
        // Align local and remote copies of the per-install broadcast token now that
        // remote preferences are reachable, so hooked-process senders and this
        // process validate against the same canonical value.
        try {
            DiagnosticsStore.convergeBroadcastToken(this)
        } catch (t: Throwable) {
            android.util.Log.d(TAG, "Broadcast token convergence failed: ${t.message}")
        }
    }

    override fun onServiceDied(service: XposedService) {
        App.mService = null
    }

    companion object {
        private const val TAG = "Pixelify"

        @Volatile
        var mService: XposedService? = null
            private set
    }
}
