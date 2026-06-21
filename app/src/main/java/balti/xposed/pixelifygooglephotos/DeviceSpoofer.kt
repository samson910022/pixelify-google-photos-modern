package balti.xposed.pixelifygooglephotos

import android.content.SharedPreferences
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
 * ## Android 17+ Safety
 *
 * Reflection-based modification of static final fields is blocked on Android 17+
 * (SDK_INT >= 37) and would cause a SIGSEGV crash. The early-return guard at the
 * top of [hook] — comparing [Build.VERSION.SDK_INT] against [ANDROID_17_SDK_INT] —
 * skips all Build spoofing entirely on affected runtimes. The SDK value 37 is the
 * canonical value from [DeviceProps.AndroidVersion] ("Android 17", SDK=37).
 *
 * On Android 16 and below, the [setStaticField] method uses a `catch(t: Throwable)`
 * guard as a final safety net against any unexpected exceptions during reflection.
 *
 * Inspired by:
 * https://github.com/itsuki-t/FakeDeviceData/blob/master/src/jp/rmitkt/xposed/fakedevicedata/FakeDeviceData.java
 */
object DeviceSpoofer {

    private const val TAG = "Pixelify"

    /**
     * Android 17 SDK_INT value. Used to guard against reflection-based
     * modification of static final fields (SIGSEGV crash on Android 17+).
     * Source of truth: [DeviceProps.AndroidVersion]("Android 17", SDK=37).
     */
    private const val ANDROID_17_SDK_INT = 37

    /**
     * Hook entry point: spoof [android.os.Build] static fields for the target app.
     *
     * @param classLoader ClassLoader of the target package (provided by [PixelifyModule]).
     */
    fun hook(classLoader: ClassLoader, prefs: SharedPreferences?) {
        // Android 17+ blocks reflection-based modification of static final fields,
        // which would cause a SIGSEGV crash. Skip all Build spoofing entirely.
        if (Build.VERSION.SDK_INT >= ANDROID_17_SDK_INT) {
            Log.w(TAG, "Android 17+ detected: skipping DeviceSpoofer (reflection-based Build spoofing is unsupported)")
            return
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
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to spoof Build.$key", t)
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
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to spoof Build.VERSION.$key", t)
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
    private val accessFlagsField: Field? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        var cls: Class<*> = Field::class.java
        while (true) {
            try {
                val f = cls.getDeclaredField("accessFlags")
                f.isAccessible = true
                return@lazy f
            } catch (t: Throwable) {
                cls = cls.superclass as? Class<*> ?: break
            }
        }
        null
    }

    private fun setStaticField(clazz: Class<*>, fieldName: String, value: Any) {
        Log.v(TAG, "setStaticField: ${clazz.name}.$fieldName = $value")
        try {
            val field: Field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true

            // Remove the final modifier so we can write to the field.
            // On some runtimes (Android 17+) accessFlagsField may be null,
            // in which case we skip final-removal and attempt the set anyway.
            if (accessFlagsField == null) {
                Log.w(TAG, "Cannot remove final modifier for $fieldName — accessFlags field not found; may fail on final fields")
            }
            // Use safe-call to avoid NPE if accessFlagsField is null
            accessFlagsField?.setInt(field, field.modifiers and Modifier.FINAL.inv())

            field.set(null, value)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to set static field $fieldName on ${clazz.name}", t)
        }
    }
}
