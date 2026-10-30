package io.github.samson910022.pixelifyphotos

import io.github.libxposed.service.XposedService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class MainSettingsReadinessPolicyTest {

    private val service = mock<XposedService>()

    @Test
    fun `connected syncing and error states neither initialize nor access local preferences`() {
        listOf(ServicePhase.CONNECTED_SYNCING, ServicePhase.ERROR).forEachIndexed { index, phase ->
            val state = ServiceReadinessSnapshot(service, phase, index.toLong())
            assertFalse(MainSettingsReadinessPolicy.mayInitializeConnectedSettings(state))
            assertFalse(MainSettingsReadinessPolicy.mayAccessPreferences(state))
            assertTrue(MainSettingsReadinessPolicy.needsConnectedWait(state))
        }
    }

    @Test
    fun `ready transition allows initialization and preference-backed actions`() {
        val syncing = ServiceReadinessSnapshot(service, ServicePhase.CONNECTED_SYNCING, 1L)
        val ready = ServiceReadinessSnapshot(service, ServicePhase.READY, 2L)

        assertFalse(MainSettingsReadinessPolicy.mayInitializeConnectedSettings(syncing))
        assertTrue(MainSettingsReadinessPolicy.mayInitializeConnectedSettings(ready))
        assertTrue(MainSettingsReadinessPolicy.mayAccessPreferences(ready))
        assertFalse(MainSettingsReadinessPolicy.needsConnectedWait(ready))
    }

    @Test
    fun `disconnected cold-start keeps local access but a prior binding blocks stale preference access`() {
        val disconnected = ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, 0L)

        assertTrue(MainSettingsReadinessPolicy.mayAccessPreferences(disconnected, frameworkBindingObserved = false))
        assertFalse(MainSettingsReadinessPolicy.mayAccessPreferences(disconnected, frameworkBindingObserved = true))
        assertFalse(MainSettingsReadinessPolicy.needsConnectedWait(disconnected))
    }

    @Test
    fun `ready disconnected ready transition gates access until preferences converge again`() {
        val ready = ServiceReadinessSnapshot(service, ServicePhase.READY, 1L)
        val disconnected = ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, 2L)
        val syncing = ServiceReadinessSnapshot(service, ServicePhase.CONNECTED_SYNCING, 3L)
        val reboundReady = ServiceReadinessSnapshot(service, ServicePhase.READY, 4L)

        assertTrue(MainSettingsReadinessPolicy.mayAccessPreferences(ready, frameworkBindingObserved = true))
        assertFalse(MainSettingsReadinessPolicy.mayAccessPreferences(disconnected, frameworkBindingObserved = true))
        assertFalse(MainSettingsReadinessPolicy.mayAccessPreferences(syncing, frameworkBindingObserved = true))
        assertTrue(MainSettingsReadinessPolicy.mayAccessPreferences(reboundReady, frameworkBindingObserved = true))
        assertTrue(MainSettingsReadinessPolicy.mayRetryConvergence(
            ServiceReadinessSnapshot(service, ServicePhase.ERROR, 5L),
        ))
        assertFalse(MainSettingsReadinessPolicy.mayRetryConvergence(syncing))
    }

}
