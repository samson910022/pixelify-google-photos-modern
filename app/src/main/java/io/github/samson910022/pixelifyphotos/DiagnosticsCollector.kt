package io.github.samson910022.pixelifyphotos

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure, JVM-testable logic backing [DiagnosticsActivity].
 *
 * No Android framework calls are made in this object; every external value is
 * passed in by the caller so the field comparison, milestone interpretation,
 * and report formatting can be unit-tested on a host JVM.
 */
object DiagnosticsCollector {

    /**
     * One Build field comparison: the spoof target value vs the real value.
     */
    data class FieldCheck(
        val field: String,
        val expected: String?,
        val actual: String?,
    ) {
        /** True only when a spoof target exists and matches the real value. */
        val pass: Boolean get() = expected != null && expected == actual
    }

    /**
     * Why hook telemetry cannot be presented. [NONE] when telemetry was read successfully;
     * the cause stays distinct so transport/auth failures are never reported as "no record".
     */
    enum class DiagnosticsUnavailableReason {
        NONE,
        PREFERENCES_UNREADABLE,
        TOKEN_NOT_SYNCHRONIZED,
    }

    /**
     * Snapshot of lifecycle, registration, invocation, and Build readback signals
     * recorded by [PixelifyModule], [FeatureSpoofer], and [DeviceSpoofer] in a
     * scoped target process, read back through the shared remote preferences.
     */
    data class HookState(
        /** Profile-setting availability is independent of remote hook telemetry availability. */
        val profileSettingsAvailable: Boolean = false,
        val activePackage: String? = null,
        val activeRunId: String? = null,
        val activeRunStartedAt: Long? = null,
        val activeRunBootCount: Int? = null,
        val currentBootCount: Int? = null,
        val activeRunBootEpoch: String? = null,
        val currentBootEpoch: String? = null,
        val activeOriginVerified: Boolean = false,
        /** False when telemetry cannot be presented; see [diagnosticsUnavailableReason]. */
        val diagnosticsAvailable: Boolean = true,
        val processPid: Int? = null,
        val moduleLoadedAt: Long? = null,
        val lastPackageLoaded: String? = null,
        val lastPackageReady: String? = null,
        val lastPackageReadyAt: Long? = null,
        val verifyAt: Long? = null,
        val verifyDevice: String? = null,
        val verifyPackage: String? = null,
        val verifyOk: Boolean? = null,
        val verifyFailed: List<String> = emptyList(),
        val nativeReady: Boolean? = null,
        val systemPropsHooked: Boolean? = null,
        val featureHooksAt: Long? = null,
        val featureHookHasSystemFeature: Boolean? = null,
        val featureHookHasSystemFeatureWithFlags: Boolean? = null,
        val featureHookGetAvailableFeatures: Boolean? = null,
        val systemPropsHooksAt: Long? = null,
        val systemPropsHookGet: Boolean? = null,
        val systemPropsHookGetWithDefault: Boolean? = null,
        val featureInterceptorAt: Long? = null,
        val systemPropsInterceptorAt: Long? = null,
        /** Set when [diagnosticsAvailable] is false; distinguishes unreadable data from missing auth. */
        val diagnosticsUnavailableReason: DiagnosticsUnavailableReason = DiagnosticsUnavailableReason.NONE,
    ) {
        /** True when any lifecycle, registration, invocation, or VERIFY signal exists. */
        val anyRecorded: Boolean
            get() = moduleLoadedAt != null || lastPackageLoaded != null ||
                lastPackageReady != null || verifyAt != null || featureHooksAt != null ||
                systemPropsHooksAt != null || featureInterceptorAt != null || systemPropsInterceptorAt != null
    }

    /**
     * Compare expected spoof values against real values. Keys are sorted so
     * output order is stable regardless of map iteration order.
     */
    fun compareProps(expected: Map<String, String>, actual: Map<String, String>): List<FieldCheck> =
        (expected.keys + actual.keys).sorted().map { key ->
            FieldCheck(
                field = key,
                expected = expected[key],
                actual = actual[key],
            )
        }

    /**
     * Structured hook-lifecycle signal, the single source of truth for both the
     * English copyable report ([interpretMilestones]) and the localized on-screen
     * rendering in `DiagnosticsActivity`.
     */
    enum class MilestoneKind {
        NO_RECORD,
        DIAGNOSTICS_UNAVAILABLE,
        /** Telemetry store is readable, but no auth token means no target can deliver telemetry. */
        DIAGNOSTICS_TRANSPORT_UNAVAILABLE,
        PRIOR_BOOT_RUN,
        BOOT_UNKNOWN_RUN,
        MODULE_LOADED,
        NO_PACKAGE_LOAD_RECORDED,
        PACKAGE_LOADED,
        PACKAGE_NOT_READY,
        PACKAGE_READY,
        VERIFY_OK,
        VERIFY_FAILED,
        VERIFY_UNVERIFIED,
        VERIFY_PRIOR_BOOT,
        VERIFY_BOOT_UNKNOWN,
        VERIFY_UNEXPECTED,
        VERIFY_NONE_BUT_READY,
    }

    data class MilestoneSignal(
        val kind: MilestoneKind,
        val pkg: String? = null,
        val timestamp: Long? = null,
        val failedFields: List<String> = emptyList(),
        val nativeReady: Boolean? = null,
        val clockAnomaly: Boolean = false,
        val priorBootRun: Boolean = false,
        val currentnessUnknown: Boolean = false,
        val tokenAuthenticated: Boolean = false,
    )

