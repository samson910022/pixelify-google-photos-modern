package balti.xposed.pixelifygooglephotos

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

class PixelifyModule : XposedModule() {

    companion object {
        const val TAG = "Pixelify"
    }

    override fun onModuleLoaded(params: XposedInterface.ModuleLoadedParam) {
        Log.d(TAG, "Pixelify Google Photos module loaded (libxposed Modern API v5.0)")
    }

    override fun onPackageLoaded(params: XposedInterface.PackageLoadedParam) {
        when (params.packageName) {
            Constants.PACKAGE_NAME_GOOGLE_PHOTOS -> {
                Log.d(TAG, "Google Photos detected (${params.packageName}). Applying hooks...")

                try {
                    // FeatureSpoofer: hook hasSystemFeature()
                    FeatureSpoofer.hook(this, params.classLoader)
                    Log.d(TAG, "FeatureSpoofer hook registered")

                    // DeviceSpoofer: spoof Build properties
                    DeviceSpoofer.hook(params.classLoader)
                    Log.d(TAG, "DeviceSpoofer hook registered")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register hooks", e)
                }
            }
        }
    }

    override fun onPackageReady(params: XposedInterface.PackageReadyParam) {
        // Reserved for hooks that require fully initialized package
    }

    override fun onHotReloading() {
        Log.d(TAG, "Hot reload starting...")
    }

    override fun onHotReloaded() {
        Log.d(TAG, "Hot reload completed")
    }
}
