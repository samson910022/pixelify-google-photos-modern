package io.github.samson910022.pixelifyphotos

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

/**
 * Unit tests for [PixelifyModule.onModuleLoaded] remote-prefs broadcast token
 * provisioning, using the libxposed [io.github.libxposed.api.XposedModule.attachFramework]
 * seam with a mocked [XposedInterface] so no real Xposed framework is required on the JVM.
 */
class PixelifyModuleTest {

    private lateinit var mockedLog: MockedStatic<Log>
    private lateinit var mockFramework: XposedInterface
    private lateinit var mockRemotePrefs: SharedPreferences
    private lateinit var mockRemoteEditor: SharedPreferences.Editor
    private lateinit var module: PixelifyModule

    @Before
    fun setUp() {
        mockedLog = Mockito.mockStatic(Log::class.java)
        mockedLog.`when`<Int> { Log.d(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.d(any<String>(), any<String>(), anyOrNull()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.e(any<String>(), any<String>()) }.thenReturn(0)

        mockFramework = mock()
        mockRemotePrefs = mock()
        mockRemoteEditor = mock()
        whenever(mockRemotePrefs.edit()).thenReturn(mockRemoteEditor)
        whenever(mockRemoteEditor.putString(any(), any())).thenReturn(mockRemoteEditor)
        whenever(mockFramework.getRemotePreferences(eq(Constants.SHARED_PREF_FILE_NAME)))
            .thenReturn(mockRemotePrefs)

        module = PixelifyModule()
        module.attachFramework(mockFramework)
    }

    @After
    fun tearDown() {
        try {
            // The module constructor publishes itself into the companion singleton;
            // clear it so later suites never observe a stale framework-attached module.
            TestStatics.setStaticField(PixelifyModule::class.java, "instance", null)
        } finally {
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
    // Token provisioning
    // =========================================================================

    @Test
    fun `onModuleLoaded provisions a fresh UUID token into remote prefs when absent`() {
        whenever(mockRemotePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)).thenReturn(null)

        module.onModuleLoaded(fakeParam())

        val tokenCaptor = argumentCaptor<String>()
        verify(mockRemoteEditor).putString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), tokenCaptor.capture())
        val provisioned = tokenCaptor.firstValue
        val parsed = UUID.fromString(provisioned)
        // Well-formed, non-nil, randomly generated (version 4) so receivers can
        // constant-time compare an unpredictable per-install value.
        assertEquals(provisioned, parsed.toString())
        assertEquals(4, parsed.version())
        assertNotEquals(UUID(0, 0), parsed)
        verify(mockRemoteEditor).commit()
        // Cross-process atomicity contract: apply() is asynchronous and forbidden here.
        verify(mockRemoteEditor, never()).apply()
    }

    @Test
    fun `onModuleLoaded keeps an existing remote token without overwriting`() {
        whenever(mockRemotePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null))
            .thenReturn("00000000-1111-2222-3333-444444444444")

        module.onModuleLoaded(fakeParam())

        verify(mockRemoteEditor, never()).putString(any(), any())
        verify(mockRemoteEditor, never()).commit()
    }

    @Test
    fun `onModuleLoaded regenerates when remote prefs report a blank string token`() {
        whenever(mockRemotePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)).thenReturn("")
        whenever(mockRemoteEditor.commit()).thenReturn(true)

        module.onModuleLoaded(fakeParam())

        verify(mockRemoteEditor, times(1)).putString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), any())
        verify(mockRemoteEditor).commit()
    }

    @Test
    fun `onModuleLoaded swallows provisioning failure without propagating`() {
        whenever(mockFramework.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME))
            .thenThrow(RuntimeException("remote prefs are read-only in target processes"))

        // Must not propagate: the hook lifecycle must continue even without a writable store.
        module.onModuleLoaded(fakeParam())

        // Proves the throwing stub actually engaged (happy path would have reached edit()).
        verify(mockRemotePrefs, never()).edit()
    }

    @Test
    fun `onModuleLoaded tolerates any process name and system server flag`() {
        whenever(mockRemotePrefs.getString(Constants.PREF_DIAG_BROADCAST_TOKEN, null)).thenReturn(null)
        whenever(mockRemoteEditor.commit()).thenReturn(true)

        module.onModuleLoaded(fakeParam(processName = "system", isSystemServer = true))

        verify(mockRemoteEditor).putString(eq(Constants.PREF_DIAG_BROADCAST_TOKEN), any())
    }

    // =========================================================================
    // Singleton contract
    // =========================================================================

    @Test
    fun `module constructor publishes process-wide instance reference`() {
        assertNotNull(PixelifyModule.instance)
        assertEquals(module, PixelifyModule.instance)
    }
}
