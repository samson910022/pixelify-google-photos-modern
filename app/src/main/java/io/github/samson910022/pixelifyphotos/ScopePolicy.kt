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
     * scopes them in LSPosed. Exact match only (v1).
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
