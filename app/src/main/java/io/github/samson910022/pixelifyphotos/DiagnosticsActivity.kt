package io.github.samson910022.pixelifyphotos

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_VERIFY_AT
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_VERIFY_DEVICE
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_VERIFY_FAILED
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_VERIFY_NATIVE_READY
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_VERIFY_OK
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_VERIFY_PACKAGE
import io.github.samson910022.pixelifyphotos.Constants.PREF_DIAG_VERIFY_SYSPROPS
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL
import io.github.samson910022.pixelifyphotos.Constants.PREF_USE_CLASSIC_UI
import io.github.samson910022.pixelifyphotos.DiagnosticsCollector.FieldCheck
import io.github.samson910022.pixelifyphotos.DiagnosticsCollector.HookState
import io.github.samson910022.pixelifyphotos.DiagnosticsCollector.MilestoneKind
import io.github.samson910022.pixelifyphotos.DiagnosticsCollector.MilestoneSignal

/**
 * In-app diagnostics / self-test screen.
 *
 * Surfaces what the module's hook lifecycle looks like in the scoped (Google
 * Photos) process — module-loaded / package-loaded / package-ready milestones
 * and the last device-spoof VERIFY outcome — plus the real Build values of
 * this device vs the spoof target of the selected profile. A copy button
 * produces a sanitized report users can paste into an issue.
 */
