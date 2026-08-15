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
import io.github.samson910022.pixelifyphotos.Constants.PREF_LAST_VERSION
import io.github.samson910022.pixelifyphotos.Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL
import io.github.samson910022.pixelifyphotos.Constants.PREF_SPOOF_FEATURES_LIST
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
    }

    /**
     * Force-stop each package via root. Used for Photos plus other LSPosed-scoped apps.
     */
    fun forceStopPackages(packageNames: Collection<String>, context: Context) {
        val packages = packageNames
            .filter { SpoofedPackageTracker.isValidPackageName(it) }
            .distinct()
        if (packages.isEmpty()) return

        Toast.makeText(context, R.string.killing_please_wait, Toast.LENGTH_SHORT).show()

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
                    Toast.makeText(context, R.string.failed_to_stop_package, Toast.LENGTH_SHORT).show()
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", firstFailed, null)
                    }
                    context.startActivity(intent)
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
     * @param pref SharedPreference instance.
     * Should be obtained via [android.content.Context.getSharedPreferences] for
     * UI-side access, or via [io.github.libxposed.service.XposedService.getRemotePreferences]
     * from the Xposed hook side.
     */
    fun writeConfigFile(context: Context, uri: Uri, pref: SharedPreferences?) {

        // List of keys from shared preference which need not be copied to file.
        val fieldsNotToCopy = listOf(PREF_LAST_VERSION, PREF_SPOOF_FEATURES_LIST)

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->

                val jsonObject = JSONObject()
                pref?.all?.let { allPrefs ->
                    for (key in allPrefs.keys) {
                        if (key !in fieldsNotToCopy) jsonObject.put(key, allPrefs[key])
                    }
                }

                // Store PREF_SPOOF_FEATURES_LIST
                pref?.getStringSet(PREF_SPOOF_FEATURES_LIST, setOf())?.let {
                    jsonObject.put(PREF_SPOOF_FEATURES_LIST, JSONArray(it.toTypedArray()))
                }

                writer.write(jsonObject.toString(4))
            }
        }
    }

    /**
     * Read an exported JSON file and stores entries in shared preference.
     *
     * @param context Activity context
     * @param uri Uri of file to read from.
     * @param pref SharedPreference instance.
     * Should be obtained via [android.content.Context.getSharedPreferences] for
     * UI-side access, or via [io.github.libxposed.service.XposedService.getRemotePreferences]
     * from the Xposed hook side.
     */
    fun readConfigFile(context: Context, uri: Uri, pref: SharedPreferences?) {

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
        val jsonObject = JSONObject(jsonBytes.toString(Charsets.UTF_8))

        /**
         * In inbuilt function exists to convert JSONArray to List.
         */
        fun convertJsonArrayToList(jsonArray: JSONArray): List<String> {
            val list = ArrayList<String>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray[i].toString())
            }
            return list
        }

        // Validate before writing
        val importedDeviceName = jsonObject.optString(PREF_DEVICE_TO_SPOOF, "")
        val importedVersion = jsonObject.optString(PREF_SPOOF_ANDROID_VERSION_MANUAL, "")

        val deviceIsValid = importedDeviceName.isEmpty() || DeviceProps.getDeviceProps(importedDeviceName) != null
        val versionIsValid = importedVersion.isEmpty() || DeviceProps.getAndroidVersionFromLabel(importedVersion) != null

        if (!deviceIsValid && importedDeviceName.isNotEmpty()) {
            Toast.makeText(context, R.string.invalid_imported_device, Toast.LENGTH_LONG).show()
        }
        if (!versionIsValid && importedVersion.isNotEmpty()) {
            Toast.makeText(context, R.string.invalid_imported_android_version, Toast.LENGTH_LONG).show()
        }

        /**
         * Check for field and store in shared prefs.
         */
        pref?.edit()?.apply {

            PREF_SPOOF_FEATURES_LIST.let { key ->
                jsonObject.optJSONArray(key)?.let {
                    putStringSet(key, convertJsonArrayToList(it).toSet())
                }
            }

            PREF_DEVICE_TO_SPOOF.let { key ->
                val v = jsonObject.optString(key)
                if (v.isNotEmpty() && deviceIsValid) {
                    putString(key, v)
                }
            }

            fun putBooleanIfPresent(key: String) {
                val value = jsonObject.opt(key)
                if (value is Boolean) putBoolean(key, value)
            }

            putBooleanIfPresent(PREF_OVERRIDE_ROM_FEATURE_LEVELS)

            /** Advanced options */

            putBooleanIfPresent(PREF_ENABLE_VERBOSE_LOGS)
            putBooleanIfPresent(PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE)

            PREF_SPOOF_ANDROID_VERSION_MANUAL.let { key ->
                val v = jsonObject.optString(key)
                if (v.isNotEmpty() && versionIsValid) {
                    putString(key, v)
                }
            }

            apply()
        }
    }
}
