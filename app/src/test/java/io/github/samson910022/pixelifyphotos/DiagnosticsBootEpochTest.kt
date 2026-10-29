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

    @Test
    fun `proc stat parser extracts only a valid btime line`() {
        val procStat = sequenceOf(
            "cpu  1 2 3 4",
            "intr 12345",
            "btime 1700000000",
            "processes 9876",
        )
        assertEquals("1700000000", DiagnosticsBootEpoch.parseBootTimeLine(procStat))
        assertEquals(
            DiagnosticsBootEpoch.hashBootTime("1700000000"),
            DiagnosticsBootEpoch.parseBootTimeLine(procStat)?.let(DiagnosticsBootEpoch::hashBootTime),
        )
        assertNull(DiagnosticsBootEpoch.parseBootTimeLine(sequenceOf("cpu 1 2 3")))
        assertNull(DiagnosticsBootEpoch.parseBootTimeLine(sequenceOf("btime not-a-number")))
        assertNull(DiagnosticsBootEpoch.parseBootTimeLine(sequenceOf("btime 17000000000000")))
    }

    @Test
    fun `proc stat boot time is a validated deterministic fallback identity`() {
        val hash = DiagnosticsBootEpoch.hashBootTime("1700000000")

        assertEquals(64, hash?.length)
        assertEquals(hash, DiagnosticsBootEpoch.hashBootTime(" 1700000000 "))
        assertNotEquals(hash, DiagnosticsBootEpoch.hashBootTime("1700000001"))
        assertNotEquals(
            "a boot-time fallback must not collide with a boot UUID hash",
            hash,
            DiagnosticsBootEpoch.hashBootId("123e4567-e89b-12d3-a456-426614174000"),
        )
        assertNull(DiagnosticsBootEpoch.hashBootTime(""))
        assertNull(DiagnosticsBootEpoch.hashBootTime("btime"))
        assertNull(DiagnosticsBootEpoch.hashBootTime("17 00"))
        assertNull(DiagnosticsBootEpoch.hashBootTime("17000000000000"))
    }
}
