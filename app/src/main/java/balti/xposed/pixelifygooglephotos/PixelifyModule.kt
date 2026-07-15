package balti.xposed.pixelifygooglephotos

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class PixelifyModule : XposedModule() {

    companion object {
        const val TAG = "Pixelify"
    }

    override fun onModuleLoaded(params: XposedModuleInterface.ModuleLoadedParam) {
        Log.d(TAG, "Pixelify Google Photos module loaded (libxposed Modern API v5.0)")
    }

    override fun onPackageReady(params: XposedModuleInterface.PackageReadyParam) {
        when (params.packageName) {
            Constants.PACKAGE_NAME_GOOGLE_PHOTOS -> {
                Log.d(TAG, "Google Photos detected (${params.packageName}). Applying hooks...")

                // Each hook is individually guarded so one failure doesn't block the other.
                // The outer catch is a safety net for any unexpected errors that might slip
                // past the inner per-hook guards (e.g. uncaught exceptions from try blocks themselves).
                try {
                    // FeatureSpoofer: hook hasSystemFeature()
                    try {
                        FeatureSpoofer.hook(this, params.classLoader)
                        Log.d(TAG, "FeatureSpoofer hook registered")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to register FeatureSpoofer hooks", t)
                    }

                    // DeviceSpoofer: spoof Build properties
                    try {
                        val prefs = getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
                        DeviceSpoofer.hook(prefs)
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

    // onHotReloading/onHotReloaded are optional default methods (available since API 102)
    // using defaults from XposedModuleInterface
}
