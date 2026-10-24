package io.github.samson910022.pixelifyphotos

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import io.github.samson910022.pixelifyphotos.Constants.PREF_DEVICE_TO_SPOOF
import io.github.samson910022.pixelifyphotos.Constants.PREF_ENABLE_VERBOSE_LOGS
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL

class AdvancedOptionsActivity : AppCompatActivity(R.layout.advanced_options_activity) {

    private val verboseLogging by lazy { findViewById<CheckBox>(R.id.verbose_logging) }
    private val deviceNameLabel by lazy { findViewById<TextView>(R.id.device_name_label) }
    private val androidVersionRadioGroup by lazy { findViewById<RadioGroup>(R.id.android_version_radio_group) }
    private val deviceAndroidVersion by lazy { findViewById<TextView>(R.id.device_android_version) }
    private val androidVersionSpinner by lazy { findViewById<Spinner>(R.id.android_version_spinner) }
    private val save by lazy { findViewById<Button>(R.id.save_advanced_option) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceStateListener: ((ServiceReadinessSnapshot) -> Unit)? = null
    private var settingsInitialized = false
    private var settingsSourceVersion: Long? = null
    private var sourceRefreshRequested = false
    private var initialSelection: AdvancedOptionsSelection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sourceState = App.serviceReadinessSnapshot()
        settingsSourceVersion = sourceState.version
        val prefs = PrefUtils.getPrefs(this, sourceState)
        if (App.serviceReadinessSnapshot().version != sourceState.version) {
            installServiceStateListener()
            updateSettingsAvailability()
            recreate()
            return
        }
        if (prefs == null) {
            installServiceStateListener()
            updateSettingsAvailability()
            return
        }

        verboseLogging.isChecked = prefs.getBoolean(PREF_ENABLE_VERBOSE_LOGS, false)

        /**
         * Get the current spoofing device and its android version.
         */
        val deviceNameInPreference = prefs.getString(PREF_DEVICE_TO_SPOOF, DeviceProps.defaultDeviceName)
        val spoofDevice = DeviceProps.getDeviceProps(deviceNameInPreference)
        deviceNameLabel.text = spoofDevice?.deviceName
        deviceAndroidVersion.text = spoofDevice?.androidVersion?.label

        /**
         * String list of all [DeviceProps.AndroidVersion.label].
         */
        val allVersionLabels = DeviceProps.allAndroidVersions.map { it.label }

        /**
         * Set spinner for manually setting android version.
         */
        androidVersionSpinner.apply {
            val aa = ArrayAdapter(
                this@AdvancedOptionsActivity,
                android.R.layout.simple_spinner_item,
                allVersionLabels
            )

            aa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            adapter = aa
            val defaultSelection = prefs.getString(PREF_SPOOF_ANDROID_VERSION_MANUAL, null)
            val pos = aa.getPosition(defaultSelection)
            if (pos >= 0) {
                setSelection(pos)
            }
        }

        /**
         * Set default radio button.
         */
        androidVersionRadioGroup.apply {

            // hide spinner if not to be manually set.
            setOnCheckedChangeListener { _, checkedId ->
                androidVersionSpinner.isVisible = checkedId == R.id.manually_set_android_version
            }

            val manualVersion = prefs.getString(PREF_SPOOF_ANDROID_VERSION_MANUAL, null)?.trim()

            check(
                when {
                    prefs.getBoolean(PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, false) -> R.id.follow_spoof_device_version
                    manualVersion != null && manualVersion in allVersionLabels -> R.id.manually_set_android_version
                    else -> R.id.dont_spoof_android_version
                }
            )
        }

        save.setOnClickListener {
            savePreferences()
        }

        settingsInitialized = true
        initialSelection = currentSelection()
        installServiceStateListener()
        updateSettingsAvailability()

    }

