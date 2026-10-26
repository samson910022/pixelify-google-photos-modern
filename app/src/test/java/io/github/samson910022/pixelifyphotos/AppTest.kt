package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.material.color.DynamicColors
import io.github.libxposed.service.XposedService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray

class AppTest {

    private lateinit var mockedLog: MockedStatic<Log>
    private lateinit var mockedDynamicColors: MockedStatic<DynamicColors>
    private lateinit var app: App
    private lateinit var mockFilePrefs: SharedPreferences
    private lateinit var mockFileEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        mockedLog = Mockito.mockStatic(Log::class.java)
        mockedLog.`when`<Int> { Log.d(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>(), anyOrNull()) }.thenReturn(0)
        // Real Material internals would walk Activity lifecycle registrations against a
        // stub android.jar; intercept the entry point for deterministic JVM behavior.
        mockedDynamicColors = Mockito.mockStatic(DynamicColors::class.java)
        mockFilePrefs = mock()
        mockFileEditor = mock()
        whenever(mockFilePrefs.edit()).thenReturn(mockFileEditor)
        whenever(mockFileEditor.putString(any(), any())).thenReturn(mockFileEditor)
        whenever(mockFileEditor.putBoolean(any(), any())).thenReturn(mockFileEditor)
        whenever(mockFileEditor.putInt(any(), any())).thenReturn(mockFileEditor)
        whenever(mockFileEditor.putStringSet(any(), any())).thenReturn(mockFileEditor)
        whenever(mockFileEditor.commit()).thenReturn(true)
        app = App()
        app.testTokenConvergenceOverride = { true }
        app.testSettingsHandoffOverride = { true }
    }

    @After
    fun tearDown() {
        try {
            setMockXposedService(null)
            clearListeners()
            app.testAfterConvergence = null
            app.testTokenConvergenceOverride = null
            app.testSettingsHandoffOverride = null
        } finally {
            mockedDynamicColors.close()
            mockedLog.close()
        }
    }

    /**
     * Injects a mock XposedService into the [App] companion so bind-state paths can
     * be exercised in JVM unit tests. The fail-fast helper verifies the field is
     * still a non-final static and that the write landed.
     */
    private fun setMockXposedService(service: XposedService?) {
        App.setServiceStateForTests(service, ready = service != null)
    }

    /** Clears pending listeners via the production test seam. */
    private fun clearListeners() {
        App.clearServiceBoundListeners()
        App.clearServiceStateListenersForTests()
    }

    private fun currentListenerCount(): Int =
        TestStatics.getStaticField<MutableList<*>>(App::class.java, "serviceBoundListeners").size

    // =========================================================================
    // 1. Registration before the service binds
    // =========================================================================

    @Test
    fun `addListener before bind does not invoke callback`() {
        var invoked = false
        App.addOnServiceBoundListener { invoked = true }

        assertFalse(invoked)
        assertEquals(1, currentListenerCount())
    }

    @Test
    fun `removeListener before bind prevents invocation on later bind`() {
        var invoked = false
        val listener = { invoked = true }
        App.addOnServiceBoundListener(listener)

        App.removeOnServiceBoundListener(listener)
        app.onServiceBind(mock<XposedService>())

        assertFalse(invoked)
    }

    @Test
    fun `removeListener only removes its own callback and leaves others registered`() {
        var firstInvoked = false
        var secondInvoked = false
        val first = { firstInvoked = true }
        val second = { secondInvoked = true }
        App.addOnServiceBoundListener(first)
        App.addOnServiceBoundListener(second)

        App.removeOnServiceBoundListener(first)
        assertEquals(1, currentListenerCount())

        app.onServiceBind(mock<XposedService>())
        assertFalse(firstInvoked)
        assertTrue(secondInvoked)
    }

    // =========================================================================
    // 2. Bind event behavior
    // =========================================================================

    @Test
    fun `onServiceBind sets mService and notifies pending listener exactly once`() {
        var invocationCount = 0
        App.addOnServiceBoundListener { invocationCount++ }
        val service = mock<XposedService>()

        app.onServiceBind(service)

        assertNotNull(App.mService)
        assertEquals(1, invocationCount)
        assertEquals(0, currentListenerCount())
    }

    @Test
    fun `token convergence failure degrades diagnostics only while settings become ready`() {
        app.testTokenConvergenceOverride = { false }
        var settingsHandoffAttempted = false
        app.testSettingsHandoffOverride = {
            settingsHandoffAttempted = true
            true
        }
        val phases = mutableListOf<ServicePhase>()
        val stateListener: (ServiceReadinessSnapshot) -> Unit = { phases += it.phase }
        App.addOnServiceStateListener(stateListener)
        var boundCallbackInvoked = false
        App.addOnServiceBoundListener { boundCallbackInvoked = true }
        val service = mock<XposedService>()

        app.onServiceBind(service)

        val snapshot = App.serviceReadinessSnapshot()
        assertSame(service, snapshot.service)
        assertTrue(snapshot.connected)
        assertTrue("READY means settings readiness, independent of diagnostics token state", snapshot.ready)
        assertEquals(ServicePhase.READY, snapshot.phase)
        assertTrue(App.isServiceReady())
        assertSame(service, App.readyService())
        assertTrue("settings handoff must still run after a token failure", settingsHandoffAttempted)
        assertTrue("settings readiness must drain bound listeners despite diagnostics failure", boundCallbackInvoked)
        assertEquals(0, currentListenerCount())
        assertEquals(
            listOf(ServicePhase.DISCONNECTED, ServicePhase.CONNECTED_SYNCING, ServicePhase.READY),
            phases,
        )
        mockedLog.verify {
            Log.w(
                eq("Pixelify"),
                eq("Diagnostics IPC token stores did not converge; remote diagnostic results will remain unknown"),
            )
        }
        App.removeOnServiceStateListener(stateListener)
    }

    @Test
    fun `settings convergence failure remains connected error and never becomes ready`() {
        app.testTokenConvergenceOverride = { true }
        app.testSettingsHandoffOverride = { false }
        val phases = mutableListOf<ServicePhase>()
        val stateListener: (ServiceReadinessSnapshot) -> Unit = { phases += it.phase }
        App.addOnServiceStateListener(stateListener)
        var boundCallbackInvoked = false
        App.addOnServiceBoundListener { boundCallbackInvoked = true }
        val service = mock<XposedService>()

        app.onServiceBind(service)

        val snapshot = App.serviceReadinessSnapshot()
        assertSame(service, snapshot.service)
        assertTrue(snapshot.connected)
        assertFalse(snapshot.ready)
        assertEquals(ServicePhase.ERROR, snapshot.phase)
        assertFalse(App.isServiceReady())
        assertNull(App.readyService())
        assertFalse("ready-only listeners must not fire on failed settings convergence", boundCallbackInvoked)
        assertEquals("failed settings convergence must keep the listener queued", 1, currentListenerCount())
        assertEquals(
            listOf(ServicePhase.DISCONNECTED, ServicePhase.CONNECTED_SYNCING, ServicePhase.ERROR),
            phases,
        )
        App.removeOnServiceStateListener(stateListener)
    }

    @Test
    fun `unreachable remote settings keeps service in error even with no pending offline intent`() {
        // A real preference store for the empty handoff is required to distinguish the pre-fix
        // short-circuit (empty pending -> READY) from the post-fix reachability failure.
        val appSpy = spyAppBackedByFileStore()
        val pendingStore = MemorySharedPreferences()
        doReturn(pendingStore).whenever(appSpy).getSharedPreferences(
            eq(OfflineSettingsHandoff.PREFERENCES_FILE),
            eq(Context.MODE_PRIVATE),
        )
        appSpy.testTokenConvergenceOverride = { true }
        appSpy.testSettingsHandoffOverride = null
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)).thenReturn(null)
        var boundCallbackInvoked = false
        App.addOnServiceBoundListener { boundCallbackInvoked = true }

        appSpy.onServiceBind(service)

        val snapshot = App.serviceReadinessSnapshot()
        assertSame(service, snapshot.service)
        assertTrue(snapshot.connected)
        assertFalse("READY must prove the remote settings store is usable", snapshot.ready)
        assertEquals(ServicePhase.ERROR, snapshot.phase)
        assertFalse(App.isServiceReady())
        assertNull(App.readyService())
        assertFalse(boundCallbackInvoked)
        assertEquals(1, currentListenerCount())
        assertTrue("the failed probe must not invent a pending handoff", pendingStore.all.isEmpty())
    }

    @Test
    fun `combined token and settings convergence failure stays connected error`() {
        app.testTokenConvergenceOverride = { false }
        app.testSettingsHandoffOverride = { false }
        var boundCallbackInvoked = false
        App.addOnServiceBoundListener { boundCallbackInvoked = true }
        val service = mock<XposedService>()

        app.onServiceBind(service)

        val snapshot = App.serviceReadinessSnapshot()
        assertSame(service, snapshot.service)
        assertTrue(snapshot.connected)
        assertFalse(snapshot.ready)
        assertEquals(ServicePhase.ERROR, snapshot.phase)
        assertFalse(App.isServiceReady())
        assertNull(App.readyService())
        assertFalse(boundCallbackInvoked)
        assertEquals(1, currentListenerCount())
    }

    @Test
    fun `older same-service bind success cannot override newer failed attempt or drain listeners`() {
        val service = mock<XposedService>()
        val firstAttemptStarted = CountDownLatch(1)
        val releaseFirstAttempt = CountDownLatch(1)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        app.testTokenConvergenceOverride = { true }
        app.testSettingsHandoffOverride = {
            if (attempts.incrementAndGet() == 1) {
                firstAttemptStarted.countDown()
                check(releaseFirstAttempt.await(5, TimeUnit.SECONDS))
                true
            } else {
                false
            }
        }
        var boundCallbackCount = 0
        App.addOnServiceBoundListener { boundCallbackCount++ }

        val olderBind = Thread { app.onServiceBind(service) }
        olderBind.start()
        assertTrue(firstAttemptStarted.await(5, TimeUnit.SECONDS))
        app.onServiceBind(service)
        assertEquals(ServicePhase.ERROR, App.serviceReadinessSnapshot().phase)
        assertEquals(0, boundCallbackCount)

        releaseFirstAttempt.countDown()
        olderBind.join(5_000)

        assertFalse(olderBind.isAlive)
        assertSame(service, App.serviceReadinessSnapshot().service)
        assertEquals(ServicePhase.ERROR, App.serviceReadinessSnapshot().phase)
        assertFalse(App.isServiceReady())
        assertEquals("stale attempt must not drain readiness listeners", 0, boundCallbackCount)
        assertEquals(1, currentListenerCount())
    }

    @Test
    fun `explicit convergence retry succeeds on current Binder and drains pending listener`() {
        val service = mock<XposedService>()
        val phases = mutableListOf<ServicePhase>()
        val stateListener: (ServiceReadinessSnapshot) -> Unit = { phases += it.phase }
        App.addOnServiceStateListener(stateListener)
        var allowConvergence = false
        app.testTokenConvergenceOverride = { true }
        app.testSettingsHandoffOverride = { allowConvergence }
        var callbackCount = 0
        App.addOnServiceBoundListener { callbackCount++ }

        app.onServiceBind(service)
        assertEquals(ServicePhase.ERROR, App.serviceReadinessSnapshot().phase)
        assertEquals(0, callbackCount)

        allowConvergence = true
        assertTrue(app.retryServiceConvergence())

        assertSame(service, App.readyService())
        assertEquals(ServicePhase.READY, App.serviceReadinessSnapshot().phase)
        assertEquals(1, callbackCount)
        assertEquals(0, currentListenerCount())
        assertEquals(
            listOf(
                ServicePhase.DISCONNECTED,
                ServicePhase.CONNECTED_SYNCING,
                ServicePhase.ERROR,
                ServicePhase.CONNECTED_SYNCING,
                ServicePhase.READY,
            ),
            phases,
        )
        App.removeOnServiceStateListener(stateListener)
    }

    @Test
    fun `retry succeeds on settings convergence even while diagnostics token still fails`() {
        val service = mock<XposedService>()
        var allowSettingsConvergence = false
        app.testTokenConvergenceOverride = { false }
        app.testSettingsHandoffOverride = { allowSettingsConvergence }
        var callbackCount = 0
        App.addOnServiceBoundListener { callbackCount++ }

        app.onServiceBind(service)
        assertEquals(ServicePhase.ERROR, App.serviceReadinessSnapshot().phase)
        assertEquals(0, callbackCount)

        allowSettingsConvergence = true
        assertTrue(app.retryServiceConvergence())

        assertSame(service, App.readyService())
        assertEquals(ServicePhase.READY, App.serviceReadinessSnapshot().phase)
        assertEquals(1, callbackCount)
        assertEquals(0, currentListenerCount())
    }

    @Test
    fun `listener registered during bind waits until convergence before refresh`() {
        app.testTokenConvergenceOverride = null
        val service = mock<XposedService>()
        val remotePrefs = stubRemotePrefsOf(service)
        whenever(remotePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null))
            .thenReturn("shared-token")
        whenever(mockFilePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null))
            .thenReturn("shared-token")
        val syncStarted = CountDownLatch(1)
        val allowSyncToFinish = CountDownLatch(1)
        whenever(service.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)).thenAnswer {
            syncStarted.countDown()
            check(allowSyncToFinish.await(5, TimeUnit.SECONDS))
            remotePrefs
        }
        val appSpy = spyAppBackedByFileStore()
        var convergenceFinished = false
        var listenerObservedConvergedState = false
        appSpy.testAfterConvergence = { convergenceFinished = true }
        val bindThread = Thread { appSpy.onServiceBind(service) }
        bindThread.start()

        assertTrue(syncStarted.await(5, TimeUnit.SECONDS))
        assertNotNull("service object is visible during convergence", App.mService)
        assertFalse("a visible service is not ready before convergence", App.isServiceReady())
        App.addOnServiceBoundListener {
            listenerObservedConvergedState = convergenceFinished
        }
        assertFalse(listenerObservedConvergedState)
        allowSyncToFinish.countDown()
        bindThread.join(5_000)

        assertFalse(bindThread.isAlive)
        assertTrue(convergenceFinished)
        assertTrue(App.isServiceReady())
        assertTrue("collector listener should refresh only after convergence", listenerObservedConvergedState)
    }

    @Test
    fun `onServiceBind drains registry so re-binding notifies nothing extra`() {
        var invocationCount = 0
        App.addOnServiceBoundListener { invocationCount++ }
        val service = mock<XposedService>()

        app.onServiceBind(service)
        app.onServiceBind(service)

        assertEquals(1, invocationCount)
    }

    @Test
    fun `onServiceDied clears mService`() {
        val service = mock<XposedService>()
        app.onServiceBind(service)
        assertNotNull(App.mService)

        app.onServiceDied(service)

        assertNull(App.mService)
    }

    @Test
    fun `framework binding history remains true after service disconnect`() {
        val service = mock<XposedService>()
        assertFalse(App.hasObservedFrameworkBinding())

        app.onServiceBind(service)
        assertTrue(App.hasObservedFrameworkBinding())
        app.onServiceDied(service)

        assertNull(App.mService)
        assertTrue(App.hasObservedFrameworkBinding())
    }

    @Test
    fun `memory preferences separate live and persisted effects of failed commit`() {
        val preferences = MemorySharedPreferences(
            mapOf("setting" to "before"),
            commitResult = false,
            commitFalseUpdatesMemory = true,
            commitFalseUpdatesDisk = false,
        )

        assertFalse(preferences.edit().putString("setting", "live-only").commit())
        assertEquals("live map receives failed commit", "live-only", preferences.getString("setting", null))
        assertEquals("disk remains independently observable", "before", preferences.persistedAll["setting"])

        preferences.commitFalseUpdatesDisk = true
        assertFalse(preferences.edit().putString("setting", "also-on-disk").commit())
        assertEquals("also-on-disk", preferences.getString("setting", null))
        assertEquals("disk update is independently configurable", "also-on-disk", preferences.persistedAll["setting"])
    }

    @Test
    fun `guarded settings writes use local preferences for a never-bound offline cold start`() {
        val context = mock<Context>()
        whenever(context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE))
            .thenReturn(mockFilePrefs)
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(mockFilePrefs)
        App.setServiceStateForTests(null, ready = false)

        val accepted = App.withGuardedSettingsPreferences(context) { prefs ->
            assertSame(mockFilePrefs, prefs)
            prefs.edit().apply()
            true
        }

        assertTrue(accepted)
        verify(context).getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE)
        verify(mockFilePrefs, atLeastOnce()).edit()
    }

    @Test
    fun `guarded offline settings edit persists its allowlisted handoff value`() {
        val context = mock<Context>()
        val local = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel 5"))
        val pending = MemorySharedPreferences()
        whenever(context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE))
            .thenReturn(local)
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(pending)
        App.setServiceStateForTests(null, ready = false)

        val accepted = App.withGuardedSettingsPreferences(
            context,
            editedKeys = setOf(Constants.PREF_DEVICE_TO_SPOOF),
        ) { prefs ->
            prefs.edit().putString(Constants.PREF_DEVICE_TO_SPOOF, "Pixel 5").apply()
            true
        }

        assertTrue(accepted)
        assertEquals("Pixel 5", pending.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertTrue(
            pending.getStringSet("pending_keys", mutableSetOf())
                ?.contains(Constants.PREF_DEVICE_TO_SPOOF) == true,
        )
    }

    @Test
    fun `pending store acquisition failure rolls back the offline setting edit`() {
        val context = mock<Context>()
        val local = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel 3"))
        val pending = MemorySharedPreferences()
        whenever(context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE))
            .thenReturn(local)
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenThrow(IllegalStateException("injected pending-store acquisition failure"))
        App.setServiceStateForTests(null, ready = false)

        val accepted = App.withGuardedSettingsPreferences(
            context,
            editedKeys = setOf(Constants.PREF_DEVICE_TO_SPOOF),
        ) { prefs ->
            prefs.edit().putString(Constants.PREF_DEVICE_TO_SPOOF, "Pixel 5").apply()
            true
        }

        assertFalse(accepted)
        assertEquals("Pixel 3", local.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertTrue(pending.all.isEmpty())

        val laterContext = mock<Context>()
        whenever(laterContext.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(pending)
        val remote = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel XL"))
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remote)
        assertTrue(OfflineSettingsHandoff.converge(laterContext, service))
        assertEquals("Pixel XL", remote.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
    }

    @Test
    fun `pending commit failure rolls back and later bind has no stranded edit`() {
        val context = mock<Context>()
        val local = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel 3"))
        val pending = MemorySharedPreferences(commitResult = false)
        whenever(context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE))
            .thenReturn(local)
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(pending)
        App.setServiceStateForTests(null, ready = false)

        val accepted = App.withGuardedSettingsPreferences(
            context,
            editedKeys = setOf(Constants.PREF_DEVICE_TO_SPOOF),
        ) { prefs ->
            prefs.edit().putString(Constants.PREF_DEVICE_TO_SPOOF, "Pixel 5").apply()
            true
        }

        assertFalse(accepted)
        assertEquals("Pixel 3", local.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertTrue(pending.all.isEmpty())

        pending.commitResult = true
        val remote = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel XL"))
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remote)
        assertTrue(OfflineSettingsHandoff.converge(context, service))
        assertEquals("Pixel XL", remote.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertTrue(pending.all.isEmpty())
    }

    @Test
    fun `Android commit false live pending intent is accepted and never silently lost or applied after recreation`() {
        val context = mock<Context>()
        val local = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel 3"))
        val pending = MemorySharedPreferences(commitResult = false, commitFalseUpdatesMemory = true)
        whenever(context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE))
            .thenReturn(local)
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(pending)
        App.setServiceStateForTests(null, ready = false)
        var persistenceWarning = false

        val accepted = App.withGuardedSettingsPreferences(
            context,
            editedKeys = setOf(Constants.PREF_DEVICE_TO_SPOOF),
            onOfflinePersistenceUncertain = { persistenceWarning = true },
        ) { prefs ->
            prefs.edit().putString(Constants.PREF_DEVICE_TO_SPOOF, "Pixel 5").apply()
            true
        }

        assertTrue(accepted)
        assertTrue("commit=false with matching live intent is visibly caveated", persistenceWarning)
        assertEquals("Pixel 5", local.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertEquals("Pixel 5", pending.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertTrue(
            pending.getStringSet("pending_keys", mutableSetOf())
                .orEmpty().contains(Constants.PREF_DEVICE_TO_SPOOF),
        )

        // A new process sees only the durable pending-store state. The local apply
        // may already be durable, but the failed pending commit is not promoted into
        // a hidden remote write on recreation.
        val recreatedPending = MemorySharedPreferences(pending.persistedAll)
        val recreatedContext = mock<Context>()
        whenever(recreatedContext.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(recreatedPending)
        val recreatedRemote = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel XL"))
        val recreatedService = mock<XposedService>()
        whenever(recreatedService.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(recreatedRemote)
        assertTrue(OfflineSettingsHandoff.converge(recreatedContext, recreatedService))
        assertEquals("Pixel XL", recreatedRemote.getString(Constants.PREF_DEVICE_TO_SPOOF, null))

        // In the original process the marker and local value remain paired, so a
        // bind can safely apply the accepted in-memory intent.
        val sameProcessRemote = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel XL"))
        val sameProcessService = mock<XposedService>()
        whenever(sameProcessService.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME)))
            .thenReturn(sameProcessRemote)
        // A later bind-time marker cleanup can succeed; the original user write's
        // commit=false state remains represented by the in-memory pending map.
        pending.commitResult = true
        assertTrue(OfflineSettingsHandoff.converge(context, sameProcessService))
        assertEquals("Pixel 5", sameProcessRemote.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
    }

    @Test
    fun `rejected pending edit recovery blocks uncertain local value in same and fresh instances`() {
        val context = mock<Context>()
        val local = MemorySharedPreferences(
            mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel 3"),
        )
        val pending = MemorySharedPreferences(commitResult = false)
        whenever(context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE))
            .thenReturn(local)
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(pending)
        App.setServiceStateForTests(null, ready = false)

        val accepted = App.withGuardedSettingsPreferences(
            context,
            editedKeys = setOf(Constants.PREF_DEVICE_TO_SPOOF),
        ) { prefs ->
            prefs.edit().putString(Constants.PREF_DEVICE_TO_SPOOF, "Pixel 5").apply()
            // The edit is already on disk, but both the rejected pending marker
            // and rollback commits now fail. The durable pre-edit recovery journal
            // must keep the disk-only value from being exposed after recreation.
            local.commitResult = false
            local.commitFalseUpdatesMemory = true
            true
        }

        assertFalse(accepted)
        assertEquals("in-memory rollback is applied despite its false disk result",
            "Pixel 3", local.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertEquals("edit remains on disk until recovery commits", "Pixel 5",
            local.persistedAll[Constants.PREF_DEVICE_TO_SPOOF])
        assertTrue(pending.all.isEmpty())

        assertNull("same-process reads fail closed while recovery commit is failing", PrefUtils.getPrefs(context))

        val recreatedLocal = MemorySharedPreferences(local.persistedAll)
        val recreatedPending = MemorySharedPreferences(pending.persistedAll)
        val recreatedContext = mock<Context>()
        whenever(recreatedContext.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE))
            .thenReturn(recreatedLocal)
        whenever(recreatedContext.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(recreatedPending)
        App.setServiceStateForTests(null, ready = false)
        val recoveredFresh = PrefUtils.getPrefs(recreatedContext)
        assertSame(recreatedLocal, recoveredFresh)
        assertEquals("fresh process restores the durable pre-edit value before exposing settings",
            "Pixel 3", recreatedLocal.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertEquals("recovery itself is durable", "Pixel 3",
            recreatedLocal.persistedAll[Constants.PREF_DEVICE_TO_SPOOF])

        local.commitResult = true
        val recoveredSameProcess = PrefUtils.getPrefs(context)
        assertSame(local, recoveredSameProcess)
        assertEquals("same process remains on the rollback value", "Pixel 3",
            local.getString(Constants.PREF_DEVICE_TO_SPOOF, null))

        val remote = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel XL"))
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remote)
        assertTrue(OfflineSettingsHandoff.converge(recreatedContext, service))
        assertEquals("a rejected local edit has no remote handoff marker",
            "Pixel XL", remote.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
    }

    @Test
    fun `successful first bind waits for pending offline user settings handoff`() {
        val priorLocal = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel 3"))
        val editedLocal = MemorySharedPreferences(mapOf(Constants.PREF_DEVICE_TO_SPOOF to "Pixel 5"))
        val pending = MemorySharedPreferences()
        assertTrue(
            OfflineSettingsHandoff.recordChanges(
                pending,
                OfflineSettingsHandoff.snapshot(priorLocal),
                editedLocal,
            ),
        )
        val remote = MemorySharedPreferences(
            mapOf(
                Constants.PREF_DEVICE_TO_SPOOF to "Pixel XL",
                "unrelated_remote_setting" to "preserve",
            ),
        )
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remote)
        val appSpy = spy(app)
        doReturn(pending).whenever(appSpy).getSharedPreferences(
            eq(OfflineSettingsHandoff.PREFERENCES_FILE),
            eq(Context.MODE_PRIVATE),
        )
        appSpy.testTokenConvergenceOverride = { true }
        appSpy.testSettingsHandoffOverride = null

        appSpy.onServiceBind(service)

        assertTrue("service readiness follows the settings handoff", App.isServiceReady())
        assertEquals("Pixel 5", remote.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertEquals("preserve", remote.getString("unrelated_remote_setting", null))
        assertTrue(pending.all.isEmpty())
    }

    @Test
    fun `offline edit survives simulated process recreation bind failure and explicit retry`() {
        val context = mock<Context>()
        val local = MemorySharedPreferences(
            mapOf(
                Constants.PREF_DEVICE_TO_SPOOF to "Pixel 3",
                Constants.PREF_ENABLE_VERBOSE_LOGS to false,
            ),
        )
        val pending = MemorySharedPreferences()
        whenever(context.getSharedPreferences(Constants.SHARED_PREF_FILE_NAME, Context.MODE_PRIVATE))
            .thenReturn(local)
        whenever(context.getSharedPreferences(OfflineSettingsHandoff.PREFERENCES_FILE, Context.MODE_PRIVATE))
            .thenReturn(pending)
        App.setServiceStateForTests(null, ready = false)

        assertTrue(
            App.withGuardedSettingsPreferences(
                context,
                editedKeys = setOf(Constants.PREF_DEVICE_TO_SPOOF),
            ) { prefs ->
                prefs.edit().putString(Constants.PREF_DEVICE_TO_SPOOF, "Pixel 5").apply()
                true
            },
        )
        assertEquals("Pixel 5", pending.getString(Constants.PREF_DEVICE_TO_SPOOF, null))

        // A fresh App instance sees the same durable handoff preferences, modeling
        // the local-store state retained after the original process exits.
        App.setServiceStateForTests(null, ready = false)
        val recreatedApp = spy(App())
        doReturn(pending).whenever(recreatedApp).getSharedPreferences(
            eq(OfflineSettingsHandoff.PREFERENCES_FILE),
            eq(Context.MODE_PRIVATE),
        )
        recreatedApp.testTokenConvergenceOverride = { true }
        recreatedApp.testSettingsHandoffOverride = null

        val remote = MemorySharedPreferences(
            mapOf(
                Constants.PREF_DEVICE_TO_SPOOF to "Pixel XL",
                Constants.PREF_ENABLE_VERBOSE_LOGS to true,
                "unrelated_remote_setting" to "preserve remote value",
            ),
            commitResult = false,
        )
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remote)

        recreatedApp.onServiceBind(service)

        assertEquals(ServicePhase.ERROR, App.serviceReadinessSnapshot().phase)
        assertFalse(App.isServiceReady())
        assertEquals("Pixel XL", remote.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertEquals("preserve remote value", remote.getString("unrelated_remote_setting", null))
        assertEquals("Pixel 5", pending.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertTrue(
            pending.getStringSet("pending_keys", mutableSetOf())
                ?.contains(Constants.PREF_DEVICE_TO_SPOOF) == true,
        )

        remote.commitResult = true
        assertTrue(recreatedApp.retryServiceConvergence())

        assertEquals(ServicePhase.READY, App.serviceReadinessSnapshot().phase)
        assertTrue(App.isServiceReady())
        assertEquals("Pixel 5", remote.getString(Constants.PREF_DEVICE_TO_SPOOF, null))
        assertTrue(remote.getBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, false))
        assertEquals("preserve remote value", remote.getString("unrelated_remote_setting", null))
        assertTrue(pending.all.isEmpty())
    }

    @Test
    fun `main feature and advanced saves opened while ready are rejected after service death`() {
        val context = mock<Context>()
        val service = mock<XposedService>()
        val remotePrefs = mock<SharedPreferences>()
        val remoteEditor = mock<SharedPreferences.Editor>()
        whenever(service.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)).thenReturn(remotePrefs)
        whenever(remotePrefs.edit()).thenReturn(remoteEditor)
        whenever(remoteEditor.putString(any(), any())).thenReturn(remoteEditor)
        whenever(remoteEditor.putStringSet(any(), any())).thenReturn(remoteEditor)
        whenever(remoteEditor.putBoolean(any(), any())).thenReturn(remoteEditor)
        app.onServiceBind(service)
        assertTrue(App.isServiceReady())
        var retainedRemote: SharedPreferences? = null
        assertTrue(App.withGuardedSettingsPreferences(context) { prefs ->
            retainedRemote = prefs
            true
        })
        assertSame(remotePrefs, retainedRemote)

        // These are the three activity save paths. Each asks the central guard for
        // a fresh authorized source instead of reusing the remote handle captured
        // when the screen was opened.
        app.onServiceDied(service)
        val saveAttempts = listOf(
            "main screen" to { prefs: SharedPreferences ->
                prefs.edit().putBoolean(Constants.PREF_OVERRIDE_ROM_FEATURE_LEVELS, false).apply()
                true
            },
            "FeatureCustomize" to { prefs: SharedPreferences ->
                prefs.edit().putStringSet(Constants.PREF_SPOOF_FEATURES_LIST, emptySet()).apply()
                true
            },
            "AdvancedOptionsActivity" to { prefs: SharedPreferences ->
                prefs.edit().putBoolean(Constants.PREF_ENABLE_VERBOSE_LOGS, true).apply()
                true
            },
        )
        saveAttempts.forEach { (screen, operation) ->
            var invoked = false
            val accepted = App.withGuardedSettingsPreferences(context) { currentPrefs ->
                invoked = true
                operation(currentPrefs)
            }
            assertFalse("$screen must not save after service death", accepted)
            assertFalse("$screen must not receive a cached preference handle", invoked)
        }
        verify(remotePrefs, never()).edit()
        verify(mockFilePrefs, never()).edit()
        verify(context, never()).getSharedPreferences(any(), any())
    }

    @Test
    fun `source version guard rejects child save values loaded before bind and rebind`() {
        val context = mock<Context>()
        App.setServiceStateForTests(null, ready = false)
        val offlineVersion = App.serviceReadinessSnapshot().version
        val firstService = mock<XposedService>()
        app.onServiceBind(firstService)
        val firstReadyVersion = App.serviceReadinessSnapshot().version
        app.onServiceDied(firstService)
        val reboundService = mock<XposedService>()
        app.onServiceBind(reboundService)

        val staleSave = listOf(offlineVersion, firstReadyVersion).map { expectedVersion ->
            var invoked = false
            val accepted = App.withGuardedSettingsPreferences(
                context,
                expectedStateVersion = expectedVersion,
            ) {
                invoked = true
                true
            }
            accepted to invoked
        }

        assertEquals(listOf(false to false, false to false), staleSave)
        verify(context, never()).getSharedPreferences(any(), any())
    }

    @Test
    fun `settings mutation and readiness transition are serialized`() {
        val context = mock<Context>()
        val service = mock<XposedService>()
        val remotePrefs = mock<SharedPreferences>()
        val remoteEditor = mock<SharedPreferences.Editor>()
        whenever(service.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)).thenReturn(remotePrefs)
        whenever(remotePrefs.edit()).thenReturn(remoteEditor)
        whenever(remoteEditor.putString(any(), any())).thenReturn(remoteEditor)
        app.onServiceBind(service)

        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val deathStarted = CountDownLatch(1)
        val deathFinished = CountDownLatch(1)
        var accepted = false
        val mutationThread = Thread {
            accepted = App.withGuardedSettingsPreferences(context) { prefs ->
                mutationEntered.countDown()
                check(releaseMutation.await(5, TimeUnit.SECONDS))
                prefs.edit().putString(Constants.PREF_DEVICE_TO_SPOOF, "test-device").apply()
                true
            }
        }
        mutationThread.start()
        assertTrue(mutationEntered.await(5, TimeUnit.SECONDS))

        val deathThread = Thread {
            deathStarted.countDown()
            app.onServiceDied(service)
            deathFinished.countDown()
        }
        deathThread.start()
        assertTrue(deathStarted.await(5, TimeUnit.SECONDS))
        assertFalse("death must not split a guarded mutation", deathFinished.await(100, TimeUnit.MILLISECONDS))

        releaseMutation.countDown()
        mutationThread.join(5_000)
        deathThread.join(5_000)
        assertFalse(mutationThread.isAlive)
        assertFalse(deathThread.isAlive)
        assertTrue(accepted)
        assertTrue(deathFinished.await(1, TimeUnit.SECONDS))
        verify(remoteEditor).putString(Constants.PREF_DEVICE_TO_SPOOF, "test-device")
        assertEquals(ServicePhase.DISCONNECTED, App.serviceReadinessSnapshot().phase)
    }

    @Test
    fun `stale death callback from older service cannot clear newer ready binding`() {
        val oldService = mock<XposedService>()
        val currentService = mock<XposedService>()
        app.onServiceBind(oldService)
        app.onServiceBind(currentService)
        assertSame(currentService, App.mService)
        assertTrue(App.isServiceReady())

        app.onServiceDied(oldService)

        assertSame(currentService, App.mService)
        assertTrue(App.isServiceReady())
    }

    @Test
    fun `persistent service-state listener observes death and rebind while still registered`() {
        val states = mutableListOf<Pair<Boolean, Long>>()
        val listener: (ServiceReadinessSnapshot) -> Unit = { states += it.ready to it.version }
        App.addOnServiceStateListener(listener)
        val initialListenerCount = App.serviceStateListenerCountForTests()

        val first = mock<XposedService>()
        app.onServiceBind(first)
        app.onServiceDied(first)
        val second = mock<XposedService>()
        app.onServiceBind(second)

        assertEquals(listOf(false, false, true, false, false, true), states.map { it.first })
        assertTrue(states.zipWithNext().all { (a, b) -> b.second > a.second })
        assertEquals(initialListenerCount, App.serviceStateListenerCountForTests())

        App.removeOnServiceStateListener(listener)
        assertEquals(initialListenerCount - 1, App.serviceStateListenerCountForTests())
        app.onServiceDied(second)
        assertEquals("removed Activity listener must not be retained", 6, states.size)
    }

    @Test
    fun `listener registered after service death waits for rebind instead of firing`() {
        val service = mock<XposedService>()
        app.onServiceBind(service)
        app.onServiceDied(service)

        var invoked = false
        App.addOnServiceBoundListener { invoked = true }

        assertFalse(invoked)
        assertEquals(1, currentListenerCount())
    }

    // =========================================================================
    // 3. Registration after the service is already bound
    // =========================================================================

    @Test
    fun `addListener after bind invokes synchronously and registers nothing`() {
        setMockXposedService(mock<XposedService>())
        var invoked = false

        App.addOnServiceBoundListener { invoked = true }

        assertTrue(invoked)
        assertEquals(0, currentListenerCount())
    }

    // =========================================================================
    // 4. Listener isolation
    // =========================================================================

    @Test
    fun `throwing listener does not break other listeners or state assignment`() {
        var secondInvoked = false
        App.addOnServiceBoundListener { error("boom") }
        App.addOnServiceBoundListener { secondInvoked = true }

        app.onServiceBind(mock<XposedService>())

        assertTrue(secondInvoked)
        assertNotNull(App.mService)
    }

    @Test
    fun `multiple pending listeners are all notified on bind`() {
        val invocations = mutableListOf<Int>()
        App.addOnServiceBoundListener { invocations.add(1) }
        App.addOnServiceBoundListener { invocations.add(2) }

        app.onServiceBind(mock<XposedService>())

        assertEquals(listOf(1, 2), invocations)
    }

    // =========================================================================
    // 5. Token lifecycle: App.onCreate (pre-bind local provisioning)
    // =========================================================================

    /**
     * Builds a spy of [App] whose [Context.getSharedPreferences] resolves to the
     * mocked MODE_PRIVATE file store. The inline mockmaker intercepts the
     * self-calls that [DiagnosticsStore] makes with the application instance.
     */
    private fun spyAppBackedByFileStore(): App {
        val appSpy = spy(app)
        doReturn(mockFilePrefs).whenever(appSpy).getSharedPreferences(
            eq(Constants.SHARED_PREF_FILE_NAME),
            eq(Context.MODE_PRIVATE),
        )
        return appSpy
    }

    private fun stubRemotePrefsOf(service: XposedService): SharedPreferences {
        val remotePrefs = mock<SharedPreferences>()
        whenever(service.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME))).thenReturn(remotePrefs)
        return remotePrefs
    }

    @Test
    fun `onCreate pre-bind provisions a fresh UUID token into the local file store`() {
        val appSpy = spyAppBackedByFileStore()
        whenever(mockFilePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)).thenReturn(null)

        appSpy.onCreate()

        val tokenCaptor = argumentCaptor<String>()
        verify(mockFileEditor).putString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), tokenCaptor.capture())
        val provisioned = tokenCaptor.firstValue
        val parsed = UUID.fromString(provisioned)
        assertEquals(provisioned, parsed.toString())
        assertEquals(4, parsed.version())
        assertNotEquals(UUID(0, 0), parsed)
        verify(mockFileEditor).commit()
        verify(mockFileEditor, never()).apply()
    }

    @Test
    fun `onCreate pre-bind keeps an existing local token without regenerating`() {
        val appSpy = spyAppBackedByFileStore()
        whenever(mockFilePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null))
            .thenReturn("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")

        appSpy.onCreate()

        verify(mockFileEditor, never()).putString(any(), any())
        verify(mockFileEditor, never()).commit()
    }

    // =========================================================================
    // 6. Token lifecycle: App.onServiceBind convergence
    // =========================================================================

    @Test
    fun `onServiceBind converges divergent remote token into the local file store`() {
        app.testTokenConvergenceOverride = null
        val service = mock<XposedService>()
        val remotePrefs = stubRemotePrefsOf(service)
        whenever(remotePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null))
            .thenReturn("remote-token-value")
        whenever(mockFilePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null))
            .thenReturn("stale-local-value")
        val appSpy = spyAppBackedByFileStore()

        appSpy.onServiceBind(service)

        verify(mockFileEditor).putString(Constants.PREF_DIAG_BROADCAST_TOKEN, "remote-token-value")
        verify(mockFileEditor).commit()
    }

    @Test
    fun `onServiceBind mirrors local token into empty remote prefs`() {
        app.testTokenConvergenceOverride = null
        val service = mock<XposedService>()
        val remotePrefs = stubRemotePrefsOf(service)
        val remoteEditor = mock<SharedPreferences.Editor>()
        whenever(remotePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)).thenReturn(null)
        whenever(remotePrefs.edit()).thenReturn(remoteEditor)
        whenever(remoteEditor.putString(any(), any())).thenReturn(remoteEditor)
        whenever(remoteEditor.commit()).thenReturn(true)
        whenever(mockFilePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null))
            .thenReturn("local-token-value")
        val appSpy = spyAppBackedByFileStore()

        appSpy.onServiceBind(service)

        val remoteEditorCaptor = argumentCaptor<String>()
        verify(remoteEditor).putString(
            eq(Constants.PREF_DIAG_BROADCAST_TOKEN),
            remoteEditorCaptor.capture(),
        )
        assertEquals("local-token-value", remoteEditorCaptor.firstValue)
        // Remote wins contract: the local copy must stay untouched in this direction.
        verify(mockFileEditor, never()).putString(any(), any())
    }

    @Test
    fun `onServiceBind leaves both stores untouched when tokens already agree`() {
        app.testTokenConvergenceOverride = null
        val service = mock<XposedService>()
        val remotePrefs = stubRemotePrefsOf(service)
        whenever(remotePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null))
            .thenReturn("shared-token")
        whenever(mockFilePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null))
            .thenReturn("shared-token")
        val appSpy = spyAppBackedByFileStore()

        appSpy.onServiceBind(service)

        verify(mockFileEditor, never()).putString(any(), any())
        verify(remotePrefs, never()).edit()
    }

    @Test
    fun `onServiceBind swallows convergence failure when remote prefs are unavailable`() {
        app.testTokenConvergenceOverride = null
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME))
            .thenThrow(RuntimeException("remote prefs unavailable"))
        val appSpy = spyAppBackedByFileStore()

        // Must not propagate out of the binder callback.
        appSpy.onServiceBind(service)

        verify(mockFileEditor, never()).putString(any(), any())
        // The logged swallow proves convergence actually ran and hit its guarded
        // failure path, rather than the wiring being skipped entirely.
        mockedLog.verify { Log.w(any(), any(), anyOrNull()) }
    }

    // =========================================================================
    // 7. Concurrency: registration racing the bind event
    // =========================================================================

    @Test
    fun `concurrent registrations racing bind invoke every listener exactly once`() {
        val threads = 8
        val perThread = 64
        val total = threads * perThread
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads + 1)
        try {
            val counts = AtomicIntegerArray(total)
            val registrationFutures = (0 until total).map { index ->
                pool.submit(
                    Callable {
                        go.await()
                        App.addOnServiceBoundListener { counts.incrementAndGet(index) }
                    },
                )
            }
            val binderFuture = pool.submit(
                Callable {
                    go.await()
                    app.onServiceBind(mock<XposedService>())
                },
            )

            go.countDown()
            registrationFutures.forEach { it.get(30, TimeUnit.SECONDS) }
            binderFuture.get(30, TimeUnit.SECONDS)

            // Invariants that must hold under every interleaving: registrations
            // landing before the drain are invoked from it, registrations after it
            // take the synchronous fast path — either way exactly once each, with
            // no listener left stranded in the registry.
            assertEquals(total, (0 until total).sumOf { counts.get(it) })
            assertEquals(0, currentListenerCount())
        } finally {
            pool.shutdownNow()
            // Bounded wait so tearDown's registry/mService resets cannot race live
            // workers if an assertion above failed while tasks were still running.
            check(pool.awaitTermination(5, TimeUnit.SECONDS)) { "stress pool did not terminate" }
        }
    }
}
