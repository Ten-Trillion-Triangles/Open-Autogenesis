package org.ttt.autogenesis.kvisionapp.audio

import org.ttt.autogenesis.audio.AudioChannelState
import org.ttt.autogenesis.audio.AudioMusicSchedule
import org.ttt.autogenesis.audio.AudioChannelUpdate
import org.ttt.autogenesis.audio.AudioObjectState
import org.ttt.autogenesis.audio.AudioParamUpdate
import org.ttt.autogenesis.audio.AudioQueryState
import org.ttt.autogenesis.audio.AudioReportState
import org.ttt.autogenesis.audio.AudioSchedulePlay
import org.ttt.autogenesis.audio.AudioStop
import org.ttt.autogenesis.audio.AudioSyncState
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcInvoker
import org.ttt.autogenesis.network.RpcMethod
import ui.gameplay.AudioSettings

/**
 * RPC handlers for SERVER → CLIENT audio notifications.
 * Receives and executes audio commands from the game server.
 */
object AudioClientHandlers
{
    private val log = Logger
    private var rpcInvoker: RpcInvoker? = null

    init {
        log.info(
            LogCategory.SYSTEM,
            "AudioClientHandlers: singleton initialized (waiting for configure(invoker) call)"
        )
    }

    /**
     * Configure the RPC invoker for sending responses back to server.
     */
    fun configure(invoker: RpcInvoker)
    {
        log.info(
            LogCategory.SYSTEM,
            "AudioClientHandlers.configure: invoker wired — outgoing audio RPCs now possible"
        )
        rpcInvoker = invoker
    }

    /**
     * Receive a schedule play command from the server.
     */
    @RpcMethod(name = "audio.schedulePlay", direction = RpcDirection.CLIENT)
    suspend fun handleSchedulePlay(ctx: RpcCallContext, payload: AudioSchedulePlay)
    {
        log.info(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleSchedulePlay: ENTER objects.size=${payload.objects.size} " +
            "serverTimestampMs=${payload.serverTimestampMs} serverFrame=${payload.currentServerFrame} " +
            "serverSampleRate=${payload.serverSampleRate}"
        )
        AudioEngine.scheduleFromServer(
            objects = payload.objects,
            serverTimestampMs = payload.serverTimestampMs,
            currentServerFrame = payload.currentServerFrame,
            serverSampleRate = payload.serverSampleRate
        )
        // Note: no ack is sent back. The server's AudioManager.schedulePlay
        // does not require a response, and the previous empty-queryId
        // sendReportState("") generated a needless audio.reportState RPC
        // round-trip for every play.
        log.debug(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleSchedulePlay: EXIT (handed off to AudioEngine.scheduleFromServer)"
        )
    }

    /**
     * Receive a stop command.
     */
    @RpcMethod(name = "audio.stop", direction = RpcDirection.CLIENT)
    suspend fun handleStop(ctx: RpcCallContext, payload: AudioStop)
    {
        log.info(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleStop: ENTER objectId='${payload.objectId}' fadeOutMs=${payload.fadeOutDurationMs}"
        )
        AudioEngine.stop(payload.objectId, payload.fadeOutDurationMs)
        log.debug(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleStop: EXIT objectId='${payload.objectId}'"
        )
    }

    /**
     * Receive a parameter update (volume, panning, or speed).
     */
    @RpcMethod(name = "audio.paramUpdate", direction = RpcDirection.CLIENT)
    suspend fun handleParamUpdate(ctx: RpcCallContext, payload: AudioParamUpdate)
    {
        log.info(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleParamUpdate: ENTER objectId='${payload.objectId}' " +
            "param='${payload.param}' value=${payload.value} fadeMs=${payload.fadeDurationMs}"
        )
        when (payload.param)
        {
            "volume" -> AudioEngine.setVolume(payload.objectId, payload.value, payload.fadeDurationMs)
            "panning" -> AudioEngine.setPanning(payload.objectId, payload.value, payload.fadeDurationMs)
            "speed" -> AudioEngine.setSpeed(payload.objectId, payload.value, payload.fadeDurationMs)
            "globalVolume" -> AudioEngine.setGlobalVolume(payload.value)
            else -> log.warn(
                LogCategory.NETWORK,
                "AudioClientHandlers.handleParamUpdate: unknown param='${payload.param}' " +
                "(expected volume/panning/speed/globalVolume) — ignored"
            )
        }
        log.debug(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleParamUpdate: EXIT param='${payload.param}'"
        )
    }

    /**
     * Receive a channel update.
     */
    @RpcMethod(name = "audio.channelUpdate", direction = RpcDirection.CLIENT)
    suspend fun handleChannelUpdate(ctx: RpcCallContext, payload: AudioChannelUpdate)
    {
        log.info(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleChannelUpdate: ENTER channelId='${payload.channelId}' " +
            "volume=${payload.volume} muted=${payload.muted}"
        )
        AudioEngine.setChannelVolume(payload.channelId, payload.volume ?: 1.0f, payload.muted ?: false)
        log.debug(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleChannelUpdate: EXIT channelId='${payload.channelId}'"
        )
    }

