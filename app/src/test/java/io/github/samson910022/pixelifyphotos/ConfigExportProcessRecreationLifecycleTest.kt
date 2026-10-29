package io.github.samson910022.pixelifyphotos

import android.os.Bundle
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * End-to-end unit tests verifying the multi-process SAF export lifecycle:
 * - Deferred exports surviving process death while syncing and writing upon READY.
 * - Prevention of duplicate writes on subsequent recreation with stale saved state via SKIP_COMPLETED.
 * - Resilience across consecutive process deaths while remaining in CONNECTED_SYNCING.
 * - Rejection marking the completion store to prevent zombie revival.
 */
class ConfigExportProcessRecreationLifecycleTest {

    @After
    fun tearDown() {
        ConfigExportDeferredCompletionStore.resetForTests()
    }

    private companion object {
        private const val STATE_CONFIG_EXPORT_REQUEST_ID = "config_export_deferred_request_id"
        private const val STATE_CONFIG_EXPORT_JSON = "config_export_deferred_json"
        private const val STATE_CONFIG_EXPORT_CREATED_AT = "config_export_deferred_created_at"
        private const val STATE_CONFIG_EXPORT_SOURCE_EPOCH = "config_export_deferred_source_epoch"
        private const val STATE_CONFIG_EXPORT_SOURCE_VERSION = "config_export_deferred_source_version"
        private const val STATE_CONFIG_EXPORT_SOURCE_PHASE = "config_export_deferred_source_phase"
        private const val STATE_CONFIG_EXPORT_SOURCE_CONNECTED = "config_export_deferred_source_connected"
        private const val STATE_CONFIG_EXPORT_TARGET = "config_export_deferred_target"

        fun createMockBundle(map: Map<String, Any?>): Bundle {
            val bundle = mock<Bundle>()
            whenever(bundle.getString(any())).thenAnswer { map[it.getArgument<String>(0)] as? String }
            whenever(bundle.getLong(any(), any())).thenAnswer {
                map[it.getArgument<String>(0)] as? Long ?: it.getArgument(1)
            }
            whenever(bundle.getLong(any())).thenAnswer {
                map[it.getArgument<String>(0)] as? Long ?: 0L
            }
            whenever(bundle.getBoolean(any(), any())).thenAnswer {
                map[it.getArgument<String>(0)] as? Boolean ?: it.getArgument(1)
            }
            whenever(bundle.getBoolean(any())).thenAnswer {
                map[it.getArgument<String>(0)] as? Boolean ?: false
            }
            return bundle
        }
    }

    private data class WrittenExport(
        val request: ConfigExportPendingRequest,
        val target: String,
    )

    private data class DeferredExportRecord(
        val request: ConfigExportPendingRequest,
        val target: String,
    )

