package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureCustomizeChangePolicyTest {

    @Test
    fun `levels without flags are not rendered but stay preserved on save`() {
        val emptyLevel = DeviceProps.allFeatures.first { it.featureFlags.isEmpty() }
        val renderedNames = FeatureCustomizeChangePolicy.renderedFeatureNames(DeviceProps.allFeatures)

        assertFalse(emptyLevel.displayName in renderedNames)
        assertTrue(renderedNames.isNotEmpty())
        assertTrue(
            DeviceProps.allFeatures
                .filter { it.featureFlags.isNotEmpty() }
                .all { it.displayName in renderedNames },
        )

        val renderedLevel = DeviceProps.allFeatures.first { it.featureFlags.isNotEmpty() }.displayName
        val unrenderedImportedName = "future imported feature flag"
        val stored = setOf(emptyLevel.displayName, unrenderedImportedName, renderedLevel)

        val persisted = FeatureCustomizeChangePolicy.preserveUnrendered(
            stored,
            renderedNames,
            selectedRendered = emptySet(),
        )

        assertEquals(setOf(emptyLevel.displayName, unrenderedImportedName), persisted)
    }
}
