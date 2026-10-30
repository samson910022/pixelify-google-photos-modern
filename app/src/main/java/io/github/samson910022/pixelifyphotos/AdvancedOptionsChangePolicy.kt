package io.github.samson910022.pixelifyphotos

internal data class AdvancedOptionsSelection(
    val verboseLogging: Boolean,
    val androidVersionMode: AndroidVersionMode,
    val manualAndroidVersion: String?,
)

internal enum class AndroidVersionMode { NONE, FOLLOW_DEVICE, MANUAL }

/** Identifies only edits made since Advanced Options was populated. */
internal object AdvancedOptionsChangePolicy {
    fun changedKeys(
        before: AdvancedOptionsSelection,
        after: AdvancedOptionsSelection,
    ): Set<String> = buildSet {
        if (before.verboseLogging != after.verboseLogging) {
            add(Constants.PREF_ENABLE_VERBOSE_LOGS)
        }

        val androidVersionChanged = before.androidVersionMode != after.androidVersionMode ||
            (before.androidVersionMode == AndroidVersionMode.MANUAL &&
                after.androidVersionMode == AndroidVersionMode.MANUAL &&
                before.manualAndroidVersion != after.manualAndroidVersion)
        if (androidVersionChanged) {
            // These keys encode one mutually exclusive choice. Updating them as a
            // pair prevents a remote preference from leaving both modes enabled.
            add(Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE)
            add(Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL)
        }
    }
}
