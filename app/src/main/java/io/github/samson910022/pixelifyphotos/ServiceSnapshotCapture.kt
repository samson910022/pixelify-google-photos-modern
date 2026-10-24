package io.github.samson910022.pixelifyphotos

/** Result of reading data against a service state and verifying that state afterward. */
internal data class CapturedServiceSnapshot<T>(
    val serviceState: ServiceReadinessSnapshot,
    val value: T,
    val consistent: Boolean,
)

/** Bounded policy for avoiding preference data/version mismatches during service rebinds. */
internal object ServiceSnapshotCapture {

    fun <T> capture(
        maxAttempts: Int = 3,
        readServiceState: () -> ServiceReadinessSnapshot,
        readForState: (ServiceReadinessSnapshot) -> T,
        afterRead: () -> Unit = {},
    ): CapturedServiceSnapshot<T> {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        var last: CapturedServiceSnapshot<T>? = null
        repeat(maxAttempts) {
            val before = readServiceState()
            val value = readForState(before)
            afterRead()
            val after = readServiceState()
            val stable = before.version == after.version &&
                before.phase == after.phase && before.service === after.service
            last = CapturedServiceSnapshot(before, value, stable)
            if (stable) return last!!
        }
        return checkNotNull(last)
    }
}

/** Copy only a report built from a service-state-consistent snapshot. */
internal object DiagnosticsCopyPolicy {
    fun mayCopy(consistent: Boolean): Boolean = consistent
}
