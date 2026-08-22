package io.github.samson910022.pixelifyphotos

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * Lightweight [ContentProvider] that allows hooked target processes (e.g. Google Photos)
 * to safely send diagnostic telemetry and device-spoof VERIFY outcomes back to the module
 * manager application.
 *
 * Modern Xposed frameworks (LSPosed, Vector) make `XposedModule.getRemotePreferences`
 * read-only inside hooked target processes by specification. This provider acts as the
 * authoritative write pipeline, validating incoming callers and keys against immutable
 * security boundaries before persisting them into the manager's preferences.
 */
open class DiagnosticsProvider : ContentProvider() {

    internal var testContext: Context? = null
    internal var testCallingUid: Int? = null
    internal var testMyUid: Int? = null

    /**
     * Test seam for the [call] result bundle: host-side unit tests run against the
     * stub android.jar, whose Bundle is inert (putBoolean is a no-op), so tests
     * override this to supply an observable bundle instead of instrumenting every
     * Bundle construction.
     */
    internal open fun createResultBundle(): Bundle = Bundle()

    private fun resolveContext(): Context? = testContext ?: context
    private fun resolveCallingUid(): Int = testCallingUid ?: runCatching { Binder.getCallingUid() }.getOrDefault(0)
    private fun resolveMyUid(): Int = testMyUid ?: runCatching { Process.myUid() }.getOrDefault(0)

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    @Suppress("DEPRECATION")
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val result = createResultBundle()
        val ctx = resolveContext() ?: return result.apply { putBoolean("success", false) }

        val callingUid = resolveCallingUid()
        val myUid = resolveMyUid()
        val callingPackages = runCatching { ctx.packageManager.getPackagesForUid(callingUid) }.getOrNull()

        val success = DiagnosticsStore.applyDiagnostics(
            context = ctx,
            method = method,
            extras = extras,
            callingUid = callingUid,
            callingPackages = callingPackages,
            myUid = myUid,
        )

        return result.apply { putBoolean("success", success) }
    }
}
