package io.github.samson910022.pixelifyphotos

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Hooks [android.app.ApplicationPackageManager.hasSystemFeature] to spoof
 * Google Pixel feature flags, making Google Photos believe the device is a
 * supported Pixel device.
 *
 * Uses libxposed Modern API via [XposedModule.hook] + intercept lambdas.
 *
 * ### Hook targets
 * - `android.app.ApplicationPackageManager.hasSystemFeature(String)`
 * - `android.app.ApplicationPackageManager.hasSystemFeature(String, int)`
 *
 * ### Spoofing logic
 * 1. If pass-through mode (device "None" or empty feature selection) → always
 *    [XposedInterface.Chain.proceed] (same as module disabled for features)
 * 2. If the queried feature name is in [finalFeaturesToSpoof] → return `true`
 * 3. If [overrideCustomROMLevels] is true and the feature is in [featuresNotToSpoof]
 *    → return `false`
 * 4. Otherwise → `chain.proceed()`
 *
 * @see DeviceProps for the list of all known Pixel feature flags
 * @see Constants for preference keys
 */
object FeatureSpoofer {

    private const val TAG = "Pixelify"
    private const val CLASS_APPLICATION_MANAGER = "android.app.ApplicationPackageManager"

    /**
     * Guard flag that is set to `true` once [initFromPrefs] completes.
     * If initialization fails (e.g. service unavailable), all hooks
     * transparently pass through with [XposedInterface.Chain.proceed].
     */
    @Volatile
    private var initialized = false

    /**
     * When true, do not force TRUE or FALSE for any feature flag — behave like
     * the module is not intercepting features. Used for device "None" and empty
     * feature selection.
     */
    private var passThroughAll = false

    /**
     * Feature flags that should be spoofed as **present** (return `true`).
     * Built from the user's selected feature levels in preferences.
     */
    private var finalFeaturesToSpoof: Set<String> = emptySet()

    /**
     * Feature flags that should be spoofed as **absent** (return `false`).
     * Built from all known flags minus [finalFeaturesToSpoof].
     * Only active when [overrideCustomROMLevels] is `true` and not [passThroughAll].
     */
    private var featuresNotToSpoof: Set<String> = emptySet()

    /**
     * Whether to override upper feature levels advertised by custom ROMs.
     * When `true`, features from [featuresNotToSpoof] are reported as absent.
     * Default: `true` (matching the original module behaviour).
     */
    private var overrideCustomROMLevels = false

    /**
     * Whether verbose logging is enabled.
     */
    private var verboseLog = false

