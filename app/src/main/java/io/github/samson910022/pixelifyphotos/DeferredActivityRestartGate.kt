package io.github.samson910022.pixelifyphotos

/**
 * Defers an explicit Activity restart until the matching config-export request is complete and
 * the host is in a lifecycle state where finishing and relaunching cannot lose the task.
 */
internal class DeferredActivityRestartGate {
    private var restartDeferred = false

    /**
     * Returns true when the caller may restart immediately.
     *
     * A background Activity cannot reliably launch its replacement (Android 10+ background
     * activity-launch restrictions), so an inactive host always defers even when no export is
     * pending. The deferred intent is consumed from `onStart`.
     */
    fun requestRestart(configExportPending: Boolean, hostActive: Boolean = true): Boolean {
        if (configExportPending || !hostActive) {
            restartDeferred = true
            return false
        }
        // A restart with no export to preserve supersedes any stale deferred intent.
        restartDeferred = false
        return true
    }

    /**
     * Completes only the request actually consumed by its callback (or cleared by its launcher
     * path). A stale callback cannot release the deferred restart, a newer pending request keeps
     * it deferred, and an inactive host keeps the intent for `onStart`.
     */
    fun completeRequest(
        callbackRequestId: String,
        completedRequestId: String?,
        configExportPending: Boolean,
        hostActive: Boolean = true,
    ): Boolean {
        if (callbackRequestId != completedRequestId || configExportPending || !restartDeferred) {
            return false
        }
        if (!hostActive) return false
        restartDeferred = false
        return true
    }

    /** Consumes a restart deferred while the host was stopped. Call only from `onStart`. */
    fun takeResumedRestart(configExportPending: Boolean): Boolean {
        if (!restartDeferred || configExportPending) return false
        restartDeferred = false
        return true
    }

    /**
     * Releases a restart deferred for a now-finished operation while the host is active.
     *
     * Unlike [takeResumedRestart], this is called from a completion callback rather than
     * `onStart`. A stopped host keeps the intent for `onStart`, and anything still pending keeps
     * it deferred, so a completion path cannot restart early or strand the intent.
     */
    fun releaseDeferredRestart(configExportPending: Boolean, hostActive: Boolean = true): Boolean {
        if (!restartDeferred || configExportPending || !hostActive) return false
        restartDeferred = false
        return true
    }
}
