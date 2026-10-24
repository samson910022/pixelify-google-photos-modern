package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.libxposed.service.XposedService
import org.mockito.kotlin.mock

class DiagnosticsSnapshotPresentationTest {
    @Test
    fun `scope labels distinguish unknown from explicitly empty in screen and report`() {
        assertEquals("unknown", DiagnosticsScopePresentation.label(null))
        assertEquals("none", DiagnosticsScopePresentation.label(emptyList()))
        assertEquals("なし", DiagnosticsScopePresentation.label(emptyList(), "不明", "なし"))

        val report = DiagnosticsCollector.formatReportText(
            state = DiagnosticsCollector.HookState(),
            serviceConnected = false,
            scope = emptyList(),
            selectedDevice = null,
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("Scope: none"))
        assertFalse(report.contains("Scope: unknown"))
    }

    @Test
    fun `inconsistent capture is marked loading rather than rendered as current`() {
        assertFalse(DiagnosticsSnapshotPresentation.mayRenderCapturedData(consistent = false))
        assertTrue(DiagnosticsSnapshotPresentation.mayRenderCapturedData(consistent = true))
        assertFalse("copy policy remains blocked for inconsistent captures", DiagnosticsCopyPolicy.mayCopy(false))
    }

    @Test
    fun `unavailable preferences do not claim default profile was selected`() {
        val disconnected = DiagnosticsProfilePolicy.fromPreferences(
            preferencesAvailable = false,
            savedDeviceName = null,
            defaultDeviceName = "Pixel XL",
        )
        val transientReadFailure = DiagnosticsProfilePolicy.fromPreferenceRead(
            preferencesAvailable = false,
            readSavedDeviceName = { "Pixel 6" },
            defaultDeviceName = "Pixel XL",
        )

        assertNull(disconnected.deviceName)
        assertNull(transientReadFailure.deviceName)
        assertFalse(disconnected.isFallback)
        assertFalse(transientReadFailure.isFallback)
        assertFalse(disconnected.settingsReadable)
        assertFalse(transientReadFailure.settingsReadable)
    }

    @Test
    fun `transient preference read exception is unavailable not the default profile`() {
        val profile = DiagnosticsProfilePolicy.fromPreferenceRead(
            preferencesAvailable = true,
            readSavedDeviceName = { error("remote read failed") },
            defaultDeviceName = "Pixel XL",
        )

        assertNull(profile.deviceName)
        assertFalse(profile.isFallback)
        assertFalse(profile.settingsReadable)
        val report = DiagnosticsCollector.formatReportText(
            state = DiagnosticsCollector.HookState(profileSettingsAvailable = profile.settingsReadable),
            serviceConnected = false,
            scope = null,
            selectedDevice = profile.deviceName,
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("Profile settings: UNAVAILABLE"))
        assertTrue(report.contains("Selected profile: unavailable (settings could not be read)"))
        assertFalse(report.contains("Profile settings: readable"))
    }

    @Test
    fun `available preferences without saved selection label the implementation fallback`() {
        val profile = DiagnosticsProfilePolicy.fromPreferences(
            preferencesAvailable = true,
            savedDeviceName = null,
            defaultDeviceName = "Pixel XL",
        )

        assertEquals("Pixel XL", profile.deviceName)
        assertTrue(profile.isFallback)
        assertTrue(profile.settingsReadable)
    }

    @Test
    fun `successful null profile read uses readable default while unavailable source is unreadable`() {
        val defaultProfile = DiagnosticsProfilePolicy.fromPreferenceRead(
            preferencesAvailable = true,
            readSavedDeviceName = { null },
            defaultDeviceName = "Pixel XL",
        )
        var unavailableSourceRead = false
        val unavailableProfile = DiagnosticsProfilePolicy.fromPreferenceRead(
            preferencesAvailable = false,
            readSavedDeviceName = {
                unavailableSourceRead = true
                null
            },
            defaultDeviceName = "Pixel XL",
        )

        assertEquals("Pixel XL", defaultProfile.deviceName)
        assertTrue(defaultProfile.isFallback)
        assertTrue(defaultProfile.settingsReadable)
        assertNull(unavailableProfile.deviceName)
        assertFalse(unavailableProfile.settingsReadable)
        assertFalse(unavailableSourceRead)
    }

