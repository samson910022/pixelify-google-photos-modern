package io.github.samson910022.pixelifyphotos

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import org.junit.After
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

class DiagnosticsReceiverTest {

    private lateinit var mockedLog: MockedStatic<Log>
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var receiver: DiagnosticsReceiver

    @Before
    fun setUp() {
        mockedLog = Mockito.mockStatic(Log::class.java)
        mockedLog.`when`<Int> { Log.d(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.w(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.e(any<String>(), any<String>()) }.thenReturn(0)
        mockedLog.`when`<Int> { Log.e(any<String>(), any<String>(), anyOrNull()) }.thenReturn(0)

        mockContext = mock()
        mockPrefs = mock()
        mockEditor = mock()

        whenever(mockContext.getSharedPreferences(eq(Constants.SHARED_PREF_FILE_NAME), eq(Context.MODE_PRIVATE)))
            .thenReturn(mockPrefs)
        whenever(mockPrefs.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putLong(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putString(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putBoolean(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.putStringSet(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.remove(any())).thenReturn(mockEditor)

        receiver = DiagnosticsReceiver()
    }

    @After
    fun tearDown() {
        mockedLog.close()
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
    fun `onReceive processes valid ACTION_RECORD_DIAGNOSTICS with RECORD_VERIFY`() {
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

        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(Constants.ACTION_RECORD_DIAGNOSTICS)
        whenever(intent.getStringExtra(Constants.EXTRA_DIAGNOSTICS_METHOD)).thenReturn(Constants.METHOD_RECORD_VERIFY)
        whenever(intent.extras).thenReturn(extras)

        receiver.onReceive(mockContext, intent)

        verify(mockEditor).putLong(Constants.PREF_DIAG_VERIFY_AT, 123456789L)
        verify(mockEditor).putString(Constants.PREF_DIAG_VERIFY_DEVICE, "Pixel XL")
        verify(mockEditor).putString(Constants.PREF_DIAG_VERIFY_PACKAGE, Constants.PACKAGE_NAME_GOOGLE_PHOTOS)
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_OK, true)
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_NATIVE_READY, true)
        verify(mockEditor).putBoolean(Constants.PREF_DIAG_VERIFY_SYSPROPS, false)
        verify(mockEditor).apply()
    }

    @Test
    fun `onReceive processes CLEAR_VERIFY method`() {
        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(Constants.ACTION_RECORD_DIAGNOSTICS)
        whenever(intent.getStringExtra(Constants.EXTRA_DIAGNOSTICS_METHOD)).thenReturn(Constants.METHOD_CLEAR_VERIFY)
        whenever(intent.extras).thenReturn(mock())

        receiver.onReceive(mockContext, intent)

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
    fun `onReceive rejects broadcast reporting denylisted package`() {
        val extras = mock<Bundle>()
        whenever(extras.getString(Constants.PREF_DIAG_VERIFY_PACKAGE)).thenReturn("app.grapheneos.gmscompat")

        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(Constants.ACTION_RECORD_DIAGNOSTICS)
        whenever(intent.getStringExtra(Constants.EXTRA_DIAGNOSTICS_METHOD)).thenReturn(Constants.METHOD_RECORD_VERIFY)
        whenever(intent.extras).thenReturn(extras)

        receiver.onReceive(mockContext, intent)

        verify(mockEditor, never()).apply()
    }
}
