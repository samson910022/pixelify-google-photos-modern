package io.github.samson910022.pixelifyphotos

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [DeviceProps] — the pure-data / logic object that defines
 * Pixel device entries, feature levels, and Android versions.
 *
 * All methods under test are pure functions with no Android framework
 * dependencies, so these tests run entirely on a host JVM.
 */
class DevicePropsTest {

    /** Devices shipped without cited FINGERPRINT/ID/INCREMENTAL/SECURITY_PATCH. */
    private val experimentalIdentityOnly = setOf(
        "Pixel 10 Pro Fold (experimental)",
        "Pixel 10a (experimental)",
    )

    // =========================================================================
    // allFeatures
    // =========================================================================

    @Test
    fun `allFeatures contains 13 feature levels`() {
        assertEquals(13, DeviceProps.allFeatures.size)
    }

    @Test
    fun `allFeatures display names are in chronological order`() {
        val names = DeviceProps.allFeatures.map { it.displayName }
        assertEquals(
            listOf(
                "Pixel 2016",
                "Pixel 2017",
                "Pixel 2018",
                "Pixel 2019 mid-year",
                "Pixel 2019",
                "Pixel 2020 mid-year",
                "Pixel 2020",
                "Pixel 2021 mid-year",
                "Pixel 2021",
                "Pixel 2022",
                "Pixel 2023",
                "Pixel 2024",
                "Pixel 2025",
            ),
            names
        )
    }

    @Test
    fun `allFeatures have no duplicate display names`() {
        val names = DeviceProps.allFeatures.map { it.displayName }
        assertEquals(names.toSet().size, names.size)
    }

    @Test
    fun `allFeatures have no duplicate feature flag strings across levels`() {
        val allFlags = DeviceProps.allFeatures.flatMap { it.featureFlags }
        assertEquals(allFlags.toSet().size, allFlags.size)
    }

    @Test
    fun `each feature level has at least one feature flag`() {
        DeviceProps.allFeatures.forEach { features ->
            assertTrue(
                "Feature level '${features.displayName}' should have at least one flag",
                features.featureFlags.isNotEmpty()
            )
        }
    }

    @Test
    fun `Pixel 2016 has 5 feature flags (legacy preload flags)`() {
        val pixel2016 = DeviceProps.allFeatures.first { it.displayName == "Pixel 2016" }
        assertEquals(5, pixel2016.featureFlags.size)
    }

    @Test
    fun `Pixel 2017 and later have exactly 2 feature flags each`() {
        DeviceProps.allFeatures.drop(1).forEach { features ->
            assertEquals(
                "Feature level '${features.displayName}' should have exactly 2 flags",
                2,
                features.featureFlags.size
            )
        }
    }

    @Test
    fun `feature flags from Pixel 2017 onwards contain the word PIXEL`() {
        DeviceProps.allFeatures.drop(1).flatMap { it.featureFlags }.forEach { flag ->
            assertTrue(
                "Flag '$flag' should contain 'PIXEL'",
                flag.contains("PIXEL", ignoreCase = true)
            )
        }
    }

    @Test
    fun `Pixel 2025 feature flags are present`() {
        val pixel2025 = DeviceProps.allFeatures.first { it.displayName == "Pixel 2025" }
        assertTrue(pixel2025.featureFlags.contains("com.google.android.feature.PIXEL_2025_EXPERIENCE"))
        assertTrue(pixel2025.featureFlags.contains("com.google.android.apps.photos.PIXEL_2025_PRELOAD"))
    }

    // =========================================================================
    // defaultFeatures
    // =========================================================================

    @Test
    fun `defaultFeatures contains feature levels up to Pixel 2016 inclusive`() {
        val defaultDisplayNames = DeviceProps.defaultFeatures.map { it.displayName }
        assertEquals(listOf("Pixel 2016"), defaultDisplayNames)
    }

    @Test
    fun `defaultFeatures has 1 feature level`() {
        assertEquals(1, DeviceProps.defaultFeatures.size)
    }

