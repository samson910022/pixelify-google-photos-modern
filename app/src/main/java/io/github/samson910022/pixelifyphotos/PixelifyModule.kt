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
     */
    override fun onPackageLoaded(params: XposedModuleInterface.PackageLoadedParam) {
        if (params.packageName != Constants.PACKAGE_NAME_GOOGLE_PHOTOS) return
        // PackageLoadedParam.isFirstPackage() is part of libxposed API 101.
        if (!params.isFirstPackage) {
            Log.v(TAG, "Skipping non-first package load for ${params.packageName}")
            return
        }

        Log.d(TAG, "Google Photos package loaded. Early device spoof...")
        try {
            val prefs = getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
            // Pass module so DeviceSpoofer can resolve module nativeLibraryDir for JNI.
            // No failure UI yet — Application/host extract may only work on package ready.
            DeviceSpoofer.hook(this, prefs, allowFailureUi = false)
            Log.d(TAG, "DeviceSpoofer early apply done")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed early DeviceSpoofer apply", t)
        }
    }

    override fun onPackageReady(params: XposedModuleInterface.PackageReadyParam) {
        when (params.packageName) {
            Constants.PACKAGE_NAME_GOOGLE_PHOTOS -> {
                Log.d(TAG, "Google Photos ready (${params.packageName}). Applying hooks...")

                // Each hook is individually guarded so one failure doesn't block the other.
                try {
                    try {
                        FeatureSpoofer.hook(this, params.classLoader)
                        Log.d(TAG, "FeatureSpoofer hook registered")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to register FeatureSpoofer hooks", t)
                    }

                    try {
                        val prefs = getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
                        // Re-apply Build writes + ensure SystemProperties hooks exist.
                        DeviceSpoofer.hook(this, prefs)
                        Log.d(TAG, "DeviceSpoofer hook registered")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to register DeviceSpoofer hooks", t)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to register hooks", t)
                }
            }
        }
    }
}
