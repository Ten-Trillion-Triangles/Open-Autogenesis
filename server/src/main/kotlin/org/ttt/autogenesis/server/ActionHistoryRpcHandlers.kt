package org.ttt.autogenesis.server

import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import gameState.WorldManager
import kotlinx.coroutines.sync.withLock
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.network.RpcMethod
import scoring.HistoryParser
import scoring.ScoreManager
import structs.GameHistory
import structs.Resource
import structs.ui.*

object ActionHistoryRpcHandlers
{
    internal var connectionManager: PlayerConnectionManager? = null
    private val processor = ActionHistoryProcessor()
    private val parser = HistoryParser()

    private const val UI_TURN_COMPLETE = "ui.turnComplete"
    private const val UI_GEOPOLITICS_UPDATE = "ui.geopoliticsUpdate"

    @RpcMethod(name = "actionHistory.recordEvent", direction = RpcDirection.SERVER)
    suspend fun recordActionHistoryEvent(ctx: RpcCallContext, event: ActionHistoryEvent): Boolean
    {
        if(!event.event.validate())
        {
            Logger.warn(LogCategory.GENERAL, "ActionHistory event failed validation: ${event.event}")
            return false
        }
        WorldManager.recordActionHistoryEvent(event)
        return true
    }

    // @RpcMethod(name = "actionHistory.processTurn", direction = RpcDirection.SERVER)
    // suspend fun processTurn(snapshot: WorldSnapshot, pageKey: String): TurnHistory {
    //    val history = ActionHistoryService.processTurn(snapshot, pageKey)
    //    return history
    // }

    suspend fun processTurnComplete(ctx: RpcCallContext, batch: ActionHistoryBatch): GameHistory
    {
        val turnPlayer = batch.turnPlayer.ifBlank { batch.events.firstOrNull()?.event?.player.orEmpty() }
        val gameHistory = processor.processActionHistoryToGameHistory(batch.events.map { it.event }, turnPlayer)
        WorldManager.history.add(gameHistory)
        
        updateWorldFromGameHistory(gameHistory)
        val scoringResult = parser.parseHistoryForScoring(gameHistory)
        
        // Add story content to ContextBank
        val storyContext = ContextBank.getContextFromBank("story") ?: ContextWindow()
        storyContext.contextElements.add(gameHistory.turnStory)
        ContextBank.emplace("story", storyContext)
        
        broadcastTurnComplete(gameHistory)
        return gameHistory
    }

    @RpcMethod(name = "actionHistory.getLatestAssessment", direction = RpcDirection.SERVER)
    suspend fun getLatestAssessment(ctx: RpcCallContext, dummy: String): org.ttt.autogenesis.network.GeopoliticsUpdateData
    {
        Logger.info(LogCategory.NETWORK, "ActionHistoryRpcHandlers: getLatestAssessment called.")
        val assessment = WorldManager.geopoliticalAssessment
        Logger.info(LogCategory.NETWORK, "[GEOPOLITICS DEBUG] Returning assessment via RPC. Length: ${assessment.length}")
        Logger.info(LogCategory.NETWORK, "[GEOPOLITICS DEBUG] Assessment Content Preview: ${assessment.take(100)}...")
        
        return org.ttt.autogenesis.network.GeopoliticsUpdateData(assessment = assessment)
    }

    private suspend fun updateWorldFromGameHistory(gameHistory: GameHistory)
    {
        WorldManager.worldMutex.withLock {
            gameHistory.actionHistory.forEach { event ->
                when(event.eventType)
                {
                    GameEventType.TERRITORY -> updateTerritory(event.player, event)
                    GameEventType.RESOURCE  -> updateResources(event.player, event)
                    else -> Unit
                }
            }
        }
    }