    @Test
    fun `defaultDeviceName is Pixel XL`() {
        assertEquals("Pixel XL", DeviceProps.defaultDeviceName)
    }

    // =========================================================================
    // getDeviceProps
    // =========================================================================

    @Test
    fun `getDeviceProps returns correct entry for Pixel 5`() {
        val device = DeviceProps.getDeviceProps("Pixel 5")
        assertNotNull(device)
        assertEquals("Pixel 5", device!!.deviceName)
        assertEquals("redfin", device.props["DEVICE"])
        assertEquals("google", device.props["BRAND"])
        assertEquals("Google", device.props["MANUFACTURER"])
        assertEquals("redfin", device.props["PRODUCT"])
        assertEquals("Pixel 5", device.props["MODEL"])
        assertTrue(device.props["FINGERPRINT"]!!.contains("redfin"))
    }

    @Test
    fun `getDeviceProps returns correct entry for Pixel 9 Pro XL`() {
        val device = DeviceProps.getDeviceProps("Pixel 9 Pro XL")
        assertNotNull(device)
        assertEquals("komodo", device!!.props["DEVICE"])
        assertEquals("Pixel 9 Pro XL", device.props["MODEL"])
        assertEquals("Pixel 2024", device.featureLevelName)
    }

    @Test
    fun `getDeviceProps returns None entry with empty props`() {
        val device = DeviceProps.getDeviceProps("None")
        assertNotNull(device)
        assertEquals("None", device!!.deviceName)
        assertTrue(device.props.isEmpty())
        assertNull(device.androidVersion)
    }

    @Test
    fun `getDeviceProps returns null for nonexistent device`() {
        assertNull(DeviceProps.getDeviceProps("Galaxy S24"))
    }

    @Test
    fun `getDeviceProps returns null for null input`() {
        assertNull(DeviceProps.getDeviceProps(null))
    }

    @Test
    fun `getDeviceProps returns null for empty string`() {
        assertNull(DeviceProps.getDeviceProps(""))
    }

    @Test
    fun `getDeviceProps is case sensitive`() {
        assertNull(DeviceProps.getDeviceProps("pixel 5"))
        assertNull(DeviceProps.getDeviceProps("PIXEL 5"))
    }

    @Test
    fun `all devices have allDevices count of 26`() {
        // 1 "None" + 25 actual devices (was 20 + Pixel 10 series 5)
        assertEquals(26, DeviceProps.allDevices.size)
    }

    @Test
    fun `all device names are unique`() {
        val names = DeviceProps.allDevices.map { it.deviceName }
        assertEquals(names.toSet().size, names.size)
    }

    @Test
    fun `all non-None devices have non-empty props`() {
        DeviceProps.allDevices.filter { it.deviceName != "None" }.forEach { device ->
            assertTrue(
                "Device '${device.deviceName}' should have non-empty props",
                device.props.isNotEmpty()
            )
        }
    }

    @Test
    fun `all non-None devices have BRAND=google and MANUFACTURER=Google`() {
        DeviceProps.allDevices.filter { it.deviceName != "None" }.forEach { device ->
            assertEquals(
                "Device '${device.deviceName}' BRAND should be 'google'",
                "google",
                device.props["BRAND"]
            )
            assertEquals(
                "Device '${device.deviceName}' MANUFACTURER should be 'Google'",
                "Google",
                device.props["MANUFACTURER"]
            )
        }
    }

    @Test
    fun `all non-None devices have an androidVersion`() {
        DeviceProps.allDevices.filter { it.deviceName != "None" }.forEach { device ->
            assertNotNull(
                "Device '${device.deviceName}' should have an androidVersion",
                device.androidVersion
            )
        }
    }

