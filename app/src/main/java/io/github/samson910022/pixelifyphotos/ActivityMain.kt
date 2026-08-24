package io.github.samson910022.pixelifyphotos

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import io.github.samson910022.pixelifyphotos.Constants.CONF_EXPORT_NAME
import io.github.samson910022.pixelifyphotos.Constants.FIELD_LATEST_VERSION_CODE
import io.github.samson910022.pixelifyphotos.Constants.PREF_DEVICE_TO_SPOOF
import io.github.samson910022.pixelifyphotos.Constants.PREF_ENABLE_VERBOSE_LOGS
import io.github.samson910022.pixelifyphotos.Constants.PREF_FIRST_RUN_COMPLETED
import io.github.samson910022.pixelifyphotos.Constants.PREF_LAST_VERSION
import io.github.samson910022.pixelifyphotos.Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_FEATURES_LIST
import io.github.samson910022.pixelifyphotos.Constants.PREF_USE_CLASSIC_UI
import io.github.samson910022.pixelifyphotos.Constants.RELEASES_URL
import io.github.samson910022.pixelifyphotos.Constants.RELEASES_URL2
import io.github.samson910022.pixelifyphotos.Constants.SUPPORT_URL
import io.github.samson910022.pixelifyphotos.Constants.UPDATE_INFO_URL
import io.github.samson910022.pixelifyphotos.Constants.UPDATE_INFO_URL2
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Main entry-point activity for Pixelify Infinity configuration.
 *
 * Supports both Modern (Material 3) and Classic UI layouts, onboarding flow,
 * device selection, feature customization, configuration import/export, and
 * scoped application force-stopping.
 */
class ActivityMain : AppCompatActivity() {

    companion object {
        private const val TAG = "Pixelify"
        private const val MAX_UPDATE_INFO_BYTES = 64 * 1024

        /**
         * Grace window for the asynchronous LSPosed service binder delivery. On cold
         * start the binder routinely arrives after activity creation, so an immediate
         * "module not enabled" verdict would be a false negative; only report the
         * module as disabled once this window elapses without a bind event. The
         * 3000 ms value is an empirical choice sized against observed cold-start
         * binder latency headroom, not derived from any platform contract.
         */
        private const val SERVICE_BIND_GRACE_TIMEOUT_MS = 3_000L
    }

    private val updateExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val utils by lazy { Utils() }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isClassicUi: Boolean = false

    /** Active cold-start gate, or null when the enabled fast path skipped waiting. */
    private var moduleStateResolver: ModuleStateResolver? = null

    /**
     * Cold-start race guard: the LSPosed service binder is delivered asynchronously
     * after process start, so [isModuleEnabled] can legitimately be false while this
     * activity is being created even though the module IS enabled in LSPosed. The
     * resolver owns listener registration, targeted grace-timeout cancellation, and
     * exactly-once verdict delivery; see [ModuleStateResolver].
     */
    private fun createModuleStateResolver(): ModuleStateResolver = ModuleStateResolver(
        addServiceBoundListener = App::addOnServiceBoundListener,
        removeServiceBoundListener = App::removeOnServiceBoundListener,
        dispatchToMain = { mainHandler.post(it) },
        scheduleGraceTimeout = { mainHandler.postDelayed(it, SERVICE_BIND_GRACE_TIMEOUT_MS) },
        cancelGraceTimeout = { mainHandler.removeCallbacks(it) },
        isHostDisposed = { isFinishing || isDestroyed },
        onResolved = ::onModuleStateResolved,
    )

    /**
     * Get preferences via XposedService remote preferences when available,
     * falling back to MODE_PRIVATE when the service is not connected.
     */
    private fun getPrefs(): SharedPreferences? = PrefUtils.getPrefs(this)

    /**
     * Check if the Xposed module is actually active/enabled in LSPosed.
     */
    private fun isModuleEnabled(): Boolean {
        return App.mService != null
    }

