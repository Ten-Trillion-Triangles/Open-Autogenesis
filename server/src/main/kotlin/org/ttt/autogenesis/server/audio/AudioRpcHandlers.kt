package org.ttt.autogenesis.server.audio

import kotlinx.serialization.serializer
import org.ttt.autogenesis.audio.*
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.network.RpcMethod
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * RPC handlers for CLIENT → SERVER audio messages.
 * These are called by clients reporting their audio state back to the server.
 */
object AudioRpcHandlers {
    private val log = Logger

    /**
     * Client reports periodic position for drift correction.
     */
    @RpcMethod(name = "audio.reportPosition", direction = RpcDirection.SERVER)
    suspend fun reportPosition(ctx: RpcCallContext, report: AudioPositionReport): Boolean {
        log.debug(
            LogCategory.NETWORK,
            "AudioRpcHandlers.reportPosition: ENTER connectionId=${ctx.connectionId} objectId=${report.objectId} " +
            "currentTimeMs=${report.currentTimeMs} isPlaying=${report.isPlaying}"
        )
        AudioManager.onPositionReport(ctx.connectionId, report)
        log.debug(
            LogCategory.NETWORK,
            "AudioRpcHandlers.reportPosition: OK connectionId=${ctx.connectionId} objectId=${report.objectId}"
        )
        return true
    }

    /**
     * Client responds to a state query from the server.
     */
    @RpcMethod(name = "audio.reportState", direction = RpcDirection.SERVER)
    suspend fun reportState(ctx: RpcCallContext, state: AudioReportState): Boolean {
        log.info(
            LogCategory.NETWORK,
            "AudioRpcHandlers.reportState: ENTER connectionId=${ctx.connectionId} queryId=${state.queryId} " +
            "globalVolume=${state.globalVolume} channels=${state.channelStates.size} " +
            "playingObjects=${state.playingObjects.size} clientTimestampMs=${state.clientTimestampMs}"
        )
        AudioManager.onStateReport(state.queryId, state)
        log.info(
            LogCategory.NETWORK,
            "AudioRpcHandlers.reportState: OK queryId=${state.queryId} (deferred completed)"
        )
        return true
    }

    /**
     * Client updates a channel's volume and/or mute state.
     * Server propagates the change to all connected clients.
     */
    @RpcMethod(name = "audio.setChannelVolume", direction = RpcDirection.SERVER)
    suspend fun setChannelVolume(
        ctx: RpcCallContext,
        update: AudioChannelUpdate
    ): Boolean {
        log.info(
            LogCategory.NETWORK,
            "AudioRpcHandlers.setChannelVolume: ENTER connectionId=${ctx.connectionId} " +
            "channelId=${update.channelId} volume=${update.volume} muted=${update.muted}"
        )
        val connectionManager = org.ttt.autogenesis.server.UiSignalRpcHandlers.connectionManager
        AudioManager.updateChannel(update.channelId, update.volume, update.muted, connectionManager)
        log.info(
            LogCategory.NETWORK,
            "AudioRpcHandlers.setChannelVolume: OK channelId=${update.channelId} (broadcast dispatched)"
        )
        return true
    }

    /**
     * Client triggers a game event sound (button click, etc.).
     * Server schedules it and broadcasts to all clients.
     */
    @RpcMethod(name = "audio.triggerGameAudio", direction = RpcDirection.SERVER)
    suspend fun triggerGameAudio(
        ctx: RpcCallContext,
        trigger: AudioGameTrigger
    ): Boolean {
        log.info(
            LogCategory.NETWORK,
            "AudioRpcHandlers.triggerGameAudio: ENTER connectionId=${ctx.connectionId} " +
            "resourceName='${trigger.resourceName}' channelId=${trigger.channelId} volume=${trigger.volume}"
        )
        val obj = AudioObject(
            resourceName = trigger.resourceName,
            channelId = trigger.channelId,
            volume = trigger.volume,
            startTimeMs = System.currentTimeMillis()  // immediate
        )
        // Access PlayerConnectionManager via UiSignalRpcHandlers pattern (same package access)
        val connectionManager = org.ttt.autogenesis.server.UiSignalRpcHandlers.connectionManager
        AudioManager.schedulePlay(listOf(obj), connectionManager)
        log.info(
            LogCategory.NETWORK,
            "AudioRpcHandlers.triggerGameAudio: OK objectId=${obj.id} (schedule broadcast dispatched)"
        )
        return true
    }

    /**
     * Client updates global volume.
     * Server applies the change and broadcasts to all connected clients.
     */
    @RpcMethod(name = "audio.setGlobalVolume", direction = RpcDirection.SERVER)
    suspend fun setGlobalVolume(
        ctx: RpcCallContext,
        update: AudioGlobalVolumeUpdate
    ): Boolean {
        log.info(
            LogCategory.NETWORK,
            "AudioRpcHandlers.setGlobalVolume: ENTER connectionId=${ctx.connectionId} volume=${update.volume}"
        )
        val connectionManager = org.ttt.autogenesis.server.UiSignalRpcHandlers.connectionManager
        AudioManager.setGlobalVolume(update.volume, connectionManager)
        log.info(
            LogCategory.NETWORK,
            "AudioRpcHandlers.setGlobalVolume: OK volume=${update.volume} (broadcast dispatched)"
        )
        return true
    }
}
