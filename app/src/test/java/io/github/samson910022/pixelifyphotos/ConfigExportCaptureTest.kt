package io.github.samson910022.pixelifyphotos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigExportCaptureTest {
    private val offline = ServiceReadinessSnapshot(null, ServicePhase.DISCONNECTED, 1L)

    @Test
    fun `empty first attempt then unavailable retry reports unavailable`() {
        val ready = ServiceReadinessSnapshot(mockService(), ServicePhase.READY, 2L)
        var source = offline
        var attempts = 0

        val result = ConfigExportCapturePolicy.capture<JSONObject>(
            readServiceState = { source },
            readForState = {
                attempts += 1
                if (attempts == 1) {
                    ConfigExportAttempt.failure<JSONObject>(ConfigExportFailureReason.EMPTY)
                } else {
                    ConfigExportAttempt.failure<JSONObject>(ConfigExportFailureReason.UNAVAILABLE)
                }
            },
            afterRead = { if (attempts == 1) source = ready },
        )

        assertEquals(2, attempts)
        assertEquals(ConfigExportFailureReason.UNAVAILABLE, result.failureReason)
        assertNull(result.value)
    }

    @Test
    fun `stable empty source reports empty`() {
        val result = ConfigExportCapturePolicy.capture<JSONObject>(
            readServiceState = { offline },
            readForState = { ConfigExportAttempt.failure<JSONObject>(ConfigExportFailureReason.EMPTY) },
        )

        assertEquals(ConfigExportFailureReason.EMPTY, result.failureReason)
        assertNull(result.value)
    }

    @Test
    fun `consistent export returns value without failure reason`() {
        val json = JSONObject().put(Constants.PREF_DEVICE_TO_SPOOF, "Pixel 5")
        val result = ConfigExportCapturePolicy.capture<JSONObject>(
            readServiceState = { offline },
            readForState = { ConfigExportAttempt.success(json) },
        )

        assertNull(result.failureReason)
        assertNotNull(result.value)
        assertTrue(ConfigExportPolicy.hasExportableSettings(checkNotNull(result.value)))
    }

    private fun mockService() = org.mockito.kotlin.mock<io.github.libxposed.service.XposedService>()
}
