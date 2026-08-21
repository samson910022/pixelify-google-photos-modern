package io.github.samson910022.pixelifyphotos

import android.content.pm.FeatureInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the spoofing decision logic inside [FeatureSpoofer].
 *
 * Tests the real internal methods of [FeatureSpoofer]:
 * - [FeatureSpoofer.initFromValues]
 * - [FeatureSpoofer.decideSpoofForFeature]
 * - [FeatureSpoofer.filterAndAugmentFeatures]
 * - [FeatureSpoofer.resetForTesting]
 */
class FeatureSpoofLogicTest {

    @Before
    fun setUp() {
        FeatureSpoofer.resetForTesting()
    }

    @After
    fun tearDown() {
        FeatureSpoofer.resetForTesting()
    }

    // =========================================================================
    // decideSpoofForFeature: TRUE path
    // =========================================================================

    @Test
    fun `decideSpoofForFeature returns TRUE when feature is configured for spoofing`() {
        FeatureSpoofer.initFromValues(
            deviceName = "Pixel XL",
            selectedNames = setOf("Pixel 2020"),
            overrideROM = true,
        )

        assertEquals(true, FeatureSpoofer.decideSpoofForFeature("com.google.android.feature.PIXEL_2020_EXPERIENCE"))
    }

    @Test
    fun `decideSpoofForFeature returns TRUE for all default feature flags`() {
        FeatureSpoofer.initFromValues(
            deviceName = "Pixel XL",
            selectedNames = DeviceProps.defaultFeatures.map { it.displayName }.toSet(),
            overrideROM = true,
        )

        val defaultFlags = DeviceProps.defaultFeatures.flatMap { it.featureFlags }.toSet()
        defaultFlags.forEach { flag ->
            assertEquals(
                "Flag '$flag' should be spoofed as TRUE",
                true,
                FeatureSpoofer.decideSpoofForFeature(flag)
            )
        }
    }

    // =========================================================================
    // decideSpoofForFeature: FALSE path
    // =========================================================================

    @Test
    fun `decideSpoofForFeature returns FALSE when feature is above level and override is enabled`() {
        FeatureSpoofer.initFromValues(
            deviceName = "Pixel XL",
            selectedNames = setOf("Pixel 2016"),
            overrideROM = true,
        )

        assertEquals(
            false,
            FeatureSpoofer.decideSpoofForFeature("com.google.android.feature.PIXEL_2024_EXPERIENCE")
        )
    }

    @Test
    fun `decideSpoofForFeature returns null when override is disabled even if feature is above level`() {
        FeatureSpoofer.initFromValues(
            deviceName = "Pixel XL",
            selectedNames = setOf("Pixel 2016"),
            overrideROM = false,
        )

        assertNull(
            FeatureSpoofer.decideSpoofForFeature("com.google.android.feature.PIXEL_2024_EXPERIENCE")
        )
    }

    // =========================================================================
    // decideSpoofForFeature: PASS_THROUGH (null) path
    // =========================================================================

    @Test
    fun `decideSpoofForFeature returns null when uninitialized`() {
        assertNull(
            FeatureSpoofer.decideSpoofForFeature("com.google.android.feature.PIXEL_2020_EXPERIENCE")
        )
    }

    @Test
    fun `decideSpoofForFeature returns null for unknown feature not in any Pixel profile`() {
        FeatureSpoofer.initFromValues(
            deviceName = "Pixel XL",
            selectedNames = setOf("Pixel 2016"),
            overrideROM = true,
        )

        assertNull(
            FeatureSpoofer.decideSpoofForFeature("com.samsung.feature.SAMSUNG_EXPERIENCE")
        )
    }

    @Test
    fun `decideSpoofForFeature returns null when passThroughAll is enabled with device None`() {
        FeatureSpoofer.initFromValues(
            deviceName = "None",
            selectedNames = setOf("Pixel 2016"),
            overrideROM = true,
        )

        assertNull(
            FeatureSpoofer.decideSpoofForFeature("com.google.android.feature.PIXEL_EXPERIENCE")
        )
    }

