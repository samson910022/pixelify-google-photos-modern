package io.github.samson910022.pixelifyphotos

/** No-record VERIFY card classification, kept separate from Build/API identity provenance. */
internal object DiagnosticsVerifyCardPolicy {
    enum class NoRecordStatus {
        PRIOR_BOOT,
        BOOT_UNKNOWN,
        UNVERIFIED_RUN,
        CURRENT_RUN,
        CURRENT_RUN_NOT_READY,
        NO_RUN_IDENTITY,
    }

    fun noRecordStatus(state: DiagnosticsCollector.HookState): NoRecordStatus = when {
        DiagnosticsCollector.hasStaleBootRunIdentity(state) -> NoRecordStatus.PRIOR_BOOT
        DiagnosticsCollector.hasUnknownBootRunIdentity(state) -> NoRecordStatus.BOOT_UNKNOWN
        DiagnosticsCollector.hasVerifiedOriginIdentity(state) &&
            !DiagnosticsCollector.hasVerifiedRunIdentity(state) -> NoRecordStatus.BOOT_UNKNOWN
        DiagnosticsCollector.hasVerifiedRunIdentity(state) &&
            state.lastPackageReady == null && state.lastPackageLoaded != null ->
            NoRecordStatus.CURRENT_RUN_NOT_READY
        DiagnosticsCollector.hasVerifiedRunIdentity(state) -> NoRecordStatus.CURRENT_RUN
        state.activeRunId != null || state.anyRecorded -> NoRecordStatus.UNVERIFIED_RUN
        else -> NoRecordStatus.NO_RUN_IDENTITY
    }
}
