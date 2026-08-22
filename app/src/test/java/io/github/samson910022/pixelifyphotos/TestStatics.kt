package io.github.samson910022.pixelifyphotos

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Test-only static-field surgery shared by suites that need to inject or reset
 * global JVM state.
 *
 * KNOWN-JDK-17-ONLY: [putStaticObject] writes static-final fields through
 * sun.misc.Unsafe because [Field.set] refuses them on JDK 9+. CI pins Temurin 17
 * (.github/workflows/ci.yml) and CONTRIBUTING.md mandates JDK 17 locally; revisit
 * this helper before bumping the test toolchain. Known caveat: HotSpot may in
 * theory constant-fold trusted-final static reads and bypass a swap — short-lived
 * --no-daemon test JVMs make this a non-issue today, but if a delegation test
 * ever passes vacuously against the real singleton, suspect folding first.
 */
@Suppress("DEPRECATION") // defensive: Unsafe memory-access APIs are terminally deprecated since JDK 23 (JEP 471)
internal object TestStatics {

    /** Resolved reflectively because sun.* is absent from the android.jar compile classpath. */
    private val unsafe: Any by lazy {
        val theUnsafe: Any? = try {
            Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null)
        } catch (t: Throwable) {
            throw IllegalStateException("sun.misc.Unsafe unavailable; unit tests require JDK 17", t)
        }
        theUnsafe ?: error("sun.misc.Unsafe.theUnsafe resolved to null; unit tests require JDK 17")
    }

    /**
     * Unsafe write for FINAL statics (e.g. a Kotlin object's INSTANCE backing
     * field), which [Field.set] refuses on JDK 9+.
     */
    fun putStaticObject(field: Field, value: Any?) {
        val unsafeClass = unsafe.javaClass
        val base = unsafeClass.getMethod("staticFieldBase", Field::class.java)
            .invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java)
            .invoke(unsafe, field) as Long
        unsafeClass.getMethod(
            "putObject",
            Any::class.java,
            Long::class.javaPrimitiveType,
            Any::class.java,
        ).invoke(unsafe, base, offset, value)
    }

    /**
     * Fail-fast checked write for NON-final statics (e.g. Kotlin companion vars).
     * Throws immediately with guidance if the field ever becomes final, and
     * verifies the write landed so a silently ineffective seam fails loudly here.
     * Assumes single-threaded test execution.
     */
    fun setStaticField(clazz: Class<*>, fieldName: String, value: Any?) {
        val field = clazz.getDeclaredField(fieldName).apply { isAccessible = true }
        check(!Modifier.isFinal(field.modifiers)) {
            "${clazz.simpleName}.$fieldName became final; migrate this site to putStaticObject()"
        }
        field.set(null, value)
        check(field.get(null) == value) { "${clazz.simpleName}.$fieldName write did not stick" }
    }

    /** Typed read of a static field, keeping unchecked casts in one place. */
    @Suppress("UNCHECKED_CAST")
    fun <T> getStaticField(clazz: Class<*>, fieldName: String): T =
        clazz.getDeclaredField(fieldName).apply { isAccessible = true }.get(null) as T

    /**
     * Swaps a Kotlin object's INSTANCE backing field with [replacement] via
     * [putStaticObject]. The returned [SwapHandle] restores the original; closing
     * more than once is a no-op.
     */
    fun <T : Any> swapObjectInstance(clazz: Class<T>, fieldName: String, replacement: T): SwapHandle {
        val field = clazz.getDeclaredField(fieldName).apply { isAccessible = true }
        val original = field.get(null)
        try {
            putStaticObject(field, replacement)
            // Readback guards against a silently ineffective swap (e.g. JIT folding).
            check(field.get(null) === replacement) { "${clazz.simpleName}.$fieldName swap did not stick" }
        } catch (t: Throwable) {
            // Never leave the singleton swapped without a handle to restore it.
            putStaticObject(field, original)
            throw t
        }
        return SwapHandle(field, original, "${clazz.simpleName}.$fieldName")
    }

    /**
     * Close-once restoring handle for [swapObjectInstance]. Restore failures
     * propagate loudly from tearDown by design; they are practically unreachable
     * because [swapObjectInstance] verified the forward write via readback.
     */
    class SwapHandle internal constructor(
        private val field: Field,
        private val original: Any?,
        private val site: String,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            putStaticObject(field, original)
        }

        override fun toString(): String = "SwapHandle($site)"
    }
}
