package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsBootEpochTest {
    @Test
    fun `boot UUIDs are validated and stored only as deterministic SHA-256 hashes`() {
        val bootId = "123e4567-e89b-12d3-a456-426614174000"
        val hash = DiagnosticsBootEpoch.hashBootId(bootId)

        assertEquals(64, hash?.length)
        assertEquals(hash, DiagnosticsBootEpoch.hashBootId(bootId.uppercase()))
        assertNotEquals(hash, DiagnosticsBootEpoch.hashBootId("123e4567-e89b-12d3-a456-426614174001"))
        assertNull(DiagnosticsBootEpoch.hashBootId("not-a-boot-uuid"))
        assertTrue(hash != bootId)
    }
}