    @Test
    fun `Pixel 6a has ID and INCREMENTAL and SECURITY_PATCH properties`() {
        val device = DeviceProps.getDeviceProps("Pixel 6a")
        assertNotNull(device)
        assertNotNull(device!!.props["ID"])
        assertNotNull(device.props["INCREMENTAL"])
        assertNotNull(device.props["SECURITY_PATCH"])
    }

    @Test
    fun `each device's featureLevelName exists in allFeatures`() {
        val featureNames = DeviceProps.allFeatures.map { it.displayName }.toSet()
        DeviceProps.allDevices.filter { it.deviceName != "None" }.forEach { device ->
            assertTrue(
                "Device '${device.deviceName}' featureLevelName '${device.featureLevelName}' should exist in allFeatures",
                featureNames.contains(device.featureLevelName)
            )
        }
    }

    // =========================================================================
    // Pixel 9a fix + Pixel 10 series
    // =========================================================================

    @Test
    fun `Pixel 9a uses tegu codename and cited A16 fingerprint`() {
        val device = DeviceProps.getDeviceProps("Pixel 9a")
        assertNotNull(device)
        assertEquals("tegu", device!!.props["DEVICE"])
        assertEquals("tegu", device.props["PRODUCT"])
        assertEquals(
            "google/tegu/tegu:16/BP4A.260105.004.E1/14587043:user/release-keys",
            device.props["FINGERPRINT"]
        )
        assertEquals("BP4A.260105.004.E1", device.props["ID"])
        assertEquals("14587043", device.props["INCREMENTAL"])
        assertFalse(device.props.containsKey("SECURITY_PATCH"))
        assertFalse(device.props.values.any { it.contains("tehua") })
        assertFalse(device.props.values.any { it.contains("BP1A.250405.002") })
        assertFalse(device.props.values.any { it.contains("13115780") })
    }

    @Test
    fun `DeviceProps source has no tehua residue`() {
        // Runtime props must not expose tehua; this catches remaining map values.
        DeviceProps.allDevices.forEach { device ->
            device.props.forEach { (k, v) ->
                assertFalse(
                    "Device '${device.deviceName}' prop $k must not contain tehua",
                    v.contains("tehua", ignoreCase = true)
                )
            }
        }
    }

    @Test
    fun `Pixel 10 has pinned Android 16 frankel fingerprint`() {
        val device = DeviceProps.getDeviceProps("Pixel 10")
        assertNotNull(device)
        assertEquals("frankel", device!!.props["DEVICE"])
        assertEquals("frankel", device.props["PRODUCT"])
        assertEquals("Pixel 10", device.props["MODEL"])
        assertEquals("Pixel 2025", device.featureLevelName)
        assertEquals("Android 16", device.androidVersion?.label)
        assertEquals(
            "google/frankel/frankel:16/BD3A.250721.001/13808258:user/release-keys",
            device.props["FINGERPRINT"]
        )
        assertEquals("BD3A.250721.001", device.props["ID"])
        assertEquals("13808258", device.props["INCREMENTAL"])
        assertEquals("2025-08-05", device.props["SECURITY_PATCH"])
    }

    @Test
    fun `Pixel 10 Pro has pinned Android 16 blazer fingerprint`() {
        val device = DeviceProps.getDeviceProps("Pixel 10 Pro")
        assertNotNull(device)
        assertEquals("blazer", device!!.props["DEVICE"])
        assertEquals(
            "google/blazer/blazer:16/BD3A.250721.001/13808258:user/release-keys",
            device.props["FINGERPRINT"]
        )
        assertEquals("Pixel 2025", device.featureLevelName)
    }

    @Test
    fun `Pixel 10 Pro XL has pinned Android 16 mustang fingerprint`() {
        val device = DeviceProps.getDeviceProps("Pixel 10 Pro XL")
        assertNotNull(device)
        assertEquals("mustang", device!!.props["DEVICE"])
        assertEquals(
            "google/mustang/mustang:16/BD3A.250721.001/13808258:user/release-keys",
            device.props["FINGERPRINT"]
        )
        assertEquals("Pixel 2025", device.featureLevelName)
    }

