package io.github.samson910022.pixelifyphotos

/**
 * Collapses redundant renders in [DiagnosticsActivity] when the XposedService
 * binds while a resume transaction is still in flight.
 *
 * The posted bind-callback typically dispatches AFTER the resume that rendered
 * the same bound state (looper ordering), which would otherwise rebuild the
 * whole build comparison view twice with identical data. A render that observes
 * the UNBOUND state followed by a bind-callback remains mandatory and is never
 * skipped. The opposite interleaving (bind dispatched before the first resume)
 * still renders twice today; this helper intentionally covers only the common
 * post-after-resume direction rather than gating onResume rendering.
 */
internal class BindRenderCoordinator {

    /** True iff the most recent completed render observed the service bound. */
    var lastRenderSawBound: Boolean = false
        private set

    /** Called once per completed render with what that render observed. */
    fun onRendered(serviceWasBound: Boolean) {
        lastRenderSawBound = serviceWasBound
    }

    /**
     * Decision for the posted bind-callback path: skip only when the just-posted
     * bound state was already rendered by the resume transaction. The caller keeps
     * its own lifecycle guards (`isFinishing`, `isDestroyed`, `App.mService != null`);
     * this helper deliberately decides on render history alone.
     */
    fun skipPostedRender(serviceNowBound: Boolean): Boolean =
        serviceNowBound && lastRenderSawBound
}
