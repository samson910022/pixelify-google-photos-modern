package io.github.samson910022.pixelifyphotos

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the spoofing decision logic used by [FeatureSpoofer].
 *
 * Since [FeatureSpoofer] is a Kotlin object with private state and requires
 * Android SharedPreferences + XposedModule to initialize, we test the
 * *logic pattern* directly here. This is the recommended approach:
 *
 * 1. Extract the pure decision logic into a testable function/class.
 * 2. Verify all decision branches with concrete inputs.
 *
 * These tests validate the correctness of the spoofing rules:
 * - Feature in finalFeaturesToSpoof → TRUE
 * - Feature in featuresNotToSpoof AND overrideCustomROMLevels → FALSE
 * - Otherwise → pass-through (null means "don't intercept")
 */
class FeatureSpoofLogicTest {

    // =========================================================================
    // Helper: pure-function recreation of FeatureSpoofer.decideSpoof logic
    // =========================================================================

    /**
     * Represents the possible outcomes of the spoofing decision.
     */
    enum class SpoofDecision {
        /** Return true — feature is reported as present. */
        TRUE,
        /** Return false — feature is reported as absent. */
        FALSE,
        /** Pass through to original implementation. */
        PASS_THROUGH,
    }

    /**
     * Pure-function version of FeatureSpoofer.decideSpoof logic.
     * This mirrors the exact branching in FeatureSpoofer lines 177-201.
     */
    private fun decideSpoof(
        feature: String?,
        initialized: Boolean,
        finalFeaturesToSpoof: Set<String>,
        featuresNotToSpoof: Set<String>,
        overrideCustomROMLevels: Boolean,
        passThroughAll: Boolean = false,
    ): SpoofDecision {
        if (!initialized || passThroughAll || feature == null) return SpoofDecision.PASS_THROUGH

        return when {
            feature in finalFeaturesToSpoof -> SpoofDecision.TRUE
            overrideCustomROMLevels && feature in featuresNotToSpoof -> SpoofDecision.FALSE
            else -> SpoofDecision.PASS_THROUGH
        }
    }

    /**
     * Pure-function version of FeatureSpoofer.initFromPrefs logic.
     * Reconstructs the feature lists from selected display names.
     */
    /**
     * @return Triple(spoof, notToSpoof, passThroughAll)
     */
    private fun buildFeatureLists(
        selectedNames: Set<String>?,
        overrideCustomROMLevels: Boolean,
        deviceName: String = DeviceProps.defaultDeviceName,
    ): Triple<Set<String>, Set<String>, Boolean> {
        val defaultNames = DeviceProps.defaultFeatures
            .map { it.displayName }
            .toSet()

        val effectiveSelected = selectedNames ?: defaultNames

        if (deviceName == "None" || effectiveSelected.isEmpty()) {
            return Triple(emptySet(), emptySet(), true)
        }

        val eligibleFeatures = when {
            effectiveSelected == defaultNames -> DeviceProps.defaultFeatures
            else -> DeviceProps.allFeatures.filter { it.displayName in effectiveSelected }
        }

        val finalFeaturesToSpoof = eligibleFeatures.flatMap { it.featureFlags }.toSet()
        val allFlags = DeviceProps.allFeatures.flatMap { it.featureFlags }.toSet()
        val featuresNotToSpoof = allFlags - finalFeaturesToSpoof

        return Triple(finalFeaturesToSpoof, featuresNotToSpoof, false)
    }

    // =========================================================================
    // decideSpoof: TRUE path
    // =========================================================================

    @Test
    fun `decideSpoof returns TRUE when feature is in finalFeaturesToSpoof`() {
        val spoof = setOf("com.google.android.feature.PIXEL_2020_EXPERIENCE")
        assertEquals(
            SpoofDecision.TRUE,
            decideSpoof(
                feature = "com.google.android.feature.PIXEL_2020_EXPERIENCE",
                initialized = true,
                finalFeaturesToSpoof = spoof,
                featuresNotToSpoof = emptySet(),
                overrideCustomROMLevels = false,
            )
        )
    }

    @Test
    fun `decideSpoof returns TRUE for all default feature flags`() {
        val (spoof, _, passThrough) = buildFeatureLists(null, true)
        spoof.forEach { flag ->
            assertEquals(
                "Flag '$flag' should be spoofed as TRUE",
                SpoofDecision.TRUE,
                decideSpoof(flag, true, spoof, emptySet(), false)
            )
        }
    }

    // =========================================================================
    // decideSpoof: FALSE path
    // =========================================================================

