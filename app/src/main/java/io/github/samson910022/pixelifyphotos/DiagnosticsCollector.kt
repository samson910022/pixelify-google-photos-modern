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
     * Snapshot of the module hook lifecycle recorded by [PixelifyModule] and
     * [DeviceSpoofer] in the scoped (Google Photos) process, read back through
     * the shared remote preferences.
     */
    data class HookState(
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
    ) {
        /** True when any hook milestone or VERIFY record exists. */
        val anyRecorded: Boolean
            get() = moduleLoadedAt != null || lastPackageLoaded != null ||
                lastPackageReady != null || verifyAt != null
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
        MODULE_LOADED,
        NO_PACKAGE_SEEN,
        PACKAGE_LOADED,
        PACKAGE_NOT_READY,
        PACKAGE_READY,
        VERIFY_OK,
        VERIFY_FAILED,
        VERIFY_UNEXPECTED,
        VERIFY_NONE_BUT_READY,
    }

    data class MilestoneSignal(
        val kind: MilestoneKind,
        val pkg: String? = null,
        val timestamp: Long? = null,
        val failedFields: List<String> = emptyList(),
        val nativeReady: Boolean? = null,
    )

    /**
     * Derive the ordered list of hook-lifecycle signals from a [HookState].
     * Presentation-free and pure so it can be unit-tested and reused by both the
     * English report and the localized UI.
     */
    fun milestoneSignals(state: HookState): List<MilestoneSignal> {
        if (!state.anyRecorded) {
            return listOf(MilestoneSignal(MilestoneKind.NO_RECORD))
        }

        val signals = mutableListOf<MilestoneSignal>()
        if (state.moduleLoadedAt != null) {
            signals += MilestoneSignal(MilestoneKind.MODULE_LOADED, timestamp = state.moduleLoadedAt)
        }
        if (state.lastPackageLoaded == null) {
            signals += MilestoneSignal(MilestoneKind.NO_PACKAGE_SEEN)
        } else {
            signals += MilestoneSignal(MilestoneKind.PACKAGE_LOADED, pkg = state.lastPackageLoaded)
            if (state.lastPackageReady == null) {
                signals += MilestoneSignal(MilestoneKind.PACKAGE_NOT_READY)
            } else {
                signals += MilestoneSignal(MilestoneKind.PACKAGE_READY, pkg = state.lastPackageReady)
            }
        }
        if (state.verifyAt != null) {
            when (state.verifyOk) {
                true -> signals += MilestoneSignal(MilestoneKind.VERIFY_OK, nativeReady = state.nativeReady)
                false -> signals += MilestoneSignal(
                    MilestoneKind.VERIFY_FAILED,
                    failedFields = state.verifyFailed,
                    nativeReady = state.nativeReady,
                )
                null -> signals += MilestoneSignal(MilestoneKind.VERIFY_UNEXPECTED)
            }
        } else if (state.lastPackageReady != null) {
            signals += MilestoneSignal(MilestoneKind.VERIFY_NONE_BUT_READY)
        }
        return signals
    }

    /**
     * Plain-language (English) interpretation of the recorded hook milestones,
     * mirroring the load/VERIFY signal table documented in SUPPORT.md. Used by
     * the copyable report; the on-screen UI uses [milestoneSignals] with
     * localized strings.
     */
    fun interpretMilestones(state: HookState): List<String> =
        milestoneSignals(state).map { englishMilestone(it) }

    private fun englishMilestone(signal: MilestoneSignal): String = when (signal.kind) {
        MilestoneKind.NO_RECORD ->
            "No hook activity recorded yet. Open Google Photos once, wait a few seconds, then reopen this screen."
        MilestoneKind.MODULE_LOADED ->
            "Module loaded in a scoped process (${formatTimestamp(signal.timestamp)})."
        MilestoneKind.NO_PACKAGE_SEEN ->
            "Module loaded, but no scoped package was ever seen. Check that Google Photos is in the LSPosed module scope, then reboot."
        MilestoneKind.PACKAGE_LOADED ->
            "Package loaded: ${signal.pkg} (early device spoof ran here)."
        MilestoneKind.PACKAGE_NOT_READY ->
            "Package loaded but never became ready — this points to a framework/API compatibility problem in your Xposed variant."
        MilestoneKind.PACKAGE_READY ->
            "Package ready: ${signal.pkg} (hooks re-applied)."
        MilestoneKind.VERIFY_OK ->
            "Last device-spoof VERIFY passed (nativeReady=${signal.nativeReady ?: false})."
        MilestoneKind.VERIFY_FAILED ->
            "Last device-spoof VERIFY failed: " +
                signal.failedFields.joinToString().ifEmpty { "unknown fields" } +
                " (nativeReady=${signal.nativeReady ?: false}). See the copied diagnostics report or enable verbose logging."
        MilestoneKind.VERIFY_UNEXPECTED ->
            "VERIFY ran but no result was recorded (unexpected)."
        MilestoneKind.VERIFY_NONE_BUT_READY ->
            "Package-ready ran but no VERIFY result was recorded; enable verbose logging in Advanced options and retry."
    }

    /** Format an epoch-millis timestamp for diagnostics output. */
    fun formatTimestamp(epochMillis: Long?): String {
        if (epochMillis == null) return "never recorded"
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMillis))
        } catch (_: Throwable) {
            epochMillis.toString()
        }
    }

    /**
     * Build the copyable diagnostics report text (English). All values are
     * passed in so the function stays framework-free and testable.
     */
    fun formatReportText(
        state: HookState,
        moduleActive: Boolean,
        scope: List<String>?,
        selectedDevice: String,
        real: Map<String, String>,
        target: Map<String, String>,
    ): String {
        val entitlement = DeviceProps.getBackupEntitlement(selectedDevice)
        val entitlementSummary = when (entitlement) {
            DeviceProps.BackupEntitlement.UNLIMITED_ORIGINAL ->
                "Unlimited Original Quality (0 quota used)"
            DeviceProps.BackupEntitlement.UNLIMITED_STORAGE_SAVER ->
                "Unlimited Storage Saver only (Requires 'Storage saver' backup quality)"
            DeviceProps.BackupEntitlement.NO_UNLIMITED_STORAGE ->
                "No free unlimited backup (Uploads consume Google Account storage quota)"
        }
        val qualityRequirement = when (entitlement) {
            DeviceProps.BackupEntitlement.UNLIMITED_ORIGINAL ->
                "Original quality or Storage saver (0 bytes quota used)"
            DeviceProps.BackupEntitlement.UNLIMITED_STORAGE_SAVER ->
                "MUST be set to 'Storage saver' in Google Photos settings"
            DeviceProps.BackupEntitlement.NO_UNLIMITED_STORAGE ->
                "None — Google Account storage is consumed for all uploads"
        }
        val sb = StringBuilder()
        sb.appendLine("Pixelify Infinity diagnostics")
        sb.appendLine("Module active: ${if (moduleActive) "yes" else "no"}")
        sb.appendLine("Scope: ${scope?.joinToString() ?: "unknown (Xposed service not connected)"}")
        sb.appendLine("Selected profile: $selectedDevice")
        sb.appendLine("Entitlement tier: $entitlementSummary")
        sb.appendLine("Backup quality requirement: $qualityRequirement")
        sb.appendLine()
        sb.appendLine("Hook milestones:")
        interpretMilestones(state).forEach { sb.appendLine("- $it") }
        sb.appendLine()
        sb.appendLine(
            "Last VERIFY: ${state.verifyAt?.let { formatTimestamp(it) } ?: "never recorded"}"
        )
        if (state.verifyAt != null) {
            sb.appendLine("  Result: ${state.verifyOk?.let { if (it) "OK" else "FAIL" } ?: "unknown"}")
            sb.appendLine("  Device: ${state.verifyDevice ?: "unknown"}")
            sb.appendLine("  Package: ${state.verifyPackage ?: "unknown"}")
            sb.appendLine("  Failed fields: ${state.verifyFailed.joinToString().ifEmpty { "none" }}")
            sb.appendLine("  nativeReady: ${state.nativeReady ?: false}")
            sb.appendLine("  SystemProperties hooked: ${state.systemPropsHooked ?: false}")
        }
        sb.appendLine()
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