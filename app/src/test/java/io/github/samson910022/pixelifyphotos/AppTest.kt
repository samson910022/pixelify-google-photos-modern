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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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
        // Real Material internals would walk Activity lifecycle registrations against a
        // stub android.jar; intercept the entry point for deterministic JVM behavior.
        mockedDynamicColors = Mockito.mockStatic(DynamicColors::class.java)
        mockFilePrefs = mock()
        mockFileEditor = mock()
        whenever(mockFilePrefs.edit()).thenReturn(mockFileEditor)
        whenever(mockFileEditor.putString(any(), any())).thenReturn(mockFileEditor)
        whenever(mockFileEditor.commit()).thenReturn(true)
        app = App()
    }

    @After
    fun tearDown() {
        try {
            setMockXposedService(null)
            clearListeners()
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
        TestStatics.setStaticField(App::class.java, "mService", service)
    }

    /** Clears pending listeners via the production test seam. */
    private fun clearListeners() {
        App.clearServiceBoundListeners()
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
        app.onServiceBind(mock<XposedService>())
        assertNotNull(App.mService)

        app.onServiceDied(mock<XposedService>())

        assertNull(App.mService)
    }

    @Test
    fun `listener registered after service death waits for rebind instead of firing`() {
        app.onServiceBind(mock<XposedService>())
        app.onServiceDied(mock<XposedService>())

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
        val service = mock<XposedService>()
        val remotePrefs = stubRemotePrefsOf(service)
        val remoteEditor = mock<SharedPreferences.Editor>()
        whenever(remotePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)).thenReturn(null)
        whenever(remotePrefs.edit()).thenReturn(remoteEditor)
        whenever(remoteEditor.putString(any(), any())).thenReturn(remoteEditor)
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
        val service = mock<XposedService>()
        whenever(service.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME))
            .thenThrow(RuntimeException("remote prefs unavailable"))
        val appSpy = spyAppBackedByFileStore()

        // Must not propagate out of the binder callback.
        appSpy.onServiceBind(service)

        verify(mockFileEditor, never()).putString(any(), any())
        // The logged swallow proves convergence actually ran and hit its guarded
        // failure path, rather than the wiring being skipped entirely.
        mockedLog.verify { Log.d(any(), any()) }
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
