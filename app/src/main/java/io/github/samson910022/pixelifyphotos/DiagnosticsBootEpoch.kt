package io.github.samson910022.pixelifyphotos

import java.io.File
import java.security.MessageDigest

/**
 * OS boot identity for diagnostics run ordering. Raw boot UUIDs never leave this helper.
 * The persisted value is an unsalted SHA-256 of the OS boot UUID: it is a stable,
 * non-secret ordering token, not an authentication secret. It is deliberately
 * unsalted so every process and restart within one boot derives the same value;
 * "hardening" it with a random or per-process salt would silently break
 * current-boot matching.
 */
internal object DiagnosticsBootEpoch {
    private const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"
    private const val PROC_STAT_PATH = "/proc/stat"
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val bootTimePattern = Regex("\\d{1,12}")

    /**
     * Prefers the kernel boot UUID. Android 8.x cannot read that file on stock SELinux
     * policy, so fall back to the kernel boot wall-clock time (`btime`) from `/proc/stat`.
     * The fallback is still a per-boot ordering token (not a secret); it is only used
     * when the stronger UUID is unavailable, so a missing UUID no longer disables all
     * diagnostics on a supported Android release. It is best-effort: a device whose
     * kernel reports an unchanging boot time (for example a dead RTC reporting 0 every
     * boot) cannot be distinguished by this fallback, and a wall-clock step may cause
     * a run to appear as prior-boot.
     */
    fun currentHash(): String? = bootIdHash() ?: procStatBootTimeHash()

    private fun bootIdHash(): String? = runCatching {
        hashBootId(File(BOOT_ID_PATH).readText(Charsets.US_ASCII).trim())
    }.getOrNull()

    private fun procStatBootTimeHash(): String? = runCatching {
        File(PROC_STAT_PATH).useLines { lines ->
            parseBootTimeLine(lines)?.let(::hashBootTime)
        }
    }.getOrNull()

    /** Extracts the validated `btime` value from `/proc/stat`; exposed for deterministic tests. */
    internal fun parseBootTimeLine(lines: Sequence<String>): String? =
        lines.firstOrNull { it.startsWith("btime ") }
            ?.removePrefix("btime ")
            ?.trim()
            ?.takeIf { bootTimePattern.matches(it) }

    internal fun hashBootId(bootId: String): String? {
        val normalized = bootId.trim()
        if (!uuidPattern.matches(normalized)) return null
        return hashToken(normalized.lowercase())
    }

    /** Hashes a validated `/proc/stat` boot-time value; exposed for deterministic tests. */
    internal fun hashBootTime(bootTimeSeconds: String): String? {
        val normalized = bootTimeSeconds.trim()
        if (!bootTimePattern.matches(normalized)) return null
        return hashToken("btime:$normalized")
    }

    private fun hashToken(value: String): String? = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.US_ASCII))
            .joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull()
}