    @Test
    fun `Pixel 10 Pro Fold is experimental identity-only rango`() {
        val device = DeviceProps.getDeviceProps("Pixel 10 Pro Fold (experimental)")
        assertNotNull(device)
        assertEquals("Pixel 10 Pro Fold (experimental)", device!!.deviceName)
        assertEquals("rango", device.props["DEVICE"])
        assertEquals("rango", device.props["PRODUCT"])
        assertEquals("Pixel 10 Pro Fold", device.props["MODEL"])
        assertEquals("google", device.props["BRAND"])
        assertEquals("Google", device.props["MANUFACTURER"])
        assertEquals("Pixel 2025", device.featureLevelName)
        assertEquals("Android 16", device.androidVersion?.label)
        assertFalse(device.props.containsKey("FINGERPRINT"))
        assertFalse(device.props.containsKey("ID"))
        assertFalse(device.props.containsKey("INCREMENTAL"))
        assertFalse(device.props.containsKey("SECURITY_PATCH"))
    }

    @Test
    fun `Pixel 10a is experimental identity-only stallion`() {
        val device = DeviceProps.getDeviceProps("Pixel 10a (experimental)")
        assertNotNull(device)
        assertEquals("Pixel 10a (experimental)", device!!.deviceName)
        assertEquals("stallion", device.props["DEVICE"])
        assertEquals("stallion", device.props["PRODUCT"])
        assertEquals("Pixel 10a", device.props["MODEL"])
        assertEquals("google", device.props["BRAND"])
        assertEquals("Google", device.props["MANUFACTURER"])
        assertEquals("Pixel 2025", device.featureLevelName)
        assertEquals("Android 16", device.androidVersion?.label)
        assertFalse(device.props.containsKey("FINGERPRINT"))
        assertFalse(device.props.containsKey("ID"))
        assertFalse(device.props.containsKey("INCREMENTAL"))
        assertFalse(device.props.containsKey("SECURITY_PATCH"))
    }

    // =========================================================================
    // getFeaturesUpTo (private, tested indirectly)
    // =========================================================================

    @Test
    fun `getFeaturesUpToFromDeviceName for Pixel 5 returns 7 display names`() {
        val result = DeviceProps.getFeaturesUpToFromDeviceName("Pixel 5")
        assertEquals(7, result.size)
    }

    @Test
    fun `getFeaturesUpToFromDeviceName for Pixel 2016 device returns 1 display name`() {
        val result = DeviceProps.getFeaturesUpToFromDeviceName("Pixel XL")
        assertEquals(setOf("Pixel 2016"), result)
    }

    @Test
    fun `getFeaturesUpToFromDeviceName for Pixel 9 Pro XL returns all 12 display names`() {
        val result = DeviceProps.getFeaturesUpToFromDeviceName("Pixel 9 Pro XL")
        assertEquals(12, result.size)
    }

    @Test
    fun `getFeaturesUpToFromDeviceName for Pixel 10 returns all 13 display names`() {
        val result = DeviceProps.getFeaturesUpToFromDeviceName("Pixel 10")
        assertEquals(13, result.size)
        assertTrue(result.contains("Pixel 2025"))
    }

    @Test
    fun `getFeaturesUpToFromDeviceName for Pixel 10 Pro Fold returns all 13 display names`() {
        val result = DeviceProps.getFeaturesUpToFromDeviceName("Pixel 10 Pro Fold (experimental)")
        assertEquals(13, result.size)
    }

    @Test
    fun `getFeaturesUpToFromDeviceName for Pixel 10a returns all 13 display names`() {
        val result = DeviceProps.getFeaturesUpToFromDeviceName("Pixel 10a (experimental)")
        assertEquals(13, result.size)
    }

