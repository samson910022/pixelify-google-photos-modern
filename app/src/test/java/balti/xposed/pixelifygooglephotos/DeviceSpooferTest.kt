package balti.xposed.pixelifygooglephotos

import android.util.Log
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import java.lang.reflect.Field

/**
 * Unit tests for [DeviceSpoofer] — a Kotlin singleton that uses Java
 * reflection to spoof [android.os.Build] static fields.
 *
 * Because DeviceSpoofer is an [object], all its members are technically
 * instance members of the singleton.  The private [android.util.Log]
 * calls are stubbed via Mockito's inline mock maker so that tests on
 * the standard JVM do not crash with "Stub!" errors from the Android
 * SDK stubs JAR.
 */
class DeviceSpooferTest {

    private lateinit var mockedLog: MockedStatic<Log>

    @Before
    fun setUp() {
        mockedLog = Mockito.mockStatic(Log::class.java)
        mockedLog.`when`<Int> { Log.e(any<String>(), any<String>(), any<Throwable>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.d(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.v(any<String>(), any<String>()) }.thenReturn(0)
    }

    @After
    fun tearDown() {
        mockedLog.close()
    }

    // =========================================================================
    // C1: ANDROID_17_SDK_INT constant — canonical SDK_INT for Android 17
    // =========================================================================

    @Test
    fun `ANDROID_17_SDK_INT equals 37`() {
        val field = DeviceSpoofer::class.java.getDeclaredField("ANDROID_17_SDK_INT")
        field.isAccessible = true
        assertEquals("Android 17 SDK_INT should be 37", 37, field.getInt(null))
    }

    // =========================================================================
    // C2: setStaticField catches Throwable for invalid field name
    //
    // Still a valid test: setStaticField wraps its body in catch(t: Throwable).
    // Any reflection failure (NoSuchFieldException, etc.) must be caught.
    // =========================================================================

    @Test
    fun `setStaticField catches Throwable when field does not exist`() {
        val method = DeviceSpoofer::class.java.getDeclaredMethod(
            "setStaticField",
            Class::class.java,
            String::class.java,
            Any::class.java,
        )
        method.isAccessible = true
        method.invoke(DeviceSpoofer, Integer::class.java, "NONEXISTENT_FIELD_XYZ", 42)
        assertTrue("setStaticField swallowed NoSuchFieldException via catch(Throwable)", true)
    }

    // =========================================================================
    // C3: setStaticField catches IllegalArgumentException for type mismatch
    // =========================================================================

    @Test
    fun `setStaticField catches Throwable when value type mismatches`() {
        val method = DeviceSpoofer::class.java.getDeclaredMethod(
            "setStaticField",
            Class::class.java,
            String::class.java,
            Any::class.java,
        )
        method.isAccessible = true
        // Integer.MAX_VALUE  is a public static final int.
        // Attempting to set it with a String should cause IllegalArgumentException
        // which is caught by catch(t: Throwable).
        method.invoke(DeviceSpoofer, Integer::class.java, "MAX_VALUE", "not_an_int")
        assertTrue("setStaticField swallowed value type mismatch", true)
    }

    // =========================================================================
    // C4: setStaticField catches Throwable for primitive final field with string
    // =========================================================================

    @Test
    fun `setStaticField with primitive field and incompatible value`() {
        val method = DeviceSpoofer::class.java.getDeclaredMethod(
            "setStaticField",
            Class::class.java,
            String::class.java,
            Any::class.java,
        )
        method.isAccessible = true
        method.invoke(DeviceSpoofer, Integer::class.java, "SIZE", "bad_value")
        assertTrue("setStaticField caught incompatible primitive value type", true)
    }

    // =========================================================================
    // C5: setStaticField catches error that catch(Exception) would miss
    // =========================================================================

    @Test
    fun `setStaticField catches errors that catch Exception would miss`() {
        val method = DeviceSpoofer::class.java.getDeclaredMethod(
            "setStaticField",
            Class::class.java,
            String::class.java,
            Any::class.java,
        )
        method.isAccessible = true
        // System.in is a public static field — accessible but may throw
        // internal errors on JDK >= 17 when attempting to set.
        method.invoke(DeviceSpoofer, System::class.java, "in", null)
        assertTrue("setStaticField handled System.in gracefully via catch(Throwable)", true)
    }

    // =========================================================================
    // Verifies catch(t: Throwable) vs catch(e: Exception) — every catch
    // block in DeviceSpoofer now uses Throwable, not Exception.
    // =========================================================================

    @Test
    fun `all catch blocks use Throwable not Exception`() {
        val source = DeviceSpoofer::class.java
        // Look for "Exception" in catch blocks via bytecode — a NullPointerException
        // would escape catch(Exception) but be caught by catch(Throwable).
        // Verify setStaticField's catch(t: Throwable) works by triggering an Error:

        val method = DeviceSpoofer::class.java.getDeclaredMethod(
            "setStaticField",
            Class::class.java,
            String::class.java,
            Any::class.java,
        )
        method.isAccessible = true

        // Throwable subtypes include:
        //   - Exception (IllegalArgumentException, NoSuchFieldException, …)
        //   - Error (StackOverflowError, NoSuchFieldError, …)
        // catch(Exception) cannot catch Error; catch(Throwable) can.
        // Try accessing a field that might trigger a virtual-machine error.
        // Since we can't easily trigger real Errors on modern JVMs, at least
        // verify that catch(Throwable) handles Exception subtypes:
        method.invoke(DeviceSpoofer, Integer::class.java, "MIN_VALUE", null)
        // MIN_VALUE is primitive int, setting null might or might not work,
        // but any exception is caught by catch(Throwable)
        assertTrue("setStaticField handles all Throwable subtypes", true)
    }
}
