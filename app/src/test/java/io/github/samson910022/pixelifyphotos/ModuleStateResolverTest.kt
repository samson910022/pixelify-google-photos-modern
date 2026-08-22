package io.github.samson910022.pixelifyphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ModuleStateResolver], the pure-Kotlin gate that owns the
 * cold-start wait between asynchronous service binding and the one-shot
 * enabled/disabled verdict.
 */
class ModuleStateResolverTest {

    private val eventLog = mutableListOf<String>()
    private val resolutions = mutableListOf<Boolean>()
    private val cancelledTokens = mutableListOf<Runnable>()
    private var registeredListener: (() -> Unit)? = null
    private var dispatchedBindResolution: Runnable? = null
    private var scheduledTimeout: Runnable? = null

    /** Optional hook invoked after recording; lets a test throw from onResolved. */
    private var resolveSink: ((Boolean) -> Unit)? = null
    private var hostDisposed = false

    private fun newResolver(): ModuleStateResolver = ModuleStateResolver(
        addServiceBoundListener = { listener ->
            eventLog.add("register")
            registeredListener = listener
        },
        removeServiceBoundListener = { eventLog.add("unregister") },
        dispatchToMain = { dispatchedBindResolution = it },
        scheduleGraceTimeout = {
            scheduledTimeout = it
            eventLog.add("arm")
        },
        cancelGraceTimeout = { cancelledTokens.add(it) },
        isHostDisposed = { hostDisposed },
        onResolved = { enabled ->
            resolutions.add(enabled)
            resolveSink?.invoke(enabled)
        },
    )

    /** Simulates App.onServiceBind firing the registered callback on a Binder thread. */
    private fun fireBindEvent() {
        assertNotNull(registeredListener)
        registeredListener!!.invoke()
    }

    /** Simulates the marshaled resolution executing on the main thread. */
    private fun runDispatchedResolution() {
        assertNotNull(dispatchedBindResolution)
        dispatchedBindResolution!!.run()
    }

    // =========================================================================
    // Lifecycle ordering
    // =========================================================================

    @Test
    fun `start registers listener before arming grace timeout`() {
        newResolver().start()

        assertEquals(listOf("register", "arm"), eventLog)
        assertNotNull(scheduledTimeout)
    }

    @Test
    fun `bind within window cancels timeout resolves true once and unregisters`() {
        val resolver = newResolver()
        resolver.start()
        eventLog.clear()

        fireBindEvent()
        runDispatchedResolution()

        assertTrue(cancelledTokens.contains(scheduledTimeout))
        assertEquals(listOf(true), resolutions)
        assertEquals(listOf("unregister"), eventLog)
    }

    @Test
    fun `grace expiry resolves false once and unregisters`() {
        newResolver().start()
        eventLog.clear()

        scheduledTimeout!!.run()

        assertEquals(listOf(false), resolutions)
        assertEquals(listOf("unregister"), eventLog)
    }

    // =========================================================================
    // Exactly-once semantics
    // =========================================================================

    @Test
    fun `late bind after expiry does not re-resolve`() {
        newResolver().start()

        scheduledTimeout!!.run()
        fireBindEvent()
        runDispatchedResolution()

        assertEquals(listOf(false), resolutions)
    }

    @Test
    fun `late expiry after bind does not double resolve`() {
        newResolver().start()

        fireBindEvent()
        runDispatchedResolution()
        scheduledTimeout!!.run()

        assertEquals(listOf(true), resolutions)
    }

    @Test
    fun `second start is ignored and leaves exactly one registration and one armed timeout`() {
        val resolver = newResolver()
        resolver.start()
        eventLog.clear()
        val firstListener = registeredListener
        val firstTimeout = scheduledTimeout

        resolver.start()

        // No duplicate side effects: same single listener and timeout token remain.
        assertEquals(emptyList<String>(), eventLog)
        assertEquals(firstListener, registeredListener)
        assertEquals(firstTimeout, scheduledTimeout)

        // The wait still resolves exactly once through the original wiring.
        fireBindEvent()
        runDispatchedResolution()
        scheduledTimeout!!.run()
        assertEquals(listOf(true), resolutions)
    }

    // =========================================================================
    // Disposal and host guards
    // =========================================================================

    @Test
    fun `dispose suppresses pending resolution and is idempotent`() {
        val resolver = newResolver()
        resolver.start()

        fireBindEvent()
        resolver.dispose()
        resolver.dispose()
        runDispatchedResolution()
        scheduledTimeout!!.run()

        assertEquals(emptyList<Boolean>(), resolutions)
    }

    @Test
    fun `start after dispose is a no-op without registering or arming`() {
        val resolver = newResolver()
        resolver.dispose()
        eventLog.clear()

        resolver.start()

        assertEquals(emptyList<String>(), eventLog)
        assertNull(registeredListener)
        assertNull(scheduledTimeout)
        assertEquals(emptyList<Boolean>(), resolutions)
    }

    @Test
    fun `host disposed at execution time permanently suppresses verdict`() {
        newResolver().start()

        fireBindEvent()
        hostDisposed = true
        runDispatchedResolution()
        assertEquals(emptyList<Boolean>(), resolutions)

        // Host recovers afterwards; the verdict must still never resurrect.
        hostDisposed = false
        scheduledTimeout!!.run()

        assertEquals(emptyList<Boolean>(), resolutions)
        assertTrue(eventLog.contains("unregister"))
    }

    // =========================================================================
    // Consumer fault isolation
    // =========================================================================

    @Test
    fun `throwing consumer does not corrupt single-shot state or cleanup`() {
        resolveSink = { error("boom") }
        val resolver = newResolver()
        resolver.start()
        eventLog.clear()

        fireBindEvent()
        try {
            runDispatchedResolution()
        } catch (_: IllegalStateException) {
            // Expected from the throwing sink.
        }

        assertEquals(listOf(true), resolutions)
        assertEquals(listOf("unregister"), eventLog)
        assertTrue(cancelledTokens.contains(scheduledTimeout))

        // State is consumed despite the throw: no further verdicts fire.
        scheduledTimeout!!.run()
        assertEquals(1, resolutions.size)
    }
}
