package io.github.samson910022.pixelifyphotos

import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class PixelifyModule : XposedModule() {

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
        // Provision broadcast token early so hooked processes can authenticate fallback broadcasts.
        // Use commit() and single-owner pattern to avoid cross-process UUID races (App.onCreate also provisions).
        try {
            val prefs = getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
            if (prefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null).isNullOrEmpty()) {
                prefs.edit().putString(Constants.PREF_DIAG_BROADCAST_TOKEN, java.util.UUID.randomUUID().toString()).commit()
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Broadcast token provisioning skipped: ${t.message}")
        }
        DiagnosticsReporter.recordMilestone { bundle ->
            bundle.putLong(Constants.PREF_DIAG_MODULE_LOADED_AT, System.currentTimeMillis())
        }
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
        if (!params.isFirstPackage) {
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

        logIfNonPhotosPackage(params.packageName, "early device spoof")
        Log.d(TAG, "Package loaded (${params.packageName}). Early device spoof...")
        try {
            val prefs = getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
            // Pass module so DeviceSpoofer can resolve module nativeLibraryDir for JNI.
            // No failure UI yet — Application/host extract may only work on package ready.
            DeviceSpoofer.hook(this, prefs, allowFailureUi = false, packageName = params.packageName)
            Log.d(TAG, "DeviceSpoofer early apply done for ${params.packageName}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed early DeviceSpoofer apply for ${params.packageName}", t)
        }

        // On Android 10+ (API 29, Q), Google Photos and system libraries query PackageManager
        // features during early static initializers and ContentProvider startups (prior to
        // Application.onCreate). Hooking early via params.defaultClassLoader ensures feature
        // capabilities like Unlimited Backup are spoofed before early caching takes place.
        // On Android 9 and older, class resolution is deferred to onPackageReady.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                FeatureSpoofer.hook(this, params.defaultClassLoader)
                Log.d(TAG, "FeatureSpoofer early apply done for ${params.packageName}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed early FeatureSpoofer apply for ${params.packageName}", t)
            }
        }
        DiagnosticsReporter.recordMilestone { bundle ->
            bundle.putString(Constants.PREF_DIAG_LAST_PACKAGE_LOADED, params.packageName)
        }
    }

    /**
     * Package ready: register FeatureSpoofer + re-apply DeviceSpoofer for any
     * non-denylisted scoped package (same global prefs as Photos).
     */
    override fun onPackageReady(params: XposedModuleInterface.PackageReadyParam) {
        if (!ScopePolicy.shouldSpoof(params.packageName)) {
            Log.w(
                TAG,
                "Skipping feature/device spoof on ready for denylisted/invalid package: ${params.packageName}"
            )
            return
        }

        Log.d(TAG, "Package ready (${params.packageName}). Applying hooks...")

        // Each hook is individually guarded so one failure doesn't block the other.
        logIfNonPhotosPackage(params.packageName, "feature/device spoof on ready")
        try {
            try {
                FeatureSpoofer.hook(this, params.classLoader)
                Log.d(TAG, "FeatureSpoofer hook registered for ${params.packageName}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to register FeatureSpoofer hooks for ${params.packageName}", t)
            }

            try {
                val prefs = getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
                // Re-apply Build writes + ensure SystemProperties hooks exist.
                DeviceSpoofer.hook(this, prefs, packageName = params.packageName)
                Log.d(TAG, "DeviceSpoofer hook registered for ${params.packageName}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to register DeviceSpoofer hooks for ${params.packageName}", t)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register hooks for ${params.packageName}", t)
        }
        DiagnosticsReporter.recordMilestone { bundle ->
            bundle.putString(Constants.PREF_DIAG_LAST_PACKAGE_READY, params.packageName)
            bundle.putLong(Constants.PREF_DIAG_LAST_PACKAGE_READY_AT, System.currentTimeMillis())
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