    /**
     * Simulated activity/process session mirroring ActivityMain's deferred export state machine.
     */
    private class SimulatedProcessSession(
        val processEpoch: String,
        val completionPreferences: MemorySharedPreferences,
        initialServiceState: ServiceReadinessSnapshot,
        initialPreferencesAvailable: Boolean,
        val frameworkBindingObserved: Boolean = true,
    ) {
        val completionStore = ConfigExportDeferredCompletionStore(completionPreferences)
        var currentServiceState = initialServiceState
        var preferencesAvailable = initialPreferencesAvailable

        var deferredExport: DeferredExportRecord? = null
        val writtenExports = mutableListOf<WrittenExport>()
        val rejections = mutableListOf<String>()

        /**
         * Simulates the ActivityResult callback when the SAF document picker returns.
         */
        fun onPickerResult(request: ConfigExportPendingRequest, target: String) {
            val action = ConfigExportCallbackPolicy.decide(
                request = request,
                callbackRequestId = request.id,
                current = currentServiceState,
                processEpoch = processEpoch,
                frameworkBindingObserved = frameworkBindingObserved,
                preferencesAvailable = preferencesAvailable,
                settingsWaitExpired = false,
            )
            when (action) {
                ConfigExportCallbackAction.WRITE -> {
                    completionStore.markCompleted(request.id)
                    writtenExports += WrittenExport(request, target)
                    deferredExport = null
                }
                ConfigExportCallbackAction.WAIT_FOR_SETTINGS -> {
                    deferredExport = DeferredExportRecord(request, target)
                }
                ConfigExportCallbackAction.REJECT -> {
                    completionStore.markCompleted(request.id)
                    rejections += request.id
                    deferredExport = null
                }
            }
        }

        /**
         * Mirrors ActivityMain.onSaveInstanceState(outState: Bundle)
         */
        fun saveInstanceState(): Bundle {
            val deferred = deferredExport ?: return createMockBundle(emptyMap<String, Any?>())
            val map = mapOf(
                STATE_CONFIG_EXPORT_REQUEST_ID to deferred.request.id,
                STATE_CONFIG_EXPORT_JSON to deferred.request.json,
                STATE_CONFIG_EXPORT_CREATED_AT to deferred.request.createdAtMillis,
                STATE_CONFIG_EXPORT_SOURCE_EPOCH to deferred.request.source.processEpoch,
                STATE_CONFIG_EXPORT_SOURCE_VERSION to deferred.request.source.serviceVersion,
                STATE_CONFIG_EXPORT_SOURCE_PHASE to deferred.request.source.servicePhase.name,
                STATE_CONFIG_EXPORT_SOURCE_CONNECTED to deferred.request.source.connected,
                STATE_CONFIG_EXPORT_TARGET to deferred.target,
            )
            return createMockBundle(map)
        }

        /**
         * Mirrors ActivityMain.restoreDeferredConfigExport(savedState: Bundle?)
         */
        fun restoreInstanceState(savedState: Bundle?): ConfigExportDeferredRestore {
            if (savedState == null || deferredExport != null) {
                return ConfigExportDeferredRestore.SKIP_INVALID
            }
            val id = savedState.getString(STATE_CONFIG_EXPORT_REQUEST_ID)
            val json = savedState.getString(STATE_CONFIG_EXPORT_JSON)
            val targetString = savedState.getString(STATE_CONFIG_EXPORT_TARGET)
            val alreadyCompleted = id != null && completionStore.isCompleted(id)
            val decision = ConfigExportDeferredRestorePolicy.decide(
                requestId = id,
                json = json,
                target = targetString,
                alreadyCompleted = alreadyCompleted,
            )
            if (decision != ConfigExportDeferredRestore.RESTORE) {
                return decision
            }
            val sourceEpoch = savedState.getString(STATE_CONFIG_EXPORT_SOURCE_EPOCH)
                ?: return ConfigExportDeferredRestore.SKIP_INVALID
            val sourcePhase = savedState.getString(STATE_CONFIG_EXPORT_SOURCE_PHASE)
                ?.let { runCatching { ServicePhase.valueOf(it) }.getOrNull() }
                ?: return ConfigExportDeferredRestore.SKIP_INVALID
            val request = ConfigExportPendingRequest(
                id = id!!,
                source = ConfigExportSourceStamp(
                    processEpoch = sourceEpoch,
                    serviceVersion = savedState.getLong(STATE_CONFIG_EXPORT_SOURCE_VERSION),
                    servicePhase = sourcePhase,
                    connected = savedState.getBoolean(STATE_CONFIG_EXPORT_SOURCE_CONNECTED),
                ),
                json = json!!,
                createdAtMillis = savedState.getLong(STATE_CONFIG_EXPORT_CREATED_AT),
            )
            deferredExport = DeferredExportRecord(request, targetString!!)
            completeDeferredConfigExport(settingsWaitExpired = false)
            return ConfigExportDeferredRestore.RESTORE
        }

        /**
         * Mirrors ActivityMain.completeDeferredConfigExport(settingsWaitExpired: Boolean)
         */
        fun completeDeferredConfigExport(settingsWaitExpired: Boolean) {
            val deferred = deferredExport ?: return
            val action = ConfigExportCallbackPolicy.decide(
                request = deferred.request,
                callbackRequestId = deferred.request.id,
                current = currentServiceState,
                processEpoch = processEpoch,
                frameworkBindingObserved = frameworkBindingObserved,
                preferencesAvailable = preferencesAvailable,
                settingsWaitExpired = settingsWaitExpired,
            )
            when (action) {
                ConfigExportCallbackAction.WAIT_FOR_SETTINGS -> {
                    // Stays deferred while waiting for settings to settle
                }
                ConfigExportCallbackAction.WRITE -> {
                    completionStore.markCompleted(deferred.request.id)
                    writtenExports += WrittenExport(deferred.request, deferred.target)
                    deferredExport = null
                }
                ConfigExportCallbackAction.REJECT -> {
                    completionStore.markCompleted(deferred.request.id)
                    rejections += deferred.request.id
                    deferredExport = null
                }
            }
        }

        /**
         * Simulates service readiness transition in this process.
         */
        fun updateServiceState(newState: ServiceReadinessSnapshot, newPreferencesAvailable: Boolean) {
            currentServiceState = newState
            preferencesAvailable = newPreferencesAvailable
            completeDeferredConfigExport(settingsWaitExpired = false)
        }
    }

