package org.ttt.autogenesis.audio

import kotlinx.serialization.Serializable

// ─── SERVER → CLIENT RPC ────────────────────────────────────────────────────

/**
 * Schedule one or more audio objects for future playback.
 * Sent from server to all connected clients.
 */
@Serializable
data class AudioSchedulePlay(
    val objects: List<AudioObject>,
    val serverTimestampMs: Long,
    val currentServerFrame: Long,
    val serverSampleRate: Float
)

/**
 * Query a specific client's full running audio state.
 */
@Serializable
data class AudioQueryState(
    val queryId: String
)

/**
 * Stop playback for an object.
 */
@Serializable
data class AudioStop(
    val objectId: String,
    val fadeOutDurationMs: Long = 0
)

/**
 * Real-time parameter update (volume, panning, or speed).
 */
@Serializable
data class AudioParamUpdate(
    val objectId: String,
    val param: String,
    val value: Float,
    val fadeDurationMs: Long = 0
)

/**
 * Channel volume / mute change.
 */
@Serializable
data class AudioChannelUpdate(
    val channelId: String,
    val volume: Float? = null,
    val muted: Boolean? = null
)

/**
 * Full audio state sync — sent to reconnecting or late-joining clients.
 *
 * The server-frame / sample-rate / frame-size fields are optional for backward
 * compatibility with older servers; clients fall back to wall-clock-only
 * scheduling (startTimeMs) when these are missing.
 */
@Serializable
data class AudioSyncState(
    val globalVolume: Float,
    val channels: List<AudioChannel>,
    val scheduledObjects: List<AudioObject>,
    val timestampMs: Long,
    val currentServerFrame: Long = 0L,
    val serverSampleRate: Float = 48000f,
    val serverFrameSize: Int = 128
)

// ─── CLIENT → SERVER RPC ──────────────────────────────────────────────────────

/**
 * Client reports its complete audio state in response to AudioQueryState.
 */
@Serializable
data class AudioReportState(
    val queryId: String,
    val globalVolume: Float,
    val channelStates: List<AudioChannelState>,
    val playingObjects: List<AudioObjectState>,
    val clientTimestampMs: Long
)

@Serializable
data class AudioChannelState(
    val channelId: String,
    val volume: Float,
    val muted: Boolean
)

@Serializable
data class AudioObjectState(
    val id: String,
    val resourceName: String,
    val channelId: String,
    val volume: Float,
    val panning: Float,
    val speed: Float,
    val loop: Boolean,
    val currentTimeMs: Long,
    val startedAtMs: Long,
    val isPlaying: Boolean,
    val isPaused: Boolean,
    val isEnded: Boolean
)

/**
 * Periodic position report for drift correction.
 */
@Serializable
data class AudioPositionReport(
    val objectId: String,
    val currentTimeMs: Long,
    val isPlaying: Boolean
)

/**
 * Client requests immediate audio for a game event (e.g., button click).
 */
@Serializable
data class AudioGameTrigger(
    val resourceName: String,
    val channelId: String,
    val volume: Float = 1.0f
)

/**
 * Client requests global volume change.
 */
@Serializable
data class AudioGlobalVolumeUpdate(
    val volume: Float
)
// ─── MUSIC SELECTOR (server → client) ────────────────────────────────────────

/**
 * Music-selector broadcast: sent from the server at the start of every
 * turn. The client runner (org.ttt.autogenesis.kvisionapp.audio.MusicRunner)
 * plays the new tracks and fades the old ones in a single atomic step.
 *
 * The timestamp / frame / sample-rate fields mirror [AudioSchedulePlay]'s
 * wire shape so the client can apply the same clock-offset logic if a
 * turn-driven music change ever needs to be scheduled for a future time
 * (for v1 the runner plays immediately on receipt).
 */
@Serializable
data class AudioMusicSchedule(
    val decision: MusicDecision,
    val serverTimestampMs: Long,
    val currentServerFrame: Long,
    val serverSampleRate: Float
)
