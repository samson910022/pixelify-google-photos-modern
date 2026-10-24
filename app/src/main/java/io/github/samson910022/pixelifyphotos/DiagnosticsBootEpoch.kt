package io.github.samson910022.pixelifyphotos

import java.io.File
import java.security.MessageDigest

/** OS boot identity for diagnostics run ordering. Raw boot UUIDs never leave this helper. */
internal object DiagnosticsBootEpoch {
    private const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun currentHash(): String? = runCatching {
        hashBootId(File(BOOT_ID_PATH).readText(Charsets.US_ASCII).trim())
    }.getOrNull()

    internal fun hashBootId(bootId: String): String? {
        val normalized = bootId.trim()
        if (!uuidPattern.matches(normalized)) return null
        return runCatching {
            MessageDigest.getInstance("SHA-256")
                .digest(normalized.lowercase().toByteArray(Charsets.US_ASCII))
                .joinToString("") { byte -> "%02x".format(byte) }
        }.getOrNull()
    }
}