    private val sampleExportJson = JSONObject()
        .put(Constants.PREF_DEVICE_TO_SPOOF, "Pixel 9 Pro")
        .toString()

    @Test
    fun `deferred export survives process death during syncing and performs exactly-once write upon READY`() {
        val service = mock<XposedService>()
        val syncingState = ServiceReadinessSnapshot(service, ServicePhase.CONNECTED_SYNCING, 10L)
        val readyState = ServiceReadinessSnapshot(service, ServicePhase.READY, 11L)

        val request = ConfigExportPendingRequest(
            id = "req-saf-survives-death",
            source = ConfigExportSourceStamp.from(syncingState, "epoch-1"),
            json = sampleExportJson,
            createdAtMillis = 1000L,
        )
        val targetUri = "content://downloads/document/1"

        // Process 1: receives SAF picker result while in CONNECTED_SYNCING.
        val proc1Prefs = MemorySharedPreferences()
        val proc1 = SimulatedProcessSession(
            processEpoch = "epoch-1",
            completionPreferences = proc1Prefs,
            initialServiceState = syncingState,
            initialPreferencesAvailable = false,
        )
        proc1.onPickerResult(request, targetUri)

        assertNotNull("Export must be deferred while syncing", proc1.deferredExport)
        assertEquals(0, proc1.writtenExports.size)
        assertFalse(proc1.completionStore.isCompleted(request.id))

        // Process 1 dies: state is saved to bundle, durable preferences survive on disk
        val savedBundle = proc1.saveInstanceState()
        val diskAfterProc1 = proc1Prefs.persistedAll

        // Process 2: starts in CONNECTED_SYNCING with a new process epoch
        val proc2Prefs = MemorySharedPreferences(initialValues = diskAfterProc1)
        val proc2 = SimulatedProcessSession(
            processEpoch = "epoch-2",
            completionPreferences = proc2Prefs,
            initialServiceState = syncingState,
            initialPreferencesAvailable = false,
        )

        // Process 2 restores from saved state
        val restoreDecision = proc2.restoreInstanceState(savedBundle)
        assertEquals(ConfigExportDeferredRestore.RESTORE, restoreDecision)
        assertNotNull("Deferred export should be re-armed in process 2", proc2.deferredExport)
        assertEquals(0, proc2.writtenExports.size)

        // Process 2 service transitions to READY
        proc2.updateServiceState(readyState, newPreferencesAvailable = true)

        // Export must be written exactly once upon READY
        assertEquals(1, proc2.writtenExports.size)
        assertEquals("req-saf-survives-death", proc2.writtenExports.first().request.id)
        assertEquals(targetUri, proc2.writtenExports.first().target)
        assertNull(proc2.deferredExport)
        assertTrue("Completion must be persisted durably", proc2.completionStore.isCompleted(request.id))
        assertTrue("Durable prefs must contain completed request", proc2Prefs.persistedAll.isNotEmpty())
    }

