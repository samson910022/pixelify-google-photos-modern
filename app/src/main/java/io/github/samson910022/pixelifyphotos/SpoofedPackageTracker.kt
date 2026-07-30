package io.github.samson910022.pixelifyphotos

/**
 * Resolves which packages the module UI should force-stop after preference changes.
 *
 * Scope source of truth is LSPosed via [io.github.libxposed.service.XposedService.getScope]
 * (module UI process). Hook-side remote prefs are read-only in target apps, so packages
 * are not recorded from [PixelifyModule].
 *
 * Pure JVM logic (no Android framework) for unit tests.
 */
object SpoofedPackageTracker {

    private val PACKAGE_NAME_PATTERN = Regex("^[a-zA-Z0-9._]+$")

    fun isValidPackageName(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return packageName.matches(PACKAGE_NAME_PATTERN)
    }

    /**
     * Packages the UI should force-stop: always Photos, plus [scopePackages] from
     * LSPosed that pass name validation and [ScopePolicy.shouldSpoof].
     *
     * @param scopePackages typically [io.github.libxposed.service.XposedService.getScope];
     * null/empty still yields Photos-only.
     */
    fun packagesToForceStop(scopePackages: Set<String>?): Set<String> {
        val out = linkedSetOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        scopePackages?.forEach { pkg ->
            if (isValidPackageName(pkg) && ScopePolicy.shouldSpoof(pkg)) {
                out.add(pkg)
            }
        }
        return out
    }
}
