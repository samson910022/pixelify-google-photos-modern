package io.github.samson910022.pixelifyphotos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Fallback receiver allowing hooked target processes (e.g. Google Photos) to deliver
 * diagnostic telemetry and VERIFY outcomes when Android 11+ package visibility (AppsFilter)
 * blocks ContentProvider resolution.
 */
class DiagnosticsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PixelifyDiagReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Constants.ACTION_RECORD_DIAGNOSTICS) return

        val method = intent.getStringExtra(Constants.EXTRA_DIAGNOSTICS_METHOD) ?: return
        val extras = intent.extras ?: Bundle()

        val success = DiagnosticsStore.applyDiagnostics(
            context = context,
            method = method,
            extras = extras,
        )
        Log.d(TAG, "Handled diagnostic broadcast for method '$method', success=$success")
    }
}
