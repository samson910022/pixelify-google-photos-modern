package io.github.samson910022.pixelifyphotos

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class PixelifyModule : XposedModule() {

    companion object {
        const val TAG = "Pixelify"
    }

    override fun onModuleLoaded(params: XposedModuleInterface.ModuleLoadedParam) {
        Log.d(TAG, "Pixelify Infinity module loaded (libxposed Modern API)")
    }

    /**
     * Early entry (closest to legacy handleLoadPackage). Apply Build spoof +
     * SystemProperties hooks as soon as the package class loader exists.
     *
     * Multi-app Option B: trust LSPosed scope for any first package, then apply
     * soft [ScopePolicy] denylist (skip spoof, still allow module load).
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

        Log.d(TAG, "Package loaded (${params.packageName}). Early device spoof...")
        try {
            val prefs = getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
            // Pass module so DeviceSpoofer can resolve module nativeLibraryDir for JNI.
            // No failure UI yet — Application/host extract may only work on package ready.
            DeviceSpoofer.hook(this, prefs, allowFailureUi = false)
            Log.d(TAG, "DeviceSpoofer early apply done for ${params.packageName}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed early DeviceSpoofer apply for ${params.packageName}", t)
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
                DeviceSpoofer.hook(this, prefs)
                Log.d(TAG, "DeviceSpoofer hook registered for ${params.packageName}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to register DeviceSpoofer hooks for ${params.packageName}", t)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register hooks for ${params.packageName}", t)
        }
    }
}
