package io.github.samson910022.pixelifyphotos

/** Decisions shared by ActivityMain and JVM tests for remote/local settings access. */
internal object MainSettingsReadinessPolicy {
    /**
     * Keep local settings available only before the framework has ever bound in this
     * Activity. After a bind, disconnected state must not mutate a stale preference
     * source; editing resumes only once the current Binder is ready.
     */
    fun mayAccessPreferences(
        state: ServiceReadinessSnapshot,
        frameworkBindingObserved: Boolean = false,
    ): Boolean = state.ready || (!frameworkBindingObserved && !state.connected)

    /** A connected settings screen may initialize only after remote preference convergence. */
    fun mayInitializeConnectedSettings(state: ServiceReadinessSnapshot): Boolean = state.ready

    fun needsConnectedWait(state: ServiceReadinessSnapshot): Boolean =
        state.connected && !state.ready

    fun mayRetryConvergence(state: ServiceReadinessSnapshot): Boolean =
        state.connected && state.phase == ServicePhase.ERROR
}

/** Prevents child editors from carrying values across preference source transitions. */
internal object SettingsScreenSourcePolicy {
    fun mustReload(capturedVersion: Long?, current: ServiceReadinessSnapshot): Boolean =
        capturedVersion != null && capturedVersion != current.version

    fun maySave(
        capturedVersion: Long?,
        current: ServiceReadinessSnapshot,
        frameworkBindingObserved: Boolean,
    ): Boolean = capturedVersion != null &&
        capturedVersion == current.version &&
        MainSettingsReadinessPolicy.mayAccessPreferences(current, frameworkBindingObserved)
}

/**
 * Bounds the synchronous `recreate()` chain used when a settings screen notices a source change
 * between reading its snapshot and reading preferences. A flapping manager service must not be
 * able to relaunch the screen forever; after the cap the screen keeps its listener and falls
 * back to the normal disabled/retry state.
 */
internal object SettingsScreenRecreatePolicy {
    const val MAX_SYNC_RECREATES = 1

    fun mayRecreate(attempts: Int): Boolean = attempts in 0 until MAX_SYNC_RECREATES

    fun nextAttempts(attempts: Int): Int = (attempts + 1).coerceAtMost(MAX_SYNC_RECREATES)
}

/** ActivityMain's lifecycle decisions, kept pure so cold-start/death races are testable. */
internal object ActivityMainLifecycle {
    enum class Action {
        INITIALIZE_REMOTE,
        INITIALIZE_LOCAL_AND_WAIT,
        WAIT_FOR_CONNECTION,
        WAIT_FOR_REBIND,
        NO_CHANGE,
    }

    fun onCreate(state: ServiceReadinessSnapshot, frameworkBindingObserved: Boolean): Action = when {
        state.ready -> Action.INITIALIZE_REMOTE
        state.connected -> Action.WAIT_FOR_CONNECTION
        frameworkBindingObserved -> Action.WAIT_FOR_REBIND
        else -> Action.INITIALIZE_LOCAL_AND_WAIT
    }

    fun onServiceStateChanged(
        state: ServiceReadinessSnapshot,
        frameworkBindingObserved: Boolean,
    ): Action = when {
        state.ready -> Action.INITIALIZE_REMOTE
        state.connected -> Action.WAIT_FOR_CONNECTION
        frameworkBindingObserved -> Action.WAIT_FOR_REBIND
        else -> Action.NO_CHANGE
    }

    /** A timed-out resolver must never turn an offline screen or known death into a disable verdict. */
    fun onResolverResolved(
        enabled: Boolean,
        state: ServiceReadinessSnapshot,
        frameworkBindingObserved: Boolean,
    ): Action = when {
        enabled && state.ready -> Action.INITIALIZE_REMOTE
        state.ready -> Action.INITIALIZE_REMOTE
        state.connected -> Action.WAIT_FOR_CONNECTION
        frameworkBindingObserved -> Action.WAIT_FOR_REBIND
        else -> Action.NO_CHANGE
    }
}
