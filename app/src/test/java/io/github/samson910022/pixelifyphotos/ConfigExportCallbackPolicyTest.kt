package io.github.samson910022.pixelifyphotos

import io.github.libxposed.service.XposedService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

class ConfigExportCallbackPolicyTest {
    private val service = mock<XposedService>()
    private val currentEpoch = "process-two"

    private fun snapshot(phase: ServicePhase, version: Long = 7L) =
        ServiceReadinessSnapshot(service, phase, version)

    private fun disconnectedSnapshot() =
        ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, 0L)

    private fun request(
        source: ConfigExportSourceStamp,
        json: String = JSONObject()
            .put(Constants.PREF_DEVICE_TO_SPOOF, "Pixel 9")
            .toString(),
    ) = ConfigExportPendingRequest(
        id = "request-1",
        source = source,
        json = json,
        createdAtMillis = 1_000L,
    )

    private fun decide(
        request: ConfigExportPendingRequest,
        current: ServiceReadinessSnapshot,
        callbackRequestId: String = request.id,
        frameworkBindingObserved: Boolean = true,
        preferencesAvailable: Boolean = true,
        settingsWaitExpired: Boolean = false,
    ) = ConfigExportCallbackPolicy.decide(
        request = request,
        callbackRequestId = callbackRequestId,
        current = current,
        processEpoch = currentEpoch,
        frameworkBindingObserved = frameworkBindingObserved,
        preferencesAvailable = preferencesAvailable,
        settingsWaitExpired = settingsWaitExpired,
    )

    @Test
    fun `restored result arriving while syncing waits instead of consuming the export`() {
        val restored = request(
            ConfigExportSourceStamp.from(snapshot(ServicePhase.READY), "process-one"),
        )

        assertEquals(
            ConfigExportCallbackAction.WAIT_FOR_SETTINGS,
            decide(
                restored,
                snapshot(ServicePhase.CONNECTED_SYNCING),
                preferencesAvailable = false,
            ),
        )
    }

    @Test
    fun `restored result during a transient error waits for convergence`() {
        val restored = request(
            ConfigExportSourceStamp.from(snapshot(ServicePhase.READY), "process-one"),
        )

        assertEquals(
            ConfigExportCallbackAction.WAIT_FOR_SETTINGS,
            decide(
                restored,
                snapshot(ServicePhase.ERROR),
                preferencesAvailable = false,
            ),
        )
    }

    @Test
    fun `waiting result is rejected once the wait budget expires`() {
        val restored = request(
            ConfigExportSourceStamp.from(snapshot(ServicePhase.READY), "process-one"),
        )

        assertEquals(
            ConfigExportCallbackAction.REJECT,
            decide(
                restored,
                snapshot(ServicePhase.CONNECTED_SYNCING),
                preferencesAvailable = false,
                settingsWaitExpired = true,
            ),
        )
    }

    @Test
    fun `settled ready source writes a restored request exactly once`() {
        val restored = request(
            ConfigExportSourceStamp.from(snapshot(ServicePhase.READY), "process-one"),
        )

        assertEquals(
            ConfigExportCallbackAction.WRITE,
            decide(restored, snapshot(ServicePhase.READY)),
        )
    }

    @Test
    fun `ready source without a usable store fails closed instead of waiting`() {
        val restored = request(
            ConfigExportSourceStamp.from(snapshot(ServicePhase.READY), "process-one"),
        )

        assertEquals(
            ConfigExportCallbackAction.REJECT,
            decide(
                restored,
                snapshot(ServicePhase.READY),
                preferencesAvailable = false,
            ),
        )
    }

    @Test
    fun `same-process source change stays a permanent rejection`() {
        val request = request(
            ConfigExportSourceStamp.from(snapshot(ServicePhase.READY, version = 7L), currentEpoch),
        )

        assertEquals(
            ConfigExportCallbackAction.REJECT,
            decide(
                request,
                snapshot(ServicePhase.READY, version = 8L),
                settingsWaitExpired = false,
            ),
        )
    }

    @Test
    fun `same-process unchanged source still writes`() {
        val request = request(
            ConfigExportSourceStamp.from(snapshot(ServicePhase.READY), currentEpoch),
        )

        assertEquals(ConfigExportCallbackAction.WRITE, decide(request, snapshot(ServicePhase.READY)))
    }

    @Test
    fun `same-process offline capture remains writable before any binding is observed`() {
        val request = request(
            ConfigExportSourceStamp.from(disconnectedSnapshot(), currentEpoch),
        )

        assertEquals(
            ConfigExportCallbackAction.WRITE,
            decide(
                request,
                disconnectedSnapshot(),
                frameworkBindingObserved = false,
            ),
        )
    }

    @Test
    fun `offline capture with a temporarily unreadable store waits rather than discarding`() {
        val request = request(
            ConfigExportSourceStamp.from(disconnectedSnapshot(), currentEpoch),
        )

        assertEquals(
            ConfigExportCallbackAction.WAIT_FOR_SETTINGS,
            decide(
                request,
                disconnectedSnapshot(),
                frameworkBindingObserved = false,
                preferencesAvailable = false,
            ),
        )
    }

    @Test
    fun `wait budget expiry does not block a settled authorized write`() {
        val restored = request(
            ConfigExportSourceStamp.from(snapshot(ServicePhase.READY), "process-one"),
        )

        assertEquals(
            ConfigExportCallbackAction.WRITE,
            decide(
                restored,
                snapshot(ServicePhase.READY),
                settingsWaitExpired = true,
            ),
        )
    }

    @Test
    fun `disconnected after a bind waits for a rebind and rejects only when expired`() {
        val restored = request(
            ConfigExportSourceStamp.from(snapshot(ServicePhase.READY), "process-one"),
        )

        assertEquals(
            ConfigExportCallbackAction.WAIT_FOR_SETTINGS,
            decide(
                restored,
                disconnectedSnapshot(),
                preferencesAvailable = false,
                frameworkBindingObserved = true,
            ),
        )
        assertEquals(
            ConfigExportCallbackAction.REJECT,
            decide(
                restored,
                disconnectedSnapshot(),
                preferencesAvailable = false,
                frameworkBindingObserved = true,
                settingsWaitExpired = true,
            ),
        )
    }

    @Test
    fun `same-process wait cannot be repaired by a newer service version`() {
        val waitingRequest = request(
            ConfigExportSourceStamp.from(
                snapshot(ServicePhase.CONNECTED_SYNCING, version = 7L),
                currentEpoch,
            ),
        )

        assertEquals(
            ConfigExportCallbackAction.WAIT_FOR_SETTINGS,
            decide(
                waitingRequest,
                snapshot(ServicePhase.CONNECTED_SYNCING, version = 7L),
                preferencesAvailable = false,
            ),
        )
        assertEquals(
            ConfigExportCallbackAction.REJECT,
            decide(
                waitingRequest,
                snapshot(ServicePhase.CONNECTED_SYNCING, version = 8L),
                preferencesAvailable = false,
            ),
        )
    }

    @Test
    fun `callback id mismatch and non-allowlisted payload are rejected`() {
        val request = request(
            ConfigExportSourceStamp.from(snapshot(ServicePhase.READY), currentEpoch),
        )

        assertEquals(
            ConfigExportCallbackAction.REJECT,
            decide(request, snapshot(ServicePhase.READY), callbackRequestId = "other"),
        )
        assertEquals(
            ConfigExportCallbackAction.REJECT,
            decide(
                request(
                    ConfigExportSourceStamp.from(snapshot(ServicePhase.READY), currentEpoch),
                    json = JSONObject().put("internal_runtime_key", "secret").toString(),
                ),
                snapshot(ServicePhase.READY),
            ),
        )
    }
}
