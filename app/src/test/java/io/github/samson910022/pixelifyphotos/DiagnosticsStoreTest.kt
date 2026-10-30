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
import org.junit.Assert.assertSame
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

    private val testBootEpoch = "test-epoch-1"

    private lateinit var mockedLog: MockedStatic<Log>
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockRemotePrefs: SharedPreferences
    private lateinit var mockRemoteEditor: SharedPreferences.Editor
    private val preferenceValues = mutableMapOf<String, Any?>()
    private val remotePreferenceValues = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = testBootEpoch,
        )
        DiagnosticsStore.testRemotePreferencesResolver = null
        setMockXposedService(null)
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
        remotePreferenceValues.clear()
        DiagnosticsStore.testRemotePreferencesResolver = { mockPrefs }

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
        whenever(mockEditor.commit()).thenReturn(true)
        // Remote store editor needs fluent chaining too (putX() must return the editor).
        whenever(mockRemoteEditor.putLong(any(), any())).thenReturn(mockRemoteEditor)
        whenever(mockRemoteEditor.putString(any(), any())).thenReturn(mockRemoteEditor)
        whenever(mockRemoteEditor.putBoolean(any(), any())).thenReturn(mockRemoteEditor)
        whenever(mockRemoteEditor.putFloat(any(), any())).thenReturn(mockRemoteEditor)
        whenever(mockRemoteEditor.putInt(any(), any())).thenReturn(mockRemoteEditor)
        whenever(mockRemoteEditor.putStringSet(any(), any())).thenReturn(mockRemoteEditor)
        whenever(mockRemoteEditor.remove(any())).thenReturn(mockRemoteEditor)
        whenever(mockRemoteEditor.commit()).thenReturn(true)
    }

    @After
    fun tearDown() {
        try {
            setMockXposedService(null)
            DiagnosticsStore.testRunClock = null
            DiagnosticsStore.testRemotePreferencesResolver = null
        } finally {
            mockedLog.close()
        }
    }

    /**
     * Injects a mock XposedService into the [App] companion so remote-preferences
     * paths can be exercised in JVM unit tests.
     */
    private fun setMockXposedService(service: XposedService?) {
        App.setServiceStateForTests(service, ready = service != null)
    }

    private fun stubBoundServiceWithRemotePrefs(): XposedService {
        DiagnosticsStore.testRemotePreferencesResolver = null
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(mockRemotePrefs)
        whenever(mockRemotePrefs.edit()).thenReturn(mockRemoteEditor)
        setMockXposedService(service)
        return service
    }

    private fun enablePersistedPrefs() {
        whenever(mockPrefs.getString(any(), anyOrNull())).thenAnswer {
            preferenceValues[it.getArgument<String>(0)] as? String ?: it.getArgument(1)
        }
        whenever(mockPrefs.getLong(any(), any())).thenAnswer {
            preferenceValues[it.getArgument<String>(0)] as? Long ?: it.getArgument(1)
        }
        whenever(mockPrefs.getInt(any(), any())).thenAnswer {
            preferenceValues[it.getArgument<String>(0)] as? Int ?: it.getArgument(1)
        }
        whenever(mockPrefs.getBoolean(any(), any())).thenAnswer {
            preferenceValues[it.getArgument<String>(0)] as? Boolean ?: it.getArgument(1)
        }
        whenever(mockEditor.putString(any(), anyOrNull())).thenAnswer {
            preferenceValues[it.getArgument<String>(0)] = it.getArgument(1)
            mockEditor
        }
        whenever(mockEditor.putLong(any(), any())).thenAnswer {
            preferenceValues[it.getArgument<String>(0)] = it.getArgument<Long>(1)
            mockEditor
        }
        whenever(mockEditor.putBoolean(any(), any())).thenAnswer {
            preferenceValues[it.getArgument<String>(0)] = it.getArgument<Boolean>(1)
            mockEditor
        }
        whenever(mockEditor.putStringSet(any(), anyOrNull())).thenAnswer {
            val key = it.getArgument<String>(0)
            val value = it.getArgument<Set<String>?>(1)
            if (value == null) preferenceValues.remove(key) else preferenceValues[key] = value.toSet()
            mockEditor
        }
        whenever(mockEditor.putInt(any(), any())).thenAnswer {
            preferenceValues[it.getArgument<String>(0)] = it.getArgument<Int>(1)
            mockEditor
        }
        whenever(mockEditor.remove(any())).thenAnswer {
            preferenceValues.remove(it.getArgument<String>(0))
            mockEditor
        }
        whenever(mockEditor.apply()).thenAnswer { Unit }
    }

    private fun enableRemotePersistedPrefs() {
        whenever(mockRemotePrefs.getString(any(), anyOrNull())).thenAnswer {
            remotePreferenceValues[it.getArgument<String>(0)] as? String ?: it.getArgument(1)
        }
        whenever(mockRemotePrefs.getLong(any(), any())).thenAnswer {
            remotePreferenceValues[it.getArgument<String>(0)] as? Long ?: it.getArgument(1)
        }
        whenever(mockRemotePrefs.getInt(any(), any())).thenAnswer {
            remotePreferenceValues[it.getArgument<String>(0)] as? Int ?: it.getArgument(1)
        }
        whenever(mockRemotePrefs.getBoolean(any(), any())).thenAnswer {
            remotePreferenceValues[it.getArgument<String>(0)] as? Boolean ?: it.getArgument(1)
        }
        whenever(mockRemoteEditor.putString(any(), anyOrNull())).thenAnswer {
            remotePreferenceValues[it.getArgument<String>(0)] = it.getArgument(1)
            mockRemoteEditor
        }
        whenever(mockRemoteEditor.putLong(any(), any())).thenAnswer {
            remotePreferenceValues[it.getArgument<String>(0)] = it.getArgument<Long>(1)
            mockRemoteEditor
        }
        whenever(mockRemoteEditor.putBoolean(any(), any())).thenAnswer {
            remotePreferenceValues[it.getArgument<String>(0)] = it.getArgument<Boolean>(1)
            mockRemoteEditor
        }
        whenever(mockRemoteEditor.putInt(any(), any())).thenAnswer {
            remotePreferenceValues[it.getArgument<String>(0)] = it.getArgument<Int>(1)
            mockRemoteEditor
        }
        whenever(mockRemoteEditor.putStringSet(any(), any())).thenAnswer {
            remotePreferenceValues[it.getArgument<String>(0)] = it.getArgument<Set<String>>(1)
            mockRemoteEditor
        }
        whenever(mockRemoteEditor.remove(any())).thenAnswer {
            remotePreferenceValues.remove(it.getArgument<String>(0))
            mockRemoteEditor
        }
        whenever(mockRemoteEditor.apply()).thenAnswer { Unit }
    }

    private fun bundle(values: Map<String, Any?>, addTestBootEpoch: Boolean = true): Bundle {
        val completeValues = if (
            addTestBootEpoch &&
            Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT in values &&
            Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH !in values
        ) values + (Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH to testBootEpoch) else values
        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(completeValues.keys)
        whenever(extras.get(any())).thenAnswer { completeValues[it.getArgument<String>(0)] }
        whenever(extras.getString(any())).thenAnswer { completeValues[it.getArgument<String>(0)] as? String }
        whenever(extras.getLong(any(), any())).thenAnswer {
            completeValues[it.getArgument<String>(0)] as? Long ?: it.getArgument(1)
        }
        whenever(extras.getInt(any(), any())).thenAnswer {
            completeValues[it.getArgument<String>(0)] as? Int ?: it.getArgument(1)
        }
        return extras
    }

    private fun authenticatedStartBundle(
        origin: String = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
        runId: String = "run-1",
        startedAt: Long = System.currentTimeMillis() - 1_000L,
        generation: Long = System.nanoTime(),
        bootCount: Int = 1,
        bootEpoch: String? = testBootEpoch,
        extras: Map<String, Any?> = emptyMap(),
    ): Bundle = bundle(buildMap {
        putAll(mapOf(
            Constants.EXTRA_DIAGNOSTICS_TOKEN to "test-run-token",
            Constants.EXTRA_DIAGNOSTICS_ORIGIN to origin,
            Constants.EXTRA_DIAGNOSTICS_RUN_ID to runId,
            Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
            Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
            Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to bootCount,
            Constants.PREF_DIAG_MODULE_LOADED_AT to startedAt,
            Constants.PREF_DIAG_LAST_PACKAGE_LOADED to origin,
        ))
        bootEpoch?.let { put(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH, it) }
        putAll(extras)
    }, addTestBootEpoch = bootEpoch != null)

    private fun authenticatedEventBundle(
        startedAt: Long,
        generation: Long,
        runId: String = "run-1",
        sequence: Any? = 1L,
        bootCount: Int = 1,
        bootEpoch: String? = testBootEpoch,
        diagnostics: Map<String, Any?>,
        includeSequence: Boolean = true,
    ): Bundle = bundle(
        buildMap {
            put(Constants.EXTRA_DIAGNOSTICS_TOKEN, "test-run-token")
            put(Constants.EXTRA_DIAGNOSTICS_ORIGIN, Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
            put(Constants.EXTRA_DIAGNOSTICS_RUN_ID, runId)
            put(Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT, startedAt)
            put(Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION, generation)
            put(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT, bootCount)
            bootEpoch?.let { put(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH, it) }
            if (includeSequence) put(Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE, sequence)
            putAll(diagnostics)
        },
        addTestBootEpoch = bootEpoch != null,
    )

    private fun verifyFields(
        failed: Any? = arrayListOf<String>(),
        ok: Boolean = failed is Collection<*> && failed.isEmpty(),
        packageName: String = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
    ): Map<String, Any?> = mapOf(
        Constants.PREF_DIAG_VERIFY_AT to 99_999L,
        Constants.PREF_DIAG_VERIFY_DEVICE to "Pixel XL",
        Constants.PREF_DIAG_VERIFY_PACKAGE to packageName,
        Constants.PREF_DIAG_VERIFY_OK to ok,
        Constants.PREF_DIAG_VERIFY_FAILED to failed,
        Constants.PREF_DIAG_VERIFY_NATIVE_READY to true,
        Constants.PREF_DIAG_VERIFY_SYSPROPS to true,
    )

    private fun seedActiveRun(
        startedAt: Long,
        generation: Long,
        runId: String = "run-1",
        origin: String = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
    ) {
        preferenceValues[Constants.PREF_DIAG_ACTIVE_PACKAGE] = origin
        preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID] = runId
        preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT] = startedAt
        preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_GENERATION] = generation
        preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH] = testBootEpoch
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
        assertTrue(allowed.contains(Constants.PREF_DIAG_FEATURE_HOOKS_AT))
        assertTrue(allowed.contains(Constants.PREF_DIAG_FEATURE_HOOK_STRING))
        assertTrue(allowed.contains(Constants.PREF_DIAG_FEATURE_HOOK_STRING_INT))
        assertTrue(allowed.contains(Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE))
        assertTrue(allowed.contains(Constants.PREF_DIAG_SYSPROPS_HOOKS_AT))
        assertTrue(allowed.contains(Constants.PREF_DIAG_SYSPROPS_HOOK_GET))
        assertTrue(allowed.contains(Constants.PREF_DIAG_SYSPROPS_HOOK_GET_DEFAULT))
        assertTrue(allowed.contains(Constants.PREF_DIAG_FEATURE_INTERCEPTOR_AT))
        assertTrue(allowed.contains(Constants.PREF_DIAG_SYSPROPS_INTERCEPTOR_AT))
        assertTrue(allowed.contains(Constants.PREF_DIAG_ACTIVE_PACKAGE))
        assertTrue(allowed.contains(Constants.PREF_DIAG_ACTIVE_RUN_ID))
        assertTrue(allowed.contains(Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT))

        // Sensitive preference keys must NEVER be in allowed keys
        assertFalse(allowed.contains(Constants.PREF_DEVICE_TO_SPOOF))
        assertFalse(allowed.contains(Constants.PREF_SPOOF_FEATURES_LIST))
        assertFalse(allowed.contains(Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS))
        assertFalse(allowed.contains(Constants.PREF_LAST_VERSION))
        assertFalse(allowed.contains(Constants.PREF_DIAG_BROADCAST_TOKEN))
        assertFalse(allowed.contains(Constants.EXTRA_DIAGNOSTICS_TOKEN))
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
    fun `applyDiagnostics processes complete RECORD_VERIFY successfully`() {
        enablePersistedPrefs()
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = System.nanoTime()
        seedActiveRun(startedAt, generation)
        val extras = authenticatedEventBundle(
            startedAt = startedAt,
            generation = generation,
            diagnostics = verifyFields(),
        )

        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_RECORD_VERIFY,
            extras = extras,
            callingUid = 10001,
            callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
            myUid = 10001,
        )

        assertTrue(ok)
        verify(mockEditor).putLong(Constants.PREF_DIAG_VERIFY_AT, 99999L)
        verify(mockEditor).putString(Constants.PREF_DIAG_VERIFY_DEVICE, "Pixel XL")
        verify(mockEditor).putString(Constants.PREF_DIAG_VERIFY_PACKAGE, Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_OK, true)
        verify(mockEditor).putStringSet(Constants.PREF_DIAG_VERIFY_FAILED, emptySet())
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_NATIVE_READY, true)
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_SYSPROPS, true)
        verify(mockEditor).apply()
    }

    @Test
    fun `generic record rejects VERIFY fields before mutation`() {
        val extras = bundle(mapOf(
            Constants.PREF_DIAG_VERIFY_AT to 99_999L,
            Constants.PREF_DIAG_VERIFY_OK to true,
        ))
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                extras,
                callingUid = 10001,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_MODULE),
                myUid = 10001,
            ),
        )
        verify(mockEditor, never()).apply()
        verify(mockEditor, never()).putLong(Constants.PREF_DIAG_VERIFY_AT, 99_999L)
        verify(mockEditor, never()).putBoolean(Constants.PREF_DIAG_VERIFY_OK, true)
    }

    @Test
    fun `partial and contradictory VERIFY records preserve the previous complete record`() {
        enablePersistedPrefs()
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = System.nanoTime()
        seedActiveRun(startedAt, generation)
        preferenceValues.putAll(verifyFields())
        val before = preferenceValues.toMap()

        val partial = authenticatedEventBundle(
            startedAt,
            generation,
            diagnostics = mapOf(Constants.PREF_DIAG_VERIFY_AT to 100_000L),
        )
        assertFalse(DiagnosticsStore.applyDiagnostics(
            mockContext,
            Constants.METHOD_RECORD_VERIFY,
            partial,
            callingUid = 10001,
            callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
            myUid = 10001,
        ))
        assertEquals(before, preferenceValues)

        val contradictory = authenticatedEventBundle(
            startedAt,
            generation,
            diagnostics = verifyFields(failed = arrayListOf("MODEL"), ok = true),
        )
        assertFalse(DiagnosticsStore.applyDiagnostics(
            mockContext,
            Constants.METHOD_RECORD_VERIFY,
            contradictory,
            callingUid = 10001,
            callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
            myUid = 10001,
        ))
        assertEquals(before, preferenceValues)

        val wrongPackage = authenticatedEventBundle(
            startedAt,
            generation,
            diagnostics = verifyFields(packageName = "com.google.android.apps.youtube.music"),
        )
        assertFalse(DiagnosticsStore.applyDiagnostics(
            mockContext,
            Constants.METHOD_RECORD_VERIFY,
            wrongPackage,
            callingUid = 10001,
            callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
            myUid = 10001,
        ))
        assertEquals(before, preferenceValues)
    }

    @Test
    fun `complete VERIFY record and clear both apply as whole records`() {
        enablePersistedPrefs()
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = System.nanoTime()
        seedActiveRun(startedAt, generation)
        val verify = authenticatedEventBundle(startedAt, generation, diagnostics = verifyFields())
        assertTrue(DiagnosticsStore.applyDiagnostics(
            mockContext,
            Constants.METHOD_RECORD_VERIFY,
            verify,
            callingUid = 10001,
            callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
            myUid = 10001,
        ))
        assertEquals(99_999L, preferenceValues[Constants.PREF_DIAG_VERIFY_AT])
        assertEquals(emptySet<String>(), preferenceValues[Constants.PREF_DIAG_VERIFY_FAILED])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_VERIFY_OK])

        assertTrue(DiagnosticsStore.applyDiagnostics(
            mockContext,
            Constants.METHOD_CLEAR_VERIFY,
            authenticatedEventBundle(startedAt, generation, diagnostics = emptyMap()),
            callingUid = 10001,
            callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
            myUid = 10001,
        ))
        DiagnosticsStore.VERIFY_KEYS_TO_CLEAR.forEach { assertFalse(preferenceValues.containsKey(it)) }
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
    fun `applyDiagnostics rejects unknown keys without mutating preferences`() {
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

        assertFalse(ok)
        verify(mockEditor, never()).putString(eq(Constants.PREF_DIAG_VERIFY_DEVICE), any())
        verify(mockEditor, never()).putString(eq(Constants.PREF_DEVICE_TO_SPOOF), any())
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `applyDiagnostics accepts typed token metadata but never writes it as a diagnostic`() {
        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(
            setOf(
                Constants.PREF_DIAG_FEATURE_HOOKS_AT,
                Constants.PREF_DIAG_FEATURE_HOOK_STRING,
                Constants.EXTRA_DIAGNOSTICS_TOKEN
            )
        )
        whenever(extras.get(Constants.PREF_DIAG_FEATURE_HOOKS_AT)).thenReturn(123L)
        whenever(extras.get(Constants.PREF_DIAG_FEATURE_HOOK_STRING)).thenReturn(true)
        whenever(extras.get(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("secret-token")
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("secret-token")

        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_RECORD_DIAGNOSTICS,
            extras = extras,
            callingUid = 10001,
            callingPackages = arrayOf(Constants.PACKAGE_NAME_MODULE),
            myUid = 10001,
        )

        assertTrue(ok)
        verify(mockEditor).putLong(Constants.PREF_DIAG_FEATURE_HOOKS_AT, 123L)
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_FEATURE_HOOK_STRING, true)
        verify(mockEditor, never()).putString(eq(Constants.EXTRA_DIAGNOSTICS_TOKEN), any())
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
    fun `token-only broadcast with valid token fails closed without API 34 origin identity`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "valid-token-xyz"
        val extras = authenticatedStartBundle(
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "valid-token-xyz"),
        )

        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_START_DIAGNOSTICS_RUN,
            extras = extras,
            callingUid = null,
            callingPackages = null,
            myUid = null,
        )

        assertFalse(ok)
        verify(mockEditor, never()).putString(eq(Constants.PREF_DIAG_ACTIVE_PACKAGE), anyOrNull())
        verify(mockEditor, never()).putString(eq(Constants.PREF_DIAG_ACTIVE_RUN_ID), anyOrNull())
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `unverified token-only run cannot replace or erase active verified diagnostics`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "valid-token-xyz"
        preferenceValues[Constants.PREF_DIAG_ACTIVE_PACKAGE] = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID] = "trusted-run"
        preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT] = 1_700_000_000_000L
        preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_GENERATION] = 100L
        preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT] = 1
        preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED] = true
        preferenceValues[Constants.PREF_DIAG_VERIFY_OK] = true
        preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING] = true

        val unverifiedStart = authenticatedStartBundle(
            runId = "token-only-run",
            startedAt = 1_700_000_001_000L,
            generation = 101L,
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "valid-token-xyz"),
        )
        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_START_DIAGNOSTICS_RUN,
            extras = unverifiedStart,
        )

        assertFalse(ok)
        assertEquals(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, preferenceValues[Constants.PREF_DIAG_ACTIVE_PACKAGE])
        assertEquals("trusted-run", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_VERIFY_OK])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
        verify(mockEditor, never()).apply()
        verify(mockEditor, never()).remove(any())
    }

    @Test
    fun `remote provider write requires token and matching target run`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "provider-token"
        val startAt = System.currentTimeMillis() - 2_000L
        val generation = System.nanoTime()
        val start = authenticatedStartBundle(
            startedAt = startAt,
            generation = generation,
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "provider-token"),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                start,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            )
        )

        val validUpdate = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to "provider-token",
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "run-1",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 1L,
                Constants.PREF_DIAG_FEATURE_HOOKS_AT to startAt,
                Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
            ),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                validUpdate,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )

        val wrongTypeUpdate = authenticatedEventBundle(
            startedAt = startAt,
            generation = generation,
            sequence = 2L,
            diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to "true"),
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                wrongTypeUpdate,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
        assertEquals(1L, preferenceValues["PREF_DIAG_EVENT_SEQUENCE:${Constants.PREF_DIAG_FEATURE_HOOK_STRING}"])

        val forgedUpdate = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "run-1",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.PREF_DIAG_FEATURE_HOOK_STRING to false,
            ),
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                forgedUpdate,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
    }

    @Test
    fun `single request accepts string arrays and collections for VERIFY_FAILED only`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = System.nanoTime()
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(startedAt = startedAt, generation = generation),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )

        val collectionPayload = authenticatedEventBundle(
            startedAt,
            generation,
            sequence = 1L,
            diagnostics = verifyFields(failed = arrayListOf("MODEL", "ID"), ok = false),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_VERIFY,
                collectionPayload,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(setOf("MODEL", "ID"), preferenceValues[Constants.PREF_DIAG_VERIFY_FAILED])

        val arrayPayload = authenticatedEventBundle(
            startedAt,
            generation,
            sequence = 2L,
            diagnostics = verifyFields(failed = arrayOf("FINGERPRINT"), ok = false),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_VERIFY,
                arrayPayload,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(setOf("FINGERPRINT"), preferenceValues[Constants.PREF_DIAG_VERIFY_FAILED])

        val beforeMalformedValues = preferenceValues.toMap()
        val malformedValues = listOf(
            arrayOf<Any?>("valid", null),
            arrayListOf<Any>("valid", 7),
            arrayListOf("MODEL".repeat(100)),
            ArrayList(List(33) { "MODEL" }),
            ArrayList(List(32) { "FINGERPRINT" }),
            arrayListOf("not_a_build_field"),
            null,
        )
        malformedValues.forEach { malformed ->
            val invalid = authenticatedEventBundle(
                startedAt,
                generation,
                sequence = 3L,
                diagnostics = verifyFields(failed = malformed, ok = false),
            )
            assertFalse(
                DiagnosticsStore.applyDiagnostics(
                    mockContext,
                    Constants.METHOD_RECORD_VERIFY,
                    invalid,
                    callingUid = 10002,
                    callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                    myUid = 10001,
                ),
            )
            assertEquals(beforeMalformedValues, preferenceValues)
        }
    }

    @Test
    fun `invalid run start schema and unknown event keys leave preferences unchanged`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID] = "preserved-run"
        preferenceValues[Constants.PREF_DIAG_VERIFY_OK] = true
        val before = preferenceValues.toMap()

        val wrongStartType = authenticatedStartBundle(
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to "1"),
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                wrongStartType,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(before, preferenceValues)
        verify(mockEditor, never()).apply()

        val unknownEventKey = authenticatedEventBundle(
            startedAt = System.currentTimeMillis() - 1_000L,
            generation = System.nanoTime(),
            sequence = 1L,
            diagnostics = mapOf("unknown_diagnostic" to true),
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                unknownEventKey,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(before, preferenceValues)
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `valid atomic batch writes start and event while malformed event writes neither`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = System.nanoTime()
        val start = authenticatedStartBundle(startedAt = startedAt, generation = generation)
        val event = authenticatedEventBundle(
            startedAt,
            generation,
            sequence = 1L,
            diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
        )

        assertEquals(
            true to true,
            DiagnosticsStore.applyDiagnosticsBatch(
                mockContext,
                start,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                event,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals("run-1", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])

        val before = preferenceValues.toMap()
        val nextGeneration = generation + 1L
        val invalidStart = authenticatedStartBundle(
            runId = "run-2",
            startedAt = startedAt + 1L,
            generation = nextGeneration,
        )
        val invalidEvent = authenticatedEventBundle(
            startedAt + 1L,
            nextGeneration,
            sequence = 1L,
            diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to "false"),
        )
        assertEquals(
            false to false,
            DiagnosticsStore.applyDiagnosticsBatch(
                mockContext,
                invalidStart,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                invalidEvent,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(before, preferenceValues)
    }

    @Test
    fun `atomic batch applies a complete VERIFY with its new run in one success`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val oldStartedAt = System.currentTimeMillis() - 2_000L
        val oldGeneration = System.nanoTime()
        seedActiveRun(oldStartedAt, oldGeneration)
        preferenceValues.putAll(verifyFields())

        val startedAt = oldStartedAt + 1L
        val generation = oldGeneration + 1L
        val start = authenticatedStartBundle(
            runId = "run-2",
            startedAt = startedAt,
            generation = generation,
        )
        val completeVerify = authenticatedEventBundle(
            startedAt = startedAt,
            generation = generation,
            runId = "run-2",
            sequence = 1L,
            diagnostics = verifyFields(),
        )

        assertEquals(
            true to true,
            DiagnosticsStore.applyDiagnosticsBatch(
                mockContext,
                start,
                Constants.METHOD_RECORD_VERIFY,
                completeVerify,
                callingUid = 10001,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )

        verify(mockEditor).putString(Constants.PREF_DIAG_ACTIVE_RUN_ID, "run-2")
        verify(mockEditor).putLong(Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT, startedAt)
        verify(mockEditor).putLong(Constants.PREF_DIAG_ACTIVE_RUN_GENERATION, generation)
        verify(mockEditor).putLong(Constants.PREF_DIAG_VERIFY_AT, 99_999L)
        verify(mockEditor).putString(Constants.PREF_DIAG_VERIFY_DEVICE, "Pixel XL")
        verify(mockEditor).putString(
            Constants.PREF_DIAG_VERIFY_PACKAGE,
            Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
        )
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_OK, true)
        verify(mockEditor).putStringSet(Constants.PREF_DIAG_VERIFY_FAILED, emptySet())
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_NATIVE_READY, true)
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_SYSPROPS, true)
        verify(mockEditor).apply()
    }

    @Test
    fun `atomic batch rejects incomplete VERIFY without replacing old run or record`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val oldStartedAt = System.currentTimeMillis() - 2_000L
        val oldGeneration = System.nanoTime()
        seedActiveRun(oldStartedAt, oldGeneration)
        preferenceValues.putAll(verifyFields())
        val before = preferenceValues.toMap()

        val startedAt = oldStartedAt + 1L
        val generation = oldGeneration + 1L
        val start = authenticatedStartBundle(
            runId = "run-2",
            startedAt = startedAt,
            generation = generation,
        )
        val incompleteVerify = authenticatedEventBundle(
            startedAt = startedAt,
            generation = generation,
            runId = "run-2",
            sequence = 1L,
            diagnostics = mapOf(Constants.PREF_DIAG_VERIFY_AT to 100_000L),
        )

        assertEquals(
            false to false,
            DiagnosticsStore.applyDiagnosticsBatch(
                mockContext,
                start,
                Constants.METHOD_RECORD_VERIFY,
                incompleteVerify,
                callingUid = 10001,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals("the start and incomplete VERIFY must be rejected atomically", before, preferenceValues)
    }

    @Test
    fun `remote event methods require values and reject replayed single and empty batch without mutation`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = System.nanoTime()
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(startedAt = startedAt, generation = generation),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )

        val beforeEmptyEvents = preferenceValues.toMap()
        listOf(Constants.METHOD_RECORD_DIAGNOSTICS, Constants.METHOD_RECORD_VERIFY).forEach { method ->
            val metadataOnly = authenticatedEventBundle(
                startedAt,
                generation,
                sequence = 1L,
                diagnostics = emptyMap(),
            )
            assertFalse(
                "$method metadata alone is not an event",
                DiagnosticsStore.applyDiagnostics(
                    mockContext,
                    method,
                    metadataOnly,
                    callingUid = 10002,
                    callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                    myUid = 10001,
                ),
            )
            assertEquals(beforeEmptyEvents, preferenceValues)
        }

        val first = authenticatedEventBundle(
            startedAt,
            generation,
            sequence = 1L,
            diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                first,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        val beforeReplay = preferenceValues.toMap()
        val replay = authenticatedEventBundle(
            startedAt,
            generation,
            sequence = 1L,
            diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to false),
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                replay,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(beforeReplay, preferenceValues)

        val replayBatchStart = authenticatedStartBundle(startedAt = startedAt, generation = generation)
        val replayBatchEvent = authenticatedEventBundle(
            startedAt,
            generation,
            sequence = 1L,
            diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
        )
        assertEquals(
            false to false,
            DiagnosticsStore.applyDiagnosticsBatch(
                mockContext,
                replayBatchStart,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                replayBatchEvent,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals("replayed batch must not acknowledge or commit its start", beforeReplay, preferenceValues)

        val beforeEmptyBatch = preferenceValues.toMap()
        val nextStartedAt = startedAt + 1L
        val nextGeneration = generation + 1L
        val candidateStart = authenticatedStartBundle(
            runId = "empty-event-run",
            startedAt = nextStartedAt,
            generation = nextGeneration,
        )
        val emptyEvent = authenticatedEventBundle(
            nextStartedAt,
            nextGeneration,
            runId = "empty-event-run",
            sequence = 1L,
            diagnostics = emptyMap(),
        )
        assertEquals(
            false to false,
            DiagnosticsStore.applyDiagnosticsBatch(
                mockContext,
                candidateStart,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                emptyEvent,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals("invalid batch must not commit its staged new run", beforeEmptyBatch, preferenceValues)
    }

    @Test
    fun `VERIFY clear is a valid sequenced operation and rejects a replay`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = System.nanoTime()
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(startedAt = startedAt, generation = generation),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        preferenceValues[Constants.PREF_DIAG_VERIFY_AT] = startedAt
        preferenceValues[Constants.PREF_DIAG_VERIFY_OK] = true
        preferenceValues[Constants.PREF_DIAG_VERIFY_FAILED] = setOf("MODEL")

        val clear = authenticatedEventBundle(
            startedAt,
            generation,
            sequence = 1L,
            diagnostics = emptyMap(),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_CLEAR_VERIFY,
                clear,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_VERIFY_AT))
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_VERIFY_FAILED))

        val afterClear = preferenceValues.toMap()
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_CLEAR_VERIFY,
                clear,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(afterClear, preferenceValues)
    }

    @Test
    fun `secondary same-process package telemetry is unsupported without weakening run or sender identity`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val photos = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        val secondary = "com.example.scoped.app"
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = System.nanoTime()
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(origin = photos, startedAt = startedAt, generation = generation),
                callingUid = 10002,
                callingPackages = arrayOf(photos),
                myUid = 10001,
            ),
        )
        val before = preferenceValues.toMap()
        val secondaryCallback = authenticatedEventBundle(
            startedAt,
            generation,
            sequence = 1L,
            diagnostics = mapOf(
                Constants.PREF_DIAG_LAST_PACKAGE_READY to secondary,
                Constants.PREF_DIAG_LAST_PACKAGE_READY_AT to startedAt + 1L,
            ),
        )

        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                secondaryCallback,
                callingUid = 10002,
                callingPackages = arrayOf(photos, secondary),
                myUid = 10001,
                verifiedBroadcastOrigin = secondary,
                requireVerifiedOrigin = true,
                remoteTargetWrite = true,
            ),
        )
        assertEquals("run identity and OS sender package remain independently bound",
            before, preferenceValues)
    }

    @Test
    fun `delayed receiver event cannot regress newer provider key but still applies a distinct key`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val startedAt = System.currentTimeMillis() - 2_000L
        val generation = System.nanoTime()
        val start = authenticatedStartBundle(startedAt = startedAt, generation = generation)
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                start,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )

        // Reporter dispatched sequence 1 through the receiver path, but receiver
        // handling is delayed until after the provider commits sequence 2.
        val delayedReceiverEvent = authenticatedEventBundle(
            startedAt = startedAt,
            generation = generation,
            sequence = 1L,
            diagnostics = mapOf(
                Constants.PREF_DIAG_FEATURE_HOOK_STRING to false,
                Constants.PREF_DIAG_FEATURE_INTERCEPTOR_AT to startedAt + 10L,
            ),
        )
        val newerProviderEvent = authenticatedEventBundle(
            startedAt = startedAt,
            generation = generation,
            sequence = 2L,
            diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                newerProviderEvent,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                delayedReceiverEvent,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
                verifiedBroadcastOrigin = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                requireVerifiedOrigin = true,
                remoteTargetWrite = true,
            ),
        )

        assertEquals("newer same-key provider value must win", true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
        assertEquals("unrelated key from delayed event must still be applied", startedAt + 10L,
            preferenceValues[Constants.PREF_DIAG_FEATURE_INTERCEPTOR_AT])
        assertEquals(2L, preferenceValues["PREF_DIAG_EVENT_SEQUENCE:${Constants.PREF_DIAG_FEATURE_HOOK_STRING}"])
        assertEquals(1L, preferenceValues["PREF_DIAG_EVENT_SEQUENCE:${Constants.PREF_DIAG_FEATURE_INTERCEPTOR_AT}"])
    }

    @Test
    fun `remote run-bound event rejects missing mistyped and out-of-range sequence`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = System.nanoTime()
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(startedAt = startedAt, generation = generation),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )

        val malformed = listOf(
            authenticatedEventBundle(startedAt, generation, includeSequence = false,
                diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true)),
            authenticatedEventBundle(startedAt, generation, sequence = 1,
                diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true)),
            authenticatedEventBundle(startedAt, generation, sequence = 0L,
                diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true)),
            authenticatedEventBundle(startedAt, generation, sequence = DiagnosticsStore.MAX_EVENT_SEQUENCE + 1L,
                diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true)),
        )
        malformed.forEach { extras ->
            assertFalse(
                DiagnosticsStore.applyDiagnostics(
                    mockContext,
                    Constants.METHOD_RECORD_DIAGNOSTICS,
                    extras,
                    callingUid = 10002,
                    callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                    myUid = 10001,
                ),
            )
        }
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_FEATURE_HOOK_STRING))
    }

    @Test
    fun `run-bound update and VERIFY clear reject matching persisted run from a previous OS boot`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = System.nanoTime()
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(startedAt = startedAt, generation = generation),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        // Incoming identity deliberately matches all persisted run fields; the
        // OS-observed boot epoch has changed, so the old run is no longer active.
        assertEquals(1, preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT])
        preferenceValues[Constants.PREF_DIAG_VERIFY_AT] = startedAt
        preferenceValues[Constants.PREF_DIAG_VERIFY_OK] = true
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 2,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = "test-epoch-2",
        )

        val update = authenticatedEventBundle(
            startedAt = startedAt,
            generation = generation,
            sequence = 1L,
            diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                update,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )

        val clear = authenticatedEventBundle(
            startedAt = startedAt,
            generation = generation,
            sequence = 1L,
            diagnostics = emptyMap(),
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_CLEAR_VERIFY,
                clear,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )

        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_FEATURE_HOOK_STRING))
        assertEquals(startedAt, preferenceValues[Constants.PREF_DIAG_VERIFY_AT])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_VERIFY_OK])
    }

    @Test
    fun `regressed boot counter accepts a new boot epoch and old reused-counter events stay stale`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val priorEpoch = "prior-boot-epoch"
        val currentEpoch = "current-boot-epoch"
        val priorStartedAt = System.currentTimeMillis() - 2_000L
        val priorGeneration = 800L
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 9,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = priorEpoch,
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    runId = "prior-run",
                    startedAt = priorStartedAt,
                    generation = priorGeneration,
                    bootCount = 9,
                    bootEpoch = priorEpoch,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        preferenceValues[Constants.PREF_DIAG_VERIFY_OK] = true
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = currentEpoch,
        )
        val currentStartedAt = priorStartedAt + 1_000L
        val currentGeneration = 700L
        assertTrue(
            "A valid current-epoch run replaces the prior epoch even with a regressed counter and generation",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    runId = "current-run",
                    startedAt = currentStartedAt,
                    generation = currentGeneration,
                    bootCount = 1,
                    bootEpoch = currentEpoch,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals("current-run", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(currentEpoch, preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH])
        assertFalse("prior VERIFY must not carry forward", preferenceValues.containsKey(Constants.PREF_DIAG_VERIFY_OK))

        val oldEvent = authenticatedEventBundle(
            startedAt = priorStartedAt,
            generation = priorGeneration,
            runId = "prior-run",
            bootCount = 1,
            bootEpoch = priorEpoch,
            diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
        )
        assertFalse(
            "An old run with a reused numeric counter cannot update the current epoch",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                oldEvent,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_FEATURE_HOOK_STRING))
    }

    @Test
    fun `missing boot epoch seeds an empty unknown run and cannot authorize events`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = null,
        )
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = 900L
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    runId = "unknown-epoch-run",
                    startedAt = startedAt,
                    generation = generation,
                    bootEpoch = null,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals("unknown-epoch-run", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH))
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                authenticatedEventBundle(
                    startedAt = startedAt,
                    generation = generation,
                    runId = "unknown-epoch-run",
                    bootEpoch = null,
                    diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_FEATURE_HOOK_STRING))
    }

    @Test
    fun `missing boot epoch cannot replace an existing run and recovers when identity returns`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = null,
        )
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = 900L
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    runId = "recovered-epoch-run",
                    startedAt = startedAt,
                    generation = generation,
                    bootEpoch = null,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertFalse(
            "an epoch-less start must not replace the active unknown run",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    runId = "recovered-epoch-run-successor",
                    startedAt = startedAt + 1L,
                    generation = generation + 1L,
                    bootEpoch = null,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )

        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = "recovered-epoch",
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    runId = "recovered-epoch-run",
                    startedAt = startedAt,
                    generation = generation,
                    bootEpoch = "recovered-epoch",
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals("recovered-epoch", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH])
    }

    @Test
    fun `run-bound event binds a blank stored epoch once identity becomes readable`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = null,
        )
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = 900L
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    runId = "upgrade-run",
                    startedAt = startedAt,
                    generation = generation,
                    bootEpoch = null,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH))

        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = "recovered-epoch",
        )
        assertTrue(
            "an event that proves the current epoch must upgrade the blank stored run",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                authenticatedEventBundle(
                    startedAt = startedAt,
                    generation = generation,
                    runId = "upgrade-run",
                    bootEpoch = "recovered-epoch",
                    diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals("recovered-epoch", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])

        assertFalse(
            "a different epoch cannot keep using the bound run",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                authenticatedEventBundle(
                    startedAt = startedAt,
                    generation = generation,
                    runId = "upgrade-run",
                    bootEpoch = "other-epoch",
                    diagnostics = mapOf(Constants.PREF_DIAG_SYSPROPS_HOOK_GET to true),
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
    }

    @Test
    fun `unverified event cannot bind the recovered epoch to a verified run`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = null,
        )
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = 910L
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    runId = "verified-blank-epoch-run",
                    startedAt = startedAt,
                    generation = generation,
                    bootEpoch = null,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])

        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = "recovered-epoch",
        )
        assertFalse(
            "a token-only event must not upgrade verified diagnostics",
            DiagnosticsStore.applyDiagnostics(
                context = mockContext,
                method = Constants.METHOD_RECORD_DIAGNOSTICS,
                extras = authenticatedEventBundle(
                    startedAt = startedAt,
                    generation = generation,
                    runId = "verified-blank-epoch-run",
                    bootEpoch = "recovered-epoch",
                    diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
                ),
                callingUid = null,
                callingPackages = null,
                myUid = null,
                allowTokenOnlyUnverifiedOrigin = true,
            ),
        )
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH))
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_FEATURE_HOOK_STRING))
    }

    @Test
    fun `unverified VERIFY clear cannot bind the recovered epoch`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = null,
        )
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = 915L
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    runId = "clear-blank-epoch-run",
                    startedAt = startedAt,
                    generation = generation,
                    bootEpoch = null,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])

        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = "recovered-epoch",
        )
        assertFalse(
            "a token-only VERIFY clear must not bind the epoch of a verified run",
            DiagnosticsStore.applyDiagnostics(
                context = mockContext,
                method = Constants.METHOD_CLEAR_VERIFY,
                extras = authenticatedEventBundle(
                    startedAt = startedAt,
                    generation = generation,
                    runId = "clear-blank-epoch-run",
                    bootEpoch = "recovered-epoch",
                    diagnostics = emptyMap(),
                ),
                callingUid = null,
                callingPackages = null,
                myUid = null,
                allowTokenOnlyUnverifiedOrigin = true,
            ),
        )
        assertFalse(
            "a rejected VERIFY clear must not stage the epoch upgrade",
            preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH),
        )
    }

    @Test
    fun `verified VERIFY clear binds the recovered epoch for an active run`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = null,
        )
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = 918L
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    runId = "clear-verified-upgrade-run",
                    startedAt = startedAt,
                    generation = generation,
                    bootEpoch = null,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH))

        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = "recovered-epoch",
        )
        assertTrue(
            "an OS-verified VERIFY clear must bind the epoch for a matching active run",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_CLEAR_VERIFY,
                authenticatedEventBundle(
                    startedAt = startedAt,
                    generation = generation,
                    runId = "clear-verified-upgrade-run",
                    bootEpoch = "recovered-epoch",
                    diagnostics = emptyMap(),
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals("recovered-epoch", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH])
    }

    @Test
    fun `rejected batch does not persist the epoch upgrade`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = null,
        )
        val startedAt = System.currentTimeMillis() - 2_000L
        val generation = 920L
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    runId = "batch-previous-run",
                    startedAt = startedAt,
                    generation = generation,
                    bootEpoch = null,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH))

        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = "recovered-epoch",
        )
        // The successor start is valid and fully staged. The event is a decodable VERIFY for the
        // previous run, so it is rejected only after the staged start exists. A batch that
        // committed its staged editor before checking the event result would persist both the
        // successor run and its epoch upgrade.
        val successorStart = authenticatedStartBundle(
            runId = "batch-successor-run",
            startedAt = startedAt + 1_000L,
            generation = generation + 1L,
            bootEpoch = "recovered-epoch",
        )
        val staleEvent = authenticatedEventBundle(
            startedAt = startedAt,
            generation = generation,
            runId = "batch-previous-run",
            bootEpoch = "recovered-epoch",
            diagnostics = verifyFields(),
        )
        assertEquals(
            false to false,
            DiagnosticsStore.applyDiagnosticsBatch(
                mockContext,
                start = successorStart,
                eventMethod = Constants.METHOD_RECORD_VERIFY,
                event = staleEvent,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(
            "a rejected batch must not commit the staged successor run",
            "batch-previous-run",
            preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID],
        )
        assertFalse(
            "a rejected batch must not commit the staged epoch upgrade",
            preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH),
        )

        // Positive control: the exact same successor start with a matching verify event
        // succeeds and commits the new run identity and epoch upgrade.
        val matchingVerify = authenticatedEventBundle(
            startedAt = startedAt + 1_000L,
            generation = generation + 1L,
            runId = "batch-successor-run",
            bootEpoch = "recovered-epoch",
            diagnostics = verifyFields(),
        )
        assertEquals(
            true to true,
            DiagnosticsStore.applyDiagnosticsBatch(
                mockContext,
                start = successorStart,
                eventMethod = Constants.METHOD_RECORD_VERIFY,
                event = matchingVerify,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(
            "batch-successor-run",
            preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID],
        )
        assertEquals(
            "recovered-epoch",
            preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH],
        )
    }

    @Test
    fun `start with an omitted epoch is rejected when the store can prove currentness`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = testBootEpoch,
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    runId = "omitted-epoch-run",
                    bootEpoch = null,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_RUN_ID))
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH))
    }

    @Test
    fun `unique boot epoch permits a run when numeric boot count is unavailable`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val epoch = "current-epoch-without-counter"
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = null,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = epoch,
        )
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = 900L
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    runId = "epoch-only-run",
                    startedAt = startedAt,
                    generation = generation,
                    bootEpoch = epoch,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT))
        val event = authenticatedEventBundle(
            startedAt = startedAt,
            generation = generation,
            runId = "epoch-only-run",
            bootCount = -1,
            bootEpoch = epoch,
            diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                event,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
    }

    @Test
    fun `new run resets per-key event sequences so sequence restarts at one`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val firstStartedAt = System.currentTimeMillis() - 2_000L
        val firstGeneration = System.nanoTime()
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(startedAt = firstStartedAt, generation = firstGeneration),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                authenticatedEventBundle(
                    startedAt = firstStartedAt,
                    generation = firstGeneration,
                    sequence = 10L,
                    diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to false),
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertTrue(
            "replaying the same run start must remain idempotent",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(startedAt = firstStartedAt, generation = firstGeneration),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(
            "same-run start replay must preserve event ordering state",
            10L,
            preferenceValues["PREF_DIAG_EVENT_SEQUENCE:${Constants.PREF_DIAG_FEATURE_HOOK_STRING}"],
        )

        val nextStartedAt = firstStartedAt + 1_000L
        val nextGeneration = firstGeneration + 1L
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    runId = "next-run",
                    startedAt = nextStartedAt,
                    generation = nextGeneration,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertFalse(preferenceValues.containsKey("PREF_DIAG_EVENT_SEQUENCE:${Constants.PREF_DIAG_FEATURE_HOOK_STRING}"))

        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                authenticatedEventBundle(
                    runId = "next-run",
                    startedAt = nextStartedAt,
                    generation = nextGeneration,
                    sequence = 1L,
                    diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
    }

    @Test
    fun `token-only start upgrades to OS-verified when the same run is replayed by the provider`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val startedAt = System.currentTimeMillis() - 1_500L
        val generation = 901L
        val start = authenticatedStartBundle(startedAt = startedAt, generation = generation)

        assertTrue(
            "token-only fallback may start a run when no verified run exists",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                start,
                callingUid = null,
                callingPackages = null,
                myUid = null,
                allowTokenOnlyUnverifiedOrigin = true,
            ),
        )
        assertEquals(false, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_BINDER_PID))
        assertFalse(
            "token-only start must not claim package-specific lifecycle observations",
            preferenceValues.containsKey(Constants.PREF_DIAG_MODULE_LOADED_AT),
        )
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_LAST_PACKAGE_LOADED))

        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                authenticatedEventBundle(
                    startedAt = startedAt,
                    generation = generation,
                    sequence = 5L,
                    diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
                ),
                callingUid = null,
                callingPackages = null,
                myUid = null,
                allowTokenOnlyUnverifiedOrigin = true,
            ),
        )

        assertTrue(
            "the same run identity from the OS-verified provider must upgrade the run",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                start,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
                callingPid = 4321,
            ),
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertEquals(4321, preferenceValues[Constants.PREF_DIAG_ACTIVE_BINDER_PID])
        assertEquals(
            "the verified replay seeds lifecycle observations the token-only start omitted",
            startedAt,
            preferenceValues[Constants.PREF_DIAG_MODULE_LOADED_AT],
        )
        assertEquals(
            Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            preferenceValues[Constants.PREF_DIAG_LAST_PACKAGE_LOADED],
        )
        assertEquals(
            "a verified replay of the same run must not reset event ordering",
            5L,
            preferenceValues["PREF_DIAG_EVENT_SEQUENCE:${Constants.PREF_DIAG_FEATURE_HOOK_STRING}"],
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])

        assertTrue(
            "a verified replay with a different PID stays idempotent",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                start,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
                callingPid = 8765,
            ),
        )
        assertEquals(
            "the first OS-observed sender PID must be preserved",
            4321,
            preferenceValues[Constants.PREF_DIAG_ACTIVE_BINDER_PID],
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertEquals(startedAt, preferenceValues[Constants.PREF_DIAG_MODULE_LOADED_AT])
        assertEquals(
            Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            preferenceValues[Constants.PREF_DIAG_LAST_PACKAGE_LOADED],
        )
    }

    @Test
    fun `verified batch upgrades a token-only run and applies its event atomically`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val startedAt = System.currentTimeMillis() - 1_500L
        val generation = 903L
        val start = authenticatedStartBundle(startedAt = startedAt, generation = generation)

        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                start,
                callingUid = null,
                callingPackages = null,
                myUid = null,
                allowTokenOnlyUnverifiedOrigin = true,
            ),
        )
        assertEquals(false, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])

        val event = authenticatedEventBundle(
            startedAt = startedAt,
            generation = generation,
            sequence = 7L,
            diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
        )
        assertEquals(
            "the verified batch must accept both its start replay and dependent event",
            true to true,
            DiagnosticsStore.applyDiagnosticsBatch(
                mockContext,
                start,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                event,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
                callingPid = 2468,
            ),
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertEquals(2468, preferenceValues[Constants.PREF_DIAG_ACTIVE_BINDER_PID])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
        assertEquals(startedAt, preferenceValues[Constants.PREF_DIAG_MODULE_LOADED_AT])
        assertEquals(7L, preferenceValues["PREF_DIAG_EVENT_SEQUENCE:${Constants.PREF_DIAG_FEATURE_HOOK_STRING}"])
    }

    @Test
    fun `verified event cannot replace the first OS-observed sender PID`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val startedAt = System.currentTimeMillis() - 1_500L
        val generation = 905L

        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(startedAt = startedAt, generation = generation),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
                callingPid = 1111,
            ),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                authenticatedEventBundle(
                    startedAt = startedAt,
                    generation = generation,
                    sequence = 1L,
                    diagnostics = mapOf(Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
                ),
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
                callingPid = 2222,
            ),
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
        assertEquals(
            "the first OS-observed sender PID must not be overwritten by later events",
            1111,
            preferenceValues[Constants.PREF_DIAG_ACTIVE_BINDER_PID],
        )
    }

    @Test
    fun `unverified replay of a verified same run is rejected and never downgrades state`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val startedAt = System.currentTimeMillis() - 1_500L
        val generation = 902L
        val start = authenticatedStartBundle(startedAt = startedAt, generation = generation)

        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                start,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
                callingPid = 5555,
            ),
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertEquals(5555, preferenceValues[Constants.PREF_DIAG_ACTIVE_BINDER_PID])

        assertFalse(
            "token-only replay must not downgrade verified provenance",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                start,
                callingUid = null,
                callingPackages = null,
                myUid = null,
                allowTokenOnlyUnverifiedOrigin = true,
            ),
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertEquals(5555, preferenceValues[Constants.PREF_DIAG_ACTIVE_BINDER_PID])
    }

    @Test
    fun `shared UID provider calls fail instead of claiming an unpersisted write succeeded`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "shared-token"
        val startAt = System.currentTimeMillis() - 1_000L
        val generation = System.nanoTime()
        val start = authenticatedStartBundle(
            startedAt = startAt,
            generation = generation,
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "shared-token"),
        )
        val sharedPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, "com.example.shared")
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext, Constants.METHOD_START_DIAGNOSTICS_RUN, start,
                callingUid = 10002, callingPackages = sharedPackages, myUid = 10001, callingPid = 4321,
            ),
        )
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_PACKAGE))
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED))

        val update = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to "shared-token",
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "run-1",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startAt,
                Constants.PREF_DIAG_FEATURE_HOOKS_AT to startAt,
                Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
            ),
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext, Constants.METHOD_RECORD_DIAGNOSTICS, update,
                callingUid = 10002, callingPackages = sharedPackages, myUid = 10001, callingPid = 4321,
            ),
        )
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_FEATURE_HOOK_STRING))
    }

    @Test
    fun `authenticated same-UID unverified start reaches startRun and preserves verified active state`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_ACTIVE_PACKAGE] = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID] = "trusted-run"
        preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT] = System.currentTimeMillis() + 86_400_000L
        preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_GENERATION] = 300L
        preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT] = 1
        preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED] = true
        preferenceValues[Constants.PREF_DIAG_VERIFY_OK] = true
        preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING] = true

        val sameUidStart = authenticatedStartBundle(
            runId = "unverified-local-run",
            startedAt = System.currentTimeMillis() - 1_000L,
            generation = 301L,
        )
        val accepted = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_START_DIAGNOSTICS_RUN,
            extras = sameUidStart,
            callingUid = 10001,
            callingPackages = null,
            myUid = 10001,
        )

        assertFalse(accepted)
        assertEquals("trusted-run", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_VERIFY_OK])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
        verify(mockEditor, never()).apply()
        verify(mockEditor, never()).remove(any())
    }

    @Test
    fun `single-package provider records only OS-observed Binder PID`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "pid-token"
        val startedAt = System.currentTimeMillis() - 1_000L
        val extras = authenticatedStartBundle(
            startedAt = startedAt,
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "pid-token"),
        )

        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext, Constants.METHOD_START_DIAGNOSTICS_RUN, extras,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
                callingPid = 7654,
            ),
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertEquals(7654, preferenceValues[Constants.PREF_DIAG_ACTIVE_BINDER_PID])
    }

    @Test
    fun `ready-only fallback persists ready without load and collector reports both facts`() {
        enablePersistedPrefs()
        val token = "ready-fallback-token"
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = token
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = System.nanoTime()
        val packageName = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        val startFields = DiagnosticsReporter.buildTargetRunStartFields(
            packageName = packageName,
            runId = "ready-only-run",
            startedAt = startedAt,
            generation = generation,
            bootEpochHash = testBootEpoch,
            moduleLoadedAt = 1_000L,
            packageLoadCallbackObserved = false,
        ) + mapOf(
            Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
            Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
        )
        val start = bundle(startFields)

        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                start,
                callingUid = 10002,
                callingPackages = arrayOf(packageName),
                myUid = 10001,
            ),
        )
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_LAST_PACKAGE_LOADED))

        val readyAt = startedAt + 1L
        val readyUpdate = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to packageName,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "ready-only-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 1L,
                Constants.PREF_DIAG_LAST_PACKAGE_READY to packageName,
                Constants.PREF_DIAG_LAST_PACKAGE_READY_AT to readyAt,
            ),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                readyUpdate,
                callingUid = 10002,
                callingPackages = arrayOf(packageName),
                myUid = 10001,
            ),
        )

        val lines = DiagnosticsCollector.interpretMilestones(
            DiagnosticsCollector.HookState(
                moduleLoadedAt = preferenceValues[Constants.PREF_DIAG_MODULE_LOADED_AT] as Long,
                lastPackageLoaded = preferenceValues[Constants.PREF_DIAG_LAST_PACKAGE_LOADED] as? String,
                lastPackageReady = preferenceValues[Constants.PREF_DIAG_LAST_PACKAGE_READY] as? String,
                lastPackageReadyAt = preferenceValues[Constants.PREF_DIAG_LAST_PACKAGE_READY_AT] as? Long,
            ),
            now = readyAt + 1L,
        )
        assertTrue(lines.any { it.contains("No package-load callback was recorded for this run") })
        assertTrue(lines.any { it.contains("Last package-ready callback observed: $packageName") })
    }

    @Test
    fun `newer verified package run replaces another package in the same epoch and stale starts stay rejected`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val packageA = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        val packageB = "com.example.allowed"

        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    origin = packageA,
                    runId = "package-a-run",
                    startedAt = 1_000L,
                    generation = 100L,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(packageA),
                myUid = 10001,
            ),
        )
        preferenceValues[Constants.PREF_DIAG_VERIFY_OK] = true

        assertTrue(
            "An independently verified newer package may become the active run",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    origin = packageB,
                    runId = "package-b-newer-run",
                    startedAt = 2_000L,
                    generation = 101L,
                ),
                callingUid = 10003,
                callingPackages = arrayOf(packageB),
                myUid = 10001,
            ),
        )
        assertEquals(packageB, preferenceValues[Constants.PREF_DIAG_ACTIVE_PACKAGE])
        assertEquals("package-b-newer-run", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertEquals("Package lifecycle attribution follows the verified sender", packageB,
            preferenceValues[Constants.PREF_DIAG_LAST_PACKAGE_LOADED])
        assertFalse("A new package run clears the prior package outcome",
            preferenceValues.containsKey(Constants.PREF_DIAG_VERIFY_OK))

        assertFalse(
            "A lower same-epoch monotonic generation cannot replace the current run",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    origin = packageB,
                    runId = "package-b-stale-run",
                    startedAt = 3_000L,
                    generation = 100L,
                ),
                callingUid = 10003,
                callingPackages = arrayOf(packageB),
                myUid = 10001,
            ),
        )
        assertEquals(packageB, preferenceValues[Constants.PREF_DIAG_ACTIVE_PACKAGE])
        assertEquals("package-b-newer-run", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])

        assertFalse(
            "A verified sender cannot attribute lifecycle state to a different package",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    origin = packageB,
                    runId = "package-b-misattributed-run",
                    startedAt = 4_000L,
                    generation = 102L,
                    extras = mapOf(Constants.PREF_DIAG_LAST_PACKAGE_LOADED to packageA),
                ),
                callingUid = 10003,
                callingPackages = arrayOf(packageB),
                myUid = 10001,
            ),
        )
        assertEquals(packageB, preferenceValues[Constants.PREF_DIAG_ACTIVE_PACKAGE])
        assertEquals(packageB, preferenceValues[Constants.PREF_DIAG_LAST_PACKAGE_LOADED])
    }

    @Test
    fun `new verified package run from current boot replaces prior epoch despite boot count regression`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val packageA = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        val packageB = "com.example.allowed"
        val priorEpoch = "prior-package-boot-epoch"
        val currentEpoch = "current-package-boot-epoch"
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 9,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = priorEpoch,
        )

        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    origin = packageA,
                    runId = "prior-package-run",
                    startedAt = 1_000L,
                    generation = 800L,
                    bootCount = 9,
                    bootEpoch = priorEpoch,
                ),
                callingUid = 10002,
                callingPackages = arrayOf(packageA),
                myUid = 10001,
            ),
        )

        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = currentEpoch,
        )
        assertTrue(
            "The OS-observed current epoch outranks a regressed boot count and generation",
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    origin = packageB,
                    runId = "current-package-run",
                    startedAt = 2_000L,
                    generation = 700L,
                    bootCount = 1,
                    bootEpoch = currentEpoch,
                ),
                callingUid = 10003,
                callingPackages = arrayOf(packageB),
                myUid = 10001,
            ),
        )

        assertEquals(packageB, preferenceValues[Constants.PREF_DIAG_ACTIVE_PACKAGE])
        assertEquals("current-package-run", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(1, preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT])
        assertEquals(currentEpoch, preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH])
        assertEquals(packageB, preferenceValues[Constants.PREF_DIAG_LAST_PACKAGE_LOADED])
    }

    @Test
    fun `unverified token-only package cannot replace verified active package run`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "valid-token-xyz"
        val packageA = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        val packageB = "com.example.allowed"
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    origin = packageA,
                    runId = "verified-package-a-run",
                    startedAt = 1_000L,
                    generation = 100L,
                    extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "valid-token-xyz"),
                ),
                callingUid = 10002,
                callingPackages = arrayOf(packageA),
                myUid = 10001,
            ),
        )

        val tokenOnlyPackageB = authenticatedStartBundle(
            origin = packageB,
            runId = "unverified-package-b-run",
            startedAt = 2_000L,
            generation = 101L,
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "valid-token-xyz"),
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                context = mockContext,
                method = Constants.METHOD_START_DIAGNOSTICS_RUN,
                extras = tokenOnlyPackageB,
                allowTokenOnlyUnverifiedOrigin = true,
                allowPriorBootUnverifiedReplacement = true,
                remoteTargetWrite = true,
            ),
        )
        assertEquals(packageA, preferenceValues[Constants.PREF_DIAG_ACTIVE_PACKAGE])
        assertEquals("verified-package-a-run", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
    }

    @Test
    fun `token-only fallback batch replaces a verified prior-boot run and stays explicitly unverified`() {
        enablePersistedPrefs()
        val token = "test-run-token"
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = token
        val priorEpoch = "verified-prior-boot-epoch"
        val currentEpoch = "fallback-current-boot-epoch"
        val photos = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        val fallbackPackage = "com.example.allowed"
        val priorStartedAt = System.currentTimeMillis() - 2_000L
        val priorGeneration = 800L
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 9,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = priorEpoch,
        )

        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                authenticatedStartBundle(
                    origin = photos,
                    runId = "verified-prior-boot-run",
                    startedAt = priorStartedAt,
                    generation = priorGeneration,
                    bootCount = 9,
                    bootEpoch = priorEpoch,
                    extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to token),
                ),
                callingUid = 10002,
                callingPackages = arrayOf(photos),
                myUid = 10001,
            ),
        )
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])

        // Model outcomes left behind by the verified run on the prior boot.
        preferenceValues.putAll(verifyFields(packageName = photos))
        preferenceValues[Constants.PREF_DIAG_FEATURE_HOOKS_AT] = priorStartedAt
        preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING] = true
        preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE] = true

        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = currentEpoch,
        )
        val currentStartedAt = priorStartedAt + 1_000L
        val currentGeneration = 700L // Deliberately lower: the current boot epoch takes precedence.
        val start = authenticatedStartBundle(
            origin = fallbackPackage,
            runId = "token-only-current-boot-run",
            startedAt = currentStartedAt,
            generation = currentGeneration,
            bootCount = 1,
            bootEpoch = currentEpoch,
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to token),
        )
        val event = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to fallbackPackage,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "token-only-current-boot-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to currentStartedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to currentGeneration,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH to currentEpoch,
                Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 1L,
                Constants.PREF_DIAG_FEATURE_HOOKS_AT to currentStartedAt + 10L,
                Constants.PREF_DIAG_FEATURE_HOOK_STRING_INT to true,
            ),
        )

        assertEquals(
            "A token-authenticated API 30-33 fallback batch should start the new boot run and record telemetry",
            true to true,
            DiagnosticsStore.applyDiagnosticsBatch(
                context = mockContext,
                start = start,
                eventMethod = Constants.METHOD_RECORD_DIAGNOSTICS,
                event = event,
                allowTokenOnlyUnverifiedOrigin = true,
                allowPriorBootUnverifiedReplacement = true,
                remoteTargetWrite = true,
            ),
        )

        assertEquals("token-only-current-boot-run", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(fallbackPackage, preferenceValues[Constants.PREF_DIAG_ACTIVE_PACKAGE])
        assertEquals(currentEpoch, preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH])
        assertEquals(1, preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT])
        assertEquals(false, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_VERIFY_AT))
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_VERIFY_OK))
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_VERIFY_FAILED))
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_FEATURE_HOOK_STRING))
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE))
        assertEquals(currentStartedAt + 10L, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOKS_AT])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING_INT])
    }

    @Test
    fun `token-only fallback preserves verified state when either boot epoch is unknown`() {
        enablePersistedPrefs()
        val token = "test-run-token"
        val photos = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        val fallbackPackage = "com.example.allowed"
        val currentStartedAt = System.currentTimeMillis() - 1_000L
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = token

        fun seedVerifiedRun(runId: String, epoch: String?) {
            preferenceValues.clear()
            preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = token
            preferenceValues[Constants.PREF_DIAG_ACTIVE_PACKAGE] = photos
            preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID] = runId
            preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT] = currentStartedAt - 1_000L
            preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_GENERATION] = 800L
            preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT] = 9
            epoch?.let { preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH] = it }
            preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED] = true
            preferenceValues[Constants.PREF_DIAG_VERIFY_OK] = true
            preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING] = true
        }

        // A missing stored epoch cannot prove that the verified run is historical.
        seedVerifiedRun("verified-run-with-unknown-epoch", epoch = null)
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = testBootEpoch,
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                context = mockContext,
                method = Constants.METHOD_START_DIAGNOSTICS_RUN,
                extras = authenticatedStartBundle(
                    origin = fallbackPackage,
                    runId = "must-not-replace-missing-epoch",
                    startedAt = currentStartedAt,
                    generation = 900L,
                    bootCount = 1,
                    bootEpoch = testBootEpoch,
                    extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to token),
                ),
                allowTokenOnlyUnverifiedOrigin = true,
                allowPriorBootUnverifiedReplacement = true,
                remoteTargetWrite = true,
            ),
        )
        assertEquals("verified-run-with-unknown-epoch", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_VERIFY_OK])

        // An unknown current epoch likewise cannot establish that any active run is old.
        seedVerifiedRun("verified-run-with-known-epoch", epoch = testBootEpoch)
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
            bootEpochHash = null,
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                context = mockContext,
                method = Constants.METHOD_START_DIAGNOSTICS_RUN,
                extras = authenticatedStartBundle(
                    origin = fallbackPackage,
                    runId = "must-not-replace-unknown-current-epoch",
                    startedAt = currentStartedAt,
                    generation = 900L,
                    bootCount = 1,
                    bootEpoch = null,
                    extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to token),
                ),
                allowTokenOnlyUnverifiedOrigin = true,
                allowPriorBootUnverifiedReplacement = true,
                remoteTargetWrite = true,
            ),
        )
        assertEquals("verified-run-with-known-epoch", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(testBootEpoch, preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertEquals(true, preferenceValues[Constants.PREF_DIAG_VERIFY_OK])
    }

    @Test
    fun `API 26 and 29 token-only telemetry stays available but cannot replace prior verified state`() {
        enablePersistedPrefs()
        val token = "pre-appsfilter-fallback-token"
        val photos = Constants.PACKAGE_NAME_GOOGLE_PHOTOS
        val fallbackPackage = "com.example.allowed"
        val priorEpoch = "verified-prior-epoch"
        val currentEpoch = "unverified-current-epoch"

        for (sdkInt in listOf(26, 29)) {
            val allowPriorBootReplacement =
                DiagnosticsOriginPolicy.allowsTokenOnlyPriorBootReplacement(sdkInt, tokenOnlyAllowed = true)
            assertFalse("API $sdkInt must not enable the prior-boot exception", allowPriorBootReplacement)

            // An empty/new run remains eligible for ordinary explicitly unverified telemetry.
            preferenceValues.clear()
            preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = token
            DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
                bootCount = 1,
                elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
                bootEpochHash = currentEpoch,
            )
            val newStartedAt = System.currentTimeMillis() - 2_000L
            val newGeneration = 900L
            assertTrue(
                "API $sdkInt token-only identity can still start an empty run",
                DiagnosticsStore.applyDiagnostics(
                    context = mockContext,
                    method = Constants.METHOD_START_DIAGNOSTICS_RUN,
                    extras = authenticatedStartBundle(
                        origin = fallbackPackage,
                        runId = "api-$sdkInt-new-run",
                        startedAt = newStartedAt,
                        generation = newGeneration,
                        bootCount = 1,
                        bootEpoch = currentEpoch,
                        extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to token),
                    ),
                    allowTokenOnlyUnverifiedOrigin = true,
                    allowPriorBootUnverifiedReplacement = allowPriorBootReplacement,
                    remoteTargetWrite = true,
                ),
            )
            assertTrue(
                "API $sdkInt token-only identity can still record normal telemetry",
                DiagnosticsStore.applyDiagnostics(
                    context = mockContext,
                    method = Constants.METHOD_RECORD_DIAGNOSTICS,
                    extras = bundle(
                        mapOf(
                            Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                            Constants.EXTRA_DIAGNOSTICS_ORIGIN to fallbackPackage,
                            Constants.EXTRA_DIAGNOSTICS_RUN_ID to "api-$sdkInt-new-run",
                            Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to newStartedAt,
                            Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to newGeneration,
                            Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                            Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH to currentEpoch,
                            Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 1L,
                            Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
                        ),
                    ),
                    allowTokenOnlyUnverifiedOrigin = true,
                    allowPriorBootUnverifiedReplacement = allowPriorBootReplacement,
                    remoteTargetWrite = true,
                ),
            )
            assertEquals("api-$sdkInt-new-run", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
            assertEquals(false, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
            assertEquals(true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])

            // A known prior epoch is not enough to replace verified state on API 26-29.
            preferenceValues.clear()
            preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = token
            DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
                bootCount = 9,
                elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
                bootEpochHash = priorEpoch,
            )
            val priorStartedAt = System.currentTimeMillis() - 1_000L
            assertTrue(
                DiagnosticsStore.applyDiagnostics(
                    context = mockContext,
                    method = Constants.METHOD_START_DIAGNOSTICS_RUN,
                    extras = authenticatedStartBundle(
                        origin = photos,
                        runId = "api-$sdkInt-verified-prior-run",
                        startedAt = priorStartedAt,
                        generation = 800L,
                        bootCount = 9,
                        bootEpoch = priorEpoch,
                        extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to token),
                    ),
                    callingUid = 10002,
                    callingPackages = arrayOf(photos),
                    myUid = 10001,
                ),
            )
            preferenceValues[Constants.PREF_DIAG_VERIFY_OK] = true
            preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING] = true

            DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
                bootCount = 1,
                elapsedRealtimeNanos = Long.MAX_VALUE - 1L,
                bootEpochHash = currentEpoch,
            )
            val currentStartedAt = priorStartedAt + 1_000L
            val currentRunId = "api-$sdkInt-unverified-current-run"
            val start = authenticatedStartBundle(
                origin = fallbackPackage,
                runId = currentRunId,
                startedAt = currentStartedAt,
                generation = 700L,
                bootCount = 1,
                bootEpoch = currentEpoch,
                extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to token),
            )
            val event = bundle(
                mapOf(
                    Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                    Constants.EXTRA_DIAGNOSTICS_ORIGIN to fallbackPackage,
                    Constants.EXTRA_DIAGNOSTICS_RUN_ID to currentRunId,
                    Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to currentStartedAt,
                    Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to 700L,
                    Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                    Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH to currentEpoch,
                    Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 1L,
                    Constants.PREF_DIAG_FEATURE_HOOKS_AT to currentStartedAt + 10L,
                ),
            )
            assertEquals(
                "API $sdkInt must preserve a verified prior-boot run",
                false to false,
                DiagnosticsStore.applyDiagnosticsBatch(
                    context = mockContext,
                    start = start,
                    eventMethod = Constants.METHOD_RECORD_DIAGNOSTICS,
                    event = event,
                    allowTokenOnlyUnverifiedOrigin = true,
                    allowPriorBootUnverifiedReplacement = allowPriorBootReplacement,
                    remoteTargetWrite = true,
                ),
            )
            assertEquals("api-$sdkInt-verified-prior-run", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
            assertEquals(true, preferenceValues[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
            assertEquals(true, preferenceValues[Constants.PREF_DIAG_VERIFY_OK])
            assertEquals(true, preferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
            assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_FEATURE_HOOKS_AT))
        }
    }

    @Test
    fun `new package run clears old outcomes and rejects delayed old-run updates`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "test-run-token"
        val firstAt = System.currentTimeMillis() - 10_000L
        val firstGeneration = System.nanoTime()
        val firstStart = authenticatedStartBundle(startedAt = firstAt, generation = firstGeneration)
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                firstStart,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        val firstReport = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to "test-run-token",
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "run-1",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to firstAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to firstGeneration,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 1L,
                Constants.PREF_DIAG_FEATURE_HOOKS_AT to firstAt,
                Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
            ),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                firstReport,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )

        val secondAt = firstAt + 1_000L
        val secondGeneration = firstGeneration + 1L
        val secondStart = authenticatedStartBundle(
            origin = "com.example.allowed",
            runId = "run-2",
            startedAt = secondAt,
            generation = secondGeneration,
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "test-run-token"),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                secondStart,
                callingUid = 10002,
                callingPackages = arrayOf("com.example.allowed"),
                myUid = 10001,
            ),
        )
        assertEquals("com.example.allowed", preferenceValues[Constants.PREF_DIAG_ACTIVE_PACKAGE])
        assertEquals("run-2", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_FEATURE_HOOK_STRING))

        val delayedOldReport = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to "test-run-token",
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "run-1",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to firstAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to firstGeneration,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
            ),
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                delayedOldReport,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )

        val delayedOldStart = authenticatedStartBundle(startedAt = firstAt, generation = firstGeneration)
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                delayedOldStart,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals("com.example.allowed", preferenceValues[Constants.PREF_DIAG_ACTIVE_PACKAGE])
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_FEATURE_HOOK_STRING))
    }

    @Test
    fun `monotonic generation replaces future wall timestamp and rejects delayed prior UUID`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "monotonic-token"
        val futureStartedAt = System.currentTimeMillis() + 86_400_000L
        val futureGeneration = 700L
        val futureStart = authenticatedStartBundle(
            runId = "future-wall-run",
            startedAt = futureStartedAt,
            generation = futureGeneration,
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "monotonic-token"),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                futureStart,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )

        val rolledBackWallTime = System.currentTimeMillis() - 60_000L
        val newerGeneration = futureGeneration + 1L
        val newerStart = authenticatedStartBundle(
            runId = "newer-monotonic-run",
            startedAt = rolledBackWallTime,
            generation = newerGeneration,
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "monotonic-token"),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                newerStart,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals("newer-monotonic-run", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(rolledBackWallTime, preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT])
        assertEquals(newerGeneration, preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_GENERATION])

        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                futureStart,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        assertEquals("newer-monotonic-run", preferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
    }

    @Test
    fun `run start rejects sender generation beyond OS monotonic time`() {
        enablePersistedPrefs()
        val forged = authenticatedStartBundle(
            runId = "future-generation",
            generation = Long.MAX_VALUE,
        )

        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                forged,
                callingUid = 10002,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10001,
            ),
        )
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `remote source must own the declared package and future runs fail closed`() {
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull()))
            .thenReturn("identity-token")
        val startAt = System.currentTimeMillis() - 1_000L
        val start = authenticatedStartBundle(
            startedAt = startAt,
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "identity-token"),
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                start,
                callingUid = 10002,
                callingPackages = arrayOf("com.example.different.uid"),
                myUid = 10001,
            ),
        )

        val futureStart = authenticatedStartBundle(
            runId = "future-run",
            startedAt = System.currentTimeMillis() + 60_000L,
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "identity-token"),
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                futureStart,
            ),
        )
        verify(mockEditor, never()).apply()
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
    fun `remote provider write fails closed without ready Xposed service instead of writing local fallback`() {
        DiagnosticsStore.testRemotePreferencesResolver = null
        setMockXposedService(null)
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "shared-token"

        val start = authenticatedStartBundle(
            runId = "must-not-land-locally",
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "shared-token"),
        )
        val accepted = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_START_DIAGNOSTICS_RUN,
            extras = start,
            callingUid = 10123,
            callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
            myUid = 10000,
            remoteTargetWrite = true,
        )

        assertFalse(accepted)
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_RUN_ID))
        verify(mockEditor, never()).apply()
        verify(mockEditor, never()).remove(any())
    }

    @Test
    fun `remote telemetry after service rebind persists run and updates only canonical remote store`() {
        enablePersistedPrefs()
        preferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "shared-token"
        enableRemotePersistedPrefs()
        remotePreferenceValues[Constants.PREF_DIAG_BROADCAST_TOKEN] = "shared-token"
        stubBoundServiceWithRemotePrefs()

        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = System.nanoTime()
        val start = authenticatedStartBundle(
            runId = "rebound-run",
            startedAt = startedAt,
            generation = generation,
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "shared-token"),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                context = mockContext,
                method = Constants.METHOD_START_DIAGNOSTICS_RUN,
                extras = start,
                callingUid = 10123,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10000,
                remoteTargetWrite = true,
            ),
        )

        val activeRunUpdate = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to "shared-token",
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "rebound-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 1L,
                Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
            ),
        )
        assertTrue(
            DiagnosticsStore.applyDiagnostics(
                context = mockContext,
                method = Constants.METHOD_RECORD_DIAGNOSTICS,
                extras = activeRunUpdate,
                callingUid = 10123,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10000,
                remoteTargetWrite = true,
            ),
        )

        assertSame(mockRemotePrefs, PrefUtils.getPrefs(mockContext))
        assertEquals("rebound-run", remotePreferenceValues[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(true, remotePreferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_PACKAGE))
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_ACTIVE_RUN_ID))
        assertFalse(preferenceValues.containsKey(Constants.PREF_DIAG_FEATURE_HOOK_STRING))
        verify(mockEditor, never()).apply()
        verify(mockRemoteEditor, org.mockito.Mockito.atLeastOnce()).apply()

        val staleUpdate = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to "shared-token",
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "inactive-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to 1_700_000_000_000L,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to 1L,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.PREF_DIAG_FEATURE_HOOK_STRING to false,
            ),
        )
        assertFalse(
            DiagnosticsStore.applyDiagnostics(
                context = mockContext,
                method = Constants.METHOD_RECORD_DIAGNOSTICS,
                extras = staleUpdate,
                callingUid = 10123,
                callingPackages = arrayOf(Constants.PACKAGE_NAME_GOOGLE_PHOTOS),
                myUid = 10000,
                remoteTargetWrite = true,
            ),
        )
        assertEquals(true, remotePreferenceValues[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
    }

    @Test
    fun `applyDiagnostics broadcast with valid token but denylisted reported package is rejected`() {
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull()))
            .thenReturn("valid-token-xyz")

        val extras = authenticatedStartBundle(
            origin = "app.grapheneos.gmscompat",
            extras = mapOf(Constants.EXTRA_DIAGNOSTICS_TOKEN to "valid-token-xyz"),
        )

        val ok = DiagnosticsStore.applyDiagnostics(
            context = mockContext,
            method = Constants.METHOD_START_DIAGNOSTICS_RUN,
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

        assertTrue(DiagnosticsStore.convergeBroadcastToken(mockContext))

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

        assertTrue(DiagnosticsStore.convergeBroadcastToken(mockContext))

        verify(mockEditor).putString(Constants.PREF_DIAG_BROADCAST_TOKEN, "remote-canonical")
        verify(mockEditor).commit()
        verify(mockRemoteEditor, never()).commit()
    }

    @Test
    fun `convergeBroadcastToken treats a canonical remote token as usable when the local mirror commit fails`() {
        stubBoundServiceWithRemotePrefs()
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn("stale-local")
        whenever(mockRemotePrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull()))
            .thenReturn("remote-canonical")
        whenever(mockEditor.commit()).thenReturn(false)

        assertTrue(DiagnosticsStore.convergeBroadcastToken(mockContext))

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

        assertTrue(DiagnosticsStore.convergeBroadcastToken(mockContext))

        verify(mockEditor, never()).commit()
        verify(mockRemoteEditor, never()).commit()
    }

    @Test
    fun `convergeBroadcastToken is a no-op without bound XposedService`() {
        whenever(mockContext.getSharedPreferences(eq(Constants.SHARED_PREF_FILE_NAME), eq(Context.MODE_PRIVATE)))
            .thenReturn(mockPrefs)
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn("local-only")

        assertFalse(DiagnosticsStore.convergeBroadcastToken(mockContext))

        verify(mockEditor, never()).commit()
    }

    @Test
    fun `convergeBroadcastToken provisions local token on demand when both stores are empty`() {
        stubBoundServiceWithRemotePrefs()
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn(null)
        whenever(mockRemotePrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn(null)

        assertTrue(DiagnosticsStore.convergeBroadcastToken(mockContext))

        verify(mockEditor).putString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), any())
        verify(mockEditor).commit()
        verify(mockRemoteEditor).putString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), any())
        verify(mockRemoteEditor).commit()
    }

    @Test
    fun `convergeBroadcastToken returns false when local on-demand provisioning fails and remote is empty`() {
        stubBoundServiceWithRemotePrefs()
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn(null)
        whenever(mockRemotePrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn(null)
        whenever(mockEditor.commit()).thenReturn(false)

        assertFalse(DiagnosticsStore.convergeBroadcastToken(mockContext))

        verify(mockRemoteEditor, never()).commit()
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
    fun `getOrCreateToken returns null when commit fails so unpersisted token is never trusted`() {
        whenever(mockContext.getSharedPreferences(eq(Constants.SHARED_PREF_FILE_NAME), eq(Context.MODE_PRIVATE)))
            .thenReturn(mockPrefs)
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn(null)
        whenever(mockEditor.commit()).thenReturn(false)

        assertNull(DiagnosticsStore.getOrCreateToken(mockContext))
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
