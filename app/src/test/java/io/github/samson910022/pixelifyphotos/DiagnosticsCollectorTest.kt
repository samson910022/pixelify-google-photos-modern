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

    @Test
    fun `no VERIFY card and copied report qualify prior boot lifecycle without VERIFY`() {
        val state = HookState(
            activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            activeRunId = "prior-run-no-verify",
            activeOriginVerified = true,
            activeRunBootCount = 1,
            currentBootCount = 1,
            activeRunBootEpoch = "prior-epoch",
            currentBootEpoch = "current-epoch",
            moduleLoadedAt = 1_000L,
            lastPackageLoaded = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            lastPackageReady = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            lastPackageReadyAt = 1_100L,
        )

        assertEquals(
            DiagnosticsVerifyCardPolicy.NoRecordStatus.PRIOR_BOOT,
            DiagnosticsVerifyCardPolicy.noRecordStatus(state),
        )
        val signals = DiagnosticsCollector.milestoneSignals(state, now = 2_000L)
        assertTrue(signals.any { it.kind == DiagnosticsCollector.MilestoneKind.PRIOR_BOOT_RUN })
        val noVerify = signals.single { it.kind == DiagnosticsCollector.MilestoneKind.VERIFY_NONE_BUT_READY }
        assertTrue(noVerify.priorBootRun)
        val report = DiagnosticsCollector.formatReportText(
            state = state,
            serviceConnected = false,
            scope = null,
            selectedDevice = null,
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("Last VERIFY (HISTORICAL PRIOR-BOOT, OS-VERIFIED ORIGIN): no VERIFY record"))
        assertTrue(report.contains("does not describe the current run"))
        assertFalse(report.contains("not recorded yet"))
    }

    @Test
    fun `no VERIFY card and copied report qualify unknown boot lifecycle without VERIFY`() {
        val state = HookState(
            activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            activeRunId = "unknown-boot-no-verify",
            activeOriginVerified = true,
            activeRunBootCount = 4,
            currentBootCount = 4,
            activeRunBootEpoch = null,
            currentBootEpoch = null,
            moduleLoadedAt = 1_000L,
            lastPackageLoaded = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            lastPackageReady = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            lastPackageReadyAt = 1_100L,
        )

        assertEquals(
            DiagnosticsVerifyCardPolicy.NoRecordStatus.BOOT_UNKNOWN,
            DiagnosticsVerifyCardPolicy.noRecordStatus(state),
        )
        assertEquals(
            "Legacy persisted identity without an epoch cannot be matched by reused boot count",
            DiagnosticsCollector.BootRelation.UNKNOWN,
            DiagnosticsCollector.bootRelation(state.copy(currentBootEpoch = "current-epoch")),
        )
        val signals = DiagnosticsCollector.milestoneSignals(state, now = 2_000L)
        assertTrue(signals.any { it.kind == DiagnosticsCollector.MilestoneKind.BOOT_UNKNOWN_RUN })
        assertTrue(signals.single { it.kind == DiagnosticsCollector.MilestoneKind.VERIFY_NONE_BUT_READY }.currentnessUnknown)
        val report = DiagnosticsCollector.formatReportText(
            state = state,
            serviceConnected = false,
            scope = null,
            selectedDevice = null,
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("Last VERIFY (current boot unknown): no VERIFY record can be established as current"))
        assertTrue(report.contains("run relation is unknown"))
        assertFalse(report.contains("not recorded yet"))
    }

    @Test
    fun `current epoch no VERIFY remains not recorded yet`() {
        val state = HookState(
            activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            activeRunId = "current-run-no-verify",
            activeOriginVerified = true,
            activeRunBootCount = 1,
            currentBootCount = 1,
            activeRunBootEpoch = "current-epoch",
            currentBootEpoch = "current-epoch",
            moduleLoadedAt = 1_000L,
            lastPackageLoaded = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            lastPackageReady = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
        )

        assertEquals(
            DiagnosticsVerifyCardPolicy.NoRecordStatus.CURRENT_RUN,
            DiagnosticsVerifyCardPolicy.noRecordStatus(state),
        )
        assertTrue(
            "A matching unique epoch remains authoritative if the numeric counter is unavailable",
            DiagnosticsCollector.hasVerifiedRunIdentity(state.copy(activeRunBootCount = null, currentBootCount = null)),
        )
        val report = DiagnosticsCollector.formatReportText(
            state = state,
            serviceConnected = false,
            scope = null,
            selectedDevice = null,
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("Last VERIFY (verified current run): not recorded yet"))
    }

    @Test
    fun `matching boot epoch does not substitute for OS origin identity verification`() {
        val state = HookState(
            activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            activeRunId = "legacy-origin-run",
            activeOriginVerified = false,
            activeRunBootEpoch = "same-epoch",
            currentBootEpoch = "same-epoch",
            verifyAt = 1_000L,
            verifyOk = true,
        )

        assertEquals(DiagnosticsCollector.BootRelation.UNKNOWN, DiagnosticsCollector.bootRelation(state))
        assertFalse(DiagnosticsCollector.hasVerifiedRunIdentity(state))
        assertEquals(
            DiagnosticsCollector.MilestoneKind.VERIFY_UNVERIFIED,
            DiagnosticsCollector.milestoneSignals(state, now = 2_000L).last().kind,
        )
    }

    @Test
    fun `verified success persisted from prior boot is never rendered as current verified`() {
        val state = HookState(
            activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            activeRunId = "boot-n-run",
            activeRunBootCount = 10,
            currentBootCount = 11,
            activeRunBootEpoch = "epoch-10",
            currentBootEpoch = "epoch-11",
            activeOriginVerified = true,
            verifyAt = 1_000L,
            verifyPackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            verifyOk = true,
            featureHooksAt = 1_000L,
            featureHookHasSystemFeature = true,
            featureHookHasSystemFeatureWithFlags = true,
            featureHookGetAvailableFeatures = true,
            featureInterceptorAt = 1_000L,
            processPid = 1234,
        )

        assertTrue(DiagnosticsCollector.hasVerifiedOriginIdentity(state))
        assertFalse(DiagnosticsCollector.hasVerifiedRunIdentity(state))
        assertTrue(DiagnosticsCollector.hasStaleBootRunIdentity(state))
        assertEquals(
            DiagnosticsCollector.MilestoneKind.VERIFY_PRIOR_BOOT,
            DiagnosticsCollector.milestoneSignals(state, now = 2_000L).last().kind,
        )
        assertEquals(
            DiagnosticsCollector.RegistrationStatus.STALE,
            DiagnosticsCollector.registrationModel(state, now = 2_000L).feature.status,
        )
        assertEquals(
            DiagnosticsCollector.InvocationStatus.STALE,
            DiagnosticsCollector.registrationModel(state, now = 2_000L).featureInvocation,
        )
        val report = DiagnosticsCollector.formatReportText(
            state = state,
            serviceConnected = true,
            scope = emptyList(),
            selectedDevice = "None",
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("HISTORICAL (prior-boot, OS-verified origin:"))
        assertTrue(report.contains("Result: unknown (prior-boot verified origin; historical, not current)"))
        assertTrue(report.contains("Feature hooks: stale last report"))
        assertTrue(report.contains("Feature interceptor entered: stale observation"))
        assertFalse(report.contains("UNVERIFIED TELEMETRY"))
        assertFalse(report.contains("Target run origin: UNVERIFIED"))
        assertFalse(report.contains("Result: OK"))
        assertFalse(DiagnosticsCollector.hasVerifiedRunIdentity(state.copy(currentBootCount = null)))
    }

    @Test
    fun `prior boot run without a registration report remains unknown`() {
        val state = HookState(
            activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            activeRunId = "prior-run-without-registration",
            activeOriginVerified = true,
            activeRunBootCount = 10,
            currentBootCount = 11,
            activeRunBootEpoch = "epoch-10",
            currentBootEpoch = "epoch-11",
            verifyAt = 1_000L,
            verifyOk = true,
        )

        val model = DiagnosticsCollector.registrationModel(state, now = 2_000L)
        assertEquals(DiagnosticsCollector.RegistrationStatus.UNKNOWN, model.feature.status)
        assertEquals(DiagnosticsCollector.RegistrationStatus.UNKNOWN, model.systemProperties.status)

        val report = DiagnosticsCollector.formatHookRegistrationText(state, now = 2_000L).joinToString("\n")
        assertTrue(report.contains("Feature hooks: unknown/unavailable"))
        assertTrue(report.contains("SystemProperties hooks: unknown/unavailable"))
        assertFalse(report.contains("stale last report"))
        assertFalse(report.contains("0/3"))
        assertFalse(report.contains("0/2"))
        val copiedReport = DiagnosticsCollector.formatReportText(
            state = state,
            serviceConnected = false,
            scope = null,
            selectedDevice = null,
            real = emptyMap(),
            target = emptyMap(),
        )
        assertFalse(copiedReport.contains("stale last report"))
        assertFalse(copiedReport.contains("0/3"))
    }

    @Test
    fun `prior boot registration report is marked stale`() {
        val state = HookState(
            activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            activeRunId = "prior-run-with-registration",
            activeOriginVerified = true,
            activeRunBootCount = 10,
            currentBootCount = 11,
            activeRunBootEpoch = "epoch-10",
            currentBootEpoch = "epoch-11",
            featureHooksAt = 1_000L,
            featureHookHasSystemFeature = true,
            featureHookHasSystemFeatureWithFlags = true,
            featureHookGetAvailableFeatures = true,
        )

        val model = DiagnosticsCollector.registrationModel(state, now = 2_000L)
        assertEquals(DiagnosticsCollector.RegistrationStatus.STALE, model.feature.status)
        val report = DiagnosticsCollector.formatHookRegistrationText(state, now = 2_000L).joinToString("\n")
        assertTrue(report.contains("Feature hooks: stale last report (3/3 marked registered)"))
    }

    @Test
    fun `OS-verified origin with unavailable boot count is unknown not unverified`() {
        val state = HookState(
            activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            activeRunId = "verified-run-with-unknown-boot",
            activeOriginVerified = true,
            activeRunBootCount = 10,
            currentBootCount = null,
            activeRunBootEpoch = "epoch-10",
            currentBootEpoch = null,
            verifyAt = System.currentTimeMillis(),
            verifyOk = true,
        )

        assertTrue(DiagnosticsCollector.hasVerifiedOriginIdentity(state))
        assertFalse(DiagnosticsCollector.hasVerifiedRunIdentity(state))
        val report = DiagnosticsCollector.formatReportText(
            state = state,
            serviceConnected = true,
            scope = null,
            selectedDevice = "Pixel XL",
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("OS-verified origin, current boot unknown:"))
        assertTrue(report.contains("Result: unknown (OS-verified origin, but current boot was not established)"))
        assertFalse(report.contains("UNVERIFIED TELEMETRY"))
        assertFalse(report.contains("Result: OK"))
    }

    @Test
    fun `verified run with unreadable current boot keeps lifecycle registration invocation and verify historical-unverified`() {
        val state = HookState(
            activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            activeRunId = "verified-run-current-boot-unavailable",
            activeOriginVerified = true,
            activeRunBootCount = 10,
            currentBootCount = null,
            activeRunBootEpoch = "epoch-10",
            currentBootEpoch = null,
            moduleLoadedAt = 900L,
            lastPackageLoaded = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            lastPackageReady = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            lastPackageReadyAt = 1_000L,
            verifyAt = 1_100L,
            verifyPackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            verifyOk = true,
            featureHooksAt = 1_000L,
            featureHookHasSystemFeature = true,
            featureHookHasSystemFeatureWithFlags = true,
            featureHookGetAvailableFeatures = true,
            systemPropsHooksAt = 1_000L,
            systemPropsHookGet = true,
            systemPropsHookGetWithDefault = true,
            featureInterceptorAt = 1_050L,
            systemPropsInterceptorAt = 1_060L,
        )

        assertEquals(DiagnosticsCollector.BootRelation.UNKNOWN, DiagnosticsCollector.bootRelation(state))
        assertTrue(DiagnosticsCollector.hasVerifiedOriginIdentity(state))
        assertFalse(DiagnosticsCollector.hasVerifiedRunIdentity(state))
        assertFalse(DiagnosticsCollector.hasStaleBootRunIdentity(state))
        assertTrue(DiagnosticsCollector.hasUnknownBootRunIdentity(state))

        val milestones = DiagnosticsCollector.milestoneSignals(state, now = 2_000L)
        assertTrue(milestones.any { it.kind == DiagnosticsCollector.MilestoneKind.BOOT_UNKNOWN_RUN })
        assertTrue(milestones.single { it.kind == DiagnosticsCollector.MilestoneKind.MODULE_LOADED }.currentnessUnknown)
        assertTrue(milestones.single { it.kind == DiagnosticsCollector.MilestoneKind.PACKAGE_LOADED }.currentnessUnknown)
        assertTrue(milestones.single { it.kind == DiagnosticsCollector.MilestoneKind.PACKAGE_READY }.currentnessUnknown)
        assertEquals(DiagnosticsCollector.MilestoneKind.VERIFY_BOOT_UNKNOWN, milestones.last().kind)

        val model = DiagnosticsCollector.registrationModel(state, now = 2_000L)
        assertEquals(DiagnosticsCollector.RegistrationStatus.CURRENTNESS_UNKNOWN, model.feature.status)
        assertEquals(DiagnosticsCollector.RegistrationStatus.CURRENTNESS_UNKNOWN, model.systemProperties.status)
        assertEquals(DiagnosticsCollector.InvocationStatus.CURRENTNESS_UNKNOWN, model.featureInvocation)
        assertEquals(DiagnosticsCollector.InvocationStatus.CURRENTNESS_UNKNOWN, model.systemPropertiesInvocation)
        val registration = DiagnosticsCollector.formatHookRegistrationText(state, now = 2_000L).joinToString("\n")
        assertTrue(registration.contains("run currentness unverified"))
        assertFalse(registration.contains("all registered"))
        assertFalse(registration.contains("observed at least once"))

        val report = DiagnosticsCollector.formatReportText(
            state = state,
            serviceConnected = true,
            scope = null,
            selectedDevice = "Pixel XL",
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("OS-verified origin, current boot unknown:"))
        assertTrue(report.contains("Historical/unverified-currentness package-load record:"))
        assertTrue(report.contains("Historical/unverified-currentness package-ready record:"))
        assertTrue(report.contains("Result: unknown (OS-verified origin, but current boot was not established)"))
        assertTrue(report.contains("historical (current boot unknown):"))
        assertFalse(report.contains("UNVERIFIED TELEMETRY"))
        assertFalse(report.contains("not observed yet"))
        assertFalse(report.contains("Result: OK"))
    }

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
        assertTrue(lines[0].contains("No lifecycle milestones have been recorded yet"))
    }

    @Test
    fun `module loaded with no package callback is evidence bounded`() {
        val lines = DiagnosticsCollector.interpretMilestones(HookState(moduleLoadedAt = 1L))
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("Module loaded"))
        assertTrue(lines[1].contains("No package-load callback was recorded for this run"))
        assertTrue(lines[1].contains("does not establish whether a callback occurred"))
    }

    @Test
    fun `package loaded without ready says callback is not observed yet`() {
        val lines = DiagnosticsCollector.interpretMilestones(
            HookState(moduleLoadedAt = 1L, lastPackageLoaded = "com.google.android.apps.photos")
        )
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("Package-load callback observed: com.google.android.apps.photos"))
        assertFalse(lines[1].contains("spoof"))
        assertFalse(lines[1].contains("attempt"))
        assertTrue(lines[2].contains("package-ready callback not observed yet"))
        assertTrue(lines[2].contains("not evidence"))
    }

    @Test
    fun `prior boot lifecycle signals and report use historical missing callback wording`() {
        val state = HookState(
            activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            activeRunId = "prior-lifecycle-run",
            activeOriginVerified = true,
            activeRunBootCount = 10,
            currentBootCount = 11,
            activeRunBootEpoch = "epoch-10",
            currentBootEpoch = "epoch-11",
            moduleLoadedAt = 1_000L,
            lastPackageLoaded = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
        )
        val signals = DiagnosticsCollector.milestoneSignals(state, now = 2_000L)
        val notReadySignal = signals.single { it.kind == DiagnosticsCollector.MilestoneKind.PACKAGE_NOT_READY }
        assertTrue(notReadySignal.priorBootRun)
        val reportLines = DiagnosticsCollector.interpretMilestones(state, now = 2_000L)
        assertTrue(reportLines.any { it.contains("not recorded for that prior-boot run") })
        assertFalse(reportLines.any { it.contains("not observed yet") })

        val readyWithoutVerify = state.copy(lastPackageReady = Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        val noVerifySignal = DiagnosticsCollector.milestoneSignals(readyWithoutVerify, now = 2_000L)
            .single { it.kind == DiagnosticsCollector.MilestoneKind.VERIFY_NONE_BUT_READY }
        assertTrue(noVerifySignal.priorBootRun)
        val noVerifyText = DiagnosticsCollector.interpretMilestones(readyWithoutVerify, now = 2_000L).last()
        assertTrue(noVerifyText.contains("for that prior-boot run"))
        assertFalse(noVerifyText.contains("retry"))

        val noPackageRecord = DiagnosticsCollector.milestoneSignals(
            state.copy(lastPackageLoaded = null),
            now = 2_000L,
        ).single { it.kind == DiagnosticsCollector.MilestoneKind.NO_PACKAGE_LOAD_RECORDED }
        assertTrue(noPackageRecord.priorBootRun)
        assertTrue(
            DiagnosticsCollector.interpretMilestones(state.copy(lastPackageLoaded = null), now = 2_000L)
                .any { it.contains("for that prior-boot run") },
        )

        val noLifecycleRecord = state.copy(moduleLoadedAt = null, lastPackageLoaded = null)
        val noRecord = DiagnosticsCollector.milestoneSignals(noLifecycleRecord, now = 2_000L)
            .single { it.kind == DiagnosticsCollector.MilestoneKind.NO_RECORD }
        assertTrue(noRecord.priorBootRun)
        val noRecordText = DiagnosticsCollector.interpretMilestones(noLifecycleRecord, now = 2_000L).last()
        assertTrue(noRecordText.contains("for this prior-boot run"))
        assertFalse(noRecordText.contains("recorded yet"))
    }

    @Test
    fun `ready-only run reports ready observed and package-load callback not recorded`() {
        val state = HookState(
            moduleLoadedAt = 10L,
            lastPackageReady = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            lastPackageReadyAt = 20L,
        )

        assertEquals(
            listOf(
                DiagnosticsCollector.MilestoneKind.MODULE_LOADED,
                DiagnosticsCollector.MilestoneKind.NO_PACKAGE_LOAD_RECORDED,
                DiagnosticsCollector.MilestoneKind.PACKAGE_READY,
                DiagnosticsCollector.MilestoneKind.VERIFY_NONE_BUT_READY,
            ),
            DiagnosticsCollector.milestoneSignals(state, now = 30L).map { it.kind },
        )
        val lines = DiagnosticsCollector.interpretMilestones(state, now = 30L)
        assertTrue(lines.any { it.contains("No package-load callback was recorded for this run") })
        assertTrue(lines.any { it.contains("Last package-ready callback observed: ${Constants.PACKAGE_NAME_GOOGLE_PHOTOS}") })
    }

    @Test
    fun `full happy path reports loaded ready and verify pass`() {
        val lines = DiagnosticsCollector.interpretMilestones(
            HookState(
                activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                activeRunId = "trusted-run",
                activeOriginVerified = true,
                activeRunBootCount = 1,
                currentBootCount = 1,
                activeRunBootEpoch = "epoch-1",
                currentBootEpoch = "epoch-1",
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
        assertTrue(lines[1].contains("Package-load callback observed: com.google.android.apps.photos"))
        assertFalse(lines[1].contains("attempted"))
        assertTrue(lines[2].contains("Last package-ready callback observed"))
        assertTrue(lines[3].contains("Build-field VERIFY readback passed"))
        assertTrue(lines[3].contains("nativeReady=true"))
    }

    @Test
    fun `verify fail reports failed fields`() {
        val lines = DiagnosticsCollector.interpretMilestones(
            HookState(
                activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                activeRunId = "trusted-run",
                activeOriginVerified = true,
                activeRunBootCount = 1,
                currentBootCount = 1,
                activeRunBootEpoch = "epoch-1",
                currentBootEpoch = "epoch-1",
                moduleLoadedAt = 1L,
                lastPackageLoaded = "com.google.android.apps.photos",
                lastPackageReady = "com.google.android.apps.photos",
                verifyAt = 2L,
                verifyOk = false,
                verifyFailed = listOf("MODEL(actual=Redmi)", "FINGERPRINT(actual=unknown)"),
                nativeReady = false,
            )
        )
        val failLine = lines.first { it.contains("Build-field VERIFY readback failed") }
        assertTrue(failLine.contains("MODEL(actual=Redmi)"))
        assertTrue(failLine.contains("FINGERPRINT(actual=unknown)"))
        assertTrue(failLine.contains("nativeReady=false"))
        assertTrue(failLine.contains("enable verbose logging"))
    }

    @Test
    fun `verify fail with empty failed list falls back to unknown fields`() {
        val lines = DiagnosticsCollector.interpretMilestones(
            HookState(
                activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                activeRunId = "trusted-run",
                activeOriginVerified = true,
                activeRunBootCount = 1,
                currentBootCount = 1,
                activeRunBootEpoch = "epoch-1",
                currentBootEpoch = "epoch-1",
                verifyAt = 2L,
                verifyOk = false,
                verifyFailed = emptyList(),
            )
        )
        assertTrue(lines.any { it.contains("Build-field VERIFY readback failed") && it.contains("unknown fields") })
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
            HookState(
                activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                activeRunId = "trusted-run",
                activeOriginVerified = true,
                activeRunBootCount = 1,
                currentBootCount = 1,
                activeRunBootEpoch = "epoch-1",
                currentBootEpoch = "epoch-1",
                moduleLoadedAt = 1L,
                verifyAt = 2L,
                verifyOk = true,
            )
        )
        assertTrue(lines.any { it.contains("Build-field VERIFY readback passed") })
    }

    @Test
    fun `verify record with null result is reported as unexpected`() {
        val lines = DiagnosticsCollector.interpretMilestones(
            HookState(
                activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                activeRunId = "trusted-run",
                activeOriginVerified = true,
                activeRunBootCount = 1,
                currentBootCount = 1,
                activeRunBootEpoch = "epoch-1",
                currentBootEpoch = "epoch-1",
                moduleLoadedAt = 1L,
                lastPackageLoaded = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                verifyAt = 2L,
                verifyOk = null,
            )
        )
        assertTrue(lines.any { it.contains("no result was recorded (unexpected)") })
    }

    // =========================================================================
    // milestoneSignals (structured, presentation-free)
    // =========================================================================

    @Test
    fun `milestoneSignals empty state yields single NO_RECORD`() {
        val signals = DiagnosticsCollector.milestoneSignals(HookState())
        assertEquals(listOf(DiagnosticsCollector.MilestoneKind.NO_RECORD), signals.map { it.kind })
    }

    @Test
    fun `milestoneSignals full happy path order and payload`() {
        val signals = DiagnosticsCollector.milestoneSignals(
            HookState(
                activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                activeRunId = "trusted-run",
                activeOriginVerified = true,
                activeRunBootCount = 1,
                currentBootCount = 1,
                activeRunBootEpoch = "epoch-1",
                currentBootEpoch = "epoch-1",
                moduleLoadedAt = 1L,
                lastPackageLoaded = "com.google.android.apps.photos",
                lastPackageReady = "com.google.android.apps.photos",
                verifyAt = 2L,
                verifyOk = true,
                nativeReady = true,
            )
        )
        assertEquals(
            listOf(
                DiagnosticsCollector.MilestoneKind.MODULE_LOADED,
                DiagnosticsCollector.MilestoneKind.PACKAGE_LOADED,
                DiagnosticsCollector.MilestoneKind.PACKAGE_READY,
                DiagnosticsCollector.MilestoneKind.VERIFY_OK,
            ),
            signals.map { it.kind }
        )
        assertEquals("com.google.android.apps.photos", signals[1].pkg)
        assertEquals(true, signals[3].nativeReady)
    }

    @Test
    fun `milestoneSignals verify fail carries fields and nativeReady`() {
        val signals = DiagnosticsCollector.milestoneSignals(
            HookState(
                activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                activeRunId = "trusted-run",
                activeOriginVerified = true,
                activeRunBootCount = 1,
                currentBootCount = 1,
                activeRunBootEpoch = "epoch-1",
                currentBootEpoch = "epoch-1",
                moduleLoadedAt = 1L,
                lastPackageLoaded = "com.google.android.apps.photos",
                lastPackageReady = "com.google.android.apps.photos",
                verifyAt = 2L,
                verifyOk = false,
                verifyFailed = listOf("MODEL(actual=Redmi)"),
                nativeReady = false,
            )
        )
        val failed = signals.single { it.kind == DiagnosticsCollector.MilestoneKind.VERIFY_FAILED }
        assertEquals(listOf("MODEL(actual=Redmi)"), failed.failedFields)
        assertEquals(false, failed.nativeReady)
    }

    @Test
    fun `milestoneSignals reports no package-load record for this run`() {
        val signals = DiagnosticsCollector.milestoneSignals(HookState(moduleLoadedAt = 1L))
        assertEquals(
            listOf(
                DiagnosticsCollector.MilestoneKind.MODULE_LOADED,
                DiagnosticsCollector.MilestoneKind.NO_PACKAGE_LOAD_RECORDED,
            ),
            signals.map { it.kind }
        )
    }

    @Test
    fun `milestoneSignals verify none but ready suggests verbose logging`() {
        val signals = DiagnosticsCollector.milestoneSignals(
            HookState(
                moduleLoadedAt = 1L,
                lastPackageLoaded = "com.google.android.apps.photos",
                lastPackageReady = "com.google.android.apps.photos",
            )
        )
        assertEquals(
            DiagnosticsCollector.MilestoneKind.VERIFY_NONE_BUT_READY,
            signals.last().kind
        )
    }

    // =========================================================================
    // Hook registration / runtime state derivation shared by UI and report
    // =========================================================================

    @Test
    fun `registration assessment distinguishes all none and partial outcomes`() {
        val now = 2_000_000_000_000L
        assertEquals(
            DiagnosticsCollector.RegistrationStatus.ALL,
            DiagnosticsCollector.assessRegistrations(now, listOf(true, true, true), now).status,
        )
        assertEquals(
            DiagnosticsCollector.RegistrationStatus.NONE,
            DiagnosticsCollector.assessRegistrations(now, listOf(false, false), now).status,
        )
        assertEquals(
            DiagnosticsCollector.RegistrationStatus.UNKNOWN,
            DiagnosticsCollector.assessRegistrations(now, listOf(false, null), now).status,
        )
        val partial = DiagnosticsCollector.assessRegistrations(now, listOf(true, false, null), now)
        assertEquals(DiagnosticsCollector.RegistrationStatus.PARTIAL, partial.status)
        assertEquals(1, partial.successful)
        assertEquals(3, partial.total)
    }

    @Test
    fun `localized UI registration model maps none all partial and unknown provenance`() {
        val now = 2_000_000_000_000L
        fun model(outcomes: List<Boolean?>, verified: Boolean = true) =
            DiagnosticsCollector.registrationModel(
                HookState(
                    activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                    activeRunId = "ui-model-run",
                    activeOriginVerified = verified,
                    activeRunBootCount = 1,
                    currentBootCount = 1,
                    activeRunBootEpoch = "epoch-1",
                    currentBootEpoch = "epoch-1",
                    featureHooksAt = now,
                    featureHookHasSystemFeature = outcomes[0],
                    featureHookHasSystemFeatureWithFlags = outcomes[1],
                    featureHookGetAvailableFeatures = outcomes[2],
                ),
                now,
            )

        assertEquals(DiagnosticsCollector.RegistrationStatus.NONE, model(listOf(false, false, false)).feature.status)
        assertEquals(DiagnosticsCollector.RegistrationStatus.ALL, model(listOf(true, true, true)).feature.status)
        assertEquals(DiagnosticsCollector.RegistrationStatus.PARTIAL, model(listOf(true, false, null)).feature.status)
        val unverified = model(listOf(true, true, true), verified = false)
        assertEquals(DiagnosticsCollector.RegistrationStatus.UNKNOWN, unverified.feature.status)
        assertEquals(DiagnosticsCollector.InvocationStatus.UNKNOWN, unverified.featureInvocation)
        val verifiedNoInvocation = model(listOf(true, true, true))
        assertEquals(DiagnosticsCollector.InvocationStatus.NOT_OBSERVED, verifiedNoInvocation.featureInvocation)
    }

    @Test
    fun `registration assessment fails closed for unavailable and stale results`() {
        val now = 2_000_000_000_000L
        assertEquals(
            DiagnosticsCollector.RegistrationStatus.UNKNOWN,
            DiagnosticsCollector.assessRegistrations(null, listOf(null, null), now).status,
        )
        assertEquals(
            DiagnosticsCollector.RegistrationStatus.UNKNOWN,
            DiagnosticsCollector.assessRegistrations(now, listOf(null, null), now).status,
        )
        assertEquals(
            DiagnosticsCollector.RegistrationStatus.STALE,
            DiagnosticsCollector.assessRegistrations(1L, listOf(true, true), now).status,
        )
        assertEquals(
            DiagnosticsCollector.RegistrationStatus.STALE,
            DiagnosticsCollector.assessRegistrations(now + 1L, listOf(true, true), now).status,
        )
    }

    @Test
    fun `invocation state separates observed unobserved and stale`() {
        val now = 2_000_000_000_000L
        assertEquals(
            DiagnosticsCollector.InvocationStatus.NOT_OBSERVED,
            DiagnosticsCollector.assessInvocation(null, now),
        )
        assertEquals(
            DiagnosticsCollector.InvocationStatus.OBSERVED,
            DiagnosticsCollector.assessInvocation(now, now),
        )
        assertEquals(
            DiagnosticsCollector.InvocationStatus.STALE,
            DiagnosticsCollector.assessInvocation(1L, now),
        )
        assertEquals(
            DiagnosticsCollector.InvocationStatus.STALE,
            DiagnosticsCollector.assessInvocation(now + 1L, now),
        )
    }

    @Test
    fun `lifecycle-only status does not imply any hook registration`() {
        val state = HookState(
            lastPackageReady = "com.google.android.apps.photos",
            lastPackageReadyAt = 2_000_000_000_000L,
        )
        val report = DiagnosticsCollector.formatReportText(
            state = state,
            serviceConnected = true,
            scope = listOf("com.google.android.apps.photos"),
            selectedDevice = "Pixel XL",
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("Last package-ready callback observed"))
        assertTrue(report.contains("Feature hooks: unknown/unavailable"))
        assertTrue(report.contains("SystemProperties hooks: unknown/unavailable"))
        assertTrue(report.contains("All registrations do not prove Google Photos called a target API"))
        assertFalse(report.contains("overall hook success"))
    }

    @Test
    fun `report and UI derivation share explicit partial and runtime outcomes`() {
        val now = System.currentTimeMillis()
        val state = HookState(
            activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            activeRunId = "report-run",
            activeOriginVerified = true,
            activeRunBootCount = 1,
            currentBootCount = 1,
            activeRunBootEpoch = "epoch-1",
            currentBootEpoch = "epoch-1",
            featureHooksAt = now,
            featureHookHasSystemFeature = true,
            featureHookHasSystemFeatureWithFlags = false,
            featureHookGetAvailableFeatures = true,
            systemPropsHooksAt = now,
            systemPropsHookGet = true,
            systemPropsHookGetWithDefault = false,
            featureInterceptorAt = now,
        )
        val feature = DiagnosticsCollector.assessRegistrations(
            state.featureHooksAt,
            listOf(state.featureHookHasSystemFeature, state.featureHookHasSystemFeatureWithFlags, state.featureHookGetAvailableFeatures),
            now,
        )
        val sysprops = DiagnosticsCollector.assessRegistrations(
            state.systemPropsHooksAt,
            listOf(state.systemPropsHookGet, state.systemPropsHookGetWithDefault),
            now,
        )
        assertEquals(DiagnosticsCollector.RegistrationStatus.PARTIAL, feature.status)
        assertEquals(DiagnosticsCollector.RegistrationStatus.PARTIAL, sysprops.status)

        val report = DiagnosticsCollector.formatHookRegistrationText(state, now).joinToString("\n")
        assertTrue(report.contains("Feature hooks: partial (2/3 registered)"))
        assertTrue(report.contains("hasSystemFeature(String, int): not registered"))
        assertTrue(report.contains("SystemProperties hooks: partial (1/2 registered)"))
        assertTrue(report.contains("Feature interceptor entered: observed at least once"))
        assertTrue(report.contains("SystemProperties interceptor entered: not observed"))
        assertTrue(report.contains("All registrations do not prove Google Photos called a target API"))
    }

    @Test
    fun `formatted registration report distinguishes none all partial and future stale rows`() {
        val now = 2_000_000_000_000L
        fun featureLine(state: HookState) = DiagnosticsCollector.formatHookRegistrationText(
            state.copy(activePackage = state.activePackage ?: Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                activeRunId = state.activeRunId ?: "test-run", activeOriginVerified = true, activeRunBootCount = 1, currentBootCount = 1, activeRunBootEpoch = "epoch-1", currentBootEpoch = "epoch-1"),
            now,
        )
            .first { it.startsWith("Feature hooks:") }

        assertTrue(
            featureLine(HookState(featureHooksAt = now, featureHookHasSystemFeature = false,
                featureHookHasSystemFeatureWithFlags = false, featureHookGetAvailableFeatures = false))
                .contains("none registered (0/3)"),
        )
        assertTrue(
            featureLine(HookState(featureHooksAt = now, featureHookHasSystemFeature = true,
                featureHookHasSystemFeatureWithFlags = true, featureHookGetAvailableFeatures = true))
                .contains("all registered (3/3)"),
        )
        val partialLine = featureLine(HookState(featureHooksAt = now, featureHookHasSystemFeature = true,
                featureHookHasSystemFeatureWithFlags = false, featureHookGetAvailableFeatures = null))
        assertTrue("Unexpected partial line: $partialLine", partialLine.contains("partial (1/3 registered)"))

        val futureReport = DiagnosticsCollector.formatHookRegistrationText(
            HookState(
                activePackage = "com.example.allowed",
                activeRunId = "run-future",
                activeOriginVerified = true,
                activeRunBootCount = 1,
                currentBootCount = 1,
                activeRunBootEpoch = "epoch-1",
                currentBootEpoch = "epoch-1",
                featureHooksAt = now + 1L,
                featureHookHasSystemFeature = true,
                featureHookHasSystemFeatureWithFlags = true,
                featureHookGetAvailableFeatures = true,
                featureInterceptorAt = now + 1L,
            ),
            now,
        ).joinToString("\n")
        assertTrue(futureReport.contains("Registration origin: com.example.allowed (run run-future"))
        assertTrue(futureReport.contains("Feature hooks: stale last report"))
        assertTrue(futureReport.contains("hasSystemFeature(String): unknown (stale run/timestamp)"))
        assertTrue(futureReport.contains("Feature interceptor entered: stale observation"))
    }

    @Test
    fun `new run origin without registrations does not inherit prior result in report`() {
        val state = HookState(
            activePackage = "com.example.allowed",
            activeRunId = "new-run",
            activeRunStartedAt = 2_000_000_000_000L,
            activeOriginVerified = true,
            activeRunBootCount = 1,
            currentBootCount = 1,
            activeRunBootEpoch = "epoch-1",
            currentBootEpoch = "epoch-1",
        )
        val report = DiagnosticsCollector.formatHookRegistrationText(state, 2_000_000_000_000L).joinToString("\n")
        assertTrue(report.contains("Registration origin: com.example.allowed (run new-run"))
        assertTrue(report.contains("Feature hooks: unknown/unavailable"))
        assertTrue(report.contains("SystemProperties hooks: unknown/unavailable"))
    }

    @Test
    fun `registration values without package and run origin are rendered unknown`() {
        val report = DiagnosticsCollector.formatHookRegistrationText(
            HookState(
                featureHooksAt = System.currentTimeMillis(),
                featureHookHasSystemFeature = true,
                featureHookHasSystemFeatureWithFlags = true,
                featureHookGetAvailableFeatures = true,
                featureInterceptorAt = System.currentTimeMillis(),
            ),
            System.currentTimeMillis(),
        ).joinToString("\n")
        assertTrue(report.contains("Registration origin: unknown"))
        assertTrue(report.contains("Feature hooks: unknown/unavailable"))
        assertTrue(report.contains("hasSystemFeature(String): unknown"))
        assertTrue(report.contains("Feature interceptor entered: unknown"))
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

    @Test
    fun `future timestamp is identified as clock anomaly and VERIFY is not fresh`() {
        val future = System.currentTimeMillis() + 60_000L
        assertEquals("future timestamp (clock anomaly; stale)", DiagnosticsCollector.formatTimestamp(future))
        val report = DiagnosticsCollector.formatReportText(
            state = HookState(
                activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                activeRunId = "trusted-run",
                activeOriginVerified = true,
                activeRunBootCount = 1,
                currentBootCount = 1,
                activeRunBootEpoch = "epoch-1",
                currentBootEpoch = "epoch-1",
                verifyAt = future,
                verifyOk = true,
            ),
            serviceConnected = true,
            scope = null,
            selectedDevice = "Pixel XL",
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("Last VERIFY: future timestamp (clock anomaly; stale)"))
        assertTrue(report.contains("Result: unknown (clock anomaly; stale)"))
        assertFalse(report.contains("Result: OK"))
    }

    @Test
    fun `legacy VERIFY without current run identity is unknown and package is unverified`() {
        val state = HookState(
            verifyAt = System.currentTimeMillis() - 1_000L,
            verifyOk = true,
            verifyPackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
        )

        assertEquals(
            DiagnosticsCollector.MilestoneKind.VERIFY_UNVERIFIED,
            DiagnosticsCollector.milestoneSignals(state).last().kind,
        )
        val report = DiagnosticsCollector.formatReportText(
            state = state,
            serviceConnected = false,
            scope = null,
            selectedDevice = "Pixel XL",
            real = emptyMap(),
            target = emptyMap(),
        )

        assertTrue(report.contains("UNVERIFIED TELEMETRY"))
        assertTrue(report.contains("Result: unknown (origin is not OS-verified)"))
        assertTrue(report.contains("Package: UNVERIFIED telemetry: ${Constants.PACKAGE_NAME_GOOGLE_PHOTOS}"))
        assertFalse(report.contains("Result: OK"))
        assertFalse(DiagnosticsCollector.hasVerifiedRunIdentity(state))
    }

    @Test
    fun `token-only provenance downgrades outcomes and distinguishes unknown from not observed`() {
        val now = System.currentTimeMillis()
        val unverified = HookState(
            activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            activeRunId = "token-only-run",
            featureHooksAt = now,
            featureHookHasSystemFeature = true,
            featureHookHasSystemFeatureWithFlags = true,
            featureHookGetAvailableFeatures = true,
            featureInterceptorAt = now,
            processPid = 1234,
        )
        val unknownReport = DiagnosticsCollector.formatHookRegistrationText(unverified, now).joinToString("\n")
        assertTrue(unknownReport.contains("Registration origin: unknown/unverified"))
        assertTrue(unknownReport.contains("OS-observed sender PID (Binder when available): unknown/unverified"))
        assertTrue(unknownReport.contains("Feature hooks: unknown/unavailable"))
        assertTrue(unknownReport.contains("Feature interceptor entered: unknown"))
        assertFalse(unknownReport.contains("all registered"))

        val copiedReport = DiagnosticsCollector.formatReportText(
            state = unverified.copy(
                verifyAt = now,
                verifyDevice = "Pixel XL",
                verifyPackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                verifyOk = true,
            ),
            serviceConnected = true,
            scope = listOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
            selectedDevice = "Pixel XL",
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(copiedReport.contains("Target run origin: UNVERIFIED (reported origin:"))
        assertTrue(copiedReport.contains("UNVERIFIED TELEMETRY (reported package attribution is not OS-verified)"))
        assertTrue(copiedReport.contains("Last VERIFY (UNVERIFIED TELEMETRY)"))
        assertTrue(copiedReport.contains("Result: unknown (origin is not OS-verified)"))
        assertFalse(copiedReport.contains("Photos hooks confirmed"))

        val verifiedNoInvocation = DiagnosticsCollector.formatHookRegistrationText(
            unverified.copy(
                activeOriginVerified = true,
                activeRunBootCount = 1,
                currentBootCount = 1,
                activeRunBootEpoch = "epoch-1",
                currentBootEpoch = "epoch-1",
                processPid = 1234,
                featureInterceptorAt = null,
            ),
            now,
        ).joinToString("\n")
        assertTrue(verifiedNoInvocation.contains("Registration origin: ${Constants.PACKAGE_NAME_GOOGLE_PHOTOS}"))
        assertTrue(verifiedNoInvocation.contains("OS-observed sender PID (Binder when available): 1234"))
        assertTrue(verifiedNoInvocation.contains("Feature interceptor entered: not observed"))
    }

    // =========================================================================
    // formatReportText
    // =========================================================================

    @Test
    fun `formatReportText includes status scope profile and milestones`() {
        val report = DiagnosticsCollector.formatReportText(
            state = HookState(),
            serviceConnected = true,
            scope = listOf("com.google.android.apps.photos"),
            selectedDevice = "Pixel XL",
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("Xposed manager service: connected"))
        assertTrue(report.contains("connection state only; not proof hooks registered"))
        assertTrue(report.contains("Scope: com.google.android.apps.photos"))
        assertTrue(report.contains("Selected profile: Pixel XL"))
        assertTrue(report.contains("real values are read in the module app process"))
        assertTrue(report.contains("not read in the Photos process"))
        assertTrue(report.contains("Entitlement tier: Unlimited Original Quality (0 quota used)"))
        assertTrue(report.contains("Backup quality requirement: Original quality or Storage saver (0 bytes quota used)"))
        assertTrue(report.contains("No lifecycle milestones have been recorded yet"))
        assertTrue(report.contains("Feature hooks: unknown/unavailable"))
        assertTrue(report.contains("Build fields (real vs spoof target):"))
    }

    @Test
    fun `formatReportText reports module inactive and unknown scope`() {
        val report = DiagnosticsCollector.formatReportText(
            state = HookState(),
            serviceConnected = false,
            scope = null,
            selectedDevice = "None",
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("Xposed manager service: disconnected"))
        assertTrue(report.contains("Scope: unknown"))
        assertTrue(report.contains("Entitlement tier: No free unlimited backup (Uploads consume Google Account storage quota)"))
        assertTrue(report.contains("Backup quality requirement: None — Google Account storage is consumed for all uploads"))
    }

    @Test
    fun `formatReportText formats storage saver requirement for Pixel 5`() {
        val report = DiagnosticsCollector.formatReportText(
            state = HookState(),
            serviceConnected = true,
            scope = listOf("com.google.android.apps.photos"),
            selectedDevice = "Pixel 5",
            real = emptyMap(),
            target = emptyMap(),
        )
        assertTrue(report.contains("Selected profile: Pixel 5"))
        assertTrue(report.contains("Entitlement tier: Unlimited Storage Saver only (Requires 'Storage saver' backup quality)"))
        assertTrue(report.contains("Backup quality requirement: MUST be set to 'Storage saver' in Google Photos settings"))
    }

    @Test
    fun `formatReportText includes verify details when recorded`() {
        val now = System.currentTimeMillis()
        val report = DiagnosticsCollector.formatReportText(
            state = HookState(
                activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                activeRunId = "trusted-run",
                activeOriginVerified = true,
                activeRunBootCount = 1,
                currentBootCount = 1,
                activeRunBootEpoch = "epoch-1",
                currentBootEpoch = "epoch-1",
                verifyAt = now,
                verifyDevice = "Pixel 8 Pro",
                verifyPackage = "com.google.android.apps.photos",
                verifyOk = false,
                verifyFailed = listOf("MODEL(actual=Redmi)"),
                nativeReady = false,
                systemPropsHooked = true,
            ),
            serviceConnected = true,
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
        assertTrue(report.contains("Legacy SystemProperties field (both overloads registered): true"))
    }

    @Test
    fun `formatReportText reports unknown failed fields when failed VERIFY list is empty`() {
        val report = DiagnosticsCollector.formatReportText(
            state = HookState(
                activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                activeRunId = "trusted-run",
                activeOriginVerified = true,
                activeRunBootCount = 1,
                currentBootCount = 1,
                activeRunBootEpoch = "epoch-1",
                currentBootEpoch = "epoch-1",
                verifyAt = System.currentTimeMillis(),
                verifyOk = false,
                verifyFailed = emptyList(),
            ),
            serviceConnected = true,
            scope = null,
            selectedDevice = "Pixel 8 Pro",
            real = emptyMap(),
            target = emptyMap(),
        )

        assertTrue(report.contains("Result: FAIL"))
        assertTrue(report.contains("Failed fields: unknown"))
        assertFalse(report.contains("Failed fields: none"))
    }

    @Test
    fun `formatReportText renders field rows with real and target`() {
        val report = DiagnosticsCollector.formatReportText(
            state = HookState(
                activePackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                activeRunId = "trusted-run",
                activeOriginVerified = true,
                activeRunBootCount = 1,
                currentBootCount = 1,
                activeRunBootEpoch = "epoch-1",
                currentBootEpoch = "epoch-1",
                moduleLoadedAt = 1L,
                verifyAt = 2L,
                verifyOk = true,
            ),
            serviceConnected = true,
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
            serviceConnected = false,
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