    @Test
    fun `decideSpoof returns FALSE when feature is in featuresNotToSpoof and override enabled`() {
        val notToSpoof = setOf("com.google.android.feature.PIXEL_2024_EXPERIENCE")
        assertEquals(
            SpoofDecision.FALSE,
            decideSpoof(
                feature = "com.google.android.feature.PIXEL_2024_EXPERIENCE",
                initialized = true,
                finalFeaturesToSpoof = emptySet(),
                featuresNotToSpoof = notToSpoof,
                overrideCustomROMLevels = true,
            )
        )
    }

    @Test
    fun `decideSpoof returns PASS_THROUGH when override is disabled even if in featuresNotToSpoof`() {
        val notToSpoof = setOf("com.google.android.feature.PIXEL_2024_EXPERIENCE")
        assertEquals(
            SpoofDecision.PASS_THROUGH,
            decideSpoof(
                feature = "com.google.android.feature.PIXEL_2024_EXPERIENCE",
                initialized = true,
                finalFeaturesToSpoof = emptySet(),
                featuresNotToSpoof = notToSpoof,
                overrideCustomROMLevels = false,
            )
        )
    }

    // =========================================================================
    // decideSpoof: PASS_THROUGH path
    // =========================================================================

    @Test
    fun `decideSpoof returns PASS_THROUGH when not initialized`() {
        assertEquals(
            SpoofDecision.PASS_THROUGH,
            decideSpoof(
                feature = "com.google.android.feature.PIXEL_2020_EXPERIENCE",
                initialized = false,
                finalFeaturesToSpoof = setOf("com.google.android.feature.PIXEL_2020_EXPERIENCE"),
                featuresNotToSpoof = emptySet(),
                overrideCustomROMLevels = true,
            )
        )
    }

    @Test
    fun `decideSpoof returns PASS_THROUGH for null feature`() {
        assertEquals(
            SpoofDecision.PASS_THROUGH,
            decideSpoof(
                feature = null,
                initialized = true,
                finalFeaturesToSpoof = setOf("com.google.android.feature.PIXEL_2020_EXPERIENCE"),
                featuresNotToSpoof = emptySet(),
                overrideCustomROMLevels = true,
            )
        )
    }

    @Test
    fun `decideSpoof returns PASS_THROUGH for unknown feature not in any list`() {
        assertEquals(
            SpoofDecision.PASS_THROUGH,
            decideSpoof(
                feature = "com.samsung.feature.SAMSUNG_EXPERIENCE",
                initialized = true,
                finalFeaturesToSpoof = setOf("com.google.android.feature.PIXEL_2020_EXPERIENCE"),
                featuresNotToSpoof = setOf("com.google.android.feature.PIXEL_2024_EXPERIENCE"),
                overrideCustomROMLevels = true,
            )
        )
    }

    // =========================================================================
    // buildFeatureLists: Default selection
    // =========================================================================

    @Test
    fun `buildFeatureLists with null selection uses default features`() {
        val (spoof, notToSpoof, passThrough) = buildFeatureLists(null, true)
        assertFalse(passThrough)
        // Default is Pixel 2020 = 7 levels
        assertTrue("Should have spoof flags", spoof.isNotEmpty())
        // Pixel 2016 has 5 flags, 2017-2020 have 2 each = 5 + 6*2 = 17
        assertEquals(17, spoof.size)
        assertTrue(notToSpoof.isNotEmpty())
    }

    @Test
    fun `buildFeatureLists with default names matches defaultFeatures`() {
        val defaultNames = DeviceProps.defaultFeatures.map { it.displayName }.toSet()
        val (spoof, _, passThrough) = buildFeatureLists(defaultNames, true)
        assertFalse(passThrough)
        val expectedFlags = DeviceProps.defaultFeatures.flatMap { it.featureFlags }.toSet()
        assertEquals(expectedFlags, spoof)
    }

    // =========================================================================
    // buildFeatureLists: Empty selection
    // =========================================================================

    @Test
    fun `buildFeatureLists with empty set enables full pass-through`() {
        val (spoof, notToSpoof, passThrough) = buildFeatureLists(emptySet(), true)
        assertTrue(passThrough)
        assertTrue("Should have no spoof flags", spoof.isEmpty())
        assertTrue("Should not force FALSE flags in pass-through mode", notToSpoof.isEmpty())
    }

    @Test
    fun `buildFeatureLists with device None enables full pass-through`() {
        val (spoof, notToSpoof, passThrough) = buildFeatureLists(
            selectedNames = DeviceProps.defaultFeatures.map { it.displayName }.toSet(),
            overrideCustomROMLevels = true,
            deviceName = "None",
        )
        assertTrue(passThrough)
        assertTrue(spoof.isEmpty())
        assertTrue(notToSpoof.isEmpty())
    }

