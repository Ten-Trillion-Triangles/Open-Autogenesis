package org.ttt.autogenesis.kvisionapp.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.audio.AudioChannel

/**
 * Unit tests for [AudioEngine.computeStartWhenSeconds].
 *
 * These tests pin down the correct unit conversions for the three
 * server-schedule modes (startSample, startFrame, startTimeMs). The
 * pre-fix code in the `startFrame` branch had a unit-mixing bug
 * (dividing elapsedSamples by 128.0 then multiplying the whole
 * expression by 128.0 — the units canceled only by accident).
 *
 * The helper is `internal` so it is testable from the same module
 * without exposing it to JS.
 */
class AudioEngineTest
{
    private val musicChannel = AudioChannel(id = "Music", name = "Music", parentId = null)

    // ─── startFrame branch (the previously buggy one) ────────────────────

    @Test
    fun startFrame_oneSecondFromNow_atStartOfSchedule()
    {
        // Setup: target frame is 1 second away at 48000 Hz / 128 samples per frame
        //   = 48000/128 frames = 375 frames.
        // currentServerFrame = 0, serverTimestampMs = nowMs → elapsedSeconds = 0
        // → expected startWhen = 375 * 128 / 48000 - 0 = 1.0
        val obj = audioObj(id = "test1", startFrame = 375L)

        val startWhen = AudioEngine.computeStartWhenSeconds(
            obj = obj,
            nowMs = 1_000_000L,
            serverTimestampMs = 1_000_000L,
            clockOffsetMs = 0L,
            currentServerFrame = 0L,
            serverSampleRate = 48000f,
            audioSampleRate = 48000f,
            serverFrameSize = 128
        )

        assertEquals(1.0, startWhen, 0.0001, "1 second of audio should produce startWhen=1.0")
    }

    @Test
    fun startFrame_compensatesForElapsedTime()
    {
        // Same as above but 250ms have elapsed since the schedule was sent.
        // target is still 375 frames (= 1 sec from frame 0).
        // elapsedSeconds = 0.25, so startWhen = 1.0 - 0.25 = 0.75
        val obj = audioObj(id = "test2", startFrame = 375L)

        val startWhen = AudioEngine.computeStartWhenSeconds(
            obj = obj,
            nowMs = 1_000_250L,
            serverTimestampMs = 1_000_000L,
            clockOffsetMs = -250L,
            currentServerFrame = 0L,
            serverSampleRate = 48000f,
            audioSampleRate = 48000f,
            serverFrameSize = 128
        )

        assertEquals(0.75, startWhen, 0.0001, "Should compensate for 250ms of elapsed time")
    }

    @Test
    fun startFrame_uses_serverFrameSize_not_hardcoded_128()
    {
        // With serverFrameSize = 256, targetSeconds = 100 * 256 / 48000 = 0.533...
        val obj = audioObj(id = "test3", startFrame = 100L)

        val startWhen = AudioEngine.computeStartWhenSeconds(
            obj = obj,
            nowMs = 1_000_000L,
            serverTimestampMs = 1_000_000L,
            clockOffsetMs = 0L,
            currentServerFrame = 0L,
            serverSampleRate = 48000f,
            audioSampleRate = 48000f,
            serverFrameSize = 256
        )

        val expected = 100.0 * 256.0 / 48000.0
        assertEquals(expected, startWhen, 0.0001, "Should scale by serverFrameSize=256, not hardcoded 128")
    }

    @Test
    fun startFrame_inThePast_returnsNegative()
    {
        // Target frame already passed (200ms ago) → startWhen should be negative
        val obj = audioObj(id = "test4", startFrame = 0L)

        val startWhen = AudioEngine.computeStartWhenSeconds(
            obj = obj,
            nowMs = 1_000_200L,
            serverTimestampMs = 1_000_000L,
            clockOffsetMs = -200L,
            currentServerFrame = 100L,
            serverSampleRate = 48000f,
            audioSampleRate = 48000f,
            serverFrameSize = 128
        )

        assertTrue(startWhen < 0.0, "Past target should produce negative startWhen (was: $startWhen)")
    }

    // ─── startSample branch ──────────────────────────────────────────────

    @Test
    fun startSample_zeroElapsed_returnsSampleIndexInSeconds()
    {
        // 48000 samples at 48000 Hz = 1.0 second. Elapsed = 0.
        val obj = audioObj(id = "test5", startSample = 48000L)

        val startWhen = AudioEngine.computeStartWhenSeconds(
            obj = obj,
            nowMs = 1_000_000L,
            serverTimestampMs = 1_000_000L,
            clockOffsetMs = 0L,
            currentServerFrame = 0L,
            serverSampleRate = 48000f,
            audioSampleRate = 48000f,
            serverFrameSize = 128
        )

        assertEquals(1.0, startWhen, 0.0001, "48000 samples at 48000 Hz should equal 1.0 seconds")
    }

    // ─── startTimeMs branch ──────────────────────────────────────────────

    @Test
    fun startTimeMs_isCorrectedByServerOffset()
    {
        // Pin down the exact formula: startWhen = (startTimeMs - clockOffsetMs) / 1000
        // We don't claim the formula is semantically right (that's a separate concern);
        // we just want it to be stable and unit-testable.
        val obj = audioObj(id = "test6", startTimeMs = 2_000L)

        val startWhen = AudioEngine.computeStartWhenSeconds(
            obj = obj,
            nowMs = 1_000_000L,
            serverTimestampMs = 1_000_000L,
            clockOffsetMs = 0L,
            currentServerFrame = 0L,
            serverSampleRate = 48000f,
            audioSampleRate = 48000f,
            serverFrameSize = 128
        )

        // startWhen = (2_000 - 0) / 1000 = 2.0
        assertEquals(2.0, startWhen, 0.0001)
    }

    @Test
    fun startTimeMs_offsetShiftsResult()
    {
        // With a 500ms server-clock offset, the same startTimeMs
        // produces a startWhen 500ms earlier.
        val obj = audioObj(id = "test7", startTimeMs = 2_000L)

        val startWhen = AudioEngine.computeStartWhenSeconds(
            obj = obj,
            nowMs = 1_000_000L,
            serverTimestampMs = 1_000_000L,
            clockOffsetMs = 500L,
            currentServerFrame = 0L,
            serverSampleRate = 48000f,
            audioSampleRate = 48000f,
            serverFrameSize = 128
        )

        // startWhen = (2_000 - 500) / 1000 = 1.5
        assertEquals(1.5, startWhen, 0.0001)
    }

    // ─── default branch ──────────────────────────────────────────────────

    @Test
    fun noTimeField_returnsZero()
    {
        val obj = audioObj(id = "test7") // no startSample, no startFrame, startTimeMs = 0

        val startWhen = AudioEngine.computeStartWhenSeconds(
            obj = obj,
            nowMs = 1_000_000L,
            serverTimestampMs = 1_000_000L,
            clockOffsetMs = 0L,
            currentServerFrame = 0L,
            serverSampleRate = 48000f,
            audioSampleRate = 48000f,
            serverFrameSize = 128
        )

        assertEquals(0.0, startWhen, 0.0001)
    }

    // ─── helper ──────────────────────────────────────────────────────────

    private fun audioObj(
        id: String,
        startSample: Long? = null,
        startFrame: Long? = null,
        startTimeMs: Long = 0
    ) = AudioObject(
        id = id,
        resourceName = "test",
        channelId = "Music",
        volume = 1.0f,
        panning = 0.0f,
        speed = 1.0f,
        loop = false,
        startTimeMs = startTimeMs,
        startSample = startSample,
        startFrame = startFrame
    )
}