    @Test
    fun `explicit saved profile is not mislabeled as a fallback`() {
        val profile = DiagnosticsProfilePolicy.fromPreferences(
            preferencesAvailable = true,
            savedDeviceName = "Pixel 5",
            defaultDeviceName = "Pixel XL",
        )

        assertEquals("Pixel 5", profile.deviceName)
        assertFalse(profile.isFallback)
    }

    @Test
    fun `copy report labels fallback and unavailable profiles without claiming user selection`() {
        val fallbackReport = DiagnosticsCollector.formatReportText(
            state = DiagnosticsCollector.HookState(),
            serviceConnected = false,
            scope = null,
            selectedDevice = "Pixel XL",
            real = emptyMap(),
            target = emptyMap(),
            profileIsFallback = true,
        )
        val unavailableReport = DiagnosticsCollector.formatReportText(
            state = DiagnosticsCollector.HookState(),
            serviceConnected = true,
            scope = null,
            selectedDevice = null,
            real = emptyMap(),
            target = emptyMap(),
        )

        assertTrue(fallbackReport.contains("Default profile (no saved user selection): Pixel XL"))
        assertFalse(fallbackReport.contains("Selected profile: Pixel XL"))
        assertTrue(unavailableReport.contains("Selected profile: unavailable (settings could not be read)"))
        assertTrue(unavailableReport.contains("Entitlement tier: Unavailable (settings could not be read)"))
    }

    @Test
    fun `unreadable diagnostics differ from a readable empty telemetry store in model and report`() {
        val unavailableState = DiagnosticsCollector.HookState(diagnosticsAvailable = false)
        val emptyReadableState = DiagnosticsCollector.HookState(diagnosticsAvailable = true)

        assertEquals(
            DiagnosticsCollector.MilestoneKind.DIAGNOSTICS_UNAVAILABLE,
            DiagnosticsCollector.milestoneSignals(unavailableState).single().kind,
        )
        assertEquals(
            DiagnosticsCollector.MilestoneKind.NO_RECORD,
            DiagnosticsCollector.milestoneSignals(emptyReadableState).single().kind,
        )

        fun report(state: DiagnosticsCollector.HookState) = DiagnosticsCollector.formatReportText(
            state = state,
            serviceConnected = false,
            serviceStatus = "disconnected",
            scope = null,
            selectedDevice = null,
            real = emptyMap(),
            target = emptyMap(),
        )
        val unavailableReport = report(unavailableState)
        val emptyReport = report(emptyReadableState)
        assertTrue(unavailableReport.contains("Diagnostics data: UNAVAILABLE (preferences could not be read"))
        assertTrue(unavailableReport.contains("Diagnostics data is unavailable because preferences could not be read"))
        assertFalse(unavailableReport.contains("No lifecycle milestones have been recorded yet"))
        assertTrue(unavailableReport.contains("Last VERIFY: unavailable (diagnostics data could not be read)"))
        assertTrue(emptyReport.contains("Diagnostics data: readable"))
        assertTrue(emptyReport.contains("No lifecycle milestones have been recorded yet"))
        assertTrue(emptyReport.contains("Last VERIFY: no record or OS-verified run relationship is available"))
    }

    @Test
    fun `stable error and disconnected captures retain unavailable diagnostics status in copied report`() {
        val service = mock<XposedService>()
        val captures = listOf(
            ServiceReadinessSnapshot(service, ServicePhase.ERROR, 4L) to "connected, sync error",
            ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, 5L) to "disconnected",
        )
        captures.forEach { (serviceState, label) ->
            val captured = ServiceSnapshotCapture.capture(
                readServiceState = { serviceState },
                readForState = { DiagnosticsCollector.HookState(diagnosticsAvailable = false) },
            )
            assertTrue("$label capture should be stable", captured.consistent)
            val report = DiagnosticsCollector.formatReportText(
                state = captured.value,
                serviceConnected = serviceState.connected,
                serviceStatus = label,
                scope = null,
                selectedDevice = null,
                real = emptyMap(),
                target = emptyMap(),
            )
            assertTrue(report.contains("Xposed manager service: $label"))
            assertTrue(report.contains("Diagnostics data: UNAVAILABLE"))
            assertFalse(report.contains("No lifecycle milestones have been recorded yet"))
        }
    }
}
