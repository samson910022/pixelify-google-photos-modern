package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DiagnosticsReceiverTest {

    private lateinit var mockedLog: MockedStatic<Log>
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var receiver: DiagnosticsReceiver

    @Before
    fun setUp() {
        DiagnosticsStore.testRunClock = DiagnosticsStore.RunClock(
            bootCount = 1,
            elapsedRealtimeNanos = Long.MAX_VALUE,
            bootEpochHash = "test-epoch-1",
        )
        mockedLog = Mockito.mockStatic(Log::class.java)
        mockedLog.`when`<Int> { Log.d(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>(), anyOrNull()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.e(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.e(any<String>(), any<String>(), anyOrNull()) }.thenReturn(0)

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
        whenever(mockEditor.putStringSet(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.remove(any())).thenReturn(mockEditor)

        App.setServiceStateForTests(null, ready = false)
        receiver = DiagnosticsReceiver()
    }

    @After
    fun tearDown() {
        // Fail-fast reset: a silent swallow here could mask cross-suite pollution.
        App.setServiceStateForTests(null, ready = false)
        DiagnosticsStore.testRunClock = null
        DiagnosticsStore.testRemotePreferencesResolver = null
        mockedLog.close()
    }

    private fun stubActiveRun(
        extras: Bundle,
        origin: String = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
        runId: String = "receiver-run",
        startedAt: Long = 123456789L,
    ) {
        whenever(mockPrefs.getString(Constants.PREF_DIAG_ACTIVE_PACKAGE, null)).thenReturn(origin)
        whenever(mockPrefs.getString(Constants.PREF_DIAG_ACTIVE_RUN_ID, null)).thenReturn(runId)
        whenever(mockPrefs.getLong(Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT, 0L)).thenReturn(startedAt)
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_ORIGIN)).thenReturn(origin)
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_RUN_ID)).thenReturn(runId)
        whenever(extras.getLong(Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT, 0L)).thenReturn(startedAt)
        whenever(mockPrefs.getLong(Constants.PREF_DIAG_ACTIVE_RUN_GENERATION, 0L)).thenReturn(456L)
        whenever(mockPrefs.getInt(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT, -1)).thenReturn(1)
        whenever(mockPrefs.getString(Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH, null)).thenReturn("test-epoch-1")
        whenever(extras.getLong(Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION, 0L)).thenReturn(456L)
        whenever(extras.getInt(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT, -1)).thenReturn(1)
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH)).thenReturn("test-epoch-1")
    }

    private fun bundle(values: Map<String, Any?>): Bundle {
        val completeValues = (if (
            Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT in values &&
            Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH !in values
        ) values + (Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH to "test-epoch-1") else values).toMutableMap()
        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenAnswer { completeValues.keys }
        whenever(extras.get(any())).thenAnswer { completeValues[it.getArgument<String>(0)] }
        whenever(extras.getString(any())).thenAnswer { completeValues[it.getArgument<String>(0)] as? String }
        whenever(extras.getLong(any(), any())).thenAnswer {
            completeValues[it.getArgument<String>(0)] as? Long ?: it.getArgument(1)
        }
        whenever(extras.getInt(any(), any())).thenAnswer {
            completeValues[it.getArgument<String>(0)] as? Int ?: it.getArgument(1)
        }
        doAnswer { completeValues.remove(it.getArgument<String>(0)); null }
            .whenever(extras).remove(any())
        return extras
    }

    private fun api34Identity(
        origin: String = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
        sentPackage: String? = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
        packagesForUid: Array<String>? = arrayOf(
            Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            "com.example.shared",
        ),
    ) = DiagnosticsOriginPolicy.broadcastCallerIdentity(
        origin = origin,
        sentUid = 10123,
        invalidUid = -1,
        sentPackage = sentPackage,
        packagesForUid = packagesForUid,
        myUid = 10000,
    )

    private fun api34RunStart(token: String, startedAt: Long): Bundle = bundle(
        mapOf(
            Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
            Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            Constants.EXTRA_DIAGNOSTICS_RUN_ID to "api34-run",
            Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
            Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to 123L,
            Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
            Constants.PREF_DIAG_MODULE_LOADED_AT to startedAt,
            Constants.PREF_DIAG_LAST_PACKAGE_LOADED to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
        ),
    )

    private fun installMapBackedPrefs(initial: Map<String, Any?>): MutableMap<String, Any?> {
        val values = initial.toMutableMap()
        whenever(mockPrefs.getString(any(), anyOrNull())).thenAnswer {
            values[it.getArgument<String>(0)] as? String ?: it.getArgument(1)
        }
        whenever(mockPrefs.getLong(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] as? Long ?: it.getArgument(1)
        }
        whenever(mockPrefs.getInt(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] as? Int ?: it.getArgument(1)
        }
        whenever(mockPrefs.getBoolean(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] as? Boolean ?: it.getArgument(1)
        }
        whenever(mockPrefs.contains(any())).thenAnswer { values.containsKey(it.getArgument<String>(0)) }
        whenever(mockPrefs.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putString(any(), anyOrNull())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument(1)
            mockEditor
        }
        whenever(mockEditor.putLong(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument<Long>(1)
            mockEditor
        }
        whenever(mockEditor.putInt(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument<Int>(1)
            mockEditor
        }
        whenever(mockEditor.putBoolean(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument<Boolean>(1)
            mockEditor
        }
        whenever(mockEditor.putStringSet(any(), anyOrNull())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument<Set<String>?>(1)
            mockEditor
        }
        whenever(mockEditor.remove(any())).thenAnswer {
            values.remove(it.getArgument<String>(0))
            mockEditor
        }
        whenever(mockEditor.apply()).thenAnswer { Unit }
        return values
    }

    private fun runBoundEvent(token: String, startedAt: Long, generation: Long): Bundle = bundle(
        mapOf(
            Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
            Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            Constants.EXTRA_DIAGNOSTICS_RUN_ID to "batch-run",
            Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
            Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
            Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
            Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 1L,
            Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
        ),
    )

    @Test
    fun `fallback batch applies start before dependent event using same verified sender`() {
        val token = "batch-auth-token"
        val values = installMapBackedPrefs(mapOf(Constants.PREF_DIAG_BROADCAST_TOKEN to token))
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = 123L
        val start = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "batch-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.PREF_DIAG_MODULE_LOADED_AT to startedAt,
            ),
        )

        val result = receiver.applyBatchWithSenderIdentity(
            context = mockContext,
            start = start,
            eventMethod = Constants.METHOD_RECORD_DIAGNOSTICS,
            event = runBoundEvent(token, startedAt, generation),
            identity = api34Identity(),
        )

        assertTrue(result.first)
        assertTrue(result.second)
        assertEquals("batch-run", values[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(true, values[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
        assertEquals(1L, values["PREF_DIAG_EVENT_SEQUENCE:${Constants.PREF_DIAG_FEATURE_HOOK_STRING}"])
        verify(mockEditor, times(1)).apply()
    }

    @Test
    fun `malformed nested event leaves prior active run and telemetry unchanged`() {
        val token = "atomic-malformed-token"
        val initial = mapOf(
            Constants.PREF_DIAG_BROADCAST_TOKEN to token,
            Constants.PREF_DIAG_ACTIVE_PACKAGE to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            Constants.PREF_DIAG_ACTIVE_RUN_ID to "previous-run",
            Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT to 111_000L,
            Constants.PREF_DIAG_ACTIVE_RUN_GENERATION to 100L,
            Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT to 1,
            Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH to "test-epoch-1",
            Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED to true,
            Constants.PREF_DIAG_MODULE_LOADED_AT to 111_000L,
            Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
            "PREF_DIAG_EVENT_SEQUENCE:${Constants.PREF_DIAG_FEATURE_HOOK_STRING}" to 7L,
        )
        val values = installMapBackedPrefs(initial)
        val startedAt = System.currentTimeMillis() - 1_000L
        val start = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "candidate-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to 123L,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.PREF_DIAG_MODULE_LOADED_AT to startedAt,
            ),
        )
        val eventValues = linkedMapOf<String, Any?>(
            Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
            Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            Constants.EXTRA_DIAGNOSTICS_RUN_ID to "candidate-run",
            Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
            Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to 123L,
            Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
            Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH to "test-epoch-1",
            Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 1L,
            Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
        )
        val malformedEvent = mock<Bundle>()
        whenever(malformedEvent.keySet()).thenReturn(eventValues.keys)
        whenever(malformedEvent.get(any())).thenAnswer {
            val key = it.getArgument<String>(0)
            if (key == Constants.EXTRA_DIAGNOSTICS_TOKEN) {
                throw IllegalStateException("malformed lazy token parcel")
            }
            eventValues[key]
        }

        val result = receiver.applyBatchWithSenderIdentity(
            mockContext,
            start,
            Constants.METHOD_RECORD_DIAGNOSTICS,
            malformedEvent,
            api34Identity(),
        )

        assertEquals(false to false, result)
        assertEquals(initial, values)
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `invalid batch event sequence origin or run identity cannot commit its start`() {
        val token = "atomic-policy-token"
        val initial = mapOf(
            Constants.PREF_DIAG_BROADCAST_TOKEN to token,
            Constants.PREF_DIAG_ACTIVE_PACKAGE to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            Constants.PREF_DIAG_ACTIVE_RUN_ID to "previous-run",
            Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT to 111_000L,
            Constants.PREF_DIAG_ACTIVE_RUN_GENERATION to 100L,
            Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT to 1,
            Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH to "test-epoch-1",
            Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED to true,
            Constants.PREF_DIAG_MODULE_LOADED_AT to 111_000L,
            Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
        )
        val values = installMapBackedPrefs(initial)
        val startedAt = System.currentTimeMillis() - 1_000L
        val start = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "candidate-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to 123L,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.PREF_DIAG_MODULE_LOADED_AT to startedAt,
            ),
        )

        fun event(origin: String = Constants.PACKAGE_NAME_GOOGLE_PHOTOS, runId: String = "candidate-run", sequence: Long = 1L) =
            bundle(
                mapOf(
                    Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                    Constants.EXTRA_DIAGNOSTICS_ORIGIN to origin,
                    Constants.EXTRA_DIAGNOSTICS_RUN_ID to runId,
                    Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                    Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to 123L,
                    Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                    Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to sequence,
                    Constants.PREF_DIAG_FEATURE_HOOK_STRING to false,
                ),
            )

        val invalidEvents = listOf(
            event(sequence = 0L),
            event(origin = Constants.PACKAGE_NAME_MODULE),
            event(runId = "different-run"),
        )
        for (invalidEvent in invalidEvents) {
            val result = receiver.applyBatchWithSenderIdentity(
                mockContext,
                start,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                invalidEvent,
                api34Identity(),
            )
            assertEquals(false to false, result)
            assertEquals(initial, values)
        }
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `duplicate start remains idempotent while duplicate event sequence leaves batch unchanged`() {
        val token = "duplicate-batch-token"
        val values = installMapBackedPrefs(mapOf(Constants.PREF_DIAG_BROADCAST_TOKEN to token))
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = 123L
        val start = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "duplicate-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
            ),
        )
        val firstEvent = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "duplicate-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 1L,
                Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
            ),
        )

        assertEquals(
            true to true,
            receiver.applyBatchWithSenderIdentity(
                mockContext,
                start,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                firstEvent,
                api34Identity(),
            ),
        )
        val beforeReplay = values.toMap()
        val replayResult = receiver.applyBatchWithSenderIdentity(
            mockContext,
            start,
            Constants.METHOD_RECORD_DIAGNOSTICS,
            firstEvent,
            api34Identity(),
        )

        assertEquals(false to false, replayResult)
        assertEquals(beforeReplay, values)
        assertEquals("duplicate-run", values[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(true, values[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
    }

    @Test
    fun `batch preserves independent per-key event sequence ordering`() {
        val token = "per-key-batch-token"
        val startedAt = System.currentTimeMillis() - 1_000L
        val initial = mapOf(
            Constants.PREF_DIAG_BROADCAST_TOKEN to token,
            Constants.PREF_DIAG_ACTIVE_PACKAGE to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            Constants.PREF_DIAG_ACTIVE_RUN_ID to "existing-run",
            Constants.PREF_DIAG_ACTIVE_RUN_STARTED_AT to startedAt,
            Constants.PREF_DIAG_ACTIVE_RUN_GENERATION to 123L,
            Constants.PREF_DIAG_ACTIVE_RUN_BOOT_COUNT to 1,
            Constants.PREF_DIAG_ACTIVE_RUN_BOOT_EPOCH to "test-epoch-1",
            Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED to true,
            Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
            Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE to false,
            "PREF_DIAG_EVENT_SEQUENCE:${Constants.PREF_DIAG_FEATURE_HOOK_STRING}" to 8L,
            "PREF_DIAG_EVENT_SEQUENCE:${Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE}" to 2L,
        )
        val values = installMapBackedPrefs(initial)
        val start = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "existing-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to 123L,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
            ),
        )
        val event = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "existing-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to 123L,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 5L,
                Constants.PREF_DIAG_FEATURE_HOOK_STRING to false,
                Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE to true,
            ),
        )

        assertEquals(
            true to true,
            receiver.applyBatchWithSenderIdentity(
                mockContext,
                start,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                event,
                api34Identity(),
            ),
        )
        assertEquals(true, values[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
        assertEquals(8L, values["PREF_DIAG_EVENT_SEQUENCE:${Constants.PREF_DIAG_FEATURE_HOOK_STRING}"])
        assertEquals(true, values[Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE])
        assertEquals(5L, values["PREF_DIAG_EVENT_SEQUENCE:${Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE}"])
    }

    @Test
    fun `API 30 to 33 token-only batch persists explicitly unverified telemetry`() {
        val token = "legacy-batch-token"
        val values = installMapBackedPrefs(mapOf(Constants.PREF_DIAG_BROADCAST_TOKEN to token))
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = 123L
        val start = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "legacy-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.PREF_DIAG_MODULE_LOADED_AT to startedAt,
            ),
        )
        val event = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "legacy-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 1L,
                Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
            ),
        )

        val result = receiver.applyBatchWithSenderIdentity(
            mockContext,
            start,
            Constants.METHOD_RECORD_DIAGNOSTICS,
            event,
            DiagnosticsOriginPolicy.tokenOnlyBroadcastIdentity(),
        )

        assertTrue(result.first)
        assertTrue(result.second)
        assertEquals("legacy-run", values[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(false, values[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertEquals(true, values[Constants.PREF_DIAG_FEATURE_HOOK_STRING])

        val verifyEvent = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "legacy-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 2L,
                Constants.PREF_DIAG_VERIFY_AT to startedAt,
                Constants.PREF_DIAG_VERIFY_DEVICE to "Pixel XL",
                Constants.PREF_DIAG_VERIFY_PACKAGE to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.PREF_DIAG_VERIFY_OK to true,
                Constants.PREF_DIAG_VERIFY_FAILED to arrayListOf<String>(),
                Constants.PREF_DIAG_VERIFY_NATIVE_READY to true,
                Constants.PREF_DIAG_VERIFY_SYSPROPS to true,
            ),
        )
        assertTrue(
            receiver.applyWithSenderIdentity(
                mockContext,
                Constants.METHOD_RECORD_VERIFY,
                verifyEvent,
                DiagnosticsOriginPolicy.tokenOnlyBroadcastIdentity(),
            ),
        )
        assertEquals(false, values[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertEquals(true, values[Constants.PREF_DIAG_VERIFY_OK])

        val clearEvent = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "legacy-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 3L,
            ),
        )
        assertTrue(
            receiver.applyWithSenderIdentity(
                mockContext,
                Constants.METHOD_CLEAR_VERIFY,
                clearEvent,
                DiagnosticsOriginPolicy.tokenOnlyBroadcastIdentity(),
            ),
        )
        assertFalse(values.containsKey(Constants.PREF_DIAG_VERIFY_OK))
        assertEquals(false, values[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
    }

    @Test
    fun `API 30 to 33 token-only batch cannot replace or clear active verified run`() {
        val token = "legacy-preserve-token"
        val values = installMapBackedPrefs(mapOf(Constants.PREF_DIAG_BROADCAST_TOKEN to token))
        val startedAt = System.currentTimeMillis() - 1_000L
        assertTrue(
            receiver.applyWithSenderIdentity(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                api34RunStart(token, startedAt),
                api34Identity(),
            ),
        )
        val verifiedEvent = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "api34-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to 123L,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 1L,
                Constants.PREF_DIAG_FEATURE_HOOK_STRING to true,
            ),
        )
        assertTrue(
            receiver.applyWithSenderIdentity(
                mockContext,
                Constants.METHOD_RECORD_DIAGNOSTICS,
                verifiedEvent,
                api34Identity(),
            ),
        )

        val legacyStartedAt = startedAt + 1_000L
        val legacyStart = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "legacy-newer-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to legacyStartedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to 124L,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
            ),
        )
        val legacyEvent = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "legacy-newer-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to legacyStartedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to 124L,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 1L,
                Constants.PREF_DIAG_FEATURE_HOOK_STRING to false,
            ),
        )
        val result = receiver.applyBatchWithSenderIdentity(
            mockContext,
            legacyStart,
            Constants.METHOD_RECORD_DIAGNOSTICS,
            legacyEvent,
            DiagnosticsOriginPolicy.tokenOnlyBroadcastIdentity(),
        )

        assertFalse(result.first)
        assertFalse(result.second)
        assertEquals("api34-run", values[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(true, values[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
        assertEquals(true, values[Constants.PREF_DIAG_FEATURE_HOOK_STRING])

        val clearCurrentRun = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "api34-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to 123L,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to 2L,
            ),
        )
        assertFalse(
            receiver.applyWithSenderIdentity(
                mockContext,
                Constants.METHOD_CLEAR_VERIFY,
                clearCurrentRun,
                DiagnosticsOriginPolicy.tokenOnlyBroadcastIdentity(),
            ),
        )
        assertEquals(true, values[Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED])
    }

    @Test
    fun `rejected batch run start prevents dependent telemetry from being attempted`() {
        installMapBackedPrefs(mapOf(Constants.PREF_DIAG_BROADCAST_TOKEN to "expected-token"))
        val startedAt = System.currentTimeMillis() - 1_000L
        val start = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to "wrong-token",
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "batch-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to 123L,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
            ),
        )

        val result = receiver.applyBatchWithSenderIdentity(
            context = mockContext,
            start = start,
            eventMethod = Constants.METHOD_RECORD_DIAGNOSTICS,
            event = runBoundEvent("expected-token", startedAt, 123L),
            identity = api34Identity(),
        )

        assertFalse(result.first)
        assertFalse(result.second)
        verify(mockPrefs, times(1)).getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `onReceive ignores null context or null intent safely`() {
        receiver.onReceive(null, null)
        receiver.onReceive(mockContext, null)
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `onReceive ignores intent with non-matching action`() {
        val intent = mock<Intent>()
        whenever(intent.action).thenReturn("android.intent.action.BOOT_COMPLETED")
        whenever(intent.getStringExtra(Constants.EXTRA_DIAGNOSTICS_METHOD)).thenReturn(Constants.METHOD_RECORD_DIAGNOSTICS)

        receiver.onReceive(mockContext, intent)
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `onReceive ignores intent with missing method extra`() {
        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(Constants.ACTION_RECORD_DIAGNOSTICS)
        whenever(intent.getStringExtra(Constants.EXTRA_DIAGNOSTICS_METHOD)).thenReturn(null)

        receiver.onReceive(mockContext, intent)
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `onReceive rejects malformed extras without authenticating or crashing`() {
        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(Constants.ACTION_RECORD_DIAGNOSTICS)
        whenever(intent.getStringExtra(Constants.EXTRA_DIAGNOSTICS_METHOD))
            .thenReturn(Constants.METHOD_RECORD_DIAGNOSTICS)
        whenever(intent.extras).thenThrow(IllegalStateException("malformed parcel"))

        receiver.onReceive(mockContext, intent)

        verify(mockPrefs, never()).getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `onReceive accepts actual single broadcast envelopes for start record VERIFY and clear`() {
        val token = "actual-envelope-token"
        val values = installMapBackedPrefs(mapOf(Constants.PREF_DIAG_BROADCAST_TOKEN to token))
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = 123L
        val runId = "actual-envelope-run"

        receiver.onReceive(
            mockContext,
            broadcastIntent(
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                mapOf(
                    Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                    Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                    Constants.EXTRA_DIAGNOSTICS_RUN_ID to runId,
                    Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                    Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
                    Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                ),
            ),
        )
        assertEquals(runId, values[Constants.PREF_DIAG_ACTIVE_RUN_ID])

        receiver.onReceive(
            mockContext,
            broadcastIntent(
                Constants.METHOD_RECORD_DIAGNOSTICS,
                eventValues(token, runId, startedAt, generation, 1L) +
                    (Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
            ),
        )
        assertEquals(true, values[Constants.PREF_DIAG_FEATURE_HOOK_STRING])

        receiver.onReceive(
            mockContext,
            broadcastIntent(
                Constants.METHOD_RECORD_VERIFY,
                eventValues(token, runId, startedAt, generation, 2L) + mapOf(
                    Constants.PREF_DIAG_VERIFY_AT to startedAt + 1L,
                    Constants.PREF_DIAG_VERIFY_DEVICE to "Pixel XL",
                    Constants.PREF_DIAG_VERIFY_PACKAGE to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                    Constants.PREF_DIAG_VERIFY_OK to false,
                    Constants.PREF_DIAG_VERIFY_FAILED to arrayListOf("MODEL"),
                    Constants.PREF_DIAG_VERIFY_NATIVE_READY to true,
                    Constants.PREF_DIAG_VERIFY_SYSPROPS to true,
                ),
            ),
        )
        assertEquals(false, values[Constants.PREF_DIAG_VERIFY_OK])
        assertEquals(setOf("MODEL"), values[Constants.PREF_DIAG_VERIFY_FAILED])

        receiver.onReceive(
            mockContext,
            broadcastIntent(
                Constants.METHOD_CLEAR_VERIFY,
                eventValues(token, runId, startedAt, generation, 3L),
            ),
        )
        assertFalse(values.containsKey(Constants.PREF_DIAG_VERIFY_AT))
        assertFalse(values.containsKey(Constants.PREF_DIAG_VERIFY_FAILED))
        assertEquals(runId, values[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        verify(mockEditor, times(4)).apply()
    }

    @Test
    fun `onReceive rejects mismatched method payload and malformed method type`() {
        val token = "mismatched-method-token"
        val values = installMapBackedPrefs(mapOf(Constants.PREF_DIAG_BROADCAST_TOKEN to token))
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = 234L
        receiver.onReceive(
            mockContext,
            broadcastIntent(
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                mapOf(
                    Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                    Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                    Constants.EXTRA_DIAGNOSTICS_RUN_ID to "mismatched-run",
                    Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                    Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
                    Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
                ),
            ),
        )
        val before = values.toMap()

        // A VERIFY operation cannot smuggle a general diagnostics key merely by
        // supplying the correctly typed transport method.
        receiver.onReceive(
            mockContext,
            broadcastIntent(
                Constants.METHOD_RECORD_VERIFY,
                eventValues(token, "mismatched-run", startedAt, generation, 1L) +
                    (Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
            ),
        )
        assertEquals(before, values)

        val malformedExtras = bundle(mapOf(Constants.EXTRA_DIAGNOSTICS_METHOD to 42))
        val malformedIntent = mock<Intent>()
        whenever(malformedIntent.action).thenReturn(Constants.ACTION_RECORD_DIAGNOSTICS)
        whenever(malformedIntent.extras).thenReturn(malformedExtras)
        receiver.onReceive(mockContext, malformedIntent)
        assertEquals(before, values)
    }

    @Test
    fun `onReceive validates the batch transport envelope before atomic Store handling`() {
        val token = "actual-batch-envelope-token"
        val values = installMapBackedPrefs(mapOf(Constants.PREF_DIAG_BROADCAST_TOKEN to token))
        val startedAt = System.currentTimeMillis() - 1_000L
        val generation = 345L
        val start = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
                Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                Constants.EXTRA_DIAGNOSTICS_RUN_ID to "actual-batch-run",
                Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
                Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
                Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
            ),
        )
        val event = bundle(
            eventValues(token, "actual-batch-run", startedAt, generation, 1L) +
                (Constants.PREF_DIAG_FEATURE_HOOK_STRING to true),
        )
        val envelope = bundle(
            mapOf(
                Constants.EXTRA_DIAGNOSTICS_METHOD to Constants.METHOD_RECORD_DIAGNOSTICS_BATCH,
                Constants.EXTRA_DIAGNOSTICS_BATCH_START to start,
                Constants.EXTRA_DIAGNOSTICS_BATCH_EVENT_METHOD to Constants.METHOD_RECORD_DIAGNOSTICS,
                Constants.EXTRA_DIAGNOSTICS_BATCH_EVENT to event,
            ),
        )
        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(Constants.ACTION_RECORD_DIAGNOSTICS)
        whenever(intent.extras).thenReturn(envelope)

        receiver.onReceive(mockContext, intent)

        assertEquals("actual-batch-run", values[Constants.PREF_DIAG_ACTIVE_RUN_ID])
        assertEquals(true, values[Constants.PREF_DIAG_FEATURE_HOOK_STRING])
        verify(mockEditor, times(1)).apply()
    }

    private fun broadcastIntent(method: String, values: Map<String, Any?>): Intent {
        val extras = bundle(values + (Constants.EXTRA_DIAGNOSTICS_METHOD to method))
        return mock<Intent>().also { intent ->
            whenever(intent.action).thenReturn(Constants.ACTION_RECORD_DIAGNOSTICS)
            whenever(intent.extras).thenReturn(extras)
        }
    }

    private fun eventValues(
        token: String,
        runId: String,
        startedAt: Long,
        generation: Long,
        sequence: Long,
    ): Map<String, Any?> = mapOf(
        Constants.EXTRA_DIAGNOSTICS_TOKEN to token,
        Constants.EXTRA_DIAGNOSTICS_ORIGIN to Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
        Constants.EXTRA_DIAGNOSTICS_RUN_ID to runId,
        Constants.EXTRA_DIAGNOSTICS_RUN_STARTED_AT to startedAt,
        Constants.EXTRA_DIAGNOSTICS_RUN_GENERATION to generation,
        Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_COUNT to 1,
        Constants.EXTRA_DIAGNOSTICS_EVENT_SEQUENCE to sequence,
    )

    @Test
    fun `API 34 exact sent package and UID ownership are handed to store as verified origin`() {
        val token = "receiver-api34-token"
        val startedAt = System.currentTimeMillis() - 1_000L
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn(token)

        val identity = api34Identity()
        assertTrue(identity.requireVerifiedOrigin)
        assertTrue(identity.callingPackages!!.contains(Constants.PACKAGE_NAME_GOOGLE_PHOTOS))
        assertTrue(
            receiver.applyWithSenderIdentity(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                api34RunStart(token, startedAt),
                identity,
            ),
        )

        verify(mockEditor).putString(Constants.PREF_DIAG_ACTIVE_PACKAGE, Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        verify(mockEditor).putString(Constants.PREF_DIAG_ACTIVE_RUN_ID, "api34-run")
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_ACTIVE_ORIGIN_VERIFIED, true)
        verify(mockEditor).putLong(Constants.PREF_DIAG_MODULE_LOADED_AT, startedAt)
        verify(mockEditor).apply()
    }

    @Test
    fun `API 34 receiver handoff rejects mismatched sent package or unowned sent package`() {
        val token = "receiver-api34-token"
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn(token)
        val extras = api34RunStart(token, System.currentTimeMillis() - 1_000L)

        val sharedUidSibling = api34Identity(sentPackage = "com.example.shared")
        assertTrue(sharedUidSibling.requireVerifiedOrigin)
        assertTrue(sharedUidSibling.callingPackages!!.contains("com.example.shared"))
        assertFalse(
            receiver.applyWithSenderIdentity(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                extras,
                sharedUidSibling,
            ),
        )

        val packageNotOwnedByUid = api34Identity(
            sentPackage = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            packagesForUid = arrayOf("com.example.shared"),
        )
        assertTrue(packageNotOwnedByUid.requireVerifiedOrigin)
        assertFalse(
            receiver.applyWithSenderIdentity(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                extras,
                packageNotOwnedByUid,
            ),
        )
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `API 34 identity lookup failure cannot downgrade to token-only provenance`() {
        val token = "receiver-legacy-token"
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull())).thenReturn(token)
        val identity = DiagnosticsOriginPolicy.unavailableApi34BroadcastIdentity()

        assertTrue(identity.requireVerifiedOrigin)
        assertFalse(identity.allowTokenOnlyUnverifiedOrigin)
        assertFalse(
            receiver.applyWithSenderIdentity(
                mockContext,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                api34RunStart(token, System.currentTimeMillis() - 1_000L),
                identity,
            ),
        )
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `token-only broadcast does not persist package-specific VERIFY as verified evidence`() {
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull()))
            .thenReturn("test-broadcast-token")

        val extras = mock<Bundle>()
        val keys = setOf(
            Constants.PREF_DIAG_VERIFY_AT,
            Constants.PREF_DIAG_VERIFY_DEVICE,
            Constants.PREF_DIAG_VERIFY_PACKAGE,
            Constants.PREF_DIAG_VERIFY_OK,
            Constants.PREF_DIAG_VERIFY_NATIVE_READY,
            Constants.PREF_DIAG_VERIFY_SYSPROPS,
        )
        whenever(extras.keySet()).thenReturn(keys)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_AT)).thenReturn(123456789L)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_DEVICE)).thenReturn("Pixel XL")
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_OK)).thenReturn(true)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_NATIVE_READY)).thenReturn(true)
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_SYSPROPS)).thenReturn(false)
        whenever(extras.getString(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn(Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("test-broadcast-token")
        stubActiveRun(extras)

        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(Constants.ACTION_RECORD_DIAGNOSTICS)
        whenever(intent.getStringExtra(Constants.EXTRA_DIAGNOSTICS_METHOD)).thenReturn(Constants.METHOD_RECORD_VERIFY)
        whenever(intent.extras).thenReturn(extras)

        receiver.onReceive(mockContext, intent)

        verify(mockEditor, never()).putLong(Constants.PREF_DIAG_VERIFY_AT, 123456789L)
        verify(mockEditor, never()).putString(Constants.PREF_DIAG_VERIFY_PACKAGE, Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        verify(mockEditor, never()).putBoolean(Constants.PREF_DIAG_VERIFY_OK, true)
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `token-only broadcast cannot clear verified run state`() {
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull()))
            .thenReturn("test-broadcast-token")

        val extras = mock<Bundle>()
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("test-broadcast-token")
        stubActiveRun(extras)

        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(Constants.ACTION_RECORD_DIAGNOSTICS)
        whenever(intent.getStringExtra(Constants.EXTRA_DIAGNOSTICS_METHOD)).thenReturn(Constants.METHOD_CLEAR_VERIFY)
        whenever(intent.extras).thenReturn(extras)

        receiver.onReceive(mockContext, intent)

        verify(mockEditor, never()).remove(Constants.PREF_DIAG_VERIFY_AT)
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `onReceive rejects broadcast when no token provisioned yet (fail-closed)`() {
        // No PREF_DIAG_BROADCAST_TOKEN stubbed — fresh install before provisioning.
        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(setOf(Constants.PREF_DIAG_VERIFY_DEVICE))
        whenever(extras.get(Constants.PREF_DIAG_VERIFY_DEVICE)).thenReturn("Pixel XL")

        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(Constants.ACTION_RECORD_DIAGNOSTICS)
        whenever(intent.getStringExtra(Constants.EXTRA_DIAGNOSTICS_METHOD)).thenReturn(Constants.METHOD_RECORD_VERIFY)
        whenever(intent.extras).thenReturn(extras)

        receiver.onReceive(mockContext, intent)

        verify(mockEditor, never()).apply()
    }

    @Test
    fun `onReceive rejects broadcast reporting denylisted package`() {
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull()))
            .thenReturn("test-broadcast-token")

        val extras = mock<Bundle>()
        whenever(extras.getString(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn("app.grapheneos.gmscompat")
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("test-broadcast-token")

        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(Constants.ACTION_RECORD_DIAGNOSTICS)
        whenever(intent.getStringExtra(Constants.EXTRA_DIAGNOSTICS_METHOD)).thenReturn(Constants.METHOD_RECORD_VERIFY)
        whenever(intent.extras).thenReturn(extras)

        receiver.onReceive(mockContext, intent)

        verify(mockEditor, never()).apply()
    }

    @Test
    fun `token-only broadcast does not persist even allowlisted package-specific outcome keys`() {
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull()))
            .thenReturn("test-broadcast-token")

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
        whenever(extras.get(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("test-broadcast-token")
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("test-broadcast-token")
        stubActiveRun(extras)

        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(Constants.ACTION_RECORD_DIAGNOSTICS)
        whenever(intent.getStringExtra(Constants.EXTRA_DIAGNOSTICS_METHOD)).thenReturn(Constants.METHOD_RECORD_VERIFY)
        whenever(intent.extras).thenReturn(extras)

        receiver.onReceive(mockContext, intent)

        verify(mockEditor, never()).putLong(Constants.PREF_DIAG_VERIFY_AT, 123L)
        verify(mockEditor, never()).putString(Constants.PREF_DIAG_VERIFY_DEVICE, "Pixel XL")
        verify(mockEditor, never()).putString(eq(Constants.EXTRA_DIAGNOSTICS_METHOD), any())
        verify(mockEditor, never()).putString(eq(Constants.EXTRA_DIAGNOSTICS_TOKEN), any())
        verify(mockEditor, never()).putString(eq("method"), any())
        verify(mockEditor, never()).putString(eq("token"), any())
        verify(mockEditor, never()).apply()
    }

    @Test
    fun `token-only broadcast cannot persist package-specific lifecycle state`() {
        whenever(mockPrefs.getString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), anyOrNull()))
            .thenReturn("test-broadcast-token")

        val extras = mock<Bundle>()
        whenever(extras.keySet()).thenReturn(
            setOf(
                Constants.PREF_DIAG_MODULE_LOADED_AT,
                Constants.PREF_DEVICE_TO_SPOOF
            )
        )
        whenever(extras.get(Constants.PREF_DIAG_MODULE_LOADED_AT)).thenReturn(999L)
        whenever(extras.get(Constants.PREF_DEVICE_TO_SPOOF)).thenReturn("HACKED_DEVICE")
        whenever(extras.getString(Constants.EXTRA_DIAGNOSTICS_TOKEN)).thenReturn("test-broadcast-token")
        stubActiveRun(extras, startedAt = 123456789L)

        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(Constants.ACTION_RECORD_DIAGNOSTICS)
        whenever(intent.getStringExtra(Constants.EXTRA_DIAGNOSTICS_METHOD)).thenReturn(Constants.METHOD_RECORD_DIAGNOSTICS)
        whenever(intent.extras).thenReturn(extras)

        receiver.onReceive(mockContext, intent)

        verify(mockEditor, never()).putLong(Constants.PREF_DIAG_MODULE_LOADED_AT, 999L)
        verify(mockEditor, never()).putString(eq(Constants.PREF_DEVICE_TO_SPOOF), any())
        verify(mockEditor, never()).apply()
    }
}
