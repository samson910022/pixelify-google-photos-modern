package io.github.samson910022.pixelifyphotos

import io.github.samson910022.pixelifyphotos.DiagnosticsCollector.FieldCheck
import io.github.samson910022.pixelifyphotos.DiagnosticsCollector.HookState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DiagnosticsCollector] — the pure report/interpretation logic
 * behind [DiagnosticsActivity]. No Android framework dependencies.
 */
class DiagnosticsCollectorTest {

    // =========================================================================
    // compareProps
    // =========================================================================

    @Test
    fun `compareProps merges expected and actual keys sorted`() {
        val checks = DiagnosticsCollector.compareProps(
            expected = mapOf("MODEL" to "Pixel 8 Pro", "BRAND" to "google"),
            actual = mapOf("BRAND" to "google", "MODEL" to "Pixel 8 Pro", "DEVICE" to "husky"),
        )
        assertEquals(listOf("BRAND", "DEVICE", "MODEL"), checks.map { it.field })
    }

    @Test
    fun `compareProps marks pass when values match`() {
        val checks = DiagnosticsCollector.compareProps(
            expected = mapOf("MODEL" to "Pixel 8 Pro"),
            actual = mapOf("MODEL" to "Pixel 8 Pro"),
        )
        assertEquals(1, checks.size)
        assertTrue(checks[0].pass)
        assertEquals("Pixel 8 Pro", checks[0].expected)
        assertEquals("Pixel 8 Pro", checks[0].actual)
    }

    @Test
    fun `compareProps marks fail when values differ`() {
        val checks = DiagnosticsCollector.compareProps(
            expected = mapOf("MODEL" to "Pixel 8 Pro"),
            actual = mapOf("MODEL" to "Redmi Note 11"),
        )
        assertFalse(checks[0].pass)
    }

    @Test
    fun `compareProps marks fail when expected missing`() {
        val checks = DiagnosticsCollector.compareProps(
            expected = emptyMap(),
            actual = mapOf("MODEL" to "Pixel 8 Pro"),
        )
        assertEquals(null, checks[0].expected)
        assertFalse(checks[0].pass)
    }

    @Test
    fun `compareProps marks fail when actual missing`() {
        val checks = DiagnosticsCollector.compareProps(
            expected = mapOf("FINGERPRINT" to "google/husky/husky:14/UD1A.230803.022/10666019:user/release-keys"),
            actual = emptyMap(),
        )
        assertEquals(null, checks[0].actual)
        assertFalse(checks[0].pass)
    }

    @Test
    fun `compareProps handles identical full maps with all pass`() {
        val props = mapOf(
            "BRAND" to "google",
            "MODEL" to "Pixel 5",
            "DEVICE" to "redfin",
            "RELEASE" to "12",
        )
        val checks = DiagnosticsCollector.compareProps(props, props)
        assertEquals(4, checks.size)
        assertTrue(checks.all { it.pass })
    }

    // =========================================================================
    // HookState.anyRecorded
    // =========================================================================

    @Test
    fun `empty HookState has no records`() {
        assertFalse(HookState().anyRecorded)
    }

    @Test
    fun `HookState with any milestone has records`() {
        assertTrue(HookState(moduleLoadedAt = 1L).anyRecorded)
        assertTrue(HookState(lastPackageLoaded = "com.google.android.apps.photos").anyRecorded)
        assertTrue(HookState(verifyAt = 1L).anyRecorded)
        assertTrue(HookState(lastPackageReady = "com.google.android.apps.photos").anyRecorded)
    }

    // =========================================================================
    // interpretMilestones
    // =========================================================================