    /**
     * Display a snackbar prompting the user to force-stop Google Photos so
     * that updated spoofing preferences take effect.
     */
    private fun showRebootSnack() {
        if (!isModuleEnabled() || isFinishing || isDestroyed) return
        val view = findViewById<View>(if (isClassicUi) R.id.root_view_for_snackbar else R.id.modern_root_coordinator)
            ?: findViewById(android.R.id.content)
            ?: return
        Snackbar.make(view, R.string.please_force_stop_google_photos, Snackbar.LENGTH_SHORT).show()
    }

    /**
     * Animate a transient indicator showing that feature flags have changed.
     *
     * @param textView Optional TextView target to animate.
     */
    private fun peekFeatureFlagsChanged(textView: TextView?) {
        textView?.run {
            alpha = 1.0f
            animate().alpha(0.0f).apply {
                duration = 1000
                startDelay = 3000
            }.start()
        }
    }

    /**
     * ActivityResultLauncher for sub-activities (e.g. FeatureCustomize, AdvancedOptionsActivity).
     * Triggers [showRebootSnack] when returning with [Activity.RESULT_OK].
     */
    private val childActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                showRebootSnack()
            }
        }

    /**
     * Restart the activity without transition animations to apply theme or UI mode changes.
     */
    private fun restartActivity() {
        if (isFinishing || isDestroyed) return
        finish()
        startActivity(Intent(this, ActivityMain::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        })
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /**
     * Activity creation callback. Applies theme, verifies module state, sets up
     * the chosen UI mode, checks onboarding and updates.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        val pref = getPrefs()
        isClassicUi = pref?.getBoolean(PREF_USE_CLASSIC_UI, false) ?: false

        if (isClassicUi) {
            setTheme(R.style.Theme_PixelifyGooglePhotos_Classic)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)
        } else {
            setTheme(R.style.Theme_PixelifyGooglePhotos)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main_modern)
            val toolbar = findViewById<MaterialToolbar>(R.id.top_toolbar)
            if (toolbar != null) {
                setSupportActionBar(toolbar)
            }
        }

        if (!isModuleEnabled()) {
            moduleStateResolver = createModuleStateResolver().also { it.start() }
            return
        }
        initializeUi(pref)
    }

    /**
     * Delivers the resolved verdict: bind within the grace window initializes the
     * full UI; expiry keeps the original blocking "module not enabled" dialog.
     */
    private fun onModuleStateResolved(enabled: Boolean) {
        if (enabled) {
            initializeUi(getPrefs())
        } else {
            showModuleNotEnabledDialog()
        }
    }

    /**
     * Display the non-dismissable "module not enabled" dialog shown only after the
     * bind grace window expires without service delivery.
     */
    private fun showModuleNotEnabledDialog() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.module_not_enabled)
            .setPositiveButton(R.string.close) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    /**
     * Perform full UI initialization once the module state has been positively
     * resolved as enabled.
     *
     * @param pref SharedPreferences instance containing user settings.
     */
    private fun initializeUi(pref: SharedPreferences?) {
        if (isClassicUi) {
            setupClassicUi(pref)
        } else {
            setupModernUi(pref)
        }

        checkFirstRunOnboarding(pref)

        // Check if changelogs need to be shown when upgrading from older version.
        pref?.apply {
            val thisVersion = BuildConfig.VERSION_CODE
            if (getInt(PREF_LAST_VERSION, 0) < thisVersion) {
                showChangeLog()
                edit().apply {
                    putInt(PREF_LAST_VERSION, thisVersion)
                    apply()
                }
            }
        }

        // Check for updates in background thread.
        updateExecutor.execute {
            isUpdateAvailable()?.let { url ->
                if (!isFinishing && !isDestroyed) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            val updateLink = findViewById<TextView>(R.id.update_available_link)
                            updateLink?.apply {
                                paintFlags = Paint.UNDERLINE_TEXT_FLAG
                                visibility = View.VISIBLE
                                setOnClickListener { openWebLink(url) }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Data item for device dropdown / spinner displaying the device name with its entitlement tag.
     */
    private data class DeviceDisplayItem(
        val deviceName: String,
        val displayLabel: String,
    ) {
        override fun toString(): String = displayLabel
    }

    /**
     * Build list of [DeviceDisplayItem] instances with localized entitlement tags for UI selection.
     */
    private fun getDeviceDisplayItems(): List<DeviceDisplayItem> {
        return DeviceProps.allDevices.map { entry ->
            val tag = when (DeviceProps.getBackupEntitlement(entry.deviceName)) {
                DeviceProps.BackupEntitlement.UNLIMITED_ORIGINAL -> getString(R.string.entitlement_tag_original)
                DeviceProps.BackupEntitlement.UNLIMITED_STORAGE_SAVER -> getString(R.string.entitlement_tag_storage_saver)
                DeviceProps.BackupEntitlement.NO_UNLIMITED_STORAGE -> if (entry.deviceName == "None") {
                    getString(R.string.entitlement_tag_none)
                } else {
                    getString(R.string.entitlement_tag_editing_only)
                }
            }
            DeviceDisplayItem(entry.deviceName, "${entry.deviceName} $tag")
        }
    }

    /**
     * Update the entitlement badge and description views based on the selected device.
     */
    private fun updateEntitlementUi(
        deviceName: String,
        badgeView: TextView?,
        descView: TextView?
    ) {
        val entitlement = DeviceProps.getBackupEntitlement(deviceName)
        val (badgeRes, descRes) = when (entitlement) {
            DeviceProps.BackupEntitlement.UNLIMITED_ORIGINAL ->
                Pair(R.string.entitlement_badge_unlimited_original, R.string.entitlement_desc_unlimited_original)
            DeviceProps.BackupEntitlement.UNLIMITED_STORAGE_SAVER ->
                Pair(R.string.entitlement_badge_unlimited_storage_saver, R.string.entitlement_desc_unlimited_storage_saver)
            DeviceProps.BackupEntitlement.NO_UNLIMITED_STORAGE ->
                Pair(R.string.entitlement_badge_no_unlimited, R.string.entitlement_desc_no_unlimited)
        }
        badgeView?.text = getString(badgeRes)
        descView?.text = getString(descRes)
    }

    /**
     * Display a dialog explaining how to verify unlimited backup status and troubleshoot quota issues.
     */
    private fun showVerificationGuideDialog() {
        val message = Html.fromHtml(
            getString(R.string.verification_guide_content_html),
            Html.FROM_HTML_MODE_COMPACT
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.verification_guide_title)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.diagnostics) { _, _ ->
                startActivity(Intent(this, DiagnosticsActivity::class.java))
            }
            .show()
    }

    /**
     * Bind and initialize view elements for the Classic UI layout.
     *
     * @param pref SharedPreferences instance containing user settings.
     */
    private fun setupClassicUi(pref: SharedPreferences?) {
        val resetSettings = findViewById<Button>(R.id.reset_settings)
        val customizeFeatureFlags = findViewById<LinearLayout>(R.id.customize_feature_flags)
        val featureFlagsChanged = findViewById<TextView>(R.id.feature_flags_changed)
        val overrideROMFeatureLevels = findViewById<SwitchCompat>(R.id.override_rom_feature_levels)
        val deviceSpooferSpinner = findViewById<Spinner>(R.id.device_spoofer_spinner)
        val forceStopGooglePhotos = findViewById<Button>(R.id.force_stop_google_photos)
        val openGooglePhotos = findViewById<ImageButton>(R.id.open_google_photos)
        val advancedOptions = findViewById<TextView>(R.id.advanced_options)
        val supportLink = findViewById<TextView>(R.id.support_link)
        val confExport = findViewById<ImageButton>(R.id.conf_export)
        val confImport = findViewById<ImageButton>(R.id.conf_import)
        val classicEntitlementBadge = findViewById<TextView>(R.id.classic_entitlement_badge)
        val classicEntitlementDesc = findViewById<TextView>(R.id.classic_entitlement_desc)
        val classicBtnVerifyGuide = findViewById<TextView>(R.id.classic_btn_verify_guide)

        resetSettings?.setOnClickListener { performResetSettings(pref) }

        overrideROMFeatureLevels?.apply {
            isChecked = pref?.getBoolean(PREF_OVERRIDE_ROM_FEATURE_LEVELS, true) ?: false
            setOnCheckedChangeListener { _, isChecked ->
                pref?.edit()?.run {
                    putBoolean(PREF_OVERRIDE_ROM_FEATURE_LEVELS, isChecked)
                    apply()
                    showRebootSnack()
                }
            }
        }

        val deviceItems = getDeviceDisplayItems()
        val defaultSelection = pref?.getString(PREF_DEVICE_TO_SPOOF, DeviceProps.defaultDeviceName)
            ?: DeviceProps.defaultDeviceName
        updateEntitlementUi(defaultSelection, classicEntitlementBadge, classicEntitlementDesc)

        deviceSpooferSpinner?.apply {
            val aa = ArrayAdapter(this@ActivityMain, android.R.layout.simple_spinner_item, deviceItems)
            aa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            adapter = aa
            val pos = deviceItems.indexOfFirst { it.deviceName == defaultSelection }.coerceAtLeast(0)
            setSelection(pos, false)

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedItem = aa.getItem(position)
                    val deviceName = selectedItem?.deviceName ?: DeviceProps.defaultDeviceName
                    pref?.edit()?.apply {
                        putString(PREF_DEVICE_TO_SPOOF, deviceName)
                        putStringSet(
                            PREF_SPOOF_FEATURES_LIST,
                            DeviceProps.getFeaturesUpToFromDeviceName(deviceName)
                        )
                        apply()
                    }
                    updateEntitlementUi(deviceName, classicEntitlementBadge, classicEntitlementDesc)
                    peekFeatureFlagsChanged(featureFlagsChanged)
                    showRebootSnack()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        classicBtnVerifyGuide?.apply {
            paintFlags = Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { showVerificationGuideDialog() }
        }

        advancedOptions?.apply {
            paintFlags = Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                childActivityLauncher.launch(Intent(this@ActivityMain, AdvancedOptionsActivity::class.java))
            }
        }

        findViewById<TextView>(R.id.diagnostics_link)?.apply {
            paintFlags = Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                startActivity(Intent(this@ActivityMain, DiagnosticsActivity::class.java))
            }
        }

        forceStopGooglePhotos?.setOnClickListener { forceStopScopedApps() }
        openGooglePhotos?.setOnClickListener { openGooglePhotos() }
        customizeFeatureFlags?.setOnClickListener {
            childActivityLauncher.launch(Intent(this, FeatureCustomize::class.java))
        }

        supportLink?.apply {
            paintFlags = Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { openWebLink(SUPPORT_URL) }
        }

        confExport?.setOnClickListener { showExportDialog() }
        confImport?.setOnClickListener { showImportDialog() }
    }

    /**
     * Bind and initialize view elements for the Modern (Material 3) UI layout.
     *
     * @param pref SharedPreferences instance containing user settings.
     */
    private fun setupModernUi(pref: SharedPreferences?) {
        val currentDevice = pref?.getString(PREF_DEVICE_TO_SPOOF, DeviceProps.defaultDeviceName)
            ?: DeviceProps.defaultDeviceName

        val statusProfile = findViewById<TextView>(R.id.modern_status_profile)
        statusProfile?.text = getString(R.string.device_spoofed_active, currentDevice)

        val modernEntitlementBadge = findViewById<TextView>(R.id.modern_entitlement_badge)
        val modernEntitlementDesc = findViewById<TextView>(R.id.modern_entitlement_desc)
        updateEntitlementUi(currentDevice, modernEntitlementBadge, modernEntitlementDesc)

        findViewById<View>(R.id.modern_btn_verify_guide)?.setOnClickListener {
            showVerificationGuideDialog()
        }

        val deviceItems = getDeviceDisplayItems()
        val autoCompleteDevice = findViewById<AutoCompleteTextView>(R.id.auto_complete_device)
        autoCompleteDevice?.apply {
            val adapter = ArrayAdapter(this@ActivityMain, android.R.layout.simple_dropdown_item_1line, deviceItems)
            setAdapter(adapter)
            val currentItem = deviceItems.find { it.deviceName == currentDevice } ?: deviceItems.first()
            setText(currentItem.displayLabel, false)

            setOnItemClickListener { _, _, position, _ ->
                val selectedItem = adapter.getItem(position)
                val selectedDevice = selectedItem?.deviceName ?: DeviceProps.defaultDeviceName
                pref?.edit()?.apply {
                    putString(PREF_DEVICE_TO_SPOOF, selectedDevice)
                    putStringSet(
                        PREF_SPOOF_FEATURES_LIST,
                        DeviceProps.getFeaturesUpToFromDeviceName(selectedDevice)
                    )
                    apply()
                }
                statusProfile?.text = getString(R.string.device_spoofed_active, selectedDevice)
                updateEntitlementUi(selectedDevice, modernEntitlementBadge, modernEntitlementDesc)
                showRebootSnack()
            }
        }

        val btnCustomizeFeatures = findViewById<View>(R.id.modern_btn_customize_features)
        btnCustomizeFeatures?.setOnClickListener {
            childActivityLauncher.launch(Intent(this, FeatureCustomize::class.java))
        }

        val switchOverrideRom = findViewById<MaterialSwitch>(R.id.modern_switch_override_rom)
        switchOverrideRom?.apply {
            isChecked = pref?.getBoolean(PREF_OVERRIDE_ROM_FEATURE_LEVELS, true) ?: false
            setOnCheckedChangeListener { _, isChecked ->
                pref?.edit()?.run {
                    putBoolean(PREF_OVERRIDE_ROM_FEATURE_LEVELS, isChecked)
                    apply()
                    showRebootSnack()
                }
            }
        }

        findViewById<View>(R.id.modern_btn_force_stop)?.setOnClickListener { forceStopScopedApps() }
        findViewById<View>(R.id.modern_btn_open_photos)?.setOnClickListener { openGooglePhotos() }

        findViewById<View>(R.id.modern_item_advanced_options)?.setOnClickListener {
            childActivityLauncher.launch(Intent(this, AdvancedOptionsActivity::class.java))
        }

        findViewById<View>(R.id.modern_item_diagnostics)?.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        findViewById<View>(R.id.modern_item_switch_ui)?.setOnClickListener { toggleUiMode() }
        findViewById<View>(R.id.modern_item_support)?.setOnClickListener { openWebLink(SUPPORT_URL) }
    }

    /**
     * Check if first-run onboarding needs to be presented to the user, providing initial setup guidance.
     *
     * @param pref SharedPreferences instance containing user settings.
     */
    private fun checkFirstRunOnboarding(pref: SharedPreferences?) {
        val firstRunDone = pref?.getBoolean(PREF_FIRST_RUN_COMPLETED, false) ?: false
        if (!firstRunDone && isModuleEnabled()) {
            val message = StringBuilder()
                .append(getString(R.string.onboarding_default_profile_title))
                .append("\n\n")
                .append(Html.fromHtml(getString(R.string.onboarding_default_profile_desc), Html.FROM_HTML_MODE_COMPACT))
                .append("\n\n")
                .append(getString(R.string.onboarding_force_stop_warning_desc))

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.onboarding_title)
                .setMessage(message)
                .setPositiveButton(R.string.onboarding_action_force_stop_and_launch) { _, _ ->
                    pref?.edit()?.putBoolean(PREF_FIRST_RUN_COMPLETED, true)?.apply()
                    forceStopScopedApps()
                    findViewById<View>(android.R.id.content)?.postDelayed({
                        if (!isFinishing && !isDestroyed) {
                            openGooglePhotos()
                        }
                    }, 500)
                }
                .setNegativeButton(R.string.onboarding_action_got_it) { _, _ ->
                    pref?.edit()?.putBoolean(PREF_FIRST_RUN_COMPLETED, true)?.apply()
                }
                .setCancelable(false)
                .show()
        }
    }

    /**
     * Reset all user configurations back to factory defaults and restart the activity.
     *
     * @param pref SharedPreferences instance to reset.
     */
    private fun performResetSettings(pref: SharedPreferences?) {
        pref?.edit()?.run {
            putString(PREF_DEVICE_TO_SPOOF, DeviceProps.defaultDeviceName)
            putBoolean(PREF_OVERRIDE_ROM_FEATURE_LEVELS, true)
            putStringSet(
                PREF_SPOOF_FEATURES_LIST,
                DeviceProps.defaultFeatures.map { it.displayName }.toSet()
            )
            putBoolean(PREF_ENABLE_VERBOSE_LOGS, false)
            putBoolean(PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, false)
            putString(PREF_SPOOF_ANDROID_VERSION_MANUAL, null)
            apply()
        }
        restartActivity()
    }

    /**
     * Toggle between Modern and Classic UI layouts and restart the activity.
     */
    private fun toggleUiMode() {
        val pref = getPrefs()
        val current = pref?.getBoolean(PREF_USE_CLASSIC_UI, false) ?: false
        pref?.edit()?.putBoolean(PREF_USE_CLASSIC_UI, !current)?.apply()
        Toast.makeText(this, R.string.ui_mode_changed, Toast.LENGTH_SHORT).show()
        restartActivity()
    }

    /**
     * Force-stop all packages within the active LSPosed scope using root privileges.
     */
    private fun forceStopScopedApps() {
        val scopePackages = try {
            val service = App.mService
            if (service == null) {
                Log.w(TAG, "XposedService null while force-stopping; using Photos only")
                null
            } else {
                service.scope?.toSet()
            }
        } catch (_: Throwable) {
            null
        }
        utils.forceStopPackages(SpoofedPackageTracker.packagesToForceStop(scopePackages), this)
    }

    /**
     * Launch Google Photos application.
     */
    private fun openGooglePhotos() {
        utils.openApplication(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, this)
    }

    /**
     * Show a dialog offering options to share or save the exported JSON configuration.
     */
    private fun showExportDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_config)
            .setMessage(R.string.export_config_desc)
            .setPositiveButton(R.string.share) { _, _ -> shareConfFile() }
            .setNegativeButton(R.string.save) { _, _ -> saveConfFile() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Show a confirmation dialog before importing a configuration JSON file.
     */
    private fun showImportDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_config)
            .setMessage(R.string.import_config_desc)
            .setPositiveButton(android.R.string.ok) { _, _ -> importConfFile() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Show the version changelog dialog.
     */
    private fun showChangeLog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.version_head)
            .setMessage(R.string.version_desc)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Inflate options menu.
     */
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_activity_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    /**
     * Prepare dynamic options menu items based on current UI mode.
     */
    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val switchUiItem = menu?.findItem(R.id.menu_switch_ui)
        if (isClassicUi) {
            switchUiItem?.setTitle(R.string.switch_to_modern_ui)
        } else {
            switchUiItem?.setTitle(R.string.switch_to_classic_ui)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    /**
     * Handle options menu item selection.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_export -> showExportDialog()
            R.id.menu_import -> showImportDialog()
            R.id.menu_reset -> {
                val pref = getPrefs()
                performResetSettings(pref)
            }
            R.id.menu_switch_ui -> toggleUiMode()
            R.id.menu_changelog -> showChangeLog()
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Check remote endpoints for new version updates.
     *
     * @return Releases page URL if an update is available, or null if up-to-date or on error.
     */
    private fun isUpdateAvailable(): String? {
        fun getUpdateStatus(url: String): Boolean {
            val baos = ByteArrayOutputStream()
            try {
                val connection = URL(url).openConnection().apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    useCaches = false
                }
                connection.getInputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_UPDATE_INFO_BYTES) return false
                        baos.write(buffer, 0, count)
                    }
                }
                val jsonString = baos.toString(Charsets.UTF_8.name())
                return if (jsonString.isNotBlank()) {
                    val json = JSONObject(jsonString)
                    val remoteVersion = json.getInt(FIELD_LATEST_VERSION_CODE)
                    BuildConfig.VERSION_CODE < remoteVersion
                } else false
            } catch (_: Exception) {
                return false
            }
        }

        return when {
            getUpdateStatus(UPDATE_INFO_URL) -> RELEASES_URL
            getUpdateStatus(UPDATE_INFO_URL2) -> RELEASES_URL2
            else -> null
        }
    }

    /**
     * Open an external web link in the default browser.
     *
     * @param url Web URL string to open.
     */
    fun openWebLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
            })
        } catch (_: Exception) {
            Toast.makeText(this, R.string.unable_to_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Export the current configuration to cache and launch the Android share sheet.
     */
    private fun shareConfFile() {
        val pref = getPrefs()
        try {
            val configDir = File(cacheDir, "config_exports")
            configDir.mkdirs()
            val confFile = File(configDir, CONF_EXPORT_NAME)
            val uriFromFile = Uri.fromFile(confFile)

            confFile.delete()
            utils.writeConfigFile(this, uriFromFile, pref)

            val confFileShareUri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                confFile,
            )

            Intent().run {
                action = Intent.ACTION_SEND
                type = "application/json"
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                putExtra(Intent.EXTRA_STREAM, confFileShareUri)
                startActivity(Intent.createChooser(this, getString(R.string.share_config_file)))
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.share_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launch the Storage Access Framework document creation picker to save configuration JSON.
     */
    private fun saveConfFile() {
        val openIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, CONF_EXPORT_NAME)
        }
        Toast.makeText(this, R.string.select_a_location, Toast.LENGTH_SHORT).show()
        configCreateLauncher.launch(openIntent)
    }

    /**
     * ActivityResultLauncher handling SAF document creation result for configuration export.
     */
    private val configCreateLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { it ->
        val pref = getPrefs()
        try {
            if (it.resultCode == Activity.RESULT_OK) {
                val uri = it.data?.data ?: run {
                    Toast.makeText(this, R.string.read_error, Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                utils.writeConfigFile(this, uri, pref)
                Toast.makeText(this, R.string.export_complete, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.share_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launch the Storage Access Framework document picker to select a configuration JSON for import.
     */
    private fun importConfFile() {
        val openIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        configOpenLauncher.launch(openIntent)
    }

    /**
     * ActivityResultLauncher handling SAF document selection result for configuration import.
     */
    private val configOpenLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { it ->
        val pref = getPrefs()
        try {
            if (it.resultCode == Activity.RESULT_OK) {
                val uri = it.data?.data ?: run {
                    Toast.makeText(this, R.string.read_error, Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                utils.readConfigFile(this, uri, pref)
                Toast.makeText(this, R.string.import_complete, Toast.LENGTH_SHORT).show()
                restartActivity()
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.read_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Activity destroy lifecycle callback. Cancels pending bind resolution and shuts
     * down the background update executor.
     */
    override fun onDestroy() {
        // Targeted teardown: the resolver owns the only runnables this activity
        // posts (grace timeout + marshaled bind resolution). dispose() cancels the
        // timeout token and unregisters its listener by identity; an already
        // dispatched resolution post self-suppresses via the disposed flag. Null
        // when the enabled fast path never started a wait.
        moduleStateResolver?.dispose()
        updateExecutor.shutdownNow()
        super.onDestroy()
    }
}
