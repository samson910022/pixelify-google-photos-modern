package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedOptionsChangePolicyTest {
    @Test
    fun `verbose-only edit does not mark Android version settings`() {
        val before = AdvancedOptionsSelection(
            verboseLogging = false,
            androidVersionMode = AndroidVersionMode.NONE,
            manualAndroidVersion = null,
        )
        val after = before.copy(verboseLogging = true)

        assertEquals(
            setOf(Constants.PREF_ENABLE_VERBOSE_LOGS),
            AdvancedOptionsChangePolicy.changedKeys(before, after),
        )
    }

    @Test
    fun `manual and follow-device choices coordinate both Android version keys`() {
        val none = AdvancedOptionsSelection(false, AndroidVersionMode.NONE, null)
        val manual = AdvancedOptionsSelection(false, AndroidVersionMode.MANUAL, "Android 14")
        val follow = AdvancedOptionsSelection(false, AndroidVersionMode.FOLLOW_DEVICE, null)
        val expected = setOf(
            Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE,
            Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL,
        )

        assertEquals(expected, AdvancedOptionsChangePolicy.changedKeys(none, manual))
        assertEquals(expected, AdvancedOptionsChangePolicy.changedKeys(manual, follow))
        assertEquals(
            expected,
            AdvancedOptionsChangePolicy.changedKeys(
                manual,
                manual.copy(manualAndroidVersion = "Android 15"),
            ),
        )
    }

    @Test
    fun `no-op save has no edited keys`() {
        val selection = AdvancedOptionsSelection(
            verboseLogging = true,
            androidVersionMode = AndroidVersionMode.FOLLOW_DEVICE,
            manualAndroidVersion = "Android 15",
        )

        assertEquals(emptySet<String>(), AdvancedOptionsChangePolicy.changedKeys(selection, selection.copy()))
    }
}
