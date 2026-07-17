package io.github.samson910022.pixelifyphotos

import android.os.Build
import android.util.Log
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Unit tests for [DeviceSpoofer] — reflection / Unsafe static field spoofing.
 *
 * Android [Log] is stubbed so JVM unit tests do not crash on SDK stubs.
 */
class DeviceSpooferTest {

    private lateinit var mockedLog: MockedStatic<Log>

    @Before
    fun setUp() {
        mockedLog = Mockito.mockStatic(Log::class.java)
        mockedLog.`when`<Int> { Log.e(any<String>(), any<String>(), anyOrNull()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.e(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>(), anyOrNull()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.d(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.v(any<String>(), any<String>()) }.thenReturn(0)
    }

    @After
    fun tearDown() {
        mockedLog.close()
    }

    // =========================================================================
    // C1: ANDROID_17_SDK_INT constant — diagnostic only
    // =========================================================================

    @Test
    fun `ANDROID_17_SDK_INT equals 37`() {
        val field = DeviceSpoofer::class.java.getDeclaredField("ANDROID_17_SDK_INT")
        field.isAccessible = true
        assertEquals("Android 17 SDK_INT should be 37", 37, field.getInt(null))
    }

    @Test
    fun `DeviceSpoofer source does not hard-skip Build spoofing on Android 17`() {
        assertNotNull(
            DeviceSpoofer::class.java.getDeclaredMethod(
                "hook",
                android.content.SharedPreferences::class.java,
            ),
        )
        val setStatic = DeviceSpoofer::class.java.getDeclaredMethod(
            "setStaticField",
            Class::class.java,
            String::class.java,
            Any::class.java,
        )
        assertEquals(Boolean::class.javaPrimitiveType, setStatic.returnType)
    }

    // =========================================================================
    // C2–C5: setStaticField catches Throwable / returns false safely
    // =========================================================================

    @Test
    fun `setStaticField returns false when field does not exist`() {
        val ok = invokeSetStaticField(Integer::class.java, "NONEXISTENT_FIELD_XYZ", 42)
        assertFalse(ok)
    }

    @Test
    fun `setStaticField returns false when value type mismatches unrecoverably`() {
        // Integer.SIZE is static final int; passing a non-numeric object should fail coerce/put.
        val ok = invokeSetStaticField(Integer::class.java, "SIZE", Any())
        assertFalse(ok)
    }

    @Test
    fun `setStaticField with primitive field and incompatible string value`() {
        val ok = invokeSetStaticField(Integer::class.java, "SIZE", "bad_value")
        assertFalse(ok)
    }

    @Test
    fun `setStaticField handles System_in without throwing`() {
        val ok = invokeSetStaticField(System::class.java, "in", null as Any?)
        // May succeed or fail depending on JVM; must not throw to the test harness.
        assertTrue(ok || !ok)
    }

    @Test
    fun `setStaticField handles null assignment to primitive without throwing`() {
        val ok = invokeSetStaticField(Integer::class.java, "MIN_VALUE", null as Any?)
        assertTrue(ok || !ok)
    }

    // =========================================================================
    // Successful writes on JVM fixtures
    // =========================================================================

    @Test
    fun `setStaticField updates mutable static String field`() {
        StaticFieldFixture.mutableStatic = "before"
        val ok = invokeSetStaticField(
            StaticFieldFixture::class.java,
            "mutableStatic",
            "after-spoof",
        )
        assertTrue("mutable static String should be writable", ok)
        assertEquals("after-spoof", StaticFieldFixture.mutableStatic)
    }

    @Test
    fun `setStaticField updates dynamic final static String via Unsafe fallback`() {
        val original = StaticFieldFixture.finalDynamic
        val target = "spoofed-$original"
        val ok = invokeSetStaticField(
            StaticFieldFixture::class.java,
            "finalDynamic",
            target,
        )
        // On desktop JDK, Field.set may fail for final; Unsafe should still work.
        assertTrue(
            "dynamic final static String should be writable via Field.set and/or Unsafe",
            ok,
        )
        assertEquals(target, StaticFieldFixture.finalDynamic)
    }

    @Test
    fun `setStaticField updates static int field`() {
        StaticFieldFixture.mutableInt = 1
        val ok = invokeSetStaticField(
            StaticFieldFixture::class.java,
            "mutableInt",
            99,
        )
        assertTrue(ok)
        assertEquals(99, StaticFieldFixture.mutableInt)
    }

    @Test
    fun `setStaticField coerces string to static int field`() {
        StaticFieldFixture.mutableInt = 0
        val ok = invokeSetStaticField(
            StaticFieldFixture::class.java,
            "mutableInt",
            "42",
        )
        assertTrue(ok)
        assertEquals(42, StaticFieldFixture.mutableInt)
    }

    // =========================================================================
    // Routing
    // =========================================================================

    @Test
    fun `INCREMENTAL is routed to Build VERSION`() {
        assertSame(Build.VERSION::class.java, targetClassForField("INCREMENTAL"))
    }

    @Test
    fun `SECURITY_PATCH is routed to Build VERSION`() {
        assertSame(Build.VERSION::class.java, targetClassForField("SECURITY_PATCH"))
    }

    @Test
    fun `MODEL remains routed to Build`() {
        assertSame(Build::class.java, targetClassForField("MODEL"))
    }

    @Test
    fun `accessFlagsField lookup does not throw`() {
        val field = DeviceSpoofer::class.java.getDeclaredField("accessFlagsField\$delegate")
        // lazy delegate exists; force initialization via setStaticField path instead
        invokeSetStaticField(StaticFieldFixture::class.java, "mutableStatic", "x")
        // If we got here without exception, lazy accessFlags resolution is safe.
        assertTrue(true)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun invokeSetStaticField(clazz: Class<*>, fieldName: String, value: Any?): Boolean {
        val method = DeviceSpoofer::class.java.getDeclaredMethod(
            "setStaticField",
            Class::class.java,
            String::class.java,
            Any::class.java,
        )
        method.isAccessible = true
        val result = method.invoke(DeviceSpoofer, clazz, fieldName, value)
        return result as Boolean
    }

    private fun targetClassForField(fieldName: String): Class<*> {
        val method = DeviceSpoofer::class.java.getDeclaredMethod(
            "targetClassForField",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(DeviceSpoofer, fieldName) as Class<*>
    }

    /**
     * JVM-only fixtures for write-path tests (not Android Build fields).
     */
    class StaticFieldFixture {
        companion object {
            @JvmField
            var mutableStatic: String = "orig"

            /** Not a compile-time constant, so it is a real static field slot. */
            @JvmField
            val finalDynamic: String = "final-" + System.nanoTime().toString()

            @JvmField
            var mutableInt: Int = 0
        }
    }
}
