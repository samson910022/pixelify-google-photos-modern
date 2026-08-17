package io.github.samson910022.pixelifyphotos

import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Spoofs device properties by rewriting [android.os.Build] static fields and
 * intercepting [android.os.SystemProperties] reads as a secondary signal path.
 *
 * Write strategies (success = post-write readback match):
 * 1. Clear reflected `final` + [Field.set]
 * 2. Multi-variant Unsafe static put (`putObject` / `putReference` / volatile,
 *    `staticFieldBase` and declaring-class bases)
 * 3. JNI fallback (`libpixelify_build`): `SetStatic*Field` + readback
 *    (no ArtField memory patching — unsafe on modern ART layouts)
 *
 * Native library is loaded from the **module** [ApplicationInfo.nativeLibraryDir]
 * first, then extracted into the **host** process `codeCacheDir` when needed
 * (LSPosed runs inside Google Photos; bare `System.loadLibrary` usually fails).
 *
 * VERIFY failures surface via Toast + notification (once per process).
 */
object DeviceSpoofer {

    private const val TAG = "Pixelify"
    private val VERSION_FIELD_NAMES = setOf("INCREMENTAL", "SECURITY_PATCH", "RELEASE", "SDK", "SDK_INT")

    /** Diagnostic only — not a hard skip. */
    private const val ANDROID_17_SDK_INT = 37

    private const val NOTIFY_ID = 0x50495846 // 'PIXF'
    private const val CHANNEL_ID = "pixelify_infinity_device_spoof"
    private const val CHANNEL_NAME = "Pixelify Infinity"

    private val failureUiShown = AtomicBoolean(false)
    private val hiddenApiExempted = AtomicBoolean(false)
    private val systemPropertiesHooked = AtomicBoolean(false)
    private val nativeLoadLock = Any()
    @Volatile private var nativeReady = false
    private val pendingFailureUi = mutableListOf<Runnable>()
    private val pendingFailureUiLock = Any()

