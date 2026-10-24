package io.github.samson910022.pixelifyphotos

import io.github.libxposed.service.XposedService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/** Exercises the lifecycle decisions that ActivityMain uses on create, bind, and death. */
class ActivityMainLifecycleTest {
    private val service = mock<XposedService>()

    @Test
    fun `offline cold start initializes local UI then a later bind selects remote UI`() {
        val disconnected = ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, 0L)
        val syncing = ServiceReadinessSnapshot(service, ServicePhase.CONNECTED_SYNCING, 1L)
        val ready = ServiceReadinessSnapshot(service, ServicePhase.READY, 2L)

        assertEquals(
            ActivityMainLifecycle.Action.INITIALIZE_LOCAL_AND_WAIT,
            ActivityMainLifecycle.onCreate(disconnected, frameworkBindingObserved = false),
        )
        assertEquals(
            ActivityMainLifecycle.Action.WAIT_FOR_CONNECTION,
            ActivityMainLifecycle.onServiceStateChanged(syncing, frameworkBindingObserved = true),
        )
        assertEquals(
            ActivityMainLifecycle.Action.INITIALIZE_REMOTE,
            ActivityMainLifecycle.onServiceStateChanged(ready, frameworkBindingObserved = true),
        )
    }

    @Test
    fun `recreated activity and resolver timeout after known death remain in reconnect state`() {
        val disconnectedAfterDeath = ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, 3L)

        assertEquals(
            ActivityMainLifecycle.Action.WAIT_FOR_REBIND,
            ActivityMainLifecycle.onCreate(disconnectedAfterDeath, frameworkBindingObserved = true),
        )
        assertEquals(
            ActivityMainLifecycle.Action.WAIT_FOR_REBIND,
            ActivityMainLifecycle.onResolverResolved(
                enabled = false,
                state = disconnectedAfterDeath,
                frameworkBindingObserved = true,
            ),
        )
    }

    @Test
    fun `cold offline resolver timeout does not replace usable local settings with disabled dialog`() {
        val disconnected = ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, 0L)

        assertEquals(
            ActivityMainLifecycle.Action.INITIALIZE_LOCAL_AND_WAIT,
            ActivityMainLifecycle.onCreate(disconnected, frameworkBindingObserved = false),
        )
        assertEquals(
            ActivityMainLifecycle.Action.NO_CHANGE,
            ActivityMainLifecycle.onResolverResolved(
                enabled = false,
                state = disconnected,
                frameworkBindingObserved = false,
            ),
        )
    }

    @Test
    fun `advanced and feature editors reject offline values after the first bind before refreshing`() {
        val offline = ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, 0L)
        val ready = ServiceReadinessSnapshot(service, ServicePhase.READY, 2L)

        listOf("AdvancedOptionsActivity", "FeatureCustomize").forEach { screen ->
            assertTrue("$screen loads from offline source", MainSettingsReadinessPolicy.mayAccessPreferences(offline))
            assertFalse("$screen cannot save stale offline values into remote store", SettingsScreenSourcePolicy.maySave(
                capturedVersion = offline.version,
                current = ready,
                frameworkBindingObserved = true,
            ))
            assertTrue("$screen must reload from current remote source", SettingsScreenSourcePolicy.mustReload(
                offline.version,
                ready,
            ))
        }
    }

    @Test
    fun `ready child editor cannot save across disconnect and rebind until it reloads`() {
        val firstReady = ServiceReadinessSnapshot(service, ServicePhase.READY, 5L)
        val disconnected = ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, 6L)
        val reboundReady = ServiceReadinessSnapshot(service, ServicePhase.READY, 8L)

        listOf("AdvancedOptionsActivity", "FeatureCustomize").forEach { screen ->
            assertFalse("$screen cannot save while disconnected", SettingsScreenSourcePolicy.maySave(
                firstReady.version,
                disconnected,
                frameworkBindingObserved = true,
            ))
            assertTrue("$screen notices disconnect source version", SettingsScreenSourcePolicy.mustReload(
                firstReady.version,
                disconnected,
            ))
            assertFalse("$screen cannot save old ready values after rebind", SettingsScreenSourcePolicy.maySave(
                firstReady.version,
                reboundReady,
                frameworkBindingObserved = true,
            ))
            assertTrue("$screen may save only after recreating against rebind", SettingsScreenSourcePolicy.maySave(
                reboundReady.version,
                reboundReady,
                frameworkBindingObserved = true,
            ))
        }
    }

    @Test
    fun `configuration export allows readable cold offline prefs but rejects prior-bound disconnect`() {
        val coldOffline = ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, 0L)
        val disconnectedAfterBind = ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, 3L)
        val syncing = ServiceReadinessSnapshot(service, ServicePhase.CONNECTED_SYNCING, 4L)
        val ready = ServiceReadinessSnapshot(service, ServicePhase.READY, 5L)

        assertTrue(ConfigExportPolicy.mayExport(coldOffline, frameworkBindingObserved = false, preferencesAvailable = true))
        assertFalse(ConfigExportPolicy.mayExport(coldOffline, frameworkBindingObserved = false, preferencesAvailable = false))
        assertFalse(ConfigExportPolicy.mayExport(disconnectedAfterBind, frameworkBindingObserved = true, preferencesAvailable = true))
        assertFalse(ConfigExportPolicy.mayExport(syncing, frameworkBindingObserved = true, preferencesAvailable = true))
        assertTrue(ConfigExportPolicy.mayExport(ready, frameworkBindingObserved = true, preferencesAvailable = true))
    }

    @Test
    fun `activity export capture rejects empty cold offline config but accepts saved user settings`() {
        val coldOffline = ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, 0L)
        fun capture(preferences: MemorySharedPreferences): CapturedServiceSnapshot<JSONObject?> =
            ServiceSnapshotCapture.capture(
                readServiceState = { coldOffline },
                readForState = { source ->
                    if (!ConfigExportPolicy.mayExport(source, frameworkBindingObserved = false, preferencesAvailable = true)) {
                        null
                    } else {
                        try {
                            ConfigExportPolicy.toJson(preferences)
                        } catch (_: ConfigExportPolicy.EmptyConfigurationException) {
                            null
                        }
                    }
                },
            )

        val emptyCapture = capture(MemorySharedPreferences())
        assertTrue(emptyCapture.consistent)
        assertNull("empty preferences cannot reach share/save success paths", emptyCapture.value)

        val savedCapture = capture(MemorySharedPreferences(
            mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel 5"),
        ))
        assertTrue(savedCapture.consistent)
        val exported = savedCapture.value
        assertNotNull(exported)
        assertTrue(ConfigExportPolicy.hasExportableSettings(checkNotNull(exported)))
        assertEquals("Pixel 5", exported!!.getString(Constants.PREF_DEVICE_TO_SPOOF))
    }

    @Test
    fun `configuration export rejects a source version transition during snapshot read`() {
        val coldOffline = ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, 1L)
        val ready = ServiceReadinessSnapshot(service, ServicePhase.READY, 2L)
        var current = coldOffline

        val captured = ServiceSnapshotCapture.capture(
            maxAttempts = 1,
            readServiceState = { current },
            readForState = { source ->
                ConfigExportPolicy.mayExport(
                    source,
                    frameworkBindingObserved = false,
                    preferencesAvailable = true,
                )
            },
            afterRead = { current = ready },
        )

        assertTrue("the first source may be cold-offline readable", captured.value)
        assertFalse("an export snapshot spanning a bind must not be used", captured.consistent)
    }
}
