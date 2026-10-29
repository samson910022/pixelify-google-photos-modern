package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import io.github.samson910022.pixelifyphotos.Constants.PREF_DEVICE_TO_SPOOF
import io.github.samson910022.pixelifyphotos.Constants.PREF_ENABLE_VERBOSE_LOGS
import io.github.samson910022.pixelifyphotos.Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_FEATURES_LIST
import io.github.samson910022.pixelifyphotos.Constants.PREF_USE_CLASSIC_UI
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit


/**
 * Utilities class for various functions.
 */
class Utils {

    companion object {
        private const val MAX_CONFIG_BYTES = 1024 * 1024

        /**
         * Parses and validates a configuration document into a pure in-memory
         * staged plan. This performs no document I/O and never touches
         * SharedPreferences, so callers may run it before taking a settings lock.
         *
         * Validation semantics are intentionally narrow: unknown keys are ignored,
         * missing keys leave existing values untouched, and an invalid device or
         * Android version field is skipped while the remaining valid fields still
         * apply. [ConfigImportPlan.hasInvalidDevice] and
         * [ConfigImportPlan.hasInvalidAndroidVersion] let the caller report the
         * localized field warnings.
         */
        internal fun buildConfigImportPlan(jsonObject: JSONObject): ConfigImportPlan {
            /**
             * Inbuilt function exists to convert JSONArray to List.
             */
            fun convertJsonArrayToList(jsonArray: JSONArray): List<String> {
                val list = ArrayList<String>()
                for (i in 0 until jsonArray.length()) {
                    list.add(jsonArray[i].toString())
                }
                return list
            }

            // Validate before staging. Empty fields mean "not provided" and must leave
            // the existing setting untouched rather than writing an empty value.
            val importedDeviceName = jsonObject.optString(PREF_DEVICE_TO_SPOOF, "")
            val importedVersion = jsonObject.optString(PREF_SPOOF_ANDROID_VERSION_MANUAL, "")

            val deviceIsValid = importedDeviceName.isEmpty() ||
                DeviceProps.getDeviceProps(importedDeviceName) != null
            val versionIsValid = importedVersion.isEmpty() ||
                DeviceProps.getAndroidVersionFromLabel(importedVersion) != null

            val stringValues = mutableMapOf<String, String>()
            if (importedDeviceName.isNotEmpty() && deviceIsValid) {
                stringValues[PREF_DEVICE_TO_SPOOF] = importedDeviceName
            }
            if (importedVersion.isNotEmpty() && versionIsValid) {
                stringValues[PREF_SPOOF_ANDROID_VERSION_MANUAL] = importedVersion
            }

            val booleanValues = mutableMapOf<String, Boolean>()
            fun stageBooleanIfPresent(key: String) {
                val value = jsonObject.opt(key)
                if (value is Boolean) {
                    booleanValues[key] = value
                }
            }
            stageBooleanIfPresent(PREF_OVERRIDE_ROM_FEATURE_LEVELS)

            /** Advanced options */
            stageBooleanIfPresent(PREF_ENABLE_VERBOSE_LOGS)
            stageBooleanIfPresent(PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE)
            stageBooleanIfPresent(PREF_USE_CLASSIC_UI)

            val stringSetValues = mutableMapOf<String, Set<String>>()
            jsonObject.optJSONArray(PREF_SPOOF_FEATURES_LIST)?.let { features ->
                stringSetValues[PREF_SPOOF_FEATURES_LIST] = convertJsonArrayToList(features).toSet()
            }

            return ConfigImportPlan(
                stringValues = stringValues,
                booleanValues = booleanValues,
                stringSetValues = stringSetValues,
                hasInvalidDevice = importedDeviceName.isNotEmpty() && !deviceIsValid,
                hasInvalidAndroidVersion = importedVersion.isNotEmpty() && !versionIsValid,
            )
        }
    }

