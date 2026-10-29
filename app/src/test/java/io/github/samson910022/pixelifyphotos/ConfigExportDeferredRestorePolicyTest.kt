package io.github.samson910022.pixelifyphotos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigExportDeferredRestorePolicyTest {
    private val validJson = JSONObject()
        .put(Constants.PREF_DEVICE_TO_SPOOF, "Pixel 9 Pro")
        .toString()

    @Test
    fun `valid saved deferred result is restored`() {
        assertEquals(
            ConfigExportDeferredRestore.RESTORE,
            ConfigExportDeferredRestorePolicy.decide(
                requestId = "request-one",
                json = validJson,
                target = "content://downloads/one",
                alreadyCompleted = false,
            ),
        )
    }

    @Test
    fun `completed result is not written twice`() {
        assertEquals(
            ConfigExportDeferredRestore.SKIP_COMPLETED,
            ConfigExportDeferredRestorePolicy.decide(
                requestId = "request-one",
                json = validJson,
                target = "content://downloads/one",
                alreadyCompleted = true,
            ),
        )
    }

    @Test
    fun `missing or blank saved fields are invalid`() {
        listOf(
            ConfigExportDeferredRestorePolicy.decide(null, validJson, "content://one", false),
            ConfigExportDeferredRestorePolicy.decide("", validJson, "content://one", false),
            ConfigExportDeferredRestorePolicy.decide("request", null, "content://one", false),
            ConfigExportDeferredRestorePolicy.decide("request", validJson, null, false),
            ConfigExportDeferredRestorePolicy.decide("request", validJson, "  ", false),
        ).forEach { assertEquals(ConfigExportDeferredRestore.SKIP_INVALID, it) }
    }

    @Test
    fun `non-allowlisted saved payload is rejected`() {
        val secret = JSONObject().put("password", "must not be written").toString()
        assertEquals(
            ConfigExportDeferredRestore.SKIP_INVALID,
            ConfigExportDeferredRestorePolicy.decide(
                requestId = "request-one",
                json = secret,
                target = "content://downloads/one",
                alreadyCompleted = false,
            ),
        )
    }
}
