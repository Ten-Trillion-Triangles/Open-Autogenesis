package timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [CountdownTimer] functionality.
 * Uses coroutine test framework to control time advancement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CountdownTimerTest
{
    
    /**
     * Verifies timer counts down correctly and triggers finish callback.
     */
    @Test
    fun `timer ticks and finishes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val timer = CountdownTimer(callbackDispatcher = dispatcher, tickerDispatcher = dispatcher)

        val ticks = mutableListOf<Long>()
        var finished = false
        timer.onTick = { ticks += it.remainingSeconds }
        timer.onFinish = { finished = true }

        timer.start(seconds = 2)
        assertFalse(finished)
        assertEquals(2L, timer.timeSnapshot().remainingSeconds)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(1L), ticks)
        assertFalse(finished)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(1L, 0L), ticks)
        assertTrue(finished)
        assertEquals(0L, timer.timeSnapshot().remainingSeconds)
    }

    /**
     * Verifies pause preserves remaining time and resume continues countdown.
     */
    @Test
    fun `pause preserves remaining and resume continues`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val timer = CountdownTimer(callbackDispatcher = dispatcher, tickerDispatcher = dispatcher)

        timer.start(seconds = 3)
        advanceTimeBy(1_000)
        runCurrent()
        timer.pause()

        assertTrue(timer.isPaused)
        assertEquals(2, timer.timeSnapshot().remainingSeconds)

        // Time passes while paused - remaining should not change
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, timer.timeSnapshot().remainingSeconds)

        timer.resume()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, timer.timeSnapshot().remainingSeconds)
    }

    /**
     * Verifies reset and updateTime methods work correctly.
     */
    @Test
    fun `reset updates time instantly`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val timer = CountdownTimer(callbackDispatcher = dispatcher, tickerDispatcher = dispatcher)

        timer.start(seconds = 5)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, timer.timeSnapshot().remainingSeconds)

        timer.reset(minutes = 0, seconds = 10)
        runCurrent()
        val remainingAfterReset = timer.timeSnapshot().remainingSeconds
        assertEquals(10, remainingAfterReset, "remaining after reset was $remainingAfterReset")
        assertFalse(timer.isRunning)

        timer.updateTime(minutes = 0, seconds = 8)
        runCurrent()
        assertEquals(8, timer.timeSnapshot().remainingSeconds)
    }
}
