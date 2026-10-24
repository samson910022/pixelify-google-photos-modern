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
    const val PREF_DIAG_FEATURE_HOOKS_AT = "PREF_DIAG_FEATURE_HOOKS_AT"
    const val PREF_DIAG_FEATURE_HOOK_STRING = "PREF_DIAG_FEATURE_HOOK_STRING"
    const val PREF_DIAG_FEATURE_HOOK_STRING_INT = "PREF_DIAG_FEATURE_HOOK_STRING_INT"
    const val PREF_DIAG_FEATURE_HOOK_AVAILABLE = "PREF_DIAG_FEATURE_HOOK_AVAILABLE"
    const val PREF_DIAG_SYSPROPS_HOOKS_AT = "PREF_DIAG_SYSPROPS_HOOKS_AT"
    const val PREF_DIAG_SYSPROPS_HOOK_GET = "PREF_DIAG_SYSPROPS_HOOK_GET"
    const val PREF_DIAG_SYSPROPS_HOOK_GET_DEFAULT = "PREF_DIAG_SYSPROPS_HOOK_GET_DEFAULT"
    const val PREF_DIAG_FEATURE_INTERCEPTOR_AT = "PREF_DIAG_FEATURE_INTERCEPTOR_AT"
    const val PREF_DIAG_SYSPROPS_INTERCEPTOR_AT = "PREF_DIAG_SYSPROPS_INTERCEPTOR_AT"
    const val PREF_DIAG_ACTIVE_PACKAGE = "PREF_DIAG_ACTIVE_PACKAGE"
    const val PREF_DIAG_ACTIVE_RUN_ID = "PREF_DIAG_ACTIVE_RUN_ID"
    const val PREF_DIAG_ACTIVE_RUN_STARTED_AT = "PREF_DIAG_ACTIVE_RUN_STARTED_AT"
    const val PREF_DIAG_ACTIVE_RUN_GENERATION = "PREF_DIAG_ACTIVE_RUN_GENERATION"
    const val PREF_DIAG_ACTIVE_RUN_BOOT_COUNT = "PREF_DIAG_ACTIVE_RUN_BOOT_COUNT"
    const val PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH = "PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH"
    const val PREF_DIAG_ACTIVE_ORIGIN_VERIFIED = "PREF_DIAG_ACTIVE_ORIGIN_VERIFIED"
    const val PREF_DIAG_ACTIVE_BINDER_PID = "PREF_DIAG_ACTIVE_BINDER_PID"

    const val DIAGNOSTICS_AUTHORITY = "io.github.samson910022.pixelifyphotos.diagnostics"
    const val METHOD_RECORD_DIAGNOSTICS = "recordDiagnostics"
    const val METHOD_RECORD_VERIFY = "recordVerify"
    const val METHOD_CLEAR_VERIFY = "clearVerify"
    const val METHOD_START_DIAGNOSTICS_RUN = "startDiagnosticsRun"
    const val METHOD_RECORD_DIAGNOSTICS_BATCH = "recordDiagnosticsBatch"

    const val ACTION_RECORD_DIAGNOSTICS = "io.github.samson910022.pixelifyphotos.RECORD_DIAGNOSTICS"
    const val EXTRA_DIAGNOSTICS_METHOD = "method"
    const val EXTRA_DIAGNOSTICS_TOKEN = "token"
    const val EXTRA_DIAGNOSTICS_ORIGIN = "diagnostics_origin"
    const val EXTRA_DIAGNOSTICS_RUN_ID = "diagnostics_run_id"
    const val EXTRA_DIAGNOSTICS_RUN_STARTED_AT = "diagnostics_run_started_at"
    const val EXTRA_DIAGNOSTICS_RUN_GENERATION = "diagnostics_run_generation"
    const val EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT = "diagnostics_run_boot_count"
    const val EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH = "diagnostics_run_boot_epoch"
    const val EXTRA_DIAGNOSTICS_EVENT_SEQUENCE = "diagnostics_event_sequence"
    const val EXTRA_DIAGNOSTICS_BATCH_START = "diagnostics_batch_start"
    const val EXTRA_DIAGNOSTICS_BATCH_EVENT_METHOD = "diagnostics_batch_event_method"
    const val EXTRA_DIAGNOSTICS_BATCH_EVENT = "diagnostics_batch_event"

    // Per-install random token for remote diagnostics IPC authentication (never reportable).
    const val PREF_DIAG_BROADCAST_TOKEN = "PREF_DIAG_BROADCAST_TOKEN"
}
