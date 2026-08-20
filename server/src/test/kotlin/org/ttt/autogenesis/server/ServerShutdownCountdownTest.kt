package org.ttt.autogenesis.server

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression coverage for the single-player shutdown countdown.
 *
 * The disconnect handler in [Application.serverModule] used to gate the
 * 15-second countdown behind `TurnHarness.isRunning() || WorldManager.isGameActive`.
 * Both flags stay `true` for the entire game lifetime — `loopJob.isActive`
 * does not flip to false while the coroutine is suspended on
 * `deferred.await()` inside [org.ttt.autogenesis.server.TurnHarness.awaitPlayerAction],
 * and `isGameActive` is set true at game start and only cleared on game-over
 * paths. The predicate was therefore always true once any turn had run, and
 * the disconnect handler always entered the 10-minute defer branch instead
 * of the 15-second countdown branch.
 *
 * The fix extracted [startSinglePlayerShutdownCountdown] and dropped the
 * defer logic entirely. These tests pin the corrected behavior by driving
 * the helper directly with a mock connection manager and a no-op `onExpire`
 * callback so `exitProcess(0)` is never invoked in the test JVM.
 */
class ServerShutdownCountdownTest
{
    @Test
    fun `countdown fires onExpire when no primary session remains after the grace window`() = runBlocking {
        val connectionManager = mockk<PlayerConnectionManager>()
        coEvery { connectionManager.hasAnyPrimarySession() } returns false
        var expireCalls = 0

        val job = startSinglePlayerShutdownCountdown(
            connectionManager = connectionManager,
            existingJob = null,
            delayMs = 50L,
            onExpire = { expireCalls += 1 }
        )
        job.join()

        assertEquals(
            1,
            expireCalls,
            "onExpire must fire exactly once when no PRIMARY session remains at expiry"
        )
    }

    @Test
    fun `countdown does not fire onExpire when a primary session reconnects during the grace window`() = runBlocking {
        val connectionManager = mockk<PlayerConnectionManager>()
        coEvery { connectionManager.hasAnyPrimarySession() } returns true
        var expireCalls = 0

        val job = startSinglePlayerShutdownCountdown(
            connectionManager = connectionManager,
            existingJob = null,
            delayMs = 50L,
            onExpire = { expireCalls += 1 }
        )
        job.join()

        assertEquals(
            0,
            expireCalls,
            "onExpire must NOT fire when a PRIMARY session is present at expiry"
        )
    }

    @Test
    fun `countdown cancels the existing job before arming a fresh one`() = runBlocking {
        val connectionManager = mockk<PlayerConnectionManager>()
        coEvery { connectionManager.hasAnyPrimarySession() } returns false
        val previousJob = mockk<Job>(relaxed = true)

        val newJob = startSinglePlayerShutdownCountdown(
            connectionManager = connectionManager,
            existingJob = previousJob,
            delayMs = 50L,
            onExpire = { }
        )

        verify { previousJob.cancel() }
        assertNotNull(newJob, "the function must return the newly armed countdown job")
        newJob.join()
    }

    @Test
    fun `countdown completes within a small fraction of the defer cap`() = runBlocking {
        // The previous defer cap was 10 minutes (600_000 ms). With the
        // regression in place the countdown never fired within 15 seconds,
        // it polled every 500 ms for 10 minutes instead. This test uses a
        // 200 ms delay and asserts the countdown completes well under the
        // 10-minute cap — i.e. it does not silently re-introduce defer
        // behavior.
        val connectionManager = mockk<PlayerConnectionManager>()
        coEvery { connectionManager.hasAnyPrimarySession() } returns false
        var fired = false

        val job = startSinglePlayerShutdownCountdown(
            connectionManager = connectionManager,
            existingJob = null,
            delayMs = 200L,
            onExpire = { fired = true }
        )
        job.join()

        assertEquals(true, fired, "onExpire must fire within the configured delayMs (no defer cap)")
    }
}