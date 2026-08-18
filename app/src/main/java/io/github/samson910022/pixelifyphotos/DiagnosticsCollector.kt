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
     * Plain-language interpretation of the recorded hook milestones, mirroring
     * the load/VERIFY signal table documented in SUPPORT.md.
     *
     * Returns one line per observed milestone; an empty state produces a single
     * "no records yet" line.
     */
    fun interpretMilestones(state: HookState): List<String> {
        if (!state.anyRecorded) {
            return listOf(
                "No hook activity recorded yet. Open Google Photos once, wait a few " +
                    "seconds, then reopen this screen."
            )
        }

        val lines = mutableListOf<String>()
        if (state.moduleLoadedAt != null) {
            lines += "Module loaded in a scoped process (${formatTimestamp(state.moduleLoadedAt)})."
        }
        if (state.lastPackageLoaded == null) {
            lines += "Module loaded, but no scoped package was ever seen. Check that " +
                "Google Photos is in the LSPosed module scope, then reboot."
        } else {
            lines += "Package loaded: ${state.lastPackageLoaded} (early device spoof ran here)."
            if (state.lastPackageReady == null) {
                lines += "Package loaded but never became ready — this points to a " +
                    "framework/API compatibility problem in your Xposed variant."
            } else {
                lines += "Package ready: ${state.lastPackageReady} (hooks re-applied)."
            }
        }
        if (state.verifyAt != null) {
            when (state.verifyOk) {
                true -> lines += "Last device-spoof VERIFY passed " +
                    "(nativeReady=${state.nativeReady ?: false})."
                false -> lines += "Last device-spoof VERIFY failed: " +
                    state.verifyFailed.joinToString().ifEmpty { "unknown fields" } +
                    " (nativeReady=${state.nativeReady ?: false}). See logcat tag Pixelify."
                null -> lines += "VERIFY ran but no result was recorded (unexpected)."
            }
        } else if (state.lastPackageReady != null) {
            lines += "Package-ready ran but no VERIFY result was recorded; enable verbose " +
                "logging in Advanced options and retry."
        }
        return lines
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
        val sb = StringBuilder()
        sb.appendLine("Pixelify Infinity diagnostics")
        sb.appendLine("Module active: ${if (moduleActive) "yes" else "no"}")
        sb.appendLine("Scope: ${scope?.joinToString() ?: "unknown (Xposed service not connected)"}")
        sb.appendLine("Selected profile: $selectedDevice")
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