    @Test
    fun `decideSpoof returns PASS_THROUGH when passThroughAll is true`() {
        assertEquals(
            SpoofDecision.PASS_THROUGH,
            decideSpoof(
                feature = "com.google.android.feature.PIXEL_2020_EXPERIENCE",
                initialized = true,
                finalFeaturesToSpoof = setOf("com.google.android.feature.PIXEL_2020_EXPERIENCE"),
                featuresNotToSpoof = emptySet(),
                overrideCustomROMLevels = true,
                passThroughAll = true,
            )
        )
    }

    // =========================================================================
    // buildFeatureLists: Custom selection
    // =========================================================================

    @Test
    fun `buildFeatureLists with single feature level returns only that level's flags`() {
        val (spoof, _, passThrough) = buildFeatureLists(setOf("Pixel 2017"), true)
        assertFalse(passThrough)
        assertEquals(2, spoof.size)
        assertTrue(spoof.contains("com.google.android.feature.PIXEL_2017_EXPERIENCE"))
        assertTrue(spoof.contains("com.google.android.apps.photos.PIXEL_2017_PRELOAD"))
    }

    @Test
    fun `buildFeatureLists with non-contiguous levels unions their flags`() {
        val (spoof, _, passThrough) = buildFeatureLists(setOf("Pixel 2016", "Pixel 2024"), true)
        assertFalse(passThrough)
        // Pixel 2016 has 5 flags, Pixel 2024 has 2 flags = 7 total
        assertEquals(7, spoof.size)
    }

    @Test
    fun `buildFeatureLists spoof and notToSpoof are disjoint`() {
        val (spoof, notToSpoof, passThrough) = buildFeatureLists(setOf("Pixel 2020"), true)
        assertFalse(passThrough)
        assertTrue(
            "Spoof and notToSpoof should be disjoint",
            spoof.intersect(notToSpoof).isEmpty()
        )
    }

    @Test
    fun `buildFeatureLists union of spoof and notToSpoof equals all flags`() {
        val (spoof, notToSpoof, passThrough) = buildFeatureLists(setOf("Pixel 2019"), true)
        assertFalse(passThrough)
        val allFlags = DeviceProps.allFeatures.flatMap { it.featureFlags }.toSet()
        assertEquals(allFlags, spoof + notToSpoof)
    }

    @Test
    fun `buildFeatureLists with nonexistent level produces empty spoof`() {
        val (spoof, notToSpoof, passThrough) = buildFeatureLists(setOf("Pixel 9999"), true)
        assertFalse(passThrough)
        assertTrue(spoof.isEmpty())
        val allFlags = DeviceProps.allFeatures.flatMap { it.featureFlags }.toSet()
        assertEquals(allFlags, notToSpoof)
    }

    // =========================================================================
    // Integration: full decision flow
    // =========================================================================

    @Test
    fun `full flow - default selection spoofs Pixel 2020 and earlier, hides later`() {
        val (spoof, notToSpoof, passThrough) = buildFeatureLists(null, true)
        assertFalse(passThrough)

        // A Pixel 2020 flag should be spoofed as TRUE
        assertEquals(
            SpoofDecision.TRUE,
            decideSpoof("com.google.android.feature.PIXEL_2020_EXPERIENCE", true, spoof, notToSpoof, true, passThrough)
        )

        // A Pixel 2024 flag should be spoofed as FALSE (hidden)
        assertEquals(
            SpoofDecision.FALSE,
            decideSpoof("com.google.android.feature.PIXEL_2024_EXPERIENCE", true, spoof, notToSpoof, true, passThrough)
        )

        // A completely unknown flag should pass through
        assertEquals(
            SpoofDecision.PASS_THROUGH,
            decideSpoof("com.samsung.feature.SOMETHING", true, spoof, notToSpoof, true, passThrough)
        )
    }

    @Test
    fun `full flow - Pixel 9 selection spoofs all 12 levels`() {
        val (spoof, notToSpoof, passThrough) = buildFeatureLists(
            DeviceProps.allFeatures.map { it.displayName }.toSet(),
            true
        )
        assertFalse(passThrough)
        // All known flags should be spoofed
        val allFlags = DeviceProps.allFeatures.flatMap { it.featureFlags }.toSet()
        assertEquals(allFlags, spoof)
        assertTrue("NotToSpoof should be empty when all levels selected", notToSpoof.isEmpty())
    }
}
