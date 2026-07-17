package io.github.samson910022.pixelifyphotos

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spoofs device properties by rewriting [android.os.Build] static fields.
 *
 * Called from [PixelifyModule.onPackageReady] when Google Photos is detected.
 *
 * Write strategy (in order):
 * 1. Classic reflection: clear the Java `final` bit via `Field.accessFlags`, then [Field.set]
 * 2. Fallback: `sun.misc.Unsafe` / `jdk.internal.misc.Unsafe` static field put
 *    (required on newer ART, where static final [Field.set] throws
 *    [IllegalAccessException] even after clearing the reflected modifier bit)
 *
 * After writes, fields are re-read. On VERIFY failure the user is notified
 * (Toast + system notification) instead of failing silently.
 *
 * Legacy module used [de.robv.android.xposed.XposedHelpers.setStaticObjectField],
 * which is also plain [Field.set] after [Field.setAccessible]; on Android 17 ART
 * that path alone is insufficient. libxposed API 101 is unrelated to field writes.
 *
 * Inspired by:
 * https://github.com/itsuki-t/FakeDeviceData/blob/master/src/jp/rmitkt/xposed/fakedevicedata/FakeDeviceData.java
 */
object DeviceSpoofer {

    private const val TAG = "Pixelify"
    private val VERSION_FIELD_NAMES = setOf("INCREMENTAL", "SECURITY_PATCH")

    /**
     * Canonical Android 17 SDK_INT from [DeviceProps.AndroidVersion]
     * ("Android 17", SDK=37). Diagnostic only — not used as a hard skip.
     */
    private const val ANDROID_17_SDK_INT = 37

    private const val NOTIFY_ID = 0x50495846 // 'PIXF'
    private const val CHANNEL_ID = "pixelify_infinity_device_spoof"
    private const val CHANNEL_NAME = "Pixelify Infinity"