    /**
     * Derive the ordered list of hook-lifecycle signals from a [HookState].
     * Presentation-free and pure so it can be unit-tested and reused by both the
     * English report and the localized UI.
     */
    fun milestoneSignals(state: HookState, now: Long = System.currentTimeMillis()): List<MilestoneSignal> {
        if (!state.diagnosticsAvailable) {
            val kind = if (state.diagnosticsUnavailableReason ==
                DiagnosticsUnavailableReason.TOKEN_NOT_SYNCHRONIZED
            ) {
                MilestoneKind.DIAGNOSTICS_TRANSPORT_UNAVAILABLE
            } else {
                MilestoneKind.DIAGNOSTICS_UNAVAILABLE
            }
            return listOf(MilestoneSignal(kind))
        }
        val hasLifecycleRecord = state.moduleLoadedAt != null || state.lastPackageLoaded != null ||
            state.lastPackageReady != null || state.verifyAt != null
        val signals = mutableListOf<MilestoneSignal>()
        val tokenAuthenticated = hasTokenAuthenticatedRunIdentity(state)
        val bootRelation = bootRelation(state)
        val staleBootRun = bootRelation == BootRelation.PRIOR
        val currentnessUnknown = bootRelation == BootRelation.UNKNOWN && hasVerifiedOriginIdentity(state)
        val tokenCurrentnessUnknown = bootRelation == BootRelation.UNKNOWN && tokenAuthenticated
        if (staleBootRun) signals += MilestoneSignal(
            MilestoneKind.PRIOR_BOOT_RUN,
            tokenAuthenticated = tokenAuthenticated,
        )
        if (currentnessUnknown) signals += MilestoneSignal(MilestoneKind.BOOT_UNKNOWN_RUN)
        if (tokenCurrentnessUnknown) signals += MilestoneSignal(
            MilestoneKind.BOOT_UNKNOWN_RUN,
            tokenAuthenticated = true,
        )
        if (!hasLifecycleRecord) {
            signals += MilestoneSignal(
                MilestoneKind.NO_RECORD,
                priorBootRun = staleBootRun,
                currentnessUnknown = currentnessUnknown || tokenCurrentnessUnknown,
                tokenAuthenticated = tokenAuthenticated,
            )
            return signals
        }

        val runCurrentnessUnknown = currentnessUnknown || tokenCurrentnessUnknown
        if (state.moduleLoadedAt != null) {
            signals += MilestoneSignal(
                MilestoneKind.MODULE_LOADED,
                timestamp = state.moduleLoadedAt,
                clockAnomaly = state.moduleLoadedAt > now,
                currentnessUnknown = runCurrentnessUnknown,
                tokenAuthenticated = tokenAuthenticated,
            )
        }
        if (state.lastPackageLoaded != null) {
            signals += MilestoneSignal(
                MilestoneKind.PACKAGE_LOADED,
                pkg = state.lastPackageLoaded,
                currentnessUnknown = runCurrentnessUnknown,
                tokenAuthenticated = tokenAuthenticated,
            )
            if (state.lastPackageReady == null) {
                signals += MilestoneSignal(
                    MilestoneKind.PACKAGE_NOT_READY,
                    priorBootRun = staleBootRun,
                    currentnessUnknown = runCurrentnessUnknown,
                    tokenAuthenticated = tokenAuthenticated,
                )
            } else {
                signals += MilestoneSignal(
                    MilestoneKind.PACKAGE_READY,
                    pkg = state.lastPackageReady,
                    timestamp = state.lastPackageReadyAt,
                    clockAnomaly = state.lastPackageReadyAt?.let { it > now } == true,
                    currentnessUnknown = runCurrentnessUnknown,
                    tokenAuthenticated = tokenAuthenticated,
                )
            }
        } else {
            if (state.lastPackageReady != null || state.moduleLoadedAt != null) {
                signals += MilestoneSignal(
                    MilestoneKind.NO_PACKAGE_LOAD_RECORDED,
                    priorBootRun = staleBootRun,
                    currentnessUnknown = runCurrentnessUnknown,
                    tokenAuthenticated = tokenAuthenticated,
                )
            }
            if (state.lastPackageReady != null) {
                signals += MilestoneSignal(
                    MilestoneKind.PACKAGE_READY,
                    pkg = state.lastPackageReady,
                    timestamp = state.lastPackageReadyAt,
                    clockAnomaly = state.lastPackageReadyAt?.let { it > now } == true,
                    currentnessUnknown = runCurrentnessUnknown,
                    tokenAuthenticated = tokenAuthenticated,
                )
            }
        }
        val verifyCurrentnessKnown = bootRelation != BootRelation.UNKNOWN
        if (state.verifyAt != null && staleBootRun) {
            signals += MilestoneSignal(
                MilestoneKind.VERIFY_PRIOR_BOOT,
                timestamp = state.verifyAt,
                clockAnomaly = state.verifyAt > now,
                tokenAuthenticated = tokenAuthenticated,
            )
        } else if (state.verifyAt != null && tokenAuthenticated) {
            if (!verifyCurrentnessKnown) {
                signals += MilestoneSignal(
                    MilestoneKind.VERIFY_BOOT_UNKNOWN,
                    timestamp = state.verifyAt,
                    clockAnomaly = state.verifyAt > now,
                    tokenAuthenticated = true,
                )
            } else when (state.verifyOk) {
                true -> signals += MilestoneSignal(
                    MilestoneKind.VERIFY_OK,
                    timestamp = state.verifyAt,
                    nativeReady = state.nativeReady,
                    clockAnomaly = state.verifyAt > now,
                    tokenAuthenticated = true,
                )
                false -> signals += MilestoneSignal(
                    MilestoneKind.VERIFY_FAILED,
                    timestamp = state.verifyAt,
                    failedFields = state.verifyFailed,
                    nativeReady = state.nativeReady,
                    clockAnomaly = state.verifyAt > now,
                    tokenAuthenticated = true,
                )
                null -> signals += MilestoneSignal(
                    MilestoneKind.VERIFY_UNEXPECTED,
                    timestamp = state.verifyAt,
                    clockAnomaly = state.verifyAt > now,
                    tokenAuthenticated = true,
                )
            }
        } else if (state.verifyAt != null && !hasVerifiedOriginIdentity(state)) {
            signals += MilestoneSignal(
                MilestoneKind.VERIFY_UNVERIFIED,
                timestamp = state.verifyAt,
                clockAnomaly = state.verifyAt > now,
            )
        } else if (state.verifyAt != null && !hasVerifiedRunIdentity(state)) {
            signals += MilestoneSignal(
                MilestoneKind.VERIFY_BOOT_UNKNOWN,
                timestamp = state.verifyAt,
                clockAnomaly = state.verifyAt > now,
            )
        } else if (state.verifyAt != null) {
            when (state.verifyOk) {
                true -> signals += MilestoneSignal(
                    MilestoneKind.VERIFY_OK,
                    timestamp = state.verifyAt,
                    nativeReady = state.nativeReady,
                    clockAnomaly = state.verifyAt > now,
                )
                false -> signals += MilestoneSignal(
                    MilestoneKind.VERIFY_FAILED,
                    timestamp = state.verifyAt,
                    failedFields = state.verifyFailed,
                    nativeReady = state.nativeReady,
                    clockAnomaly = state.verifyAt > now,
                )
                null -> signals += MilestoneSignal(
                    MilestoneKind.VERIFY_UNEXPECTED,
                    timestamp = state.verifyAt,
                    clockAnomaly = state.verifyAt > now,
                )
            }
        } else if (state.lastPackageReady != null) {
            signals += MilestoneSignal(
                MilestoneKind.VERIFY_NONE_BUT_READY,
                priorBootRun = staleBootRun,
                currentnessUnknown = runCurrentnessUnknown,
                tokenAuthenticated = tokenAuthenticated,
            )
        }
        return signals
    }

