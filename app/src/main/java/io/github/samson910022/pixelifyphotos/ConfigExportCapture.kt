package io.github.samson910022.pixelifyphotos

/** Why one stable configuration-export capture did not produce an export. */
internal enum class ConfigExportFailureReason {
    EMPTY,
    UNAVAILABLE,
    INCONSISTENT,
}

/** Value and classification produced together by one service-state read attempt. */
internal data class ConfigExportAttempt<T>(
    val value: T?,
    val failureReason: ConfigExportFailureReason?,
) {
    init {
        require((value == null) == (failureReason != null))
    }

    companion object {
        fun <T> success(value: T): ConfigExportAttempt<T> = ConfigExportAttempt(value, null)

        fun <T> failure(reason: ConfigExportFailureReason): ConfigExportAttempt<T> =
            ConfigExportAttempt(null, reason)
    }
}

/** Final service-consistent export result; retry attempts never share mutable classification state. */
internal data class ConfigExportCaptureResult<T>(
    val serviceState: ServiceReadinessSnapshot,
    val value: T?,
    val failureReason: ConfigExportFailureReason?,
) {
    init {
        require((value == null) == (failureReason != null))
    }
}

internal object ConfigExportCapturePolicy {
    fun <T> capture(
        maxAttempts: Int = 3,
        readServiceState: () -> ServiceReadinessSnapshot,
        readForState: (ServiceReadinessSnapshot) -> ConfigExportAttempt<T>,
        afterRead: () -> Unit = {},
    ): ConfigExportCaptureResult<T> {
        val captured = ServiceSnapshotCapture.capture(
            maxAttempts = maxAttempts,
            readServiceState = readServiceState,
            readForState = readForState,
            afterRead = afterRead,
        )
        if (!captured.consistent) {
            return ConfigExportCaptureResult(
                captured.serviceState,
                null,
                ConfigExportFailureReason.INCONSISTENT,
            )
        }
        return ConfigExportCaptureResult(
            captured.serviceState,
            captured.value.value,
            captured.value.failureReason,
        )
    }
}
