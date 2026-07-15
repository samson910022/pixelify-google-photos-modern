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
 * 1. If the queried feature name is in [finalFeaturesToSpoof] → return `true`
 * 2. If [overrideCustomROMLevels] is true and the feature is in [featuresNotToSpoof] → return `false`
 * 3. Otherwise → `chain.proceed()` (let the original implementation decide)
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
     * Feature flags that should be spoofed as **present** (return `true`).
     * Built from the user's selected feature levels in preferences.
     */
    private var finalFeaturesToSpoof: Set<String> = emptySet()

    /**
     * Feature flags that should be spoofed as **absent** (return `false`).
     * Built from all known flags minus [finalFeaturesToSpoof].
     * Only active when [overrideCustomROMLevels] is `true`.
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

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Called from [PixelifyModule.onPackageReady].
     *
     * Reads user preferences via [XposedModule.getRemotePreferences],
     * resolves the feature flag lists, and registers interceptors on both
     * [hasSystemFeature] overloads.
     *
     * @param module     The module instance used to register hooks and read prefs.
     * @param classLoader The class loader of the target package (Google Photos).
     */
    fun hook(module: XposedModule, classLoader: ClassLoader) {
        try {
            val prefs = module.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
            initFromPrefs(prefs)

            val clazz = classLoader.loadClass(CLASS_APPLICATION_MANAGER)

            // hasSystemFeature(String) – the most commonly called signature
            val methodString = clazz.getDeclaredMethod(
                "hasSystemFeature", String::class.java
            )
            module.hook(methodString).intercept { chain -> decideSpoof(chain) }

            // hasSystemFeature(String, int) – less common but also present in
            // the Android API; hook it for completeness
            val methodStringInt = clazz.getDeclaredMethod(
                "hasSystemFeature", String::class.java, Int::class.javaPrimitiveType
            )
            module.hook(methodStringInt).intercept { chain -> decideSpoof(chain) }

            Log.d(TAG, "FeatureSpoofer hooks registered successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register FeatureSpoofer hooks", t)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Reads all relevant preferences and builds the [finalFeaturesToSpoof] and
     * [featuresNotToSpoof] lists.
     *
     * The preference [Constants.PREF_SPOOF_FEATURES_LIST] stores a
     * [Set] of display names (e.g. `"Pixel 2020"`, `"Pixel 2019"`).
     * These are resolved against [DeviceProps.allFeatures] to obtain the
     * actual feature-flag strings that are fed to the interceptor.
     */
    private fun initFromPrefs(prefs: SharedPreferences) {
        verboseLog = prefs.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false)

        overrideCustomROMLevels = prefs.getBoolean(
            Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS, true
        )

        // --- Resolve the feature flag list from user selection ---

        val defaultNames = DeviceProps.defaultFeatures
            .map { it.displayName }
            .toSet()

        val selectedNames = prefs.getStringSet(
            Constants.PREF_SPOOF_FEATURES_LIST, defaultNames
        ) ?: defaultNames

        val eligibleFeatures = when {
            // Empty selection → spoof nothing
            selectedNames.isEmpty() -> emptyList()

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
    // Interceptor
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Core decision logic shared by both [hasSystemFeature] overloads.
     *
     * Because [XposedInterface.Hooker] is a SAM interface, we can pass a
     * lambda directly to [XposedInterface.HookBuilder.intercept]. This
     * function is called from that lambda.
     *
     * @return `true` / `false` to short-circuit, or [XposedInterface.Chain.proceed]
     *         to fall through to the original implementation.
     */
    private fun decideSpoof(chain: XposedInterface.Chain): Any? {
        if (!initialized) return chain.proceed()

        val feature = chain.getArg(0) as? String ?: return chain.proceed()

        return when {
            // 1. Feature should be reported as present
            feature in finalFeaturesToSpoof -> {
                if (verboseLog) Log.d(TAG, "TRUE - $feature")
                true
            }

            // 2. Feature should be hidden (override ROM feature levels)
            overrideCustomROMLevels && feature in featuresNotToSpoof -> {
                if (verboseLog) Log.d(TAG, "FALSE  - $feature")
                false
            }

            // 3. Pass through unchanged
            else -> {
                if (verboseLog) Log.d(TAG, "NO_CHANGE - $feature")
                chain.proceed()
            }
        }
    }
}