class DiagnosticsActivity : AppCompatActivity(R.layout.activity_diagnostics) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Deduplicates renders when the bind event races the resume transaction. */
    private val renderCoordinator = BindRenderCoordinator()

    /** Pending bind callback awaiting removal, or null when not registered. */
    private var serviceBoundCallback: (() -> Unit)? = null

    /**
     * The single posted bind-render runnable, tracked for targeted cancellation.
     * Written from the Binder thread — or synchronously on main when a bind race
     * fires the callback inline — and read/cleared on main; [Volatile] makes that
     * cross-thread visibility explicit instead of relying on the Handler message
     * queue. A stale read racing onDestroy remains benign: removeCallbacks would
     * be skipped, and the runnable self-suppresses via its own lifecycle guards.
     */
    @Volatile
    private var postedBindRender: Runnable? = null

    private fun getPrefs(): SharedPreferences? = PrefUtils.getPrefs(this)

    /** Everything the screen renders, collected once per refresh. */
    private data class Snapshot(
        val moduleActive: Boolean,
        val scope: List<String>?,
        val selectedDevice: String,
        val state: HookState,
        val target: Map<String, String>,
        val real: Map<String, String>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = PrefUtils.getPrefs(this)
        val isClassicUi = prefs?.getBoolean(PREF_USE_CLASSIC_UI, false) ?: false
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
        // The Xposed service binder may arrive after this screen renders on cold start;
        // re-render once it binds so the active/scope status cannot stay stale. When the
        // service is already bound, onResume() has just rendered fresh state and no
        // registration is needed.
        if (App.mService == null) {
            val onBound: () -> Unit = {
                val posted = Runnable {
                    postedBindRender = null
                    if (!isFinishing && !isDestroyed && App.mService != null &&
                        !renderCoordinator.skipPostedRender(serviceNowBound = true)
                    ) {
                        render()
                    }
                }
                postedBindRender = posted
                mainHandler.post(posted)
            }
            serviceBoundCallback = onBound
            App.addOnServiceBoundListener(onBound)
        }
    }

    override fun onDestroy() {
        serviceBoundCallback?.let { App.removeOnServiceBoundListener(it) }
        serviceBoundCallback = null
        // Benign race window: a Binder thread may publish a fresh runnable reference
        // just after the read below, so removeCallbacks can miss that newest
        // reference. Safe because onDestroy runs on main — the racing callback
        // posts onto this same looper, so the runnable dispatches only after
        // onDestroy returns and self-suppresses via its lifecycle guards.
        postedBindRender?.let { mainHandler.removeCallbacks(it) }
        postedBindRender = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun render() {
        val snapshot = collectSnapshot()

        findViewById<View>(R.id.diagnostics_connection_banner)?.let { banner ->
            if (snapshot.moduleActive) {
                banner.visibility = View.GONE
            } else {
                banner.visibility = View.VISIBLE
            }
        }

        findViewById<TextView>(R.id.diagnostics_status_text)?.text = buildString {
            appendLine(
                getString(
                    R.string.diagnostics_module_active,
                    if (snapshot.moduleActive) {
                        getString(R.string.diagnostics_yes)
                    } else {
                        getString(R.string.diagnostics_no)
                    }
                )
            )
            append(
                getString(
                    R.string.diagnostics_scope,
                    snapshot.scope?.takeIf { it.isNotEmpty() }?.joinToString()
                        ?: getString(R.string.diagnostics_unknown)
                )
            )
        }

        val entitlement = DeviceProps.getBackupEntitlement(snapshot.selectedDevice)
        val (badgeRes, descRes) = when (entitlement) {
            DeviceProps.BackupEntitlement.UNLIMITED_ORIGINAL ->
                Pair(R.string.entitlement_badge_unlimited_original, R.string.entitlement_desc_unlimited_original)
            DeviceProps.BackupEntitlement.UNLIMITED_STORAGE_SAVER ->
                Pair(R.string.entitlement_badge_unlimited_storage_saver, R.string.entitlement_desc_unlimited_storage_saver)
            DeviceProps.BackupEntitlement.NO_UNLIMITED_STORAGE ->
                Pair(R.string.entitlement_badge_no_unlimited, R.string.entitlement_desc_no_unlimited)
        }
        findViewById<TextView>(R.id.diagnostics_entitlement_profile)?.text =
            getString(R.string.diagnostics_entitlement_profile_format, snapshot.selectedDevice, getString(badgeRes))
        findViewById<TextView>(R.id.diagnostics_entitlement_requirement)?.text =
            getString(descRes)

        val milestoneLines = DiagnosticsCollector.milestoneSignals(snapshot.state).map {
            localizedMilestone(it)
        }
        findViewById<TextView>(R.id.diagnostics_milestones_text)?.text =
            milestoneLines.joinToString("\n\n")

        findViewById<TextView>(R.id.diagnostics_verify_text)?.text =
            buildVerifyText(snapshot.state)

        val container = findViewById<LinearLayout>(R.id.diagnostics_build_container)
        container?.removeAllViews()
        DiagnosticsCollector.compareProps(snapshot.target, snapshot.real).forEach { check ->
            container?.addView(buildFieldRow(check))
        }

        // Record what THIS render displayed, not a fresh re-read: the bind event may
        // land mid-render, and recording a newer value would wrongly suppress the
        // posted unbound→bound refresh.
        renderCoordinator.onRendered(snapshot.moduleActive)
    }

    private fun collectSnapshot(): Snapshot {
        val prefs = getPrefs()
        val moduleActive = App.mService != null
        val scope = runCatching { App.mService?.scope?.toList() }.getOrNull()
        val selectedDevice = prefs?.getString(PREF_DEVICE_TO_SPOOF, DeviceProps.defaultDeviceName)
            ?: DeviceProps.defaultDeviceName
        val state = readHookState(prefs)
        val target = buildTargetMap(prefs, selectedDevice)
        return Snapshot(
            moduleActive = moduleActive,
            scope = scope,
            selectedDevice = selectedDevice,
            state = state,
            target = target,
            real = buildRealMap(target.keys),
        )
    }

    private fun readHookState(prefs: SharedPreferences?): HookState {
        if (prefs == null) return HookState()
        return HookState(
            moduleLoadedAt = prefs.getLong(PREF_DIAG_MODULE_LOADED_AT, 0L).takeIf { it > 0 },
            lastPackageLoaded = prefs.getString(PREF_DIAG_LAST_PACKAGE_LOADED, null),
            lastPackageReady = prefs.getString(PREF_DIAG_LAST_PACKAGE_READY, null),
            lastPackageReadyAt = prefs.getLong(PREF_DIAG_LAST_PACKAGE_READY_AT, 0L).takeIf { it > 0 },
            verifyAt = prefs.getLong(PREF_DIAG_VERIFY_AT, 0L).takeIf { it > 0 },
            verifyDevice = prefs.getString(PREF_DIAG_VERIFY_DEVICE, null),
            verifyPackage = prefs.getString(PREF_DIAG_VERIFY_PACKAGE, null),
            verifyOk = prefs.getBoolean(PREF_DIAG_VERIFY_OK, false).takeIf { prefs.contains(PREF_DIAG_VERIFY_OK) },
            verifyFailed = prefs.getStringSet(PREF_DIAG_VERIFY_FAILED, emptySet())?.toList()
                ?: emptyList(),
            nativeReady = prefs.getBoolean(PREF_DIAG_VERIFY_NATIVE_READY, false)
                .takeIf { prefs.contains(PREF_DIAG_VERIFY_NATIVE_READY) },
            systemPropsHooked = prefs.getBoolean(PREF_DIAG_VERIFY_SYSPROPS, false)
                .takeIf { prefs.contains(PREF_DIAG_VERIFY_SYSPROPS) },
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
            "RELEASE" -> Build.VERSION.RELEASE
            "SDK" -> Build.VERSION.SDK_INT.toString()
            "SDK_INT" -> Build.VERSION.SDK_INT.toString()
            else -> null
        }
    }.getOrNull()

    private fun buildVerifyText(state: HookState): String {
        if (state.verifyAt == null) {
            return if (state.lastPackageReady == null && state.lastPackageLoaded != null) {
                getString(R.string.diagnostics_verify_no_package_ready)
            } else {
                getString(R.string.diagnostics_verify_not_recorded)
            }
        }
        val result = when (state.verifyOk) {
            true -> getString(R.string.diagnostics_verify_ok)
            false -> getString(
                R.string.diagnostics_verify_failed,
                state.verifyFailed.joinToString().ifEmpty { getString(R.string.diagnostics_unknown) }
            )
            null -> getString(R.string.diagnostics_unknown)
        }
        return buildString {
            appendLine(getString(R.string.diagnostics_verify_result, result))
            appendLine(
                getString(
                    R.string.diagnostics_verify_device,
                    state.verifyDevice ?: getString(R.string.diagnostics_unknown)
                )
            )
            appendLine(
                getString(
                    R.string.diagnostics_verify_package,
                    state.verifyPackage ?: getString(R.string.diagnostics_unknown)
                )
            )
            appendLine(
                getString(R.string.diagnostics_verify_time, DiagnosticsCollector.formatTimestamp(state.verifyAt))
            )
            appendLine(
                getString(
                    R.string.diagnostics_verify_native,
                    if (state.nativeReady == true) {
                        getString(R.string.diagnostics_yes)
                    } else {
                        getString(R.string.diagnostics_no)
                    }
                )
            )
            append(
                getString(
                    R.string.diagnostics_verify_sysprops,
                    if (state.systemPropsHooked == true) {
                        getString(R.string.diagnostics_yes)
                    } else {
                        getString(R.string.diagnostics_no)
                    }
                )
            )
        }
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
        MilestoneKind.NO_RECORD -> getString(R.string.diagnostics_milestone_no_record)
        MilestoneKind.MODULE_LOADED ->
            getString(R.string.diagnostics_milestone_module_loaded, formatTs(signal.timestamp))
        MilestoneKind.NO_PACKAGE_SEEN -> getString(R.string.diagnostics_milestone_no_package)
        MilestoneKind.PACKAGE_LOADED ->
            getString(R.string.diagnostics_milestone_package_loaded, signal.pkg ?: getString(R.string.diagnostics_unknown))
        MilestoneKind.PACKAGE_NOT_READY ->
            getString(R.string.diagnostics_milestone_package_not_ready)
        MilestoneKind.PACKAGE_READY ->
            getString(R.string.diagnostics_milestone_package_ready, signal.pkg ?: getString(R.string.diagnostics_unknown))
        MilestoneKind.VERIFY_OK ->
            getString(
                R.string.diagnostics_milestone_verify_ok,
                signal.nativeReady?.toString() ?: getString(R.string.diagnostics_unknown)
            )
        MilestoneKind.VERIFY_FAILED ->
            getString(
                R.string.diagnostics_milestone_verify_failed,
                signal.failedFields.joinToString().ifEmpty { getString(R.string.diagnostics_unknown) },
                signal.nativeReady?.toString() ?: getString(R.string.diagnostics_unknown)
            )
        MilestoneKind.VERIFY_UNEXPECTED ->
            getString(R.string.diagnostics_milestone_verify_unexpected)
        MilestoneKind.VERIFY_NONE_BUT_READY ->
            getString(R.string.diagnostics_milestone_verify_none)
    }

    private fun formatTs(epochMillis: Long?): String = DiagnosticsCollector.formatTimestamp(epochMillis)

    private fun copyReport() {
        val snapshot = collectSnapshot()
        val text = DiagnosticsCollector.formatReportText(
            state = snapshot.state,
            moduleActive = snapshot.moduleActive,
            scope = snapshot.scope,
            selectedDevice = snapshot.selectedDevice,
            real = snapshot.real,
            target = snapshot.target,
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