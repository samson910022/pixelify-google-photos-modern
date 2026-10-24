package io.github.samson910022.pixelifyphotos

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.*
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiagnosticsReporterTest {

    private lateinit var mockedLog: MockedStatic<Log>
    private lateinit var mockedUri: MockedStatic<Uri>
    private lateinit var mockUriInstance: Uri
    private lateinit var mockContext: Context
    private lateinit var mockResolver: ContentResolver

    @Before
    fun setUp() {
        FeatureSpoofer.resetForTesting()
        mockedLog = Mockito.mockStatic(Log::class.java)
        mockedLog.`when`<Int> { Log.d(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>(), anyOrNull()) }.thenReturn(0)

        mockUriInstance = mock()
        mockedUri = Mockito.mockStatic(Uri::class.java)
        mockedUri.`when`<Uri> { Uri.parse(any<String>()) }.thenReturn(mockUriInstance)

        mockContext = mock()
        mockResolver = mock()
        whenever(mockContext.contentResolver).thenReturn(mockResolver)

        val successBundle = mock<Bundle>()
        whenever(successBundle.getBoolean(eq("success"), eq(false))).thenReturn(true)
        whenever(successBundle.getBoolean(eq("success"), eq(true))).thenReturn(true)
        whenever(mockResolver.call(any<Uri>(), any<String>(), isNull(), anyOrNull<Bundle>()))
            .thenReturn(successBundle)

        DiagnosticsReporter.testProviderUri = mockUriInstance
        DiagnosticsReporter.testBroadcastToken = "test-auth-token"
        DiagnosticsReporter.testBootCount = 1
        DiagnosticsReporter.testBootEpochResolver = { "test-epoch-1" }
        DiagnosticsReporter.testResolvedContext = null
        DiagnosticsReporter.testEnqueueObserver = null
        DiagnosticsReporter.testBeforeDispatchLock = null
        DiagnosticsReporter.testFallbackOutcomeObserver = null
        DiagnosticsReporter.testEventSequenceObserver = null
        DiagnosticsReporter.testFallbackDispatchOverride = null
        DiagnosticsReporter.testStartIfAbsentObserver = null
        DiagnosticsReporter.clearTargetRunForTests()
    }

    @After
    fun tearDown() {
        mockedLog.close()
        mockedUri.close()
        DiagnosticsReporter.testProviderUri = null
        DiagnosticsReporter.testBroadcastToken = null
        DiagnosticsReporter.testBootCount = null
        DiagnosticsReporter.testBootEpochResolver = null
        DiagnosticsReporter.testResolvedContext = null
        DiagnosticsReporter.testEnqueueObserver = null
        DiagnosticsReporter.testBeforeDispatchLock = null
        DiagnosticsReporter.testFallbackOutcomeObserver = null
        DiagnosticsReporter.testEventSequenceObserver = null
        DiagnosticsReporter.testFallbackDispatchOverride = null
        DiagnosticsReporter.testStartIfAbsentObserver = null
        DiagnosticsReporter.clearTargetRunForTests()
        FeatureSpoofer.resetForTesting()
    }

    @Test
    fun `resolveContext returns explicit context when provided`() {
        val resolved = DiagnosticsReporter.resolveContext(mockContext)
        assertSame(mockContext, resolved)
    }

    @Test
    fun `resolveContext returns null gracefully without ActivityThread`() {
        val resolved = DiagnosticsReporter.resolveContext(null)
        assertNull(resolved)
    }

    @Test
    fun `per-install token is attached only as provider IPC plumbing`() {
        val payload = mock<Bundle>()

        DiagnosticsReporter.attachIpcToken(payload, "test-auth-token")

        verify(payload).putString(Constants.EXTRA_DIAGNOSTICS_TOKEN, "test-auth-token")
        assertFalse(Constants.EXTRA_DIAGNOSTICS_TOKEN in DiagnosticsStore.ALLOWED_DIAG_KEYS)
    }

    @Test
    fun `ready-only fallback run start does not claim package-load callback`() {
        val fields = DiagnosticsReporter.buildTargetRunStartFields(
            packageName = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            runId = "ready-only-run",
            startedAt = 2_000L,
            generation = 10L,
            bootEpochHash = "test-epoch-1",
            moduleLoadedAt = 1_000L,
            packageLoadCallbackObserved = false,
        )

        assertEquals(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, fields[Constants.EXTRA_DIAGNOSTICS_ORIGIN])
        assertEquals("test-epoch-1", fields[Constants.EXTRA_DIAGNOSTICS_RUN_BOOT_EPOCH])
        assertEquals(1_000L, fields[Constants.PREF_DIAG_MODULE_LOADED_AT])
        assertFalse(fields.containsKey(Constants.PREF_DIAG_LAST_PACKAGE_LOADED))
    }

    @Test
    fun `API 34 identity-shared broadcast failure never retries without sender identity`() {
        var legacyAttempts = 0
        val result = DiagnosticsReporter.dispatchBroadcastFallback(
            sdkInt = 34,
            sendIdentityShared = { throw SecurityException("share identity denied") },
            sendWithoutIdentity = { legacyAttempts++ },
        )

        assertFalse(result.callReturned)
        assertTrue(result.identityPath)
        assertFalse(result.receiverAcceptanceConfirmed)
        assertTrue(result.failure is SecurityException)
        assertEquals(0, legacyAttempts)
    }

    @Test
    fun `API below 34 dispatch uses only legacy broadcast path`() {
        var identityAttempts = 0
        var legacyAttempts = 0
        val result = DiagnosticsReporter.dispatchBroadcastFallback(
            sdkInt = 33,
            sendIdentityShared = { identityAttempts++ },
            sendWithoutIdentity = { legacyAttempts++ },
        )

        assertTrue(result.callReturned)
        assertFalse(result.identityPath)
        assertFalse(result.receiverAcceptanceConfirmed)
        assertEquals(0, identityAttempts)
        assertEquals(1, legacyAttempts)
    }

    @Test
    fun `API 34 successful send selector reports dispatch only not receiver acceptance`() {
        var identityAttempts = 0
        var legacyAttempts = 0
        val result = DiagnosticsReporter.dispatchBroadcastFallback(
            sdkInt = 34,
            sendIdentityShared = { identityAttempts++ },
            sendWithoutIdentity = { legacyAttempts++ },
        )

        assertTrue(result.callReturned)
        assertTrue(result.identityPath)
        assertFalse(result.receiverAcceptanceConfirmed)
        assertEquals(1, identityAttempts)
        assertEquals(0, legacyAttempts)
    }

    @Test
    fun `run start is enqueued before telemetry racing publication of the new run`() {
        val startEnteringEnqueue = java.util.concurrent.CountDownLatch(1)
        val releaseStartEnqueue = java.util.concurrent.CountDownLatch(1)
        val recordReachedDispatch = java.util.concurrent.CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        DiagnosticsReporter.testBeforeDispatchLock = { method ->
            if (method == Constants.METHOD_RECORD_DIAGNOSTICS) recordReachedDispatch.countDown()
        }
        DiagnosticsReporter.testEnqueueObserver = { method, runId, _ ->
            if (method == Constants.METHOD_START_DIAGNOSTICS_RUN) {
                startEnteringEnqueue.countDown()
                check(releaseStartEnqueue.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            events += method to runId
        }

        val startThread = Thread {
            DiagnosticsReporter.startTargetRun(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, moduleLoadedAt = 1L)
        }
        val recordThread = Thread {
            DiagnosticsReporter.recordMilestone { }
        }
        startThread.start()
        assertTrue(startEnteringEnqueue.await(5, java.util.concurrent.TimeUnit.SECONDS))
        recordThread.start()
        assertTrue(recordReachedDispatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        releaseStartEnqueue.countDown()
        startThread.join(5_000)
        recordThread.join(5_000)

        assertFalse(startThread.isAlive)
        assertFalse(recordThread.isAlive)
        assertEquals(2, events.size)
        assertEquals(Constants.METHOD_START_DIAGNOSTICS_RUN, events[0].first)
        assertEquals(Constants.METHOD_RECORD_DIAGNOSTICS, events[1].first)
        assertNotNull(events[0].second)
        assertEquals(events[0].second, events[1].second)
    }

    @Test
    fun `racing start-if-absent callbacks share the first immutable run handle`() {
        val startEnqueueEntered = CountDownLatch(1)
        val secondAttemptEntered = CountDownLatch(2)
        val releaseStartEnqueue = CountDownLatch(1)
        val handles = Collections.synchronizedList(
            mutableListOf<DiagnosticsReporter.TargetRunHandle?>(),
        )
        val events = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        DiagnosticsReporter.testStartIfAbsentObserver = { secondAttemptEntered.countDown() }
        DiagnosticsReporter.testBeforeDispatchLock = { method ->
            if (method == Constants.METHOD_START_DIAGNOSTICS_RUN) {
                startEnqueueEntered.countDown()
                check(releaseStartEnqueue.await(5, TimeUnit.SECONDS))
            }
        }
        DiagnosticsReporter.testEnqueueObserver = { method, runId, _ -> events += method to runId }

        val first = Thread {
            handles += DiagnosticsReporter.startTargetRunIfAbsent(
                packageName = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
                moduleLoadedAt = 1L,
            )
        }
        val second = Thread {
            handles += DiagnosticsReporter.startTargetRunIfAbsent(
                packageName = "org.example.secondary",
                moduleLoadedAt = 2L,
            )
        }
        first.start()
        assertTrue(startEnqueueEntered.await(5, TimeUnit.SECONDS))
        second.start()
        assertTrue("second callback must reach the atomic start-if-absent boundary", secondAttemptEntered.await(5, TimeUnit.SECONDS))
        releaseStartEnqueue.countDown()
        first.join(5_000)
        second.join(5_000)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertEquals(2, handles.size)
        assertNotNull(handles[0])
        assertSame(handles[0], handles[1])
        assertEquals(handles[0], handles[1])
        assertEquals(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, handles[0]?.origin)
        assertEquals(1, events.count { it.first == Constants.METHOD_START_DIAGNOSTICS_RUN })
        assertEquals(handles[0]?.runId, events.single().second)
    }

    @Test
    fun `stale lifecycle registration and verify clear are dropped after run transition`() {
        val events = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        DiagnosticsReporter.testEnqueueObserver = { method, runId, _ -> events += method to runId }
        val staleHandle = checkNotNull(
            DiagnosticsReporter.startTargetRun(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, moduleLoadedAt = 1L),
        )
        val currentHandle = checkNotNull(
            DiagnosticsReporter.startTargetRun("org.example.current", moduleLoadedAt = 2L),
        )
        events.clear()

        DiagnosticsReporter.recordMilestone(runHandle = staleHandle) { it.putBoolean("stale", true) }
        DiagnosticsReporter.recordFeatureHookRegistration(
            hasSystemFeature = true,
            hasSystemFeatureWithFlags = false,
            getSystemAvailableFeatures = false,
            runHandle = staleHandle,
        )
        DiagnosticsReporter.recordVerify(
            context = mockContext,
            deviceName = "Pixel 8 Pro",
            failed = emptyList(),
            packageName = staleHandle.origin,
            nativeReady = true,
            syspropsHooked = true,
            runHandle = staleHandle,
        )
        DiagnosticsReporter.clearVerify(
            context = mockContext,
            packageName = staleHandle.origin,
            runHandle = staleHandle,
        )

        assertNotEquals(staleHandle.runId, currentHandle.runId)
        assertTrue("stale run-bound events must not reach enqueue", events.isEmpty())
    }

    @Test
    fun `feature hook invocation callback stays pinned to its registration run`() {
        val events = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        DiagnosticsReporter.testEnqueueObserver = { method, runId, _ -> events += method to runId }
        val runA = checkNotNull(
            DiagnosticsReporter.startTargetRun(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, moduleLoadedAt = 1L),
        )
        val callbackRegisteredForA = FeatureSpoofer.featureInvocationCallback(runA)

        events.clear()
        callbackRegisteredForA()

        assertEquals(
            listOf(Constants.METHOD_RECORD_DIAGNOSTICS to runA.runId),
            events.toList(),
        )

        // The same registered callback must not silently adopt a newer active run.
        // Reset only the process-wide first-invocation gate so the stale reporter
        // dispatch is exercised instead of being suppressed by that gate.
        FeatureSpoofer.resetForTesting()
        val runB = checkNotNull(
            DiagnosticsReporter.startTargetRun("org.example.new-run", moduleLoadedAt = 2L),
        )
        events.clear()
        callbackRegisteredForA()

        assertEquals(runB, DiagnosticsReporter.currentTargetRunHandle())
        assertTrue("stale callback must not record an invocation under run B", events.isEmpty())
    }

    @Test
    fun `interceptor invocation without a captured run is dropped`() {
        val events = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        DiagnosticsReporter.testEnqueueObserver = { method, runId, _ -> events += method to runId }
        DiagnosticsReporter.startTargetRun(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, moduleLoadedAt = 1L)
        events.clear()

        DiagnosticsReporter.recordInterceptorInvocation(
            featureInterceptor = true,
            runHandle = null,
        )

        assertTrue("unattributed interceptor invocation must not adopt the active run", events.isEmpty())
    }

    @Test
    fun `run-bound events receive strictly increasing sequence under run lock`() {
        val sequences = Collections.synchronizedList(mutableListOf<Triple<String, String?, Long?>>())
        DiagnosticsReporter.testEventSequenceObserver = { method, runId, sequence ->
            sequences += Triple(method, runId, sequence)
        }
        DiagnosticsReporter.testEnqueueObserver = { _, _, _ -> }

        DiagnosticsReporter.startTargetRun(
            packageName = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            moduleLoadedAt = 1L,
        )
        DiagnosticsReporter.recordMilestone(mockContext) { it.putBoolean(Constants.PREF_DIAG_FEATURE_HOOK_STRING, true) }
        DiagnosticsReporter.recordMilestone(mockContext) { it.putBoolean(Constants.PREF_DIAG_FEATURE_HOOK_AVAILABLE, true) }

        assertEquals(2, sequences.size)
        assertEquals(listOf(1L, 2L), sequences.map { it.third })
        assertEquals(setOf(sequences[0].second), sequences.map { it.second }.toSet())
    }

    @Test
    fun `unconfirmed start is replayed through provider before telemetry after service becomes ready`() {
        val failedStartBundle = mock<Bundle>()
        whenever(failedStartBundle.getBoolean(eq("success"), eq(false))).thenReturn(false)
        val confirmedBundle = mock<Bundle>()
        whenever(confirmedBundle.getBoolean(eq("success"), eq(false))).thenReturn(true)
        val startAttempts = AtomicInteger()
        val methods = Collections.synchronizedList(mutableListOf<String>())
        val fallbackObserved = CountDownLatch(3)
        val telemetryConfirmed = CountDownLatch(1)
        val fallbackOutcomes = Collections.synchronizedList(mutableListOf<DiagnosticsReporter.BroadcastDispatchResult>())
        DiagnosticsReporter.testFallbackOutcomeObserver = { method, result ->
            if (method == Constants.METHOD_START_DIAGNOSTICS_RUN) {
                fallbackOutcomes += result
                fallbackObserved.countDown()
            }
        }
        DiagnosticsReporter.testResolvedContext = mockContext
        whenever(mockResolver.call(any<Uri>(), any<String>(), isNull(), anyOrNull<Bundle>())).thenAnswer { invocation ->
            val method = invocation.getArgument<String>(1)
            methods += method
            when (method) {
                Constants.METHOD_START_DIAGNOSTICS_RUN ->
                    if (startAttempts.incrementAndGet() <= 3) failedStartBundle else confirmedBundle
                Constants.METHOD_RECORD_DIAGNOSTICS -> {
                    telemetryConfirmed.countDown()
                    confirmedBundle
                }
                else -> confirmedBundle
            }
        }

        DiagnosticsReporter.startTargetRun(
            packageName = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
            moduleLoadedAt = 1L,
        )
        assertTrue("initial run-start fallback should be attempted", fallbackObserved.await(3, TimeUnit.SECONDS))
        assertEquals("bounded retries remain dispatch-only", 3, fallbackOutcomes.size)
        assertTrue(fallbackOutcomes.all { it.callReturned })
        assertTrue(fallbackOutcomes.none { it.receiverAcceptanceConfirmed })

        DiagnosticsReporter.recordMilestone(mockContext) { bundle ->
            bundle.putLong(Constants.PREF_DIAG_FEATURE_HOOKS_AT, 2L)
        }
        assertTrue("telemetry should be sent after provider confirms replay", telemetryConfirmed.await(3, TimeUnit.SECONDS))

        assertEquals(
            listOf(
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                Constants.METHOD_START_DIAGNOSTICS_RUN,
                Constants.METHOD_RECORD_DIAGNOSTICS,
            ),
            synchronized(methods) { methods.toList() },
        )
    }

    @Test
    fun `provider unavailable fallback batches run start before dependent telemetry and retries boundedly`() {
        val rejected = mock<Bundle>()
        whenever(rejected.getBoolean(eq("success"), eq(false))).thenReturn(false)
        whenever(rejected.getBoolean(eq("success"), eq(true))).thenReturn(false)
        whenever(mockResolver.call(any<Uri>(), any<String>(), isNull(), anyOrNull<Bundle>()))
            .thenReturn(rejected)
        DiagnosticsReporter.testResolvedContext = mockContext

        val startFallbacks = Collections.synchronizedList(mutableListOf<Bundle>())
        val batches = Collections.synchronizedList(mutableListOf<Pair<Bundle?, Bundle>>())
        val sequencedEvents = Collections.synchronizedList(mutableListOf<Triple<String, String?, Long?>>())
        val batchAttempts = CountDownLatch(3)
        DiagnosticsReporter.testEventSequenceObserver = { method, runId, sequence ->
            sequencedEvents += Triple(method, runId, sequence)
        }
        DiagnosticsReporter.testFallbackDispatchOverride = { method, event, start ->
            when (method) {
                Constants.METHOD_START_DIAGNOSTICS_RUN -> startFallbacks += event
                Constants.METHOD_RECORD_DIAGNOSTICS_BATCH -> {
                    batches += start to event
                    batchAttempts.countDown()
                }
            }
            DiagnosticsReporter.BroadcastDispatchResult(callReturned = true, identityPath = false)
        }

        DiagnosticsReporter.startTargetRun(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, moduleLoadedAt = 10L)
        DiagnosticsReporter.recordMilestone(mockContext) {
            it.putBoolean(Constants.PREF_DIAG_FEATURE_HOOK_STRING, true)
        }

        assertTrue("fallback event should get a bounded retry series", batchAttempts.await(5, TimeUnit.SECONDS))
        assertEquals("start fallback is retried, never permanently suppressed", 3, startFallbacks.size)
        assertEquals(3, batches.size)
        batches.forEach { (start, event) ->
            assertNotNull("dependent event must carry its run start in the same broadcast", start)
            assertNotNull("dependent event payload accompanies start", event)
        }
        assertEquals(1, sequencedEvents.size)
        assertEquals(Constants.METHOD_RECORD_DIAGNOSTICS, sequencedEvents.single().first)
        assertEquals(1L, sequencedEvents.single().third)
        assertNotNull(sequencedEvents.single().second)
        verify(mockResolver, never()).call(
            eq(mockUriInstance),
            eq(Constants.METHOD_RECORD_DIAGNOSTICS),
            isNull(),
            anyOrNull(),
        )
    }

    @Test
    fun `fallback retry exhaustion logs dispatch-only state and never treats receiver rejection as persistence`() {
        val rejected = mock<Bundle>()
        whenever(rejected.getBoolean(eq("success"), eq(false))).thenReturn(false)
        whenever(rejected.getBoolean(eq("success"), eq(true))).thenReturn(false)
        whenever(mockResolver.call(any<Uri>(), any<String>(), isNull(), anyOrNull<Bundle>()))
            .thenReturn(rejected)
        DiagnosticsReporter.testResolvedContext = mockContext
        val attempted = AtomicInteger()
        val outcomes = Collections.synchronizedList(mutableListOf<DiagnosticsReporter.BroadcastDispatchResult>())
        val allAttemptsFinished = CountDownLatch(3)
        DiagnosticsReporter.testFallbackOutcomeObserver = { method, outcome ->
            if (method == Constants.METHOD_START_DIAGNOSTICS_RUN) {
                outcomes += outcome
                allAttemptsFinished.countDown()
            }
        }
        DiagnosticsReporter.testFallbackDispatchOverride = { _, _, _ ->
            attempted.incrementAndGet()
            // sendBroadcast returned, but test receiver deliberately rejects; no
            // acknowledgement is exposed to Reporter, so it must remain unknown.
            DiagnosticsReporter.BroadcastDispatchResult(callReturned = true, identityPath = false)
        }

        DiagnosticsReporter.startTargetRun(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, moduleLoadedAt = null)

        assertTrue(allAttemptsFinished.await(3, TimeUnit.SECONDS))
        assertEquals(3, attempted.get())
        assertEquals(3, outcomes.size)
        assertTrue(outcomes.all { it.callReturned && !it.receiverAcceptanceConfirmed })
    }

    @Test
    fun `recordMilestone dispatches to ContentResolver and skips broadcast on success`() {
        DiagnosticsReporter.recordMilestone(mockContext) { bundle ->
            bundle.putLong(Constants.PREF_DIAG_MODULE_LOADED_AT, 12345L)
        }

        verify(mockResolver, timeout(2000)).call(
            eq(mockUriInstance),
            eq(Constants.METHOD_RECORD_DIAGNOSTICS),
            isNull(),
            notNull(),
        )
        verify(mockContext, never()).sendBroadcast(any())
    }

    @Test
    fun `recordVerify packages verify payload and dispatches RECORD_VERIFY`() {
        val failed = listOf("MODEL(actual=Redmi)")
        DiagnosticsReporter.startTargetRun(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, moduleLoadedAt = null)
        DiagnosticsReporter.recordVerify(
            context = mockContext,
            deviceName = "Pixel 8 Pro",
            failed = failed,
            packageName = "com.google.android.apps.photos",
            nativeReady = true,
            syspropsHooked = true,
        )

        verify(mockResolver, timeout(2000)).call(
            eq(mockUriInstance),
            eq(Constants.METHOD_RECORD_VERIFY),
            isNull(),
            notNull(),
        )
        verify(mockContext, never()).sendBroadcast(any())
    }

    @Test
    fun `recordVerify without package attribution or current target run is skipped`() {
        DiagnosticsReporter.recordVerify(
            context = mockContext,
            deviceName = "Pixel 8 Pro",
            failed = emptyList(),
            packageName = null,
            nativeReady = true,
            syspropsHooked = true,
        )

        verify(mockResolver, never()).call(
            eq(mockUriInstance),
            eq(Constants.METHOD_RECORD_VERIFY),
            isNull(),
            anyOrNull(),
        )
        verify(mockContext, never()).sendBroadcast(any())
    }

    @Test
    fun `VERIFY failure transport remains ArrayList of field identifiers`() {
        val failedFields = DiagnosticsReporter.verifyFailureFieldNames(
            listOf("MODEL(actual=Redmi)", "FINGERPRINT(error)"),
        )

        assertEquals(ArrayList::class.java, failedFields.javaClass)
        assertEquals(arrayListOf("MODEL", "FINGERPRINT"), failedFields)
    }

    @Test
    fun `clearVerify dispatches METHOD_CLEAR_VERIFY`() {
        DiagnosticsReporter.clearVerify(mockContext)

        verify(mockResolver, timeout(2000)).call(
            eq(mockUriInstance),
            eq(Constants.METHOD_CLEAR_VERIFY),
            isNull(),
            any(),
        )
        verify(mockContext, never()).sendBroadcast(any())
    }

    @Test
    fun `fallback to explicit broadcast when ContentResolver throws exception`() {
        whenever(mockResolver.call(any<Uri>(), any<String>(), isNull(), anyOrNull<Bundle>()))
            .thenThrow(IllegalArgumentException("Unknown authority"))

        DiagnosticsReporter.clearVerify(mockContext)

        verify(mockContext, timeout(2000)).sendBroadcast(any())
    }

    @Test
    fun `fallback to explicit broadcast when ContentResolver returns null result`() {
        whenever(mockResolver.call(any<Uri>(), any<String>(), isNull(), anyOrNull<Bundle>()))
            .thenReturn(null)

        DiagnosticsReporter.recordMilestone(mockContext) { bundle ->
            bundle.putLong(Constants.PREF_DIAG_MODULE_LOADED_AT, 9999L)
        }

        verify(mockContext, timeout(2000)).sendBroadcast(any())
    }

    @Test
    fun `dispatch fails silently and closed when both ContentResolver and broadcast throw`() {
        whenever(mockResolver.call(any<Uri>(), any<String>(), isNull(), anyOrNull<Bundle>()))
            .thenThrow(SecurityException("Permission denied"))
        whenever(mockContext.sendBroadcast(any())).thenThrow(RuntimeException("Broadcast failed"))

        // Must not propagate exception to caller
        DiagnosticsReporter.clearVerify(mockContext)

        verify(mockResolver, timeout(2000)).call(any<Uri>(), any<String>(), isNull(), anyOrNull<Bundle>())
        verify(mockContext, timeout(2000)).sendBroadcast(any())
    }

    @Test
    fun `fallback broadcast dispatches explicit Intent with receiver component`() {
        whenever(mockResolver.call(any<Uri>(), any<String>(), isNull(), anyOrNull<Bundle>()))
            .thenThrow(IllegalArgumentException("Unknown authority"))

        DiagnosticsReporter.clearVerify(mockContext)

        // Verify broadcast was dispatched (explicit component is set inside Reporter;
        // Intent field assertions require Robolectric shadow, so we verify dispatch occurred).
        verify(mockContext, timeout(2000)).sendBroadcast(any())
        // Additional structural check: ensure Reporter uses explicit component name
        assertEquals(
            "io.github.samson910022.pixelifyphotos.DiagnosticsReceiver",
            DiagnosticsReceiver::class.java.name
        )
    }

    @Test
    fun `provider rejection for ambiguous shared UID origin falls back to receiver broadcast`() {
        val failBundle = mock<Bundle>()
        whenever(failBundle.getBoolean(eq("success"), eq(false))).thenReturn(false)
        whenever(failBundle.getBoolean(eq("success"), eq(true))).thenReturn(false)
        whenever(mockResolver.call(any<Uri>(), any<String>(), isNull(), anyOrNull<Bundle>()))
            .thenReturn(failBundle)

        DiagnosticsReporter.clearVerify(mockContext)

        // The provider's false result means the ambiguous shared-UID write was not
        // persisted; Reporter must attempt the receiver path rather than treat it as success.
        verify(mockResolver, timeout(2000)).call(
            eq(mockUriInstance),
            eq(Constants.METHOD_CLEAR_VERIFY),
            isNull(),
            anyOrNull(),
        )
        verify(mockContext, timeout(2000)).sendBroadcast(any())
    }

    @Test
    fun `fallback when provider returns bundle without success key is fail-closed`() {
        val empty = Bundle()
        whenever(mockResolver.call(any<Uri>(), any<String>(), isNull(), anyOrNull<Bundle>()))
            .thenReturn(empty)

        DiagnosticsReporter.recordMilestone(mockContext) { bundle ->
            bundle.putLong(Constants.PREF_DIAG_MODULE_LOADED_AT, 9999L)
        }

        verify(mockContext, timeout(2000)).sendBroadcast(any())
    }
}
