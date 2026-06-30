package org.ttt.autogenesis.server.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.serializer
import org.ttt.autogenesis.audio.*
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage

/**
 * Server-side authoritative audio state tracker.
 * Directs audio playback across all connected clients without
 * duplicating Web Audio API — it tracks what *should* be playing.
 *
 * AudioManager is a singleton object. It does NOT run Web Audio API.
 * Client browsers run AudioEngine which executes actual playback.
 */
object AudioManager {
    private val log = Logger

    // Registered audio channels
    val channels: MutableMap<String, AudioChannel> = mutableMapOf(
        "Music" to AudioChannel(id = "Music", name = "Music"),
        "Sfx" to AudioChannel(id = "Sfx", name = "SFX")
    )

    // Currently scheduled/playing audio objects (by object id)
    val playingObjects: MutableMap<String, ScheduledAudio> = mutableMapOf()

    // Global volume (0.0–1.0)
    var globalVolume: Float = 1.0f

    // Client position reports: playerId → last report timestamp
    private val clientLastReport: MutableMap<String, Long> = mutableMapOf()

    // Pending query responses: queryId → deferred response
    private val pendingQueries: MutableMap<String, CompletableDeferred<AudioReportState>> = mutableMapOf()

    // Server frame counter (incremented each scheduling tick)
    private var serverFrameCounter: Long = 0L

    // Sample rate reference (44100 Hz typical)
    const val SERVER_SAMPLE_RATE = 44100f

    /**
     * Server's render quantum in samples (128 frames, matching Web Audio spec).
     */
    const val SERVER_RENDER_QUANTUM = 128

    init {
        // Emit a single bootstrap line so log readers can confirm the
        // singleton was loaded and the default channel tree is in place.
        log.info(
            LogCategory.SYSTEM,
            "AudioManager: singleton initialized with ${channels.size} default channels " +
            "(${channels.keys.joinToString()}), globalVolume=$globalVolume, " +
            "serverSampleRate=$SERVER_SAMPLE_RATE renderQuantum=$SERVER_RENDER_QUANTUM"
        )
    }

    /**
     * Increment the server frame counter.
     * Called by the game's main loop or turn processor.
     */
    fun tickFrame() {
        serverFrameCounter++
        if (serverFrameCounter % 600L == 0L)
        {
            // Throttled heartbeat — every ~10s of frame ticks at 60Hz.
            // Useful to see "audio system is alive" in the log without
            // spamming every frame.
            log.debug(
                LogCategory.SYSTEM,
                "AudioManager.tickFrame: frame=$serverFrameCounter (heartbeat), playingObjects=${playingObjects.size}"
            )
        }
    }

    /**
     * Schedule one or more audio objects for playback on all clients.
     * Broadcasts AudioSchedulePlay to every connected client via PlayerConnectionManager.
     */
    internal fun schedulePlay(
        objects: List<AudioObject>,
        connectionManager: org.ttt.autogenesis.server.PlayerConnectionManager?
    ) {
        val timestampMs = System.currentTimeMillis()
        log.info(
            LogCategory.GENERAL,
            "AudioManager.schedulePlay: ENTER count=${objects.size} " +
            "ids=[${objects.take(5).joinToString { it.id }}${if (objects.size > 5) ", ..." else ""}] " +
            "resources=[${objects.take(5).joinToString { "'${it.resourceName}'" }}${if (objects.size > 5) ", ..." else ""}]"
        )
        for (obj in objects) {
            val scheduled = ScheduledAudio(
                id = obj.id,
                resourceName = obj.resourceName,
                channelId = obj.channelId,
                volume = obj.volume,
                panning = obj.panning,
                speed = obj.speed,
                loop = obj.loop,
                scheduledStartMs = timestampMs,
                startTimeMs = obj.startTimeMs,
                startFrame = obj.startFrame,
                startSample = obj.startSample,
                endTimeMs = obj.endTimeMs,
                fadeInDurationMs = obj.fadeInDurationMs,
                fadeOutDurationMs = obj.fadeOutDurationMs,
                loopStart = obj.loopStart,
                loopEnd = obj.loopEnd
            )
            playingObjects[obj.id] = scheduled
        }

        val frame = serverFrameCounter
        val payload = AudioSchedulePlay(
            objects = objects,
            serverTimestampMs = timestampMs,
            currentServerFrame = frame,
            serverSampleRate = SERVER_SAMPLE_RATE
        )
        val jsonPayload = RpcJson.encodeToJsonElement(serializer<AudioSchedulePlay>(), payload)
        log.info(
            LogCategory.GENERAL,
            "AudioManager.schedulePlay: scheduled ${objects.size} audio objects, frame=$frame " +
            "(playingObjects now=${playingObjects.size})"
        )
        // Broadcast is called from a coroutine context in the calling code
        GlobalScope.launch {
            connectionManager?.broadcast(RpcMessage.Notification("audio.schedulePlay", jsonPayload))
            log.debug(
                LogCategory.GENERAL,
                "AudioManager.schedulePlay: broadcast dispatched count=${objects.size} frame=$frame"
            )
        }
    }

