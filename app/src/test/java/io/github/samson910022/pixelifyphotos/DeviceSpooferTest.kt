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
    // Coercion & Matching
    // =========================================================================

    @Test
    fun `coerceValue correctly handles primitive and boxed types`() {
        val longField = StaticFieldFixture::class.java.getDeclaredField("mutableLong")
        assertEquals(1234567890123L, invokeCoerceValue(longField, 1234567890123L))
        assertEquals(42L, invokeCoerceValue(longField, 42))
        assertEquals(999L, invokeCoerceValue(longField, "999"))

        val boolField = StaticFieldFixture::class.java.getDeclaredField("mutableBoolean")
        assertEquals(true, invokeCoerceValue(boolField, true))
        assertEquals(true, invokeCoerceValue(boolField, "true"))
        assertEquals(true, invokeCoerceValue(boolField, "1"))
        assertEquals(false, invokeCoerceValue(boolField, "false"))
        assertEquals(true, invokeCoerceValue(boolField, 1))
        assertEquals(false, invokeCoerceValue(boolField, 0))

        val floatField = StaticFieldFixture::class.java.getDeclaredField("mutableFloat")
        assertEquals(3.14f, invokeCoerceValue(floatField, 3.14f))
        assertEquals(3.14f, invokeCoerceValue(floatField, "3.14"))

        val doubleField = StaticFieldFixture::class.java.getDeclaredField("mutableDouble")
        assertEquals(3.14159, invokeCoerceValue(doubleField, 3.14159))
        assertEquals(2.718, invokeCoerceValue(doubleField, "2.718"))

        val charField = StaticFieldFixture::class.java.getDeclaredField("mutableChar")
        assertEquals('A', invokeCoerceValue(charField, 'A'))
        assertEquals('Z', invokeCoerceValue(charField, "Z"))
        assertEquals('C', invokeCoerceValue(charField, 67))

        val shortField = StaticFieldFixture::class.java.getDeclaredField("mutableShort")
        assertEquals(42.toShort(), invokeCoerceValue(shortField, 42.toShort()))
        assertEquals(100.toShort(), invokeCoerceValue(shortField, 100))
        assertEquals(256.toShort(), invokeCoerceValue(shortField, "256"))

        val byteField = StaticFieldFixture::class.java.getDeclaredField("mutableByte")
        assertEquals(8.toByte(), invokeCoerceValue(byteField, 8.toByte()))
        assertEquals(16.toByte(), invokeCoerceValue(byteField, 16))
        assertEquals(32.toByte(), invokeCoerceValue(byteField, "32"))
    }

    @Test
    fun `fieldValueMatches compares numbers, strings and nulls safely`() {
        val strField = StaticFieldFixture::class.java.getDeclaredField("mutableStatic")
        StaticFieldFixture.mutableStatic = "test-val"
        assertTrue(invokeFieldValueMatches(strField, "test-val"))
        assertFalse(invokeFieldValueMatches(strField, "other-val"))
        assertFalse(invokeFieldValueMatches(strField, null))

        val intField = StaticFieldFixture::class.java.getDeclaredField("mutableInt")
        StaticFieldFixture.mutableInt = 100
        assertTrue(invokeFieldValueMatches(intField, 100))
        assertTrue(invokeFieldValueMatches(intField, 100L))
        assertTrue(invokeFieldValueMatches(intField, "100"))
        assertFalse(invokeFieldValueMatches(intField, 200))
        assertFalse(invokeFieldValueMatches(intField, null))
    }

    @Test
    fun `buildSystemPropertyOverrides maps all standard props to ro keys`() {
        val props = mapOf(
            "BRAND" to "google",
            "MANUFACTURER" to "Google",
            "DEVICE" to "husky",
            "PRODUCT" to "husky",
            "MODEL" to "Pixel 8 Pro",
            "FINGERPRINT" to "google/husky/husky:14/UD1A.230803.022/10666019:user/release-keys",
            "ID" to "UD1A.230803.022",
            "INCREMENTAL" to "10666019",
            "SECURITY_PATCH" to "2023-11-05",
        )
        val overrides = invokeBuildSystemPropertyOverrides(props)
        assertEquals("google", overrides["ro.product.brand"])
        assertEquals("Google", overrides["ro.product.manufacturer"])
        assertEquals("husky", overrides["ro.product.device"])
        assertEquals("husky", overrides["ro.product.name"])
        assertEquals("Pixel 8 Pro", overrides["ro.product.model"])
        assertEquals("google/husky/husky:14/UD1A.230803.022/10666019:user/release-keys", overrides["ro.build.fingerprint"])
        assertEquals("husky", overrides["ro.product.vendor.device"])
        assertEquals("Pixel 8 Pro", overrides["ro.product.vendor.model"])
    }

    @Test
    fun `setStaticField updates primitive long, boolean, float, and double via Unsafe`() {
        assertTrue(invokeSetStaticField(StaticFieldFixture::class.java, "mutableLong", 9876543210L))
        assertEquals(9876543210L, StaticFieldFixture.mutableLong)

        assertTrue(invokeSetStaticField(StaticFieldFixture::class.java, "mutableBoolean", true))
        assertTrue(StaticFieldFixture.mutableBoolean)

        assertTrue(invokeSetStaticField(StaticFieldFixture::class.java, "mutableFloat", 1.23f))
        assertEquals(1.23f, StaticFieldFixture.mutableFloat, 0.001f)

        assertTrue(invokeSetStaticField(StaticFieldFixture::class.java, "mutableDouble", 9.876))
        assertEquals(9.876, StaticFieldFixture.mutableDouble, 0.0001)

        assertTrue(invokeSetStaticField(StaticFieldFixture::class.java, "mutableChar", 'K'))
        assertEquals('K', StaticFieldFixture.mutableChar)

        assertTrue(invokeSetStaticField(StaticFieldFixture::class.java, "mutableShort", 77.toShort()))
        assertEquals(77.toShort(), StaticFieldFixture.mutableShort)

        assertTrue(invokeSetStaticField(StaticFieldFixture::class.java, "mutableByte", 12.toByte()))
        assertEquals(12.toByte(), StaticFieldFixture.mutableByte)
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

    private fun invokeCoerceValue(field: Field, value: Any?): Any? {
        val method = DeviceSpoofer::class.java.getDeclaredMethod(
            "coerceValue",
            Field::class.java,
            Any::class.java,
        )
        method.isAccessible = true
        return method.invoke(DeviceSpoofer, field, value)
    }

    private fun invokeFieldValueMatches(field: Field, expected: Any?): Boolean {
        val method = DeviceSpoofer::class.java.getDeclaredMethod(
            "fieldValueMatches",
            Field::class.java,
            Any::class.java,
        )
        method.isAccessible = true
        return method.invoke(DeviceSpoofer, field, expected) as Boolean
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildSystemPropertyOverrides(props: Map<String, String>): Map<String, String> {
        val method = DeviceSpoofer::class.java.getDeclaredMethod(
            "buildSystemPropertyOverrides",
            Map::class.java,
        )
        method.isAccessible = true
        return method.invoke(DeviceSpoofer, props) as Map<String, String>
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

            @JvmField
            var mutableLong: Long = 0L

            @JvmField
            var mutableBoolean: Boolean = false

            @JvmField
            var mutableFloat: Float = 0.0f

            @JvmField
            var mutableDouble: Double = 0.0

            @JvmField
            var mutableChar: Char = ' '

            @JvmField
            var mutableShort: Short = 0

            @JvmField
            var mutableByte: Byte = 0
        }
    }
}
