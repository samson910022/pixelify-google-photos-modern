package io.github.samson910022.pixelifyphotos

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [ScopePolicy] multi-app denylist (Option B).
 */
class ScopePolicyTest {

    @Test
    fun `Photos is allowed`() {
        assertTrue(ScopePolicy.shouldSpoof(Constants.PACKAGE_NAME_GOOGLE_PHOTOS))
        assertFalse(ScopePolicy.isDenied(Constants.PACKAGE_NAME_GOOGLE_PHOTOS))
    }

    @Test
    fun `random third-party app is allowed`() {
        assertTrue(ScopePolicy.shouldSpoof("com.example.gallery"))
        assertFalse(ScopePolicy.isDenied("com.example.gallery"))
    }

    @Test
    fun `each denylist package is denied`() {
        val expected = setOf(
            "com.google.android.gms",
            "com.android.vending",
            "com.google.android.gsf",
            "com.google.android.gsf.login",
            "com.google.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.android.settings",
            "com.android.systemui",
            "com.android.phone",
        )
        assertEquals(expected, ScopePolicy.DENYLIST)
        expected.forEach { pkg ->
            assertTrue("Expected denied: $pkg", ScopePolicy.isDenied(pkg))
            assertFalse("Expected shouldSpoof false: $pkg", ScopePolicy.shouldSpoof(pkg))
        }
    }

    @Test
    fun `denylist has no duplicates`() {
        assertEquals(ScopePolicy.DENYLIST.size, ScopePolicy.DENYLIST.toList().size)
    }

    @Test
    fun `null and empty package are not spoofed`() {
        assertFalse(ScopePolicy.shouldSpoof(null))
        assertFalse(ScopePolicy.shouldSpoof(""))
        assertFalse(ScopePolicy.isDenied(null))
        assertFalse(ScopePolicy.isDenied(""))
    }

    @Test
    fun `denylist match is exact not prefix`() {
        // Near-miss packages should still be allowed (exact-match policy).
        assertTrue(ScopePolicy.shouldSpoof("com.google.android.gms.unstable"))
        assertTrue(ScopePolicy.shouldSpoof("com.android.vending.foo"))
        assertTrue(ScopePolicy.shouldSpoof("com.android.settings.intelligence"))
    }
}