    /** Save only advanced options changed since this screen was populated. */
    private fun savePreferences() {
        val beforeSelection = initialSelection ?: return
        val editedKeys = AdvancedOptionsChangePolicy.changedKeys(beforeSelection, currentSelection())
        val expectedVersion = settingsSourceVersion
        if (!SettingsScreenSourcePolicy.maySave(
                expectedVersion,
                App.serviceReadinessSnapshot(),
                App.hasObservedFrameworkBinding(),
            )
        ) {
            refreshForCurrentSource()
            Toast.makeText(this, R.string.settings_write_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        var offlinePersistenceUncertain = false
        val saved = App.withGuardedSettingsPreferences(
            this,
            expectedStateVersion = expectedVersion,
            editedKeys = editedKeys,
            onOfflinePersistenceUncertain = { offlinePersistenceUncertain = true },
        ) { currentPrefs ->
            currentPrefs.edit().run {
                if (PREF_ENABLE_VERBOSE_LOGS in editedKeys) {
                    putBoolean(PREF_ENABLE_VERBOSE_LOGS, verboseLogging.isChecked)
                }

                val androidVersionChanged = PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE in editedKeys ||
                    PREF_SPOOF_ANDROID_VERSION_MANUAL in editedKeys
                if (androidVersionChanged) {
                    when (androidVersionRadioGroup.checkedRadioButtonId) {

                        /** Enable only [PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE] when following the device. */
                        R.id.follow_spoof_device_version -> {
                            putBoolean(PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, true)
                            putString(PREF_SPOOF_ANDROID_VERSION_MANUAL, null)
                        }

                        R.id.manually_set_android_version -> {
                            putBoolean(PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, false)
                            putString(
                                PREF_SPOOF_ANDROID_VERSION_MANUAL,
                                androidVersionSpinner.selectedItem.toString()
                            )
                        }

                        R.id.dont_spoof_android_version -> {
                            putBoolean(PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, false)
                            putString(PREF_SPOOF_ANDROID_VERSION_MANUAL, null)
                        }
                    }
                }

                apply()
            }
            true
        }
        if (!saved) {
            Toast.makeText(this, R.string.settings_write_unavailable, Toast.LENGTH_LONG).show()
            if (!canEditSettings()) refreshForCurrentSource() else updateSettingsAvailability()
            return
        }
        if (offlinePersistenceUncertain) {
            Toast.makeText(this, R.string.settings_offline_memory_only, Toast.LENGTH_LONG).show()
        }
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun currentSelection(): AdvancedOptionsSelection {
        val mode = when (androidVersionRadioGroup.checkedRadioButtonId) {
            R.id.follow_spoof_device_version -> AndroidVersionMode.FOLLOW_DEVICE
            R.id.manually_set_android_version -> AndroidVersionMode.MANUAL
            else -> AndroidVersionMode.NONE
        }
        return AdvancedOptionsSelection(
            verboseLogging = verboseLogging.isChecked,
            androidVersionMode = mode,
            manualAndroidVersion = androidVersionSpinner.selectedItem?.toString(),
        )
    }

    private fun canEditSettings(): Boolean = SettingsScreenSourcePolicy.maySave(
        settingsSourceVersion,
        App.serviceReadinessSnapshot(),
        App.hasObservedFrameworkBinding(),
    )

    private fun refreshForCurrentSource() {
        if (sourceRefreshRequested) return
        sourceRefreshRequested = true
        settingsInitialized = false
        updateSettingsAvailability()
        if (!isFinishing && !isDestroyed) recreate()
    }

    private fun installServiceStateListener() {
        lateinit var listener: (ServiceReadinessSnapshot) -> Unit
        listener = {
            mainHandler.post {
                if (!isFinishing && !isDestroyed && serviceStateListener === listener) {
                    val current = App.serviceReadinessSnapshot()
                    if (SettingsScreenSourcePolicy.mustReload(settingsSourceVersion, current)) {
                        refreshForCurrentSource()
                    } else if (!settingsInitialized && current.ready && PrefUtils.getPrefs(this, current) != null) {
                        recreate()
                    } else {
                        updateSettingsAvailability()
                    }
                }
            }
        }
        serviceStateListener = listener
        App.addOnServiceStateListener(listener)
    }

    private fun updateSettingsAvailability() {
        val enabled = settingsInitialized && canEditSettings()
        setEnabledRecursively(verboseLogging, enabled)
        setEnabledRecursively(androidVersionRadioGroup, enabled)
        setEnabledRecursively(androidVersionSpinner, enabled)
        save.isEnabled = enabled
        findViewById<TextView>(R.id.settings_write_status)?.apply {
            text = getString(R.string.settings_write_unavailable)
            visibility = if (enabled) View.GONE else View.VISIBLE
        }
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) view.childrenRecursive().forEach { setEnabledRecursively(it, enabled) }
    }

    private fun ViewGroup.childrenRecursive(): Sequence<View> =
        (0 until childCount).asSequence().map(::getChildAt)

    override fun onDestroy() {
        serviceStateListener?.let { App.removeOnServiceStateListener(it) }
        serviceStateListener = null
        super.onDestroy()
    }

}
