package io.github.samson910022.pixelifyphotos

/**
 * Deduplicates asynchronous service-state renders against the state already
 * displayed by [DiagnosticsActivity]. Versions make callbacks safe when bind,
 * death, and Activity-resume work race or arrive at the main looper out of order.
 */
internal class BindRenderCoordinator {

    private var lastRenderedVersion = -1L

    /** A callback is needed only when its state version is newer than the last render. */
    fun shouldRenderState(stateVersion: Long): Boolean =
        stateVersion > lastRenderedVersion

    /** Records the exact state version captured by a completed render. */
    fun onRendered(stateVersion: Long) {
        if (stateVersion > lastRenderedVersion) {
            lastRenderedVersion = stateVersion
        }
    }
}
