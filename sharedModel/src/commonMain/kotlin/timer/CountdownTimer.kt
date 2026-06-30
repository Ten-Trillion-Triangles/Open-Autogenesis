package timer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Lightweight countdown timer that emits tick events every second and exposes binding hooks for the
 * caller. Designed to be shared between client/server without assuming Android-main-loop behavior; the
 * caller may provide their preferred dispatcher for callback delivery.
 *
 * Timer can be paused with [pause] and resumed with [resume]. Use [timeSnapshot] to get current [TimerSnapshot] state.
 *
 * @param callbackDispatcher where events such as [onTick], [onFinish], etc. are executed.
 * @param tickerDispatcher dispatcher for internal timer coroutine execution.
 */
class CountdownTimer(
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val tickerDispatcher: CoroutineDispatcher = Dispatchers.Default
)
{
    private val timerScope = CoroutineScope(SupervisorJob() + tickerDispatcher)
    private var tickerJob: Job? = null
    private var paused = false
    private var initialDurationSeconds = 0L
    private val _remainingSeconds = MutableStateFlow(0L)
    
    /** StateFlow of remaining seconds, updated every tick */
    val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

    /** Callback invoked when timer starts or resumes */
    var onStart: (() -> Unit)? = null
    
    /** Callback invoked on each tick with current timer state */
    var onTick: ((TimerSnapshot) -> Unit)? = null
    
    /** Callback invoked when timer is paused */
    var onPause: (() -> Unit)? = null
    
    /** Callback invoked when timer is stopped */
    var onStop: (() -> Unit)? = null
    
    /** Callback invoked when timer reaches zero */
    var onFinish: (() -> Unit)? = null

    /** True if timer is actively counting down */
    val isRunning: Boolean
        get() = tickerJob?.isActive == true && !paused

    /** True if timer is paused but retains remaining time */
    val isPaused: Boolean
        get() = paused

    /**
     * Starts the countdown timer with specified duration.
     *
     * @param minutes Minutes to count down (minimum 0)
     * @param seconds Additional seconds to count down (minimum 0)
     */
    fun start(minutes: Int = 0, seconds: Int = 0): Unit
    {
        val totalSeconds = minutes.toLong().coerceAtLeast(0L) * 60 + seconds.toLong().coerceAtLeast(0L)
        
        if(totalSeconds > 0L)
        {
            initialDurationSeconds = totalSeconds
            setRemainingSeconds(totalSeconds)
        }
        
        if(_remainingSeconds.value <= 0L)
        {
            Logger.warn(LogCategory.SYSTEM, "CountdownTimer: Cannot start with 0 seconds.")
            return
        }

        paused = false
        signal(onStart)
        beginTicker()
    }

    /**
     * Pauses the timer, preserving remaining time.
     * Timer can be resumed with [resume].
     */
    fun pause(): Unit
    {
        if(!isRunning)
        {
            return
        }

        paused = true
        tickerJob?.cancel()
        tickerJob = null
        signal(onPause)
    }

    /**
     * Resumes a paused timer from where it left off.
     * No effect if timer is not paused or has no remaining time.
     */
    fun resume(): Unit
    {
        if(!paused || _remainingSeconds.value <= 0L)
        {
            return
        }

        paused = false
        signal(onStart)
        beginTicker()
    }

    /**
     * Stops the timer and resets remaining time to zero.
     * Timer must be restarted with [start] to count down again.
     */
    fun stop(): Unit
    {
        tickerJob?.cancel()
        tickerJob = null
        paused = false
        setRemainingSeconds(0L)
        signal(onStop)
    }

    /**
     * Resets timer to specified duration without starting countdown.
     *
     * @param minutes Minutes for new duration (minimum 0)
     * @param seconds Additional seconds for new duration (minimum 0)
     */
    fun reset(minutes: Int = 0, seconds: Int = 0): Unit
    {
        val totalSeconds = minutes.toLong().coerceAtLeast(0L) * 60 + seconds.toLong().coerceAtLeast(0L)
        initialDurationSeconds = totalSeconds
        setRemainingSeconds(totalSeconds)
        paused = false
        tickerJob?.cancel()
        tickerJob = null
    }

    /**
     * Updates remaining time without affecting timer state.
     *
     * @param minutes Minutes for updated time (minimum 0)
     * @param seconds Additional seconds for updated time (minimum 0)
     */
    fun updateTime(minutes: Int = 0, seconds: Int = 0): Unit
    {
        val totalSeconds = minutes.toLong().coerceAtLeast(0L) * 60 + seconds.toLong().coerceAtLeast(0L)
        setRemainingSeconds(totalSeconds)
    }

    /**
     * Returns current timer state as immutable snapshot.
     *
     * @return [TimerSnapshot] with current timer values
     */
    fun timeSnapshot(): TimerSnapshot
    {
        val secs = remainingSeconds.value
        return TimerSnapshot(
            remainingSeconds = secs,
            minutes = (secs / 60).toInt(),
            seconds = (secs % 60).toInt(),
            isRunning = isRunning,
            isPaused = isPaused
        )
    }

    /**
     * Advances or reduces remaining time by specified seconds.
     * Negative values reduce time, positive values add time.
     * Remaining time cannot go below zero.
     *
     * @param seconds Seconds to add (positive) or subtract (negative)
     */
    fun advanceBy(seconds: Long): Unit
    {
        val current = remainingSeconds.value
        setRemainingSeconds(maxOf(0L, current + seconds))
    }

    /**
     * Cancels all timer operations and cleans up resources.
     * Timer cannot be used after disposal.
     */
    fun dispose(): Unit
    {
        timerScope.coroutineContext.cancelChildren()
    }

    /**
     * Starts the internal ticker coroutine that decrements remaining time every second.
     */
    private fun beginTicker(): Unit
    {
        tickerJob?.cancel()
        Logger.info(LogCategory.SYSTEM, "CountdownTimer: beginTicker started for ${_remainingSeconds.value}s")
        tickerJob = timerScope.launch {
            while(isActive && _remainingSeconds.value > 0L && !paused)
            {
                delay(1_000)
                val updated = maxOf(0L, _remainingSeconds.value - 1L)
                setRemainingSeconds(updated)
                Logger.debug(LogCategory.SYSTEM, "CountdownTimer: Ticking... remaining=$updated")
                signal(onTick) { timeSnapshot() }
                if(updated == 0L)
                {
                    Logger.info(LogCategory.SYSTEM, "CountdownTimer: Finished.")
                    signal(onFinish)
                    tickerJob?.cancel()
                    tickerJob = null
                }
            }
        }
    }

    /**
     * Updates the remaining seconds state flow.
     */
    private fun setRemainingSeconds(value: Long): Unit
    {
        _remainingSeconds.value = value
    }

    /**
     * Executes callback on the configured callback dispatcher.
     */
    private fun signal(action: (() -> Unit)?): Unit
    {
        val callback = action ?: return
        timerScope.launch(callbackDispatcher) {
            callback()
        }
    }

    /**
     * Executes callback with payload on the configured callback dispatcher.
     */
    private fun <T> signal(action: ((T) -> Unit)?, payload: () -> T): Unit
    {
        val callback = action ?: return
        timerScope.launch(callbackDispatcher) {
            callback(payload())
        }
    }
}

/**
 * Immutable snapshot of timer state at a specific point in time.
 *
 * @property remainingSeconds Total seconds remaining in the countdown
 * @property minutes Minutes portion of remaining time
 * @property seconds Seconds portion of remaining time (0-59)
 * @property isRunning Whether the timer is currently active and counting down
 * @property isPaused Whether the timer is paused but retains its remaining time
 */
data class TimerSnapshot(
    val remainingSeconds: Long,
    val minutes: Int,
    val seconds: Int,
    val isRunning: Boolean,
    val isPaused: Boolean
)