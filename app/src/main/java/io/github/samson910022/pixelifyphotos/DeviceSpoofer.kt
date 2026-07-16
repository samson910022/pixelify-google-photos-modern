package io.github.samson910022.pixelifyphotos

import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Spoofs device properties by rewriting [android.os.Build] static fields via
 * Java reflection (clear `final`, then [Field.set]), matching the legacy module.
 *
 * Called from [PixelifyModule.onPackageReady] when Google Photos is detected.
 *
 * Device properties are defined in [DeviceProps] and selected via user preferences
 * stored in the module's shared preference file.
 *
 * ## Android 17+ note
 *
 * An earlier guard skipped all Build spoofing on SDK >= 37 based on a speculative
 * SIGSEGV concern. That disabled the core model-name spoof (e.g. UI still showed
 * the real "Pixel 6 Pro"). Reflection writes to `Build` static fields are still
 * attempted on Android 17+; failures are caught and logged. libxposed API 101 is
 * unrelated to whether these fields can be written.
 *
 * Inspired by:
 * https://github.com/itsuki-t/FakeDeviceData/blob/master/src/jp/rmitkt/xposed/fakedevicedata/FakeDeviceData.java
 */
object DeviceSpoofer {

    private const val TAG = "Pixelify"
    private val VERSION_FIELD_NAMES = setOf("INCREMENTAL", "SECURITY_PATCH")

    /**
     * Canonical Android 17 SDK_INT from [DeviceProps.AndroidVersion]
     * ("Android 17", SDK=37). Retained for tests and diagnostics only — not used
     * as a hard skip for spoofing.
     */
    private const val ANDROID_17_SDK_INT = 37

    /**
     * Hook entry point: spoof [android.os.Build] static fields for the target app.
     *
     * @param prefs Remote preferences obtained in the hooked process.
     */
    fun hook(prefs: SharedPreferences?) {
        if (prefs == null) {
            Log.w(TAG, "Remote preferences unavailable, using defaults")
        }

        val verboseLog = prefs?.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false) ?: false

        val deviceName = prefs?.getString(Constants.PREF_DEVICE_TO_SPOOF, DeviceProps.defaultDeviceName)
            ?: DeviceProps.defaultDeviceName
        Log.d(TAG, "Device spoof: $deviceName (sdk=${Build.VERSION.SDK_INT})")

        val deviceEntries = DeviceProps.getDeviceProps(deviceName)

        // Skip if device is unset, explicitly "None", or has no props to spoof.
        if (deviceEntries == null || deviceName == "None" || deviceEntries.props.isEmpty()) {
            Log.d(TAG, "No device spoofing configured, skipping")
            return
        }

        if (Build.VERSION.SDK_INT >= ANDROID_17_SDK_INT) {
            Log.d(
                TAG,
                "Android 17+ detected: applying Build reflection spoof " +
                    "(not skipped; API 101 is unrelated to Build field writes)",
            )
        }

        // ── Spoof android.os.Build static fields ──
        deviceEntries.props.forEach { (key, value) ->
            val targetClass = targetClassForField(key)
            try {
                setStaticField(targetClass, key, value)
                if (verboseLog) {
                    Log.d(TAG, "DEVICE PROPS: ${targetClass.simpleName}.$key - $value")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to spoof ${targetClass.simpleName}.$key", t)
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

        verifySpoof(deviceEntries.props, verboseLog)
    }

    private fun verifySpoof(props: Map<String, String>, verboseLog: Boolean) {
        props.forEach { (key, expected) ->
            try {
                val targetClass = targetClassForField(key)
                val field = targetClass.getDeclaredField(key)
                field.isAccessible = true
                val actual = field.get(null)?.toString()
                if (actual != expected) {
                    Log.w(
                        TAG,
                        "VERIFY FAIL ${targetClass.simpleName}.$key: actual='$actual' expected='$expected'",
                    )
                } else if (verboseLog) {
                    Log.d(TAG, "VERIFY OK ${targetClass.simpleName}.$key='$actual'")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "VERIFY error for $key", t)
            }
        }
    }

    private fun targetClassForField(fieldName: String): Class<*> =
        if (fieldName in VERSION_FIELD_NAMES) Build.VERSION::class.java else Build::class.java

    /**
     * Sets a static field on the given class using Java Reflection.
     *
     * Removes the `final` modifier if present so that the field can be written
     * (standard technique via `Field.accessFlags`).
     */
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

            if (accessFlagsField == null) {
                Log.w(
                    TAG,
                    "Cannot remove final modifier for $fieldName — accessFlags field not found; " +
                        "may fail on final fields",
                )
            }
            accessFlagsField?.setInt(field, field.modifiers and Modifier.FINAL.inv())

            field.set(null, value)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to set static field $fieldName on ${clazz.name}", t)
        }
    }
}
