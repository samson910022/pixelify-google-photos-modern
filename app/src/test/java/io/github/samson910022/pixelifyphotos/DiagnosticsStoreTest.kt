package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import io.github.libxposed.service.XposedService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DiagnosticsStoreTest {

    private lateinit var mockedLog: MockedStatic<Log>
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockRemotePrefs: SharedPreferences
    private lateinit var mockRemoteEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        mockedLog = Mockito.mockStatic(Log::class.java)
        mockedLog.`when`<Int> { Log.d(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>(), anyOrNull()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.e(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.e(any<String>(), any<String>(), anyOrNull()) }.thenReturn(0)

        mockContext = mock()
        mockPrefs = mock()
        mockEditor = mock()
        mockRemotePrefs = mock()
        mockRemoteEditor = mock()

        whenever(mockContext.getSharedPreferences(eq(Constants.SHARED_PREF_FILE_NAME), eq(Context.MODE_PRIVATE)))
            .thenReturn(mockPrefs)
        whenever(mockPrefs.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putLong(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putString(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putBoolean(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putFloat(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putInt(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putStringSet(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.remove(any())).thenReturn(mockEditor)
        // Remote store editor needs fluent chaining too (putX() must return the editor).
        whenever(mockRemoteEditor.putLong(any(), any())).thenReturn(mockRemoteEditor)
        whenever(mockRemoteEditor.putString(any(), any())).thenReturn(mockRemoteEditor)
        whenever(mockRemoteEditor.putBoolean(any(), any())).thenReturn(mockRemoteEditor)
        whenever(mockRemoteEditor.putFloat(any(), any())).thenReturn(mockRemoteEditor)
        whenever(mockRemoteEditor.putInt(any(), any())).thenReturn(mockRemoteEditor)
        whenever(mockRemoteEditor.putStringSet(any(), any())).thenReturn(mockRemoteEditor)
        whenever(mockRemoteEditor.remove(any())).thenReturn(mockRemoteEditor)
    }

    @After
    fun tearDown() {
        try {
            setMockXposedService(null)
        } finally {
            mockedLog.close()
        }
    }

    /**
     * Injects a mock XposedService into the [App] companion (private setter) via
     * reflection so remote-preferences paths can be exercised in JVM unit tests.
     * The backing field is a static field on the outer App class.
     */
    private fun setMockXposedService(service: XposedService?) {
        val field = App::class.java.getDeclaredField("mService")
        field.isAccessible = true
        field.set(null, service)
    }

    private fun stubBoundServiceWithRemotePrefs(): XposedService {
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(mockRemotePrefs)
        whenever(mockRemotePrefs.edit()).thenReturn(mockRemoteEditor)
        setMockXposedService(service)
        return service
    }

    // =========================================================================
    // 1. Caller Authorization Tests
    // =========================================================================

    @Test
    fun `isCallerAuthorized allows self module UID`() {
        assertTrue(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10001,
                myUid = 10001,
                callingPackages = arrayOf("com.arbitrary.pkg"),
            )
        )
    }

    @Test
    fun `isCallerAuthorized rejects null or empty calling packages`() {
        assertFalse(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10002,
                myUid = 10001,
                callingPackages = null,
            )
        )
        assertFalse(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10002,
                myUid = 10001,
                callingPackages = emptyArray(),
            )
        )
    }

    @Test
    fun `isCallerAuthorized allows valid scoped package like Google Photos`() {
        assertTrue(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10002,
                myUid = 10001,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
            )
        )
    }

    @Test
    fun `isCallerAuthorized rejects denylisted system packages`() {
        assertFalse(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10002,
                myUid = 10001,
                callingPackages = arrayOf("com.google.android.gms"),
            )
        )
        assertFalse(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10002,
                myUid = 10001,
                callingPackages = arrayOf("com.android.settings"),
            )
        )
        assertFalse(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10002,
                myUid = 10001,
                callingPackages = arrayOf("app.grapheneos.gmscompat"),
            )
        )
    }

    @Test
    fun `isCallerAuthorized prevents package name spoofing in extras`() {
        val extras = mock<Bundle>()
        whenever(extras.getString(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)

        // Caller claims to report for Photos, but UID only owns a rogue package
        assertFalse(
            DiagnosticsStore.isCallerAuthorized(
                callingUid = 10002,
                myUid = 10001,
                callingPackages = arrayOf("com.example.rogueapp"),
                extras = extras,
            )
        )
    }

    // =========================================================================
    // 2. Key Allowlist & applyExtra Type Coercion
    // =========================================================================

    @Test
    fun `ALLOWED_DIAG_KEYS contains only diagnostic preferences`() {
        val allowed = DiagnosticsStore.ALLOWED_DIAG_KEYS
        assertTrue(allowed.contains(Constants.PREF_DIAG_MODULE_LOADED_AT))
        assertTrue(allowed.contains(Constants.PREF_DIAG_VERIFY_AT))
        assertTrue(allowed.contains(Constants.PREF_DIAG_VERIFY_DEVICE))
        assertTrue(allowed.contains(Constants.PREF_DIAG_VERIFY_PACKAGE))
        assertTrue(allowed.contains(Constants.PREF_DIAG_VERIFY_OK))
        assertTrue(allowed.contains(Constants.PREF_DIAG_VERIFY_FAILED))
        assertTrue(allowed.contains(Constants.PREF_DIAG_VERIFY_NATIVE_READY))
        assertTrue(allowed.contains(Constants.PREF_DIAG_VERIFY_SYSPROPS))

        // Sensitive preference keys must NEVER be in allowed keys
        assertFalse(allowed.contains(Constants.PREF_DEVICE_TO_SPOOF))
        assertFalse(allowed.contains(Constants.PREF_SPOOF_FEATURES_LIST))
        assertFalse(allowed.contains(Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS))
        assertFalse(allowed.contains(Constants.PREF_LAST_VERSION))
    }

    @Test
    fun `applyExtra correctly handles primitives and collection types`() {
        DiagnosticsStore.applyExtra(mockEditor, "key_long", 12345L)
        verify(mockEditor).putLong("key_long", 12345L)

        DiagnosticsStore.applyExtra(mockEditor, "key_int", 42)
        verify(mockEditor).putInt("key_int", 42)

        DiagnosticsStore.applyExtra(mockEditor, "key_bool", true)
        verify(mockEditor).putBoolean("key_bool", true)

        DiagnosticsStore.applyExtra(mockEditor, "key_float", 3.14f)
        verify(mockEditor).putFloat("key_float", 3.14f)

        DiagnosticsStore.applyExtra(mockEditor, "key_string", "test_val")
        verify(mockEditor).putString("key_string", "test_val")

        DiagnosticsStore.applyExtra(mockEditor, "key_array", arrayOf("a", "b", "c"))
        verify(mockEditor).putStringSet("key_array", setOf("a", "b", "c"))

        DiagnosticsStore.applyExtra(mockEditor, "key_list", arrayListOf("x", "y"))
        verify(mockEditor).putStringSet("key_list", setOf("x", "y"))
    }

    // =========================================================================
    // 3. applyDiagnostics Methods
    // =========================================================================

    @Test
    fun `applyDiagnostics returns false for null method`() {
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                context = mockContext,
                method = null,
                extras = mock(),
            )
        )
    }

    @Test
    fun `applyDiagnostics processes RECORD_VERIFY successfully`() {
        val extras = mock<Bundle>()
        val keys = setOf(
            Constants.PREF_DIAG_VERIFY_AT,
            Constants.PREF_DIAG_VERIFY_DEVICE,
            Constants.PREF_DIAG_VERIFY_PACKAGE,
            Constants.PREF_DIAG_VERIFY_OK,
        )
        whenever(extras.keySet()).thenReturn(keys)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_AT)).thenReturn(99999L)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_DEVICE)).thenReturn("Pixel XL")
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_OK)).thenReturn(true)
        whenever(extras.getString(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)

        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_RECORD_VERIFY,
            extras = extras,
            callingUid = 10002,
            callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
            myUid = 10001,
        )

        assertTrue(ok)
        verify(mockEditor).putLong(Constants.PREF_DIAG_VERIFY_AT, 99999L)
        verify(mockEditor).putString(Constants.PREF_DIAG_VERIFY_DEVICE, "Pixel XL")
        verify(mockEditor).putString(Constants.PREF_DIAG_VERIFY_PACKAGE, Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_OK, true)
        verify(mockEditor).apply()
    }

    @Test
    fun `applyDiagnostics processes CLEAR_VERIFY successfully`() {
        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_CLEAR_VERIFY,
            extras = mock(),
            callingUid = 10001,
            callingPackages = arrayOf(Constants.PACKAGE_NAME_MODULE),
            myUid = 10001,
        )

        assertTrue(ok)
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
    fun `applyDiagnostics filters out unauthorized keys in payload`() {
        val extras = mock<Bundle>()
        val keys = setOf(
            Constants.PREF_DIAG_VERIFY_DEVICE,
            Constants.PREF_DEVICE_TO_SPOOF, // Unauthorized key
        )
        whenever(extras.keySet()).thenReturn(keys)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_DEVICE)).thenReturn("Pixel 8 Pro")
        whenever(extras.get(Constants.PREF_DEVICE_TO_SPOOF)).thenReturn("HACKED_DEVICE")

        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_RECORD_DIAGNOSTICS,
            extras = extras,
            callingUid = 10001,
            callingPackages = arrayOf(Constants.PACKAGE_NAME_MODULE),
            myUid = 10001,
        )

        assertTrue(ok)
        verify(mockEditor).putString(Constants.PREF_DIAG_VERIFY_DEVICE, "Pixel 8 Pro")
        verify(mockEditor, never()).putString(eq(Constants.PREF_DEVICE_TO_SPOOF), any())
    }

    @Test
    fun `applyDiagnostics filters method and token plumbing keys via allowlist`() {
        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(
            setOf(
                Constants.PREF_DIAG_VERIFY_AT,
                Constants.PREF_DIAG_VERIFY_DEVICE,
                Constants.EXTRA_DIAGNOSTICS_METHOD,
                Constants.EXTRA_DIAGNOSTICS_TOKEN
            )
        )
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_AT)).thenReturn(123L)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_DEVICE)).thenReturn("Pixel XL")
        whenever(extras.get(Constants.EXTRA_DIAGNOSTICS_METHOD)).thenReturn(Constants.METHOD_RECORD_VERIFY)
        whenever(extras.get(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("secret-token")
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("secret-token")

        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_RECORD_VERIFY,
            extras = extras,
            callingUid = 10001,
            callingPackages = arrayOf(Constants.PACKAGE_NAME_MODULE),
            myUid = 10001,
        )

        assertTrue(ok)
        verify(mockEditor).putLong(Constants.PREF_DIAG_VERIFY_AT, 123L)
        verify(mockEditor).putString(Constants.PREF_DIAG_VERIFY_DEVICE, "Pixel XL")
        verify(mockEditor, never()).putString(eq(Constants.EXTRA_DIAGNOSTICS_METHOD), any())
        verify(mockEditor, never()).putString(eq(Constants.EXTRA_DIAGNOSTICS_TOKEN), any())
        verify(mockEditor, never()).putString(eq("method"), any())
        verify(mockEditor, never()).putString(eq("token"), any())
    }

    @Test
    fun `applyDiagnostics broadcast without token is rejected when token provisioned`() {
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn("expected-token-123")

        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(setOf(Constants.PREF_DIAG_VERIFY_DEVICE))
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_DEVICE)).thenReturn("Pixel XL")
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn(null)
        whenever(extras.getString(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn(null)

        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_RECORD_VERIFY,
            extras = extras,
            // Broadcast path: no UID
            callingUid = null,
            callingPackages = null,
            myUid = null,
        )

        assertFalse(ok)
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `applyDiagnostics broadcast with valid token is accepted`() {
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn("valid-token-xyz")

        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(setOf(Constants.PREF_DIAG_VERIFY_DEVICE))
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_DEVICE)).thenReturn("Pixel XL")
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("valid-token-xyz")
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn(null)
        whenever(extras.getString(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn(null)

        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_RECORD_VERIFY,
            extras = extras,
            callingUid = null,
            callingPackages = null,
            myUid = null,
        )

        assertTrue(ok)
        verify(mockEditor).putString(Constants.PREF_DIAG_VERIFY_DEVICE, "Pixel XL")
        verify(mockEditor).apply()
    }

    @Test
    fun `applyDiagnostics broadcast with invalid token is rejected`() {
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn("expected-token-123")

        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(setOf(Constants.PREF_DIAG_VERIFY_DEVICE))
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_DEVICE)).thenReturn("Pixel XL")
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("wrong-token-999")

        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_RECORD_VERIFY,
            extras = extras,
            callingUid = null,
            callingPackages = null,
            myUid = null,
        )

        assertFalse(ok)
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `applyDiagnostics fails closed when no token provisioned yet`() {
        // No token stubbed anywhere (fresh install): even benign, denylist-passing
        // payloads must be rejected — no unauthenticated fallback exists.
        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(setOf(Constants.PREF_DIAG_VERIFY_DEVICE))
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_DEVICE)).thenReturn("Pixel XL")
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn(null)

        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_RECORD_DIAGNOSTICS,
            extras = extras,
            callingUid = null,
            callingPackages = null,
            myUid = null,
        )

        assertFalse(ok)
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `applyDiagnostics fails closed for CLEAR_VERIFY when no token provisioned yet`() {
        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_CLEAR_VERIFY,
            extras = mock(),
            callingUid = null,
            callingPackages = null,
            myUid = null,
        )

        assertFalse(ok)
        verify(mockEditor, never()).remove(any())
    }

    @Test
    fun `getStoredToken resolves remote prefs through PrefUtils when XposedService bound`() {
        // With the service bound, PrefUtils.getPrefs prefers the remote store — the
        // same canonical store hooked-process senders resolve the token from.
        stubBoundServiceWithRemotePrefs()
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn(null)
        whenever(mockRemotePrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull()))
            .thenReturn("remote-canonical-token")

        assertEquals("remote-canonical-token", DiagnosticsStore.getStoredToken(mockContext))
    }

    @Test
    fun `getStoredToken falls back to file prefs when no XposedService bound`() {
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn("file-token")

        assertEquals("file-token", DiagnosticsStore.getStoredToken(mockContext))
    }

    @Test
    fun `applyDiagnostics broadcast with valid token but denylisted reported package is rejected`() {
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull()))
            .thenReturn("valid-token-xyz")

        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(setOf(Constants.PREF_DIAG_VERIFY_PACKAGE))
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn("app.grapheneos.gmscompat")
        whenever(extras.getString(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn("app.grapheneos.gmscompat")
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("valid-token-xyz")

        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_RECORD_VERIFY,
            extras = extras,
            callingUid = null,
            callingPackages = null,
            myUid = null,
        )

        assertFalse(ok)
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `convergeBroadcastToken mirrors local file token into empty remote store`() {
        stubBoundServiceWithRemotePrefs()
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn("local-token")
        whenever(mockRemotePrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn(null)

        DiagnosticsStore.convergeBroadcastToken(mockContext)

        verify(mockRemoteEditor).putString(Constants.PREF_DIAG_BROADCAST_TOKEN, "local-token")
        verify(mockRemoteEditor).commit()
        verify(mockEditor, never()).commit()
    }

    @Test
    fun `convergeBroadcastToken aligns divergent local file token to remote`() {
        stubBoundServiceWithRemotePrefs()
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn("stale-local")
        whenever(mockRemotePrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull()))
            .thenReturn("remote-canonical")

        DiagnosticsStore.convergeBroadcastToken(mockContext)

        verify(mockEditor).putString(Constants.PREF_DIAG_BROADCAST_TOKEN, "remote-canonical")
        verify(mockEditor).commit()
        verify(mockRemoteEditor, never()).commit()
    }

    @Test
    fun `convergeBroadcastToken is a no-op when both stores already agree`() {
        stubBoundServiceWithRemotePrefs()
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn("same-token")
        whenever(mockRemotePrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull()))
            .thenReturn("same-token")

        DiagnosticsStore.convergeBroadcastToken(mockContext)

        verify(mockEditor, never()).commit()
        verify(mockRemoteEditor, never()).commit()
    }

    @Test
    fun `convergeBroadcastToken is a no-op without bound XposedService`() {
        whenever(mockContext.getSharedPreferences(eq(Constants.SHARED_PREF_FILE_NAME), eq(Context.MODE_PRIVATE)))
            .thenReturn(mockPrefs)
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn("local-only")

        DiagnosticsStore.convergeBroadcastToken(mockContext)

        verify(mockEditor, never()).commit()
    }

    @Test
    fun `getOrCreateToken returns existing token without regenerating`() {
        whenever(mockContext.getSharedPreferences(eq(Constants.SHARED_PREF_FILE_NAME), eq(Context.MODE_PRIVATE)))
            .thenReturn(mockPrefs)
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn("existing-token")

        assertEquals("existing-token", DiagnosticsStore.getOrCreateToken(mockContext))
        verify(mockEditor, never()).putString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), any())
        verify(mockEditor, never()).commit()
    }

    @Test
    fun `getOrCreateToken generates a new token when none exists`() {
        whenever(mockContext.getSharedPreferences(eq(Constants.SHARED_PREF_FILE_NAME), eq(Context.MODE_PRIVATE)))
            .thenReturn(mockPrefs)
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn(null)
        whenever(mockEditor.commit()).thenReturn(true)

        val token = DiagnosticsStore.getOrCreateToken(mockContext)

        assertTrue(!token.isNullOrEmpty())
        verify(mockEditor).putString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), eq(token))
        verify(mockEditor).commit()
    }

    @Test
    fun `getOrCreateToken still returns generated token when commit fails (receiver stays fail-closed)`() {
        whenever(mockContext.getSharedPreferences(eq(Constants.SHARED_PREF_FILE_NAME), eq(Context.MODE_PRIVATE)))
            .thenReturn(mockPrefs)
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn(null)
        whenever(mockEditor.commit()).thenReturn(false)

        val token = DiagnosticsStore.getOrCreateToken(mockContext)

        // Token is returned for immediate use, but because it was never persisted,
        // receiver-side getStoredToken keeps returning null and broadcasts stay rejected.
        assertTrue(!token.isNullOrEmpty())
        verify(mockEditor).commit()
    }

    @Test
    fun `getOrCreateToken returns null when preference access throws`() {
        whenever(mockContext.getSharedPreferences(eq(Constants.SHARED_PREF_FILE_NAME), eq(Context.MODE_PRIVATE)))
            .thenReturn(mockPrefs)
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn(null)
        whenever(mockEditor.commit()).thenThrow(IllegalStateException("disk full"))

        assertNull(DiagnosticsStore.getOrCreateToken(mockContext))
    }
}
