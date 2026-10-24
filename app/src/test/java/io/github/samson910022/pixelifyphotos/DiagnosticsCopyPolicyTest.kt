package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsCopyPolicyTest {
    @Test
    fun `inconsistent bounded capture is not copied`() {
        assertFalse(DiagnosticsCopyPolicy.mayCopy(consistent = false))
    }

    @Test
    fun `consistent capture may be copied`() {
        assertTrue(DiagnosticsCopyPolicy.mayCopy(consistent = true))
    }
}