    /**
     * Plain-language (English) interpretation of the recorded hook milestones,
     * mirroring the load/VERIFY signal table documented in SUPPORT.md. Used by
     * the copyable report; the on-screen UI uses [milestoneSignals] with
     * localized strings.
     */
    fun interpretMilestones(
        state: HookState,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        val lines = milestoneSignals(state, now).map { englishMilestone(it) }
        if (!state.diagnosticsAvailable || (!state.anyRecorded && state.verifyAt == null)) return lines
        return when {
            hasTokenAuthenticatedRunIdentity(state) ->
                lines.map { "TOKEN-AUTHENTICATED FALLBACK (sender identity not OS-verified): $it" }
            !hasVerifiedOriginIdentity(state) ->
                lines.map { "UNVERIFIED TELEMETRY (reported package attribution is not OS-verified): $it" }
            else -> lines
        }
    }

    /** OS-verified provenance for the persisted run, independent of which boot is current now. */
    fun hasVerifiedOriginIdentity(state: HookState): Boolean = state.activeOriginVerified &&
        !state.activePackage.isNullOrBlank() && !state.activeRunId.isNullOrBlank()

    /**
     * An accepted run identity whose sender was authenticated by token-only fallback,
     * not by OS-verified package/UID binding. Useful for diagnosis but never OS proof.
     */
    fun hasTokenAuthenticatedRunIdentity(state: HookState): Boolean =
        state.diagnosticsAvailable && !state.activeOriginVerified &&
            !state.activePackage.isNullOrBlank() && !state.activeRunId.isNullOrBlank() &&
            (state.activeRunStartedAt ?: 0L) > 0L

    /** Any accepted run identity with enough metadata to relate telemetry to a run. */
    private fun hasAcceptedRunIdentity(state: HookState): Boolean =
        hasVerifiedOriginIdentity(state) || hasTokenAuthenticatedRunIdentity(state)

    enum class BootRelation { CURRENT, PRIOR, UNKNOWN }

    /** Relates an accepted active run to this boot without conflating missing data with a mismatch. */
    fun bootRelation(state: HookState): BootRelation {
        if (!hasAcceptedRunIdentity(state)) return BootRelation.UNKNOWN
        val runBootEpoch = state.activeRunBootEpoch?.takeIf(String::isNotBlank) ?: return BootRelation.UNKNOWN
        val currentBootEpoch = state.currentBootEpoch?.takeIf(String::isNotBlank) ?: return BootRelation.UNKNOWN
        return if (runBootEpoch == currentBootEpoch) BootRelation.CURRENT else BootRelation.PRIOR
    }

    /** An OS-verified origin belongs to the current boot only when unique OS boot epochs match. */
    fun hasVerifiedRunIdentity(state: HookState): Boolean =
        hasVerifiedOriginIdentity(state) && bootRelation(state) == BootRelation.CURRENT

    /** A verified persisted run from an earlier OS boot is historical, never current evidence. */
    fun hasStaleBootRunIdentity(state: HookState): Boolean =
        hasVerifiedOriginIdentity(state) && bootRelation(state) == BootRelation.PRIOR

    /** OS-verified origin with an active run whose currentness cannot be established. */
    fun hasUnknownBootRunIdentity(state: HookState): Boolean =
        hasVerifiedOriginIdentity(state) && bootRelation(state) == BootRelation.UNKNOWN

    /** Token-authenticated unverified run attributable to the current boot by epoch match. */
    fun hasTokenAuthenticatedCurrentRun(state: HookState): Boolean =
        hasTokenAuthenticatedRunIdentity(state) && bootRelation(state) == BootRelation.CURRENT

    fun hasTokenAuthenticatedPriorBootRun(state: HookState): Boolean =
        hasTokenAuthenticatedRunIdentity(state) && bootRelation(state) == BootRelation.PRIOR

    fun hasTokenAuthenticatedUnknownBootRun(state: HookState): Boolean =
        hasTokenAuthenticatedRunIdentity(state) && bootRelation(state) == BootRelation.UNKNOWN

