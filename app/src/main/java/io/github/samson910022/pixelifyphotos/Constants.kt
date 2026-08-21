package io.github.samson910022.pixelifyphotos

object Constants {

    val PACKAGE_NAME_GOOGLE_PHOTOS = "com.google.android.apps.photos"

    val PACKAGE_NAME_MODULE = "io.github.samson910022.pixelifyphotos"

    val SUPPORT_URL = "https://github.com/samson910022/pixelify-google-photos-modern/issues"

    val UPDATE_INFO_URL = "https://raw.githubusercontent.com/samson910022/pixelify-google-photos-modern/master/update_info.json"
    val UPDATE_INFO_URL2 = "https://raw.githubusercontent.com/Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos/main/update_info.json"
    val RELEASES_URL = "https://github.com/samson910022/pixelify-google-photos-modern/releases"
    val RELEASES_URL2 = "https://github.com/Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos/releases"

    val FIELD_LATEST_VERSION_CODE = "latest_version_code"

    val SHARED_PREF_FILE_NAME = "prefs"

    val CONF_EXPORT_NAME = "pixelify_photos_conf.json"

    val PREF_SPOOF_FEATURES_LIST = "PREF_SPOOF_FEATURES_LIST"
    val PREF_DEVICE_TO_SPOOF = "PREF_DEVICE_TO_SPOOF"
    val PREF_OVERRIDE_ROM_FEATURE_LEVELS = "PREF_OVERRIDE_ROM_FEATURE_LEVELS"
    val PREF_ENABLE_VERBOSE_LOGS = "PREF_ENABLE_VERBOSE_LOGS"
    val PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE = "PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE"
    val PREF_SPOOF_ANDROID_VERSION_MANUAL = "PREF_SPOOF_ANDROID_VERSION_MANUAL"
    val PREF_LAST_VERSION = "PREF_LAST_VERSION"
    val PREF_USE_CLASSIC_UI = "PREF_USE_CLASSIC_UI"
    val PREF_FIRST_RUN_COMPLETED = "PREF_FIRST_RUN_COMPLETED"

    // Diagnostics state written by the module in the hooked process and read
    // by the module UI (shared remote preferences).
    val PREF_DIAG_MODULE_LOADED_AT = "PREF_DIAG_MODULE_LOADED_AT"
    val PREF_DIAG_LAST_PACKAGE_LOADED = "PREF_DIAG_LAST_PACKAGE_LOADED"
    val PREF_DIAG_LAST_PACKAGE_READY = "PREF_DIAG_LAST_PACKAGE_READY"
    val PREF_DIAG_LAST_PACKAGE_READY_AT = "PREF_DIAG_LAST_PACKAGE_READY_AT"
    val PREF_DIAG_VERIFY_AT = "PREF_DIAG_VERIFY_AT"
    val PREF_DIAG_VERIFY_DEVICE = "PREF_DIAG_VERIFY_DEVICE"
    val PREF_DIAG_VERIFY_PACKAGE = "PREF_DIAG_VERIFY_PACKAGE"
    val PREF_DIAG_VERIFY_OK = "PREF_DIAG_VERIFY_OK"
    val PREF_DIAG_VERIFY_FAILED = "PREF_DIAG_VERIFY_FAILED"
    val PREF_DIAG_VERIFY_NATIVE_READY = "PREF_DIAG_VERIFY_NATIVE_READY"
    val PREF_DIAG_VERIFY_SYSPROPS = "PREF_DIAG_VERIFY_SYSPROPS"

    val DIAGNOSTICS_AUTHORITY = "io.github.samson910022.pixelifyphotos.diagnostics"
    val METHOD_RECORD_DIAGNOSTICS = "recordDiagnostics"
    val METHOD_RECORD_VERIFY = "recordVerify"
    val METHOD_CLEAR_VERIFY = "clearVerify"
}