    private fun updateTerritory(player: String, event: ActionHistory)
    {
        val metadata = event.metadata as? TerritoryEventMetadata ?: return
        if(metadata.territoryName.isBlank()) return
        val territory = WorldManager.world.mapTiles.find { it.name.equals(metadata.territoryName, ignoreCase = true) }
        territory?.apply {
            ruler = metadata.newOwner.ifBlank { player }
            isCaptured = metadata.newOwner.isNotBlank()
            isDestroyed = metadata.isDestroyed
        }
    }

    private fun updateResources(player: String, event: ActionHistory)
    {
        val metadata = event.metadata as? ResourceEventMetadata ?: return
        val targetPlayer = WorldManager.world.activePlayers.firstOrNull { it.name == player } ?: return
        if(metadata.isGranted && metadata.resourceName.isNotBlank())
        {
            if(targetPlayer.resources.none { it.name == metadata.resourceName })
            {
                targetPlayer.resources.add(Resource(name = metadata.resourceName))
            }
        }
        if(metadata.isDepleted || metadata.isDestroyed)
        {
            targetPlayer.resources.removeAll { it.name == metadata.resourceName }
        }
    }

    suspend fun broadcastTurnComplete(gameHistory: GameHistory)
    {
        // Update local ContextBank for server-side agents (NPCs, AI Takeover)
        val storyContext = ContextBank.getContextFromBank("story") ?: ContextWindow()
        storyContext.contextElements.add(gameHistory.turnStory)
        ContextBank.emplace("story", storyContext)

        Logger.info(LogCategory.NETWORK, "Broadcasting TurnComplete. Story Length: ${gameHistory.turnStory.length}")
        Logger.info(LogCategory.NETWORK, "[HISTORY DEBUG] Broadcasting GameHistory:")
        Logger.info(LogCategory.NETWORK, "[HISTORY DEBUG]   - turnPlayer: ${gameHistory.turnPlayer}")
        Logger.info(LogCategory.NETWORK, "[HISTORY DEBUG]   - turnAction: ${gameHistory.turnAction}")
        Logger.info(LogCategory.NETWORK, "[HISTORY DEBUG]   - turnStory length: ${gameHistory.turnStory.length}")
        Logger.info(LogCategory.NETWORK, "[HISTORY DEBUG]   - turnResult: ${gameHistory.turnResult}")
        Logger.info(LogCategory.NETWORK, "[HISTORY DEBUG]   - wasPlayerSuccessful: ${gameHistory.wasPlayerSuccessful}")
        val payload = RpcJson.encodeToJsonElement(GameHistory.serializer(), gameHistory)
        Logger.info(LogCategory.NETWORK, "[HISTORY DEBUG] Serialized payload keys: ${payload}")
        val notification = RpcMessage.Notification(UI_TURN_COMPLETE, payload)
        
        if(connectionManager == null) {
            Logger.error(LogCategory.NETWORK, "!!! DISPATCH FAILURE: connectionManager is NULL !!!")
        } else {
            Logger.info(LogCategory.NETWORK, "Dispatching to active connectionManager...")
        }
        connectionManager?.broadcast(notification)
    }

    @RpcMethod(name = "ui.geopoliticsUpdate", direction = RpcDirection.CLIENT)
    suspend fun broadcastGeopoliticsUpdate(ctx: RpcCallContext, data: org.ttt.autogenesis.network.GeopoliticsUpdateData)
    {
        Logger.info(LogCategory.NETWORK, "Broadcasting Geopolitics Update. Length: ${data.assessment.length}")
        Logger.info(LogCategory.NETWORK, "[GEOPOLITICS DEBUG] RPC Payload Assessment: ${data.assessment.take(100)}...")
        if(data.assessment.isBlank()) Logger.error(LogCategory.NETWORK, "[GEOPOLITICS DEBUG] RPC Payload Assessment is BLANK!")
        val payload = RpcJson.encodeToJsonElement(org.ttt.autogenesis.network.GeopoliticsUpdateData.serializer(), data)
        val notification = RpcMessage.Notification(UI_GEOPOLITICS_UPDATE, payload)
        connectionManager?.broadcast(notification)
    }
}
