package io.github.samson910022.pixelifyphotos

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        val options = DynamicColorsOptions.Builder()
            .setPrecondition { _, _ ->
                val prefs = getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, MODE_PRIVATE)
                !prefs.getBoolean(Constants.PREF_USE_CLASSIC_UI, false)
            }
            .build()
        DynamicColors.applyToActivitiesIfAvailable(this, options)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        App.mService = service
    }

    override fun onServiceDied(service: XposedService) {
        App.mService = null
    }

    companion object {
        var mService: XposedService? = null
            private set
    }
}
