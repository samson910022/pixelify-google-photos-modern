package io.github.samson910022.pixelifyphotos

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import io.github.samson910022.pixelifyphotos.Constants.CONF_EXPORT_NAME
import io.github.samson910022.pixelifyphotos.Constants.FIELD_LATEST_VERSION_CODE
import io.github.samson910022.pixelifyphotos.Constants.PREF_DEVICE_TO_SPOOF
import io.github.samson910022.pixelifyphotos.Constants.PREF_ENABLE_VERBOSE_LOGS
import io.github.samson910022.pixelifyphotos.Constants.PREF_LAST_VERSION
import io.github.samson910022.pixelifyphotos.Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_FEATURES_LIST
import io.github.samson910022.pixelifyphotos.Constants.RELEASES_URL
import io.github.samson910022.pixelifyphotos.Constants.RELEASES_URL2
import io.github.samson910022.pixelifyphotos.Constants.SHARED_PREF_FILE_NAME
import io.github.samson910022.pixelifyphotos.Constants.SUPPORT_URL
import io.github.samson910022.pixelifyphotos.Constants.UPDATE_INFO_URL
import io.github.samson910022.pixelifyphotos.Constants.UPDATE_INFO_URL2
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class ActivityMain: AppCompatActivity(R.layout.activity_main) {

    companion object {
        private const val MAX_UPDATE_INFO_BYTES = 64 * 1024
    }

    private val updateExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Get preferences via XposedService remote preferences when available,
     * falling back to MODE_PRIVATE when the service is not connected.
     *
     * This replaces the old MODE_WORLD_READABLE approach that required
     * the "xposedsharedprefs" manifest flag and could throw SecurityException
     * when the module was not enabled in LSPosed.
     *
     * Remote Preferences (via libxposed Modern API) are preferred because
     * they allow the Xposed hook process (inside Google Photos) to read
     * preferences written by the module UI process.
     */
    private fun getPrefs(): SharedPreferences? = PrefUtils.getPrefs(this)

    /**
     * Check if the Xposed module is actually active/enabled in LSPosed.
     *
     * Uses the XposedService connection status rather than the old
     * MODE_WORLD_READABLE crash check (which no longer applies since
     * we always fall back to MODE_PRIVATE).
     */
    private fun isModuleEnabled(): Boolean {
        return App.mService != null
    }

    private fun showRebootSnack() {
        if (!isModuleEnabled()) return // don't display snackbar if module not active.
        val rootView = findViewById<ScrollView>(R.id.root_view_for_snackbar)
        Snackbar.make(rootView, R.string.please_force_stop_google_photos, Snackbar.LENGTH_SHORT).show()
    }

    /**
     * Animate the "Feature flags changed" textview and hide it after showing for sometime.
     */
    private fun peekFeatureFlagsChanged(textView: TextView) {
        textView.run {
            alpha = 1.0f
            animate().alpha(0.0f).apply {
                duration = 1000
                startDelay = 3000
            }.start()
        }
    }

    private val utils by lazy { Utils() }

    /**
     * Activity launcher for [FeatureCustomize] activity.
     * If user presses "Save" on [FeatureCustomize] activity, then result code is RESULT_OK.
     * Then show prompt to force stop Google Photos.
     */
    private val childActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                showRebootSnack()
            }
        }

    /**
     * Close and reopen the activity.
     * For some reason, invalidate or recreate() does not refresh the switches.
     */
    private fun restartActivity() {
        if (isFinishing || isDestroyed) return
        finish()
        startActivity(Intent(this, ActivityMain::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        })
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /**
         * Check if the module is enabled (XposedService connected).
         * If it is not, show a warning dialog and close the activity.
         */
        if (!isModuleEnabled()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.module_not_enabled)
                .setPositiveButton(R.string.close) { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()
            return
        }

        /**
         * Get preferences instance for this activity.
         * Remote prefs via XposedService are preferred; MODE_PRIVATE is used as fallback.
         */
        val pref = getPrefs()

        /**
         * Link to xml views.
         */
        val resetSettings = findViewById<Button>(R.id.reset_settings)
        val customizeFeatureFlags = findViewById<LinearLayout>(R.id.customize_feature_flags)
        val featureFlagsChanged = findViewById<TextView>(R.id.feature_flags_changed)
        val overrideROMFeatureLevels = findViewById<SwitchCompat>(R.id.override_rom_feature_levels)
        val deviceSpooferSpinner = findViewById<Spinner>(R.id.device_spoofer_spinner)
        val forceStopGooglePhotos = findViewById<Button>(R.id.force_stop_google_photos)
        val openGooglePhotos = findViewById<ImageButton>(R.id.open_google_photos)
        val advancedOptions = findViewById<TextView>(R.id.advanced_options)
        val supportLink = findViewById<TextView>(R.id.support_link)
        val updateAvailableLink = findViewById<TextView>(R.id.update_available_link)
        val confExport = findViewById<ImageButton>(R.id.conf_export)
        val confImport = findViewById<ImageButton>(R.id.conf_import)

        /**
         * Set default spoof device to [DeviceProps.defaultDeviceName].
         * Set check for google photos as `false`.
         * Set default feature levels to spoof.
         * Restart the activity.
         */
        resetSettings.setOnClickListener {
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
         * See [FeatureSpoofer.featuresNotToSpoof].
         */
        overrideROMFeatureLevels.apply {
            isChecked = pref?.getBoolean(PREF_OVERRIDE_ROM_FEATURE_LEVELS, true) ?: false
            setOnCheckedChangeListener { _, isChecked ->
                pref?.edit()?.run {
                    putBoolean(PREF_OVERRIDE_ROM_FEATURE_LEVELS, isChecked)
                    apply()
                    showRebootSnack()
                }
            }
        }

        /**
         * See [DeviceSpoofer].
         */
        deviceSpooferSpinner.apply {
            val deviceNames = DeviceProps.allDevices.map { it.deviceName }
            val aa = ArrayAdapter(this@ActivityMain, android.R.layout.simple_spinner_item, deviceNames)

            aa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            adapter = aa
            val defaultSelection = pref?.getString(PREF_DEVICE_TO_SPOOF, DeviceProps.defaultDeviceName)
            /** Second argument is `false` to prevent calling [peekFeatureFlagsChanged] on initialization */
            setSelection(aa.getPosition(defaultSelection), false)

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val deviceName = aa.getItem(position)
                    pref?.edit()?.apply {
                        putString(PREF_DEVICE_TO_SPOOF, deviceName)
                        putStringSet(
                            PREF_SPOOF_FEATURES_LIST,
                            DeviceProps.getFeaturesUpToFromDeviceName(deviceName)
                        )
                        apply()
                    }

                    peekFeatureFlagsChanged(featureFlagsChanged)
                    showRebootSnack()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        advancedOptions.apply {
            paintFlags = Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                childActivityLauncher.launch(Intent(this@ActivityMain, AdvancedOptionsActivity::class.java))
            }
        }

        /**
         * Force-stop Photos plus other LSPosed-scoped packages (via service scope list),
         * filtered by [SpoofedPackageTracker] / [ScopePolicy].
         */
        forceStopGooglePhotos.setOnClickListener {
            val scopePackages = try {
                val service = App.mService
                if (service == null) {
                    // Module is enabled (activity would have closed earlier), but
                    // guard anyway: a null service yields Photos-only force-stop.
                    Log.w("Pixelify", "XposedService null while force-stopping; using Photos only")
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
         * See [Utils.openApplication].
         */
        openGooglePhotos.setOnClickListener {
            utils.openApplication(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, this)
        }

        /**
         * Launch [FeatureCustomize] to fine select the features.
         */
        customizeFeatureFlags.setOnClickListener {
            childActivityLauncher.launch(Intent(this, FeatureCustomize::class.java))
        }

        /**
         * Open telegram group.
         */
        supportLink.apply {
            paintFlags = Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                openWebLink(SUPPORT_URL)
            }
        }

        /**
         * Open config share options.
         * Also see [Utils.writeConfigFile].
         */
        confExport.setOnClickListener {
            AlertDialog.Builder(this).apply {
                setTitle(R.string.export_config)
                setMessage(R.string.export_config_desc)
                setPositiveButton(R.string.share) { _, _ ->
                    shareConfFile()
                }
                setNegativeButton(R.string.save) { _, _ ->
                    saveConfFile()
                }
                setNeutralButton(android.R.string.cancel, null)
            }
                .show()
        }

        confImport.setOnClickListener {
            AlertDialog.Builder(this).apply {
                setTitle(R.string.import_config)
                setMessage(R.string.import_config_desc)
                setPositiveButton(android.R.string.ok) { _, _ ->
                    importConfFile()
                }
                setNegativeButton(android.R.string.cancel, null)
            }
                .show()
        }

        /**
         * Check if changelogs need to be shown when upgrading from older version.
         */
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

        /**
         * Check for updates in background thread.
         */
        updateExecutor.execute {
            isUpdateAvailable()?.let { url ->
                if (!isFinishing && !isDestroyed) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            updateAvailableLink.apply {
                                paintFlags = Paint.UNDERLINE_TEXT_FLAG
                                visibility = View.VISIBLE
                                setOnClickListener {
                                    openWebLink(url)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Method to show latest changes.
     */
    private fun showChangeLog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.version_head)
            .setMessage(R.string.version_desc)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Populate menu.
     * Menu contains option to show changelog.
     */
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_activity_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    /**
     * Click listener on menu.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_changelog -> showChangeLog()
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Check if update is available. Return url string of Github Releases page if update is present.
     * Else returns null.
     *
     * This checks the independently maintained GitHub repository first and the
     * Xposed Modules Repository mirror second. If either reports a newer build,
     * return the corresponding releases page.
     */
    private fun isUpdateAvailable(): String? {

        fun getUpdateStatus(url: String): Boolean {
            var jsonString = ""
            val baos = ByteArrayOutputStream()

            /**
             * Get contents of the file into a string.
             */
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
                    jsonString = baos.toString(Charsets.UTF_8.name())
                }
            } catch (_: Exception) {
                return false
            }

            /**
             * Parse the string as a JSON object.
             */
            return if (jsonString.isNotBlank()) {
                try {
                    val json = JSONObject(jsonString)
                    val remoteVersion = json.getInt(FIELD_LATEST_VERSION_CODE)
                    BuildConfig.VERSION_CODE < remoteVersion
                } catch (_: Exception) {
                    false
                }
            } else false
        }

        /**
         * Check both maintained release sources.
         */
        return when {
            getUpdateStatus(UPDATE_INFO_URL) -> RELEASES_URL
            getUpdateStatus(UPDATE_INFO_URL2) -> RELEASES_URL2
            else -> null
        }
    }

    /**
     * Open any url link
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
     * Creates configuration export file to internal cache.
     * Shares it to other apps.
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

                this.putExtra(Intent.EXTRA_STREAM, confFileShareUri)
                startActivity(Intent.createChooser(this, getString(R.string.share_config_file)))
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.share_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Open a storage location on the device to export the configuration as a document.
     * Uses intent with action [Intent.ACTION_CREATE_DOCUMENT]
     * Also see [configCreateLauncher].
     *
     * Derived from https://gist.github.com/neonankiti/05922cf0a44108a2e2732671ed9ef386
     */
    private fun saveConfFile() {
        val openIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            // filter to only show openable items.
            addCategory(Intent.CATEGORY_OPENABLE)

            // Create a file with the requested Mime type
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, CONF_EXPORT_NAME)
        }
        Toast.makeText(this, R.string.select_a_location, Toast.LENGTH_SHORT).show()
        configCreateLauncher.launch(openIntent)
    }

    /**
     * Intent launcher to start system file picker UI to select location of export.
     * The Uri of the location is present in result.
     * Then call [Utils.writeConfigFile] using that Uri.
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
     * Read a JSON file to get the configurations.
     * Opens system file picker to select the file.
     *
     * https://developer.android.com/training/data-storage/shared/documents-files#open-file
     */
    private fun importConfFile() {
        val openIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        configOpenLauncher.launch(openIntent)
    }

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


    override fun onDestroy() {
        updateExecutor.shutdownNow()
        super.onDestroy()
    }

}
