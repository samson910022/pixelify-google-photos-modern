package io.github.samson910022.pixelifyphotos

import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class PixelifyModule : XposedModule() {

    @Volatile
    private var moduleLoadedAt: Long? = null

    companion object {
        const val TAG = "Pixelify"

        /**
         * Process-wide module reference. Assumes exactly one [PixelifyModule] instance
         * per process (the libxposed framework instantiates one module instance per
         * module per process); last-writer-wins assignment is safe under that contract
         * and the reference lives for the process lifetime once assigned in init.
         */
        @Volatile
        var instance: PixelifyModule? = null
            private set
    }

    init {
        instance = this
    }

    override fun onModuleLoaded(params: XposedModuleInterface.ModuleLoadedParam) {
        Log.d(TAG, "Pixelify Infinity module loaded (libxposed Modern API)")
        moduleLoadedAt = System.currentTimeMillis()
    }

    /**
     * Early entry (closest to legacy handleLoadPackage). Apply Build spoof +
     * SystemProperties hooks as soon as the package class loader exists.
     *
     * Multi-app Option B: trust LSPosed scope for any first package, then apply
     * soft [ScopePolicy] denylist (skip spoof, still allow module load).
     *
     * Owner decision (locked): the module is intentionally NOT restricted to
     * Google Photos or to the developer. Scoping extra apps is documented as
     * advanced/unsupported; non-Photos spoof is therefore logged explicitly so
     * the behavior stays auditable.
     */
    override fun onPackageLoaded(params: XposedModuleInterface.PackageLoadedParam) {
        // PackageLoadedParam.isFirstPackage() is part of libxposed API 101.
        if (!ModuleLifecyclePolicy.shouldHandlePackageLoaded(params.isFirstPackage)) {
            Log.v(TAG, "Skipping non-first package load for ${params.packageName}")
            return
        }

        if (!ScopePolicy.shouldSpoof(params.packageName)) {
            Log.w(
                TAG,
                "Skipping device spoof for denylisted/invalid package: ${params.packageName}"
            )
            return
        }

        // Establish the package/process run before queuing any telemetry so every
        // subsequent result carries this run identity and cannot overwrite a newer run.
        val runHandle = DiagnosticsReporter.startTargetRun(params.packageName, moduleLoadedAt) ?: return

        logIfNonPhotosPackage(params.packageName, "early device spoof")
        Log.d(TAG, "Package loaded (${params.packageName}). Early device spoof...")
        try {
            val prefs = getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
            // Pass module so DeviceSpoofer can resolve module nativeLibraryDir for JNI.
            // No failure UI yet — Application/host extract may only work on package ready.
            DeviceSpoofer.hook(
                this,
                prefs,
                allowFailureUi = false,
                packageName = params.packageName,
                runHandle = runHandle,
            )
            Log.d(TAG, "DeviceSpoofer early apply attempt completed for ${params.packageName}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed early DeviceSpoofer apply for ${params.packageName}", t)
        } finally {
            recordSystemPropertiesRegistration(runHandle)
        }

        // On Android 10+ (API 29, Q), Google Photos and system libraries query PackageManager
        // features during early static initializers and ContentProvider startups (prior to
        // Application.onCreate). Hooking early via params.defaultClassLoader ensures feature
        // capabilities like Unlimited Backup are spoofed before early caching takes place.
        // On Android 9 and older, class resolution is deferred to onPackageReady.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ModuleLifecyclePolicy.shouldHookFeaturesEarly(Build.VERSION.SDK_INT)
        ) {
            try {
                FeatureSpoofer.hook(this, params.defaultClassLoader, runHandle)
                Log.d(TAG, "FeatureSpoofer early registration attempt completed for ${params.packageName}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed early FeatureSpoofer apply for ${params.packageName}", t)
            } finally {
                recordFeatureRegistration(runHandle)
            }
        }
        DiagnosticsReporter.recordMilestone(runHandle = runHandle) { bundle ->
            bundle.putString(Constants.PREF_DIAG_LAST_PACKAGE_LOADED, params.packageName)
        }
    }

    /**
     * Package-ready lifecycle callback: attempt FeatureSpoofer registration and
     * re-apply DeviceSpoofer for any non-denylisted scoped package (same global
     * prefs as Photos). Registration outcomes are reported separately.
     */
    override fun onPackageReady(params: XposedModuleInterface.PackageReadyParam) {
        if (!ScopePolicy.shouldSpoof(params.packageName)) {
            Log.w(
                TAG,
                "Skipping feature/device spoof on ready for denylisted/invalid package: ${params.packageName}"
            )
            return
        }

        // Some framework lifecycle variants can reach package-ready without a usable
        // first-package callback. The create-or-return operation is atomic so racing
        // package callbacks cannot each publish a different run identity.
        val runHandle = DiagnosticsReporter.startTargetRunIfAbsent(
            packageName = params.packageName,
            moduleLoadedAt = moduleLoadedAt,
            packageLoadCallbackObserved = false,
        ) ?: return
        val telemetrySupportedForPackage =
            DiagnosticsReporter.supportsCallbackPackage(params.packageName, runHandle)
        if (!telemetrySupportedForPackage) {
            Log.w(
                TAG,
                "Same-process secondary package telemetry is unsupported: run origin differs from " +
                    "${params.packageName}; hook registration still proceeds, but its diagnostics remain unknown",
            )
        }

        Log.d(TAG, "Package-ready callback observed (${params.packageName}). Attempting hooks...")

        // Each hook is individually guarded so one failure doesn't block the other.
        logIfNonPhotosPackage(params.packageName, "feature/device spoof on ready")
        try {
            try {
                FeatureSpoofer.hook(this, params.classLoader, runHandle)
                Log.d(TAG, "FeatureSpoofer registration attempt completed for ${params.packageName}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to register FeatureSpoofer hooks for ${params.packageName}", t)
            } finally {
                if (telemetrySupportedForPackage) recordFeatureRegistration(runHandle)
            }

            try {
                val prefs = getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
                // Re-apply Build writes + ensure SystemProperties hooks exist.
                DeviceSpoofer.hook(
                    this,
                    prefs,
                    packageName = params.packageName,
                    runHandle = runHandle,
                )
                Log.d(TAG, "DeviceSpoofer apply attempt completed for ${params.packageName}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to register DeviceSpoofer hooks for ${params.packageName}", t)
            } finally {
                if (telemetrySupportedForPackage) recordSystemPropertiesRegistration(runHandle)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register hooks for ${params.packageName}", t)
        }
        if (telemetrySupportedForPackage) {
            DiagnosticsReporter.recordMilestone(runHandle = runHandle) { bundle ->
                bundle.putString(Constants.PREF_DIAG_LAST_PACKAGE_READY, params.packageName)
                bundle.putLong(Constants.PREF_DIAG_LAST_PACKAGE_READY_AT, System.currentTimeMillis())
            }
        }
    }

    private fun recordFeatureRegistration(runHandle: DiagnosticsReporter.TargetRunHandle) {
        val result = FeatureSpoofer.registrationSnapshot()
        DiagnosticsReporter.recordFeatureHookRegistration(
            hasSystemFeature = result.hasSystemFeature,
            hasSystemFeatureWithFlags = result.hasSystemFeatureWithFlags,
            getSystemAvailableFeatures = result.getSystemAvailableFeatures,
            runHandle = runHandle,
        )
    }

    private fun recordSystemPropertiesRegistration(runHandle: DiagnosticsReporter.TargetRunHandle) {
        val result = DeviceSpoofer.systemPropertiesRegistrationSnapshot()
        if (result.attempted) {
            DiagnosticsReporter.recordSystemPropertiesHookRegistration(
                get = result.get,
                getWithDefault = result.getWithDefault,
                runHandle = runHandle,
            )
        }
    }

    /**
     * Audit log for the deliberate multi-app behavior: Photos is the recommended
     * scope, but scoping extra apps is allowed (advanced/unsupported). Spoofing
     * any non-Photos package is intentional and must stay visible in logs.
     */
    private fun logIfNonPhotosPackage(packageName: String, phase: String) {
        if (packageName != Constants.PACKAGE_NAME_GOOGLE_PHOTOS) {
            Log.w(
                TAG,
                "Applying $phase to non-Photos package $packageName " +
                    "(advanced multi-app scope; not restricted to developer/Photos by design)"
            )
        }
    }
}