    @Test
    fun `subsequent recreation with old saved state correctly skips via SKIP_COMPLETED`() {
        val service = mock<XposedService>()
        val syncingState = ServiceReadinessSnapshot(service, ServicePhase.CONNECTED_SYNCING, 10L)
        val readyState = ServiceReadinessSnapshot(service, ServicePhase.READY, 11L)

        val request = ConfigExportPendingRequest(
            id = "req-saf-skip-completed",
            source = ConfigExportSourceStamp.from(syncingState, "epoch-1"),
            json = sampleExportJson,
            createdAtMillis = 1000L,
        )
        val targetUri = "content://downloads/document/2"

        // Process 1 defers and saves state
        val proc1Prefs = MemorySharedPreferences()
        val proc1 = SimulatedProcessSession(
            processEpoch = "epoch-1",
            completionPreferences = proc1Prefs,
            initialServiceState = syncingState,
            initialPreferencesAvailable = false,
        )
        proc1.onPickerResult(request, targetUri)
        val oldSavedBundleFromProc1 = proc1.saveInstanceState()

        // Process 2 recreates, transitions to READY, and completes the write
        val proc2Prefs = MemorySharedPreferences(initialValues = proc1Prefs.persistedAll)
        val proc2 = SimulatedProcessSession(
            processEpoch = "epoch-2",
            completionPreferences = proc2Prefs,
            initialServiceState = syncingState,
            initialPreferencesAvailable = false,
        )
        proc2.restoreInstanceState(oldSavedBundleFromProc1)
        proc2.updateServiceState(readyState, newPreferencesAvailable = true)
        assertEquals(1, proc2.writtenExports.size)
        assertTrue(proc2.completionStore.isCompleted(request.id))

        // Process 3 is recreated with old saved state from before Process 2 wrote the file
        val proc3Prefs = MemorySharedPreferences(initialValues = proc2Prefs.persistedAll)
        val proc3 = SimulatedProcessSession(
            processEpoch = "epoch-3",
            completionPreferences = proc3Prefs,
            initialServiceState = readyState,
            initialPreferencesAvailable = true,
        )
        val proc3Decision = proc3.restoreInstanceState(oldSavedBundleFromProc1)

        assertEquals(
            "Old saved state must be skipped since it was already completed",
            ConfigExportDeferredRestore.SKIP_COMPLETED,
            proc3Decision,
        )
        assertNull(proc3.deferredExport)
        assertEquals(0, proc3.writtenExports.size)
    }

    @Test
    fun `deferred export survives consecutive process deaths while remaining in CONNECTED_SYNCING`() {
        val service = mock<XposedService>()
        val syncingState = ServiceReadinessSnapshot(service, ServicePhase.CONNECTED_SYNCING, 10L)
        val readyState = ServiceReadinessSnapshot(service, ServicePhase.READY, 11L)

        val request = ConfigExportPendingRequest(
            id = "req-saf-consecutive-deaths",
            source = ConfigExportSourceStamp.from(syncingState, "epoch-1"),
            json = sampleExportJson,
            createdAtMillis = 1000L,
        )
        val targetUri = "content://downloads/document/3"

        // Process 1: defers export and dies
        val proc1Prefs = MemorySharedPreferences()
        val proc1 = SimulatedProcessSession(
            processEpoch = "epoch-1",
            completionPreferences = proc1Prefs,
            initialServiceState = syncingState,
            initialPreferencesAvailable = false,
        )
        proc1.onPickerResult(request, targetUri)
        val savedBundle1 = proc1.saveInstanceState()

        // Process 2: starts in CONNECTED_SYNCING, restores, remains syncing, and dies again without completing
        val proc2Prefs = MemorySharedPreferences(initialValues = proc1Prefs.persistedAll)
        val proc2 = SimulatedProcessSession(
            processEpoch = "epoch-2",
            completionPreferences = proc2Prefs,
            initialServiceState = syncingState,
            initialPreferencesAvailable = false,
        )
        val proc2Restore = proc2.restoreInstanceState(savedBundle1)
        assertEquals(ConfigExportDeferredRestore.RESTORE, proc2Restore)
        assertNotNull(proc2.deferredExport)
        assertEquals(0, proc2.writtenExports.size)
        assertFalse(proc2.completionStore.isCompleted(request.id))

        // Process 2 dies while still syncing and saves its state
        val savedBundle2 = proc2.saveInstanceState()

        // Process 3: starts in CONNECTED_SYNCING with new epoch-3
        val proc3Prefs = MemorySharedPreferences(initialValues = proc2Prefs.persistedAll)
        val proc3 = SimulatedProcessSession(
            processEpoch = "epoch-3",
            completionPreferences = proc3Prefs,
            initialServiceState = syncingState,
            initialPreferencesAvailable = false,
        )
        val proc3Restore = proc3.restoreInstanceState(savedBundle2)
        assertEquals(ConfigExportDeferredRestore.RESTORE, proc3Restore)
        assertNotNull(proc3.deferredExport)
        assertEquals(0, proc3.writtenExports.size)

        // Process 3 service state transitions to READY
        proc3.updateServiceState(readyState, newPreferencesAvailable = true)

        // Export performs exactly-once write
        assertEquals(1, proc3.writtenExports.size)
        assertEquals("req-saf-consecutive-deaths", proc3.writtenExports.first().request.id)
        assertNull(proc3.deferredExport)
        assertTrue(proc3.completionStore.isCompleted(request.id))
    }

