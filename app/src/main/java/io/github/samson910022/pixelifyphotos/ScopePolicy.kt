package io.github.samson910022.pixelifyphotos

/**
 * Pure scope / denylist policy for multi-app Option B (B0 + denylist).
 *
 * LSPosed decides which packages load the module. This policy only decides
 * whether Device+Feature spoof should run inside an already-loaded process.
 *
 * Keep this object free of Android framework dependencies so unit tests can
 * run on the host JVM.
 */
object ScopePolicy {

    /**
     * Packages that must never receive Build/feature spoof even if the user
     * scopes them in LSPosed, and that the module UI must never force-stop.
     *
     * Exact match only (v1): near-miss packages (e.g. `com.google.android.gms.unstable`)
     * are intentionally not matched, so they still pass `shouldSpoof`. This is a
     * deliberate trade-off: prefix matching would silently block legitimate
     * third-party apps, while the documented stance is that extra scoped apps are
     * advanced/unsupported. Users should keep the scope list to Photos.
     */
    val DENYLIST: Set<String> = setOf(
        "com.google.android.gms",
        "com.android.vending",
        "com.google.android.gsf",
        "com.google.android.gsf.login",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.settings",
        "com.android.systemui",
        "com.android.phone",
        "com.google.android.gm",
        "com.google.android.apps.maps",
        "com.google.android.youtube",
        "com.google.android.apps.docs",
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.messaging",
        "com.google.android.apps.meetings",
        "com.google.android.apps.contacts",
        "com.google.android.dialer",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
    )

    /**
     * @return true if spoof hooks should be applied for [packageName].
     */
    fun shouldSpoof(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return packageName !in DENYLIST
    }

    /**
     * @return true if [packageName] is on the soft denylist.
     */
    fun isDenied(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return packageName in DENYLIST
    }
}