    /**
     * Stop a playing audio object.
     */
    internal fun stop(objectId: String, fadeOutDurationMs: Long, connectionManager: org.ttt.autogenesis.server.PlayerConnectionManager?) {
        val removed = playingObjects.remove(objectId)
        if (removed == null)
        {
            log.warn(
                LogCategory.GENERAL,
                "AudioManager.stop: objectId='$objectId' was not in playingObjects (no-op), fadeOutMs=$fadeOutDurationMs"
            )
        }
        val payload = AudioStop(objectId = objectId, fadeOutDurationMs = fadeOutDurationMs)
        val jsonPayload = RpcJson.encodeToJsonElement(serializer<AudioStop>(), payload)
        log.info(
            LogCategory.GENERAL,
            "AudioManager.stop: objectId='$objectId' fadeOutMs=$fadeOutDurationMs (playingObjects now=${playingObjects.size})"
        )
        GlobalScope.launch {
            connectionManager?.broadcast(RpcMessage.Notification("audio.stop", jsonPayload))
        }
    }

    /**
     * Update a channel's volume and/or mute state.
     */
    internal fun updateChannel(channelId: String, volume: Float?, muted: Boolean?, connectionManager: org.ttt.autogenesis.server.PlayerConnectionManager?) {
        val channel = channels[channelId]
        if (channel == null)
        {
            log.warn(
                LogCategory.GENERAL,
                "AudioManager.updateChannel: unknown channelId='$channelId' (no broadcast) — " +
                "known channels: ${channels.keys}"
            )
            return
        }
        val updated = channel.copy(
            volume = volume ?: channel.volume,
            muted = muted ?: channel.muted
        )
        channels[channelId] = updated
        val payload = AudioChannelUpdate(channelId = channelId, volume = volume, muted = muted)
        val jsonPayload = RpcJson.encodeToJsonElement(serializer<AudioChannelUpdate>(), payload)
        log.info(
            LogCategory.GENERAL,
            "AudioManager.updateChannel: channelId='$channelId' " +
            "volume: ${channel.volume} → ${updated.volume} " +
            "muted: ${channel.muted} → ${updated.muted}"
        )
        GlobalScope.launch {
            connectionManager?.broadcast(RpcMessage.Notification("audio.channelUpdate", jsonPayload))
        }
    }

    /**
     * Set global volume and broadcast to all clients.
     */
    internal fun setGlobalVolume(volume: Float, connectionManager: org.ttt.autogenesis.server.PlayerConnectionManager?) {
        val previous = globalVolume
        globalVolume = volume
        log.info(
            LogCategory.GENERAL,
            "AudioManager.setGlobalVolume: $previous → $volume (will broadcast to all active objects)"
        )
        // Broadcast a null-param update to all active objects as a signal to read globalVolume
        GlobalScope.launch {
            val payload = AudioParamUpdate(
                objectId = "",  // empty id signals global volume change
                param = "globalVolume",
                value = volume,
                fadeDurationMs = 0
            )
            val jsonPayload = RpcJson.encodeToJsonElement(serializer<AudioParamUpdate>(), payload)
            connectionManager?.broadcast(RpcMessage.Notification("audio.paramUpdate", jsonPayload))
            log.debug(
                LogCategory.GENERAL,
                "AudioManager.setGlobalVolume: globalVolume broadcast dispatched (volume=$volume)"
            )
        }
    }