    private fun englishMilestone(signal: MilestoneSignal): String = when (signal.kind) {
        MilestoneKind.NO_RECORD -> if (signal.priorBootRun) {
            "No lifecycle milestones were recorded for this prior-boot run. Hook registration results, if available, are shown separately."
        } else if (signal.currentnessUnknown) {
            if (signal.tokenAuthenticated) {
                "No lifecycle milestones were recorded for a token-authenticated run whose current boot could not be established. This is historical/unknown data with unverified sender identity, not current OS-verified evidence."
            } else {
                "No lifecycle milestones were recorded for an OS-verified run whose current boot could not be established. This is unknown historical data, not evidence about the current run."
            }
        } else
            "No lifecycle milestones have been recorded yet. Hook registration results, if available, are shown separately. Open Google Photos once, wait a few seconds, then reopen this screen."
        MilestoneKind.DIAGNOSTICS_UNAVAILABLE ->
            "Diagnostics data is unavailable because preferences could not be read; no-record status cannot be determined."
        MilestoneKind.DIAGNOSTICS_TRANSPORT_UNAVAILABLE ->
            "Diagnostics data is unavailable because the diagnostics auth token is not synchronized to remote preferences. The hooked process cannot deliver telemetry, so an empty result cannot be inferred. Restart LSPosed or toggle the module to rebind the manager service, then reopen Google Photos and this screen."
        MilestoneKind.PRIOR_BOOT_RUN ->
            if (signal.tokenAuthenticated) {
                "Historical/prior-boot diagnostics from a token-authenticated fallback run (sender identity not OS-verified); these records are not current OS-verified evidence."
            } else {
                "Historical/prior-boot diagnostics from an OS-verified origin; these records are not current evidence."
            }
        MilestoneKind.BOOT_UNKNOWN_RUN ->
            if (signal.tokenAuthenticated) {
                "Token-authenticated fallback run (sender identity not OS-verified); the run's boot could not be matched to the current boot. Lifecycle and hook data below have unverified currentness and are not current OS-verified evidence."
            } else {
                "OS-verified origin; the run's boot could not be matched to the current boot. Lifecycle and hook data below have unverified currentness and are not current evidence."
            }
        MilestoneKind.MODULE_LOADED ->
            if (signal.currentnessUnknown) {
                "Module-loaded record is historical/unverified for currentness (${formatTimestamp(signal.timestamp)}); run boot is unknown."
            } else {
                "Module loaded in a scoped process (${formatTimestamp(signal.timestamp)})." +
                    if (signal.tokenAuthenticated) " Sender identity is token-authenticated but not OS-verified." else ""
            }
        MilestoneKind.NO_PACKAGE_LOAD_RECORDED -> if (signal.priorBootRun) {
            "No package-load callback was recorded for that prior-boot run. Missing data alone does not establish whether a callback occurred or why it is missing."
        } else if (signal.currentnessUnknown) {
            "No package-load callback was recorded for a run whose currentness is unknown. Missing data does not describe the current run or establish why the record is missing."
        } else
            "No package-load callback was recorded for this run. Check the configured scope and reopen the target app; absence of a record alone does not establish whether a callback occurred or why it is missing."
        MilestoneKind.PACKAGE_LOADED ->
            if (signal.currentnessUnknown) {
                "Historical/unverified-currentness package-load record: ${signal.pkg} (run boot unknown; not current evidence)."
            } else {
                "Package-load callback observed: ${signal.pkg}." +
                    if (signal.tokenAuthenticated) " Sender identity is token-authenticated but not OS-verified." else ""
            }
        MilestoneKind.PACKAGE_NOT_READY -> if (signal.priorBootRun) {
            "Package was loaded; a package-ready callback was not recorded for that prior-boot run. This does not establish whether the callback occurred or why it is missing."
        } else if (signal.currentnessUnknown) {
            "Package was loaded in a run whose currentness is unknown; no package-ready record can be treated as current or as 'not observed yet'."
        } else
            "Package loaded; package-ready callback not observed yet. This is not evidence that the package never became ready or that the framework/API is incompatible."
        MilestoneKind.PACKAGE_READY ->
            if (signal.currentnessUnknown) {
                "Historical/unverified-currentness package-ready record: ${signal.pkg} (${formatTimestamp(signal.timestamp)}; run boot unknown). This is not current evidence."
            } else {
                "Last package-ready callback observed: ${signal.pkg} (${formatTimestamp(signal.timestamp)}). This is lifecycle evidence only; see registration results below." +
                    if (signal.tokenAuthenticated) " Sender identity is token-authenticated but not OS-verified." else ""
            }
        MilestoneKind.VERIFY_OK -> if (signal.clockAnomaly) {
            "Build-field VERIFY has a future timestamp (${formatTimestamp(signal.timestamp)}); stale and not treated as a current result."
        } else if (signal.tokenAuthenticated) {
            "Last Build-field VERIFY readback passed (token-authenticated fallback, sender identity not OS-verified; nativeReady=${signal.nativeReady ?: "unknown"}); this does not verify feature hooks or Photos behavior."
        } else
            "Last Build-field VERIFY readback passed (nativeReady=${signal.nativeReady ?: "unknown"}); this does not verify feature hooks or Photos behavior."
        MilestoneKind.VERIFY_FAILED -> if (signal.clockAnomaly) {
            "Build-field VERIFY has a future timestamp (${formatTimestamp(signal.timestamp)}); stale and not treated as a current result."
        } else if (signal.tokenAuthenticated) {
            "Last Build-field VERIFY readback failed (token-authenticated fallback, sender identity not OS-verified): " +
                signal.failedFields.joinToString().ifEmpty { "unknown fields" } +
                " (nativeReady=${signal.nativeReady ?: "unknown"}). See the copied diagnostics report or enable verbose logging."
        } else
            "Last Build-field VERIFY readback failed: " +
                signal.failedFields.joinToString().ifEmpty { "unknown fields" } +
                " (nativeReady=${signal.nativeReady ?: "unknown"}). See the copied diagnostics report or enable verbose logging."
        MilestoneKind.VERIFY_UNVERIFIED -> if (signal.clockAnomaly) {
            "Legacy or unverified-origin VERIFY data has no OS-verified run identity; result is unknown (future timestamp / stale)."
        } else {
            "Legacy or unverified-origin VERIFY data has no OS-verified run identity; result is unknown."
        }
        MilestoneKind.VERIFY_PRIOR_BOOT ->
            if (signal.tokenAuthenticated) {
                "Prior-boot VERIFY from a token-authenticated fallback run is historical (sender identity not OS-verified); its result is not current OS-verified evidence."
            } else {
                "Prior-boot VERIFY from an OS-verified origin is historical; its result is unknown, not current."
            }
        MilestoneKind.VERIFY_BOOT_UNKNOWN ->
            if (signal.tokenAuthenticated) {
                "VERIFY is from a token-authenticated fallback run (sender identity not OS-verified), but the run's boot could not be matched to the current boot; result is not current OS-verified evidence."
            } else {
                "VERIFY has an OS-verified origin, but the run's boot could not be matched to the current boot; result is unknown."
            }
        MilestoneKind.VERIFY_UNEXPECTED -> if (signal.clockAnomaly) {
            "VERIFY has a future timestamp (${formatTimestamp(signal.timestamp)}); stale clock anomaly."
        } else
            "VERIFY ran but no result was recorded (unexpected)."
        MilestoneKind.VERIFY_NONE_BUT_READY -> if (signal.priorBootRun) {
            "Package-ready was observed, but no VERIFY result was recorded for that prior-boot run. This is historical and does not describe the current run."
        } else if (signal.currentnessUnknown) {
            "Package-ready was recorded for a run whose currentness is unknown, but no VERIFY result is recorded; current-run VERIFY state is unknown."
        } else
            "Package-ready ran but no VERIFY result was recorded; enable verbose logging in Advanced options and retry."
    }

