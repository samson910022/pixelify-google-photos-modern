package io.github.samson910022.pixelifyphotos

/** Safe theme selection when preference access is unavailable or throws. */
internal object DiagnosticsUiModePolicy {
    data class PreferenceResult(
        val useClassicUi: Boolean,
        val available: Boolean,
    )

    fun readClassicUiPreference(
        preferencesAvailable: Boolean,
        readPreference: () -> Boolean,
    ): PreferenceResult {
        if (!preferencesAvailable) return PreferenceResult(useClassicUi = false, available = false)
        return runCatching { readPreference() }
            .fold(
                onSuccess = { PreferenceResult(useClassicUi = it, available = true) },
                onFailure = { PreferenceResult(useClassicUi = false, available = false) },
            )
    }
}