    /**
     * Force-stop each package via root. Used for Photos plus other LSPosed-scoped apps.
     */
    fun forceStopPackages(packageNames: Collection<String>, context: Context) {
        val packages = packageNames
            .filter { SpoofedPackageTracker.isValidPackageName(it) }
            .distinct()
        if (packages.isEmpty()) return
        val appContext = context.applicationContext

        Toast.makeText(appContext, R.string.killing_please_wait, Toast.LENGTH_SHORT).show()

        Thread({
            var firstFailed: String? = null
            for (packageName in packages) {
                if (packageName != Constants.PACKAGE_NAME_GOOGLE_PHOTOS) {
                    Log.w(
                        "Pixelify",
                        "Force-stopping non-Photos scoped package $packageName " +
                            "(advanced multi-app scope; root access is used)"
                    )
                }
                var process: Process? = null
                val stopped = try {
                    val runningProcess = ProcessBuilder("su", "-c", "am force-stop $packageName")
                        .redirectErrorStream(true)
                        .start()
                    process = runningProcess
                    val completed = runningProcess.waitFor(5, TimeUnit.SECONDS)
                    if (!completed) {
                        runningProcess.destroyForcibly()
                        false
                    } else {
                        runningProcess.exitValue() == 0
                    }
                } catch (_: Exception) {
                    process?.destroyForcibly()
                    false
                }
                if (!stopped && firstFailed == null) {
                    firstFailed = packageName
                }
            }

            if (firstFailed != null) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, R.string.failed_to_stop_package, Toast.LENGTH_SHORT).show()
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", firstFailed, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { appContext.startActivity(intent) }
                }
            }
        }, "pixelify-force-stop").start()
    }

    /**
     * Launch an app.
     */
    fun openApplication(packageName: String, context: Context) {
        try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                context.startActivity(launchIntent)
            } else {
                Toast.makeText(context, R.string.failed_to_launch_package, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, R.string.failed_to_launch_package, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Write all keys of shared preference in a file as a JSON string.
     *
     * @param context Activity context
     * @param uri Uri of file to write to.
     * Using uri as it can be used to write a file in internal cache directory,
     * as well as an external location opened using [Intent.ACTION_CREATE_DOCUMENT].
     * @param configuration A validated, source-authorized configuration snapshot.
     */
    fun writeConfigFile(context: Context, uri: Uri, configuration: JSONObject) {

        val outputStream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Unable to open configuration output")
        outputStream.use {
            BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                writer.write(configuration.toString(4))
            }
        }
    }

    /**
     * Read and validate an exported JSON file into an in-memory staged import plan.
     *
     * The document stream, the 1 MiB size limit, JSON parsing, and schema/value
     * validation all run on the caller's thread and never touch SharedPreferences,
     * so callers must not hold a settings lock while this runs. Persist the
     * returned plan with [ConfigImportPlan.apply] from inside the guarded settings
     * write so the remote/local source is re-authorized first.
     *
     * @param context Activity context, used only to open the document stream.
     * @param uri Uri of file to read from.
     * @throws IOException if the document cannot be opened or exceeds 1 MiB.
     * @throws org.json.JSONException if the document is not a JSON object.
     */
    internal fun readConfigFile(context: Context, uri: Uri): ConfigImportPlan {

        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open configuration file")
        val jsonBytes = inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_CONFIG_BYTES) {
                    throw IOException("Configuration file exceeds 1 MiB")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return buildConfigImportPlan(JSONObject(jsonBytes.toString(Charsets.UTF_8)))
    }
}

/**
 * Validated, in-memory configuration import staged outside the settings lock.
 *
 * [apply] only writes pre-mapped values: no document I/O, JSON parsing, or field
 * validation happens here, so the guarded settings operation stays short and the
 * caller reports invalid-field warnings from the staged flags.
 */
internal class ConfigImportPlan(
    private val stringValues: Map<String, String>,
    private val booleanValues: Map<String, Boolean>,
    private val stringSetValues: Map<String, Set<String>>,
    val hasInvalidDevice: Boolean,
    val hasInvalidAndroidVersion: Boolean,
) {
    /** Exactly the preference keys [apply] writes. */
    val editedKeys: Set<String> = buildSet {
        addAll(stringValues.keys)
        addAll(booleanValues.keys)
        addAll(stringSetValues.keys)
    }

    /** Writes the staged values through one editor; callers must hold the settings guard. */
    fun apply(preferences: SharedPreferences) {
        val editor = preferences.edit()
        stringValues.forEach { (key, value) -> editor.putString(key, value) }
        booleanValues.forEach { (key, value) -> editor.putBoolean(key, value) }
        stringSetValues.forEach { (key, value) -> editor.putStringSet(key, value.toMutableSet()) }
        editor.apply()
    }
}