    /** Format an epoch-millis timestamp for diagnostics output. */
    fun formatTimestamp(epochMillis: Long?, now: Long = System.currentTimeMillis()): String {
        if (epochMillis == null) return "never recorded"
        if (epochMillis > now) return "future timestamp (clock anomaly; stale)"
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMillis))
        } catch (_: Throwable) {
            epochMillis.toString()
        }
    }

    enum class RegistrationStatus { UNKNOWN, CURRENTNESS_UNKNOWN, STALE, NONE, PARTIAL, ALL }

    data class RegistrationAssessment(
        val status: RegistrationStatus,
        val successful: Int,
        val total: Int,
        val recordedAt: Long?,
        val outcomesRecorded: Boolean = false,
    )

    enum class InvocationStatus { UNKNOWN, CURRENTNESS_UNKNOWN, STALE, NOT_OBSERVED, OBSERVED }

    /** Pure state model consumed by the localized UI and copyable report. */
    data class HookRegistrationModel(
        val feature: RegistrationAssessment,
        val systemProperties: RegistrationAssessment,
        val featureInvocation: InvocationStatus,
        val systemPropertiesInvocation: InvocationStatus,
        val originVerified: Boolean,
        val tokenAuthenticatedRun: Boolean = false,
    )

    private const val REGISTRATION_FRESHNESS_MS = 24L * 60L * 60L * 1000L

    /** Fail-closed summary for one set of independently registered methods. */
    fun assessRegistrations(
        recordedAt: Long?,
        outcomes: List<Boolean?>,
        now: Long,
    ): RegistrationAssessment {
        val successes = outcomes.count { it == true }
        val status = when {
            recordedAt == null || outcomes.all { it == null } -> RegistrationStatus.UNKNOWN
            recordedAt > now || now - recordedAt > REGISTRATION_FRESHNESS_MS -> RegistrationStatus.STALE
            outcomes.any { it == null } && successes == 0 -> RegistrationStatus.UNKNOWN
            outcomes.all { it == true } -> RegistrationStatus.ALL
            outcomes.all { it == false } -> RegistrationStatus.NONE
            else -> RegistrationStatus.PARTIAL
        }
        return RegistrationAssessment(
            status,
            successes,
            outcomes.size,
            recordedAt,
            outcomesRecorded = outcomes.any { it != null },
        )
    }

    fun assessInvocation(recordedAt: Long?, now: Long): InvocationStatus = when {
        recordedAt == null -> InvocationStatus.NOT_OBSERVED
        recordedAt > now || now - recordedAt > REGISTRATION_FRESHNESS_MS -> InvocationStatus.STALE
        else -> InvocationStatus.OBSERVED
    }

    fun registrationModel(state: HookState, now: Long): HookRegistrationModel {
        val verifiedCurrentRun = state.diagnosticsAvailable && hasVerifiedRunIdentity(state)
        val tokenAuthenticated = state.diagnosticsAvailable && hasTokenAuthenticatedRunIdentity(state)
        val tokenCurrentRun = state.diagnosticsAvailable && hasTokenAuthenticatedCurrentRun(state)
        val tokenPriorBootRun = state.diagnosticsAvailable && hasTokenAuthenticatedPriorBootRun(state)
        val tokenCurrentnessUnknown = state.diagnosticsAvailable && hasTokenAuthenticatedUnknownBootRun(state)
        val featureOutcomes = listOf(
            state.featureHookHasSystemFeature,
            state.featureHookHasSystemFeatureWithFlags,
            state.featureHookGetAvailableFeatures,
        )
        val systemPropertiesOutcomes = listOf(state.systemPropsHookGet, state.systemPropsHookGetWithDefault)
        val staleBootRun = state.diagnosticsAvailable && hasStaleBootRunIdentity(state)
        val currentnessUnknown = state.diagnosticsAvailable && hasUnknownBootRunIdentity(state)
        fun priorBootAssessment(recordedAt: Long?, outcomes: List<Boolean?>): RegistrationAssessment =
            if (recordedAt == null && outcomes.all { it == null }) {
                RegistrationAssessment(
                    RegistrationStatus.UNKNOWN,
                    0,
                    outcomes.size,
                    null,
                    outcomesRecorded = false,
                )
            } else {
                RegistrationAssessment(
                    RegistrationStatus.STALE,
                    outcomes.count { it == true },
                    outcomes.size,
                    recordedAt,
                    outcomesRecorded = outcomes.any { it != null },
                )
            }

        val feature = when {
            verifiedCurrentRun || tokenCurrentRun -> assessRegistrations(state.featureHooksAt, featureOutcomes, now)
            staleBootRun || tokenPriorBootRun -> priorBootAssessment(state.featureHooksAt, featureOutcomes)
            currentnessUnknown || tokenCurrentnessUnknown -> RegistrationAssessment(
                RegistrationStatus.CURRENTNESS_UNKNOWN,
                0,
                featureOutcomes.size,
                state.featureHooksAt,
                outcomesRecorded = featureOutcomes.any { it != null },
            )
            else -> RegistrationAssessment(
                RegistrationStatus.UNKNOWN,
                0,
                featureOutcomes.size,
                state.featureHooksAt,
                outcomesRecorded = featureOutcomes.any { it != null },
            )
        }
        val systemProperties = when {
            verifiedCurrentRun || tokenCurrentRun -> assessRegistrations(state.systemPropsHooksAt, systemPropertiesOutcomes, now)
            staleBootRun || tokenPriorBootRun -> priorBootAssessment(state.systemPropsHooksAt, systemPropertiesOutcomes)
            currentnessUnknown || tokenCurrentnessUnknown -> RegistrationAssessment(
                RegistrationStatus.CURRENTNESS_UNKNOWN,
                0,
                systemPropertiesOutcomes.size,
                state.systemPropsHooksAt,
                outcomesRecorded = systemPropertiesOutcomes.any { it != null },
            )
            else -> RegistrationAssessment(
                RegistrationStatus.UNKNOWN,
                0,
                systemPropertiesOutcomes.size,
                state.systemPropsHooksAt,
                outcomesRecorded = systemPropertiesOutcomes.any { it != null },
            )
        }
        return HookRegistrationModel(
            feature = feature,
            systemProperties = systemProperties,
            featureInvocation = when {
                verifiedCurrentRun || tokenCurrentRun -> assessInvocation(state.featureInterceptorAt, now)
                (staleBootRun || tokenPriorBootRun) && state.featureInterceptorAt != null -> InvocationStatus.STALE
                currentnessUnknown || tokenCurrentnessUnknown -> InvocationStatus.CURRENTNESS_UNKNOWN
                else -> InvocationStatus.UNKNOWN
            },
            systemPropertiesInvocation = when {
                verifiedCurrentRun || tokenCurrentRun -> assessInvocation(state.systemPropsInterceptorAt, now)
                (staleBootRun || tokenPriorBootRun) && state.systemPropsInterceptorAt != null -> InvocationStatus.STALE
                currentnessUnknown || tokenCurrentnessUnknown -> InvocationStatus.CURRENTNESS_UNKNOWN
                else -> InvocationStatus.UNKNOWN
            },
            originVerified = state.diagnosticsAvailable && hasVerifiedOriginIdentity(state),
            tokenAuthenticatedRun = tokenAuthenticated,
        )
    }

    /** Reason-aware wording so a missing auth token is never described as unreadable data. */
    private fun unavailableDiagnosticsDescription(state: HookState): String =
        if (state.diagnosticsUnavailableReason ==
            DiagnosticsUnavailableReason.TOKEN_NOT_SYNCHRONIZED
        ) {
            "unavailable (diagnostics token is not synchronized; no telemetry can be delivered)"
        } else {
            "unavailable (diagnostics data could not be read)"
        }

    /** Summary line that keeps a transport/auth failure distinct from readable-empty telemetry. */
    private fun diagnosticsDataStatus(state: HookState): String = when {
        state.diagnosticsAvailable -> "Diagnostics data: readable"
        state.diagnosticsUnavailableReason == DiagnosticsUnavailableReason.TOKEN_NOT_SYNCHRONIZED ->
            "Diagnostics data: UNAVAILABLE (diagnostics token is not synchronized to remote " +
                "preferences; no telemetry can be delivered, so empty telemetry cannot be inferred)"
        else ->
            "Diagnostics data: UNAVAILABLE (preferences could not be read; empty telemetry cannot be inferred)"
    }

    /** Registration is not proof of execution; no combined "hook success" flag is exposed. */
    fun formatHookRegistrationText(state: HookState, now: Long): List<String> {
        val model = registrationModel(state, now)
        val tokenAuthenticated = hasTokenAuthenticatedRunIdentity(state)
        fun rowOutcome(value: Boolean?, assessment: RegistrationAssessment): String =
            when (assessment.status) {
                RegistrationStatus.STALE -> "unknown (stale run/timestamp)"
                RegistrationStatus.CURRENTNESS_UNKNOWN -> if (tokenAuthenticated) {
                    "unknown (token-authenticated run, currentness unverified)"
                } else {
                    "unknown (OS-verified origin, run currentness unverified)"
                }
                RegistrationStatus.UNKNOWN -> "unknown"
                else -> formatOutcome(value)
            }
        val originDescription = when {
            !state.diagnosticsAvailable -> unavailableDiagnosticsDescription(state)
            hasVerifiedRunIdentity(state) -> state.activePackage ?: "unknown"
            hasStaleBootRunIdentity(state) ->
                "historical/prior-boot verified origin: ${state.activePackage ?: "unknown"}"
            hasVerifiedOriginIdentity(state) ->
                "OS-verified origin; current boot unknown: ${state.activePackage ?: "unknown"}"
            hasTokenAuthenticatedCurrentRun(state) ->
                "token-authenticated fallback (sender identity not OS-verified): ${state.activePackage ?: "unknown"}"
            hasTokenAuthenticatedPriorBootRun(state) ->
                "historical/prior-boot token-authenticated fallback (sender identity not OS-verified): ${state.activePackage ?: "unknown"}"
            hasTokenAuthenticatedRunIdentity(state) ->
                "token-authenticated fallback; current boot unknown (sender identity not OS-verified): ${state.activePackage ?: "unknown"}"
            else -> "unknown/unverified (reported origin: ${state.activePackage ?: "unknown"})"
        }
        val pidDescription = when {
            !state.diagnosticsAvailable -> unavailableDiagnosticsDescription(state)
            hasVerifiedRunIdentity(state) -> state.processPid?.toString() ?: "unknown"
            hasStaleBootRunIdentity(state) ->
                "historical/prior-boot OS-observed PID: ${state.processPid ?: "unknown"} (not current)"
            hasVerifiedOriginIdentity(state) -> "unknown (run boot not verified)"
            tokenAuthenticated -> "unavailable (token-authenticated fallback has no OS-observed sender PID)"
            else -> "unknown/unverified"
        }
        return listOf(
            "Registration origin: $originDescription " +
                "(run ${state.activeRunId ?: "unknown"}, started ${formatTimestamp(state.activeRunStartedAt, now)})",
            "OS-observed sender PID (Binder when available): $pidDescription",
            "Feature hooks: ${formatRegistration(model.feature, now)}",
            "  hasSystemFeature(String): ${rowOutcome(state.featureHookHasSystemFeature, model.feature)}",
            "  hasSystemFeature(String, int): ${rowOutcome(state.featureHookHasSystemFeatureWithFlags, model.feature)}",
            "  getSystemAvailableFeatures(): ${rowOutcome(state.featureHookGetAvailableFeatures, model.feature)}",
            "SystemProperties hooks: ${formatRegistration(model.systemProperties, now)}",
            "  get(String): ${rowOutcome(state.systemPropsHookGet, model.systemProperties)}",
            "  get(String, String): ${rowOutcome(state.systemPropsHookGetWithDefault, model.systemProperties)}",
            "Feature interceptor entered: ${formatInvocation(model.featureInvocation, state.featureInterceptorAt, now)}",
            "SystemProperties interceptor entered: ${formatInvocation(model.systemPropertiesInvocation, state.systemPropsInterceptorAt, now)}",
            "All registrations do not prove Google Photos called a target API. An observed entry only proves an interceptor was entered, not that it ran for Google Photos or returned a spoofed value.",
        )
    }

    private fun formatRegistration(assessment: RegistrationAssessment, now: Long): String = when (assessment.status) {
        RegistrationStatus.UNKNOWN -> "unknown/unavailable (registration outcomes unavailable)"
        RegistrationStatus.CURRENTNESS_UNKNOWN -> "unknown (run currentness unverified)" +
            if (assessment.outcomesRecorded) " (recorded outcomes not treated as current)" else " (no current outcomes established)"
        RegistrationStatus.STALE -> if (assessment.outcomesRecorded) {
            "stale last report (${assessment.successful}/${assessment.total} marked registered)"
        } else {
            "stale last report (registration outcomes unavailable)"
        }
        RegistrationStatus.NONE -> "none registered (0/${assessment.total})"
        RegistrationStatus.PARTIAL -> "partial (${assessment.successful}/${assessment.total} registered)"
        RegistrationStatus.ALL -> "all registered (${assessment.successful}/${assessment.total})"
    } + "; recorded ${formatTimestamp(assessment.recordedAt, now)}"

    private fun formatOutcome(outcome: Boolean?): String = when (outcome) {
        true -> "registered"
        false -> "not registered"
        null -> "unknown"
    }

    private fun formatInvocation(status: InvocationStatus, recordedAt: Long?, now: Long): String = when (status) {
        InvocationStatus.UNKNOWN -> "unknown"
        InvocationStatus.CURRENTNESS_UNKNOWN -> "unknown (run currentness unverified)"
        InvocationStatus.STALE -> "stale observation (${formatTimestamp(recordedAt, now)})"
        InvocationStatus.NOT_OBSERVED -> "not observed (may not have been called; absence is not proof)"
        InvocationStatus.OBSERVED -> "observed at least once (${formatTimestamp(recordedAt, now)})"
    }

    /**
     * Build the copyable diagnostics report text (English). All values are
     * passed in so the function stays framework-free and testable.
     */
    fun formatReportText(
        state: HookState,
        serviceConnected: Boolean,
        scope: List<String>?,
        selectedDevice: String?,
        real: Map<String, String>,
        target: Map<String, String>,
        serviceStatus: String? = null,
        profileIsFallback: Boolean = false,
    ): String {
        val now = System.currentTimeMillis()
        val reportState = state
        val verifiedRunIdentity = hasVerifiedRunIdentity(state)
        val verifiedOriginIdentity = hasVerifiedOriginIdentity(state)
        val tokenAuthenticatedRun = hasTokenAuthenticatedRunIdentity(state)
        val entitlement = selectedDevice?.let(DeviceProps::getBackupEntitlement)
        val entitlementSummary = when (entitlement) {
            DeviceProps.BackupEntitlement.UNLIMITED_ORIGINAL ->
                "Unlimited Original Quality (0 quota used)"
            DeviceProps.BackupEntitlement.UNLIMITED_STORAGE_SAVER ->
                "Unlimited Storage Saver only (Requires 'Storage saver' backup quality)"
            DeviceProps.BackupEntitlement.NO_UNLIMITED_STORAGE ->
                "No free unlimited backup (Uploads consume Google Account storage quota)"
            null -> "Unavailable (settings could not be read)"
        }
        val qualityRequirement = when (entitlement) {
            DeviceProps.BackupEntitlement.UNLIMITED_ORIGINAL ->
                "Original quality or Storage saver (0 bytes quota used)"
            DeviceProps.BackupEntitlement.UNLIMITED_STORAGE_SAVER ->
                "MUST be set to 'Storage saver' in Google Photos settings"
            DeviceProps.BackupEntitlement.NO_UNLIMITED_STORAGE ->
                "None — Google Account storage is consumed for all uploads"
            null -> "Unavailable (settings could not be read)"
        }
        val sb = StringBuilder()
        sb.appendLine("Pixelify Infinity diagnostics")
        sb.appendLine("Xposed manager service: ${serviceStatus ?: if (serviceConnected) "connected" else "disconnected"} (connection state only; not proof hooks registered)")
        sb.appendLine(diagnosticsDataStatus(reportState))
        sb.appendLine(
            "Profile settings: ${if (reportState.profileSettingsAvailable) "readable" else "UNAVAILABLE"}"
        )
        sb.appendLine("Scope: ${DiagnosticsScopePresentation.label(scope)}")
        sb.appendLine(
            when {
                selectedDevice == null -> "Selected profile: unavailable (settings could not be read)"
                profileIsFallback -> "Default profile (no saved user selection): $selectedDevice"
                else -> "Selected profile: $selectedDevice"
            }
        )
        val originDescription = when {
            !state.diagnosticsAvailable -> unavailableDiagnosticsDescription(state)
            verifiedRunIdentity -> reportState.activePackage ?: "unknown"
            hasStaleBootRunIdentity(state) ->
                "HISTORICAL (prior-boot, OS-verified origin: ${reportState.activePackage ?: "unknown"})"
            verifiedOriginIdentity ->
                "OS-verified origin, current boot unknown: ${reportState.activePackage ?: "unknown"}"
            hasTokenAuthenticatedCurrentRun(state) ->
                "TOKEN-AUTHENTICATED FALLBACK (sender identity not OS-verified): ${reportState.activePackage ?: "unknown"}"
            hasTokenAuthenticatedPriorBootRun(state) ->
                "HISTORICAL (prior-boot, token-authenticated fallback, sender identity not OS-verified): ${reportState.activePackage ?: "unknown"}"
            hasTokenAuthenticatedRunIdentity(state) ->
                "TOKEN-AUTHENTICATED FALLBACK (current boot unknown, sender identity not OS-verified): ${reportState.activePackage ?: "unknown"}"
            else -> "UNVERIFIED (reported origin: ${reportState.activePackage ?: "unknown"})"
        }
        val pidDescription = when {
            !state.diagnosticsAvailable -> unavailableDiagnosticsDescription(state)
            verifiedRunIdentity -> state.processPid?.toString() ?: "unknown"
            hasStaleBootRunIdentity(state) ->
                "historical/prior-boot PID (not current): ${state.processPid ?: "unknown"}"
            verifiedOriginIdentity -> "unknown (run boot not verified)"
            tokenAuthenticatedRun -> "unavailable (token-authenticated fallback has no OS-observed sender PID)"
            else -> "unknown/unverified"
        }
        sb.appendLine(
            "Target run origin: $originDescription; " +
                "run ID: ${reportState.activeRunId ?: "unknown"}; " +
                "started: ${formatTimestamp(state.activeRunStartedAt, now)}"
        )
        sb.appendLine("OS-observed sender PID (Binder when available): $pidDescription")
        sb.appendLine("Entitlement tier: $entitlementSummary")
        sb.appendLine("Backup quality requirement: $qualityRequirement")
        sb.appendLine()
        sb.appendLine("Hook milestones:")
        interpretMilestones(reportState).forEach { sb.appendLine("- $it") }
        sb.appendLine()
        sb.appendLine("Hook registration and runtime observation:")
        formatHookRegistrationText(reportState, now).forEach { sb.appendLine(it) }
        sb.appendLine()
        val tokenCurrentRun = hasTokenAuthenticatedCurrentRun(state)
        val tokenPriorBootRun = hasTokenAuthenticatedPriorBootRun(state)
        val hasLegacyUnverifiedRun = state.diagnosticsAvailable && (state.anyRecorded || state.verifyAt != null) &&
            !verifiedOriginIdentity && !tokenAuthenticatedRun
        val priorBootVerify = hasStaleBootRunIdentity(state) || tokenPriorBootRun
        val bootUnknownVerify = (verifiedOriginIdentity && !verifiedRunIdentity && !hasStaleBootRunIdentity(state)) ||
            (tokenAuthenticatedRun && !tokenCurrentRun && !tokenPriorBootRun)
        val verifyLabel = when {
            !state.diagnosticsAvailable -> "Last VERIFY: ${unavailableDiagnosticsDescription(state)}"
            hasStaleBootRunIdentity(state) -> "Last VERIFY (HISTORICAL PRIOR-BOOT, OS-VERIFIED ORIGIN)"
            tokenPriorBootRun -> "Last VERIFY (HISTORICAL PRIOR-BOOT, TOKEN-AUTHENTICATED FALLBACK)"
            verifiedOriginIdentity && !verifiedRunIdentity -> "Last VERIFY (current boot unknown, OS-verified origin)"
            tokenAuthenticatedRun && !tokenCurrentRun -> "Last VERIFY (current boot unknown, token-authenticated fallback)"
            tokenCurrentRun -> "Last VERIFY (token-authenticated fallback, sender identity not OS-verified)"
            hasLegacyUnverifiedRun -> "Last VERIFY (UNVERIFIED TELEMETRY)"
            else -> "Last VERIFY"
        }
        sb.appendLine(
            when {
                !state.diagnosticsAvailable -> "Last VERIFY: ${unavailableDiagnosticsDescription(state)}"
                state.verifyAt != null -> "$verifyLabel: ${formatTimestamp(state.verifyAt, now)}"
                hasStaleBootRunIdentity(state) -> "$verifyLabel: no VERIFY record for that prior-boot run; this does not describe the current run"
                tokenPriorBootRun -> "$verifyLabel: no VERIFY record for that token-authenticated fallback run from a prior boot; sender identity is not OS-verified and this does not describe the current run"
                bootUnknownVerify -> "$verifyLabel: no VERIFY record can be established as current; run relation is unknown"
                tokenCurrentRun -> "$verifyLabel: not recorded yet"
                hasLegacyUnverifiedRun -> "$verifyLabel: no VERIFY record has a verified current-run relation"
                verifiedRunIdentity -> "Last VERIFY (verified current run): not recorded yet"
                else -> "Last VERIFY: no record or OS-verified run relationship is available"
            }
        )
        if (state.diagnosticsAvailable && state.verifyAt != null) {
            val staleVerify = state.verifyAt > now
            val verifyResult = when {
                staleVerify -> "unknown (clock anomaly; stale)"
                hasLegacyUnverifiedRun -> "unknown (origin is not OS-verified)"
                hasStaleBootRunIdentity(state) -> "unknown (prior-boot verified origin; historical, not current)"
                tokenPriorBootRun -> "historical (prior-boot token-authenticated fallback; not current OS-verified evidence)"
                verifiedOriginIdentity && !verifiedRunIdentity -> "unknown (OS-verified origin, but current boot was not established)"
                tokenAuthenticatedRun && !tokenCurrentRun -> "unknown (token-authenticated fallback; current boot not established)"
                tokenCurrentRun -> state.verifyOk?.let {
                    if (it) "OK (token-authenticated fallback, sender identity not OS-verified)"
                    else "FAIL (token-authenticated fallback, sender identity not OS-verified)"
                } ?: "unknown (token-authenticated fallback, sender identity not OS-verified)"
                else -> state.verifyOk?.let { if (it) "OK" else "FAIL" } ?: "unknown"
            }
            val valuePrefix = when {
                hasLegacyUnverifiedRun -> "UNVERIFIED telemetry: "
                hasStaleBootRunIdentity(state) -> "historical/prior-boot: "
                tokenPriorBootRun -> "historical/prior-boot token-authenticated fallback: "
                verifiedOriginIdentity && !verifiedRunIdentity -> "historical (current boot unknown): "
                tokenAuthenticatedRun && !tokenCurrentRun -> "token-authenticated fallback (current boot unknown): "
                tokenCurrentRun -> "token-authenticated fallback (sender identity not OS-verified): "
                else -> ""
            }
            sb.appendLine(
                "  Result: $verifyResult"
            )
            sb.appendLine("  Device: $valuePrefix${reportState.verifyDevice ?: "unknown"}")
            sb.appendLine("  Package: $valuePrefix${reportState.verifyPackage ?: "unknown"}")
            val failedFields = if (reportState.verifyOk == false) {
                reportState.verifyFailed.joinToString().ifEmpty { "unknown" }
            } else {
                reportState.verifyFailed.joinToString().ifEmpty { "none" }
            }
            sb.appendLine("  Failed fields: $failedFields")
            sb.appendLine("  nativeReady: ${reportState.nativeReady ?: "unknown"}")
            sb.appendLine("  Legacy SystemProperties field (both overloads registered): ${reportState.systemPropsHooked ?: "unknown"}")
        }
        sb.appendLine()
        sb.appendLine(
            "Build-field context: real values are read in the module app process; target values are derived from the selected profile, not read in the Photos process."
        )
        sb.appendLine("Build fields (real vs spoof target):")
        compareProps(target, real).forEach { check ->
            val marker = if (check.pass) "MATCH" else "DIFF"
            sb.appendLine(
                "  ${check.field}: real=${check.actual ?: "<unavailable>"} " +
                    "target=${check.expected ?: "<none>"} ($marker)"
            )
        }
        return sb.toString()
    }
}
