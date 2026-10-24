package io.github.samson910022.pixelifyphotos

/** Pure presentation decisions for data captured while the service may be rebinding. */
internal object DiagnosticsSnapshotPresentation {
    fun mayRenderCapturedData(consistent: Boolean): Boolean = consistent
}

/** Keeps a known empty scope distinct from unavailable scope in both UI and reports. */
internal object DiagnosticsScopePresentation {
    fun label(
        scope: List<String>?,
        unknownLabel: String = "unknown",
        emptyLabel: String = "none",
    ): String = when {
        scope == null -> unknownLabel
        scope.isEmpty() -> emptyLabel
        else -> scope.joinToString()
    }
}

/** Cold local settings never imply a readable-empty remote telemetry store. */
internal object DiagnosticsHookStatePolicy {
    fun capture(
        profileSettingsAvailable: Boolean,
        telemetryPreferencesAvailable: Boolean,
        readTelemetry: () -> DiagnosticsCollector.HookState,
    ): DiagnosticsCollector.HookState {
        if (!telemetryPreferencesAvailable) {
            return DiagnosticsCollector.HookState(
                diagnosticsAvailable = false,
                profileSettingsAvailable = profileSettingsAvailable,
            )
        }
        return try {
            readTelemetry().copy(
                profileSettingsAvailable = profileSettingsAvailable,
            )
        } catch (_: RuntimeException) {
            DiagnosticsCollector.HookState(
                diagnosticsAvailable = false,
                profileSettingsAvailable = profileSettingsAvailable,
            )
        }
    }
}

/** Builds HookState from exactly one SharedPreferences.all snapshot with strict types. */
internal object DiagnosticsHookStateSnapshot {
    fun capture(
        readAll: () -> Map<String, *>,
        currentBootCount: Int?,
        currentBootEpoch: String?,
    ): DiagnosticsCollector.HookState {
        val values = readAll().mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }.toMap()

        fun raw(key: String): Any? {
            if (key !in values) return null
            return values[key] ?: throw ClassCastException("Preference '$key' has a null value")
        }
        fun string(key: String): String? = when (val value = raw(key)) {
            null -> null
            is String -> value
            else -> throw ClassCastException("Preference '$key' is not a String")
        }
        fun long(key: String): Long? = when (val value = raw(key)) {
            null -> null
            is Long -> value
            else -> throw ClassCastException("Preference '$key' is not a Long")
        }
        fun int(key: String): Int? = when (val value = raw(key)) {
            null -> null
            is Int -> value
            else -> throw ClassCastException("Preference '$key' is not an Int")
        }
        fun boolean(key: String): Boolean? = when (val value = raw(key)) {
            null -> null
            is Boolean -> value
            else -> throw ClassCastException("Preference '$key' is not a Boolean")
        }
        fun timestamp(key: String): Long? = long(key)?.takeIf { it > 0L }

        val failedValues = raw(Constants.PREF_DIAG_VERIFY_FAILED)
        val verifyFailed = when (failedValues) {
            null -> emptyList()
            is Set<*> -> {
                if (!failedValues.all { it is String }) {
                    throw ClassCastException("Preference '${Constants.PREF_DIAG_VERIFY_FAILED}' is not a StringSet")
                }
                failedValues.map { it as String }
            }
            else -> throw ClassCastException("Preference '${Constants.PREF_DIAG_VERIFY_FAILED}' is not a StringSet")
        }

        val activeRunBootCount = int(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT)
        val binderPid = int(Constants.PREF_DIAG_ACTIVE_BINDER_PID)
        return DiagnosticsCollector.HookState(
            activePackage = string(Constants.PREF_DIAG_ACTIVE_PACKAGE),
            activeRunId = string(Constants.PREF_DIAG_ACTIVE_RUN_ID),
            activeRunStartedAt = timestamp(Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT),
            activeRunBootCount = activeRunBootCount?.takeIf { it >= 0 },
            currentBootCount = currentBootCount,
            activeRunBootEpoch = string(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH),
            currentBootEpoch = currentBootEpoch,
            activeOriginVerified = boolean(Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED) ?: false,
            processPid = binderPid?.takeIf { it > 0 },
            moduleLoadedAt = timestamp(Constants.PREF_DIAG_MODULE_LOADED_AT),
            lastPackageLoaded = string(Constants.PREF_DIAG_LAST_PACKAGE_LOADED),
            lastPackageReady = string(Constants.PREF_DIAG_LAST_PACKAGE_READY),
            lastPackageReadyAt = timestamp(Constants.PREF_DIAG_LAST_PACKAGE_READY_AT),
            verifyAt = timestamp(Constants.PREF_DIAG_VERIFY_AT),
            verifyDevice = string(Constants.PREF_DIAG_VERIFY_DEVICE),
            verifyPackage = string(Constants.PREF_DIAG_VERIFY_PACKAGE),
            verifyOk = boolean(Constants.PREF_DIAG_VERIFY_OK),
            verifyFailed = verifyFailed,
            nativeReady = boolean(Constants.PREF_DIAG_VERIFY_NATIVE_READY),
            systemPropsHooked = boolean(Constants.PREF_DIAG_VERIFY_SYSPROPS),
            featureHooksAt = timestamp(Constants.PREF_DIAG_FEATURE_HOOKS_AT),
            featureHookHasSystemFeature = boolean(Constants.PREF_DIAG_FEATURE_HOOK_STRING),
            featureHookHasSystemFeatureWithFlags = boolean(Constants.PREF_DIAG_FEATURE_HOOK_STRING_INT),
            featureHookGetAvailableFeatures = boolean(Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE),
            systemPropsHooksAt = timestamp(Constants.PREF_DIAG_SYSPROPS_HOOKS_AT),
            systemPropsHookGet = boolean(Constants.PREF_DIAG_SYSPROPS_HOOK_GET),
            systemPropsHookGetWithDefault = boolean(Constants.PREF_DIAG_SYSPROPS_HOOK_GET_DEFAULT),
            featureInterceptorAt = timestamp(Constants.PREF_DIAG_FEATURE_INTERCEPTOR_AT),
            systemPropsInterceptorAt = timestamp(Constants.PREF_DIAG_SYSPROPS_INTERCEPTOR_AT),
        )
    }
}

internal data class DiagnosticsProfile(
    val deviceName: String?,
    val isFallback: Boolean,
    /** True only when the profile preference source was available and its read completed. */
    val settingsReadable: Boolean,
)

internal object DiagnosticsProfilePolicy {
    /** Missing/unreadable preferences never become an apparent user-selected default. */
    fun fromPreferences(
        preferencesAvailable: Boolean,
        savedDeviceName: String?,
        defaultDeviceName: String,
    ): DiagnosticsProfile = when {
        !preferencesAvailable -> DiagnosticsProfile(
            deviceName = null,
            isFallback = false,
            settingsReadable = false,
        )
        savedDeviceName == null -> DiagnosticsProfile(
            deviceName = defaultDeviceName,
            isFallback = true,
            settingsReadable = true,
        )
        else -> DiagnosticsProfile(
            deviceName = savedDeviceName,
            isFallback = false,
            settingsReadable = true,
        )
    }

    fun fromPreferenceRead(
        preferencesAvailable: Boolean,
        readSavedDeviceName: () -> String?,
        defaultDeviceName: String,
    ): DiagnosticsProfile {
        if (!preferencesAvailable) return fromPreferences(false, null, defaultDeviceName)
        return runCatching {
            fromPreferences(true, readSavedDeviceName(), defaultDeviceName)
        }.getOrElse {
            fromPreferences(false, null, defaultDeviceName)
        }
    }
}
