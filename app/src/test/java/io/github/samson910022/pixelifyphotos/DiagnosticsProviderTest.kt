package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE,
            bootEpochHash = "test-epoch-1",
        )
        mockedLog = Mockito.mockStatic(Log::class.java)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>(), anyOrNull()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.d(any<String>(), any<String>()) }.thenReturn(0)

        // Hermeticity: never inherit a bound service left by another suite in this JVM,
        // otherwise PrefUtils would route to remote prefs and bypass the file-store mocks.
        App.setServiceStateForTests(null, ready = false)

        mockContext = mock()
        mockPrefs = mock()
        mockEditor = mock()

        whenever(mockContext.getSharedPreferences(eq(Constants.SHARED_PREF_FILE_NAME), eq(Context.MODE_PRIVATE)))
            .thenReturn(mockPrefs)
        DiagnosticsStore.testRemotePreferencesResolver = { mockPrefs }
        whenever(mockPrefs.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putLong(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putString(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putBoolean(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putInt(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putFloat(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putStringSet(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.remove(any())).thenReturn(mockEditor)

        provider = observingProvider()
        provider.testContext = mockContext
        provider.testCallingUid = 10000
        provider.testMyUid = 10000
    }

    @After
    fun tearDown() {
        try {
            restoreStoreInstance()
            DiagnosticsStore.testRunClock = null
            DiagnosticsStore.testRemotePreferencesResolver = null
            provider.testContext = null
            provider.testCallingUid = null
            provider.testMyUid = null
            provider.testCallingPid = null
        } finally {
            mockedLog.close()
        }
    }

    /** Active singleton swap; closed (restored) in [tearDown], close-once semantics. */
    private var storeSwap: TestStatics.SwapHandle? = null

    /**
     * Swaps the singleton backing field of the Kotlin [DiagnosticsStore] object with a
     * mock so provider→store delegation can be verified argument-by-argument. The
     * original instance is restored via the handle in [tearDown].
     */
    private fun swapStoreInstanceWithMock(): DiagnosticsStore {
        val storeMock: DiagnosticsStore = mock()
        storeSwap = TestStatics.swapObjectInstance(DiagnosticsStore::class.java, "INSTANCE", storeMock)
        return storeMock
    }

    private fun restoreStoreInstance() {
        storeSwap?.close()
        storeSwap = null
    }

    /**
     * Provider whose [DiagnosticsProvider.createResultBundle] yields a Mockito mock.
     * Under JVM unit tests android.jar's Bundle is a stub (putBoolean is a no-op and
     * getBoolean always returns false), so result-bundle flags are verified against
     * this mock. The provider returns exactly the bundle it created, so tests can
     * verify directly on the [DiagnosticsProvider.call] return value.
     */
    private fun observingProvider(): DiagnosticsProvider =
        object : DiagnosticsProvider() {
            override fun createResultBundle(): Bundle = mock()
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
    fun `call RECORD_DIAGNOSTICS writes whitelisted milestone keys and reports success`() {
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

        val result = provider.call(Constants.METHOD_RECORD_DIAGNOSTICS, null, extras)!!

        verify(result).putBoolean("success", true)
        verify(mockEditor).putLong(Constants.PREF_DIAG_MODULE_LOADED_AT, 1700000000000L)
        verify(mockEditor).putString(Constants.PREF_DIAG_LAST_PACKAGE_LOADED, "com.google.android.apps.photos")
        verify(mockEditor).putLong(Constants.PREF_DIAG_LAST_PACKAGE_READY_AT, 1700000005000L)
        verify(mockEditor).apply()
    }

    @Test
    fun `call RECORD_VERIFY writes all verify properties and converts ArrayList to Set`() {
        val extras = mock<Bundle>()
        val failedList = arrayListOf("MODEL", "FINGERPRINT")
        val startedAt = 1_700_000_000_000L
        val generation = 123L
        val origin = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_ACTIVE_PACKAGE), anyOrNull())).thenReturn(origin)
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_ACTIVE_RUN_ID), anyOrNull())).thenReturn("run-1")
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH), anyOrNull())).thenReturn("test-epoch-1")
        whenever(mockPrefs.getLong(eq(Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT), any())).thenReturn(startedAt)
        whenever(mockPrefs.getLong(eq(Constants.PREF_DIAG_ACTIVE_RUN_GENERATION), any())).thenReturn(generation)
        whenever(extras.keySet()).thenReturn(
            setOf(
                Constants.PREF_DIAG_VERIFY_AT,
                Constants.PREF_DIAG_VERIFY_DEVICE,
                Constants.PREF_DIAG_VERIFY_PACKAGE,
                Constants.PREF_DIAG_VERIFY_OK,
                Constants.PREF_DIAG_VERIFY_FAILED,
                Constants.PREF_DIAG_VERIFY_NATIVE_READY,
                Constants.PREF_DIAG_VERIFY_SYSPROPS,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID,
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH,
            )
        )
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_AT)).thenReturn(1700000010000L)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_DEVICE)).thenReturn("Pixel 8 Pro")
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn(origin)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_OK)).thenReturn(false)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_FAILED)).thenReturn(failedList)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_NATIVE_READY)).thenReturn(true)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_SYSPROPS)).thenReturn(true)
        whenever(extras.get(Constants.EXTRA_DIAGNOSTICS_ORIGIN)).thenReturn(origin)
        whenever(extras.get(Constants.EXTRA_DIAGNOSTICS_RUN_ID)).thenReturn("run-1")
        whenever(extras.get(Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT)).thenReturn(startedAt)
        whenever(extras.get(Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION)).thenReturn(generation)
        whenever(extras.get(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH)).thenReturn("test-epoch-1")

        val result = provider.call(Constants.METHOD_RECORD_VERIFY, null, extras)!!

        verify(result).putBoolean("success", true)
        verify(mockEditor).putLong(Constants.PREF_DIAG_VERIFY_AT, 1700000010000L)
        verify(mockEditor).putString(Constants.PREF_DIAG_VERIFY_DEVICE, "Pixel 8 Pro")
        verify(mockEditor).putString(Constants.PREF_DIAG_VERIFY_PACKAGE, origin)
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_OK, false)
        verify(mockEditor).putStringSet(Constants.PREF_DIAG_VERIFY_FAILED, failedList.toSet())
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_NATIVE_READY, true)
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_SYSPROPS, true)
        verify(mockEditor).apply()
    }

    @Test
    fun `call CLEAR_VERIFY removes all verify preference keys`() {
        val result = provider.call(Constants.METHOD_CLEAR_VERIFY, null, null)!!

        verify(result).putBoolean("success", true)
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
    fun `call rejects unknown keys before writing any payload`() {
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

        val result = provider.call(Constants.METHOD_RECORD_DIAGNOSTICS, null, extras)!!

        verify(result).putBoolean("success", false)
        verifyNoInteractions(mockEditor)
    }

    @Test
    fun `call returns success false on unknown method without modifying preferences`() {
        val result = provider.call("UNSUPPORTED_METHOD_XYZ", null, mock<Bundle>())!!

        verify(result).putBoolean("success", false)
        verifyNoInteractions(mockEditor)
    }

    @Test
    fun `call returns success false when context is null`() {
        val unattachedProvider = observingProvider()

        val result = unattachedProvider.call(Constants.METHOD_RECORD_DIAGNOSTICS, null, null)!!

        verify(result).putBoolean("success", false)
        verifyNoInteractions(mockEditor)
    }

    @Test
    fun `call rejects malformed extras without authenticating or crashing`() {
        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(setOf(Constants.EXTRA_DIAGNOSTICS_ORIGIN))
        whenever(extras.get(Constants.EXTRA_DIAGNOSTICS_ORIGIN))
            .thenThrow(IllegalStateException("malformed parcel"))

        val result = provider.call(Constants.METHOD_RECORD_DIAGNOSTICS, null, extras)!!

        verify(result).putBoolean("success", false)
        verifyNoInteractions(mockEditor)
    }

    @Test
    fun `call returns exactly the bundle produced by createResultBundle`() {
        val marker = mock<Bundle>()
        val providerWithMarker = object : DiagnosticsProvider() {
            override fun createResultBundle(): Bundle = marker
        }
        providerWithMarker.testContext = mockContext

        assertSame(marker, providerWithMarker.call(Constants.METHOD_CLEAR_VERIFY, null, null))
    }

    @Test
    fun `unattached call returns the seam bundle on the early-return path`() {
        val marker = mock<Bundle>()
        val providerWithMarker = object : DiagnosticsProvider() {
            override fun createResultBundle(): Bundle = marker
        }

        assertSame(marker, providerWithMarker.call(Constants.METHOD_RECORD_DIAGNOSTICS, null, null))
        verify(marker).putBoolean("success", false)
    }

    // =========================================================================
    // call() → DiagnosticsStore.applyDiagnostics delegation wiring (new signature)
    // =========================================================================

    @Test
    fun `call delegates to applyDiagnostics with resolved context method extras and uid arguments`() {
        val storeMock = swapStoreInstanceWithMock()
        whenever(storeMock.applyDiagnostics(
            any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any(), any(), anyOrNull(),
        ))
            .thenReturn(true)
        val packageManager = mock<PackageManager>()
        whenever(mockContext.packageManager).thenReturn(packageManager)
        whenever(packageManager.getPackagesForUid(10123))
            .thenReturn(arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS))
        provider.testCallingUid = 10123
        provider.testMyUid = 10000
        provider.testCallingPid = 23456

        val extras = mock<Bundle>()
        val result = provider.call(Constants.METHOD_RECORD_DIAGNOSTICS, null, extras)!!

        verify(result).putBoolean("success", true)
        verify(storeMock).applyDiagnostics(
            context = eq(mockContext),
            method = eq(Constants.METHOD_RECORD_DIAGNOSTICS),
            extras = same(extras),
            callingUid = eq(10123),
            callingPackages = eq(arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)),
            myUid = eq(10000),
            callingPid = eq(23456),
            verifiedBroadcastOrigin = anyOrNull(),
            requireVerifiedOrigin = eq(false),
            allowTokenOnlyUnverifiedOrigin = eq(false),
            allowPriorBootUnverifiedReplacement = eq(false),
            remoteTargetWrite = eq(true),
        )
    }

    @Test
    fun `call propagates false delegation outcome as success false`() {
        val storeMock = swapStoreInstanceWithMock()
        whenever(storeMock.applyDiagnostics(
            any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any(), any(), anyOrNull(),
        ))
            .thenReturn(false)

        val result = provider.call(Constants.METHOD_CLEAR_VERIFY, null, null)!!

        verify(result).putBoolean("success", false)
        verify(storeMock).applyDiagnostics(
            context = eq(mockContext),
            method = eq(Constants.METHOD_CLEAR_VERIFY),
            extras = isNull(),
            callingUid = eq(10000),
            callingPackages = anyOrNull(),
            myUid = eq(10000),
            callingPid = isNull(),
            verifiedBroadcastOrigin = isNull(),
            requireVerifiedOrigin = eq(false),
            allowTokenOnlyUnverifiedOrigin = eq(false),
            allowPriorBootUnverifiedReplacement = eq(false),
            remoteTargetWrite = eq(false),
        )
    }

    @Test
    fun `remote target call returns false and does not write local fallback when service is unavailable`() {
        DiagnosticsStore.testRemotePreferencesResolver = null
        App.setServiceStateForTests(null, ready = false)
        whenever(mockPrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)).thenReturn("local-token")
        val packageManager = mock<PackageManager>()
        whenever(mockContext.packageManager).thenReturn(packageManager)
        whenever(packageManager.getPackagesForUid(10123))
            .thenReturn(arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS))
        provider.testCallingUid = 10123
        provider.testMyUid = 10000

        val extras = mock<Bundle>()
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_ORIGIN))
            .thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        val result = provider.call(Constants.METHOD_START_DIAGNOSTICS_RUN, null, extras)!!

        verify(result).putBoolean("success", false)
        verify(mockEditor, never()).apply()
        verify(mockEditor, never()).remove(any())
    }

    @Test
    fun `remote target call fails closed after connected service token convergence fails`() {
        DiagnosticsStore.testRemotePreferencesResolver = null
        val syncingService = mock<io.github.libxposed.service.XposedService>()
        App.setServiceStateForTests(syncingService, ready = false, phase = ServicePhase.ERROR)
        val packageManager = mock<PackageManager>()
        whenever(mockContext.packageManager).thenReturn(packageManager)
        whenever(packageManager.getPackagesForUid(10123))
            .thenReturn(arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS))
        provider.testCallingUid = 10123
        provider.testMyUid = 10000

        val extras = mock<Bundle>()
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_ORIGIN))
            .thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        val result = provider.call(Constants.METHOD_START_DIAGNOSTICS_RUN, null, extras)!!

        verify(result).putBoolean("success", false)
        verify(syncingService, never()).getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
        verify(mockEditor, never()).apply()
        verify(mockEditor, never()).remove(any())
    }

    @Test
    fun `call does not delegate to applyDiagnostics when context is unresolvable`() {
        val storeMock = swapStoreInstanceWithMock()
        val unattachedProvider = observingProvider()

        val result = unattachedProvider.call(Constants.METHOD_RECORD_VERIFY, null, null)!!

        verify(result).putBoolean("success", false)
        verifyNoInteractions(storeMock)
    }

    // =========================================================================
    // Remote-caller UID path through the real provider→store stack
    // =========================================================================

    @Test
    fun `call rejects remote scoped-app uid without install token`() {
        val packageManager = mock<PackageManager>()
        whenever(mockContext.packageManager).thenReturn(packageManager)
        whenever(packageManager.getPackagesForUid(10123))
            .thenReturn(arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS))
        provider.testCallingUid = 10123
        provider.testMyUid = 10000

        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(
            setOf(
                Constants.EXTRA_DIAGNOSTICS_ORIGIN,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID,
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH,
                Constants.PREF_DIAG_MODULE_LOADED_AT,
                Constants.PREF_DIAG_LAST_PACKAGE_LOADED,
            )
        )
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_ORIGIN)).thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_RUN_ID)).thenReturn("provider-run")
        whenever(extras.getLong(Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT, 0L))
            .thenReturn(System.currentTimeMillis() - 1_000L)
        whenever(extras.get(Constants.PREF_DIAG_MODULE_LOADED_AT)).thenReturn(1700000000000L)
        whenever(extras.get(Constants.PREF_DIAG_LAST_PACKAGE_LOADED)).thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        whenever(extras.getString(Constants.PREF_DIAG_LAST_PACKAGE_LOADED)).thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)

        val result = provider.call(Constants.METHOD_START_DIAGNOSTICS_RUN, null, extras)!!

        verify(result).putBoolean("success", false)
        verifyNoInteractions(mockEditor)
    }

    @Test
    fun `call accepts authenticated remote scoped-app run start`() {
        val packageManager = mock<PackageManager>()
        whenever(mockContext.packageManager).thenReturn(packageManager)
        whenever(packageManager.getPackagesForUid(10123))
            .thenReturn(arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS))
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull()))
            .thenReturn("provider-token")
        provider.testCallingUid = 10123
        provider.testMyUid = 10000

        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = 456L
        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(
            setOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID,
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH,
                Constants.PREF_DIAG_MODULE_LOADED_AT,
                Constants.PREF_DIAG_LAST_PACKAGE_LOADED,
            )
        )
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("provider-token")
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_ORIGIN)).thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_RUN_ID)).thenReturn("provider-run")
        whenever(extras.getLong(Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT, 0L)).thenReturn(startedAt)
        whenever(extras.getLong(Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION, 0L)).thenReturn(generation)
        whenever(extras.getInt(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT, -1)).thenReturn(1)
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH)).thenReturn("test-epoch-1")
        whenever(extras.get(Constants.PREF_DIAG_MODULE_LOADED_AT)).thenReturn(startedAt)
        whenever(extras.get(Constants.PREF_DIAG_LAST_PACKAGE_LOADED)).thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        whenever(extras.getString(Constants.PREF_DIAG_LAST_PACKAGE_LOADED)).thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        val materialized = mapOf<String, Any>(
            Constants.EXTRA_DIAGNOSTICS_TOKEN to "provider-token",
            Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            Constants.EXTRA_DIAGNOSTICS_RUN_ID to "provider-run",
            Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
            Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
            Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
            Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH to "test-epoch-1",
            Constants.PREF_DIAG_MODULE_LOADED_AT to startedAt,
            Constants.PREF_DIAG_LAST_PACKAGE_LOADED to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
        )
        whenever(extras.get(any())).thenAnswer { materialized[it.getArgument<String>(0)] }

        val result = provider.call(Constants.METHOD_START_DIAGNOSTICS_RUN, null, extras)!!

        verify(result).putBoolean("success", true)
        verify(mockEditor).putString(Constants.PREF_DIAG_ACTIVE_PACKAGE, Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        verify(mockEditor).putString(Constants.PREF_DIAG_ACTIVE_RUN_ID, "provider-run")
        verify(mockEditor).putLong(Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT, startedAt)
        verify(mockEditor).putLong(Constants.PREF_DIAG_ACTIVE_RUN_GENERATION, 456L)
        verify(mockEditor).putInt(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT, 1)
        verify(mockEditor).putString(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH, "test-epoch-1")
        verify(mockEditor).putLong(Constants.PREF_DIAG_MODULE_LOADED_AT, startedAt)
        verify(mockEditor).apply()
    }

    @Test
    fun `shared UID provider origin returns success false for receiver fallback`() {
        val packageManager = mock<PackageManager>()
        val photos = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        whenever(mockContext.packageManager).thenReturn(packageManager)
        whenever(packageManager.getPackagesForUid(10123))
            .thenReturn(arrayOf(photos, "com.example.shared"))
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull()))
            .thenReturn("shared-provider-token")
        provider.testCallingUid = 10123
        provider.testMyUid = 10000

        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(
            setOf(Constants.EXTRA_DIAGNOSTICS_TOKEN, Constants.EXTRA_DIAGNOSTICS_ORIGIN),
        )
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("shared-provider-token")
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_ORIGIN)).thenReturn(photos)

        val result = provider.call(Constants.METHOD_START_DIAGNOSTICS_RUN, null, extras)!!

        verify(result).putBoolean("success", false)
        verify(mockEditor, never()).apply()
        verify(mockEditor, never()).putString(Constants.PREF_DIAG_ACTIVE_PACKAGE, photos)
    }

    @Test
    fun `call rejects remote uid whose packages cannot be resolved end to end`() {
        val packageManager = mock<PackageManager>()
        whenever(mockContext.packageManager).thenReturn(packageManager)
        whenever(packageManager.getPackagesForUid(10999)).thenReturn(null)
        provider.testCallingUid = 10999
        provider.testMyUid = 10000

        val result = provider.call(Constants.METHOD_RECORD_DIAGNOSTICS, null, mock<Bundle>())!!

        verify(result).putBoolean("success", false)
        verifyNoInteractions(mockEditor)
    }
}
