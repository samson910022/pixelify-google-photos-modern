package io.github.samson910022.pixelifyphotos

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ConfigExportDeferredCompletionStoreTest {
    @After
    fun tearDown() {
        ConfigExportDeferredCompletionStore.resetForTests()
    }

    private fun newStore(
        preferences: MemorySharedPreferences,
        maxEntries: Int = 32,
    ) = ConfigExportDeferredCompletionStore(
        preferences = preferences,
        maxEntries = maxEntries,
    )

    @Test
    fun `from returns one store per process so the in-memory guard survives recreation`() {
        val preferences = MemorySharedPreferences()
        val context = mock<Context>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(preferences)

        val first = ConfigExportDeferredCompletionStore.from(context)
        val second = ConfigExportDeferredCompletionStore.from(context)

        assertSame(first, second)
    }

    @Test
    fun `completion survives a new process over the same durable preferences`() {
        val preferences = MemorySharedPreferences()
        newStore(preferences).markCompleted("request-one")

        val recreatedProcess = MemorySharedPreferences(initialValues = preferences.persistedAll)
        assertTrue(newStore(recreatedProcess).isCompleted("request-one"))
    }

    @Test
    fun `completion store keeps only the newest bounded entries`() {
        val preferences = MemorySharedPreferences()
        val completionStore = newStore(preferences, maxEntries = 2)
        completionStore.markCompleted("oldest")
        completionStore.markCompleted("middle")
        completionStore.markCompleted("newest")

        val recreatedProcess = MemorySharedPreferences(initialValues = preferences.persistedAll)
        val restored = newStore(recreatedProcess, maxEntries = 2)
        assertFalse(restored.isCompleted("oldest"))
        assertTrue(restored.isCompleted("middle"))
        assertTrue(restored.isCompleted("newest"))
    }

    @Test
    fun `a completion can be observed by a process that was already running`() {
        val preferences = MemorySharedPreferences()
        val firstProcess = newStore(preferences)
        val secondProcess = newStore(preferences)

        firstProcess.markCompleted("request-one")

        assertTrue(secondProcess.isCompleted("request-one"))
    }

    @Test
    fun `corrupt completion record fails open once and self-heals`() {
        val preferences = MemorySharedPreferences(
            initialValues = mapOf(ConfigExportDeferredCompletionStore.KEY to "{malformed"),
        )
        val completionStore = newStore(preferences)
        assertFalse(completionStore.isCompleted("request-one"))

        completionStore.markCompleted("request-one")
        assertTrue(completionStore.isCompleted("request-one"))
        val recreatedProcess = MemorySharedPreferences(initialValues = preferences.persistedAll)
        assertTrue(newStore(recreatedProcess).isCompleted("request-one"))
    }

    @Test
    fun `failed durable commit still suppresses a duplicate write in this process`() {
        val preferences = MemorySharedPreferences(commitResult = false)
        val completionStore = newStore(preferences)
        completionStore.markCompleted("request-one")

        assertTrue(completionStore.isCompleted("request-one"))
        val recreatedProcess = MemorySharedPreferences(initialValues = preferences.persistedAll)
        assertFalse("a failed durable write cannot survive process death",
            newStore(recreatedProcess).isCompleted("request-one"))
    }
}
