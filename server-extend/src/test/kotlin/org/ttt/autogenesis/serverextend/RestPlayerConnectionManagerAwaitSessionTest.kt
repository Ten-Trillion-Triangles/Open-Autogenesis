package org.ttt.autogenesis.serverextend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RestPlayerConnectionManager.awaitSession], the wait primitive
 * that closes the SSE/POST race in the `POST /rpc` handler.
 *
 * Uses real wall time (via [runBlocking]) so the `awaitSession` polling loop
 * and the deferred `register(...)` call land on the same scheduling reality
 * as production. `runTest` would not work here because its virtual-time
 * scheduler keeps the polling loop spinning without giving the
 * `Dispatchers.Default` register job a chance to land.
 *
 * Every @Test body ends with `Unit` to keep the test functions void for JUnit,
 * since some kotlin.test assertions (e.g. `assertIs<T>`) return `T`.
 */
class RestPlayerConnectionManagerAwaitSessionTest
{
    private val manager = RestPlayerConnectionManager()

    @After
    fun resetRegisterDelay()
    {
        // Keep production behavior deterministic in case another test depends on it.
        manager.registerDelayMillis = 0
    }

    @Test
    fun awaitSessionReturnsImmediatelyWhenAlreadyRegistered() : Unit = runBlocking {
        val registered = manager.register("alice")
        val resolved = withTimeout(500) {
            manager.awaitSession("alice", timeoutMillis = 1_000, pollIntervalMillis = 25)
        }
        assertNotNull(resolved, "expected to find the already-registered session")
        assertEquals(registered.session, resolved)
        Unit
    }

    @Test
    fun awaitSessionWaitsAndReturnsOnceRegistered() : Unit = runBlocking {
        // Defer registration by 50ms to simulate a slow SSE handler.
        val registrationJob = async(Dispatchers.Default) {
            delay(50)
            manager.register("bob")
        }

        val resolved = withTimeout(1_500) {
            manager.awaitSession("bob", timeoutMillis = 1_000, pollIntervalMillis = 25)
        }
        assertNotNull(resolved, "expected to observe the session once registration completes")
        // The same session object the deferred registration produced is the one
        // awaitSession observed. We compare via a public-typed local so the
        // test's public API does not expose the `internal` ConnectionRegistration type.
        val same: Boolean = registrationJob.await().session === resolved
        assertTrue(same, "expected the session returned by awaitSession to be the same instance registered")
        Unit
    }

    @Test
    fun awaitSessionReturnsNullOnTimeout() : Unit = runBlocking {
        val started = System.currentTimeMillis()
        val resolved = manager.awaitSession("ghost", timeoutMillis = 100, pollIntervalMillis = 25)
        val elapsed = System.currentTimeMillis() - started
        assertNull(resolved, "no session was ever registered; awaitSession should return null")
        assertTrue(elapsed >= 90, "awaitSession returned too early: ${elapsed}ms (expected ~100ms)")
        assertTrue(elapsed < 500, "awaitSession blocked too long: ${elapsed}ms (expected ~100ms)")
        Unit
    }

    @Test
    fun awaitSessionReturnsNullForUnknownPlayerId() : Unit = runBlocking {
        manager.register("carol")
        val resolved = manager.awaitSession("nobody", timeoutMillis = 100, pollIntervalMillis = 25)
        assertNull(resolved)
        Unit
    }

    @Test
    fun awaitSessionRespectsCancellation() : Unit = runBlocking {
        // Use async so the awaitSession call's CancellationException propagates
        // when we cancel and then await() the deferred. Plain `launch` + `join`
        // swallows the cancellation because `join` itself never throws.
        val deferred = async(Dispatchers.Default) {
            // We never register "dave" — the call is meant to block until cancelled.
            manager.awaitSession("dave", timeoutMillis = 5_000, pollIntervalMillis = 25)
        }
        // Yield long enough for awaitSession to enter its polling loop.
        delay(50)
        deferred.cancel()
        val outcome = runCatching { deferred.await() }
        assertTrue(outcome.isFailure, "expected the cancelled awaitSession call to fail, got: $outcome")
        val ex = outcome.exceptionOrNull()
        assertIs<CancellationException>(ex, "expected CancellationException, got ${ex?.javaClass?.name}")
        Unit
    }

    @Test
    fun awaitSessionPicksUpSessionMidPollWithoutOvershootingTimeout() : Unit = runBlocking {
        // Register after 80ms with a 500ms budget to verify the wait returns promptly
        // once the session appears (not after the full timeout elapses).
        val registrationJob = async(Dispatchers.Default) {
            delay(80)
            manager.register("erin")
        }
        val started = System.currentTimeMillis()
        val resolved = manager.awaitSession("erin", timeoutMillis = 500, pollIntervalMillis = 25)
        val elapsed = System.currentTimeMillis() - started
        assertNotNull(resolved, "expected to observe the session")
        assertTrue(elapsed < 400, "awaitSession took ${elapsed}ms; should have returned shortly after registration (~80-150ms)")
        // Await the registration job without exposing its internal return type.
        @Suppress("UNUSED_VARIABLE")
        val unused: Any? = registrationJob.await()
        Unit
    }
}