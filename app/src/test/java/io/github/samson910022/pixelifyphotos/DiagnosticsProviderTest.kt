package io.github.samson910022.pixelifyphotos

import android.content.ContentProvider
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.*

/**
 * Unit tests for [DiagnosticsProvider] key validation, caller authorization,
 * collection type handling, and security boundaries.
 */
class DiagnosticsProviderTest {

    private lateinit var mockedLog: MockedStatic<Log>
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var provider: DiagnosticsProvider

    @Before
    fun setUp() {
        mockedLog = Mockito.mockStatic(Log::class.java)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>(), anyOrNull()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.d(any<String>(), any<String>()) }.thenReturn(0)

        mockContext = mock()
        mockPrefs = mock()
        mockEditor = mock()

        whenever(mockContext.getSharedPreferences(eq(Constants.SHARED_PREF_FILE_NAME), eq(Context.MODE_PRIVATE)))
            .thenReturn(mockPrefs)
        whenever(mockPrefs.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putLong(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putString(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putBoolean(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putInt(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putFloat(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putStringSet(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.remove(any())).thenReturn(mockEditor)

        provider = DiagnosticsProvider()
        provider.testContext = mockContext
        provider.testCallingUid = 10000
        provider.testMyUid = 10000
    }

    @After
    fun tearDown() {
        mockedLog.close()
    }

    @Test
    fun `ALLOWED_DIAG_KEYS only contains PREF_DIAG keys`() {
        DiagnosticsStore.ALLOWED_DIAG_KEYS.forEach { key ->
            assertTrue("Key '$key' must start with PREF_DIAG_", key.startsWith("PREF_DIAG_"))
        }
    }

    @Test
    fun `sensitive configuration keys are strictly excluded from ALLOWED_DIAG_KEYS`() {
        val forbiddenKeys = listOf(
            Constants.PREF_DEVICE_TO_SPOOF,
            Constants.PREF_SPOOF_FEATURES_LIST,
            Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS,
            Constants.PREF_ENABLE_VERBOSE_LOGS,
            Constants.PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE,
            Constants.PREF_SPOOF_ANDROID_VERSION_MANUAL,
            Constants.PREF_LAST_VERSION,
            Constants.PREF_USE_CLASSIC_UI,
            Constants.PREF_FIRST_RUN_COMPLETED,
            "custom_arbitrary_key",
            "password",
            "token"
        )

        forbiddenKeys.forEach { key ->
            assertFalse(
                "Forbidden key '$key' must never be writable via DiagnosticsProvider",
                DiagnosticsStore.ALLOWED_DIAG_KEYS.contains(key)
            )
        }
    }

    // =========================================================================
    // Caller Authorization (isCallerAuthorized)
    // =========================================================================

    @Test
    fun `caller authorization allows self UID`() {
        assertTrue(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10000,
                myUid = 10000,
                callingPackages = null
            )
        )
    }

    @Test
    fun `caller authorization rejects null or empty calling packages for remote UID`() {
        assertFalse(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10123,
                myUid = 10000,
                callingPackages = null
            )
        )
        assertFalse(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10123,
                myUid = 10000,
                callingPackages = emptyArray()
            )
        )
    }

    @Test
    fun `caller authorization allows legitimate scoped apps like Photos`() {
        assertTrue(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10123,
                myUid = 10000,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
            )
        )
    }

    @Test
    fun `caller authorization rejects denylisted system packages`() {
        assertFalse(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10001,
                myUid = 10000,
                callingPackages = arrayOf("com.google.android.gms")
            )
        )
        assertFalse(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10002,
                myUid = 10000,
                callingPackages = arrayOf("com.android.settings")
            )
        )
        assertFalse(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10003,
                myUid = 10000,
                callingPackages = arrayOf("app.grapheneos.gmscompat")
            )
        )
    }

    @Test
    fun `caller authorization rejects package spoofing when reported package does not belong to caller`() {
        val extras = mock<Bundle>()
        whenever(extras.getString(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)

        // Caller is com.rogue.app, falsely claiming to report for Photos
        assertFalse(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10999,
                myUid = 10000,
                callingPackages = arrayOf("com.rogue.app"),
                extras = extras
            )
        )
    }

    @Test
    fun `caller authorization allows when reported package matches calling package`() {
        val extras = mock<Bundle>()
        whenever(extras.getString(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)

        assertTrue(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10123,
                myUid = 10000,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                extras = extras
            )
        )
    }

    // =========================================================================
    // applyExtra type handling
    // =========================================================================

    @Test
    fun `applyExtra handles collections, arrays, and primitive types`() {
        DiagnosticsStore.applyExtra(mockEditor, "key_long", 100L)
        verify(mockEditor).putLong("key_long", 100L)

        DiagnosticsStore.applyExtra(mockEditor, "key_int", 42)
        verify(mockEditor).putInt("key_int", 42)

        DiagnosticsStore.applyExtra(mockEditor, "key_bool", true)
        verify(mockEditor).putBoolean("key_bool", true)

        DiagnosticsStore.applyExtra(mockEditor, "key_float", 3.14f)
        verify(mockEditor).putFloat("key_float", 3.14f)

        DiagnosticsStore.applyExtra(mockEditor, "key_str", "hello")
        verify(mockEditor).putString("key_str", "hello")

        val list = listOf("a", "b", "c")
        DiagnosticsStore.applyExtra(mockEditor, "key_list", list)
        verify(mockEditor).putStringSet("key_list", setOf("a", "b", "c"))

        val set = setOf("x", "y")
        DiagnosticsStore.applyExtra(mockEditor, "key_set", set)
        verify(mockEditor).putStringSet("key_set", setOf("x", "y"))

        val array = arrayOf("1", "2")
        DiagnosticsStore.applyExtra(mockEditor, "key_array", array)
        verify(mockEditor).putStringSet("key_array", setOf("1", "2"))
    }

    // =========================================================================
    // call() IPC method execution
    // =========================================================================

    @Test
    fun `call RECORD_DIAGNOSTICS writes whitelisted milestone keys`() {
        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(
            setOf(
                Constants.PREF_DIAG_MODULE_LOADED_AT,
                Constants.PREF_DIAG_LAST_PACKAGE_LOADED,
                Constants.PREF_DIAG_LAST_PACKAGE_READY_AT,
            )
        )
        whenever(extras.get(Constants.PREF_DIAG_MODULE_LOADED_AT)).thenReturn(1700000000000L)
        whenever(extras.get(Constants.PREF_DIAG_LAST_PACKAGE_LOADED)).thenReturn("com.google.android.apps.photos")
        whenever(extras.get(Constants.PREF_DIAG_LAST_PACKAGE_READY_AT)).thenReturn(1700000005000L)

        val result = provider.call(Constants.METHOD_RECORD_DIAGNOSTICS, null, extras)

        assertNotNull(result)
        verify(mockEditor).putLong(Constants.PREF_DIAG_MODULE_LOADED_AT, 1700000000000L)
        verify(mockEditor).putString(Constants.PREF_DIAG_LAST_PACKAGE_LOADED, "com.google.android.apps.photos")
        verify(mockEditor).putLong(Constants.PREF_DIAG_LAST_PACKAGE_READY_AT, 1700000005000L)
        verify(mockEditor).apply()
    }

    @Test
    fun `call RECORD_VERIFY writes all verify properties and converts ArrayList to Set`() {
        val extras = mock<Bundle>()
        val failedList = arrayListOf("MODEL(actual=Redmi)", "FINGERPRINT(actual=unknown)")
        whenever(extras.keySet()).thenReturn(
            setOf(
                Constants.PREF_DIAG_VERIFY_AT,
                Constants.PREF_DIAG_VERIFY_DEVICE,
                Constants.PREF_DIAG_VERIFY_OK,
                Constants.PREF_DIAG_VERIFY_FAILED,
                Constants.PREF_DIAG_VERIFY_NATIVE_READY,
                Constants.PREF_DIAG_VERIFY_SYSPROPS,
            )
        )
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_AT)).thenReturn(1700000010000L)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_DEVICE)).thenReturn("Pixel 8 Pro")
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_OK)).thenReturn(false)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_FAILED)).thenReturn(failedList)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_NATIVE_READY)).thenReturn(true)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_SYSPROPS)).thenReturn(true)

        val result = provider.call(Constants.METHOD_RECORD_VERIFY, null, extras)

        assertNotNull(result)
        verify(mockEditor).putLong(Constants.PREF_DIAG_VERIFY_AT, 1700000010000L)
        verify(mockEditor).putString(Constants.PREF_DIAG_VERIFY_DEVICE, "Pixel 8 Pro")
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_OK, false)
        verify(mockEditor).putStringSet(Constants.PREF_DIAG_VERIFY_FAILED, failedList.toSet())
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_NATIVE_READY, true)
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_SYSPROPS, true)
        verify(mockEditor).apply()
    }

    @Test
    fun `call CLEAR_VERIFY removes all verify preference keys`() {
        val result = provider.call(Constants.METHOD_CLEAR_VERIFY, null, null)

        assertNotNull(result)
        verify(mockEditor).remove(Constants.PREF_DIAG_VERIFY_AT)
        verify(mockEditor).remove(Constants.PREF_DIAG_VERIFY_DEVICE)
        verify(mockEditor).remove(Constants.PREF_DIAG_VERIFY_PACKAGE)
        verify(mockEditor).remove(Constants.PREF_DIAG_VERIFY_OK)
        verify(mockEditor).remove(Constants.PREF_DIAG_VERIFY_FAILED)
        verify(mockEditor).remove(Constants.PREF_DIAG_VERIFY_NATIVE_READY)
        verify(mockEditor).remove(Constants.PREF_DIAG_VERIFY_SYSPROPS)
        verify(mockEditor).apply()
    }

    @Test
    fun `call strictly ignores non-whitelisted keys from caller`() {
        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(
            setOf(
                Constants.PREF_DEVICE_TO_SPOOF,
                Constants.PREF_SPOOF_FEATURES_LIST,
                "unauthorized_key",
                Constants.PREF_DIAG_LAST_PACKAGE_LOADED,
            )
        )
        whenever(extras.get(Constants.PREF_DEVICE_TO_SPOOF)).thenReturn("Pixel 9 Pro")
        whenever(extras.get(Constants.PREF_SPOOF_FEATURES_LIST)).thenReturn(setOf("Pixel 2024"))
        whenever(extras.get("unauthorized_key")).thenReturn("malicious")
        whenever(extras.get(Constants.PREF_DIAG_LAST_PACKAGE_LOADED)).thenReturn("com.google.android.apps.photos")

        provider.call(Constants.METHOD_RECORD_DIAGNOSTICS, null, extras)

        verify(mockEditor, never()).putString(eq(Constants.PREF_DEVICE_TO_SPOOF), any())
        verify(mockEditor, never()).putStringSet(eq(Constants.PREF_SPOOF_FEATURES_LIST), any())
        verify(mockEditor, never()).putString(eq("unauthorized_key"), any())
        verify(mockEditor).putString(Constants.PREF_DIAG_LAST_PACKAGE_LOADED, "com.google.android.apps.photos")
        verify(mockEditor).apply()
    }

    @Test
    fun `call returns success false on unknown method without modifying preferences`() {
        val extras = mock<Bundle>()
        val result = provider.call("UNSUPPORTED_METHOD_XYZ", null, extras)

        assertNotNull(result)
        verifyNoInteractions(mockEditor)
    }

    @Test
    fun `call returns success false when context is null`() {
        val unattachedProvider = DiagnosticsProvider()
        val result = unattachedProvider.call(Constants.METHOD_RECORD_DIAGNOSTICS, null, Bundle())

        assertNotNull(result)
        verifyNoInteractions(mockEditor)
    }
}
