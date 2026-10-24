package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

/** A process-local epoch prevents a persisted service version from authorizing an old export. */
internal object ConfigExportProcessEpoch {
    val current: String = UUID.randomUUID().toString()
}

/** Source stamp persisted with an export; the process epoch scopes the otherwise local version. */
internal data class ConfigExportSourceStamp(
    val processEpoch: String,
    val serviceVersion: Long,
    val servicePhase: ServicePhase,
    val connected: Boolean,
) {
    companion object {
        fun from(state: ServiceReadinessSnapshot, processEpoch: String) = ConfigExportSourceStamp(
            processEpoch = processEpoch,
            serviceVersion = state.version,
            servicePhase = state.phase,
            connected = state.connected,
        )
    }
}

/** Durable association between one picker callback ID, its source, and allowlisted JSON. */
internal data class ConfigExportPendingRequest(
    val id: String,
    val source: ConfigExportSourceStamp,
    val json: String,
)

/** Minimal persistence boundary so request transitions can be tested without Android storage. */
internal interface ConfigExportRequestPersistence {
    fun read(): String?
    fun commit(value: String?): Boolean
}

/** One pending request is persisted before launching SAF; callbacks only consume their own ID. */
internal class ConfigExportRequestGate(
    private val persistence: ConfigExportRequestPersistence,
) {
    companion object {
        private val storageLock = Any()
        /** A one-shot result has no channel to be restored after its callback was delivered. */
        private val deliveredRequestIds = mutableSetOf<String>()
        private const val FORMAT_VERSION = 1

        fun from(context: Context): ConfigExportRequestGate {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return ConfigExportRequestGate(SharedPreferencesPersistence(preferences))
        }

        private const val PREFS_NAME = "config_export_pending"
        private const val PENDING_KEY = "pending_request"

        private class SharedPreferencesPersistence(
            private val preferences: SharedPreferences,
        ) : ConfigExportRequestPersistence {
            override fun read(): String? = preferences.getString(PENDING_KEY, null)

            override fun commit(value: String?): Boolean {
                val editor = preferences.edit()
                if (value == null) editor.remove(PENDING_KEY) else editor.putString(PENDING_KEY, value)
                return editor.commit()
            }
        }
    }

    /** Synchronous commit is required: a failed durable write must never launch the picker. */
    fun begin(request: ConfigExportPendingRequest): Boolean = synchronized(storageLock) {
        val existing = try {
            persistence.read()
        } catch (_: Exception) {
            return@synchronized false
        }
        if (existing != null || !isValid(request)) return@synchronized false
        val committed = runCatching { persistence.commit(encode(request)) }.getOrDefault(false)
        if (!committed) clearMatchingLocked(request.id, abandonIfStillPending = true)
        committed
    }

    /** Returns null for a corrupt record; [hasPending] still fails closed in that case. */
    fun pending(): ConfigExportPendingRequest? = synchronized(storageLock) {
        runCatching { persistence.read()?.let(::decode) }.getOrNull()
    }

    /**
     * Restores a same-process request, abandons a stale-epoch request by ID, and removes corrupt
     * records that have no usable ID/key to receive a callback. Read/commit failures remain closed.
     */
    fun restoreForProcess(processEpoch: String): ConfigExportPendingRequest? = synchronized(storageLock) {
        val raw = try {
            persistence.read()
        } catch (_: Exception) {
            return@synchronized null
        } ?: return@synchronized null

        val request = decode(raw)
        if (request == null) {
            clearCorruptRecordLocked(raw)
            return@synchronized null
        }
        if (request.source.processEpoch != processEpoch) {
            clearMatchingLocked(request.id, abandonIfStillPending = true)
            return@synchronized null
        }
        if (request.id in deliveredRequestIds) {
            // This request's one-shot result was already dispatched in this process. A recreated
            // Activity must not register the dead key, but can retry its ID-checked cleanup.
            clearMatchingLocked(request.id, abandonIfStillPending = true)
            return@synchronized null
        }
        request
    }

    fun hasPending(): Boolean = synchronized(storageLock) {
        runCatching { persistence.read() != null }.getOrDefault(true)
    }

    /**
     * Whether a durable request can still receive a result. Generic queries remain fail-closed on
     * unreadable storage. A matching callback may finish its locally marked one-shot ID if storage
     * is unavailable, but a readable replacement ID or corrupt record still blocks completion.
     */
    fun hasAwaitingResult(deliveredCallbackId: String? = null): Boolean = synchronized(storageLock) {
        val raw = try {
            persistence.read()
        } catch (_: Exception) {
            // A callback may finish its exact one-shot request even if removal failed and the
            // follow-up read is unavailable. Generic callers still fail closed. begin() also
            // fails closed while unreadable, so no newer request can be admitted through this gate.
            return@synchronized deliveredCallbackId == null || deliveredCallbackId !in deliveredRequestIds
        } ?: return@synchronized false
        val request = decode(raw) ?: return@synchronized true
        // The callback-specific exception never bypasses a different (possibly newer) request.
        if (deliveredCallbackId != null && request.id != deliveredCallbackId) return@synchronized true
        request.id !in deliveredRequestIds
    }

    /** True when this exact ID was marked one-shot under [storageLock], even if storage is unreadable. */
    fun wasCallbackDelivered(requestId: String): Boolean = synchronized(storageLock) {
        requestId in deliveredRequestIds
    }

    /** Clears only the request whose unique ID was dispatched by the result registry. */
    fun consume(requestId: String): ConfigExportPendingRequest? = synchronized(storageLock) {
        val raw = runCatching { persistence.read() }.getOrNull() ?: return@synchronized null
        val request = decode(raw) ?: return@synchronized null
        if (request.id != requestId) return@synchronized null
        if (requestId in deliveredRequestIds) {
            // A duplicate callback or a callback after synchronous launcher abandonment has no
            // authority to export. Retry only this ID's cleanup, never return its JSON again.
            clearExactLocked(requestId, abandonIfStillPending = true)
            return@synchronized null
        }

        // ActivityResult callbacks are one-shot even when SharedPreferences cannot durably remove
        // the record. Remember delivery before attempting removal so recreation will not register
        // this dead key again. This marker is process-local and scoped to the exact request ID.
        deliveredRequestIds += requestId
        if (runCatching { persistence.commit(null) }.getOrDefault(false)) {
            deliveredRequestIds -= requestId
            return@synchronized request
        }

        // SharedPreferences.commit() updates its live in-process map even when disk persistence
        // fails. Read that map back before deciding whether this one-shot callback was consumed;
        // a stale on-disk record belongs to this process epoch and is abandoned after process death.
        val afterRemoval = try {
            persistence.read()
        } catch (_: Exception) {
            return@synchronized null
        } ?: run {
            deliveredRequestIds -= requestId
            return@synchronized request
        }
        val remaining = decode(afterRemoval) ?: return@synchronized null
        if (remaining.id == requestId) return@synchronized null
        deliveredRequestIds -= requestId

        // A different request may now be pending. Return only the request captured above, never
        // the replacement, so this callback cannot be paired with another request's JSON.
        request
    }

    /** Used for a launch exception; a late callback for this ID cannot consume a later request. */
    fun clear(requestId: String): Boolean = synchronized(storageLock) {
        val raw = runCatching { persistence.read() }.getOrNull() ?: return@synchronized false
        val request = decode(raw) ?: return@synchronized false
        if (request.id != requestId) return@synchronized false
        clearExactLocked(requestId, abandonIfStillPending = true)
    }

    private fun clearMatchingLocked(requestId: String, abandonIfStillPending: Boolean) {
        val persisted = runCatching { persistence.read()?.let(::decode) }.getOrNull()
        if (persisted?.id == requestId) clearExactLocked(requestId, abandonIfStillPending)
    }

    /** Caller has verified the exact pending ID while holding [storageLock]. */
    private fun clearExactLocked(requestId: String, abandonIfStillPending: Boolean): Boolean {
        if (runCatching { persistence.commit(null) }.getOrDefault(false)) {
            deliveredRequestIds -= requestId
            return true
        }

        val afterRemoval = try {
            persistence.read()
        } catch (_: Exception) {
            if (abandonIfStillPending) deliveredRequestIds += requestId
            return false
        }
        if (afterRemoval == null) {
            deliveredRequestIds -= requestId
            return true
        }
        val remaining = decode(afterRemoval)
        if (remaining?.id == requestId) {
            if (abandonIfStillPending) deliveredRequestIds += requestId
            return false
        }

        // The old ID is no longer pending. Do not clear a replacement request, including when
        // readback is corrupt/unknown; hasAwaitingResult() will fail closed for that state.
        deliveredRequestIds -= requestId
        return true
    }

    private fun clearCorruptRecordLocked(raw: String) {
        val currentRaw = runCatching { persistence.read() }.getOrNull()
        if (currentRaw == raw) runCatching { persistence.commit(null) }
    }

    private fun isValid(request: ConfigExportPendingRequest): Boolean =
        request.id.isNotBlank() &&
            request.source.processEpoch.isNotBlank() &&
            request.source.serviceVersion >= 0L &&
            ConfigExportPolicy.isAllowlistedExportJson(request.json)

    private fun encode(request: ConfigExportPendingRequest): String = JSONObject().apply {
        put("format", FORMAT_VERSION)
        put("id", request.id)
        put("process_epoch", request.source.processEpoch)
        put("service_version", request.source.serviceVersion)
        put("service_phase", request.source.servicePhase.name)
        put("connected", request.source.connected)
        // This payload is validated against ConfigExportPolicy's explicit settings allowlist.
        put("json", request.json)
    }.toString()

    private fun decode(raw: String): ConfigExportPendingRequest? {
        return runCatching {
            val value = JSONObject(raw)
            if (value.getInt("format") != FORMAT_VERSION) {
                null
            } else {
                val source = ConfigExportSourceStamp(
                    processEpoch = value.getString("process_epoch"),
                    serviceVersion = value.getLong("service_version"),
                    servicePhase = ServicePhase.valueOf(value.getString("service_phase")),
                    connected = value.getBoolean("connected"),
                )
                ConfigExportPendingRequest(
                    id = value.getString("id"),
                    source = source,
                    json = value.getString("json"),
                ).takeIf(::isValid)
            }
        }.getOrNull()
    }
}

/** Callback validation kept separate from persistence so stale IDs/epochs are directly testable. */
internal object ConfigExportRequestPolicy {
    data class AuthorizedWrite<T : Any>(
        val requestId: String,
        val target: T,
        val json: String,
    )

    fun mayWrite(
        request: ConfigExportPendingRequest,
        callbackRequestId: String,
        currentSource: ConfigExportSourceStamp,
        sourceStillAuthorized: Boolean,
    ): Boolean = callbackRequestId == request.id &&
        request.source.processEpoch == currentSource.processEpoch &&
        request.source == currentSource &&
        sourceStillAuthorized &&
        ConfigExportPolicy.isAllowlistedExportJson(request.json)

    fun <T : Any> authorizeWrite(
        request: ConfigExportPendingRequest,
        callbackRequestId: String,
        target: T?,
        currentSource: ConfigExportSourceStamp,
        sourceStillAuthorized: Boolean,
    ): AuthorizedWrite<T>? {
        if (target == null || !mayWrite(request, callbackRequestId, currentSource, sourceStillAuthorized)) {
            return null
        }
        return AuthorizedWrite(request.id, target, request.json)
    }
}
