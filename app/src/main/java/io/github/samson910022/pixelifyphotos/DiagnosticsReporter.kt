package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import kotlin.concurrent.thread

/**
 * Dispatches diagnostic milestones and device-spoof VERIFY telemetry from hooked
 * target processes (like Google Photos) back to the module manager application via
 * [DiagnosticsProvider].
 *
 * Runs asynchronously on a daemon background thread so that target application startup
 * and hook execution paths are never blocked. Fails closed and silently on any error.
 */
object DiagnosticsReporter {

    private const val TAG = "Pixelify"
    private val PROVIDER_URI: Uri by lazy {
        Uri.parse("content://${Constants.DIAGNOSTICS_AUTHORITY}")
    }

    /**
     * Resolves the current application context if available in the process.
     */
    fun resolveContext(explicitContext: Context? = null): Context? {
        if (explicitContext != null) return explicitContext
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getDeclaredMethod("currentApplication")
            currentAppMethod.invoke(null) as? Context
        }.getOrNull()
    }

    /**
     * Asynchronously records lifecycle milestones (e.g. module loaded, package loaded, package ready).
     */
    fun recordMilestone(context: Context? = null, block: (Bundle) -> Unit) {
        val bundle = Bundle()
        block(bundle)
        dispatch(context, Constants.METHOD_RECORD_DIAGNOSTICS, bundle)
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
    ) {
        val bundle = Bundle().apply {
            putLong(Constants.PREF_DIAG_VERIFY_AT, System.currentTimeMillis())
            putString(Constants.PREF_DIAG_VERIFY_DEVICE, deviceName)
            if (packageName != null) {
                putString(Constants.PREF_DIAG_VERIFY_PACKAGE, packageName)
            }
            putBoolean(Constants.PREF_DIAG_VERIFY_OK, failed.isEmpty())
            putStringArrayList(Constants.PREF_DIAG_VERIFY_FAILED, ArrayList(failed))
            putBoolean(Constants.PREF_DIAG_VERIFY_NATIVE_READY, nativeReady)
            putBoolean(Constants.PREF_DIAG_VERIFY_SYSPROPS, syspropsHooked)
        }
        dispatch(context, Constants.METHOD_RECORD_VERIFY, bundle)
    }

    /**
     * Asynchronously clears persisted VERIFY diagnostics when spoofing is disabled.
     */
    fun clearVerify(context: Context? = null) {
        dispatch(context, Constants.METHOD_CLEAR_VERIFY, Bundle())
    }

    private fun dispatch(explicitContext: Context?, method: String, extras: Bundle) {
        thread(name = "pixelify-diag-push", isDaemon = true) {
            try {
                // Short retry loop in case ContentResolver is not yet ready during early zygote/init
                var attempts = 0
                while (attempts < 3) {
                    attempts++
                    val ctx = resolveContext(explicitContext)
                    if (ctx != null) {
                        ctx.contentResolver.call(PROVIDER_URI, method, null, extras)
                        return@thread
                    }
                    try {
                        Thread.sleep(150)
                    } catch (_: InterruptedException) {
                        return@thread
                    }
                }
            } catch (t: Throwable) {
                Log.d(TAG, "Diagnostics dispatch skipped or failed: ${t.message}")
            }
        }
    }
}
