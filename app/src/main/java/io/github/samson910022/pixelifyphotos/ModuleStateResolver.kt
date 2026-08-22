package io.github.samson910022.pixelifyphotos

/**
 * One-shot resolution gate between asynchronous LSPosed service binder delivery
 * and a consumer's enabled/disabled verdict.
 *
 * Owns the full wait lifecycle so callers cannot leak partial state: on [start]
 * it registers a service-bound listener and arms a grace timeout, marshals
 * binder-thread bind notifications onto the main thread, and delivers exactly one
 * [onResolved] verdict (true = bound within the window, false = timed out).
 * Resolution is permanently suppressed after [dispose] or when [isHostDisposed]
 * reports the host gone at execution time; listener and timeout cleanup run even
 * on suppressed paths so nothing outlives the host.
 *
 * Threading contract: all internal state mutates only on the main looper —
 * [dispatchToMain], [scheduleGraceTimeout], and [cancelGraceTimeout] must all
 * target the main looper, and [start]/[dispose] must run there too (unit tests
 * may inject synchronous fakes). [start] and [dispose] must come from the same
 * thread — a cross-thread pair could strand one registration. Pure Kotlin by
 * design — no Android imports — so the gate logic is unit-testable without
 * Robolectric.
 */
internal class ModuleStateResolver(
    private val addServiceBoundListener: (() -> Unit) -> Unit,
    private val removeServiceBoundListener: (() -> Unit) -> Unit,
    private val dispatchToMain: (Runnable) -> Unit,
    private val scheduleGraceTimeout: (Runnable) -> Unit,
    private val cancelGraceTimeout: (Runnable) -> Unit,
    private val isHostDisposed: () -> Boolean,
    private val onResolved: (Boolean) -> Unit,
) {

    private val lock = Any()
    private var started = false
    private var consumed = false
    private var disposed = false

    /** Grace-window expiry token; identity is used for targeted cancellation. */
    private val graceTimeoutToken = Runnable {
        // Defensive removal first: covers every path uniformly (registered, already
        // drained, or sync-fired-and-never-stored are all safe no-ops here).
        removeServiceBoundListener(serviceBoundListener)
        attemptResolve(enabled = false)
    }

    /** Marshaled onto the main thread when the service-bound event fires. */
    private val bindResolution = Runnable {
        cancelGraceTimeout(graceTimeoutToken)
        removeServiceBoundListener(serviceBoundListener)
        attemptResolve(enabled = true)
    }

    /** Registered with the App registry; may fire on any Binder thread. */
    private val serviceBoundListener: () -> Unit = {
        dispatchToMain(bindResolution)
    }

    /**
     * Registers the bind callback and arms the grace timeout. Registration happens
     * before arming so a synchronous fire during registration can never race an
     * unarmed timeout token. Idempotent like [dispose]: repeat calls and calls
     * after [dispose] are silent no-ops, so a future double-start lifecycle path
     * can never double-register or crash the host.
     */
    fun start() {
        synchronized(lock) {
            if (started || disposed) return
            started = true
        }
        addServiceBoundListener(serviceBoundListener)
        scheduleGraceTimeout(graceTimeoutToken)
    }

    /**
     * Cancels the wait permanently. Idempotent; after this call no pending or
     * late-arriving event produces a verdict.
     */
    fun dispose() {
        synchronized(lock) {
            disposed = true
        }
        cancelGraceTimeout(graceTimeoutToken)
        removeServiceBoundListener(serviceBoundListener)
    }

    private fun attemptResolve(enabled: Boolean) {
        synchronized(lock) {
            if (consumed || disposed) return
            if (isHostDisposed()) {
                // Suppress permanently: a destroyed host must never resurrect UI.
                consumed = true
                return
            }
            consumed = true
        }
        onResolved(enabled)
    }
}
