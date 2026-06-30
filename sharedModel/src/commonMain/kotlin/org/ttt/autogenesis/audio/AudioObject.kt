package org.ttt.autogenesis.audio

import kotlinx.serialization.Serializable

/**
 * Generates a random UUID string suitable for multiplatform usage.
 */
private fun generateUuid(): String {
    val bytes = kotlin.random.Random.nextBytes(16)
    return bytes.joinToString("") { byte ->
        val hex = (byte.toInt() and 0xFF).toString(16)
        if (hex.length == 1) "0$hex" else hex
    }
}

/**
 * Represents an audio object in the game — the settings for a piece of audio
 * that can be played. Serialized and visible to both JVM server (for tracking)
 * and JS browser client (for Web Audio execution).
 *
 * @param id Unique identifier for this audio object instance
 * @param resourceName Fuzzy-matched audio file name (e.g., "music.battle.theme")
 * @param channelId Which channel this object plays on (e.g., "Music", "Sfx")
 * @param volume 0.0–1.0, percentage of file's original volume
 * @param panning -1.0 (full left) to +1.0 (full right)
 * @param speed Playback rate multiplier (0.25–4.0)
 * @param loop Whether to loop playback
 * @param startTimeMs Wall-clock start time in epoch ms (server sets this; 0 = immediate)
 * @param startFrame Frame-relative start (server sets this; null = use startTimeMs)
 * @param startSample Sample-relative start (server sets this; null = use startFrame or startTimeMs)
 * @param endTimeMs Wall-clock end time in epoch ms (null = play to file end)
 * @param fadeInDurationMs Fade-in duration in milliseconds
 * @param fadeOutDurationMs Fade-out duration in milliseconds
 * @param loopStart Loop region start, in seconds inside the buffer (null = start of file).
 *   Only consulted when [loop] is true. Frame- and sample-relative inputs are
 *   converted client-side using `buffer.sampleRate` before being placed on the wire.
 * @param loopEnd Loop region end, in seconds inside the buffer (null = end of file).
 *   Only consulted when [loop] is true. If both are null, the whole file loops
 *   (existing behaviour).
 * @param loopWithTail When true, looping is achieved by spawning a new
 *   `AudioObjectPlayer` alongside the existing one (the new source starts at
 *   the loop point — see [loopEnd] — or at the file end if no region is
 *   configured), while the existing source continues to its natural end of
 *   buffer so any baked-in tail (reverb / decay) plays out. When false (the
 *   default), the existing Web Audio API native loop is used. Only consulted
 *   when [loop] is also true; ignored otherwise.
 */
@Serializable
data class AudioObject(
    val id: String = generateUuid(),
    val resourceName: String,
    val channelId: String,
    val volume: Float = 1.0f,
    val panning: Float = 0.0f,
    val speed: Float = 1.0f,
    val loop: Boolean = false,
    val startTimeMs: Long = 0,
    val startFrame: Long? = null,
    val startSample: Long? = null,
    val endTimeMs: Long? = null,
    val fadeInDurationMs: Long = 0,
    val fadeOutDurationMs: Long = 0,
    val loopStart: Double? = null,
    val loopEnd: Double? = null,
    val loopWithTail: Boolean = false
)

/**
 * Runtime state for an audio object — computed locally on the JS client,
 * NOT transmitted over the network. Exposed via AudioObjectPlayer.tick().
 */
interface AudioObjectView {
    val id: String
    val currentTimeMs: Long
    val currentFrame: Int
    /**
     * Last analyser time-domain sample buffer. Reserved for future
     * remote-state reporting; the current implementations declare it for
     * interface conformance but no consumer reads it. Suppress the
     * "unused" warning until a real consumer materializes.
     */
    @Suppress("unused")
    val sampleData: FloatArray?
    val isPlaying: Boolean
    val isPaused: Boolean
    val isEnded: Boolean
    val volume: Float
    val panning: Float
    val speed: Float
    fun tick()
}