    @Test
    fun `getFeaturesUpToFromDeviceName returns empty set for None`() {
        val result = DeviceProps.getFeaturesUpToFromDeviceName("None")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getFeaturesUpToFromDeviceName returns empty set for null`() {
        val result = DeviceProps.getFeaturesUpToFromDeviceName(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getFeaturesUpToFromDeviceName returns empty set for nonexistent device`() {
        val result = DeviceProps.getFeaturesUpToFromDeviceName("iPhone 15")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getFeaturesUpToFromDeviceName for Pixel 4 XL returns features up to Pixel 2019`() {
        val result = DeviceProps.getFeaturesUpToFromDeviceName("Pixel 4 XL")
        assertTrue(result.contains("Pixel 2019"))
        assertFalse(result.contains("Pixel 2020"))
        assertFalse(result.contains("Pixel 2024"))
    }

    // =========================================================================
    // AndroidVersion
    // =========================================================================

    @Test
    fun `allAndroidVersions contains 11 entries`() {
        assertEquals(11, DeviceProps.allAndroidVersions.size)
    }

    @Test
    fun `allAndroidVersions have unique labels`() {
        val labels = DeviceProps.allAndroidVersions.map { it.label }
        assertEquals(labels.toSet().size, labels.size)
    }

    @Test
    fun `allAndroidVersions have unique SDK values`() {
        val sdks = DeviceProps.allAndroidVersions.map { it.sdk }
        assertEquals(sdks.toSet().size, sdks.size)
    }

    @Test
    fun `allAndroidVersions SDK values are in ascending order`() {
        val sdks = DeviceProps.allAndroidVersions.map { it.sdk }
        assertEquals(sdks, sdks.sorted())
    }

    @Test
    fun `getAsMap returns correct keys and values for Android 14`() {
        val v14 = DeviceProps.allAndroidVersions.first { it.label == "Android 14" }
        val map = v14.getAsMap()
        assertEquals("14", map["RELEASE"])
        assertEquals(34, map["SDK_INT"])
        assertEquals("34", map["SDK"])
        assertEquals(3, map.size)
    }

    @Test
    fun `getAsMap returns correct values for Oreo 8_1_0`() {
        val oreo = DeviceProps.allAndroidVersions.first { it.label == "Oreo 8.1.0" }
        val map = oreo.getAsMap()
        assertEquals("8.1.0", map["RELEASE"])
        assertEquals(27, map["SDK_INT"])
        assertEquals("27", map["SDK"])
    }

    @Test
    fun `getAndroidVersionFromLabel returns correct version for Android 15`() {
        val v = DeviceProps.getAndroidVersionFromLabel("Android 15")
        assertNotNull(v)
        assertEquals("15", v!!.release)
        assertEquals(35, v.sdk)
    }

    @Test
    fun `getAndroidVersionFromLabel returns null for nonexistent label`() {
        assertNull(DeviceProps.getAndroidVersionFromLabel("Android 99"))
    }

    @Test
    fun `getAndroidVersionFromLabel returns null for empty string`() {
        assertNull(DeviceProps.getAndroidVersionFromLabel(""))
    }

    @Test
    fun `getAndroidVersionFromLabel is case sensitive`() {
        assertNull(DeviceProps.getAndroidVersionFromLabel("android 15"))
    }

    // =========================================================================
    // Data integrity: AndroidVersion references in DeviceEntries
    // =========================================================================

    @Test
    fun `each device's androidVersion label matches a known version`() {
        val knownLabels = DeviceProps.allAndroidVersions.map { it.label }.toSet()
        DeviceProps.allDevices.filter { it.androidVersion != null }.forEach { device ->
            assertTrue(
                "Device '${device.deviceName}' androidVersion.label '${device.androidVersion!!.label}' should be a known version",
                knownLabels.contains(device.androidVersion!!.label)
            )
        }
    }

    @Test
    fun `Pixel XL maps to Q 10_0`() {
        val device = DeviceProps.getDeviceProps("Pixel XL")
        assertNotNull(device)
        assertEquals("Q 10.0", device!!.androidVersion?.label)
        assertEquals(29, device.androidVersion?.sdk)
    }

    @Test
    fun `Pixel 9 maps to Android 16`() {
        val device = DeviceProps.getDeviceProps("Pixel 9")
        assertNotNull(device)
        assertEquals("Android 16", device!!.androidVersion?.label)
        assertEquals(36, device.androidVersion?.sdk)
    }

    @Test
    fun `Android 17 is in allAndroidVersions with SDK 37`() {
        val a17 = DeviceProps.allAndroidVersions.firstOrNull { it.sdk == 37 }
        assertNotNull("allAndroidVersions should contain SDK 37 (Android 17)", a17)
        assertEquals("Android 17", a17!!.label)
    }

    @Test
    fun `getAsMap returns correct keys and values for Android 17`() {
        val a17 = DeviceProps.allAndroidVersions.first { it.label == "Android 17" }
        val map = a17.getAsMap()
        assertEquals("17", map["RELEASE"])
        assertEquals(37, map["SDK_INT"])
        assertEquals("37", map["SDK"])
        assertEquals(3, map.size)
    }

    @Test
    fun `getAndroidVersionFromLabel returns correct version for Android 17`() {
        val v = DeviceProps.getAndroidVersionFromLabel("Android 17")
        assertNotNull(v)
        assertEquals("17", v!!.release)
        assertEquals(37, v.sdk)
    }

    // =========================================================================
    // Fingerprint format validation
    // =========================================================================

    @Test
    fun `all device fingerprints contain brand slash device slash device pattern`() {
        DeviceProps.allDevices
            .filter { it.deviceName != "None" && it.deviceName !in experimentalIdentityOnly }
            .forEach { device ->
                val fingerprint = device.props["FINGERPRINT"]
                assertNotNull(
                    "Device '${device.deviceName}' should have a FINGERPRINT",
                    fingerprint
                )
                val parts = fingerprint!!.split("/")
                assertTrue(
                    "Device '${device.deviceName}' FINGERPRINT should have at least 3 parts separated by '/'",
                    parts.size >= 3
                )
                // First part should be "google"
                assertEquals("google", parts[0])
            }
    }

    @Test
    fun `experimental identity-only devices omit fingerprint keys`() {
        experimentalIdentityOnly.forEach { name ->
            val device = DeviceProps.getDeviceProps(name)
            assertNotNull(device)
            assertFalse(device!!.props.containsKey("FINGERPRINT"))
            assertFalse(device.props.containsKey("ID"))
            assertFalse(device.props.containsKey("INCREMENTAL"))
            assertFalse(device.props.containsKey("SECURITY_PATCH"))
        }
    }

    @Test
    fun `device MODEL matches deviceName for non-experimental devices`() {
        DeviceProps.allDevices
            .filter { it.deviceName != "None" && it.deviceName !in experimentalIdentityOnly }
            .forEach { device ->
                assertEquals(
                    "Device '${device.deviceName}' MODEL should match deviceName",
                    device.deviceName,
                    device.props["MODEL"]
                )
            }
    }

    @Test
    fun `experimental deviceName has suffix while MODEL stays bare marketing name`() {
        val fold = DeviceProps.getDeviceProps("Pixel 10 Pro Fold (experimental)")
        assertNotNull(fold)
        assertTrue(fold!!.deviceName.endsWith("(experimental)"))
        assertEquals("Pixel 10 Pro Fold", fold.props["MODEL"])

        val tenA = DeviceProps.getDeviceProps("Pixel 10a (experimental)")
        assertNotNull(tenA)
        assertTrue(tenA!!.deviceName.endsWith("(experimental)"))
        assertEquals("Pixel 10a", tenA.props["MODEL"])
    }

    @Test
    fun `bare experimental marketing names are not deviceName keys`() {
        assertNull(DeviceProps.getDeviceProps("Pixel 10 Pro Fold"))
        assertNull(DeviceProps.getDeviceProps("Pixel 10a"))
    }
}