    private fun mainHandlerOrNull(): Handler? {
        return try {
            val looper = Looper.getMainLooper() ?: return null
            Handler(looper)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * @param allowFailureUi when false (early package load), VERIFY still logs but does not
     * toast/notify — package-ready re-apply may succeed after host Application exists.
     */
    fun hook(module: XposedModule?, prefs: SharedPreferences?, allowFailureUi: Boolean = true) {
        if (prefs == null) {
            Log.w(TAG, "Remote preferences unavailable, using defaults")
        }

        val verboseLog = prefs?.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false) ?: false
        val deviceName = prefs?.getString(Constants.PREF_DEVICE_TO_SPOOF, DeviceProps.defaultDeviceName)
            ?: DeviceProps.defaultDeviceName
        Log.d(TAG, "Device spoof: $deviceName (sdk=${Build.VERSION.SDK_INT})")

        val deviceEntries = DeviceProps.getDeviceProps(deviceName)
        if (deviceEntries == null || deviceName == "None" || deviceEntries.props.isEmpty()) {
            Log.d(TAG, "No device spoofing configured, skipping")
            return
        }

        if (verboseLog) {
            Log.d(TAG, "Applying Build spoof for $deviceName")
        }

        ensureNativeLoaded(module)
        exemptHiddenApis()

        deviceEntries.props.forEach { (key, value) ->
            val targetClass = targetClassForField(key)
            val ok = setStaticField(targetClass, key, value)
            if (verboseLog) {
                Log.d(TAG, "DEVICE PROPS: ${targetClass.simpleName}.$key - $value (ok=$ok)")
            }
        }

        val followDevice = prefs?.getBoolean(
            Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE, false
        ) ?: false
        val androidVersion = if (followDevice) {
            deviceEntries.androidVersion
        } else {
            prefs?.getString(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL, null)
                ?.let { DeviceProps.getAndroidVersionFromLabel(it) }
        }
        val versionProps = linkedMapOf<String, String>()
        androidVersion?.getAsMap()?.forEach { (key, value) ->
            val ok = setStaticField(Build.VERSION::class.java, key, value)
            if (verboseLog) Log.d(TAG, "VERSION SPOOF: $key - $value (ok=$ok)")
            versionProps[key] = value.toString()
        }

        if (module != null) {
            try {
                hookSystemProperties(module, deviceEntries.props, verboseLog)
            } catch (t: Throwable) {
                Log.e(TAG, "SystemProperties hooks failed", t)
            }
        }

        val verifyProps = linkedMapOf<String, String>()
        verifyProps.putAll(deviceEntries.props)
        // VERSION_* keys share names with Build fields; verify against Build.VERSION class via key set.
        verifyProps.putAll(versionProps)
        val failed = verifySpoof(verifyProps, verboseLog)
        if (failed.isNotEmpty()) {
            Log.e(
                TAG,
                "Device spoof VERIFY failed for $deviceName: ${failed.joinToString()} " +
                    "(nativeReady=$nativeReady allowFailureUi=$allowFailureUi)",
            )
            if (allowFailureUi) {
                scheduleSpoofFailureUi(deviceName, failed)
            } else {
                Log.d(TAG, "Skipping VERIFY failure UI on early apply; will re-check on package ready")
            }
        } else {
            Log.d(TAG, "Device spoof VERIFY OK for $deviceName (nativeReady=$nativeReady)")
            cancelSpoofFailureUi()
        }
    }

    /** Back-compat for unit tests. */
    fun hook(prefs: SharedPreferences?) = hook(module = null, prefs = prefs, allowFailureUi = true)

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
            } catch (_: Throwable) {
                cls = cls.superclass as? Class<*> ?: break
            }
        }
        try {
            val f = Field::class.java.getDeclaredField("modifiers")
            f.isAccessible = true
            f
        } catch (_: Throwable) {
            null
        }
    }

    private fun setStaticField(clazz: Class<*>, fieldName: String, value: Any?): Boolean {
        Log.v(TAG, "setStaticField: ${clazz.name}.$fieldName = $value")
        return try {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            val coerced = coerceValue(field, value)

            clearFinalModifier(field)
            try {
                field.set(null, coerced)
                if (fieldValueMatches(field, coerced)) {
                    Log.v(TAG, "setStaticField $fieldName via Field.set")
                    return true
                }
                Log.w(
                    TAG,
                    "Field.set returned without matching readback for " +
                        "${clazz.simpleName}.$fieldName (actual='${field.get(null)}')",
                )
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "Field.set failed for ${clazz.simpleName}.$fieldName " +
                        "(${t.javaClass.simpleName}: ${t.message}); trying Unsafe",
                )
            }

            if (UnsafeStatic.put(field, coerced) && fieldValueMatches(field, coerced)) {
                Log.d(TAG, "setStaticField $fieldName via Unsafe")
                return true
            }
            if (!nativeReady) {
                Log.w(
                    TAG,
                    "Unsafe put failed or readback mismatch for ${clazz.simpleName}.$fieldName; " +
                        "JNI unavailable (nativeReady=false)",
                )
            } else {
                Log.w(
                    TAG,
                    "Unsafe put failed or readback mismatch for ${clazz.simpleName}.$fieldName; trying JNI",
                )
                if (
                    BuildFieldNative.setStatic(clazz, fieldName, coerced) &&
                    fieldValueMatches(field, coerced)
                ) {
                    Log.d(TAG, "setStaticField $fieldName via JNI")
                    return true
                }
            }

            Log.e(
                TAG,
                "Failed to set static field $fieldName on ${clazz.name} " +
                    "(actual='${runCatching { field.get(null) }.getOrNull()}')",
            )
            false
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to set static field $fieldName on ${clazz.name}", t)
            false
        }
    }

    private fun clearFinalModifier(field: Field) {
        try {
            // Only clear FINAL on the real access-flags int. Using Field.modifiers
            // (masked Java bits) would zero ART/hidden-API high bits and can make
            // subsequent reflection/JNI worse on Android 17+.
            val flagsField = accessFlagsField ?: return
            val current = flagsField.getInt(field)
            val cleared = current and Modifier.FINAL.inv()
            if (cleared != current) {
                flagsField.setInt(field, cleared)
            }
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
        } catch (_: Throwable) {
            false
        }
    }

    private fun exemptHiddenApis() {
        if (!hiddenApiExempted.compareAndSet(false, true)) return
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            getRuntime.isAccessible = true
            val runtime = getRuntime.invoke(null)
            val setExemptions = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java,
            )
            setExemptions.isAccessible = true
            setExemptions.invoke(runtime, arrayOf("L"))
            Log.d(TAG, "Hidden API exemptions applied for device spoof")
        } catch (t: Throwable) {
            Log.v(TAG, "Hidden API exemption unavailable: ${t.message}")
        }
    }

    private fun hookSystemProperties(
        module: XposedModule,
        props: Map<String, String>,
        verboseLog: Boolean,
    ) {
        val overrides = buildSystemPropertyOverrides(props)
        if (overrides.isEmpty()) return

        val clazz = try {
            Class.forName("android.os.SystemProperties")
        } catch (t: Throwable) {
            Log.w(TAG, "SystemProperties class not found", t)
            return
        }

        if (systemPropertiesHooked.get()) {
            Log.d(TAG, "SystemProperties hooks already registered")
            return
        }

        fun resolve(key: Any?): String? = if (key is String) overrides[key] else null

        var hooked = 0
        try {
            val m = clazz.getDeclaredMethod("get", String::class.java)
            module.hook(m).intercept { chain ->
                val spoofed = resolve(chain.getArg(0))
                if (spoofed != null) {
                    if (verboseLog) Log.v(TAG, "SystemProperties.get(${chain.getArg(0)}) -> $spoofed")
                    spoofed
                } else {
                    chain.proceed()
                }
            }
            hooked++
        } catch (t: Throwable) {
            Log.w(TAG, "Hook SystemProperties.get(String) failed: ${t.message}")
        }

        try {
            val m = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
            module.hook(m).intercept { chain ->
                val spoofed = resolve(chain.getArg(0))
                if (spoofed != null) {
                    if (verboseLog) {
                        Log.v(TAG, "SystemProperties.get(${chain.getArg(0)}, def) -> $spoofed")
                    }
                    spoofed
                } else {
                    chain.proceed()
                }
            }
            hooked++
        } catch (t: Throwable) {
            Log.w(TAG, "Hook SystemProperties.get(String,String) failed: ${t.message}")
        }

        if (hooked > 0) {
            systemPropertiesHooked.set(true)
            Log.d(TAG, "SystemProperties hooks registered ($hooked methods, ${overrides.size} keys)")
        } else {
            Log.w(TAG, "SystemProperties hooks failed for all overloads; will retry on re-apply")
        }
    }

    private fun buildSystemPropertyOverrides(props: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fun putKey(key: String, value: String?) {
            if (!value.isNullOrEmpty()) out[key] = value
        }
        putKey("ro.product.brand", props["BRAND"])
        putKey("ro.product.manufacturer", props["MANUFACTURER"])
        putKey("ro.product.device", props["DEVICE"])
        putKey("ro.product.name", props["PRODUCT"])
        putKey("ro.product.model", props["MODEL"])
        putKey("ro.product.model.marketname", props["MODEL"])
        putKey("ro.build.product", props["PRODUCT"])
        putKey("ro.build.fingerprint", props["FINGERPRINT"])
        putKey("ro.vendor.build.fingerprint", props["FINGERPRINT"])
        putKey("ro.system.build.fingerprint", props["FINGERPRINT"])
        putKey("ro.bootimage.build.fingerprint", props["FINGERPRINT"])
        putKey("ro.odm.build.fingerprint", props["FINGERPRINT"])
        putKey("ro.product.system.brand", props["BRAND"])
        putKey("ro.product.system.device", props["DEVICE"])
        putKey("ro.product.system.model", props["MODEL"])
        putKey("ro.product.system.name", props["PRODUCT"])
        putKey("ro.product.system.manufacturer", props["MANUFACTURER"])
        putKey("ro.product.vendor.brand", props["BRAND"])
        putKey("ro.product.vendor.device", props["DEVICE"])
        putKey("ro.product.vendor.model", props["MODEL"])
        putKey("ro.product.vendor.name", props["PRODUCT"])
        putKey("ro.product.vendor.manufacturer", props["MANUFACTURER"])
        putKey("ro.product.odm.brand", props["BRAND"])
        putKey("ro.product.odm.device", props["DEVICE"])
        putKey("ro.product.odm.model", props["MODEL"])
        putKey("ro.product.odm.name", props["PRODUCT"])
        putKey("ro.product.odm.manufacturer", props["MANUFACTURER"])
        putKey("ro.build.id", props["ID"])
        putKey("ro.build.version.incremental", props["INCREMENTAL"])
        putKey("ro.build.version.security_patch", props["SECURITY_PATCH"])
        return out
    }

    private fun scheduleSpoofFailureUi(deviceName: String, failed: List<String>) {
        val handler = mainHandlerOrNull()
        if (handler == null) {
            Log.w(TAG, "No main looper; cannot show spoof-failure UI")
            return
        }
        cancelPendingFailureUiRunnablesOnly()
        val show = Runnable { showSpoofFailureUi(deviceName, failed) }
        synchronized(pendingFailureUiLock) {
            pendingFailureUi += show
        }
        handler.post(show)
        handler.postDelayed(show, 2500L)
        handler.postDelayed(show, 6000L)
    }

    /** Called when a later re-apply VERIFY succeeds. */
    private fun cancelSpoofFailureUi() {
        cancelPendingFailureUiRunnablesOnly()
        failureUiShown.set(false)
        try {
            currentApplication()?.getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFY_ID)
        } catch (_: Throwable) {
        }
    }

    private fun cancelPendingFailureUiRunnablesOnly() {
        val handler = mainHandlerOrNull()
        synchronized(pendingFailureUiLock) {
            if (handler != null) {
                for (r in pendingFailureUi) {
                    handler.removeCallbacks(r)
                }
            }
            pendingFailureUi.clear()
        }
    }

    private fun showSpoofFailureUi(deviceName: String, failed: List<String>) {
        if (!failureUiShown.compareAndSet(false, true)) return
        val app = currentApplication()
        if (app == null) {
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
        } catch (_: Throwable) {
            null
        }
        return if (lang == "zh") {
            "裝置模擬失敗（$deviceName）。Build 欄位未變更：$detail$more。請查看 logcat 標籤 Pixelify。"
        } else {
            "Device spoof failed ($deviceName). Build fields unchanged: $detail$more. See logcat tag Pixelify."
        }
    }

    @SuppressLint("NotificationPermission")
    private fun postFailureNotification(context: Context, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled in host process; Toast-only spoof failure alert")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Device spoof diagnostics for Pixelify Infinity"
                },
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        nm.notify(
            NOTIFY_ID,
            builder
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .build(),
        )
        Log.d(TAG, "Posted spoof-failure notification")
    }

    private fun currentApplication(): Application? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            at.getMethod("currentApplication").invoke(null) as? Application
        } catch (_: Throwable) {
            null
        }
    }


    /**
     * Load [libpixelify_build] for JNI Build field writes under LSPosed.
     * Prefer module [ApplicationInfo.nativeLibraryDir], then extract into host codeCacheDir.
     */
    private fun ensureNativeLoaded(module: XposedModule?) {
        if (nativeReady) return
        synchronized(nativeLoadLock) {
            if (nativeReady) return
            loadNativeLibrary(module)
        }
    }

    private fun loadNativeLibrary(module: XposedModule?) {
        val appInfo = resolveModuleApplicationInfo(module)
        val hostApp = currentApplication()
        val candidates = mutableListOf<File>()

        fun addAbiCandidates(dir: File?) {
            if (dir == null || !dir.isDirectory) return
            // Prefer device ABI order.
            val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
            for (abi in abis) {
                candidates += File(dir, "libpixelify_build.so") // dir may already be abi-specific
                candidates += File(File(dir, abi), "libpixelify_build.so")
            }
            candidates += File(dir, "libpixelify_build.so")
            dir.listFiles()?.filter { it.isDirectory }?.forEach { child ->
                candidates += File(child, "libpixelify_build.so")
            }
        }

        if (appInfo != null) {
            appInfo.nativeLibraryDir?.takeIf { it.isNotBlank() }?.let { dir ->
                addAbiCandidates(File(dir))
                addAbiCandidates(File(dir).parentFile)
            }
            // Fallback: extract from module APK into writable dirs (host first, then module).
            appInfo.sourceDir?.let { src ->
                candidates += extractNativeFromApk(src, appInfo, hostApp)
            }
            appInfo.splitSourceDirs?.forEach { split ->
                candidates += extractNativeFromApk(split, appInfo, hostApp)
            }
        }

        // Last resort: System.loadLibrary (works only if the host classloader path includes module libs).
        var loaded = false
        val tried = linkedSetOf<String>()
        for (so in candidates) {
            val path = so.absolutePath
            if (!tried.add(path)) continue
            if (!so.isFile) continue
            try {
                System.load(path)
                nativeReady = true
                loaded = true
                Log.d(TAG, "Loaded libpixelify_build from $path")
                break
            } catch (t: Throwable) {
                Log.w(TAG, "System.load($path) failed: ${t.message}")
            }
        }
        if (!loaded) {
            try {
                System.loadLibrary("pixelify_build")
                nativeReady = true
                loaded = true
                Log.d(TAG, "Loaded libpixelify_build via System.loadLibrary")
            } catch (t: Throwable) {
                Log.w(TAG, "System.loadLibrary(pixelify_build) failed: ${t.message}")
            }
        }
        if (!loaded) {
            Log.e(
                TAG,
                "JNI Build field writer unavailable (libpixelify_build not loaded); " +
                    "nativeReady=false reason=load_failed candidates=${tried.size}",
            )
        }
    }

    private fun resolveModuleApplicationInfo(module: XposedModule?): ApplicationInfo? {
        if (module == null) return null
        return try {
            module.moduleApplicationInfo
        } catch (t: Throwable) {
            Log.w(TAG, "getModuleApplicationInfo failed: ${t.message}")
            null
        }
    }

    /**
     * Extract libpixelify_build.so for the preferred ABI into a writable cache dir.
     * Prefer host process dirs (Photos UID under LSPosed); module data dirs are fallback only.
     */
    private fun extractNativeFromApk(
        apkPath: String,
        appInfo: ApplicationInfo,
        hostApp: Application?,
    ): List<File> {
        val apk = File(apkPath)
        if (!apk.isFile) return emptyList()
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty().ifEmpty {
            listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        val outDir = resolveExtractDir(appInfo, hostApp) ?: return emptyList()
        val results = mutableListOf<File>()
        try {
            ZipFile(apk).use { zip ->
                for (abi in abis) {
                    val entryName = "lib/$abi/libpixelify_build.so"
                    val entry = zip.getEntry(entryName) ?: continue
                    val out = File(outDir, "libpixelify_build-$abi.so")
                    if (!out.isFile || out.length() != entry.size) {
                        zip.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        // Host process must be able to map/exec the library.
                        out.setReadable(true, false)
                        out.setExecutable(true, false)
                    }
                    results += out
                    Log.d(TAG, "Extracted $entryName -> ${out.absolutePath}")
                    // Prefer first matching supported ABI.
                    break
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "APK native extract failed for $apkPath: ${t.message}")
        }
        return results
    }

    private fun resolveExtractDir(appInfo: ApplicationInfo, hostApp: Application?): File? {
        // Under LSPosed, code runs as Google Photos UID. Prefer host-writable dirs.
        val candidates = mutableListOf<File>()
        try {
            hostApp?.codeCacheDir?.let { candidates += File(it, "pixelify_native") }
        } catch (_: Throwable) {
        }
        try {
            hostApp?.cacheDir?.let { candidates += File(it, "pixelify_native") }
        } catch (_: Throwable) {
        }
        try {
            hostApp?.filesDir?.let { candidates += File(it, "pixelify_native") }
        } catch (_: Throwable) {
        }
        // Module private dirs rarely writable from the host process; still try.
        appInfo.dataDir?.let { candidates += File(it, "code_cache/pixelify_native") }
        appInfo.deviceProtectedDataDir?.let {
            candidates += File(it, "code_cache/pixelify_native")
        }
        appInfo.nativeLibraryDir?.let { nld ->
            File(nld).parentFile?.let { candidates += File(it, "pixelify_native") }
        }
        for (dir in candidates) {
            try {
                if (dir.exists() || dir.mkdirs()) {
                    if (dir.canWrite()) {
                        Log.d(TAG, "Native extract dir: ${dir.absolutePath}")
                        return dir
                    }
                }
            } catch (_: Throwable) {
            }
        }
        Log.w(TAG, "No writable native extract dir (host+module candidates exhausted)")
        return null
    }

    /**
     * JNI bridge for [libpixelify_build]. Methods kept for ProGuard/R8.
     */
    private object BuildFieldNative {
        @JvmStatic
        external fun nativeSetStatic(clazz: Class<*>, fieldName: String, value: Any?): Boolean

        fun setStatic(clazz: Class<*>, fieldName: String, value: Any?): Boolean {
            return try {
                nativeSetStatic(clazz, fieldName, value)
            } catch (t: Throwable) {
                Log.w(TAG, "JNI nativeSetStatic failed for $fieldName: ${t.message}")
                false
            }
        }
    }

    private object UnsafeStatic {
        private data class Handle(
            val unsafe: Any,
            val staticFieldBase: Method,
            val staticFieldOffset: Method,
            val putObject: Method?,
            val putObjectVolatile: Method?,
            val putReference: Method?,
            val putReferenceVolatile: Method?,
            val putInt: Method?,
            val putIntVolatile: Method?,
            val putLong: Method?,
            val putLongVolatile: Method?,
            val putBoolean: Method?,
            val putFloat: Method?,
            val putDouble: Method?,
            val putShort: Method?,
            val putByte: Method?,
            val putChar: Method?,
            val source: String,
        )

        private val handle: Handle? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            openUnsafe("sun.misc.Unsafe") ?: openUnsafe("jdk.internal.misc.Unsafe")
        }

        private fun openUnsafe(className: String): Handle? {
            return try {
                val cls = Class.forName(className)
                val instance = resolveUnsafeInstance(cls) ?: return null
                fun methodOrNull(name: String, vararg params: Class<*>): Method? =
                    try {
                        cls.getMethod(name, *params).also { it.isAccessible = true }
                    } catch (_: Throwable) {
                        null
                    }

                val obj = Object::class.java
                val l = Long::class.javaPrimitiveType!!
                val i = Int::class.javaPrimitiveType!!
                val z = Boolean::class.javaPrimitiveType!!
                val f = Float::class.javaPrimitiveType!!
                val d = Double::class.javaPrimitiveType!!
                val s = Short::class.javaPrimitiveType!!
                val b = Byte::class.javaPrimitiveType!!
                val c = Char::class.javaPrimitiveType!!

                val staticFieldBase = cls.getMethod("staticFieldBase", Field::class.java)
                val staticFieldOffset = cls.getMethod("staticFieldOffset", Field::class.java)
                staticFieldBase.isAccessible = true
                staticFieldOffset.isAccessible = true

                Handle(
                    unsafe = instance,
                    staticFieldBase = staticFieldBase,
                    staticFieldOffset = staticFieldOffset,
                    putObject = methodOrNull("putObject", obj, l, obj),
                    putObjectVolatile = methodOrNull("putObjectVolatile", obj, l, obj),
                    putReference = methodOrNull("putReference", obj, l, obj),
                    putReferenceVolatile = methodOrNull("putReferenceVolatile", obj, l, obj),
                    putInt = methodOrNull("putInt", obj, l, i),
                    putIntVolatile = methodOrNull("putIntVolatile", obj, l, i),
                    putLong = methodOrNull("putLong", obj, l, l),
                    putLongVolatile = methodOrNull("putLongVolatile", obj, l, l),
                    putBoolean = methodOrNull("putBoolean", obj, l, z),
                    putFloat = methodOrNull("putFloat", obj, l, f),
                    putDouble = methodOrNull("putDouble", obj, l, d),
                    putShort = methodOrNull("putShort", obj, l, s),
                    putByte = methodOrNull("putByte", obj, l, b),
                    putChar = methodOrNull("putChar", obj, l, c),
                    source = className,
                ).also {
                    Log.d(TAG, "Unsafe static writer ready via $className")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Unsafe unavailable for $className: ${t.message}")
                null
            }
        }

        private fun resolveUnsafeInstance(cls: Class<*>): Any? {
            for (name in listOf("theUnsafe", "THE_ONE", "unsafe")) {
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

        private fun toOffset(raw: Any?): Long = when (raw) {
            is Long -> raw
            is Int -> raw.toLong()
            is Number -> raw.toLong()
            else -> error("Unexpected staticFieldOffset type: ${raw?.javaClass?.name}")
        }

        fun put(field: Field, value: Any?): Boolean {
            val h = handle
            if (h == null) {
                Log.w(TAG, "Unsafe handle not available; cannot put ${field.name}")
                return false
            }

            val bases = linkedSetOf<Any?>()
            try {
                bases += h.staticFieldBase.invoke(h.unsafe, field)
            } catch (t: Throwable) {
                Log.w(TAG, "staticFieldBase failed for ${field.name}: ${t.message}")
            }
            bases += field.declaringClass

            val offset = try {
                toOffset(h.staticFieldOffset.invoke(h.unsafe, field))
            } catch (t: Throwable) {
                Log.e(TAG, "staticFieldOffset failed for ${field.name}", t)
                return false
            }

            val attempts = mutableListOf<Pair<String, Method?>>()
            when (field.type) {
                Integer.TYPE -> {
                    attempts += "putIntVolatile" to h.putIntVolatile
                    attempts += "putInt" to h.putInt
                }
                java.lang.Long.TYPE -> {
                    attempts += "putLongVolatile" to h.putLongVolatile
                    attempts += "putLong" to h.putLong
                }
                java.lang.Boolean.TYPE -> attempts += "putBoolean" to h.putBoolean
                java.lang.Float.TYPE -> attempts += "putFloat" to h.putFloat
                java.lang.Double.TYPE -> attempts += "putDouble" to h.putDouble
                java.lang.Short.TYPE -> attempts += "putShort" to h.putShort
                java.lang.Byte.TYPE -> attempts += "putByte" to h.putByte
                Character.TYPE -> attempts += "putChar" to h.putChar
                else -> {
                    attempts += "putReferenceVolatile" to h.putReferenceVolatile
                    attempts += "putObjectVolatile" to h.putObjectVolatile
                    attempts += "putReference" to h.putReference
                    attempts += "putObject" to h.putObject
                }
            }

            for (base in bases) {
                for ((name, method) in attempts) {
                    if (method == null) continue
                    try {
                        when (field.type) {
                            Integer.TYPE -> method.invoke(h.unsafe, base, offset, value as Int)
                            java.lang.Long.TYPE -> method.invoke(h.unsafe, base, offset, value as Long)
                            java.lang.Boolean.TYPE ->
                                method.invoke(h.unsafe, base, offset, value as Boolean)
                            java.lang.Float.TYPE ->
                                method.invoke(h.unsafe, base, offset, value as Float)
                            java.lang.Double.TYPE ->
                                method.invoke(h.unsafe, base, offset, value as Double)
                            java.lang.Short.TYPE ->
                                method.invoke(h.unsafe, base, offset, value as Short)
                            java.lang.Byte.TYPE ->
                                method.invoke(h.unsafe, base, offset, value as Byte)
                            Character.TYPE -> method.invoke(h.unsafe, base, offset, value as Char)
                            else -> method.invoke(h.unsafe, base, offset, value)
                        }
                        Log.v(
                            TAG,
                            "Unsafe.$name via ${h.source} base=${base?.javaClass?.simpleName} " +
                                "offset=$offset field=${field.declaringClass.simpleName}.${field.name}",
                        )
                        // Caller verifies with readback.
                        if (fieldValueMatches(field, value)) return true
                    } catch (t: Throwable) {
                        Log.v(
                            TAG,
                            "Unsafe.$name failed for ${field.name} " +
                                "base=${base?.javaClass?.simpleName}: ${t.message}",
                        )
                    }
                }
            }
            Log.e(
                TAG,
                "Unsafe.put exhausted strategies for ${field.declaringClass.name}.${field.name}",
            )
            return false
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
            } catch (_: Throwable) {
                false
            }
        }
    }
}
