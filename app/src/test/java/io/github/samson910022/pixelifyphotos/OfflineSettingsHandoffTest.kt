package io.github.samson910022.pixelifyphotos

import android.content.Context
import io.github.libxposed.service.XposedService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class OfflineSettingsHandoffTest {
    @Test
    fun `theme precondition falls back to dynamic colors when recovery journal is unresolved`() {
        val local = MemorySharedPreferences(mapOf(Constants.PREF_USE_CLASSIC_UI to false))
        val pending = MemorySharedPreferences()
        assertTrue(OfflineSettingsHandoff.prepareLocalRecovery(local, OfflineSettingsHandoff.snapshot(local)))
        local.edit().putBoolean(Constants.PREF_USE_CLASSIC_UI, true).commit()
        // The marker still claims the theme was present, but its recovery value is corrupt/missing.
        local.edit().remove("offline_recovery_value:${Constants.PREF_USE_CLASSIC_UI}").commit()

        assertTrue("failed recovery selects the safe non-classic theme", AppThemePolicy.shouldApplyDynamicColors(local, pending))
        assertTrue("the ghost local value is not used as the theme decision", local.getBoolean(Constants.PREF_USE_CLASSIC_UI, false))
    }

    @Test
    fun `theme precondition uses the value restored by successful recovery`() {
        val local = MemorySharedPreferences(mapOf(Constants.PREF_USE_CLASSIC_UI to true))
        val pending = MemorySharedPreferences()
        assertTrue(OfflineSettingsHandoff.prepareLocalRecovery(local, OfflineSettingsHandoff.snapshot(local)))
        local.edit().putBoolean(Constants.PREF_USE_CLASSIC_UI, false).commit()

        assertFalse("restored classic theme disables dynamic colors", AppThemePolicy.shouldApplyDynamicColors(local, pending))
        assertTrue(local.getBoolean(Constants.PREF_USE_CLASSIC_UI, false))
        assertFalse(local.contains("offline_recovery_active"))
    }

    @Test
    fun `first bind applies offline-edited keys and preserves unrelated remote settings`() {
        val before = mapOf(
            Constants.PREF_DEVICE_TO_SPOOF to "Pixel 3",
            Constants.PREF_SPOOF_FEATURES_LIST to setOf("old feature"),
            Constants.PREF_DIAG_BROADCAST_TOKEN to "private-local-token",
        )
        val local = MemorySharedPreferences(
            mapOf(
                Constants.PREF_DEVICE_TO_SPOOF to "Pixel 5",
                Constants.PREF_SPOOF_FEATURES_LIST to setOf("edited feature"),
                Constants.PREF_DIAG_BROADCAST_TOKEN to "private-local-token",
            ),
        )
        val pending = MemorySharedPreferences()
        assertTrue(OfflineSettingsHandoff.recordChanges(pending, before, local))
        assertFalse(pending.contains(Constants.PREF_DIAG_BROADCAST_TOKEN))

        val remote = MemorySharedPreferences(
            mapOf(
                Constants.PREF_DEVICE_TO_SPOOF to "Pixel XL",
                Constants.PREF_SPOOF_FEATURES_LIST to setOf("remote feature"),
                Constants.PREF_ENABLE_VERBOSE_LOGS to true,
                "unrelated_remote_setting" to "keep remote value",
                Constants.PREF_DIAG_BROADCAST_TOKEN to "private-remote-token",
            ),
        )
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remote)
        val context = mock<Context>()
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(pending)

        assertTrue(OfflineSettingsHandoff.converge(context, service))

        assertEquals("Pixel 5", remote.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertEquals(setOf("edited feature"), remote.getStringSet(Constants.PREF_SPOOF_FEATURES_LIST, null))
        assertTrue(remote.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false))
        assertEquals("keep remote value", remote.getString("unrelated_remote_setting", null))
        assertEquals("private-remote-token", remote.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null))
        assertTrue(pending.all.isEmpty())
    }

    @Test
    fun `failed remote commit retains pending intent for convergence retry`() {
        val local = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel 5"))
        val pending = MemorySharedPreferences()
        assertTrue(
            OfflineSettingsHandoff.recordChanges(
                pending,
                mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel 3"),
                local,
            ),
        )
        val remote = MemorySharedPreferences(commitResult = false)
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remote)
        val context = mock<Context>()
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(pending)

        assertFalse(OfflineSettingsHandoff.converge(context, service))
        assertTrue(pending.contains(Constants.PREF_DEVICE_TO_SPOOF))

        remote.commitResult = true
        assertTrue(OfflineSettingsHandoff.converge(context, service))
        assertEquals("Pixel 5", remote.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertTrue(pending.all.isEmpty())
    }

    @Test
    fun `explicit offline edit is retained even when it matches the existing local value`() {
        val local = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel 5"))
        val pending = MemorySharedPreferences()
        assertTrue(
            OfflineSettingsHandoff.recordChanges(
                pending,
                OfflineSettingsHandoff.snapshot(local),
                local,
                explicitlyEditedKeys = setOf(Constants.PREF_DEVICE_TO_SPOOF),
            ),
        )
        val remote = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel XL"))
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remote)
        val context = mock<Context>()
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(pending)

        assertTrue(OfflineSettingsHandoff.converge(context, service))
        assertEquals("Pixel 5", remote.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
    }

    @Test
    fun `Advanced Options verbose-only offline edit preserves differing remote fields on first bind`() {
        val local = MemorySharedPreferences(
            mapOf(
                Constants.PREF_ENABLE_VERBOSE_LOGS to false,
                Constants.PREF_DEVICE_TO_SPOOF to "Pixel 5",
                Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE to false,
                Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL to "Android 13",
            ),
        )
        val before = OfflineSettingsHandoff.snapshot(local)
        val initial = AdvancedOptionsSelection(
            verboseLogging = false,
            androidVersionMode = AndroidVersionMode.MANUAL,
            manualAndroidVersion = "Android 13",
        )
        val changed = initial.copy(verboseLogging = true)
        val editedKeys = AdvancedOptionsChangePolicy.changedKeys(initial, changed)
        assertEquals(setOf(Constants.PREF_ENABLE_VERBOSE_LOGS), editedKeys)
        local.edit().putBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, changed.verboseLogging).apply()

        val pending = MemorySharedPreferences()
        assertTrue(OfflineSettingsHandoff.recordChanges(pending, before, local, editedKeys))
        assertEquals(setOf(Constants.PREF_ENABLE_VERBOSE_LOGS), pending.getStringSet("pending_keys", null))

        val remote = MemorySharedPreferences(
            mapOf(
                Constants.PREF_ENABLE_VERBOSE_LOGS to false,
                Constants.PREF_DEVICE_TO_SPOOF to "Pixel XL",
                Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE to true,
            ),
        )
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remote)
        val context = mock<Context>()
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(pending)

        assertTrue(OfflineSettingsHandoff.converge(context, service))

        assertTrue(remote.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false))
        assertEquals("Pixel XL", remote.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertTrue(remote.getBoolean(Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, false))
        assertFalse(remote.contains(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL))
        assertTrue(pending.all.isEmpty())
    }

    @Test
    fun `no-op Advanced Options save creates no offline handoff intent`() {
        val local = MemorySharedPreferences(mapOf(Constants.PREF_ENABLE_VERBOSE_LOGS to true))
        val before = OfflineSettingsHandoff.snapshot(local)
        val selection = AdvancedOptionsSelection(true, AndroidVersionMode.NONE, null)
        val editedKeys = AdvancedOptionsChangePolicy.changedKeys(selection, selection)
        val pending = MemorySharedPreferences()

        assertTrue(OfflineSettingsHandoff.recordChanges(pending, before, local, editedKeys))
        assertTrue(pending.all.isEmpty())
    }

    @Test
    fun `unchanged Feature Customize save with an unrendered flag performs no write and preserves remote list`() {
        val recognizedFeature = DeviceProps.allFeatures.first().displayName
        val unrenderedFeature = "future imported feature flag"
        val selectedLocally = setOf(recognizedFeature, unrenderedFeature)
        val selectedRemotely = setOf("remote feature")
        val local = MemorySharedPreferences(
            mapOf(Constants.PREF_SPOOF_FEATURES_LIST to selectedLocally),
        )
        val stored = local.getStringSet(
            Constants.PREF_SPOOF_FEATURES_LIST,
            mutableSetOf(),
        ).orEmpty().toSet()
        val renderedNames = DeviceProps.allFeatures.map { it.displayName }.toSet()
        val initialRenderedSelection = FeatureCustomizeChangePolicy.renderedSelection(stored, renderedNames)
        val unchangedRenderedSelection = setOf(recognizedFeature)
        val pending = MemorySharedPreferences()

        val editedKeys = FeatureCustomizeChangePolicy.changedKeys(
            initialRenderedSelection,
            unchangedRenderedSelection,
        )
        assertTrue(editedKeys.isEmpty())
        val localEditCountBeforeSave = local.editCount
        if (editedKeys.isNotEmpty()) {
            val toPersist = FeatureCustomizeChangePolicy.preserveUnrendered(
                stored,
                renderedNames,
                unchangedRenderedSelection,
            )
            local.edit().putStringSet(Constants.PREF_SPOOF_FEATURES_LIST, toPersist).apply()
        }
        assertEquals("An unchanged rendered selection must not write local preferences",
            localEditCountBeforeSave, local.editCount)
        assertEquals(selectedLocally,
            local.getStringSet(Constants.PREF_SPOOF_FEATURES_LIST, mutableSetOf()).orEmpty().toSet())

        // The activity no-op path skips the guarded write; the empty explicit key
        // set also ensures the handoff layer cannot infer user intent from this save.
        assertTrue(OfflineSettingsHandoff.recordChanges(
            pending,
            OfflineSettingsHandoff.snapshot(local),
            local,
            explicitlyEditedKeys = editedKeys,
        ))
        assertTrue(pending.all.isEmpty())

        val remote = MemorySharedPreferences(
            mapOf(Constants.PREF_SPOOF_FEATURES_LIST to selectedRemotely),
        )
        val remoteEditCountBeforeConverge = remote.editCount
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remote)
        val context = mock<Context>()
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(pending)

        assertTrue(OfflineSettingsHandoff.converge(context, service))
        assertEquals(selectedRemotely, remote.getStringSet(Constants.PREF_SPOOF_FEATURES_LIST, null))
        assertEquals("No-op saves must not write the remote preference store",
            remoteEditCountBeforeConverge, remote.editCount)
        assertTrue(pending.all.isEmpty())
    }

    @Test
    fun `changing a rendered feature preserves unrendered stored flags through offline handoff`() {
        val featureA = DeviceProps.allFeatures[0].displayName
        val featureB = DeviceProps.allFeatures[1].displayName
        val unrenderedFeature = "future imported feature flag"
        val stored = setOf(featureA, unrenderedFeature)
        val renderedNames = DeviceProps.allFeatures.map { it.displayName }.toSet()
        val local = MemorySharedPreferences(mapOf(Constants.PREF_SPOOF_FEATURES_LIST to stored))
        val before = OfflineSettingsHandoff.snapshot(local)
        val initialRenderedSelection = FeatureCustomizeChangePolicy.renderedSelection(stored, renderedNames)
        val selectedRendered = setOf(featureB)
        val editedKeys = FeatureCustomizeChangePolicy.changedKeys(initialRenderedSelection, selectedRendered)
        assertEquals(setOf(Constants.PREF_SPOOF_FEATURES_LIST), editedKeys)

        val toPersist = FeatureCustomizeChangePolicy.preserveUnrendered(
            stored,
            renderedNames,
            selectedRendered,
        )
        assertEquals(setOf(featureB, unrenderedFeature), toPersist)
        local.edit().putStringSet(Constants.PREF_SPOOF_FEATURES_LIST, toPersist).apply()

        val pending = MemorySharedPreferences()
        assertTrue(OfflineSettingsHandoff.recordChanges(pending, before, local, editedKeys))
        assertEquals(setOf(Constants.PREF_SPOOF_FEATURES_LIST), pending.getStringSet("pending_keys", null))

        val remote = MemorySharedPreferences(mapOf(Constants.PREF_SPOOF_FEATURES_LIST to setOf("remote feature")))
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remote)
        val context = mock<Context>()
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(pending)

        assertTrue(OfflineSettingsHandoff.converge(context, service))
        assertEquals(setOf(featureB, unrenderedFeature),
            local.getStringSet(Constants.PREF_SPOOF_FEATURES_LIST, null))
        assertEquals(setOf(featureB, unrenderedFeature),
            remote.getStringSet(Constants.PREF_SPOOF_FEATURES_LIST, null))
        assertTrue(pending.all.isEmpty())
    }

    @Test
    fun `changed Feature Customize selection marks only feature list for handoff`() {
        val initial = setOf("feature A")
        val selected = setOf("feature B")
        assertEquals(
            setOf(Constants.PREF_SPOOF_FEATURES_LIST),
            FeatureCustomizeChangePolicy.changedKeys(initial, selected),
        )
    }

    @Test
    fun `unknown pending keys are retained and fail closed`() {
        val pending = MemorySharedPreferences(
            mapOf(
                "pending_keys" to setOf("future_setting_key"),
                "future_setting_key" to "do not discard",
            ),
        )
        val remote = MemorySharedPreferences(mapOf("unrelated_remote_setting" to "keep"))
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remote)
        val context = mock<Context>()
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(pending)

        assertFalse(OfflineSettingsHandoff.converge(context, service))

        assertEquals(setOf("future_setting_key"), pending.getStringSet("pending_keys", null))
        assertEquals("do not discard", pending.getString("future_setting_key", null))
        assertEquals("keep", remote.getString("unrelated_remote_setting", null))
    }

    @Test
    fun `known pending keys commit but unknown future keys remain pending`() {
        val pending = MemorySharedPreferences(
            mapOf(
                "pending_keys" to setOf(Constants.PREF_DEVICE_TO_SPOOF, "future_setting_key"),
                Constants.PREF_DEVICE_TO_SPOOF to "Pixel 5",
                "future_setting_key" to "retain for newer version",
            ),
        )
        val remote = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel XL"))
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remote)
        val context = mock<Context>()
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(pending)

        assertFalse(OfflineSettingsHandoff.converge(context, service))

        assertEquals("Pixel 5", remote.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertEquals(setOf("future_setting_key"), pending.getStringSet("pending_keys", null))
        assertEquals("retain for newer version", pending.getString("future_setting_key", null))
    }

    @Test
    fun `diagnostics and private keys are excluded from the migration allowlist`() {
        assertFalse(OfflineSettingsHandoff.isUserSettingKey(Constants.PREF_DIAG_BROADCAST_TOKEN))
        assertFalse(OfflineSettingsHandoff.isUserSettingKey(Constants.PREF_DIAG_ACTIVE_RUN_ID))
        assertTrue(OfflineSettingsHandoff.isUserSettingKey(Constants.PREF_DEVICE_TO_SPOOF))
    }
}