    /**
     * Broadcast a music-selector decision to all clients. Used by
     * [MusicSelector] at the start of every turn to drive a per-turn
     * fade-out / fade-in transition on every connected client.
     *
     * The payload travels as a single `audio.musicSchedule`
     * notification so the client runner can apply the new tracks and
     * the fade-outs atomically.
     */
    internal fun broadcastMusicSchedule(
        decision: org.ttt.autogenesis.audio.MusicDecision,
        connectionManager: org.ttt.autogenesis.server.PlayerConnectionManager?
    )
    {
        val timestampMs = System.currentTimeMillis()
        log.info(
            LogCategory.GENERAL,
            "AudioManager.broadcastMusicSchedule: ENTER " +
            "toPlay=${decision.toPlay.size} toFadeOut=${decision.toFadeOut.size} " +
            "fadeOutMs=${decision.fadeOutDurationMs} fadeInMs=${decision.fadeInDurationMs} " +
            "playingIdsBefore=${playingObjects.size}"
        )
        for(obj in decision.toPlay)
        {
            val scheduled = ScheduledAudio(
                id = obj.id,
                resourceName = obj.resourceName,
                channelId = obj.channelId,
                volume = obj.volume,
                panning = obj.panning,
                speed = obj.speed,
                loop = obj.loop,
                scheduledStartMs = timestampMs,
                startTimeMs = obj.startTimeMs,
                startFrame = obj.startFrame,
                startSample = obj.startSample,
                endTimeMs = obj.endTimeMs,
                fadeInDurationMs = obj.fadeInDurationMs,
                fadeOutDurationMs = obj.fadeOutDurationMs,
                loopStart = obj.loopStart,
                loopEnd = obj.loopEnd
            )
            playingObjects[obj.id] = scheduled
        }
        for(id in decision.toFadeOut)
        {
            playingObjects.remove(id)
        }
        val frame = serverFrameCounter
        val payload = AudioMusicSchedule(
            decision = decision,
            serverTimestampMs = timestampMs,
            currentServerFrame = frame,
            serverSampleRate = SERVER_SAMPLE_RATE
        )
        val jsonPayload = RpcJson.encodeToJsonElement(serializer<AudioMusicSchedule>(), payload)
        log.info(
            LogCategory.GENERAL,
            "Music schedule broadcast: +${decision.toPlay.size} tracks, -${decision.toFadeOut.size} fade-outs, " +
            "frame=$frame, playingObjects now=${playingObjects.size}, " +
            "resources=[${decision.toPlay.joinToString { "'${it.resourceName}'" }}]"
        )
        GlobalScope.launch {
            connectionManager?.broadcast(RpcMessage.Notification("audio.musicSchedule", jsonPayload))
            log.debug(
                LogCategory.GENERAL,
                "AudioManager.broadcastMusicSchedule: broadcast dispatched (frame=$frame)"
            )
        }
    }

    /**
     * Handle a position report from a client.
     */
    fun onPositionReport(playerId: String, report: AudioPositionReport) {
        clientLastReport[playerId] = System.currentTimeMillis()
        log.debug(
            LogCategory.NETWORK,
            "AudioManager.onPositionReport: playerId='$playerId' " +
            "objectId='${report.objectId}' currentTimeMs=${report.currentTimeMs} isPlaying=${report.isPlaying}"
        )
    }

    /**
     * Handle a full state report from a client (response to AudioQueryState).
     */
    fun onStateReport(queryId: String, state: AudioReportState) {
        val deferred = pendingQueries.remove(queryId)
        if (deferred != null) {
            deferred.complete(state)
            log.info(
                LogCategory.NETWORK,
                "AudioManager.onStateReport: deferred completed queryId='$queryId' " +
                "(pendingQueries now=${pendingQueries.size})"
            )
        } else {
            log.warn(
                LogCategory.NETWORK,
                "AudioManager.onStateReport: no pending query for queryId='$queryId' " +
                "(state arrived late or query was already answered — dropping)"
            )
        }
    }

    /**
     * Build a full AudioSyncState snapshot for a client (late join / reconnect).
     */
    fun buildSyncState(): AudioSyncState {
        val snapshot = AudioSyncState(
            globalVolume = globalVolume,
            channels = channels.values.toList(),
            scheduledObjects = playingObjects.values.map { it.toAudioObject() },
            timestampMs = System.currentTimeMillis()
        )
        log.info(
            LogCategory.SYSTEM,
            "AudioManager.buildSyncState: globalVolume=${snapshot.globalVolume} " +
            "channels=${snapshot.channels.size} scheduledObjects=${snapshot.scheduledObjects.size}"
        )
        return snapshot
    }
}

/**
 * Server-side audio scheduling record — not serialized, JVM-only.
 */
data class ScheduledAudio(
    val id: String,
    val resourceName: String,
    val channelId: String,
    val volume: Float,
    val panning: Float,
    val speed: Float,
    val loop: Boolean,
    val scheduledStartMs: Long,
    val startTimeMs: Long,
    val startFrame: Long?,
    val startSample: Long?,
    val endTimeMs: Long?,
    val fadeInDurationMs: Long,
    val fadeOutDurationMs: Long,
    val loopStart: Double? = null,
    val loopEnd: Double? = null
) {
    fun toAudioObject() = AudioObject(
        id = id,
        resourceName = resourceName,
        channelId = channelId,
        volume = volume,
        panning = panning,
        speed = speed,
        loop = loop,
        startTimeMs = startTimeMs,
        startFrame = startFrame,
        startSample = startSample,
        endTimeMs = endTimeMs,
        fadeInDurationMs = fadeInDurationMs,
        fadeOutDurationMs = fadeOutDurationMs,
        loopStart = loopStart,
        loopEnd = loopEnd
    )
}
