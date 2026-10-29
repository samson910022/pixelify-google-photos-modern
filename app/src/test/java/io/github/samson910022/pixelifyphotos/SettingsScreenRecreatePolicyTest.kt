package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenRecreatePolicyTest {
    @Test
    fun `first source mismatch may recreate once`() {
        assertTrue(SettingsScreenRecreatePolicy.mayRecreate(0))
        assertEquals(1, SettingsScreenRecreatePolicy.nextAttempts(0))
    }

    @Test
    fun `relaunch chain is capped after one recreate`() {
        assertFalse(SettingsScreenRecreatePolicy.mayRecreate(SettingsScreenRecreatePolicy.MAX_SYNC_RECREATES))
        assertEquals(
            SettingsScreenRecreatePolicy.MAX_SYNC_RECREATES,
            SettingsScreenRecreatePolicy.nextAttempts(SettingsScreenRecreatePolicy.MAX_SYNC_RECREATES),
        )
    }

    @Test
    fun `corrupt negative attempt count does not recreate`() {
        assertFalse(SettingsScreenRecreatePolicy.mayRecreate(-1))
    }
}
