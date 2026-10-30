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
        try {
            handleReceive(context, intent)
        } catch (_: RuntimeException) {
            // Intent/Bundle extras can fail while unparcelling. Reject the whole
            // request without logging attacker-controlled contents.
            Log.w(TAG, "Rejected malformed diagnostics broadcast")
        }
    }

    private fun handleReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Constants.ACTION_RECORD_DIAGNOSTICS) return

        val extras = intent.extras ?: return
        val method = extras.get(Constants.EXTRA_DIAGNOSTICS_METHOD) as? String ?: return
        if (method !in setOf(
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                Constants.METHOD_RECORD_VERIFY,
                Constants.METHOD_CLEAR_VERIFY,
                Constants.METHOD_RECORD_DIAGNOSTICS_BATCH,
            )
        ) return
        val reportedOrigin = if (method == Constants.METHOD_RECORD_DIAGNOSTICS_BATCH) {
            (extras.get(Constants.EXTRA_DIAGNOSTICS_BATCH_START) as? Bundle)
                ?.getString(Constants.EXTRA_DIAGNOSTICS_ORIGIN)
        } else {
            extras.getString(Constants.EXTRA_DIAGNOSTICS_ORIGIN)
        }

        // API 34+ requires OS sender identity; a failed identity lookup must not
        // silently downgrade to token-only attribution. Keep token-only telemetry
        // available below API 34; only API 30-33 may use it to replace a verified
        // prior-boot run, through the separate gated permission below.
        var senderIdentity = if (Build.VERSION.SDK_INT >= 34) {
            DiagnosticsOriginPolicy.unavailableApi34BroadcastIdentity()
        } else {
            DiagnosticsOriginPolicy.tokenOnlyBroadcastIdentity()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                @Suppress("NewApi")
                val sentUid = getSentFromUid()
                if (sentUid != Process.INVALID_UID) {
                    val sentPackage = try {
                        @Suppress("NewApi")
                        getSentFromPackage()
                    } catch (_: RuntimeException) {
                        null
                    }
                    val packagesForUid = try {
                        context.packageManager.getPackagesForUid(sentUid)
                    } catch (_: RuntimeException) {
                        null
                    }
                    senderIdentity = DiagnosticsOriginPolicy.broadcastCallerIdentity(
                        origin = reportedOrigin,
                        sentUid = sentUid,
                        invalidUid = Process.INVALID_UID,
                        sentPackage = sentPackage,
                        packagesForUid = packagesForUid,
                        myUid = Process.myUid(),
                    )
                }
            } catch (_: RuntimeException) {
                // API 34+ cannot downgrade to token-only identity.
                Log.d(TAG, "Sender identity unavailable")
            }
        }

        if (method == Constants.METHOD_RECORD_DIAGNOSTICS_BATCH) {
            val expectedEnvelopeKeys = setOf(
                Constants.EXTRA_DIAGNOSTICS_METHOD,
                Constants.EXTRA_DIAGNOSTICS_BATCH_START,
                Constants.EXTRA_DIAGNOSTICS_BATCH_EVENT_METHOD,
                Constants.EXTRA_DIAGNOSTICS_BATCH_EVENT,
            )
            if (extras.keySet() != expectedEnvelopeKeys) return
            val start = extras.get(Constants.EXTRA_DIAGNOSTICS_BATCH_START) as? Bundle
            val eventMethod = extras.get(Constants.EXTRA_DIAGNOSTICS_BATCH_EVENT_METHOD) as? String
            val event = extras.get(Constants.EXTRA_DIAGNOSTICS_BATCH_EVENT) as? Bundle
            val (startAccepted, eventAccepted) = if (start != null && eventMethod != null && event != null) {
                applyBatchWithSenderIdentity(context, start, eventMethod, event, senderIdentity)
            } else {
                false to false
            }
            Log.d(
                TAG,
                "Handled diagnostics fallback batch; startAccepted=$startAccepted, eventAccepted=$eventAccepted",
            )
            return
        }

        // EXTRA_DIAGNOSTICS_METHOD is transport metadata, not part of the strict
        // diagnostic schema. Remove it from this receiver-owned Intent payload.
        extras.remove(Constants.EXTRA_DIAGNOSTICS_METHOD)
        val success = applyWithSenderIdentity(context, method, extras, senderIdentity)
        Log.d(TAG, "Handled diagnostics broadcast; success=$success")
    }

    internal fun applyWithSenderIdentity(
        context: Context,
        method: String,
        extras: Bundle,
        identity: DiagnosticsOriginPolicy.BroadcastCallerIdentity,
    ): Boolean = DiagnosticsStore.applyDiagnostics(
        context = context,
        method = method,
        extras = extras,
        callingUid = identity.callingUid,
        callingPackages = identity.callingPackages,
        myUid = identity.myUid,
        verifiedBroadcastOrigin = identity.verifiedOrigin,
        requireVerifiedOrigin = identity.requireVerifiedOrigin,
        allowTokenOnlyUnverifiedOrigin = identity.allowTokenOnlyUnverifiedOrigin,
        allowPriorBootUnverifiedReplacement = DiagnosticsOriginPolicy
            .allowsTokenOnlyPriorBootReplacement(
                sdkInt = Build.VERSION.SDK_INT,
                tokenOnlyAllowed = identity.allowTokenOnlyUnverifiedOrigin,
            ),
        remoteTargetWrite = !(identity.callingUid != null && identity.myUid != null &&
            identity.callingUid == identity.myUid),
    )

    internal fun applyBatchWithSenderIdentity(
        context: Context,
        start: Bundle,
        eventMethod: String,
        event: Bundle,
        identity: DiagnosticsOriginPolicy.BroadcastCallerIdentity,
    ): Pair<Boolean, Boolean> = DiagnosticsStore.applyDiagnosticsBatch(
        context = context,
        start = start,
        eventMethod = eventMethod,
        event = event,
        callingUid = identity.callingUid,
        callingPackages = identity.callingPackages,
        myUid = identity.myUid,
        verifiedBroadcastOrigin = identity.verifiedOrigin,
        requireVerifiedOrigin = identity.requireVerifiedOrigin,
        allowTokenOnlyUnverifiedOrigin = identity.allowTokenOnlyUnverifiedOrigin,
        allowPriorBootUnverifiedReplacement = DiagnosticsOriginPolicy
            .allowsTokenOnlyPriorBootReplacement(
                sdkInt = Build.VERSION.SDK_INT,
                tokenOnlyAllowed = identity.allowTokenOnlyUnverifiedOrigin,
            ),
        remoteTargetWrite = !(identity.callingUid != null && identity.myUid != null &&
            identity.callingUid == identity.myUid),
    )
}
