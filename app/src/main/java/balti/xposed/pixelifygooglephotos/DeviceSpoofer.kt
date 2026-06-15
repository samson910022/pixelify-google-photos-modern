package balti.xposed.pixelifygooglephotos

import android.os.Build
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Spoofs device properties by modifying [android.os.Build] static fields
 * using Java Reflection instead of XposedHelpers.
 *
 * Called from [PixelifyModule.onPackageLoaded] when Google Photos is detected.
 *
 * Device properties are defined in [DeviceProps] and selected via user preferences
 * stored in the module's shared preference file.
 *
 * Inspired by:
 * https://github.com/itsuki-t/FakeDeviceData/blob/master/src/jp/rmitkt/xposed/fakedevicedata/FakeDeviceData.java
 */
object DeviceSpoofer {

    private const val TAG = "Pixelify"

    /**
     * Hook entry point: spoof [android.os.Build] static fields for the target app.
     *
     * @param classLoader ClassLoader of the target package (provided by [PixelifyModule]).
     */
    fun hook(classLoader: ClassLoader) {
        // ── Read preferences via libxposed remote preferences ──
        val prefs = try {
            App.mService?.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get remote preferences", e)
            null
        }

        if (prefs == null) {
            Log.w(TAG, "Remote preferences unavailable, using defaults")
        }

        val verboseLog = prefs?.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false) ?: false

        // ── Resolve device to spoof ──
        // Read the device name from preferences; fall back to the default (Pixel 5).
        val deviceName = prefs?.getString(Constants.PREF_DEVICE_TO_SPOOF, DeviceProps.defaultDeviceName)
            ?: DeviceProps.defaultDeviceName
        if (verboseLog) Log.d(TAG, "Device spoof: $deviceName")

        val deviceEntries = DeviceProps.getDeviceProps(deviceName)

        // Skip if device is unset, explicitly "None", or has no props to spoof.
        if (deviceEntries == null || deviceName == "None" || deviceEntries.props.isEmpty()) {
            Log.d(TAG, "No device spoofing configured, skipping")
            return
        }

        // ── Spoof android.os.Build static fields ──
        deviceEntries.props.forEach { (key, value) ->
            try {
                setStaticField(Build::class.java, key, value)
                if (verboseLog) Log.d(TAG, "DEVICE PROPS: $key - $value")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to spoof Build.$key", e)
            }
        }

        // ── Spoof android.os.Build.VERSION fields ──
        val followDevice = prefs?.getBoolean(
            Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, false
        ) ?: false

        val androidVersion = if (followDevice) {
            deviceEntries.androidVersion
        } else {
            val manualVersionLabel = prefs?.getString(
                Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL, null
            )
            manualVersionLabel?.let { DeviceProps.getAndroidVersionFromLabel(it) }
        }

        androidVersion?.getAsMap()?.forEach { (key, value) ->
            try {
                setStaticField(Build.VERSION::class.java, key, value)
                if (verboseLog) Log.d(TAG, "VERSION SPOOF: $key - $value")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to spoof Build.VERSION.$key", e)
            }
        }
    }

    /**
     * Sets a static field on the given class using Java Reflection.
     *
     * Removes the `final` modifier if present so that the field can be written
     * (standard technique via `Field.accessFlags`).
     *
     * @param clazz The class containing the static field (e.g. `Build::class.java`).
     * @param fieldName The name of the static field to set.
     * @param value The value to assign. Primitive types will be auto-unwrapped
     *              by [Field.set].
     */
    // Cache the accessFlags field from the Field class hierarchy
    private val accessFlagsField: Field by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        var cls: Class<*> = Field::class.java
        while (cls != null) {
            try {
                val f = cls.getDeclaredField("accessFlags")
                f.isAccessible = true
                return@lazy f
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException("accessFlags not found in Field class hierarchy")
    }

    private fun setStaticField(clazz: Class<*>, fieldName: String, value: Any) {
        val field: Field = clazz.getDeclaredField(fieldName)
        field.isAccessible = true

        // Remove the final modifier so we can write to the field
        accessFlagsField.setInt(field, field.modifiers and Modifier.FINAL.inv())

        field.set(null, value)
    }
}
