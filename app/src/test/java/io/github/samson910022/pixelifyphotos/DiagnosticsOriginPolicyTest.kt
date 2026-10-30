package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsOriginPolicyTest {
    @Test
    fun `provider verifies origin only for one exact calling package`() {
        val photos = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        assertEquals(photos, DiagnosticsOriginPolicy.providerVerifiedOrigin(photos, arrayOf(photos)))
        assertNull(DiagnosticsOriginPolicy.providerVerifiedOrigin(photos, arrayOf(photos, "com.example.shared")))
        assertNull(DiagnosticsOriginPolicy.providerVerifiedOrigin(photos, arrayOf("com.example.other")))
    }

    @Test
    fun `single-origin telemetry explicitly does not attribute secondary same-process callbacks`() {
        val photos = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        assertTrue(DiagnosticsOriginPolicy.supportsTargetRunCallback(photos, photos))
        assertFalse(DiagnosticsOriginPolicy.supportsTargetRunCallback(photos, "com.example.scoped.app"))
        assertFalse(DiagnosticsOriginPolicy.supportsTargetRunCallback(null, photos))
    }

    @Test
    fun `API 34 broadcast requires exact sent package and UID ownership`() {
        val photos = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        val sharedUidPackages = arrayOf(photos, "com.example.shared")
        assertEquals(
            photos,
            DiagnosticsOriginPolicy.broadcastVerifiedOrigin(
                photos, sentUid = 10123, invalidUid = -1, sentPackage = photos,
                packagesForUid = sharedUidPackages,
            ),
        )
        assertNull(
            DiagnosticsOriginPolicy.broadcastVerifiedOrigin(
                photos, sentUid = 10123, invalidUid = -1, sentPackage = "com.example.shared",
                packagesForUid = sharedUidPackages,
            ),
        )
        assertNull(
            DiagnosticsOriginPolicy.broadcastVerifiedOrigin(
                photos, sentUid = 10123, invalidUid = -1, sentPackage = photos,
                packagesForUid = arrayOf("com.example.shared"),
            ),
        )
    }

    @Test
    fun `API 34 identity handoff requires verification and retains only UID-owned packages`() {
        val photos = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        val sharedPackages = arrayOf(photos, "com.example.shared")
        val verified = DiagnosticsOriginPolicy.broadcastCallerIdentity(
            origin = photos,
            sentUid = 10123,
            invalidUid = -1,
            sentPackage = photos,
            packagesForUid = sharedPackages,
            myUid = 10000,
        )

        assertEquals(10123, verified.callingUid)
        assertEquals(10000, verified.myUid)
        assertEquals(photos, verified.verifiedOrigin)
        assertTrue(verified.requireVerifiedOrigin)
        assertTrue(verified.callingPackages!!.contains("com.example.shared"))

        val invalid = DiagnosticsOriginPolicy.broadcastCallerIdentity(
            origin = photos,
            sentUid = 10123,
            invalidUid = -1,
            sentPackage = "com.example.shared",
            packagesForUid = sharedPackages,
            myUid = 10000,
        )
        assertNull(invalid.verifiedOrigin)
        assertTrue(invalid.requireVerifiedOrigin)

        val unavailable = DiagnosticsOriginPolicy.broadcastCallerIdentity(
            origin = photos,
            sentUid = -1,
            invalidUid = -1,
            sentPackage = photos,
            packagesForUid = sharedPackages,
            myUid = 10000,
        )
        assertNull(unavailable.callingUid)
        assertFalse(unavailable.requireVerifiedOrigin)
    }

    @Test
    fun `token-only prior-boot replacement is limited to API 30 through 33`() {
        assertFalse(DiagnosticsOriginPolicy.allowsTokenOnlyPriorBootReplacement(26, tokenOnlyAllowed = true))
        assertFalse(DiagnosticsOriginPolicy.allowsTokenOnlyPriorBootReplacement(29, tokenOnlyAllowed = true))
        assertTrue(DiagnosticsOriginPolicy.allowsTokenOnlyPriorBootReplacement(30, tokenOnlyAllowed = true))
        assertTrue(DiagnosticsOriginPolicy.allowsTokenOnlyPriorBootReplacement(33, tokenOnlyAllowed = true))
        assertFalse(DiagnosticsOriginPolicy.allowsTokenOnlyPriorBootReplacement(34, tokenOnlyAllowed = true))
        assertFalse(DiagnosticsOriginPolicy.allowsTokenOnlyPriorBootReplacement(30, tokenOnlyAllowed = false))
        assertFalse(DiagnosticsOriginPolicy.allowsTokenOnlyPriorBootReplacement(33, tokenOnlyAllowed = false))
    }

    @Test
    fun `Android Q starts early feature hook while Android 9 waits for ready`() {
        assertTrue(ModuleLifecyclePolicy.shouldHookFeaturesEarly(29))
        assertTrue(ModuleLifecyclePolicy.shouldHookFeaturesEarly(36))
        org.junit.Assert.assertFalse(ModuleLifecyclePolicy.shouldHookFeaturesEarly(28))
    }

    @Test
    fun `API 101 first-package flag processes only first package`() {
        assertTrue(ModuleLifecyclePolicy.shouldHandlePackageLoaded(isFirstPackage = true))
        org.junit.Assert.assertFalse(ModuleLifecyclePolicy.shouldHandlePackageLoaded(isFirstPackage = false))
    }
}
