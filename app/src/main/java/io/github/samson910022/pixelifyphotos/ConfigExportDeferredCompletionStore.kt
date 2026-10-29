package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray

/**
 * Durable, bounded record of deferred exports that were already completed.
 *
 * A deferred result is saved in Activity instance state so a recreated Activity can finish it
 * after the process was killed. The old process may have completed the write immediately before
 * it died, so the recreated process must not write the same picker result again. A process-local
 * set cannot prove that across a process boundary; this small preference-backed store can.
 *
 * Entries are bounded by [maxEntries] and kept in completion order. There is deliberately no
 * time-based expiry: a saved task can outlive any short TTL, and expiring a marker would allow a
 * duplicate write over a document the user may have edited since. Request IDs are random UUIDs,
 * so a bounded history cannot collide with a new request.
 *
 * A failed durable commit still records the ID in memory, so the current process keeps
 * at-most-once behavior; the failure is logged because it cannot be repaired and must not block
 * the export. [from] returns one instance per process so the in-memory guard survives Activity
 * recreation rather than resetting with the view.
 */
internal class ConfigExportDeferredCompletionStore(
    private val preferences: SharedPreferences,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val lock = Any()
    private val inMemory = LinkedHashSet<String>()

    /** True when [requestId] was completed durably or by this process. */
    fun isCompleted(requestId: String): Boolean = synchronized(lock) {
        requestId in inMemory || requestId in readEntries()
    }

    /**
     * Records [requestId] before the document write is submitted. Mark-before-write is
     * deliberately at-most-once: a crash during the write is not retried, which is preferable
     * to writing the same picker result twice over a document the user may have edited.
     */
    fun markCompleted(requestId: String) {
        synchronized(lock) {
            inMemory.remove(requestId)
            inMemory += requestId
            while (inMemory.size > maxEntries.coerceAtLeast(1)) {
                inMemory.remove(inMemory.first())
            }
            val entries = readEntries()
                .filterNot { it == requestId }
                .toMutableList()
            entries += requestId
            val bounded = entries.takeLast(maxEntries.coerceAtLeast(1))
            // commit() reports disk failure by returning false instead of throwing, so the
            // return value (not an exception) is the only way to notice a lost marker.
            val persisted = runCatching {
                preferences.edit().putString(KEY, encode(bounded)).commit()
            }.getOrDefault(false)
            if (!persisted) {
                Log.w(
                    TAG,
                    "Deferred-export completion marker was not persisted; a recreated process " +
                        "may rewrite the same document",
                )
            }
        }
    }

    private fun readEntries(): List<String> = runCatching {
        val raw = preferences.getString(KEY, null) ?: return@runCatching emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            array.optString(index, "").takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    private fun encode(entries: List<String>): String = JSONArray().apply {
        entries.forEach(::put)
    }.toString()

    companion object {
        private const val TAG = "ConfigExportCompletion"
        internal const val PREFERENCES_FILE = "config_export_deferred_completion"
        internal const val KEY = "completed_requests"
        internal const val DEFAULT_MAX_ENTRIES = 32

        @Volatile
        private var sharedInstance: ConfigExportDeferredCompletionStore? = null

        /** One store per process so the in-memory guard is not lost on Activity recreation. */
        fun from(context: Context): ConfigExportDeferredCompletionStore =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: ConfigExportDeferredCompletionStore(
                    context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE),
                ).also { sharedInstance = it }
            }

        /** Clears the cached process store so a test cannot leak it into another test. */
        internal fun resetForTests() {
            sharedInstance = null
        }
    }
}

/** What a saved deferred-export bundle should do after Activity recreation. */
internal enum class ConfigExportDeferredRestore {
    RESTORE,
    SKIP_COMPLETED,
    SKIP_INVALID,
}

/** Pure validation for a saved deferred export; the Bundle adapter stays in ActivityMain. */
internal object ConfigExportDeferredRestorePolicy {
    fun decide(
        requestId: String?,
        json: String?,
        target: String?,
        alreadyCompleted: Boolean,
    ): ConfigExportDeferredRestore = when {
        requestId.isNullOrBlank() || json == null || target.isNullOrBlank() ->
            ConfigExportDeferredRestore.SKIP_INVALID
        alreadyCompleted -> ConfigExportDeferredRestore.SKIP_COMPLETED
        !ConfigExportPolicy.isAllowlistedExportJson(json) -> ConfigExportDeferredRestore.SKIP_INVALID
        else -> ConfigExportDeferredRestore.RESTORE
    }
}
