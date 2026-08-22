package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BindRenderCoordinator], which collapses redundant diagnostics
 * renders when the XposedService binds while a resume transaction is still in
 * flight, while preserving the mandatory unbound→bound refresh.
 */
class BindRenderCoordinatorTest {

    @Test
    fun `fresh instance conservatively allows posted render`() {
        assertFalse(BindRenderCoordinator().skipPostedRender(serviceNowBound = true))
    }

    @Test
    fun `resume render observing bound state collapses duplicate posted render`() {
        val coordinator = BindRenderCoordinator()
        coordinator.onRendered(serviceWasBound = true)

        assertTrue(coordinator.skipPostedRender(serviceNowBound = true))
    }

    @Test
    fun `resume render observing unbound state keeps mandatory posted render`() {
        val coordinator = BindRenderCoordinator()
        coordinator.onRendered(serviceWasBound = false)

        assertFalse(coordinator.skipPostedRender(serviceNowBound = true))
    }

    @Test
    fun `after a render records bound state redundant posted decision skips`() {
        val coordinator = BindRenderCoordinator()
        coordinator.onRendered(serviceWasBound = false)

        // Mandatory refresh happens...
        assertFalse(coordinator.skipPostedRender(serviceNowBound = true))
        coordinator.onRendered(serviceWasBound = true)

        // ...and any hypothetical repeat is now redundant.
        assertTrue(coordinator.skipPostedRender(serviceNowBound = true))
    }

    @Test
    fun `unbound posted decision never skips regardless of history`() {
        val coordinator = BindRenderCoordinator()
        coordinator.onRendered(serviceWasBound = true)

        assertFalse(coordinator.skipPostedRender(serviceNowBound = false))
    }
}