    @Test
    fun `empty state produces single no-records line`() {
        val lines = DiagnosticsCollector.interpretMilestones(HookState())
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("No hook activity recorded yet"))
    }

    @Test
    fun `module loaded only points to scope problem`() {
        val lines = DiagnosticsCollector.interpretMilestones(HookState(moduleLoadedAt = 1L))
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("Module loaded"))
        assertTrue(lines[1].contains("no scoped package was ever seen"))
        assertTrue(lines[1].contains("scope"))
    }

    @Test
    fun `package loaded without ready points to framework problem`() {
        val lines = DiagnosticsCollector.interpretMilestones(
            HookState(moduleLoadedAt = 1L, lastPackageLoaded = "com.google.android.apps.photos")
        )
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("Package loaded: com.google.android.apps.photos"))
        assertTrue(lines[2].contains("never became ready"))
        assertTrue(lines[2].contains("framework/API compatibility"))
    }

    @Test
    fun `full happy path reports loaded ready and verify pass`() {
        val lines = DiagnosticsCollector.interpretMilestones(
            HookState(
                moduleLoadedAt = 1L,
                lastPackageLoaded = "com.google.android.apps.photos",
                lastPackageReady = "com.google.android.apps.photos",
                verifyAt = 2L,
                verifyOk = true,
                nativeReady = true,
            )
        )
        assertEquals(4, lines.size)
        assertTrue(lines[0].contains("Module loaded"))
        assertTrue(lines[1].contains("early device spoof"))
        assertTrue(lines[2].contains("Package ready"))
        assertTrue(lines[3].contains("VERIFY passed"))
        assertTrue(lines[3].contains("nativeReady=true"))
    }

    @Test
    fun `verify fail reports failed fields`() {
        val lines = DiagnosticsCollector.interpretMilestones(
            HookState(
                moduleLoadedAt = 1L,
                lastPackageLoaded = "com.google.android.apps.photos",
                lastPackageReady = "com.google.android.apps.photos",
                verifyAt = 2L,
                verifyOk = false,
                verifyFailed = listOf("MODEL(actual=Redmi)", "FINGERPRINT(actual=unknown)"),
                nativeReady = false,
            )
        )
        val failLine = lines.first { it.contains("VERIFY failed") }
        assertTrue(failLine.contains("MODEL(actual=Redmi)"))
        assertTrue(failLine.contains("FINGERPRINT(actual=unknown)"))
        assertTrue(failLine.contains("nativeReady=false"))
        assertTrue(failLine.contains("logcat tag Pixelify"))
    }

    @Test
    fun `verify fail with empty failed list falls back to unknown fields`() {
        val lines = DiagnosticsCollector.interpretMilestones(
            HookState(verifyAt = 2L, verifyOk = false, verifyFailed = emptyList())
        )
        assertTrue(lines.any { it.contains("VERIFY failed") && it.contains("unknown fields") })
    }

    @Test
    fun `package ready without verify record suggests verbose logging`() {
        val lines = DiagnosticsCollector.interpretMilestones(
            HookState(
                moduleLoadedAt = 1L,
                lastPackageLoaded = "com.google.android.apps.photos",
                lastPackageReady = "com.google.android.apps.photos",
            )
        )
        assertTrue(lines.any { it.contains("no VERIFY result was recorded") })
        assertTrue(lines.any { it.contains("verbose logging") })
    }

    @Test
    fun `verify record without package ready is still reported`() {
        val lines = DiagnosticsCollector.interpretMilestones(
            HookState(moduleLoadedAt = 1L, verifyAt = 2L, verifyOk = true)
        )
        assertTrue(lines.any { it.contains("VERIFY passed") })
    }

    @Test
    fun `verify record with null result is reported as unexpected`() {
        val lines = DiagnosticsCollector.interpretMilestones(
            HookState(moduleLoadedAt = 1L, lastPackageLoaded = "com.google.android.apps.photos", verifyAt = 2L, verifyOk = null)
        )
        assertTrue(lines.any { it.contains("no result was recorded (unexpected)") })
    }

    // =========================================================================
    // formatTimestamp
    // =========================================================================

    @Test
    fun `formatTimestamp returns never recorded for null`() {
        assertEquals("never recorded", DiagnosticsCollector.formatTimestamp(null))
    }

    @Test
    fun `formatTimestamp formats a valid epoch in timestamp shape`() {
        // Timezone-independent shape check (SimpleDateFormat uses the JVM default zone).
        val formatted = DiagnosticsCollector.formatTimestamp(1787097600000L)
        assertTrue(
            "Expected timestamp shape, got '$formatted'",
            formatted.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}"""))
        )
    }

    // =========================================================================
    // formatReportText
    // =========================================================================

    @Test
    fun `formatReportText includes status scope profile and milestones`() {
        val report = DiagnosticsCollector.formatReportText(
            state = HookState(),
            moduleActive = true,
            scope = listOf("com.google.android.apps.photos"),
            selectedDevice = "Pixel XL",
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("Module active: yes"))
        assertTrue(report.contains("Scope: com.google.android.apps.photos"))
        assertTrue(report.contains("Selected profile: Pixel XL"))
        assertTrue(report.contains("No hook activity recorded yet"))
        assertTrue(report.contains("Build fields (real vs spoof target):"))
    }

    @Test
    fun `formatReportText reports module inactive and unknown scope`() {
        val report = DiagnosticsCollector.formatReportText(
            state = HookState(),
            moduleActive = false,
            scope = null,
            selectedDevice = "None",
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("Module active: no"))
        assertTrue(report.contains("Scope: unknown"))
    }

    @Test
    fun `formatReportText includes verify details when recorded`() {
        val report = DiagnosticsCollector.formatReportText(
            state = HookState(
                verifyAt = 1L,
                verifyDevice = "Pixel 8 Pro",
                verifyPackage = "com.google.android.apps.photos",
                verifyOk = false,
                verifyFailed = listOf("MODEL(actual=Redmi)"),
                nativeReady = false,
                systemPropsHooked = true,
            ),
            moduleActive = true,
            scope = null,
            selectedDevice = "Pixel 8 Pro",
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("Result: FAIL"))
        assertTrue(report.contains("Device: Pixel 8 Pro"))
        assertTrue(report.contains("Package: com.google.android.apps.photos"))
        assertTrue(report.contains("Failed fields: MODEL(actual=Redmi)"))
        assertTrue(report.contains("nativeReady: false"))
        assertTrue(report.contains("SystemProperties hooked: true"))
    }

    @Test
    fun `formatReportText renders field rows with real and target`() {
        val report = DiagnosticsCollector.formatReportText(
            state = HookState(moduleLoadedAt = 1L, verifyAt = 2L, verifyOk = true),
            moduleActive = true,
            scope = null,
            selectedDevice = "Pixel 5",
            real = mapOf("MODEL" to "Redmi Note 11"),
            target = mapOf("MODEL" to "Pixel 5"),
        )
        assertTrue(report.contains("MODEL: real=Redmi Note 11 target=Pixel 5 (DIFF)"))
        assertTrue(report.contains("Result: OK"))
    }

    @Test
    fun `formatReportText handles unavailable real values gracefully`() {
        val report = DiagnosticsCollector.formatReportText(
            state = HookState(),
            moduleActive = false,
            scope = null,
            selectedDevice = "Pixel 5",
            real = mapOf("MODEL" to "Pixel 5"),
            target = emptyMap(),
        )
        assertTrue(report.contains("MODEL: real=Pixel 5 target=<none> (DIFF)"))
    }

    @Test
    fun `FieldCheck pass semantics`() {
        assertTrue(FieldCheck("A", "x", "x").pass)
        assertFalse(FieldCheck("A", "x", "y").pass)
        assertFalse(FieldCheck("A", null, "x").pass)
        assertFalse(FieldCheck("A", "x", null).pass)
    }
}