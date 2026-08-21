package io.github.samson910022.pixelifyphotos

object Constants {

    const val PACKAGE_NAME_GOOGLE_PHOTOS = "com.google.android.apps.photos"

    const val PACKAGE_NAME_MODULE = "io.github.samson910022.pixelifyphotos"

    const val SUPPORT_URL = "https://github.com/samson910022/pixelify-google-photos-modern/issues"

    const val UPDATE_INFO_URL = "https://raw.githubusercontent.com/samson910022/pixelify-google-photos-modern/master/update_info.json"
    const val UPDATE_INFO_URL2 = "https://raw.githubusercontent.com/Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos/main/update_info.json"
    const val RELEASES_URL = "https://github.com/samson910022/pixelify-google-photos-modern/releases"
    const val RELEASES_URL2 = "https://github.com/Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos/releases"

    const val FIELD_LATEST_VERSION_CODE = "latest_version_code"

    const val SHARED_PREF_FILE_NAME = "prefs"

    const val CONF_EXPORT_NAME = "pixelify_photos_conf.json"

    const val PREF_SPOOF_FEATURES_LIST = "PREF_SPOOF_FEATURES_LIST"
    const val PREF_DEVICE_TO_SPOOF = "PREF_DEVICE_TO_SPOOF"
    const val PREF_OVERRIDE_ROM_FEATURE_LEVELS = "PREF_OVERRIDE_ROM_FEATURE_LEVELS"
    const val PREF_ENABLE_VERBOSE_LOGS = "PREF_ENABLE_VERBOSE_LOGS"
    const val PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE = "PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE"
    const val PREF_SPOOF_ANDROID_VERSION_MANUAL = "PREF_SPOOF_ANDROID_VERSION_MANUAL"
    const val PREF_LAST_VERSION = "PREF_LAST_VERSION"
    const val PREF_USE_CLASSIC_UI = "PREF_USE_CLASSIC_UI"
    const val PREF_FIRST_RUN_COMPLETED = "PREF_FIRST_RUN_COMPLETED"

    // Diagnostics state written by the module in the hooked process and read
    // by the module UI (shared remote preferences).
    const val PREF_DIAG_MODULE_LOADED_AT = "PREF_DIAG_MODULE_LOADED_AT"
    const val PREF_DIAG_LAST_PACKAGE_LOADED = "PREF_DIAG_LAST_PACKAGE_LOADED"
    const val PREF_DIAG_LAST_PACKAGE_READY = "PREF_DIAG_LAST_PACKAGE_READY"
    const val PREF_DIAG_LAST_PACKAGE_READY_AT = "PREF_DIAG_LAST_PACKAGE_READY_AT"
    const val PREF_DIAG_VERIFY_AT = "PREF_DIAG_VERIFY_AT"
    const val PREF_DIAG_VERIFY_DEVICE = "PREF_DIAG_VERIFY_DEVICE"
    const val PREF_DIAG_VERIFY_PACKAGE = "PREF_DIAG_VERIFY_PACKAGE"
    const val PREF_DIAG_VERIFY_OK = "PREF_DIAG_VERIFY_OK"
    const val PREF_DIAG_VERIFY_FAILED = "PREF_DIAG_VERIFY_FAILED"
    const val PREF_DIAG_VERIFY_NATIVE_READY = "PREF_DIAG_VERIFY_NATIVE_READY"
    const val PREF_DIAG_VERIFY_SYSPROPS = "PREF_DIAG_VERIFY_SYSPROPS"

    const val DIAGNOSTICS_AUTHORITY = "io.github.samson910022.pixelifyphotos.diagnostics"
    const val METHOD_RECORD_DIAGNOSTICS = "recordDiagnostics"
    const val METHOD_RECORD_VERIFY = "recordVerify"
    const val METHOD_CLEAR_VERIFY = "clearVerify"

    const val ACTION_RECORD_DIAGNOSTICS = "io.github.samson910022.pixelifyphotos.RECORD_DIAGNOSTICS"
    const val EXTRA_DIAGNOSTICS_METHOD = "method"
}
