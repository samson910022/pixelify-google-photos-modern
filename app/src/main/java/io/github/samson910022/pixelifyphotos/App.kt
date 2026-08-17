package io.github.samson910022.pixelifyphotos

import android.app.Application
import com.google.android.material.color.DynamicColors
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
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
