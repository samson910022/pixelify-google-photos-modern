package io.github.samson910022.pixelifyphotos

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import io.github.samson910022.pixelifyphotos.Constants.PREF_DEVICE_TO_SPOOF
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_LAST_PACKAGE_LOADED
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_LAST_PACKAGE_READY
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_LAST_PACKAGE_READY_AT
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_MODULE_LOADED_AT
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_ACTIVE_PACKAGE
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_ACTIVE_RUN_ID
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_VERIFY_AT
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_VERIFY_DEVICE
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_VERIFY_FAILED
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_VERIFY_NATIVE_READY
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_VERIFY_OK
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_VERIFY_PACKAGE
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_VERIFY_SYSPROPS
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_FEATURE_HOOKS_AT
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_FEATURE_HOOK_STRING
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_FEATURE_HOOK_STRING_INT
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_SYSPROPS_HOOKS_AT
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_SYSPROPS_HOOK_GET
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_SYSPROPS_HOOK_GET_DEFAULT
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_FEATURE_INTERCEPTOR_AT
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_SYSPROPS_INTERCEPTOR_AT
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL
import io.github.samson910022.pixelifyphotos.Constants.PREF_USE_CLASSIC_UI
import io.github.samson910022.pixelifyphotos.DiagnosticsCollector.FieldCheck
import io.github.samson910022.pixelifyphotos.DiagnosticsCollector.HookState
import io.github.samson910022.pixelifyphotos.DiagnosticsCollector.MilestoneKind
import io.github.samson910022.pixelifyphotos.DiagnosticsCollector.MilestoneSignal
import io.github.samson910022.pixelifyphotos.DiagnosticsCollector.RegistrationAssessment
import io.github.samson910022.pixelifyphotos.DiagnosticsCollector.RegistrationStatus

/**
 * In-app diagnostics / self-test screen.
 *
 * Surfaces what the module's hook lifecycle looks like in the scoped (Google
 * target process — manager-service connection, lifecycle milestones, individual
 * hook registration outcomes, first interceptor observations, and the last
 * device-spoof Build readback VERIFY — plus this device's real Build values vs
 * the selected profile. A copy button produces a sanitized report for issues.
 */
