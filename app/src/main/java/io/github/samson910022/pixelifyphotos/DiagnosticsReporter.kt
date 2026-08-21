package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
 * updates never race. Fails closed and silently on any error.
 */
object DiagnosticsReporter {

    private const val TAG = "Pixelify"
    internal var testProviderUri: Uri? = null
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
            LinkedBlockingQueue(32),
            factory,
            ThreadPoolExecutor.DiscardOldestPolicy()
        )
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

    /**
     * Attempts to resolve the per-install broadcast token for authentication.
     *
     * Only the Xposed remote-preferences store is consulted: this reporter runs inside
     * a hooked target process, where the module's MODE_PRIVATE preference file is not
     * readable cross-UID, and [PrefUtils.getPrefs] would resolve to the host application's
     * own preferences instead of the module's. The module process keeps its local copy
     * aligned with this store via [DiagnosticsStore.convergeBroadcastToken].
     */
    private fun resolveBroadcastToken(): String? {
        return try {
            val module = PixelifyModule.instance ?: return null
            val prefs = module.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
            val token = prefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)
            token?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Log.d(TAG, "Broadcast token unavailable: ${t.message}")
            null
        }
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

    private fun dispatch(explicitContext: Context?, method: String, extras: Bundle) {
        try {
            // Defensive copy: caller may mutate Bundle after dispatch is queued.
            val payload = Bundle(extras)
            executor.execute {
                try {
                    // During early process initialization (e.g. onModuleLoaded / onPackageLoaded),
                    // ActivityThread.currentApplication() or ContentResolver may not be immediately
                    // bound. We attempt up to 3 times with a 150ms sleep in background to allow
                    // Context attachment without blocking main thread or causing ANRs.
                    var attempts = 0
                    while (attempts < 3) {
                        attempts++
                        val ctx = resolveContext(explicitContext)
                        if (ctx == null) {
                            if (attempts >= 3) return@execute
                            try {
                                Thread.sleep(150)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return@execute
                            }
                            continue
                        }

                        var providerSucceeded = false
                        // 1. Channel 1 (Primary - ContentProvider IPC)
                        try {
                            val result = ctx.contentResolver.call(PROVIDER_URI, method, null, payload)
                            // Fail-closed: missing "success" key defaults to false.
                            if (result != null && result.getBoolean("success", false)) {
                                providerSucceeded = true
                            }
                        } catch (t: Throwable) {
                            Log.d(TAG, "ContentProvider call failed; trying broadcast fallback: ${t.message}")
                        }

                        if (providerSucceeded) {
                            return@execute
                        }

                        // Fallback to broadcast when provider did not succeed (null, exception, or
                        // success=false). On explicit auth rejection (success=false) the broadcast
                        // will be re-validated via token/UID and rejected again, so fallback is safe
                        // (just adds one Binder hop).
                        try {
                            val broadcastIntent = android.content.Intent(Constants.ACTION_RECORD_DIAGNOSTICS).apply {
                                setPackage(Constants.PACKAGE_NAME_MODULE)
                                component = android.content.ComponentName(
                                    Constants.PACKAGE_NAME_MODULE,
                                    DiagnosticsReceiver::class.java.name
                                )
                                putExtra(Constants.EXTRA_DIAGNOSTICS_METHOD, method)
                                putExtras(payload)
                                addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
                                // Attach per-install token if available (pre-34 auth fallback)
                                val token = resolveBroadcastToken()
                                if (!token.isNullOrEmpty()) {
                                    putExtra(Constants.EXTRA_DIAGNOSTICS_TOKEN, token)
                                }
                            }
                            // On API 34+, share sender identity so the receiver can verify
                            // via getSentFromUid(). Direct API call in a dedicated helper
                            // (see [sendBroadcastSharingIdentity]) instead of reflection.
                            if (Build.VERSION.SDK_INT >= 34) {
                                try {
                                    sendBroadcastSharingIdentity(ctx, broadcastIntent)
                                } catch (t: Throwable) {
                                    Log.d(
                                        TAG,
                                        "Identity-shared broadcast failed; retrying without options: ${t.message}"
                                    )
                                    ctx.sendBroadcast(broadcastIntent)
                                }
                            } else {
                                ctx.sendBroadcast(broadcastIntent)
                            }
                            return@execute
                        } catch (t: Throwable) {
                            Log.d(TAG, "Explicit broadcast dispatch failed: ${t.message}")
                        }

                        // Both channels failed — retry if attempts remain.
                        if (attempts >= 3) return@execute
                        try {
                            Thread.sleep(150)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@execute
                        }
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "Diagnostics dispatch skipped or failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Failed to schedule diagnostics dispatch: ${t.message}")
        }
    }
}
