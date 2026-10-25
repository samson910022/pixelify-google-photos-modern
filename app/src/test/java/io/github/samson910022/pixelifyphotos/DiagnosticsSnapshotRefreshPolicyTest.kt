package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsSnapshotRefreshPolicyTest {

    @Test
    fun `retry delays back off and end with one bounded fallback attempt`() {
        assertEquals(100L, DiagnosticsSnapshotRefreshPolicy.nextRetryDelayMillis(0))
        assertEquals(200L, DiagnosticsSnapshotRefreshPolicy.nextRetryDelayMillis(1))
        assertEquals(400L, DiagnosticsSnapshotRefreshPolicy.nextRetryDelayMillis(2))
        assertEquals(
            "one final delayed attempt covers a service that settles without an event",
            5_000L,
            DiagnosticsSnapshotRefreshPolicy.nextRetryDelayMillis(3),
        )
        assertNull(
            "no further attempt after the final fallback",
            DiagnosticsSnapshotRefreshPolicy.nextRetryDelayMillis(4),
        )
        assertNull("negative progress has no retry", DiagnosticsSnapshotRefreshPolicy.nextRetryDelayMillis(-1))
    }

    @Test
    fun `a caller can disable retries entirely`() {
        assertNull(DiagnosticsSnapshotRefreshPolicy.nextRetryDelayMillis(0, maxRefreshes = 0))
    }

    @Test
    fun `backoff shift is clamped for larger custom budgets`() {
        assertEquals(1_600L, DiagnosticsSnapshotRefreshPolicy.nextRetryDelayMillis(5, maxRefreshes = 6))
    }
}
