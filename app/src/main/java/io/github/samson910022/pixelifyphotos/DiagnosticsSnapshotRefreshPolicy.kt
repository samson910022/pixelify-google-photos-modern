package io.github.samson910022.pixelifyphotos

/**
 * Bounds the diagnostics consistency-refresh loop.
 *
 * A snapshot read that crosses a service state version cannot be shown as current
 * data. Retrying immediately would repaint the main thread without limit, so each
 * successive retry is delayed and the consecutive budget is capped; one final
 * delayed attempt covers a service that settles without emitting another event.
 * After that the screen stays in its explicit refreshing / captured-values-unavailable
 * state until an external render trigger (a service-state change or Activity resume)
 * resets the budget.
 *
 * The budget bounds *consecutive* retries only. Service-state events are external
 * triggers and intentionally reset it, so event-driven repaint is bounded by the
 * external transition rate rather than by this policy.
 */
internal object DiagnosticsSnapshotRefreshPolicy {
    const val MAX_CONSECUTIVE_REFRESHES = 3

    private const val BASE_DELAY_MS = 100L
    private const val FINAL_FALLBACK_DELAY_MS = 5_000L
    private const val MAX_BACKOFF_SHIFT = 4

    /**
     * Delay before the next consistency refresh, or null when no further attempt is
     * scheduled. [completedRefreshes] counts retries already performed since the
     * last external render trigger. [maxRefreshes] caps the backoff retries; one
     * additional final fallback attempt follows them. When [maxRefreshes] is not
     * positive no retry is ever scheduled.
     */
    fun nextRetryDelayMillis(
        completedRefreshes: Int,
        maxRefreshes: Int = MAX_CONSECUTIVE_REFRESHES,
    ): Long? {
        if (maxRefreshes <= 0 || completedRefreshes < 0) {
            return null
        }
        if (completedRefreshes < maxRefreshes) {
            return BASE_DELAY_MS shl completedRefreshes.coerceAtMost(MAX_BACKOFF_SHIFT)
        }
        return if (completedRefreshes == maxRefreshes) FINAL_FALLBACK_DELAY_MS else null
    }
}
