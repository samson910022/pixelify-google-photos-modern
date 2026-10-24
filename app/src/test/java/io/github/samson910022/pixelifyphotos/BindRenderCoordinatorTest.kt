package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import io.github.libxposed.service.XposedService

/** Unit tests for readiness-version render policy while DiagnosticsActivity is resumed. */
class BindRenderCoordinatorTest {

    @Test
    fun `fresh coordinator allows initial service state render`() {
        assertTrue(BindRenderCoordinator().shouldRenderState(1L))
    }

    @Test
    fun `rendered state suppresses duplicate callback for same version`() {
        val coordinator = BindRenderCoordinator()
        coordinator.onRendered(7L)

        assertFalse(coordinator.shouldRenderState(7L))
    }

    @Test
    fun `resumed Activity renders ready death and rebind transitions without pausing`() {
        val coordinator = BindRenderCoordinator()

        // The first ready bind was displayed, then service death and rebind happen
        // while this Activity remains resumed.
        assertTrue("initial ready bind", coordinator.shouldRenderState(10L))
        coordinator.onRendered(10L)

        assertTrue("death transition must refresh the disconnected screen", coordinator.shouldRenderState(11L))
        coordinator.onRendered(11L)

        assertTrue("rebind must refresh even without another onResume", coordinator.shouldRenderState(12L))
        coordinator.onRendered(12L)
        assertFalse("late duplicate callback cannot redraw the same state", coordinator.shouldRenderState(12L))
    }

    @Test
    fun `out of order callbacks older than the rendered service state are ignored`() {
        val coordinator = BindRenderCoordinator()
        coordinator.onRendered(20L)

        assertFalse(coordinator.shouldRenderState(19L))
        assertTrue(coordinator.shouldRenderState(21L))
    }

    @Test
    fun `snapshot capture retries when service changes after matching preferences were read`() {
        val service = mock<XposedService>()
        var current = ServiceReadinessSnapshot(service, ServicePhase.CONNECTED_SYNCING, 4L)
        var reads = 0
        val captured = ServiceSnapshotCapture.capture(
            readServiceState = { current },
            readForState = { state ->
                reads++
                if (state.ready) "remote preferences for version ${state.version}"
                else "stale local preferences for version ${state.version}"
            },
            afterRead = {
                if (reads == 1) current = ServiceReadinessSnapshot(service, ServicePhase.READY, 5L)
            },
        )

        assertTrue(captured.consistent)
        assertEquals(2, reads)
        assertEquals(5L, captured.serviceState.version)
        assertEquals("remote preferences for version 5", captured.value)
    }

    @Test
    fun `snapshot capture does not acknowledge data when service changes on every bounded read`() {
        var version = 10L
        val captured = ServiceSnapshotCapture.capture(
            maxAttempts = 2,
            readServiceState = { ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, version) },
            readForState = { "preferences-version-${it.version}" },
            afterRead = { version++ },
        )

        assertFalse(captured.consistent)
        assertEquals(11L, captured.serviceState.version)
        assertEquals("preferences-version-11", captured.value)
        val coordinator = BindRenderCoordinator()
        assertTrue("inconsistent snapshot version must not be marked rendered", coordinator.shouldRenderState(11L))
    }
}