    /**
     * Receive full sync state on reconnect / late join.
     */
    @RpcMethod(name = "audio.syncState", direction = RpcDirection.CLIENT)
    suspend fun handleSyncState(ctx: RpcCallContext, payload: AudioSyncState)
    {
        log.info(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleSyncState: ENTER globalVolume=${payload.globalVolume} " +
            "channels.size=${payload.channels.size} scheduledObjects.size=${payload.scheduledObjects.size} " +
            "timestampMs=${payload.timestampMs} serverFrame=${payload.currentServerFrame}"
        )
        val toStart = AudioEngine.reconcileWithSnapshot(payload)
        log.info(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleSyncState: reconcile result — toStart.size=${toStart.size} " +
            "objects=[${toStart.joinToString { "'${it.resourceName}'" }}]"
        )
        for (obj in toStart)
        {
            // play() now handles async preloading internally — no need to preload first
            AudioEngine.play(obj, 0.0)
        }
        // [Bug fix] The server's audio.syncState snapshot includes a default
        // volume=1 for every channel, which clobbers the user-controlled
        // Music and Sfx channels the [ui.gameplay.SettingsWidget] sliders
        // write to localStorage. Re-push the persisted user preferences so
        // the user's slider choice survives the server's authoritative
        // snapshot. The function is idempotent and a no-op when the
        // engine is not yet ready.
        log.debug(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleSyncState: re-applying user-controlled audio channels (Music, Sfx) after server snapshot reconcile"
        )
        AudioSettings.applyPersistedToEngine()
        log.debug(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleSyncState: EXIT (${toStart.size} objects handed to AudioEngine.play)"
        )
    }

    /**
     * Receive a music-selector schedule from the server. The
     * [MusicRunner] takes care of fading out the previous music
     * (using [org.ttt.autogenesis.audio.MusicDecision.fadeOutDurationMs])
     * and starting the new tracks (using their per-object fade-in).
     */
    @RpcMethod(name = "audio.musicSchedule", direction = RpcDirection.CLIENT)
    suspend fun handleMusicSchedule(ctx: RpcCallContext, payload: AudioMusicSchedule)
    {
        val decision = payload.decision
        log.info(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleMusicSchedule: ENTER toPlay=${decision.toPlay.size} " +
            "toFadeOut=${decision.toFadeOut.size} " +
            "resources=[${decision.toPlay.joinToString { "'${it.resourceName}'" }}] " +
            "serverFrame=${payload.currentServerFrame}"
        )
        MusicRunner.instance.playSchedule(payload.decision)
        log.debug(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleMusicSchedule: EXIT (handed off to MusicRunner.playSchedule)"
        )
    }

    /**
     * Server queries our full state.
     */
    @RpcMethod(name = "audio.queryState", direction = RpcDirection.CLIENT)
    suspend fun handleQueryState(ctx: RpcCallContext, payload: AudioQueryState)
    {
        log.info(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleQueryState: ENTER queryId='${payload.queryId}' " +
            "(server requested full audio state snapshot)"
        )
        sendReportState(payload.queryId)
        log.debug(
            LogCategory.NETWORK,
            "AudioClientHandlers.handleQueryState: EXIT queryId='${payload.queryId}'"
        )
    }

    private suspend fun sendReportState(queryId: String)
    {
        val state = AudioReportState(
            queryId = queryId,
            globalVolume = AudioEngine.globalVolume,
            channelStates = AudioEngine.channels.map { (_, master) ->
                AudioChannelState(
                    channelId = master.channel.id,
                    volume = master.channel.volume,
                    muted = master.channel.muted
                )
            },
            playingObjects = AudioEngine.activePlayers.map { (_, player) ->
                AudioObjectState(
                    id = player.audioObject.id,
                    resourceName = player.audioObject.resourceName,
                    channelId = player.audioObject.channelId,
                    volume = player.volume,
                    panning = player.panning,
                    speed = player.speed,
                    loop = player.audioObject.loop,
                    currentTimeMs = player.currentTimeMs,
                    startedAtMs = player.audioObject.startTimeMs,
                    isPlaying = player.isPlaying,
                    isPaused = player.isPaused,
                    isEnded = player.isEnded
                )
            },
            clientTimestampMs = js("Date.now()").unsafeCast<Double>().toLong()
        )
        log.debug(
            LogCategory.NETWORK,
            "AudioClientHandlers.sendReportState: built state queryId='$queryId' " +
            "globalVolume=${state.globalVolume} channels=${state.channelStates.size} " +
            "playingObjects=${state.playingObjects.size}"
        )
        // Send back to server via RPC invoker
        rpcInvoker?.let { invoker ->
            val encoded = org.ttt.autogenesis.network.RpcJson.encodeToJsonElement(
                org.ttt.autogenesis.audio.AudioReportState.serializer(),
                state
            )
            invoker.invoke("audio.reportState", encoded)
            log.debug(
                LogCategory.NETWORK,
                "AudioClientHandlers.sendReportState: invoked 'audio.reportState' for queryId='$queryId'"
            )
        } ?: run {
            log.warn(
                LogCategory.NETWORK,
                "AudioClientHandlers.sendReportState: no rpcInvoker configured — cannot send report for queryId='$queryId'"
            )
        }
    }
}