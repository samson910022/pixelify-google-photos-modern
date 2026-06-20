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

    override fun onPackageLoaded(params: XposedModuleInterface.PackageLoadedParam) {
        when (params.packageName) {
            Constants.PACKAGE_NAME_GOOGLE_PHOTOS -> {
                Log.d(TAG, "Google Photos detected (${params.packageName}). Applying hooks...")

                try {
                    // FeatureSpoofer: hook hasSystemFeature()
                    FeatureSpoofer.hook(this, params.defaultClassLoader)
                    Log.d(TAG, "FeatureSpoofer hook registered")

                    // DeviceSpoofer: spoof Build properties
                    DeviceSpoofer.hook(params.defaultClassLoader)
                    Log.d(TAG, "DeviceSpoofer hook registered")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register hooks", e)
                }
            }
        }
    }

    override fun onPackageReady(params: XposedModuleInterface.PackageReadyParam) {
        // Reserved for hooks that require fully initialized package
    }

    // onHotReloading/onHotReloaded are optional default methods (available since API 102)
    // using defaults from XposedModuleInterface
}