class DiagnosticsActivity : AppCompatActivity(R.layout.activity_diagnostics) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Deduplicates service-state renders that race Activity resume. */
    private val renderCoordinator = BindRenderCoordinator()

    /** Persistent service-state listener, attached only while this Activity is resumed. */
    @Volatile
    private var serviceStateListener: ((ServiceReadinessSnapshot) -> Unit)? = null

    @Volatile
    private var activityResumed = false

    private var snapshotRefreshPending = false
    private var consistencyRefreshAttempts = 0
    private val consistencyRefresh = Runnable {
        snapshotRefreshPending = false
        if (activityResumed && !isFinishing && !isDestroyed) {
            consistencyRefreshAttempts += 1
            render(resetRefreshBudget = false)
        }
    }

    private fun getPrefs(): SharedPreferences? = PrefUtils.getPrefs(this)

    /** Everything the screen renders, collected once per refresh. */
    private data class Snapshot(
        val serviceConnected: Boolean,
        val serviceReady: Boolean,
        val servicePhase: ServicePhase,
        val serviceStateVersion: Long,
        val consistent: Boolean,
        val scope: List<String>?,
        val selectedDevice: String?,
        val profileIsFallback: Boolean,
        val profileSettingsReadable: Boolean,
        val diagnosticsReadable: Boolean,
        val state: HookState,
        val target: Map<String, String>,
        val real: Map<String, String>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = runCatching { PrefUtils.getPrefs(this) }.getOrNull()
        val themePreference = DiagnosticsUiModePolicy.readClassicUiPreference(
            preferencesAvailable = prefs != null,
            readPreference = { prefs?.getBoolean(PREF_USE_CLASSIC_UI, false) ?: false },
        )
        val isClassicUi = themePreference.available && themePreference.useClassicUi
        setTheme(
            if (isClassicUi) {
                R.style.Theme_PixelifyGooglePhotos_Classic
            } else {
                R.style.Theme_PixelifyGooglePhotos
            }
        )
        super.onCreate(savedInstanceState)
        val toolbar = findViewById<MaterialToolbar>(R.id.diagnostics_toolbar)
        if (toolbar != null) {
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }
        findViewById<MaterialButton>(R.id.diagnostics_copy_report)?.setOnClickListener {
            copyReport()
        }
    }

    override fun onDestroy() {
        activityResumed = false
        mainHandler.removeCallbacks(consistencyRefresh)
        snapshotRefreshPending = false
        serviceStateListener?.let { App.removeOnServiceStateListener(it) }
        serviceStateListener = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        App.requestServiceConvergenceRetry()
        ensureServiceStateListener()
        render()
    }

    override fun onPause() {
        activityResumed = false
        mainHandler.removeCallbacks(consistencyRefresh)
        snapshotRefreshPending = false
        serviceStateListener?.let { App.removeOnServiceStateListener(it) }
        serviceStateListener = null
        super.onPause()
    }

    private fun ensureServiceStateListener() {
        if (!activityResumed || serviceStateListener != null || isFinishing || isDestroyed) return
        lateinit var listener: (ServiceReadinessSnapshot) -> Unit
        listener = { changedState ->
            mainHandler.post {
                if (!activityResumed || isFinishing || isDestroyed || serviceStateListener !== listener) {
                    return@post
                }
                if (renderCoordinator.shouldRenderState(changedState.version)) {
                    render()
                }
            }
        }
        serviceStateListener = listener
        App.addOnServiceStateListener(listener)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Renders one snapshot. A fresh (non-retry) render resets the consistency-refresh
     * budget and cancels any pending retry, so service-state events and Activity
     * resumes can always restart recovery after the budget was exhausted.
     */
    private fun render(resetRefreshBudget: Boolean = true) {
        if (resetRefreshBudget) {
            consistencyRefreshAttempts = 0
            if (snapshotRefreshPending) {
                mainHandler.removeCallbacks(consistencyRefresh)
                snapshotRefreshPending = false
            }
        }
        val snapshot = collectSnapshot()
        if (!DiagnosticsSnapshotPresentation.mayRenderCapturedData(snapshot.consistent)) {
            renderInconsistentSnapshot()
            scheduleConsistencyRefresh()
            return
        }
        consistencyRefreshAttempts = 0

        findViewById<View>(R.id.diagnostics_connection_banner)?.let { banner ->
            if (snapshot.serviceReady) {
                banner.visibility = View.GONE
            } else {
                banner.visibility = View.VISIBLE
                findViewById<TextView>(R.id.diagnostics_connection_banner_text)?.text = when (snapshot.servicePhase) {
                    ServicePhase.DISCONNECTED -> getString(R.string.diagnostics_connection_banner)
                    ServicePhase.CONNECTED_SYNCING -> getString(R.string.diagnostics_connection_syncing)
                    ServicePhase.READY -> ""
                    ServicePhase.ERROR -> getString(R.string.diagnostics_connection_error)
                }
            }
        }

        findViewById<TextView>(R.id.diagnostics_status_text)?.text = buildString {
            appendLine(
                getString(
                    R.string.diagnostics_module_active,
                    when (snapshot.servicePhase) {
                        ServicePhase.DISCONNECTED -> getString(R.string.diagnostics_service_disconnected)
                        ServicePhase.CONNECTED_SYNCING -> getString(R.string.diagnostics_service_syncing)
                        ServicePhase.READY -> getString(R.string.diagnostics_service_ready)
                        ServicePhase.ERROR -> getString(R.string.diagnostics_service_error)
                    }
                )
            )
            append(
                getString(
                    R.string.diagnostics_scope,
                    DiagnosticsScopePresentation.label(
                        snapshot.scope,
                        unknownLabel = getString(R.string.diagnostics_unknown),
                        emptyLabel = getString(R.string.diagnostics_scope_empty),
                    )
                )
            )
            appendLine()
            append(
                getString(
                    R.string.diagnostics_source_availability,
                    getString(
                        if (snapshot.profileSettingsReadable) {
                            R.string.diagnostics_source_available
                        } else {
                            R.string.diagnostics_source_unavailable
                        }
                    ),
                    getString(
                        if (snapshot.state.diagnosticsAvailable) {
                            R.string.diagnostics_source_available
                        } else {
                            R.string.diagnostics_source_unavailable
                        }
                    ),
                )
            )
        }

        val selectedDevice = snapshot.selectedDevice
        val profileLabel = when {
            selectedDevice == null -> getString(R.string.diagnostics_profile_unavailable)
            snapshot.profileIsFallback -> getString(R.string.diagnostics_profile_default, selectedDevice)
            else -> selectedDevice
        }
        findViewById<TextView>(R.id.diagnostics_entitlement_profile)?.text = if (selectedDevice == null) {
            profileLabel
        } else {
            val entitlement = DeviceProps.getBackupEntitlement(selectedDevice)
            getString(
                R.string.diagnostics_entitlement_profile_format,
                profileLabel,
                getString(entitlement.badgeResId),
            )
        }
        findViewById<TextView>(R.id.diagnostics_entitlement_requirement)?.text = if (selectedDevice == null) {
            getString(R.string.diagnostics_profile_unavailable)
        } else {
            getString(DeviceProps.getBackupEntitlement(selectedDevice).descResId)
        }

        val hasUnverifiedRun = snapshot.diagnosticsReadable && snapshot.state.anyRecorded &&
            !DiagnosticsCollector.hasVerifiedOriginIdentity(snapshot.state)
        val milestoneLines = DiagnosticsCollector.milestoneSignals(snapshot.state).map {
            val line = localizedMilestone(it)
            if (hasUnverifiedRun) {
                getString(
                    R.string.diagnostics_unverified_telemetry,
                    "${snapshot.state.activePackage ?: snapshot.state.verifyPackage ?: getString(R.string.diagnostics_unknown)} — $line",
                )
            } else {
                line
            }
        }
        findViewById<TextView>(R.id.diagnostics_milestones_text)?.text =
            milestoneLines.joinToString("\n\n")

        findViewById<TextView>(R.id.diagnostics_registration_text)?.text =
            buildRegistrationText(snapshot.state)

        findViewById<TextView>(R.id.diagnostics_verify_text)?.text =
            buildVerifyText(snapshot.state)

        val container = findViewById<LinearLayout>(R.id.diagnostics_build_container)
        container?.removeAllViews()
        DiagnosticsCollector.compareProps(snapshot.target, snapshot.real).forEach { check ->
            container?.addView(buildFieldRow(check))
        }

        // Record what THIS render displayed, not a fresh re-read: readiness may
        // transition mid-render, and recording a newer value would wrongly suppress
        // the posted not-ready→ready refresh.
        renderCoordinator.onRendered(snapshot.serviceStateVersion)
    }

    private fun collectSnapshot(): Snapshot {
        val captured = ServiceSnapshotCapture.capture(
            readServiceState = App::serviceReadinessSnapshot,
            readForState = { serviceState ->
                val sources = PrefUtils.diagnosticsSources(this, serviceState)
                val preferenceValues = sources.profileSettings?.let { currentPrefs ->
                    try {
                        val profile = DiagnosticsProfilePolicy.fromPreferenceRead(
                            preferencesAvailable = true,
                            readSavedDeviceName = { currentPrefs.getString(PREF_DEVICE_TO_SPOOF, null) },
                            defaultDeviceName = DeviceProps.defaultDeviceName,
                        )
                        val selectedDevice = profile.deviceName
                        PreferenceValues(
                            profile = profile,
                            target = if (selectedDevice != null) buildTargetMap(currentPrefs, selectedDevice) else emptyMap(),
                        )
                    } catch (_: RuntimeException) {
                        null
                    }
                }
                val profile = preferenceValues?.profile
                    ?: DiagnosticsProfilePolicy.fromPreferences(false, null, DeviceProps.defaultDeviceName)
                val profileSettingsReadable = profile.settingsReadable
                val hookState = DiagnosticsHookStatePolicy.capture(
                    profileSettingsAvailable = profileSettingsReadable,
                    telemetryPreferencesAvailable = sources.telemetry != null,
                    readTelemetry = { readHookState(checkNotNull(sources.telemetry)) },
                )
                Snapshot(
                    serviceConnected = serviceState.connected,
                    serviceReady = serviceState.ready,
                    servicePhase = serviceState.phase,
                    serviceStateVersion = serviceState.version,
                    consistent = false,
                    scope = runCatching { serviceState.service?.scope?.toList() }.getOrNull(),
                    selectedDevice = profile.deviceName,
                    profileIsFallback = profile.isFallback,
                    profileSettingsReadable = profileSettingsReadable,
                    diagnosticsReadable = hookState.diagnosticsAvailable,
                    state = hookState,
                    target = preferenceValues?.target.orEmpty(),
                    real = buildRealMap(preferenceValues?.target.orEmpty().keys),
                )
            },
        )
        return captured.value.copy(consistent = captured.consistent)
    }

    private data class PreferenceValues(
        val profile: DiagnosticsProfile,
        val target: Map<String, String>,
    )

    /** Never paints data read across a service-state transition as current. */
    private fun renderInconsistentSnapshot() {
        val currentState = App.serviceReadinessSnapshot()
        findViewById<View>(R.id.diagnostics_connection_banner)?.apply {
            visibility = View.VISIBLE
            findViewById<TextView>(R.id.diagnostics_connection_banner_text)?.text =
                getString(R.string.diagnostics_snapshot_refreshing)
        }
        findViewById<TextView>(R.id.diagnostics_status_text)?.text = buildString {
            appendLine(
                getString(
                    R.string.diagnostics_module_active,
                    getString(when (currentState.phase) {
                        ServicePhase.DISCONNECTED -> R.string.diagnostics_service_disconnected
                        ServicePhase.CONNECTED_SYNCING -> R.string.diagnostics_service_syncing
                        ServicePhase.READY -> R.string.diagnostics_service_ready
                        ServicePhase.ERROR -> R.string.diagnostics_service_error
                    }),
                )
            )
            append(getString(R.string.diagnostics_scope, getString(R.string.diagnostics_unknown)))
        }
        findViewById<TextView>(R.id.diagnostics_entitlement_profile)?.text =
            getString(R.string.diagnostics_snapshot_refreshing)
        findViewById<TextView>(R.id.diagnostics_entitlement_requirement)?.text =
            getString(R.string.diagnostics_snapshot_refreshing)
        findViewById<TextView>(R.id.diagnostics_milestones_text)?.text =
            getString(R.string.diagnostics_snapshot_refreshing)
        findViewById<TextView>(R.id.diagnostics_registration_text)?.text =
            getString(R.string.diagnostics_snapshot_refreshing)
        findViewById<TextView>(R.id.diagnostics_verify_text)?.text =
            getString(R.string.diagnostics_snapshot_refreshing)
        findViewById<LinearLayout>(R.id.diagnostics_build_container)?.removeAllViews()
    }

    private fun scheduleConsistencyRefresh() {
        if (!activityResumed || snapshotRefreshPending || isFinishing || isDestroyed) return
        val delayMillis = DiagnosticsSnapshotRefreshPolicy.nextRetryDelayMillis(consistencyRefreshAttempts)
            ?: return
        snapshotRefreshPending = true
        mainHandler.postDelayed(consistencyRefresh, delayMillis)
    }

    private fun servicePhaseForReport(phase: ServicePhase): String = when (phase) {
        ServicePhase.DISCONNECTED -> "disconnected"
        ServicePhase.CONNECTED_SYNCING -> "connected/syncing"
        ServicePhase.READY -> "ready"
        ServicePhase.ERROR -> "connected, sync error"
    }

    private fun readHookState(prefs: SharedPreferences?): HookState {
        if (prefs == null) return HookState(diagnosticsAvailable = false)
        val currentBootCount = runCatching {
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
                .takeIf { it >= 0 }
        }.getOrNull()
        return DiagnosticsHookStateSnapshot.capture(
            readAll = { prefs.all },
            currentBootCount = currentBootCount,
            currentBootEpoch = DiagnosticsBootEpoch.currentHash(),
        )
    }

    /**
     * The expected spoof values for the selected profile, mirroring the
     * resolution logic in [DeviceSpoofer.hook].
     */
    private fun buildTargetMap(prefs: SharedPreferences?, deviceName: String): Map<String, String> {
        val device = DeviceProps.getDeviceProps(deviceName) ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        out.putAll(device.props)
        val followDevice = prefs?.getBoolean(PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, false) ?: false
        val androidVersion = if (followDevice) {
            device.androidVersion
        } else {
            prefs?.getString(PREF_SPOOF_ANDROID_VERSION_MANUAL, null)
                ?.let { DeviceProps.getAndroidVersionFromLabel(it) }
        }
        androidVersion?.getAsMap()?.forEach { (key, value) ->
            out[key] = value.toString()
        }
        return out
    }

    /** Read the real Build values of this device for the requested fields. */
    private fun buildRealMap(fields: Set<String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fields.forEach { field ->
            realBuildValue(field)?.let { out[field] = it }
        }
        return out
    }

    private fun realBuildValue(field: String): String? = runCatching {
        when (field) {
            "BRAND" -> Build.BRAND
            "MANUFACTURER" -> Build.MANUFACTURER
            "DEVICE" -> Build.DEVICE
            "PRODUCT" -> Build.PRODUCT
            "MODEL" -> Build.MODEL
            "FINGERPRINT" -> Build.FINGERPRINT
            "ID" -> Build.ID
            "INCREMENTAL" -> Build.VERSION.INCREMENTAL
            "SECURITY_PATCH" -> Build.VERSION.SECURITY_PATCH
            "TYPE" -> Build.TYPE
            "TAGS" -> Build.TAGS
            "RELEASE" -> Build.VERSION.RELEASE
            "SDK" -> Build.VERSION.SDK_INT.toString()
            "SDK_INT" -> Build.VERSION.SDK_INT.toString()
            else -> null
        }
    }.getOrNull()

    private fun buildVerifyText(state: HookState): String {
        if (!state.diagnosticsAvailable) {
            return getString(R.string.diagnostics_data_unavailable)
        }
        if (state.verifyAt == null) {
            return when (DiagnosticsVerifyCardPolicy.noRecordStatus(state)) {
                DiagnosticsVerifyCardPolicy.NoRecordStatus.PRIOR_BOOT ->
                    getString(R.string.diagnostics_verify_no_record_prior_boot)
                DiagnosticsVerifyCardPolicy.NoRecordStatus.BOOT_UNKNOWN ->
                    getString(R.string.diagnostics_verify_no_record_boot_unknown)
                DiagnosticsVerifyCardPolicy.NoRecordStatus.UNVERIFIED_RUN ->
                    getString(R.string.diagnostics_verify_no_verified_run)
                DiagnosticsVerifyCardPolicy.NoRecordStatus.CURRENT_RUN_NOT_READY ->
                    getString(R.string.diagnostics_verify_no_package_ready)
                DiagnosticsVerifyCardPolicy.NoRecordStatus.CURRENT_RUN ->
                    getString(R.string.diagnostics_verify_not_recorded)
                DiagnosticsVerifyCardPolicy.NoRecordStatus.NO_RUN_IDENTITY ->
                    getString(R.string.diagnostics_verify_no_run_identity)
            }
        }
        val trustedRunIdentity = DiagnosticsCollector.hasVerifiedRunIdentity(state)
        val verifiedOrigin = DiagnosticsCollector.hasVerifiedOriginIdentity(state)
        val priorBoot = DiagnosticsCollector.hasStaleBootRunIdentity(state)
        val unverifiedVerify = !verifiedOrigin
        val result = if (priorBoot) {
            getString(R.string.diagnostics_verify_prior_boot)
        } else if (verifiedOrigin && !trustedRunIdentity) {
            getString(R.string.diagnostics_verify_boot_unknown)
        } else if (unverifiedVerify) {
            getString(R.string.diagnostics_verify_unverified)
        } else if (state.verifyAt > System.currentTimeMillis()) {
            getString(R.string.diagnostics_verify_stale)
        } else when (state.verifyOk) {
            true -> getString(R.string.diagnostics_verify_ok)
            false -> getString(
                R.string.diagnostics_verify_failed,
                state.verifyFailed.joinToString().ifEmpty { getString(R.string.diagnostics_unknown) }
            )
            null -> getString(R.string.diagnostics_unknown)
        }
        fun presentValue(value: String?): String = when {
            unverifiedVerify -> getString(
                R.string.diagnostics_unverified_telemetry,
                value ?: getString(R.string.diagnostics_unknown),
            )
            priorBoot -> getString(
                R.string.diagnostics_prior_boot_value,
                value ?: getString(R.string.diagnostics_unknown),
            )
            verifiedOrigin && !trustedRunIdentity -> getString(
                R.string.diagnostics_boot_unknown_value,
                value ?: getString(R.string.diagnostics_unknown),
            )
            else -> value ?: getString(R.string.diagnostics_unknown)
        }
        return buildString {
            if (priorBoot) {
                appendLine(getString(
                    R.string.diagnostics_prior_boot_origin,
                    state.activePackage ?: getString(R.string.diagnostics_unknown),
                ))
            } else if (verifiedOrigin && !trustedRunIdentity) {
                appendLine(getString(R.string.diagnostics_boot_unknown_origin))
            } else if (unverifiedVerify) {
                appendLine(
                    getString(
                        R.string.diagnostics_unverified_telemetry,
                        state.activePackage ?: state.verifyPackage ?: getString(R.string.diagnostics_unknown),
                    )
                )
            }
            appendLine(getString(R.string.diagnostics_verify_result, result))
            appendLine(getString(R.string.diagnostics_verify_device, presentValue(state.verifyDevice)))
            appendLine(
                getString(
                    R.string.diagnostics_verify_package,
                    presentValue(state.verifyPackage),
                )
            )
            appendLine(
                getString(R.string.diagnostics_verify_time, DiagnosticsCollector.formatTimestamp(state.verifyAt))
            )
            appendLine(
                getString(
                    R.string.diagnostics_verify_native,
                    when (state.nativeReady) {
                        true -> getString(R.string.diagnostics_yes)
                        false -> getString(R.string.diagnostics_no)
                        null -> getString(R.string.diagnostics_unknown)
                    }
                )
            )
            append(
                getString(
                    R.string.diagnostics_verify_sysprops,
                    when (state.systemPropsHooked) {
                        true -> getString(R.string.diagnostics_yes)
                        false -> getString(R.string.diagnostics_no)
                        null -> getString(R.string.diagnostics_unknown)
                    }
                )
            )
        }
    }

    private fun buildRegistrationText(state: HookState): String {
        if (!state.diagnosticsAvailable) {
            return getString(R.string.diagnostics_data_unavailable)
        }
        val now = System.currentTimeMillis()
        val model = DiagnosticsCollector.registrationModel(state, now)
        val feature = model.feature
        val sysprops = model.systemProperties
        fun statusLabel(status: RegistrationStatus): String = getString(when (status) {
            RegistrationStatus.UNKNOWN -> R.string.diagnostics_registration_unknown
            RegistrationStatus.CURRENTNESS_UNKNOWN -> R.string.diagnostics_registration_currentness_unknown
            RegistrationStatus.STALE -> R.string.diagnostics_registration_stale
            RegistrationStatus.NONE -> R.string.diagnostics_registration_none
            RegistrationStatus.PARTIAL -> R.string.diagnostics_registration_partial
            RegistrationStatus.ALL -> R.string.diagnostics_registration_all
        })
        fun outcome(value: Boolean?, assessment: RegistrationAssessment): String = getString(
            when {
                assessment.status == RegistrationStatus.STALE ||
                    assessment.status == RegistrationStatus.UNKNOWN ||
                    assessment.status == RegistrationStatus.CURRENTNESS_UNKNOWN ->
                    R.string.diagnostics_registration_unknown
                value == true -> R.string.diagnostics_registration_yes
                value == false -> R.string.diagnostics_registration_no
                else -> R.string.diagnostics_registration_unknown
            }
        )
        fun summary(label: Int, result: RegistrationAssessment): String {
            val counts = if (result.status == RegistrationStatus.UNKNOWN ||
                result.status == RegistrationStatus.CURRENTNESS_UNKNOWN ||
                (result.status == RegistrationStatus.STALE && !result.outcomesRecorded)
            ) {
                getString(R.string.diagnostics_registration_count_unknown)
            } else if (result.status == RegistrationStatus.STALE) {
                getString(R.string.diagnostics_registration_count_stale, result.successful, result.total)
            } else {
                getString(R.string.diagnostics_registration_count, result.successful, result.total)
            }
            return getString(
                label,
                statusLabel(result.status),
                counts,
                DiagnosticsCollector.formatTimestamp(result.recordedAt),
            )
        }
        fun invocation(status: DiagnosticsCollector.InvocationStatus, recordedAt: Long?): String = when (status) {
            DiagnosticsCollector.InvocationStatus.UNKNOWN -> getString(R.string.diagnostics_invocation_unknown)
            DiagnosticsCollector.InvocationStatus.CURRENTNESS_UNKNOWN ->
                getString(R.string.diagnostics_invocation_currentness_unknown)
            DiagnosticsCollector.InvocationStatus.NOT_OBSERVED -> getString(R.string.diagnostics_invocation_not_observed)
            DiagnosticsCollector.InvocationStatus.STALE -> getString(
                R.string.diagnostics_invocation_stale,
                DiagnosticsCollector.formatTimestamp(recordedAt),
            )
            DiagnosticsCollector.InvocationStatus.OBSERVED -> getString(
                R.string.diagnostics_invocation_observed,
                DiagnosticsCollector.formatTimestamp(recordedAt),
            )
        }
        return listOf(
            getString(
                R.string.diagnostics_registration_origin,
                if (DiagnosticsCollector.hasVerifiedRunIdentity(state)) {
                    state.activePackage ?: getString(R.string.diagnostics_unknown)
                } else if (DiagnosticsCollector.hasStaleBootRunIdentity(state)) {
                    getString(
                        R.string.diagnostics_origin_prior_boot,
                        state.activePackage ?: getString(R.string.diagnostics_unknown),
                    )
                } else if (DiagnosticsCollector.hasVerifiedOriginIdentity(state)) {
                    getString(
                        R.string.diagnostics_origin_boot_unknown,
                        state.activePackage ?: getString(R.string.diagnostics_unknown),
                    )
                } else {
                    getString(
                        R.string.diagnostics_origin_unverified,
                        state.activePackage ?: getString(R.string.diagnostics_unknown),
                    )
                },
                state.activeRunId ?: getString(R.string.diagnostics_unknown),
                DiagnosticsCollector.formatTimestamp(state.activeRunStartedAt),
            ),
            getString(
                R.string.diagnostics_process_pid,
                if (DiagnosticsCollector.hasVerifiedRunIdentity(state)) {
                    state.processPid?.toString() ?: getString(R.string.diagnostics_unknown)
                } else if (DiagnosticsCollector.hasStaleBootRunIdentity(state)) {
                    getString(
                        R.string.diagnostics_pid_prior_boot,
                        state.processPid?.toString() ?: getString(R.string.diagnostics_unknown),
                    )
                } else if (DiagnosticsCollector.hasVerifiedOriginIdentity(state)) {
                    getString(R.string.diagnostics_pid_boot_unknown)
                } else {
                    getString(R.string.diagnostics_unverified)
                },
            ),
            summary(R.string.diagnostics_feature_registration, feature),
            getString(R.string.diagnostics_feature_hook_string, outcome(state.featureHookHasSystemFeature, feature)),
            getString(R.string.diagnostics_feature_hook_string_int, outcome(state.featureHookHasSystemFeatureWithFlags, feature)),
            getString(R.string.diagnostics_feature_hook_available, outcome(state.featureHookGetAvailableFeatures, feature)),
            summary(R.string.diagnostics_sysprops_registration, sysprops),
            getString(R.string.diagnostics_sysprops_hook_get, outcome(state.systemPropsHookGet, sysprops)),
            getString(R.string.diagnostics_sysprops_hook_get_default, outcome(state.systemPropsHookGetWithDefault, sysprops)),
            getString(R.string.diagnostics_feature_invocation, invocation(model.featureInvocation, state.featureInterceptorAt)),
            getString(R.string.diagnostics_sysprops_invocation, invocation(model.systemPropertiesInvocation, state.systemPropsInterceptorAt)),
            getString(R.string.diagnostics_registration_caveat),
        ).joinToString("\n")
    }

    private fun buildFieldRow(check: FieldCheck): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4.dp, 0, 4.dp)
        }
        row.addView(
            fieldTextView(check.field, true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            fieldTextView(check.actual ?: getString(R.string.diagnostics_unavailable)),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            fieldTextView(check.expected ?: getString(R.string.diagnostics_none)),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        return row
    }

    private fun fieldTextView(text: String, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            val appearanceAttr = if (bold) {
                com.google.android.material.R.attr.textAppearanceBodyMedium
            } else {
                com.google.android.material.R.attr.textAppearanceBodySmall
            }
            val resolved = android.util.TypedValue()
            if (theme.resolveAttribute(appearanceAttr, resolved, true)) {
                TextViewCompat.setTextAppearance(this, resolved.resourceId)
            }
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun localizedMilestone(signal: MilestoneSignal): String = when (signal.kind) {
        MilestoneKind.NO_RECORD -> getString(
            when {
                signal.priorBootRun -> R.string.diagnostics_milestone_no_record_prior_boot
                signal.currentnessUnknown -> R.string.diagnostics_milestone_no_record_boot_unknown
                else -> R.string.diagnostics_milestone_no_record
            },
        )
        MilestoneKind.DIAGNOSTICS_UNAVAILABLE -> getString(R.string.diagnostics_data_unavailable)
        MilestoneKind.PRIOR_BOOT_RUN -> getString(R.string.diagnostics_milestone_prior_boot)
        MilestoneKind.BOOT_UNKNOWN_RUN -> getString(R.string.diagnostics_milestone_boot_unknown)
        MilestoneKind.MODULE_LOADED ->
            currentnessUnknownMilestone(
                signal,
                getString(R.string.diagnostics_milestone_module_loaded, formatTs(signal.timestamp)),
            )
        MilestoneKind.NO_PACKAGE_LOAD_RECORDED -> getString(
            when {
                signal.priorBootRun -> R.string.diagnostics_milestone_no_package_prior_boot
                signal.currentnessUnknown -> R.string.diagnostics_milestone_no_package_boot_unknown
                else -> R.string.diagnostics_milestone_no_package
            },
        )
        MilestoneKind.PACKAGE_LOADED ->
            currentnessUnknownMilestone(
                signal,
                getString(R.string.diagnostics_milestone_package_loaded, signal.pkg ?: getString(R.string.diagnostics_unknown)),
            )
        MilestoneKind.PACKAGE_NOT_READY -> getString(
            when {
                signal.priorBootRun -> R.string.diagnostics_milestone_package_not_ready_prior_boot
                signal.currentnessUnknown -> R.string.diagnostics_milestone_package_not_ready_boot_unknown
                else -> R.string.diagnostics_milestone_package_not_ready
            },
        )
        MilestoneKind.PACKAGE_READY -> currentnessUnknownMilestone(
            signal,
            getString(
                R.string.diagnostics_milestone_package_ready,
                signal.pkg ?: getString(R.string.diagnostics_unknown),
                formatTs(signal.timestamp),
            ),
        )
        MilestoneKind.VERIFY_OK -> if (signal.clockAnomaly) {
            getString(R.string.diagnostics_milestone_verify_stale, formatTs(signal.timestamp))
        } else
            getString(
                R.string.diagnostics_milestone_verify_ok,
                signal.nativeReady?.toString() ?: getString(R.string.diagnostics_unknown)
            )
        MilestoneKind.VERIFY_FAILED -> if (signal.clockAnomaly) {
            getString(R.string.diagnostics_milestone_verify_stale, formatTs(signal.timestamp))
        } else
            getString(
                R.string.diagnostics_milestone_verify_failed,
                signal.failedFields.joinToString().ifEmpty { getString(R.string.diagnostics_unknown) },
                signal.nativeReady?.toString() ?: getString(R.string.diagnostics_unknown)
            )
        MilestoneKind.VERIFY_UNVERIFIED ->
            getString(R.string.diagnostics_milestone_verify_unverified)
        MilestoneKind.VERIFY_PRIOR_BOOT ->
            getString(R.string.diagnostics_milestone_verify_prior_boot)
        MilestoneKind.VERIFY_BOOT_UNKNOWN ->
            getString(R.string.diagnostics_milestone_verify_boot_unknown)
        MilestoneKind.VERIFY_UNEXPECTED -> if (signal.clockAnomaly) {
            getString(R.string.diagnostics_milestone_verify_stale, formatTs(signal.timestamp))
        } else
            getString(R.string.diagnostics_milestone_verify_unexpected)
        MilestoneKind.VERIFY_NONE_BUT_READY -> getString(
            when {
                signal.priorBootRun -> R.string.diagnostics_milestone_verify_none_prior_boot
                signal.currentnessUnknown -> R.string.diagnostics_milestone_verify_none_boot_unknown
                else -> R.string.diagnostics_milestone_verify_none
            },
        )
    }

    private fun currentnessUnknownMilestone(signal: MilestoneSignal, message: String): String =
        if (signal.currentnessUnknown) {
            getString(R.string.diagnostics_milestone_currentness_unknown_prefix, message)
        } else {
            message
        }

    private fun formatTs(epochMillis: Long?): String = DiagnosticsCollector.formatTimestamp(epochMillis)

    private fun copyReport() {
        val snapshot = collectSnapshot()
        if (!DiagnosticsCopyPolicy.mayCopy(snapshot.consistent)) {
            Toast.makeText(this, R.string.diagnostics_copy_inconsistent, Toast.LENGTH_LONG).show()
            return
        }
        val text = DiagnosticsCollector.formatReportText(
            state = snapshot.state,
            serviceConnected = snapshot.serviceConnected,
            serviceStatus = servicePhaseForReport(snapshot.servicePhase),
            scope = snapshot.scope,
            selectedDevice = snapshot.selectedDevice,
            real = snapshot.real,
            target = snapshot.target,
            profileIsFallback = snapshot.profileIsFallback,
        )
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(this, R.string.diagnostics_copy_failed, Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Pixelify Infinity diagnostics", text))
        Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