    private val hookHasSystemFeatureString = java.util.concurrent.atomic.AtomicBoolean(false)
    private val hookHasSystemFeatureStringInt = java.util.concurrent.atomic.AtomicBoolean(false)
    private val hookGetSystemAvailableFeatures = java.util.concurrent.atomic.AtomicBoolean(false)

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Called from [PixelifyModule.onPackageLoaded] and [PixelifyModule.onPackageReady].
     *
     * Reads user preferences via [XposedModule.getRemotePreferences],
     * resolves the feature flag lists, and registers interceptors on
     * [hasSystemFeature] overloads and [getSystemAvailableFeatures].
     *
     * Idempotent: each method hook is tracked independently with an [AtomicBoolean]
     * so partial failures during one stage do not cause double-hooking on retries.
     *
     * @param module      The module instance used to register hooks and read prefs.
     * @param classLoader The class loader of the target package (Google Photos).
     */
    fun hook(module: XposedModule, classLoader: ClassLoader) {
        try {
            val prefs = module.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
            initFromPrefs(prefs)

            val clazz = classLoader.loadClass(CLASS_APPLICATION_MANAGER)

            // hasSystemFeature(String) – the most commonly called signature
            if (!hookHasSystemFeatureString.get()) {
                try {
                    val methodString = clazz.getDeclaredMethod("hasSystemFeature", String::class.java)
                    module.hook(methodString).intercept { chain -> decideSpoof(chain) }
                    hookHasSystemFeatureString.set(true)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to hook hasSystemFeature(String)", t)
                }
            }

            // hasSystemFeature(String, int) – less common but also present in
            // the Android API; hook it for completeness
            if (!hookHasSystemFeatureStringInt.get()) {
                try {
                    val methodStringInt = clazz.getDeclaredMethod(
                        "hasSystemFeature", String::class.java, Int::class.javaPrimitiveType
                    )
                    module.hook(methodStringInt).intercept { chain -> decideSpoof(chain) }
                    hookHasSystemFeatureStringInt.set(true)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to hook hasSystemFeature(String, int)", t)
                }
            }

            // getSystemAvailableFeatures() – queries the full list of features
            if (!hookGetSystemAvailableFeatures.get()) {
                try {
                    val methodFeatures = clazz.getDeclaredMethod("getSystemAvailableFeatures")
                    module.hook(methodFeatures).intercept { chain -> decideFeatures(chain) }
                    hookGetSystemAvailableFeatures.set(true)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to hook getSystemAvailableFeatures", t)
                }
            }

            Log.d(TAG, "FeatureSpoofer hooks evaluation complete")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize or resolve classes for FeatureSpoofer", t)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Reads all relevant preferences and delegates to [initFromValues].
     */
    private fun initFromPrefs(prefs: SharedPreferences) {
        val verbose = prefs.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false)
        val overrideROM = prefs.getBoolean(Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS, true)
        val deviceName = prefs.getString(
            Constants.PREF_DEVICE_TO_SPOOF,
            DeviceProps.defaultDeviceName,
        ) ?: DeviceProps.defaultDeviceName

        val defaultNames = DeviceProps.defaultFeatures
            .map { it.displayName }
            .toSet()

        val selectedNames = prefs.getStringSet(
            Constants.PREF_SPOOF_FEATURES_LIST, defaultNames
        ) ?: defaultNames

        initFromValues(deviceName, selectedNames, overrideROM, verbose)
    }

    /**
     * Testable initialization function accepting resolved values.
     */
    internal fun initFromValues(
        deviceName: String,
        selectedNames: Set<String>,
        overrideROM: Boolean,
        verbose: Boolean = false,
    ) {
        verboseLog = verbose
        overrideCustomROMLevels = overrideROM

        val defaultNames = DeviceProps.defaultFeatures
            .map { it.displayName }
            .toSet()

        // Device "None" or empty feature list: behave like module-off for features
        // (no force TRUE, no force FALSE). Do not treat empty as "hide all Pixel flags",
        // which differs from module-off and surprises users on real Pixel hardware.
        if (deviceName == "None" || selectedNames.isEmpty()) {
            passThroughAll = true
            finalFeaturesToSpoof = emptySet()
            featuresNotToSpoof = emptySet()
            initialized = true
            Log.d(TAG, "Feature spoof pass-through (device=$deviceName, emptyFeatures=${selectedNames.isEmpty()})")
            return
        }

        passThroughAll = false

        val eligibleFeatures = when {
            // Exactly the default set → use the pre-computed default features list
            selectedNames == defaultNames -> DeviceProps.defaultFeatures

            // Custom selection → filter allFeatures by the chosen display names
            else -> DeviceProps.allFeatures.filter { it.displayName in selectedNames }
        }

        finalFeaturesToSpoof = eligibleFeatures.flatMap { it.featureFlags }.toSet()

        // --- Build the "not-to-spoof" list ---
        val allFlags = DeviceProps.allFeatures.flatMap { it.featureFlags }
        featuresNotToSpoof = allFlags.filter { it !in finalFeaturesToSpoof }.toSet()

        initialized = true

        if (verboseLog) {
            Log.d(TAG, "Spoof TRUE for: $finalFeaturesToSpoof")
            if (overrideCustomROMLevels) {
                Log.d(TAG, "Spoof FALSE for: $featuresNotToSpoof")
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Interceptor & Decision Logic
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Decides whether a given feature should be spoofed to true, false, or passed through.
     * Pure logic method for direct unit test execution.
     *
     * @return `true` if spoofed as present, `false` if hidden, or `null` for pass-through.
     */
    internal fun decideSpoofForFeature(feature: String): Boolean? {
        if (!initialized || passThroughAll) return null
        return when {
            feature in finalFeaturesToSpoof -> true
            overrideCustomROMLevels && feature in featuresNotToSpoof -> false
            else -> null
        }
    }

    /**
     * Core decision logic shared by both [hasSystemFeature] overloads.
     */
    private fun decideSpoof(chain: XposedInterface.Chain): Any? {
        if (!initialized || passThroughAll) {
            return chain.proceed()
        }

        val feature = chain.getArg(0) as? String ?: return chain.proceed()

        return when (val decision = decideSpoofForFeature(feature)) {
            true -> {
                if (verboseLog) Log.d(TAG, "TRUE - $feature")
                true
            }
            false -> {
                if (verboseLog) Log.d(TAG, "FALSE  - $feature")
                false
            }
            null -> {
                if (verboseLog) Log.d(TAG, "NO_CHANGE - $feature")
                chain.proceed()
            }
        }
    }

    /**
     * Interceptor logic for [android.content.pm.PackageManager.getSystemAvailableFeatures].
     *
     * Filters out hidden features and appends spoofed Pixel features to the returned array.
     */
    internal fun filterAndAugmentFeatures(originalRaw: Any?): Array<android.content.pm.FeatureInfo>? {
        if (!initialized || passThroughAll) return null
        val original = (originalRaw as? Array<*>)?.filterIsInstance<android.content.pm.FeatureInfo>()
            ?: return null

        val resultList = mutableListOf<android.content.pm.FeatureInfo>()
        val seenNames = mutableSetOf<String>()

        for (info in original) {
            val name = info.name
            // FeatureInfo with null name represents OpenGL ES version metadata (reqGlEsVersion)
            // and must always be preserved.
            if (name == null) {
                resultList.add(info)
                continue
            }
            if (overrideCustomROMLevels && name in featuresNotToSpoof) {
                if (verboseLog) Log.d(TAG, "getSystemAvailableFeatures: HIDE - $name")
                continue
            }
            resultList.add(info)
            seenNames.add(name)
        }

        for (feature in finalFeaturesToSpoof) {
            if (feature !in seenNames) {
                if (verboseLog) Log.d(TAG, "getSystemAvailableFeatures: ADD - $feature")
                val newInfo = android.content.pm.FeatureInfo().apply {
                    this.name = feature
                }
                resultList.add(newInfo)
                seenNames.add(feature)
            }
        }

        return resultList.toTypedArray()
    }

    /**
     * Interceptor for [android.content.pm.PackageManager.getSystemAvailableFeatures].
     */
    private fun decideFeatures(chain: XposedInterface.Chain): Any? {
        if (!initialized || passThroughAll) {
            return chain.proceed()
        }

        val originalRaw = chain.proceed()
        return filterAndAugmentFeatures(originalRaw) ?: originalRaw
    }

    /**
     * Resets internal state and atomic hook registration flags for testing.
     */
    internal fun resetForTesting() {
        initialized = false
        passThroughAll = false
        finalFeaturesToSpoof = emptySet()
        featuresNotToSpoof = emptySet()
        overrideCustomROMLevels = false
        verboseLog = false
        hookHasSystemFeatureString.set(false)
        hookHasSystemFeatureStringInt.set(false)
        hookGetSystemAvailableFeatures.set(false)
    }
}