    @Test
    fun `rejection marks completion store to prevent zombie revival`() {
        val service = mock<XposedService>()
        val syncingState = ServiceReadinessSnapshot(service, ServicePhase.CONNECTED_SYNCING, 10L)
        val readyState = ServiceReadinessSnapshot(service, ServicePhase.READY, 11L)

        val request = ConfigExportPendingRequest(
            id = "req-saf-rejection-no-zombie",
            source = ConfigExportSourceStamp.from(syncingState, "epoch-1"),
            json = sampleExportJson,
            createdAtMillis = 1000L,
        )
        val targetUri = "content://downloads/document/4"

        // Process 1: defers export
        val proc1Prefs = MemorySharedPreferences()
        val proc1 = SimulatedProcessSession(
            processEpoch = "epoch-1",
            completionPreferences = proc1Prefs,
            initialServiceState = syncingState,
            initialPreferencesAvailable = false,
        )
        proc1.onPickerResult(request, targetUri)
        val staleSavedBundle = proc1.saveInstanceState()

        // Wait budget expires while still syncing in Process 1
        proc1.completeDeferredConfigExport(settingsWaitExpired = true)

        assertNull("Deferred export must be cleared on rejection", proc1.deferredExport)
        assertEquals(0, proc1.writtenExports.size)
        assertEquals(1, proc1.rejections.size)
        assertEquals("req-saf-rejection-no-zombie", proc1.rejections.first())
        assertTrue(
            "Rejection must mark completion store so the request is never revived",
            proc1.completionStore.isCompleted(request.id),
        )

        // Process 2: starts with stale saved state from before the rejection
        val proc2Prefs = MemorySharedPreferences(initialValues = proc1Prefs.persistedAll)
        val proc2 = SimulatedProcessSession(
            processEpoch = "epoch-2",
            completionPreferences = proc2Prefs,
            initialServiceState = readyState,
            initialPreferencesAvailable = true,
        )

        val restoreDecision = proc2.restoreInstanceState(staleSavedBundle)

        assertEquals(
            "Zombie revival must be prevented by marking rejection in the completion store",
            ConfigExportDeferredRestore.SKIP_COMPLETED,
            restoreDecision,
        )
        assertNull(proc2.deferredExport)
        assertEquals(0, proc2.writtenExports.size)
    }

    @Test
    fun `rejection due to unrecoverable ready state without preferences marks completion store`() {
        val service = mock<XposedService>()
        val syncingState = ServiceReadinessSnapshot(service, ServicePhase.CONNECTED_SYNCING, 10L)
        val readyState = ServiceReadinessSnapshot(service, ServicePhase.READY, 11L)

        val request = ConfigExportPendingRequest(
            id = "req-saf-unrecoverable-ready",
            source = ConfigExportSourceStamp.from(syncingState, "epoch-1"),
            json = sampleExportJson,
            createdAtMillis = 1000L,
        )
        val targetUri = "content://downloads/document/5"

        // Process 1 defers export while syncing
        val proc1Prefs = MemorySharedPreferences()
        val proc1 = SimulatedProcessSession(
            processEpoch = "epoch-1",
            completionPreferences = proc1Prefs,
            initialServiceState = syncingState,
            initialPreferencesAvailable = false,
        )
        proc1.onPickerResult(request, targetUri)
        val staleSavedBundle = proc1.saveInstanceState()

        // State becomes READY but preferences are permanently unavailable (settled unrecoverable failure)
        proc1.updateServiceState(readyState, newPreferencesAvailable = false)

        assertNull(proc1.deferredExport)
        assertEquals(0, proc1.writtenExports.size)
        assertEquals(1, proc1.rejections.size)
        assertTrue(proc1.completionStore.isCompleted(request.id))

        // Subsequent process with old saved state must not revive the export
        val proc2Prefs = MemorySharedPreferences(initialValues = proc1Prefs.persistedAll)
        val proc2 = SimulatedProcessSession(
            processEpoch = "epoch-2",
            completionPreferences = proc2Prefs,
            initialServiceState = readyState,
            initialPreferencesAvailable = true,
        )
        val decision = proc2.restoreInstanceState(staleSavedBundle)
        assertEquals(ConfigExportDeferredRestore.SKIP_COMPLETED, decision)
        assertNull(proc2.deferredExport)
        assertEquals(0, proc2.writtenExports.size)
    }
}
