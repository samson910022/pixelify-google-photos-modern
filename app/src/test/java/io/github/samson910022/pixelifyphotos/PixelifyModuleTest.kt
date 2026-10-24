package io.github.samson910022.pixelifyphotos

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [PixelifyModule.onModuleLoaded] lifecycle behavior, using the
 * libxposed [io.github.libxposed.api.XposedModule.attachFramework] seam with a
 * mocked [XposedInterface] so no real Xposed framework is required on the JVM.
 */
class PixelifyModuleTest {

    private lateinit var mockedLog: MockedStatic<Log>
    private lateinit var mockFramework: XposedInterface
    private lateinit var module: PixelifyModule

    @Before
    fun setUp() {
        mockedLog = Mockito.mockStatic(Log::class.java)
        mockedLog.`when`<Int> { Log.d(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.d(any<String>(), any<String>(), anyOrNull()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.e(any<String>(), any<String>()) }.thenReturn(0)

        mockFramework = mock()

        module = PixelifyModule()
        module.attachFramework(mockFramework)
        DiagnosticsReporter.clearTargetRunForTests()
        DiagnosticsReporter.testEnqueueObserver = null
        DiagnosticsReporter.testBeforeDispatchLock = null
        DiagnosticsReporter.testStartIfAbsentObserver = null
    }

    @After
    fun tearDown() {
        try {
            // The module constructor publishes itself into the companion singleton;
            // clear it so later suites never observe a stale framework-attached module.
            TestStatics.setStaticField(PixelifyModule::class.java, "instance", null)
        } finally {
            DiagnosticsReporter.testEnqueueObserver = null
            DiagnosticsReporter.testBeforeDispatchLock = null
            DiagnosticsReporter.testStartIfAbsentObserver = null
            DiagnosticsReporter.clearTargetRunForTests()
            mockedLog.close()
        }
    }

    /** Minimal [XposedModuleInterface.ModuleLoadedParam] stub for a scoped-app process. */
    private fun fakeParam(
        processName: String = Constants.PACKAGE_NAME_GOOGLE_PHOTOS,
        isSystemServer: Boolean = false,
    ): XposedModuleInterface.ModuleLoadedParam = object : XposedModuleInterface.ModuleLoadedParam {
        override fun isSystemServer(): Boolean = isSystemServer
        override fun getProcessName(): String = processName
    }

    // =========================================================================
    // Token ownership
    // =========================================================================

    @Test
    fun `onModuleLoaded never creates a competing per-target-process token`() {
        module.onModuleLoaded(fakeParam())

        verify(mockFramework, never()).getRemotePreferences(any())
    }

    @Test
    fun `onModuleLoaded tolerates any process name and system server flag`() {
        module.onModuleLoaded(fakeParam(processName = "system", isSystemServer = true))

        verify(mockFramework, never()).getRemotePreferences(any())
    }

    // =========================================================================
    // Singleton contract
    // =========================================================================

    @Test
    fun `module constructor publishes process-wide instance reference`() {
        assertNotNull(PixelifyModule.instance)
        assertEquals(module, PixelifyModule.instance)
    }

    @Test
    fun `racing package-ready callbacks without a run retain one origin and reject secondary telemetry`() {
        whenever(mockFramework.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME))
            .thenThrow(IllegalStateException("test prevents hook setup"))

        val startDispatchEntered = CountDownLatch(1)
        val releaseStartDispatch = CountDownLatch(1)
        val startAttempts = CountDownLatch(2)
        val events = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        DiagnosticsReporter.testStartIfAbsentObserver = { startAttempts.countDown() }
        DiagnosticsReporter.testBeforeDispatchLock = { method ->
            if (method == Constants.METHOD_START_DIAGNOSTICS_RUN) {
                startDispatchEntered.countDown()
                check(releaseStartDispatch.await(5, TimeUnit.SECONDS))
            }
        }
        DiagnosticsReporter.testEnqueueObserver = { method, runId, _ -> events += method to runId }

        val photosCallback = Thread {
            module.onPackageReady(fakeReadyParam(Constants.PACKAGE_NAME_GOOGLE_PHOTOS))
        }
        val secondaryCallback = Thread {
            module.onPackageReady(fakeReadyParam("org.example.secondary"))
        }

        photosCallback.start()
        assertTrue(startDispatchEntered.await(5, TimeUnit.SECONDS))
        secondaryCallback.start()
        assertTrue("both callbacks must reach atomic run acquisition", startAttempts.await(5, TimeUnit.SECONDS))
        releaseStartDispatch.countDown()
        photosCallback.join(5_000)
        secondaryCallback.join(5_000)

        assertTrue("callbacks must complete", !photosCallback.isAlive && !secondaryCallback.isAlive)
        val activeRun = DiagnosticsReporter.currentTargetRunHandle()
        assertNotNull(activeRun)
        assertEquals(Constants.PACKAGE_NAME_GOOGLE_PHOTOS, activeRun?.origin)
        assertEquals(1, events.count { it.first == Constants.METHOD_START_DIAGNOSTICS_RUN })
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.second == activeRun?.runId })
        // The supported first package emits its registration/milestone; the secondary
        // package still executes the existing hook path but adds no attributed telemetry.
        assertTrue("only the supported callback may contribute run-bound telemetry", events.size in 3..4)
    }

    private fun fakeReadyParam(packageName: String): XposedModuleInterface.PackageReadyParam =
        mock<XposedModuleInterface.PackageReadyParam>().also { param ->
            whenever(param.packageName).thenReturn(packageName)
            whenever(param.classLoader).thenReturn(javaClass.classLoader)
        }
}
