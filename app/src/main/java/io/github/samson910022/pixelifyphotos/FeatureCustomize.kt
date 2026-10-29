package io.github.samson910022.pixelifyphotos

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_FEATURES_LIST

/**
 * Provides granular controls for selecting some specific feature flags.
 */
class FeatureCustomize : AppCompatActivity(R.layout.feature_customize) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceStateListener: ((ServiceReadinessSnapshot) -> Unit)? = null
    private var checkboxHolder: LinearLayout? = null
    private var saveButton: Button? = null
    private var settingsInitialized = false
    private var settingsSourceVersion: Long? = null
    private var sourceRefreshRequested = false
    private var initialSelectedFeatures: Set<String> = emptySet()
    private var storedFeatureNames: Set<String> = emptySet()
    private var renderedFeatureNames: Set<String> = emptySet()
    private var recreateAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recreateAttempts = savedInstanceState?.getInt(STATE_RECREATE_ATTEMPTS, 0) ?: 0

        checkboxHolder = findViewById(R.id.feature_checkbox_holder)
        saveButton = findViewById(R.id.save_features)

        /** A changed selection is stored and returns RESULT_OK; no-op saves do neither. */
        saveButton?.setOnClickListener {

            val expectedVersion = settingsSourceVersion
            if (!SettingsScreenSourcePolicy.maySave(
                    expectedVersion,
                    App.serviceReadinessSnapshot(),
                    App.hasObservedFrameworkBinding(),
                )
            ) {
                refreshForCurrentSource()
                Toast.makeText(this, R.string.settings_write_unavailable, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val checkedFeatureNames =
                checkboxHolder?.children?.filter { it is CheckBox && it.isChecked }
                    ?.map { (it as CheckBox).text.toString() }?.toSet().orEmpty()
            val editedKeys = FeatureCustomizeChangePolicy.changedKeys(
                initialSelectedFeatures,
                checkedFeatureNames,
            )
            if (editedKeys.isEmpty()) {
                // A no-op is not user intent: in particular, do not copy a stale
                // local value over a different remote value on first bind.
                finish()
                return@setOnClickListener
            }

            val featuresToPersist = FeatureCustomizeChangePolicy.preserveUnrendered(
                storedFeatureNames,
                renderedFeatureNames,
                checkedFeatureNames,
            )

            var offlinePersistenceUncertain = false
            val saved = App.withGuardedSettingsPreferences(
                this,
                expectedStateVersion = expectedVersion,
                editedKeys = editedKeys,
                onOfflinePersistenceUncertain = { offlinePersistenceUncertain = true },
            ) { currentPrefs ->
                currentPrefs.edit().putStringSet(PREF_SPOOF_FEATURES_LIST, featuresToPersist).apply()
                true
            }
            if (!saved) {
                Toast.makeText(this, R.string.settings_write_unavailable, Toast.LENGTH_LONG).show()
                if (!canEditSettings()) refreshForCurrentSource() else updateSettingsAvailability()
                return@setOnClickListener
            }

            if (offlinePersistenceUncertain) {
                Toast.makeText(this, R.string.settings_offline_memory_only, Toast.LENGTH_LONG).show()
            }

            setResult(Activity.RESULT_OK)
            finish()

        }

        val sourceState = App.serviceReadinessSnapshot()
        settingsSourceVersion = sourceState.version
        val prefs = PrefUtils.getPrefs(this, sourceState)
        if (App.serviceReadinessSnapshot().version != sourceState.version) {
            installServiceStateListener()
            updateSettingsAvailability()
            requestRecreateIfAllowed()
            return
        }
        if (prefs == null) {
            installServiceStateListener()
            updateSettingsAvailability()
            return
        }

        populateSettings(prefs)
        installServiceStateListener()
        updateSettingsAvailability()
    }

    /**
     * Builds the checkboxes from [prefs]. Also used when the settings source becomes usable
     * after this screen was opened, so a flapping manager cannot leave the screen dead.
     */
    private fun populateSettings(prefs: SharedPreferences) {
        checkboxHolder?.removeAllViews()
        val defaultFeatureLevelsName = DeviceProps.defaultFeatures.map { it.displayName }.toSet()
        storedFeatureNames = prefs.getStringSet(PREF_SPOOF_FEATURES_LIST, defaultFeatureLevelsName)
            ?.toSet().orEmpty()
        // Only levels with actual flags render a checkbox. Empty levels are informational,
        // so any stored name for them must be preserved as unrendered state.
        renderedFeatureNames = FeatureCustomizeChangePolicy.renderedFeatureNames(DeviceProps.allFeatures)
        initialSelectedFeatures = FeatureCustomizeChangePolicy.renderedSelection(
            storedFeatureNames,
            renderedFeatureNames,
        )

        /**
         * Populate checkboxes in for each feature name. Empty feature levels are
         * shown as information only: they add no flags, and selecting such a
         * level alone would otherwise hide every known Pixel flag when ROM
         * override is enabled.
         */
        DeviceProps.allFeatures.withIndex().forEach { indexed ->

            val features = indexed.value
            if (features.featureFlags.isEmpty()) {
                val density = resources.displayMetrics.density
                val info = TextView(this).apply {
                    text = getString(R.string.feature_level_without_flags, features.displayName)
                    setPaddingRelative((48 * density).toInt(), (4 * density).toInt(), 0, (4 * density).toInt())
                    alpha = 0.7f
                }
                checkboxHolder?.addView(info)
                return@forEach
            }

            val checkBox = CheckBox(this)
            checkBox.apply {
                text = features.displayName
                id = indexed.index + 1
                isChecked = text in initialSelectedFeatures
            }

            checkboxHolder?.addView(checkBox)

        }

        settingsInitialized = true
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
        if (requestRecreateIfAllowed()) return
        // The relaunch budget is exhausted (for example a flapping manager service). Rebuild
        // in place from the current source instead of leaving the screen permanently disabled.
        reloadFromCurrentSource()
        sourceRefreshRequested = false
    }

    private fun reloadFromCurrentSource() {
        val current = App.serviceReadinessSnapshot()
        settingsSourceVersion = current.version
        val prefs = PrefUtils.getPrefs(this, current)
        if (current.ready && prefs != null) {
            populateSettings(prefs)
        }
        updateSettingsAvailability()
    }

    private fun requestRecreateIfAllowed(): Boolean {
        if (!SettingsScreenRecreatePolicy.mayRecreate(recreateAttempts)) return false
        recreateAttempts = SettingsScreenRecreatePolicy.nextAttempts(recreateAttempts)
        if (!isFinishing && !isDestroyed) recreate()
        return true
    }

    private fun installServiceStateListener() {
        lateinit var listener: (ServiceReadinessSnapshot) -> Unit
        listener = {
            mainHandler.post {
                if (!isFinishing && !isDestroyed && serviceStateListener === listener) {
                    val current = App.serviceReadinessSnapshot()
                    if (SettingsScreenSourcePolicy.mustReload(settingsSourceVersion, current)) {
                        refreshForCurrentSource()
                    } else if (!settingsInitialized) {
                        val prefs = PrefUtils.getPrefs(this, current)
                        if (current.ready && prefs != null) {
                            settingsSourceVersion = current.version
                            populateSettings(prefs)
                            sourceRefreshRequested = false
                        }
                        updateSettingsAvailability()
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
        checkboxHolder?.children?.forEach { it.isEnabled = enabled }
        saveButton?.isEnabled = enabled
        findViewById<TextView>(R.id.settings_write_status)?.apply {
            text = getString(R.string.settings_write_unavailable)
            visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_RECREATE_ATTEMPTS, recreateAttempts)
    }

    override fun onDestroy() {
        serviceStateListener?.let { App.removeOnServiceStateListener(it) }
        serviceStateListener = null
        super.onDestroy()
    }

    private companion object {
        const val STATE_RECREATE_ATTEMPTS = "feature_customize_recreate_attempts"
    }

}

internal object FeatureCustomizeChangePolicy {
    /** Levels without flags render informational text, not a selectable checkbox. */
    fun renderedFeatureNames(features: List<DeviceProps.Features>): Set<String> =
        features.filter { it.featureFlags.isNotEmpty() }.map { it.displayName }.toSet()

    fun renderedSelection(stored: Set<String>, renderedFeatureNames: Set<String>): Set<String> =
        stored.intersect(renderedFeatureNames)

    /** Keep stored flags that this version of the UI cannot display or remove. */
    fun preserveUnrendered(
        stored: Set<String>,
        renderedFeatureNames: Set<String>,
        selectedRendered: Set<String>,
    ): Set<String> = (stored - renderedFeatureNames) + selectedRendered

    fun changedKeys(initial: Set<String>, selected: Set<String>): Set<String> =
        if (initial == selected) emptySet() else setOf(PREF_SPOOF_FEATURES_LIST)
}