    /** Ensures failure UI is shown at most once per process load. */
    private val failureUiShown = AtomicBoolean(false)

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
                "Android 17+ detected: applying Build spoof with reflection+Unsafe fallback " +
                    "(not skipped; API 101 is unrelated to Build field writes)",
            )
        }

        // ── Spoof android.os.Build static fields ──
        deviceEntries.props.forEach { (key, value) ->
            val targetClass = targetClassForField(key)
            val ok = setStaticField(targetClass, key, value)
            if (verboseLog) {
                Log.d(
                    TAG,
                    "DEVICE PROPS: ${targetClass.simpleName}.$key - $value (ok=$ok)",
                )
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
            val ok = setStaticField(Build.VERSION::class.java, key, value)
            if (verboseLog) Log.d(TAG, "VERSION SPOOF: $key - $value (ok=$ok)")
        }

        val failed = verifySpoof(deviceEntries.props, verboseLog)
        if (failed.isNotEmpty()) {
            Log.e(
                TAG,
                "Device spoof VERIFY failed for $deviceName: ${failed.joinToString()}",
            )
            scheduleSpoofFailureUi(deviceName, failed)
        } else {
            Log.d(TAG, "Device spoof VERIFY OK for $deviceName")
        }
    }

    /**
     * Re-read spoofed fields and return human-readable failure entries.
     */
    private fun verifySpoof(props: Map<String, String>, verboseLog: Boolean): List<String> {
        val failed = mutableListOf<String>()
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
                    failed += "$key(actual=$actual)"
                } else if (verboseLog) {
                    Log.d(TAG, "VERIFY OK ${targetClass.simpleName}.$key='$actual'")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "VERIFY error for $key", t)
                failed += "$key(error)"
            }
        }
        return failed
    }

    private fun targetClassForField(fieldName: String): Class<*> =
        if (fieldName in VERSION_FIELD_NAMES) Build.VERSION::class.java else Build::class.java

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

    /**
     * Sets a static field. Returns true only if a subsequent read matches [value].
     */
    private fun setStaticField(clazz: Class<*>, fieldName: String, value: Any?): Boolean {
        Log.v(TAG, "setStaticField: ${clazz.name}.$fieldName = $value")
        return try {
            val field: Field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            val coerced = coerceValue(field, value)

            // Strategy 1: clear reflected FINAL bit + Field.set (legacy path).
            clearFinalModifier(field)
            var wrote = false
            try {
                field.set(null, coerced)
                wrote = true
                Log.v(TAG, "setStaticField $fieldName via Field.set")
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "Field.set failed for ${clazz.simpleName}.$fieldName " +
                        "(${t.javaClass.simpleName}: ${t.message}); trying Unsafe",
                )
            }

            // Strategy 2: Unsafe static put (needed when ART rejects final Field.set).
            if (!fieldValueMatches(field, coerced)) {
                if (UnsafeStatic.put(field, coerced)) {
                    wrote = true
                    Log.d(TAG, "setStaticField $fieldName via Unsafe")
                }
            }

            val ok = fieldValueMatches(field, coerced)
            if (!ok) {
                Log.e(
                    TAG,
                    "Failed to set static field $fieldName on ${clazz.name} " +
                        "(wroteAttempt=$wrote, actual='${field.get(null)}')",
                )
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to set static field $fieldName on ${clazz.name}", t)
            false
        }
    }

    private fun clearFinalModifier(field: Field) {
        try {
            accessFlagsField?.setInt(field, field.modifiers and Modifier.FINAL.inv())
        } catch (t: Throwable) {
            Log.v(TAG, "clearFinalModifier failed for ${field.name}: ${t.message}")
        }
    }

    private fun coerceValue(field: Field, value: Any?): Any? {
        val type = field.type
        return try {
            when (type) {
                Integer.TYPE, Integer::class.java -> when (value) {
                    is Int -> value
                    is Number -> value.toInt()
                    is String -> value.toInt()
                    else -> value
                }
                java.lang.Long.TYPE, java.lang.Long::class.java -> when (value) {
                    is Long -> value
                    is Number -> value.toLong()
                    is String -> value.toLong()
                    else -> value
                }
                java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> when (value) {
                    is Boolean -> value
                    is String -> value.equals("true", ignoreCase = true) || value == "1"
                    is Number -> value.toInt() != 0
                    else -> value
                }
                java.lang.Short.TYPE, java.lang.Short::class.java -> when (value) {
                    is Short -> value
                    is Number -> value.toShort()
                    is String -> value.toShort()
                    else -> value
                }
                java.lang.Byte.TYPE, java.lang.Byte::class.java -> when (value) {
                    is Byte -> value
                    is Number -> value.toByte()
                    is String -> value.toByte()
                    else -> value
                }
                Character.TYPE, Character::class.java -> when (value) {
                    is Char -> value
                    is String -> if (value.isNotEmpty()) value[0] else value
                    is Number -> value.toInt().toChar()
                    else -> value
                }
                java.lang.Float.TYPE, java.lang.Float::class.java -> when (value) {
                    is Float -> value
                    is Number -> value.toFloat()
                    is String -> value.toFloat()
                    else -> value
                }
                java.lang.Double.TYPE, java.lang.Double::class.java -> when (value) {
                    is Double -> value
                    is Number -> value.toDouble()
                    is String -> value.toDouble()
                    else -> value
                }
                else -> value
            }
        } catch (t: Throwable) {
            Log.w(TAG, "coerceValue failed for ${field.name}, using raw value", t)
            value
        }
    }

    private fun fieldValueMatches(field: Field, expected: Any?): Boolean {
        return try {
            val actual = field.get(null)
            when {
                actual == null && expected == null -> true
                actual == null || expected == null -> false
                expected is Number && actual is Number ->
                    expected.toLong() == actual.toLong() ||
                        expected.toDouble() == actual.toDouble()
                else -> actual == expected || actual.toString() == expected.toString()
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Defer UI until the hooked app has an Application / main looper.
     * Runs inside the Google Photos process.
     */
    private fun scheduleSpoofFailureUi(deviceName: String, failed: List<String>) {
        val looper = Looper.getMainLooper()
        if (looper == null) {
            Log.w(TAG, "No main looper; cannot show spoof-failure UI")
            return
        }
        val handler = Handler(looper)
        val show = Runnable { showSpoofFailureUi(deviceName, failed) }
        handler.post(show)
        // PackageReady can run before Application is attached; retry a few times.
        handler.postDelayed(show, 2500L)
        handler.postDelayed(show, 6000L)
    }

    private fun showSpoofFailureUi(deviceName: String, failed: List<String>) {
        if (!failureUiShown.compareAndSet(false, true)) return
        val app = currentApplication()
        if (app == null) {
            // Allow the delayed retry to try again.
            failureUiShown.set(false)
            Log.w(TAG, "Application not ready; will retry spoof-failure UI")
            return
        }

        val title = "Pixelify Infinity"
        val detail = failed.take(4).joinToString()
        val more = if (failed.size > 4) " (+${failed.size - 4})" else ""
        val text = buildSpoofFailureText(app, deviceName, detail, more)

        try {
            Toast.makeText(app, "$title: $text", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Log.e(TAG, "Toast failed for spoof failure", t)
        }

        try {
            postFailureNotification(app, title, text)
        } catch (t: Throwable) {
            Log.e(TAG, "Notification failed for spoof failure", t)
        }
    }

    private fun buildSpoofFailureText(
        context: Context,
        deviceName: String,
        detail: String,
        more: String,
    ): String {
        val lang = try {
            context.resources.configuration.locales[0]?.language
        } catch (t: Throwable) {
            null
        }
        return if (lang == "zh") {
            "裝置模擬失敗（$deviceName）。Build 欄位未變更：$detail$more。請查看 logcat 標籤 Pixelify。"
        } else {
            "Device spoof failed ($deviceName). Build fields unchanged: $detail$more. See logcat tag Pixelify."
        }
    }

    /**
     * Best-effort notification inside the hooked process.
     *
     * Declares [android.Manifest.permission.POST_NOTIFICATIONS] in our manifest for lint /
     * module-process use. When running inside Google Photos, the host app's grant state
     * applies; Toast remains the always-on fallback if notify is blocked.
     */
    @SuppressLint("NotificationPermission")
    private fun postFailureNotification(context: Context, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        // Android 13+: if the host process revoked notifications, skip quietly.
        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled in host process; Toast-only spoof failure alert")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Device spoof diagnostics for Pixelify Infinity"
            }
            nm.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        nm.notify(NOTIFY_ID, notification)
        Log.d(TAG, "Posted spoof-failure notification")
    }

    private fun currentApplication(): Application? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThreadClass.getMethod("currentApplication")
            currentApplication.invoke(null) as? Application
        } catch (t: Throwable) {
            Log.v(TAG, "currentApplication unavailable: ${t.message}")
            null
        }
    }

    /**
     * Reflective access to Unsafe for static field writes when [Field.set] is rejected.
     */
    private object UnsafeStatic {
        private data class Handle(
            val unsafe: Any,
            val staticFieldBase: Method,
            val staticFieldOffset: Method,
            val putObject: Method,
            val putInt: Method,
            val putLong: Method,
            val putBoolean: Method,
            val putFloat: Method,
            val putDouble: Method,
            val putShort: Method,
            val putByte: Method,
            val putChar: Method,
        )

        private val handle: Handle? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            openUnsafe("sun.misc.Unsafe") ?: openUnsafe("jdk.internal.misc.Unsafe")
        }

        private fun openUnsafe(className: String): Handle? {
            return try {
                val cls = Class.forName(className)
                val instance = resolveUnsafeInstance(cls) ?: return null
                Handle(
                    unsafe = instance,
                    staticFieldBase = cls.getMethod("staticFieldBase", Field::class.java),
                    staticFieldOffset = cls.getMethod("staticFieldOffset", Field::class.java),
                    putObject = cls.getMethod(
                        "putObject",
                        Object::class.java,
                        Long::class.javaPrimitiveType,
                        Object::class.java,
                    ),
                    putInt = cls.getMethod(
                        "putInt",
                        Object::class.java,
                        Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ),
                    putLong = cls.getMethod(
                        "putLong",
                        Object::class.java,
                        Long::class.javaPrimitiveType,
                        Long::class.javaPrimitiveType,
                    ),
                    putBoolean = cls.getMethod(
                        "putBoolean",
                        Object::class.java,
                        Long::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    ),
                    putFloat = cls.getMethod(
                        "putFloat",
                        Object::class.java,
                        Long::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                    ),
                    putDouble = cls.getMethod(
                        "putDouble",
                        Object::class.java,
                        Long::class.javaPrimitiveType,
                        Double::class.javaPrimitiveType,
                    ),
                    putShort = cls.getMethod(
                        "putShort",
                        Object::class.java,
                        Long::class.javaPrimitiveType,
                        Short::class.javaPrimitiveType,
                    ),
                    putByte = cls.getMethod(
                        "putByte",
                        Object::class.java,
                        Long::class.javaPrimitiveType,
                        Byte::class.javaPrimitiveType,
                    ),
                    putChar = cls.getMethod(
                        "putChar",
                        Object::class.java,
                        Long::class.javaPrimitiveType,
                        Char::class.javaPrimitiveType,
                    ),
                ).also {
                    Log.d(TAG, "Unsafe static writer ready via $className")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Unsafe unavailable for $className: ${t.message}")
                null
            }
        }

        private fun resolveUnsafeInstance(cls: Class<*>): Any? {
            // Prefer theUnsafe / THE_ONE fields; avoid getUnsafe() which checks caller.
            val fieldNames = listOf("theUnsafe", "THE_ONE", "unsafe")
            for (name in fieldNames) {
                try {
                    val f = cls.getDeclaredField(name)
                    f.isAccessible = true
                    val v = f.get(null)
                    if (v != null) return v
                } catch (_: Throwable) {
                }
            }
            return try {
                val ctor = cls.getDeclaredConstructor()
                ctor.isAccessible = true
                ctor.newInstance()
            } catch (_: Throwable) {
                null
            }
        }

        fun put(field: Field, value: Any?): Boolean {
            val h = handle
            if (h == null) {
                Log.w(TAG, "Unsafe handle not available; cannot put ${field.name}")
                return false
            }
            return try {
                val base = h.staticFieldBase.invoke(h.unsafe, field)
                val offset = (h.staticFieldOffset.invoke(h.unsafe, field) as Long)
                when (field.type) {
                    Integer.TYPE -> h.putInt.invoke(h.unsafe, base, offset, value as Int)
                    java.lang.Long.TYPE -> h.putLong.invoke(h.unsafe, base, offset, value as Long)
                    java.lang.Boolean.TYPE ->
                        h.putBoolean.invoke(h.unsafe, base, offset, value as Boolean)
                    java.lang.Float.TYPE ->
                        h.putFloat.invoke(h.unsafe, base, offset, value as Float)
                    java.lang.Double.TYPE ->
                        h.putDouble.invoke(h.unsafe, base, offset, value as Double)
                    java.lang.Short.TYPE ->
                        h.putShort.invoke(h.unsafe, base, offset, value as Short)
                    java.lang.Byte.TYPE ->
                        h.putByte.invoke(h.unsafe, base, offset, value as Byte)
                    Character.TYPE -> h.putChar.invoke(h.unsafe, base, offset, value as Char)
                    else -> h.putObject.invoke(h.unsafe, base, offset, value)
                }
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Unsafe.put failed for ${field.declaringClass.name}.${field.name}", t)
                false
            }
        }
    }
}
