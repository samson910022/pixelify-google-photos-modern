package io.github.samson910022.pixelifyphotos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigExportRequestGateTest {
    private data class MemoryFile(
        var liveValue: String? = null,
        var durableValue: String? = liveValue,
    )

    private class MemoryPersistence(
        private val file: MemoryFile,
        var commitSucceeds: Boolean = true,
        /** Android updates the in-process SharedPreferences map even if disk commit fails. */
        var commitFailureUpdatesLiveMap: Boolean = false,
        /** A failed commit may independently leave durable storage unchanged or updated. */
        var commitFailureUpdatesDisk: Boolean = false,
    ) : ConfigExportRequestPersistence {
        var readFails = false
        var onFailedCommit: (() -> Unit)? = null

        override fun read(): String? {
            check(!readFails) { "simulated unavailable preferences read" }
            return file.liveValue
        }

        override fun commit(value: String?): Boolean {
            if (!commitSucceeds) {
                if (commitFailureUpdatesLiveMap) file.liveValue = value
                if (commitFailureUpdatesDisk) file.durableValue = value
                onFailedCommit?.invoke()
                return false
            }
            file.liveValue = value
            file.durableValue = value
            return true
        }
    }

    private val source = ConfigExportSourceStamp(
        processEpoch = "process-one",
        serviceVersion = 12L,
        servicePhase = ServicePhase.READY,
        connected = true,
    )

    /** Deterministic wall clock for the cross-process pending-record TTL. */
    private class TestClock(var now: Long = 1_000L) {
        fun read(): Long = now
    }

    private fun request(
        id: String,
        source: ConfigExportSourceStamp = this.source,
        setting: String = id,
        createdAtMillis: Long = 1_000L,
    ) =
        ConfigExportPendingRequest(
            id = id,
            source = source,
            json = JSONObject().put(Constants.PREF_DEVICE_TO_SPOOF, setting).toString(),
            createdAtMillis = createdAtMillis,
        )

    @Test
    fun `request and allowlisted snapshot survive same-process activity recreation beyond the cross-process TTL`() {
        val file = MemoryFile()
        val clock = TestClock()
        val originalActivity = ConfigExportRequestGate(MemoryPersistence(file), clock::read)
        val saved = request("request-one", setting = "Original snapshot")

        assertTrue(originalActivity.begin(saved))

        // A recreated Activity gets a new gate instance over the same private durable record.
        // Same-process recreation must never age out a picker that is still open.
        clock.now += ConfigExportRequestGate.CROSS_PROCESS_PENDING_TTL_MILLIS * 2
        val recreatedActivity = ConfigExportRequestGate(MemoryPersistence(file), clock::read)
        assertEquals(saved, recreatedActivity.restorePending("process-one"))
        assertTrue(recreatedActivity.hasPending())
        val callbackRequest = recreatedActivity.consume("request-one")
        assertEquals("Original snapshot", JSONObject(callbackRequest!!.json)
            .getString(Constants.PREF_DEVICE_TO_SPOOF))
        assertTrue(ConfigExportRequestPolicy.mayWrite(callbackRequest, "request-one", source, true))
        assertFalse(recreatedActivity.hasPending())
    }

    @Test
    fun `cross-process restore re-registers the key and re-authorizes the persisted allowlisted snapshot`() {
        val file = MemoryFile()
        val clock = TestClock()
        val oldProcess = ConfigExportRequestGate(MemoryPersistence(file), clock::read)
        val saved = request("cross-process-request", setting = "Original snapshot")
        assertTrue(oldProcess.begin(saved))

        // The system document picker outlives the old process. Just under the TTL the new
        // process must restore the same key so the in-flight result can be delivered.
        clock.now += ConfigExportRequestGate.CROSS_PROCESS_PENDING_TTL_MILLIS - 1
        val newProcessSource = source.copy(
            processEpoch = "process-two",
            serviceVersion = source.serviceVersion + 7L,
        )
        val newProcess = ConfigExportRequestGate(MemoryPersistence(file), clock::read)
        assertEquals(saved, newProcess.restorePending(newProcessSource.processEpoch))
        assertTrue(newProcess.hasPending())
        assertTrue(newProcess.hasAwaitingResult())

        val callbackRequest = newProcess.consume(saved.id)
        assertEquals(saved, callbackRequest)
        assertFalse(newProcess.hasPending())

        // The old source stamp is dead, so only current authorization plus the allowlist decides.
        assertTrue(ConfigExportRequestPolicy.mayWrite(
            callbackRequest!!,
            saved.id,
            newProcessSource,
            sourceStillAuthorized = true,
        ))
        assertFalse("current settings access is re-checked", ConfigExportRequestPolicy.mayWrite(
            callbackRequest,
            saved.id,
            newProcessSource,
            sourceStillAuthorized = false,
        ))
        assertFalse("the callback ID must still match exactly", ConfigExportRequestPolicy.mayWrite(
            callbackRequest,
            "different-callback-id",
            newProcessSource,
            sourceStillAuthorized = true,
        ))
        assertEquals("Original snapshot", JSONObject(callbackRequest.json)
            .getString(Constants.PREF_DEVICE_TO_SPOOF))
    }

    @Test
    fun `stale cross-process record older than the TTL is cleared so a new export can begin`() {
        val file = MemoryFile()
        val clock = TestClock()
        val oldProcess = ConfigExportRequestGate(MemoryPersistence(file), clock::read)
        assertTrue(oldProcess.begin(request("stale-cross-process")))

        clock.now += ConfigExportRequestGate.CROSS_PROCESS_PENDING_TTL_MILLIS + 1
        val newProcess = ConfigExportRequestGate(MemoryPersistence(file), clock::read)
        assertNull(newProcess.restorePending("process-two"))
        assertFalse("a bounded stale record must not block exports forever", newProcess.hasPending())
        assertFalse(newProcess.hasAwaitingResult())

        val newProcessSource = source.copy(processEpoch = "process-two")
        val next = request("after-stale-record", newProcessSource, createdAtMillis = clock.now)
        assertTrue(newProcess.begin(next))
        assertEquals(next, newProcess.consume(next.id))
    }

    @Test
    fun `fresh task without saved registry state discards a pending request instead of orphaning export`() {
        val file = MemoryFile()
        val clock = TestClock()
        val oldProcess = ConfigExportRequestGate(MemoryPersistence(file), clock::read)
        assertTrue(oldProcess.begin(request("no-result-channel")))

        clock.now += 100L
        val newProcess = ConfigExportRequestGate(MemoryPersistence(file), clock::read)
        assertNull("a fresh task cannot route a restored picker result", newProcess.restorePending(
            currentProcessEpoch = "process-two",
            canReceiveResult = false,
        ))
        assertFalse(newProcess.hasPending())
        assertTrue("discarding the orphan must unblock the next export", newProcess.begin(
            request(
                "fresh-task-export",
                source.copy(processEpoch = "process-two"),
                createdAtMillis = clock.now,
            ),
        ))
    }

    @Test
    fun `reconcile clears an expired cross-process record without waiting for Activity recreation`() {
        val file = MemoryFile()
        val clock = TestClock()
        val gate = ConfigExportRequestGate(MemoryPersistence(file), clock::read)
        assertTrue(gate.begin(request("long-lived-activity-record")))

        clock.now += ConfigExportRequestGate.CROSS_PROCESS_PENDING_TTL_MILLIS
        gate.reconcile("process-two")

        assertFalse(gate.hasPending())
        assertTrue(gate.begin(request(
            "after-reconcile",
            source.copy(processEpoch = "process-two"),
            createdAtMillis = clock.now,
        )))
    }

    @Test
    fun `future creation timestamp cannot keep a cross-process record alive indefinitely`() {
        val file = MemoryFile()
        val clock = TestClock()
        val gate = ConfigExportRequestGate(MemoryPersistence(file), clock::read)
        assertTrue(gate.begin(request("future-timestamp", createdAtMillis = clock.now + 60_000L)))

        assertNull(gate.restorePending("process-two"))
        assertFalse(gate.hasPending())
    }

    @Test
    fun `old callback cannot consume or clear a newer request after a cross-process restore`() {
        val file = MemoryFile()
        val clock = TestClock()
        val oldProcess = ConfigExportRequestGate(MemoryPersistence(file), clock::read)
        val restoredRequest = request("restored-old-request", setting = "old JSON")
        assertTrue(oldProcess.begin(restoredRequest))

        clock.now += 100L
        val newProcessSource = source.copy(processEpoch = "process-two")
        val newProcess = ConfigExportRequestGate(MemoryPersistence(file), clock::read)
        assertEquals(restoredRequest, newProcess.restorePending(newProcessSource.processEpoch))
        assertEquals(restoredRequest, newProcess.consume(restoredRequest.id))

        val newer = request(
            "newer-after-cross-process-restore",
            newProcessSource,
            setting = "new JSON",
            createdAtMillis = clock.now,
        )
        assertTrue(newProcess.begin(newer))
        assertFalse("a stale callback cannot clear a newer request", newProcess.clear(restoredRequest.id))
        assertNull("a stale callback cannot consume a newer request", newProcess.consume(restoredRequest.id))
        assertEquals(newer, newProcess.pending())

        val newerCallback = newProcess.consume(newer.id)!!
        assertFalse("URI and JSON for the newer request cannot pair with a stale callback ID",
            ConfigExportRequestPolicy.mayWrite(
                newerCallback,
                restoredRequest.id,
                newProcessSource,
                sourceStillAuthorized = true,
            ))
        assertEquals("new JSON", JSONObject(newerCallback.json)
            .getString(Constants.PREF_DEVICE_TO_SPOOF))
    }

    @Test
    fun `corrupt pending record is removed without restoring an unusable callback key`() {
        val file = MemoryFile("{malformed")
        val gate = ConfigExportRequestGate(MemoryPersistence(file))

        assertTrue(gate.hasPending())
        assertTrue(gate.hasAwaitingResult())
        assertNull(gate.restorePending("process-one"))
        assertFalse(gate.hasPending())
        assertFalse(gate.hasAwaitingResult())
        assertTrue(gate.begin(request("after-corruption")))
    }

    @Test
    fun `legacy persistence format is cleared instead of restoring an unbounded request`() {
        val file = MemoryFile(JSONObject().put("format", 1).toString())
        val gate = ConfigExportRequestGate(MemoryPersistence(file))

        assertTrue(gate.hasPending())
        assertNull(gate.restorePending("process-one"))
        assertFalse(gate.hasPending())
        assertTrue(gate.begin(request("after-legacy-format")))
    }

    @Test
    fun `old callback cannot consume or clear a newer request`() {
        val file = MemoryFile()
        val gate = ConfigExportRequestGate(MemoryPersistence(file))
        val old = request("old-request", setting = "old JSON")
        val newer = request("new-request", setting = "new JSON")

        assertTrue(gate.begin(old))
        assertFalse(gate.begin(newer))
        assertEquals(old, gate.consume(old.id))
        assertTrue(gate.begin(newer))

        assertNull(gate.consume(old.id))
        assertEquals(newer, gate.pending())
        assertEquals(newer, gate.consume(newer.id))
    }

    @Test
    fun `failed removal commit consumes captured request when live map was cleared and releases restart`() {
        val file = MemoryFile()
        val persistence = MemoryPersistence(file)
        val gate = ConfigExportRequestGate(persistence)
        val saved = request("commit-false-live-removal")
        assertTrue(gate.begin(saved))
        val durableRequest = file.durableValue

        val restart = DeferredActivityRestartGate()
        assertFalse(restart.requestRestart(configExportPending = gate.hasPending()))
        persistence.commitSucceeds = false
        persistence.commitFailureUpdatesLiveMap = true
        persistence.commitFailureUpdatesDisk = false

        // The callback receives its captured request despite commit(false), because the live map
        // confirms removal. A restart sees only the old durable record, which is restorable only
        // within the bounded cross-process TTL and must re-authorize its allowlisted snapshot.
        val consumed = gate.consume(saved.id)
        assertEquals(saved, consumed)
        assertNull(gate.pending())
        assertFalse(gate.hasPending())
        assertEquals(
            "old on-disk record remains until cleanup or the bounded TTL",
            durableRequest,
            file.durableValue,
        )
        assertTrue(restart.completeRequest(saved.id, consumed?.id, configExportPending = gate.hasPending()))
    }

    @Test
    fun `failed removal commit quarantines delivered ID without allowing a save or restoring its key`() {
        val file = MemoryFile()
        val persistence = MemoryPersistence(file)
        val gate = ConfigExportRequestGate(persistence)
        val saved = request("commit-false-record-remains-delivered")
        assertTrue(gate.begin(saved))

        val restart = DeferredActivityRestartGate()
        assertFalse(restart.requestRestart(configExportPending = gate.hasPending()))
        persistence.commitSucceeds = false
        persistence.commitFailureUpdatesLiveMap = false

        val consumed = gate.consume(saved.id)
        assertNull(consumed)
        assertEquals(saved, gate.pending())
        assertTrue(gate.hasPending())
        assertFalse(gate.hasAwaitingResult())
        assertTrue(gate.wasCallbackDelivered(saved.id))
        assertNull("a duplicate one-shot callback cannot consume quarantined JSON", gate.consume(saved.id))
        assertTrue(gate.hasPending())
        assertFalse("a stranded durable record still blocks another export", gate.begin(request("blocked-save")))
        assertTrue(restart.completeRequest(saved.id, saved.id, configExportPending = gate.hasAwaitingResult()))

        // Activity recreation shares the process-local delivered marker and retries only the exact
        // old ID's cleanup; it must not restore a dead ActivityResult key while that record remains.
        val recreatedPersistence = MemoryPersistence(file, commitSucceeds = false)
        val recreatedGate = ConfigExportRequestGate(recreatedPersistence)
        assertNull(recreatedGate.restorePending("process-one"))
        assertTrue(recreatedGate.hasPending())
        assertFalse(recreatedGate.hasAwaitingResult())

        // A successful later cleanup removes the quarantine and allows the next export.
        recreatedPersistence.commitSucceeds = true
        assertNull(recreatedGate.restorePending("process-one"))
        assertFalse(recreatedGate.hasPending())
        assertTrue(recreatedGate.begin(request("save-after-cleanup")))
    }

    @Test
    fun `abandoned old ID cleanup never clears or consumes a newer request`() {
        val file = MemoryFile()
        val persistence = MemoryPersistence(file)
        val gate = ConfigExportRequestGate(persistence)
        val old = request("abandoned-old-id")
        val newer = request("newer-after-abandonment")
        assertTrue(gate.begin(old))

        persistence.commitSucceeds = false
        assertNull(gate.consume(old.id))
        assertFalse(gate.hasAwaitingResult())

        // Model a replacement appearing before old-ID cleanup/recreation.
        val replacementFile = MemoryFile()
        assertTrue(ConfigExportRequestGate(MemoryPersistence(replacementFile)).begin(newer))
        file.liveValue = replacementFile.liveValue
        file.durableValue = replacementFile.durableValue

        assertFalse(gate.clear(old.id))
        assertNull(gate.consume(old.id))
        val recreatedGate = ConfigExportRequestGate(MemoryPersistence(file))
        assertEquals(newer, recreatedGate.restorePending("process-one"))
        assertEquals(newer, recreatedGate.pending())
        assertTrue(recreatedGate.hasPending())
        assertTrue(recreatedGate.hasAwaitingResult())
        assertEquals(newer, recreatedGate.consume(newer.id))
    }

    @Test
    fun `synchronous cleanup failure abandons only exact still-pending request`() {
        val file = MemoryFile()
        val persistence = MemoryPersistence(file)
        val gate = ConfigExportRequestGate(persistence)
        val saved = request("launch-failure-removal-stuck")
        assertTrue(gate.begin(saved))
        persistence.commitSucceeds = false

        assertFalse(gate.clear(saved.id))
        assertTrue(gate.hasPending())
        assertFalse(gate.hasAwaitingResult())
        val recreatedPersistence = MemoryPersistence(file, commitSucceeds = false)
        assertNull(ConfigExportRequestGate(recreatedPersistence).restorePending("process-one"))
        assertTrue(ConfigExportRequestGate(MemoryPersistence(file, commitSucceeds = false)).hasPending())

        // A late result after the launcher path abandoned this request cannot regain write authority.
        persistence.commitSucceeds = true
        assertNull(gate.consume(saved.id))
        assertFalse(gate.hasPending())
    }

    @Test
    fun `failed removal commit returns captured request without consuming a different live request`() {
        val file = MemoryFile()
        val persistence = MemoryPersistence(file)
        val gate = ConfigExportRequestGate(persistence)
        val saved = request("captured-request")
        assertTrue(gate.begin(saved))

        val replacementFile = MemoryFile()
        val replacement = request("replacement-request", setting = "replacement JSON")
        assertTrue(ConfigExportRequestGate(MemoryPersistence(replacementFile)).begin(replacement))
        persistence.commitSucceeds = false
        persistence.onFailedCommit = { file.liveValue = replacementFile.liveValue }

        val consumed = gate.consume(saved.id)
        assertEquals(saved, consumed)
        assertEquals(replacement, gate.pending())
        val restart = DeferredActivityRestartGate()
        assertFalse(restart.requestRestart(configExportPending = gate.hasPending()))
        assertFalse(restart.completeRequest(saved.id, consumed?.id, configExportPending = gate.hasPending()))
    }

    @Test
    fun `failed removal commit with unavailable readback fails closed`() {
        val file = MemoryFile()
        val persistence = MemoryPersistence(file)
        val gate = ConfigExportRequestGate(persistence)
        val saved = request("unavailable-readback")
        assertTrue(gate.begin(saved))
        val pendingRecord = file.liveValue
        val restart = DeferredActivityRestartGate()
        assertFalse(restart.requestRestart(configExportPending = gate.hasPending()))
        persistence.commitSucceeds = false
        persistence.onFailedCommit = { persistence.readFails = true }

        assertNull(gate.consume(saved.id))
        assertTrue(gate.hasPending())
        assertEquals("the failed removal left its request record intact", pendingRecord, file.liveValue)
        assertTrue("a generic restart query stays fail-closed", gate.hasAwaitingResult())
        assertTrue("the one-shot callback marker survives the failed readback", gate.wasCallbackDelivered(saved.id))
        assertFalse("only this marked callback can treat unreadable storage as complete", gate.hasAwaitingResult(saved.id))
        assertFalse("unreadable storage cannot admit a newer request", gate.begin(request("blocked-while-unreadable")))
        assertFalse("a different callback cannot complete this deferred restart", restart.completeRequest(
            callbackRequestId = "different-callback",
            completedRequestId = "different-callback",
            configExportPending = gate.hasAwaitingResult("different-callback"),
        ))
        val duplicate = gate.consume(saved.id)
        assertNull("a duplicate callback cannot export quarantined JSON", duplicate)
        assertTrue(gate.hasPending())
        assertTrue(restart.completeRequest(
            callbackRequestId = saved.id,
            completedRequestId = saved.id,
            configExportPending = gate.hasAwaitingResult(saved.id),
        ))
    }

    @Test
    fun `matching callback completion remains blocked if readback reveals a different request`() {
        val file = MemoryFile()
        val persistence = MemoryPersistence(file)
        val gate = ConfigExportRequestGate(persistence)
        val saved = request("unavailable-readback-replaced")
        assertTrue(gate.begin(saved))
        persistence.commitSucceeds = false
        persistence.onFailedCommit = { persistence.readFails = true }

        assertNull(gate.consume(saved.id))
        assertTrue(gate.wasCallbackDelivered(saved.id))

        val replacementFile = MemoryFile()
        val newer = request("newer-after-readback", setting = "newer JSON")
        assertTrue(ConfigExportRequestGate(MemoryPersistence(replacementFile)).begin(newer))
        file.liveValue = replacementFile.liveValue
        file.durableValue = replacementFile.durableValue
        persistence.readFails = false

        val restart = DeferredActivityRestartGate()
        assertFalse(restart.requestRestart(configExportPending = true))
        assertTrue("a different request must still count as awaiting", gate.hasAwaitingResult(saved.id))
        assertFalse(restart.completeRequest(
            callbackRequestId = saved.id,
            completedRequestId = saved.id,
            configExportPending = gate.hasAwaitingResult(saved.id),
        ))
        assertEquals(newer, gate.pending())
        assertTrue(gate.hasAwaitingResult())
    }

    @Test
    fun `cancellation and launch failure release only their own request`() {
        val file = MemoryFile()
        val gate = ConfigExportRequestGate(MemoryPersistence(file))

        val cancelled = request("cancelled")
        assertTrue(gate.begin(cancelled))
        assertEquals(cancelled, gate.consume(cancelled.id)) // Cancellation consumes without writing.
        assertFalse(gate.hasPending())

        val launchFailed = request("launch-failed")
        assertTrue(gate.begin(launchFailed))
        assertTrue(gate.clear(launchFailed.id))
        val next = request("next")
        assertTrue(gate.begin(next))

        // A callback arriving after a launch exception cannot consume the later request.
        assertNull(gate.consume(launchFailed.id))
        assertEquals(next, gate.pending())
    }

    @Test
    fun `failed durable commit prevents request from becoming launchable`() {
        val persistence = MemoryPersistence(MemoryFile(), commitSucceeds = false)
        val gate = ConfigExportRequestGate(persistence)

        assertFalse(gate.begin(request("not-durable")))
        assertFalse(gate.hasPending())
    }

    @Test
    fun `pending storage rejects non-allowlisted data`() {
        val gate = ConfigExportRequestGate(MemoryPersistence(MemoryFile()))
        val requestWithSecret = ConfigExportPendingRequest(
            id = "secret-request",
            source = source,
            json = JSONObject().put("password", "must not persist").toString(),
            createdAtMillis = 1_000L,
        )

        assertFalse(gate.begin(requestWithSecret))
        assertFalse(gate.hasPending())
    }

    @Test
    fun `source change and callback id mismatch fail closed without pairing URI and JSON`() {
        val file = MemoryFile()
        val gate = ConfigExportRequestGate(MemoryPersistence(file))
        val first = request("first", setting = "first JSON")
        assertTrue(gate.begin(first))
        val consumed = gate.consume(first.id)!!

        val changedSource = source.copy(serviceVersion = source.serviceVersion + 1L)
        assertFalse(ConfigExportRequestPolicy.mayWrite(
            consumed,
            first.id,
            changedSource,
            sourceStillAuthorized = true,
        ))
        assertFalse("current settings access is re-checked", ConfigExportRequestPolicy.mayWrite(
            consumed,
            first.id,
            source,
            sourceStillAuthorized = false,
        ))
        assertFalse(ConfigExportRequestPolicy.mayWrite(
            consumed,
            "different-callback-id",
            source,
            sourceStillAuthorized = true,
        ))
        assertEquals(
            ConfigExportWriteAuthorization.TRANSIENTLY_UNAUTHORIZED,
            ConfigExportRequestPolicy.evaluateWrite(consumed, first.id, source, false),
        )
        assertEquals(
            ConfigExportWriteAuthorization.STALE_OR_INVALID,
            ConfigExportRequestPolicy.evaluateWrite(consumed, "different-callback-id", source, true),
        )

        val second = request("second", setting = "second JSON")
        assertTrue(gate.begin(second))
        val staleCallbackRequest = gate.consume(first.id)
        assertNull(staleCallbackRequest)
        val matchingRequest = gate.consume(second.id)!!
        assertTrue(ConfigExportRequestPolicy.mayWrite(
            matchingRequest,
            second.id,
            source,
            sourceStillAuthorized = true,
        ))
        assertEquals(
            "second JSON",
            JSONObject(matchingRequest.json).getString(Constants.PREF_DEVICE_TO_SPOOF),
        )
        assertEquals(second.id, matchingRequest.id)
    }
}
