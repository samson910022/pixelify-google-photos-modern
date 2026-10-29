package io.github.samson910022.pixelifyphotos

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Html
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
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
import java.lang.ref.WeakReference
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

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

        /**
         * Upper bound on how long a delivered SAF export result may wait for settings access
         * to settle (CONNECTED_SYNCING/ERROR -> READY). Sized above the cold-start binder grace
         * window plus one convergence pass. The one-shot picker URI grant is tied to this
         * Activity, so the wait cannot outlive it; a timeout fails closed like any other
         * unavailable-source export.
         */
        private const val CONFIG_EXPORT_SETTINGS_WAIT_TIMEOUT_MS = 10_000L

        // Saved-state keys for a delivered export result that is still waiting for settings.
        private const val STATE_CONFIG_EXPORT_REQUEST_ID = "config_export_deferred_request_id"
        private const val STATE_CONFIG_EXPORT_JSON = "config_export_deferred_json"
        private const val STATE_CONFIG_EXPORT_CREATED_AT = "config_export_deferred_created_at"
        private const val STATE_CONFIG_EXPORT_SOURCE_EPOCH = "config_export_deferred_source_epoch"
        private const val STATE_CONFIG_EXPORT_SOURCE_VERSION = "config_export_deferred_source_version"
        private const val STATE_CONFIG_EXPORT_SOURCE_PHASE = "config_export_deferred_source_phase"
        private const val STATE_CONFIG_EXPORT_SOURCE_CONNECTED = "config_export_deferred_source_connected"
        private const val STATE_CONFIG_EXPORT_TARGET = "config_export_deferred_target"

        private fun namedThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }
    }

    private val updateExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(namedThreadFactory("pixelify-update"))
    /** Document I/O stays off the main thread and out of the network update probe's queue. */
    private val configImportExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(namedThreadFactory("pixelify-config-import"))
    private val configExportExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(namedThreadFactory("pixelify-config-export"))
    private val utils by lazy { Utils() }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isClassicUi: Boolean = false
    private var initializedServiceStateVersion: Long? = null
    private var mainServiceStateListener: ((ServiceReadinessSnapshot) -> Unit)? = null
    private var readinessDialog: androidx.appcompat.app.AlertDialog? = null
    private var readinessDialogPhase: ServicePhase? = null
    @Volatile private var frameworkBindingObserved = false
    private data class AuthorizedConfigExport(
        val source: ServiceReadinessSnapshot,
        /** Serialized at capture time so later mutation cannot change the SAF snapshot. */
        val json: String,
    ) {
        init {
            require(JSONObject(json).length() > 0)
        }
    }
    private val pendingConfigExport by lazy(LazyThreadSafetyMode.NONE) {
        ConfigExportRequestGate.from(applicationContext)
    }
    private val deferredConfigExportCompletion by lazy(LazyThreadSafetyMode.NONE) {
        ConfigExportDeferredCompletionStore.from(applicationContext)
    }
    private val configCreateLaunchers = mutableMapOf<String, ActivityResultLauncher<Intent>>()
    private val deferredActivityRestart = DeferredActivityRestartGate()
    private var configExportCallbackInProgress = false

    /** A delivered export result whose write waits for the settings source to settle. */
    private data class DeferredConfigExport(
        val request: ConfigExportPendingRequest,
        val target: Uri,
        val deadlineElapsedRealtime: Long,
        /** A settled ERROR is retried at most once; a retry loop would consume the whole budget. */
        var convergenceRetryRequested: Boolean = false,
    )
    private var deferredConfigExport: DeferredConfigExport? = null
    private var configExportWriteInProgress = false
    private val configExportSettingsWaitTimeout = Runnable {
        completeDeferredConfigExport(settingsWaitExpired = true)
    }

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

    private fun canEditSettings(): Boolean =
        App.serviceReadinessSnapshot().let { state ->
            initializedServiceStateVersion == state.version &&
                MainSettingsReadinessPolicy.mayAccessPreferences(
                    state,
                    frameworkBindingObserved || App.hasObservedFrameworkBinding(),
                )
        }

    private fun updateSettings(
        editedKeys: Set<String> = emptySet(),
        operation: (SharedPreferences) -> Boolean,
    ): Boolean {
        var offlinePersistenceUncertain = false
        val updated = App.withGuardedSettingsPreferences(
            this,
            expectedStateVersion = initializedServiceStateVersion,
            editedKeys = editedKeys,
            onOfflinePersistenceUncertain = { offlinePersistenceUncertain = true },
            operation = operation,
        )
        if (!updated && !isFinishing && !isDestroyed) {
            Toast.makeText(this, R.string.settings_write_unavailable, Toast.LENGTH_LONG).show()
        } else if (updated && offlinePersistenceUncertain && !isFinishing && !isDestroyed) {
            Toast.makeText(this, R.string.settings_offline_memory_only, Toast.LENGTH_LONG).show()
        }
        return updated
    }

    private fun updateSettingsControls() {
        val enabled = canEditSettings()
        listOf(
            R.id.reset_settings,
            R.id.override_rom_feature_levels,
            R.id.device_spoofer_spinner,
            R.id.advanced_options,
            R.id.customize_feature_flags,
            R.id.conf_import,
            R.id.auto_complete_device,
            R.id.modern_btn_customize_features,
            R.id.modern_switch_override_rom,
            R.id.modern_item_advanced_options,
            R.id.menu_import,
            R.id.menu_reset,
            R.id.menu_switch_ui,
        ).forEach { id -> findViewById<View>(id)?.isEnabled = enabled }
    }

    /**
     * Check if the Xposed module is actually active/enabled in LSPosed.
     */
    private fun isModuleEnabled(): Boolean {
        return App.isServiceReady()
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
        // Expire a stale cross-process record before deciding to defer; otherwise it can block
        // this restart (and every later one) for the lifetime of a long-lived Activity.
        pendingConfigExport.reconcile(ConfigExportProcessEpoch.current)
        if (!deferredActivityRestart.requestRestart(
                configExportPending = configExportPending(),
                hostActive = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            )
        ) return
        performActivityRestart()
    }

    /**
     * True while finishing the restart now could strand an in-flight SAF result.
     *
     * [deliveredCallbackId] is the callback whose result is being completed, so a restart that
     * belongs to that callback is not blocked by its own now-delivered request.
     */
    private fun configExportPending(deliveredCallbackId: String? = null): Boolean =
        pendingConfigExport.hasAwaitingResult(deliveredCallbackId = deliveredCallbackId) ||
            configExportCallbackInProgress ||
            configImportInFlight ||
            configImportInProgress ||
            deferredConfigExport != null ||
            configExportWriteInProgress

    /**
     * A restart requested while the Activity was stopped is performed here, when a replacement
     * Activity can be launched. Restarting from a background service-state callback would finish
     * this task and then be blocked by background-activity-launch restrictions on Android 10+.
     */
    override fun onStart() {
        super.onStart()
        if (deferredActivityRestart.takeResumedRestart(configExportPending())) {
            performActivityRestart()
        }
    }

    private fun performActivityRestart() {
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
        val initialServiceState = App.serviceReadinessSnapshot()
        frameworkBindingObserved = App.hasObservedFrameworkBinding() || initialServiceState.connected
        val pref = PrefUtils.getPrefs(this, initialServiceState)
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

        // Re-register the durable request's unique key before STARTED so ActivityResultRegistry
        // can restore/deliver an in-flight SAF result after rotation or process recreation.
        // A fresh task has no saved registry mapping, so no result can ever reach a restored key;
        // discard the record instead of creating an orphan that blocks future exports.
        restorePendingConfigExportLauncher(savedStatePresent = savedInstanceState != null)
        ensureMainServiceStateListener()
        restoreDeferredConfigExport(savedInstanceState)
        updateSettingsControls()
        val currentState = App.serviceReadinessSnapshot()
        when (ActivityMainLifecycle.onCreate(currentState, frameworkBindingObserved || App.hasObservedFrameworkBinding())) {
            ActivityMainLifecycle.Action.INITIALIZE_REMOTE -> initializeForReadyService()
            ActivityMainLifecycle.Action.INITIALIZE_LOCAL_AND_WAIT -> {
                initializeForOfflineService()
                moduleStateResolver = createModuleStateResolver().also { it.start() }
            }
            ActivityMainLifecycle.Action.WAIT_FOR_CONNECTION -> {
                showServiceNotReadyDialog(currentState)
                if (currentState.phase == ServicePhase.ERROR) App.requestServiceConvergenceRetry()
            }
            ActivityMainLifecycle.Action.WAIT_FOR_REBIND -> showServiceNotReadyDialog(currentState)
            ActivityMainLifecycle.Action.NO_CHANGE -> Unit
        }
    }

    /**
     * Persists a delivered export result that is still waiting for settings access.
     *
     * The durable request record was already consumed, so this bundle is the only copy. A
     * picker result's URI grant is attached to the Activity record and survives client-process
     * death while the task and its saved state exist, so a recreated process may still finish
     * the write. The completion store prevents a saved copy from writing a result the old
     * process already completed.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val deferred = deferredConfigExport ?: return
        outState.putString(STATE_CONFIG_EXPORT_REQUEST_ID, deferred.request.id)
        outState.putString(STATE_CONFIG_EXPORT_JSON, deferred.request.json)
        outState.putLong(STATE_CONFIG_EXPORT_CREATED_AT, deferred.request.createdAtMillis)
        outState.putString(
            STATE_CONFIG_EXPORT_SOURCE_EPOCH,
            deferred.request.source.processEpoch,
        )
        outState.putLong(
            STATE_CONFIG_EXPORT_SOURCE_VERSION,
            deferred.request.source.serviceVersion,
        )
        outState.putString(
            STATE_CONFIG_EXPORT_SOURCE_PHASE,
            deferred.request.source.servicePhase.name,
        )
        outState.putBoolean(
            STATE_CONFIG_EXPORT_SOURCE_CONNECTED,
            deferred.request.source.connected,
        )
        outState.putString(STATE_CONFIG_EXPORT_TARGET, deferred.target.toString())
    }

    /**
     * Re-arms a deferred export after Activity recreation, including process death.
     *
     * The saved state is re-authorized against the current settings source exactly like a
     * restored picker callback. A durable completion marker suppresses a duplicate write when
     * the old process finished the export just before it died.
     */
    private fun restoreDeferredConfigExport(savedState: Bundle?) {
        if (savedState == null || deferredConfigExport != null) return
        val id = savedState.getString(STATE_CONFIG_EXPORT_REQUEST_ID)
        val json = savedState.getString(STATE_CONFIG_EXPORT_JSON)
        val targetString = savedState.getString(STATE_CONFIG_EXPORT_TARGET)
        val alreadyCompleted = id != null && deferredConfigExportCompletion.isCompleted(id)
        if (ConfigExportDeferredRestorePolicy.decide(
                requestId = id,
                json = json,
                target = targetString,
                alreadyCompleted = alreadyCompleted,
            ) != ConfigExportDeferredRestore.RESTORE
        ) return
        val target = Uri.parse(targetString!!)
        val sourceEpoch = savedState.getString(STATE_CONFIG_EXPORT_SOURCE_EPOCH) ?: return
        val sourcePhase = savedState.getString(STATE_CONFIG_EXPORT_SOURCE_PHASE)
            ?.let { runCatching { ServicePhase.valueOf(it) }.getOrNull() } ?: return
        val request = ConfigExportPendingRequest(
            id = id!!,
            source = ConfigExportSourceStamp(
                processEpoch = sourceEpoch,
                serviceVersion = savedState.getLong(STATE_CONFIG_EXPORT_SOURCE_VERSION),
                servicePhase = sourcePhase,
                connected = savedState.getBoolean(STATE_CONFIG_EXPORT_SOURCE_CONNECTED),
            ),
            json = json!!,
            createdAtMillis = savedState.getLong(STATE_CONFIG_EXPORT_CREATED_AT),
        )
        // The saved absolute deadline belonged to the dead process and is usually already
        // expired, which would reject the restored result before this process had any chance
        // to wait for the manager to settle. A restored picker callback gets a fresh budget,
        // so a restored deferred result must get one too.
        val deferred = DeferredConfigExport(
            request = request,
            target = target,
            deadlineElapsedRealtime =
                SystemClock.elapsedRealtime() + CONFIG_EXPORT_SETTINGS_WAIT_TIMEOUT_MS,
        )
        deferredConfigExport = deferred
        mainHandler.postDelayed(configExportSettingsWaitTimeout, CONFIG_EXPORT_SETTINGS_WAIT_TIMEOUT_MS)
        // Re-decide immediately: the rebuild may already have a settled source.
        completeDeferredConfigExport(settingsWaitExpired = false)
    }

    /** Reconciles the cold-start bind resolver with the current remembered lifecycle state. */
    private fun onModuleStateResolved(enabled: Boolean) {
        val state = App.serviceReadinessSnapshot()
        when (ActivityMainLifecycle.onResolverResolved(
            enabled,
            state,
            frameworkBindingObserved || App.hasObservedFrameworkBinding(),
        )) {
            ActivityMainLifecycle.Action.INITIALIZE_REMOTE -> initializeForReadyService()
            ActivityMainLifecycle.Action.WAIT_FOR_CONNECTION -> {
                showServiceNotReadyDialog(state)
                if (state.phase == ServicePhase.ERROR) App.requestServiceConvergenceRetry()
            }
            ActivityMainLifecycle.Action.WAIT_FOR_REBIND -> showServiceNotReadyDialog(state)
            ActivityMainLifecycle.Action.INITIALIZE_LOCAL_AND_WAIT,
            ActivityMainLifecycle.Action.NO_CHANGE -> Unit
        }
    }

    private fun ensureMainServiceStateListener() {
        if (mainServiceStateListener != null) return
        lateinit var listener: (ServiceReadinessSnapshot) -> Unit
        listener = { observedState ->
            if (observedState.connected || App.hasObservedFrameworkBinding()) frameworkBindingObserved = true
            mainHandler.post {
                if (isFinishing || isDestroyed || mainServiceStateListener !== listener) return@post
                val current = App.serviceReadinessSnapshot()
                frameworkBindingObserved = frameworkBindingObserved || App.hasObservedFrameworkBinding() || current.connected
                // A deferred export must complete before initializeForReadyService() can
                // request an Activity restart, or the one-shot URI grant would be lost.
                completeDeferredConfigExport(settingsWaitExpired = false)
                invalidateOptionsMenu()
                updateSettingsControls()
                when (ActivityMainLifecycle.onServiceStateChanged(current, frameworkBindingObserved)) {
                    ActivityMainLifecycle.Action.INITIALIZE_REMOTE -> initializeForReadyService()
                    ActivityMainLifecycle.Action.WAIT_FOR_CONNECTION,
                    ActivityMainLifecycle.Action.WAIT_FOR_REBIND -> showServiceNotReadyDialog(current)
                    ActivityMainLifecycle.Action.INITIALIZE_LOCAL_AND_WAIT,
                    ActivityMainLifecycle.Action.NO_CHANGE -> Unit
                }
            }
        }
        mainServiceStateListener = listener
        App.addOnServiceStateListener(listener)
    }

    private fun initializeForReadyService() {
        val state = App.serviceReadinessSnapshot()
        if (!MainSettingsReadinessPolicy.mayInitializeConnectedSettings(state)) return
        if (initializedServiceStateVersion == state.version) return
        val preferences = PrefUtils.getPrefs(this, state) ?: getPrefs()
        val requestedClassicUi = preferences?.getBoolean(PREF_USE_CLASSIC_UI, false) ?: false
        val latestState = App.serviceReadinessSnapshot()
        if (!latestState.ready || latestState.version != state.version) return
        readinessDialog?.dismiss()
        readinessDialog = null
        readinessDialogPhase = null
        initializedServiceStateVersion = state.version
        updateSettingsControls()
        if (requestedClassicUi != isClassicUi) {
            restartActivity()
            return
        }
        initializeUi(preferences, versionMarkerAuthoritative = true)
    }

    private fun initializeForOfflineService() {
        val state = App.serviceReadinessSnapshot()
        if (ActivityMainLifecycle.onCreate(state, App.hasObservedFrameworkBinding()) !=
            ActivityMainLifecycle.Action.INITIALIZE_LOCAL_AND_WAIT
        ) return
        if (initializedServiceStateVersion == state.version) return
        val preferences = PrefUtils.getPrefs(this, state) ?: return
        val latestState = App.serviceReadinessSnapshot()
        if (latestState.version != state.version || latestState.connected || App.hasObservedFrameworkBinding()) return
        initializedServiceStateVersion = state.version
        updateSettingsControls()
        // Local settings are not authoritative for app metadata: PREF_LAST_VERSION is not a
        // portable user setting, so deferring the changelog until the remote source is ready
        // avoids showing it again after the first framework bind.
        initializeUi(preferences, versionMarkerAuthoritative = false)
    }

    private fun showServiceNotReadyDialog(state: ServiceReadinessSnapshot) {
        val message = when (state.phase) {
            ServicePhase.CONNECTED_SYNCING -> getString(R.string.diagnostics_connection_syncing)
            ServicePhase.ERROR -> getString(R.string.diagnostics_connection_error)
            ServicePhase.DISCONNECTED -> if (frameworkBindingObserved) {
                getString(R.string.diagnostics_connection_disconnected)
            } else return
            else -> return
        }
        val existing = readinessDialog
        if (existing?.isShowing == true && readinessDialogPhase == state.phase) {
            existing.setMessage(message)
            return
        }
        existing?.dismiss()
        readinessDialogPhase = state.phase
        val isRetryableError = MainSettingsReadinessPolicy.mayRetryConvergence(state)
        val builder = MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(if (isRetryableError) R.string.retry else R.string.close, null)
            .setCancelable(false)
        if (isRetryableError) {
            builder.setNegativeButton(R.string.close) { _, _ -> finish() }
        }
        readinessDialog = builder.create().also { dialog ->
            dialog.setCanceledOnTouchOutside(false)
            dialog.show()
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (isRetryableError) {
                    App.requestServiceConvergenceRetry()
                } else {
                    finish()
                }
            }
        }
    }

    /**
     * Perform full UI initialization once the module state has been positively
     * resolved as enabled.
     *
     * @param pref SharedPreferences instance containing user settings.
     * @param versionMarkerAuthoritative True only for the remote/ready settings source.
     *   Local cold-start preferences are not a safe home for app version metadata.
     */
    private fun initializeUi(pref: SharedPreferences?, versionMarkerAuthoritative: Boolean) {
        if (isClassicUi) {
            setupClassicUi(pref)
        } else {
            setupModernUi(pref)
        }

        checkFirstRunOnboarding(pref)

        // Show/record the changelog marker only from authoritative remote settings. The marker is
        // deliberately outside OfflineSettingsHandoff's user-setting allowlist, so a local write
        // would never converge and would replay the same changelog after the first bind. Record
        // the marker before showing the dialog; `apply()` updates the remote in-memory map
        // synchronously and only defers the binder/disk write, so a lost write merely replays
        // the changelog on a later launch instead of blocking startup under the settings lock.
        val thisVersion = BuildConfig.VERSION_CODE
        if (AppVersionMarkerPolicy.shouldRecordVersion(pref, versionMarkerAuthoritative, thisVersion)) {
            val recorded = updateSettings { currentPrefs ->
                currentPrefs.edit().putInt(PREF_LAST_VERSION, thisVersion).apply()
                true
            }
            if (recorded) showChangeLog()
        }

        // Check for updates in background thread.
        try {
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
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The Activity was destroyed while initializing; there is no view left to update.
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
        badgeView?.text = getString(entitlement.badgeResId)
        descView?.text = getString(entitlement.descResId)
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

        resetSettings?.setOnClickListener { performResetSettings() }

        overrideROMFeatureLevels?.apply {
            isChecked = pref?.getBoolean(PREF_OVERRIDE_ROM_FEATURE_LEVELS, true) ?: false
            setOnCheckedChangeListener { _, isChecked ->
                if (canEditSettings()) {
                    if (updateSettings(setOf(PREF_OVERRIDE_ROM_FEATURE_LEVELS)) { currentPrefs ->
                        currentPrefs.edit().putBoolean(PREF_OVERRIDE_ROM_FEATURE_LEVELS, isChecked).apply()
                        true
                    }) {
                        showRebootSnack()
                    }
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

            var initialSelectionCallback = true
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedItem = aa.getItem(position)
                    val deviceName = selectedItem?.deviceName ?: DeviceProps.defaultDeviceName
                    if (initialSelectionCallback) {
                        initialSelectionCallback = false
                        if (position == pos) {
                            updateEntitlementUi(deviceName, classicEntitlementBadge, classicEntitlementDesc)
                            return
                        }
                    }
                    val saved = canEditSettings() && updateSettings(
                        setOf(PREF_DEVICE_TO_SPOOF, PREF_SPOOF_FEATURES_LIST)
                    ) { currentPrefs ->
                        currentPrefs.edit().apply {
                            putString(PREF_DEVICE_TO_SPOOF, deviceName)
                            putStringSet(
                                PREF_SPOOF_FEATURES_LIST,
                                DeviceProps.getFeaturesUpToFromDeviceName(deviceName)
                            )
                        }.apply()
                        true
                    }
                    if (saved) {
                        showRebootSnack()
                    }
                    updateEntitlementUi(deviceName, classicEntitlementBadge, classicEntitlementDesc)
                    peekFeatureFlagsChanged(featureFlagsChanged)
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
                if (canEditSettings()) {
                    childActivityLauncher.launch(Intent(this@ActivityMain, AdvancedOptionsActivity::class.java))
                }
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
            if (canEditSettings()) childActivityLauncher.launch(Intent(this, FeatureCustomize::class.java))
        }

        supportLink?.apply {
            paintFlags = Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { openWebLink(SUPPORT_URL) }
        }

        confExport?.setOnClickListener { showExportDialog() }
        confImport?.setOnClickListener { if (canEditSettings()) showImportDialog() }
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
                val saved = canEditSettings() && updateSettings(
                    setOf(PREF_DEVICE_TO_SPOOF, PREF_SPOOF_FEATURES_LIST)
                ) { currentPrefs ->
                    currentPrefs.edit().apply {
                        putString(PREF_DEVICE_TO_SPOOF, selectedDevice)
                        putStringSet(
                            PREF_SPOOF_FEATURES_LIST,
                            DeviceProps.getFeaturesUpToFromDeviceName(selectedDevice)
                        )
                    }.apply()
                    true
                }
                if (saved) {
                    showRebootSnack()
                }
                statusProfile?.text = getString(R.string.device_spoofed_active, selectedDevice)
                updateEntitlementUi(selectedDevice, modernEntitlementBadge, modernEntitlementDesc)
            }
        }

        val btnCustomizeFeatures = findViewById<View>(R.id.modern_btn_customize_features)
        btnCustomizeFeatures?.setOnClickListener {
            if (canEditSettings()) childActivityLauncher.launch(Intent(this, FeatureCustomize::class.java))
        }

        val switchOverrideRom = findViewById<MaterialSwitch>(R.id.modern_switch_override_rom)
        switchOverrideRom?.apply {
            isChecked = pref?.getBoolean(PREF_OVERRIDE_ROM_FEATURE_LEVELS, true) ?: false
            setOnCheckedChangeListener { _, isChecked ->
                if (canEditSettings()) {
                    if (updateSettings(setOf(PREF_OVERRIDE_ROM_FEATURE_LEVELS)) { currentPrefs ->
                        currentPrefs.edit().putBoolean(PREF_OVERRIDE_ROM_FEATURE_LEVELS, isChecked).apply()
                        true
                    }) {
                        showRebootSnack()
                    }
                }
            }
        }

        findViewById<View>(R.id.modern_btn_force_stop)?.setOnClickListener { forceStopScopedApps() }
        findViewById<View>(R.id.modern_btn_open_photos)?.setOnClickListener { openGooglePhotos() }

        findViewById<View>(R.id.modern_item_advanced_options)?.setOnClickListener {
            if (canEditSettings()) childActivityLauncher.launch(Intent(this, AdvancedOptionsActivity::class.java))
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
                    if (canEditSettings()) updateSettings { currentPrefs ->
                        currentPrefs.edit().putBoolean(PREF_FIRST_RUN_COMPLETED, true).apply()
                        true
                    }
                    forceStopScopedApps()
                    findViewById<View>(android.R.id.content)?.postDelayed({
                        if (!isFinishing && !isDestroyed) {
                            openGooglePhotos()
                        }
                    }, 500)
                }
                .setNegativeButton(R.string.onboarding_action_got_it) { _, _ ->
                    if (canEditSettings()) updateSettings { currentPrefs ->
                        currentPrefs.edit().putBoolean(PREF_FIRST_RUN_COMPLETED, true).apply()
                        true
                    }
                }
                .setCancelable(false)
                .show()
        }
    }

    /**
     * Reset all user configurations back to factory defaults and restart the activity.
     *
     */
    private fun performResetSettings() {
        if (!canEditSettings()) return
        val reset = updateSettings(
            setOf(
                PREF_DEVICE_TO_SPOOF,
                PREF_OVERRIDE_ROM_FEATURE_LEVELS,
                PREF_SPOOF_FEATURES_LIST,
                PREF_ENABLE_VERBOSE_LOGS,
                PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE,
                PREF_SPOOF_ANDROID_VERSION_MANUAL,
            )
        ) { currentPrefs ->
            currentPrefs.edit().apply {
                putString(PREF_DEVICE_TO_SPOOF, DeviceProps.defaultDeviceName)
                putBoolean(PREF_OVERRIDE_ROM_FEATURE_LEVELS, true)
                putStringSet(
                    PREF_SPOOF_FEATURES_LIST,
                    DeviceProps.defaultFeatures.map { it.displayName }.toSet()
                )
                putBoolean(PREF_ENABLE_VERBOSE_LOGS, false)
                putBoolean(PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, false)
                putString(PREF_SPOOF_ANDROID_VERSION_MANUAL, null)
            }.apply()
            true
        }
        if (reset) restartActivity()
    }

    /**
     * Toggle between Modern and Classic UI layouts and restart the activity.
     */
    private fun toggleUiMode() {
        if (!canEditSettings()) return
        val updated = updateSettings(setOf(PREF_USE_CLASSIC_UI)) { currentPrefs ->
            val current = currentPrefs.getBoolean(PREF_USE_CLASSIC_UI, false)
            currentPrefs.edit().putBoolean(PREF_USE_CLASSIC_UI, !current).apply()
            true
        }
        if (!updated) return
        Toast.makeText(this, R.string.ui_mode_changed, Toast.LENGTH_SHORT).show()
        restartActivity()
    }

    /**
     * Force-stop all packages within the active LSPosed scope using root privileges.
     */
    private fun forceStopScopedApps() {
        if (!canEditSettings()) return
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
        val mayEdit = canEditSettings()
        menu?.findItem(R.id.menu_import)?.isEnabled = mayEdit
        menu?.findItem(R.id.menu_reset)?.isEnabled = mayEdit
        switchUiItem?.isEnabled = mayEdit
        return super.onPrepareOptionsMenu(menu)
    }

    /**
     * Handle options menu item selection.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_export -> showExportDialog()
            R.id.menu_import -> if (canEditSettings()) showImportDialog()
            R.id.menu_reset -> {
                performResetSettings()
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
        val capture = captureAuthorizedConfigExport()
        val export = capture.export
        if (export == null) {
            showConfigExportUnavailable(capture.failureReason)
            return
        }
        if (!isCurrentAuthorizedConfigExport(export)) {
            showConfigExportUnavailable(ConfigExportFailureReason.UNAVAILABLE)
            return
        }
        try {
            val configDir = File(cacheDir, "config_exports")
            configDir.mkdirs()
            val confFile = File(configDir, CONF_EXPORT_NAME)
            val uriFromFile = Uri.fromFile(confFile)

            confFile.delete()
            utils.writeConfigFile(this, uriFromFile, JSONObject(export.json))

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
        // A long-lived Activity is not recreated, so reconcile here as well as in onCreate;
        // otherwise an expired cross-process record blocks export until the task restarts.
        pendingConfigExport.reconcile(ConfigExportProcessEpoch.current)
        if (pendingConfigExport.hasPending() || deferredConfigExport != null || configExportWriteInProgress) {
            Toast.makeText(this, R.string.config_export_in_progress, Toast.LENGTH_LONG).show()
            return
        }
        val capture = captureAuthorizedConfigExport()
        val export = capture.export
        if (export == null) {
            showConfigExportUnavailable(capture.failureReason)
            return
        }
        val requestId = java.util.UUID.randomUUID().toString()
        val request = ConfigExportPendingRequest(
            id = requestId,
            source = ConfigExportSourceStamp.from(export.source, ConfigExportProcessEpoch.current),
            json = export.json,
            createdAtMillis = System.currentTimeMillis(),
        )
        val launcher = try {
            registerConfigCreateLauncher(requestId, lifecycleAware = false)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.share_error, Toast.LENGTH_SHORT).show()
            return
        }
        if (!pendingConfigExport.begin(request)) {
            pendingConfigExport.clear(requestId)
            configCreateLaunchers.remove(requestId)?.unregister()
            return
        }
        if (!isCurrentAuthorizedConfigExport(export)) {
            clearConfigExportRequest(requestId)
            configCreateLaunchers.remove(requestId)?.unregister()
            showConfigExportUnavailable(ConfigExportFailureReason.UNAVAILABLE)
            return
        }
        try {
            val openIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, CONF_EXPORT_NAME)
            }
            Toast.makeText(this, R.string.select_a_location, Toast.LENGTH_SHORT).show()
            launcher.launch(openIntent)
        } catch (_: Exception) {
            clearConfigExportRequest(requestId)
            configCreateLaunchers.remove(requestId)?.unregister()
            Toast.makeText(this, R.string.share_error, Toast.LENGTH_SHORT).show()
        }
    }

    /** Clears this launch's durable request and releases any deferred restart after cleanup. */
    private fun clearConfigExportRequest(requestId: String) {
        pendingConfigExport.clear(requestId)
        if (!pendingConfigExport.hasAwaitingResult()) {
            scheduleDeferredRestartAfterConfigExport(requestId, requestId)
        }
    }

    /** Restores an outstanding request key before STARTED; the payload itself stays in app storage. */
    private fun restorePendingConfigExportLauncher(savedStatePresent: Boolean) {
        val request = pendingConfigExport.restorePending(
            currentProcessEpoch = ConfigExportProcessEpoch.current,
            canReceiveResult = savedStatePresent,
        ) ?: return
        runCatching { registerConfigCreateLauncher(request.id, lifecycleAware = true) }
    }

    private fun registerConfigCreateLauncher(
        requestId: String,
        lifecycleAware: Boolean,
    ): ActivityResultLauncher<Intent> {
        configCreateLaunchers[requestId]?.let { return it }
        val key = "config-create-$requestId"
        val callbackFired = java.util.concurrent.atomic.AtomicBoolean(false)
        val callback = ActivityResultCallback<ActivityResult> { result ->
            callbackFired.set(true)
            configCreateLaunchers.remove(requestId)?.unregister()
            handleConfigCreateResult(requestId, result)
        }
        val launcher = if (lifecycleAware) {
            activityResultRegistry.register(
                key,
                this,
                ActivityResultContracts.StartActivityForResult(),
                callback,
            )
        } else {
            activityResultRegistry.register(
                key,
                ActivityResultContracts.StartActivityForResult(),
                callback,
            )
        }
        // Defensive: an Activity/registry version may deliver a pending result during
        // registration. The callback then unregisters the launcher and removes the map entry
        // before the assignment below, so never re-add a launcher whose one-shot result was
        // already delivered.
        if (!callbackFired.get()) {
            configCreateLaunchers[requestId] = launcher
        } else {
            runCatching { launcher.unregister() }
        }
        return launcher
    }

    /**
     * Every callback consumes only its durable request ID, including cancellation and stale
     * results. A delivered result whose settings source is still converging (a restored picker
     * result can land during CONNECTED_SYNCING/ERROR after process death) is retained in memory
     * and completed once the source settles or the wait budget expires. Consuming at entry keeps
     * at-most-once delivery and cannot orphan a record that blocks later exports. The retained
     * result is saved in instance state so a configuration change re-arms it in the same process
     * instead of dropping the one-shot URI.
     */
    private fun handleConfigCreateResult(
        requestId: String,
        result: ActivityResult,
    ) {
        configExportCallbackInProgress = true
        var consumedRequestId: String? = null
        var completionDeferred = false
        try {
            val request = pendingConfigExport.consume(requestId) ?: return
            consumedRequestId = request.id
            if (result.resultCode != Activity.RESULT_OK) return
            val uri = result.data?.data ?: run {
                Toast.makeText(this, R.string.read_error, Toast.LENGTH_SHORT).show()
                return
            }
            val current = App.serviceReadinessSnapshot()
            // Settings access is part of write authorization, not launch-time state: after
            // process death the persisted snapshot must be re-authorized against the current
            // source. While the recreated process waits for a rebind, that source can still be
            // CONNECTED_SYNCING; deciding "unavailable" here would permanently discard the
            // one-shot picker result, so a transient state waits for convergence instead.
            val action = ConfigExportCallbackPolicy.decide(
                request = request,
                callbackRequestId = requestId,
                current = current,
                processEpoch = ConfigExportProcessEpoch.current,
                frameworkBindingObserved = frameworkBindingObserved || App.hasObservedFrameworkBinding(),
                preferencesAvailable = PrefUtils.getPrefs(this, current) != null,
                settingsWaitExpired = false,
            )
            when (action) {
                ConfigExportCallbackAction.WAIT_FOR_SETTINGS -> {
                    completionDeferred = true
                    beginDeferredConfigExport(request, uri)
                }

                ConfigExportCallbackAction.WRITE -> {
                    completionDeferred = true
                    submitConfigExportWrite(request, uri)
                }

                ConfigExportCallbackAction.REJECT ->
                    Toast.makeText(this, R.string.config_export_unavailable, Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.share_error, Toast.LENGTH_SHORT).show()
        } finally {
            configExportCallbackInProgress = false
            // A delivered one-shot callback may be quarantined if exact-record removal failed.
            // It may complete its own deferred restart, but cannot release a newer request.
            if (consumedRequestId == null && pendingConfigExport.wasCallbackDelivered(requestId)) {
                consumedRequestId = requestId
            }
            if (!completionDeferred) {
                consumedRequestId?.let {
                    scheduleDeferredRestartAfterConfigExport(
                        callbackRequestId = requestId,
                        completedRequestId = it,
                        deliveredCallbackId = requestId,
                    )
                }
            }
        }
    }

    /** Retains a delivered result while the settings source settles. */
    private fun beginDeferredConfigExport(request: ConfigExportPendingRequest, target: Uri) {
        if (deferredConfigExport != null) {
            Toast.makeText(this, R.string.config_export_unavailable, Toast.LENGTH_LONG).show()
            scheduleDeferredRestartAfterConfigExport(
                callbackRequestId = request.id,
                completedRequestId = request.id,
                deliveredCallbackId = request.id,
            )
            return
        }
        deferredConfigExport = DeferredConfigExport(
            request = request,
            target = target,
            deadlineElapsedRealtime = SystemClock.elapsedRealtime() + CONFIG_EXPORT_SETTINGS_WAIT_TIMEOUT_MS,
        )
        mainHandler.postDelayed(configExportSettingsWaitTimeout, CONFIG_EXPORT_SETTINGS_WAIT_TIMEOUT_MS)
    }

    /**
     * Re-decides a deferred export against the current service state. Called from the
     * service-state listener and the wait-budget timeout; the durable request was already
     * consumed, so waiting cannot orphan it or block a later export.
     */
    private fun completeDeferredConfigExport(settingsWaitExpired: Boolean) {
        val deferred = deferredConfigExport ?: return
        if (isFinishing || isDestroyed) {
            clearDeferredConfigExport()
            return
        }
        val current = App.serviceReadinessSnapshot()
        val expired = settingsWaitExpired ||
            SystemClock.elapsedRealtime() >= deferred.deadlineElapsedRealtime
        val action = ConfigExportCallbackPolicy.decide(
            request = deferred.request,
            callbackRequestId = deferred.request.id,
            current = current,
            processEpoch = ConfigExportProcessEpoch.current,
            frameworkBindingObserved = frameworkBindingObserved || App.hasObservedFrameworkBinding(),
            preferencesAvailable = PrefUtils.getPrefs(this, current) != null,
            settingsWaitExpired = expired,
        )
        when (action) {
            ConfigExportCallbackAction.WAIT_FOR_SETTINGS -> {
                // A settled ERROR would otherwise wait out the whole budget without retrying;
                // retry only once, because each retry re-enters ERROR and re-notifies listeners.
                if (current.phase == ServicePhase.ERROR && !deferred.convergenceRetryRequested) {
                    deferred.convergenceRetryRequested = true
                    App.requestServiceConvergenceRetry()
                }
            }

            ConfigExportCallbackAction.WRITE -> {
                deferredConfigExportCompletion.markCompleted(deferred.request.id)
                clearDeferredConfigExport()
                submitConfigExportWrite(deferred.request, deferred.target)
            }

            ConfigExportCallbackAction.REJECT -> {
                deferredConfigExportCompletion.markCompleted(deferred.request.id)
                clearDeferredConfigExport()
                Toast.makeText(this, R.string.config_export_unavailable, Toast.LENGTH_LONG).show()
                scheduleDeferredRestartAfterConfigExport(
                    callbackRequestId = deferred.request.id,
                    completedRequestId = deferred.request.id,
                    deliveredCallbackId = deferred.request.id,
                )
            }
        }
    }

    private fun clearDeferredConfigExport() {
        deferredConfigExport = null
        mainHandler.removeCallbacks(configExportSettingsWaitTimeout)
    }

    /** Writes the authorized document off the main thread; the URI grant outlives this callback. */
    private fun submitConfigExportWrite(request: ConfigExportPendingRequest, target: Uri) {
        configExportWriteInProgress = true
        val appContext = applicationContext
        val handler = Handler(Looper.getMainLooper())
        val activityRef = WeakReference(this)
        val json = request.json
        val requestId = request.id
        try {
            configExportExecutor.execute {
                // Keep the Activity out of this worker closure: a graceful executor shutdown
                // lets a slow provider finish after rotation without retaining the old view tree.
                val written = runCatching {
                    Utils().writeConfigFile(appContext, target, JSONObject(json))
                }.isSuccess
                handler.post {
                    val activity = activityRef.get() ?: return@post
                    activity.configExportWriteInProgress = false
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(
                            activity,
                            if (written) R.string.export_complete else R.string.share_error,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    activity.scheduleDeferredRestartAfterConfigExport(
                        callbackRequestId = requestId,
                        completedRequestId = requestId,
                        deliveredCallbackId = requestId,
                    )
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            configExportWriteInProgress = false
            scheduleDeferredRestartAfterConfigExport(
                callbackRequestId = request.id,
                completedRequestId = request.id,
                deliveredCallbackId = request.id,
            )
        }
    }

    /** Runs after the callback/cleanup stack has completed and rechecks for a newer request. */
    private fun scheduleDeferredRestartAfterConfigExport(
        callbackRequestId: String,
        completedRequestId: String?,
        deliveredCallbackId: String? = null,
    ) {
        mainHandler.post {
            val shouldRestart = deferredActivityRestart.completeRequest(
                callbackRequestId = callbackRequestId,
                completedRequestId = completedRequestId,
                configExportPending = configExportPending(
                    deliveredCallbackId = deliveredCallbackId?.takeIf {
                        it == callbackRequestId && it == completedRequestId
                    },
                ),
                hostActive = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            )
            if (shouldRestart) performActivityRestart()
        }
    }

    /**
     * Releases a restart that was deferred because a config import was in flight.
     *
     * The import result may be delivered on the ON_START event, which on API 29+ is dispatched
     * after `onStart()` returns while on API 26–28 it arrives during `super.onStart()`. The release
     * path covers both, ensuring that every import path ending without a restart re-checks and
     * releases the deferred intent.
     */
    private fun releaseDeferredRestartAfterImport() {
        mainHandler.post {
            if (deferredActivityRestart.releaseDeferredRestart(
                    configExportPending = configExportPending(),
                    hostActive = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                )
            ) {
                performActivityRestart()
            }
        }
    }

    /** Reads one authorized, source-consistent allowlisted export snapshot. */
    private data class ConfigExportOutcome(
        val export: AuthorizedConfigExport? = null,
        val failureReason: ConfigExportFailureReason? = null,
    ) {
        init {
            require((export == null) == (failureReason != null))
        }
    }

    private fun captureAuthorizedConfigExport(expectedVersion: Long? = null): ConfigExportOutcome {
        val captured = ConfigExportCapturePolicy.capture(
            readServiceState = App::serviceReadinessSnapshot,
            readForState = { source ->
                if (expectedVersion != null && expectedVersion != source.version) {
                    ConfigExportAttempt.failure(ConfigExportFailureReason.UNAVAILABLE)
                } else {
                    val bindingObserved = frameworkBindingObserved || App.hasObservedFrameworkBinding()
                    if (!ConfigExportPolicy.mayExport(source, bindingObserved, preferencesAvailable = true)) {
                        ConfigExportAttempt.failure(ConfigExportFailureReason.UNAVAILABLE)
                    } else {
                        val preferences = PrefUtils.getPrefs(this, source)
                        if (!ConfigExportPolicy.mayExport(
                                source,
                                bindingObserved,
                                preferencesAvailable = preferences != null,
                            )
                        ) {
                            ConfigExportAttempt.failure(ConfigExportFailureReason.UNAVAILABLE)
                        } else {
                            try {
                                val json = ConfigExportPolicy.toJson(checkNotNull(preferences))
                                if (ConfigExportPolicy.hasExportableSettings(json)) {
                                    ConfigExportAttempt.success(json.toString())
                                } else {
                                    ConfigExportAttempt.failure(ConfigExportFailureReason.EMPTY)
                                }
                            } catch (_: ConfigExportPolicy.EmptyConfigurationException) {
                                ConfigExportAttempt.failure(ConfigExportFailureReason.EMPTY)
                            } catch (_: RuntimeException) {
                                ConfigExportAttempt.failure(ConfigExportFailureReason.UNAVAILABLE)
                            }
                        }
                    }
                }
            },
        )
        val current = App.serviceReadinessSnapshot()
        val sourceStillAuthorized = current.version == captured.serviceState.version &&
            current.phase == captured.serviceState.phase &&
            current.service === captured.serviceState.service &&
            (expectedVersion == null || expectedVersion == current.version) &&
            ConfigExportPolicy.mayExport(
                current,
                frameworkBindingObserved || App.hasObservedFrameworkBinding(),
                preferencesAvailable = true,
            )
        if (!sourceStillAuthorized) {
            return ConfigExportOutcome(failureReason = ConfigExportFailureReason.UNAVAILABLE)
        }
        val failureReason = captured.failureReason
        if (failureReason != null) return ConfigExportOutcome(failureReason = failureReason)
        val json = captured.value ?: return ConfigExportOutcome(
            failureReason = ConfigExportFailureReason.UNAVAILABLE,
        )
        if (runCatching { JSONObject(json).length() > 0 }.getOrDefault(false).not()) {
            return ConfigExportOutcome(failureReason = ConfigExportFailureReason.EMPTY)
        }
        return ConfigExportOutcome(export = AuthorizedConfigExport(captured.serviceState, json))
    }

    private fun isCurrentAuthorizedConfigExport(export: AuthorizedConfigExport): Boolean {
        val current = App.serviceReadinessSnapshot()
        return current.version == export.source.version &&
            current.phase == export.source.phase &&
            current.service === export.source.service &&
            runCatching { JSONObject(export.json).length() > 0 }.getOrDefault(false) &&
            ConfigExportPolicy.mayExport(
                current,
                frameworkBindingObserved || App.hasObservedFrameworkBinding(),
                preferencesAvailable = true,
            )
    }

    private fun showConfigExportUnavailable(reason: ConfigExportFailureReason?) {
        val message = if (reason == ConfigExportFailureReason.EMPTY) {
            R.string.config_export_empty
        } else {
            R.string.config_export_unavailable
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Guards against overlapping document reads/imports from repeated picker results. */
    private var configImportInProgress = false

    /** True from import-picker launch until its result is handled, so a restart cannot strand it. */
    private var configImportInFlight = false

    /**
     * Launch the Storage Access Framework document picker to select a configuration JSON for import.
     */
    private fun importConfFile() {
        if (configImportInProgress || configImportInFlight || !canEditSettings()) return
        val openIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        configImportInFlight = true
        try {
            configOpenLauncher.launch(openIntent)
        } catch (_: Exception) {
            configImportInFlight = false
            Toast.makeText(this, R.string.read_error, Toast.LENGTH_SHORT).show()
            releaseDeferredRestartAfterImport()
        }
    }

    /**
     * ActivityResultLauncher handling SAF document selection result for configuration import.
     *
     * The read (up to 1 MiB from an arbitrary, possibly remote DocumentsProvider), JSON
     * parsing, and field validation run on [configImportExecutor] because main-thread provider
     * I/O can ANR, and because [App.withGuardedSettingsPreferences] holds the global service-state
     * lock during the settings write. Only the resulting in-memory plan is applied, under that
     * guard, back on the main thread after re-checking lifecycle. Editability is revalidated by
     * [updateSettings] under the settings guard, so a slow provider can never block service-state
     * transitions.
     *
     * A configuration change during the read abandons this import: the completion
     * self-cancels on the destroyed Activity and nothing is applied. The read is pure
     * and the allowlisted plan is idempotent, so the user can simply re-select the file.
     * (`shutdownNow()` cannot interrupt a provider that ignores interruption, so a hung
     * read may pin this Activity until the provider returns.)
     */
    private val configOpenLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        configImportInFlight = false
        if (result.resultCode != Activity.RESULT_OK) {
            releaseDeferredRestartAfterImport()
            return@registerForActivityResult
        }
        val uri = result.data?.data ?: run {
            Toast.makeText(this, R.string.read_error, Toast.LENGTH_SHORT).show()
            releaseDeferredRestartAfterImport()
            return@registerForActivityResult
        }
        if (configImportInProgress || !canEditSettings()) {
            releaseDeferredRestartAfterImport()
            return@registerForActivityResult
        }
        configImportInProgress = true
        val appContext = applicationContext
        try {
            configImportExecutor.execute {
                val staged = runCatching { utils.readConfigFile(appContext, uri) }
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    configImportInProgress = false
                    val plan = staged.getOrNull()
                    if (plan == null) {
                        Toast.makeText(this, R.string.read_error, Toast.LENGTH_SHORT).show()
                        releaseDeferredRestartAfterImport()
                        return@post
                    }
                    if (plan.hasInvalidDevice) {
                        Toast.makeText(this, R.string.invalid_imported_device, Toast.LENGTH_LONG).show()
                    }
                    if (plan.hasInvalidAndroidVersion) {
                        Toast.makeText(this, R.string.invalid_imported_android_version, Toast.LENGTH_LONG).show()
                    }
                    // updateSettings revalidates expectedStateVersion under the settings lock and
                    // reports a toast on failure, so no separate editability check is needed here.
                    val imported = updateSettings(editedKeys = plan.editedKeys) { currentPrefs ->
                        plan.apply(currentPrefs)
                        true
                    }
                    if (!imported) {
                        releaseDeferredRestartAfterImport()
                        return@post
                    }
                    Toast.makeText(this, R.string.import_complete, Toast.LENGTH_SHORT).show()
                    restartActivity()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            configImportInProgress = false
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, R.string.read_error, Toast.LENGTH_SHORT).show()
            }
            releaseDeferredRestartAfterImport()
        }
    }

    /**
     * Activity destroy lifecycle callback. Cancels pending bind resolution, clears any
     * still-waiting export, and drains the background executors.
     */
    override fun onDestroy() {
        // Targeted teardown: the resolver owns the only runnables this activity
        // posts (grace timeout + marshaled bind resolution). dispose() cancels the
        // timeout token and unregisters its listener by identity; an already
        // dispatched resolution post self-suppresses via the disposed flag. Null
        // when the enabled fast path never started a wait.
        moduleStateResolver?.dispose()
        mainServiceStateListener?.let { App.removeOnServiceStateListener(it) }
        mainServiceStateListener = null
        readinessDialog?.dismiss()
        readinessDialog = null
        configImportInProgress = false
        configImportInFlight = false
        clearDeferredConfigExport()
        configExportWriteInProgress = false
        // update/import work is pure reads and cancellable; the export write already owns the
        // picker destination, so it is allowed to finish instead of being interrupted mid-record.
        configImportExecutor.shutdownNow()
        configExportExecutor.shutdown()
        updateExecutor.shutdownNow()
        super.onDestroy()
    }
}
