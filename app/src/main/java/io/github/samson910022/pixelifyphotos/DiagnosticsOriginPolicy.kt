package io.github.samson910022.pixelifyphotos

/** Pure policy helpers for sender provenance and package lifecycle decisions. */
internal object DiagnosticsOriginPolicy {
    /**
     * Token-only senders may replace verified state from a known prior boot only
     * on API 30-33, where the broadcast is the AppsFilter fallback transport.
     */
    fun allowsTokenOnlyPriorBootReplacement(sdkInt: Int, tokenOnlyAllowed: Boolean): Boolean =
        sdkInt in 30..33 && tokenOnlyAllowed

    /** Current telemetry runs have one origin; secondary same-process callbacks are unsupported. */
    fun supportsTargetRunCallback(runOrigin: String?, callbackPackage: String): Boolean =
        runOrigin != null && runOrigin == callbackPackage

    data class BroadcastCallerIdentity(
        val callingUid: Int?,
        val callingPackages: Array<String>?,
        val myUid: Int?,
        val verifiedOrigin: String?,
        val requireVerifiedOrigin: Boolean,
        val allowTokenOnlyUnverifiedOrigin: Boolean = false,
    )

    fun tokenOnlyBroadcastIdentity(): BroadcastCallerIdentity = BroadcastCallerIdentity(
        callingUid = null,
        callingPackages = null,
        myUid = null,
        verifiedOrigin = null,
        requireVerifiedOrigin = false,
        allowTokenOnlyUnverifiedOrigin = true,
    )

    fun unavailableApi34BroadcastIdentity(): BroadcastCallerIdentity = BroadcastCallerIdentity(
        callingUid = null,
        callingPackages = null,
        myUid = null,
        verifiedOrigin = null,
        requireVerifiedOrigin = true,
    )

    fun providerVerifiedOrigin(origin: String?, callingPackages: Array<String>?): String? =
        origin?.takeIf { callingPackages?.singleOrNull() == it }

    fun broadcastVerifiedOrigin(
        origin: String?,
        sentUid: Int,
        invalidUid: Int,
        sentPackage: String?,
        packagesForUid: Array<String>?,
    ): String? = origin?.takeIf {
        sentUid != invalidUid && sentPackage != null && sentPackage == it &&
            packagesForUid?.contains(sentPackage) == true
    }

    fun broadcastCallerIdentity(
        origin: String?,
        sentUid: Int,
        invalidUid: Int,
        sentPackage: String?,
        packagesForUid: Array<String>?,
        myUid: Int,
    ): BroadcastCallerIdentity {
        if (sentUid == invalidUid) {
            return BroadcastCallerIdentity(null, null, null, null, requireVerifiedOrigin = false)
        }
        val senderOwned = sentPackage != null && packagesForUid?.contains(sentPackage) == true
        val ownedPackages = if (senderOwned) packagesForUid else emptyArray()
        return BroadcastCallerIdentity(
            callingUid = sentUid,
            callingPackages = ownedPackages,
            myUid = myUid,
            verifiedOrigin = broadcastVerifiedOrigin(
                origin = origin,
                sentUid = sentUid,
                invalidUid = invalidUid,
                sentPackage = sentPackage,
                packagesForUid = packagesForUid,
            ),
            requireVerifiedOrigin = true,
        )
    }
}

/** Small, directly testable decisions shared by [PixelifyModule] lifecycle callbacks. */
internal object ModuleLifecyclePolicy {
    fun shouldHookFeaturesEarly(sdkInt: Int): Boolean = sdkInt >= 29

    fun shouldHandlePackageLoaded(isFirstPackage: Boolean): Boolean = isFirstPackage
}
