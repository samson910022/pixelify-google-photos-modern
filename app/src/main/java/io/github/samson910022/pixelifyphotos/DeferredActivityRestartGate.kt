package io.github.samson910022.pixelifyphotos

/** Defers an explicit Activity restart until the matching config-export request is complete. */
internal class DeferredActivityRestartGate {
    private var restartDeferred = false

    /** Returns true when the caller may restart immediately. */
    fun requestRestart(configExportPending: Boolean): Boolean {
        if (configExportPending) {
            restartDeferred = true
            return false
        }
        // A restart with no export to preserve supersedes any stale deferred intent.
        restartDeferred = false
        return true
    }

    /**
     * Completes only the request actually consumed by its callback (or cleared by its launcher
     * path). A stale callback cannot release the deferred restart, and a newer pending request
     * keeps it deferred.
     */
    fun completeRequest(
        callbackRequestId: String,
        completedRequestId: String?,
        configExportPending: Boolean,
    ): Boolean {
        if (callbackRequestId != completedRequestId || configExportPending || !restartDeferred) {
            return false
        }
        restartDeferred = false
        return true
    }
}
