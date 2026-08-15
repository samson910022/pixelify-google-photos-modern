package io.github.samson910022.pixelifyphotos

import org.junit.Assert.*
import org.junit.Test

class SpoofedPackageTrackerTest {

    @Test
    fun `packagesToForceStop always includes Photos`() {
        assertEquals(
            setOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
            SpoofedPackageTracker.packagesToForceStop(null),
        )
        assertEquals(
            setOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
            SpoofedPackageTracker.packagesToForceStop(emptySet()),
        )
    }

    @Test
    fun `packagesToForceStop includes LSPosed scope third-party apps`() {
        val result = SpoofedPackageTracker.packagesToForceStop(
            setOf("com.example.gallery", Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
        )
        assertEquals(
            setOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, "com.example.gallery"),
            result,
        )
    }

    @Test
    fun `packagesToForceStop drops denylist and invalid names`() {
        val result = SpoofedPackageTracker.packagesToForceStop(
            setOf(
                "com.google.android.gms",
                "com.android.vending",
                "bad name",
                "",
                "com.example.ok",
            ),
        )
        assertEquals(
            setOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, "com.example.ok"),
            result,
        )
    }

    @Test
    fun `packagesToForceStop never includes the module itself`() {
        val result = SpoofedPackageTracker.packagesToForceStop(
            setOf(
                Constants.PACKAGE_NAME_MODULE,
                Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                "com.example.gallery",
            ),
        )
        assertFalse(result.contains(Constants.PACKAGE_NAME_MODULE))
        assertEquals(
            setOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, "com.example.gallery"),
            result,
        )
    }

    @Test
    fun `isValidPackageName rejects blank and injection-like names`() {
        assertTrue(SpoofedPackageTracker.isValidPackageName("com.google.android.apps.photos"))
        assertFalse(SpoofedPackageTracker.isValidPackageName(null))
        assertFalse(SpoofedPackageTracker.isValidPackageName(""))
        assertFalse(SpoofedPackageTracker.isValidPackageName("com.foo; rm -rf /"))
        assertFalse(SpoofedPackageTracker.isValidPackageName("bad name"))
    }
}
