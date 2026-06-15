package balti.xposed.pixelifygooglephotos

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import balti.xposed.pixelifygooglephotos.Constants.PREF_DEVICE_TO_SPOOF
import balti.xposed.pixelifygooglephotos.Constants.PREF_ENABLE_VERBOSE_LOGS
import balti.xposed.pixelifygooglephotos.Constants.PREF_LAST_VERSION
import balti.xposed.pixelifygooglephotos.Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS
import balti.xposed.pixelifygooglephotos.Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE
import balti.xposed.pixelifygooglephotos.Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL
import balti.xposed.pixelifygooglephotos.Constants.PREF_SPOOF_FEATURES_LIST
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit


/**
 * Utilities class for various functions.
 */
class Utils {

    /**
     * Used to force close an app.
     *
     * Uses root to stop an application.
     */
    fun forceStopPackage(packageName: String, context: Context) {
        require(packageName.matches(Regex("^[a-zA-Z0-9._]+$"))) { "Invalid package name: $packageName" }
        try {
            Toast.makeText(context, R.string.killing_please_wait, Toast.LENGTH_SHORT).show()
            val process = ProcessBuilder("su", "-c", "am force-stop $packageName")
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.failed_to_stop_package, Toast.LENGTH_SHORT).show()
            val intent = Intent().apply {
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                data = Uri.fromParts("package", packageName, null)
            }
            context.startActivity(intent)
        }
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

        var jsonObject = JSONObject()
        val baos = ByteArrayOutputStream()

        val inputStream = context.contentResolver.openInputStream(uri)
        inputStream?.use { input ->
            baos.use { output ->
                input.copyTo(output)
            }
            jsonObject = JSONObject(baos.toString())
        }

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
            Toast.makeText(context, "Invalid device in imported config: $importedDeviceName. Skipping.", Toast.LENGTH_LONG).show()
        }
        if (!versionIsValid && importedVersion.isNotEmpty()) {
            Toast.makeText(context, "Invalid Android version in imported config: $importedVersion. Skipping.", Toast.LENGTH_LONG).show()
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

            PREF_OVERRIDE_ROM_FEATURE_LEVELS.let { key ->
                jsonObject.optBoolean(key, true).let {
                    putBoolean(key, it)
                }
            }

            /** Advanced options */

            PREF_ENABLE_VERBOSE_LOGS.let { key ->
                jsonObject.optBoolean(key, true).let {
                    putBoolean(key, it)
                }
            }

            PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE.let { key ->
                jsonObject.optBoolean(key, true).let {
                    putBoolean(key, it)
                }
            }

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
