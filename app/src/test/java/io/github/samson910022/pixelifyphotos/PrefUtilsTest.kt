package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PrefUtilsTest {
    private val context = mock<Context>()

    @After
    fun tearDown() {
        App.setServiceStateForTests(null, ready = false)
    }

    @Test
    fun `connected syncing or error state never falls back to local preferences`() {
        val service = mock<XposedService>()
        whenever(context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE))
            .thenReturn(mock())

        listOf(ServicePhase.CONNECTED_SYNCING, ServicePhase.ERROR).forEach { phase ->
            App.setServiceStateForTests(service, ready = false, phase = phase)
            assertNull(PrefUtils.getPrefs(context))
        }

        verify(context, never()).getSharedPreferences(
            Constants.SHARED_PREF_FILE_NAME,
            Context.MODE_PRIVATE,
        )
    }

    @Test
    fun `disconnected state keeps local preferences as the offline source`() {
        val local = mock<SharedPreferences>()
        whenever(context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE))
            .thenReturn(local)
        App.setServiceStateForTests(null, ready = false)

        assertSame(local, PrefUtils.getPrefs(context))
    }

    @Test
    fun `cold local profile settings do not make hook telemetry readable or empty`() {
        val local = MemorySharedPreferences(
            mapOf(
                Constants.PREF_DEVICE_TO_SPOOF to "Pixel XL",
                Constants.PREF_DIAG_MODULE_LOADED_AT to 123L,
            ),
        )
        whenever(context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE))
            .thenReturn(local)
        App.setServiceStateForTests(null, ready = false)

        val sources = PrefUtils.diagnosticsSources(context, App.serviceReadinessSnapshot())
        val state = DiagnosticsHookStatePolicy.capture(
            profileSettingsAvailable = sources.profileSettings != null,
            telemetryPreferencesAvailable = sources.telemetry != null,
            readTelemetry = { error("remote telemetry must not be read while disconnected") },
        )

        assertSame(local, sources.profileSettings)
        assertNull(sources.telemetry)
        assertEquals("Pixel XL", sources.profileSettings?.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertTrue(state.profileSettingsAvailable)
        assertFalse(state.diagnosticsAvailable)
        assertFalse(state.anyRecorded)
        assertEquals(
            DiagnosticsCollector.MilestoneKind.DIAGNOSTICS_UNAVAILABLE,
            DiagnosticsCollector.milestoneSignals(state).single().kind,
        )
        val report = DiagnosticsCollector.formatReportText(
            state = state,
            serviceConnected = false,
            serviceStatus = "disconnected",
            scope = emptyList(),
            selectedDevice = "Pixel XL",
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("Profile settings: readable"))
        assertTrue(report.contains("Diagnostics data: UNAVAILABLE"))
        assertTrue(report.contains("Scope: none"))
        assertFalse(report.contains("No lifecycle milestones have been recorded yet"))
    }

    @Test
    fun `disconnect after a prior binding never falls back to stale local preferences`() {
        val service = mock<XposedService>()
        whenever(context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE))
            .thenReturn(mock())
        val app = App()
        app.testTokenConvergenceOverride = { true }
        app.onServiceBind(service)
        app.onServiceDied(service)

        assertNull(PrefUtils.getPrefs(context))
        verify(context, never()).getSharedPreferences(
            Constants.SHARED_PREF_FILE_NAME,
            Context.MODE_PRIVATE,
        )
    }

    @Test
    fun `ready state returns remote preferences`() {
        val service = mock<XposedService>()
        val remote = mock<SharedPreferences>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remote)
        App.setServiceStateForTests(service, ready = true)

        assertSame(remote, PrefUtils.getPrefs(context))
        verify(context, never()).getSharedPreferences(
            Constants.SHARED_PREF_FILE_NAME,
            Context.MODE_PRIVATE,
        )
    }
}