    @Test
    fun `decideSpoofForFeature returns null when passThroughAll is enabled with empty selection`() {
        FeatureSpoofer.initFromValues(
            deviceName = "Pixel XL",
            selectedNames = emptySet(),
            overrideROM = true,
        )

        assertNull(
            FeatureSpoofer.decideSpoofForFeature("com.google.android.feature.PIXEL_EXPERIENCE")
        )
    }

    // =========================================================================
    // Multi-level combinations
    // =========================================================================

    @Test
    fun `initFromValues with non-contiguous levels spoofs flags from both levels`() {
        FeatureSpoofer.initFromValues(
            deviceName = "Pixel XL",
            selectedNames = setOf("Pixel 2016", "Pixel 2024"),
            overrideROM = true,
        )

        assertEquals(true, FeatureSpoofer.decideSpoofForFeature("com.google.android.feature.PIXEL_EXPERIENCE"))
        assertEquals(true, FeatureSpoofer.decideSpoofForFeature("com.google.android.feature.PIXEL_2024_EXPERIENCE"))
        assertEquals(false, FeatureSpoofer.decideSpoofForFeature("com.google.android.feature.PIXEL_2020_EXPERIENCE"))
    }

    // =========================================================================
    // filterAndAugmentFeatures (getSystemAvailableFeatures interceptor)
    // =========================================================================

    @Test
    fun `filterAndAugmentFeatures modifies feature array correctly`() {
        FeatureSpoofer.initFromValues(
            deviceName = "Pixel XL",
            selectedNames = setOf("Pixel 2016"),
            overrideROM = true,
        )

        val orig1 = FeatureInfo().apply { name = "android.hardware.camera" }
        val orig2 = FeatureInfo().apply { name = "com.google.android.feature.PIXEL_2024_EXPERIENCE" } // should be removed
        val orig3 = FeatureInfo().apply { name = "android.hardware.wifi" }
        val origGlEs = FeatureInfo() // null name = OpenGL ES version

        val result = FeatureSpoofer.filterAndAugmentFeatures(arrayOf(orig1, orig2, orig3, origGlEs))
        assertNotNull(result)

        val resultNames = result!!.map { it.name }
        assertTrue("Camera should remain", resultNames.contains("android.hardware.camera"))
        assertTrue("WiFi should remain", resultNames.contains("android.hardware.wifi"))
        assertTrue("OpenGL ES feature with null name must be preserved", resultNames.contains(null))
        assertFalse("ROM 2024 feature must be filtered out", resultNames.contains("com.google.android.feature.PIXEL_2024_EXPERIENCE"))
        assertTrue("Pixel experience feature must be added", resultNames.contains("com.google.android.feature.PIXEL_EXPERIENCE"))
        assertTrue("Pixel 2016 preload feature must be added", resultNames.contains("com.google.android.apps.photos.PIXEL_2016_PRELOAD"))
    }

    @Test
    fun `filterAndAugmentFeatures preserves array in passThroughAll or uninitialized`() {
        val orig1 = FeatureInfo().apply { name = "android.hardware.camera" }

        val uninit = FeatureSpoofer.filterAndAugmentFeatures(arrayOf(orig1))
        assertNull(uninit)

        FeatureSpoofer.initFromValues("None", setOf("Pixel 2016"), true)
        val passThrough = FeatureSpoofer.filterAndAugmentFeatures(arrayOf(orig1))
        assertNull(passThrough)
    }

    @Test
    fun `filterAndAugmentFeatures does not duplicate already present features`() {
        FeatureSpoofer.initFromValues(
            deviceName = "Pixel XL",
            selectedNames = setOf("Pixel 2016"),
            overrideROM = true,
        )

        val orig1 = FeatureInfo().apply { name = "com.google.android.feature.PIXEL_EXPERIENCE" }
        val orig2 = FeatureInfo().apply { name = "android.hardware.camera" }

        val result = FeatureSpoofer.filterAndAugmentFeatures(arrayOf(orig1, orig2))
        assertNotNull(result)

        val count = result!!.count { it.name == "com.google.android.feature.PIXEL_EXPERIENCE" }
        assertEquals(1, count)
    }
}
