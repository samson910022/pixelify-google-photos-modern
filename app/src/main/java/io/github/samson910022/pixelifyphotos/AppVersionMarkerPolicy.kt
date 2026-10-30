package io.github.samson910022.pixelifyphotos

import android.content.SharedPreferences

/**
 * Decides whether the version changelog may be shown and its marker advanced.
 *
 * [Constants.PREF_LAST_VERSION] is app metadata, not a portable user setting, so it is
 * intentionally outside [OfflineSettingsHandoff]'s user-setting allowlist. Recording it in
 * cold-start local preferences would never converge into the manager store and the same
 * changelog would replay after the first framework bind. Only the authoritative remote
 * settings source may evaluate and advance the marker.
 */
internal object AppVersionMarkerPolicy {
    fun shouldRecordVersion(
        preferences: SharedPreferences?,
        versionMarkerAuthoritative: Boolean,
        versionCode: Int,
    ): Boolean = versionMarkerAuthoritative &&
        preferences != null &&
        preferences.getInt(Constants.PREF_LAST_VERSION, 0) < versionCode
}
