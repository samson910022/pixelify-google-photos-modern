package io.github.samson910022.pixelifyphotos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
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

        // Try API 34+ shareIdentity sender verification. If available, enforce isCallerAuthorized.
        var callingUid: Int? = null
        var callingPackages: Array<String>? = null
        var myUid: Int? = null
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                @Suppress("NewApi")
                val sentUid = getSentFromUid()
                @Suppress("NewApi")
                val sentPackage = getSentFromPackage()
                if (sentUid != Process.INVALID_UID && sentPackage != null) {
                    callingUid = sentUid
                    myUid = Process.myUid()
                    callingPackages = try {
                        context.packageManager.getPackagesForUid(sentUid)
                    } catch (_: Throwable) {
                        null
                    }
                    // If getSentFromPackage returns a package but getPackagesForUid is empty,
                    // synthesize at least the reported package for isCallerAuthorized check.
                    if (callingPackages.isNullOrEmpty() && sentPackage.isNotEmpty()) {
                        callingPackages = arrayOf(sentPackage)
                    }
                }
            } catch (_: Throwable) {
                // Old platform or shareIdentity not enabled — fall through to token path.
            }
        }

        val success = DiagnosticsStore.applyDiagnostics(
            context = context,
            method = method,
            extras = extras,
            callingUid = callingUid,
            callingPackages = callingPackages,
            myUid = myUid,
        )
        Log.d(TAG, "Handled diagnostic broadcast for method '$method', success=$success")
    }
}
