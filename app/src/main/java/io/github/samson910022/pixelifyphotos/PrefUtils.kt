package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.content.SharedPreferences

/**
 * Utility to retrieve SharedPreferences for the module.
 *
 * Prefers remote preferences via XposedService (used by the hook process),
 * falling back to MODE_PRIVATE when the service is not connected.
 */
object PrefUtils {

    fun getPrefs(context: Context): SharedPreferences? {
        return try {
            App.mService?.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
                ?: context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE)
        }
    }
}
