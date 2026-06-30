package ui.gameplay.networking

import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod
import org.ttt.autogenesis.network.GeopoliticsUpdateData
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.decode
import org.ttt.autogenesis.kvisionapp.WebSocketRpcBridge
import structs.GameHistory
import ui.gameplay.GameHistoryWindow
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Client-side hooks that allow the UI to react to turn completion summaries emitted by the server.
 */
object ActionHistoryClientHandlers
{
    private val cachedEntries = mutableListOf<GameHistory>()
    private var latestGeopoliticalAssessment: String = ""
    private var window: GameHistoryWindow? = null

    /**
     * Attaches a [GameHistoryWindow] so it can replay cached entries and display new ones.
     */
    fun attachWindow(historyWindow: GameHistoryWindow)
    {
        window = historyWindow
        cachedEntries.forEach { historyWindow.recordGameHistoryEntry(it) }
        if (latestGeopoliticalAssessment.isNotBlank())
        {
            historyWindow.updateGeopolitics(latestGeopoliticalAssessment)
        }
    }

    /**
     * Routes thinking update data to GameHistoryWindow for display in the Details panel.
     *
     * @param data The ThinkingUpdateData broadcast from the server
     */
    fun routeThinkingToGameHistory(data: org.ttt.autogenesis.network.ThinkingUpdateData)
    {
        Logger.debug(LogCategory.NETWORK, "ActionHistoryClientHandlers: Routing thinking update for ${data.characterName} to GameHistoryWindow")
        window?.appendThinkingUpdate(data)
    }

    /**
     * Flashes the Details tab (index 1) to notify the player of new thinking content.
     */
    fun flashDetailsTab() {
        window?.flashTab(1)
    }

    /**
     * Handles the Geopolitical Assessment payload broadcast via RPC.
     */
    @RpcMethod(name = "ui.geopoliticsUpdate", direction = RpcDirection.CLIENT)
    suspend fun handleGeopoliticsUpdate(_ctx: RpcCallContext, data: org.ttt.autogenesis.network.GeopoliticsUpdateData)
    {
        Logger.debug(LogCategory.NETWORK, "[GEOPOLITICS DEBUG] ActionHistoryClientHandlers received RPC update.")
        Logger.debug(LogCategory.NETWORK, "[GEOPOLITICS DEBUG] RPC Payload Assessment: ${data.assessment.take(50)}...")
        
        latestGeopoliticalAssessment = data.assessment
        window?.updateGeopolitics(data.assessment)
    }

    /**
     * Handles the [GameHistory] payload broadcast via RPC.
     */
    @RpcMethod(name = "ui.turnComplete", direction = RpcDirection.CLIENT)
    suspend fun handleTurnComplete(_ctx: RpcCallContext, gameHistory: GameHistory)
    {
        Logger.debug(LogCategory.NETWORK, "[GAME_HISTORY_LOG] !!! RPC RECEIVE: ui.turnComplete !!!")
        Logger.debug(LogCategory.NETWORK, "[GAME_HISTORY_LOG] Payload Analysis:")
        Logger.debug(LogCategory.NETWORK, "[GAME_HISTORY_LOG]   - ID: ${gameHistory.id}")
        Logger.debug(LogCategory.NETWORK, "[GAME_HISTORY_LOG]   - Turn Player: ${gameHistory.turnPlayer}")
        Logger.debug(LogCategory.NETWORK, "[GAME_HISTORY_LOG]   - Turn Action: ${gameHistory.turnAction}")
        Logger.debug(LogCategory.NETWORK, "[GAME_HISTORY_LOG]   - Story Length: ${gameHistory.turnStory.length}")
        Logger.debug(LogCategory.NETWORK, "[GAME_HISTORY_LOG]   - Result Summary (turnResult): '${gameHistory.turnResult}'")
        Logger.debug(LogCategory.NETWORK, "[GAME_HISTORY_LOG]   - turnResult.isNotBlank(): ${gameHistory.turnResult.isNotBlank()}")
        Logger.debug(LogCategory.NETWORK, "[GAME_HISTORY_LOG]   - Success: ${gameHistory.wasPlayerSuccessful}")
        
        cachedEntries.add(gameHistory)
        // Evict oldest if exceeding 50 entries
        if (cachedEntries.size > 50)
        {
            cachedEntries.removeAt(0)
        }
        
        if (window != null) {
            Logger.debug(LogCategory.NETWORK, "[GAME_HISTORY_LOG] Handing off to GameHistoryWindow.recordGameHistoryEntry...")
            window?.recordGameHistoryEntry(gameHistory)
        } else {
            Logger.error(LogCategory.NETWORK, "[GAME_HISTORY_LOG] CRITICAL: GameHistoryWindow is NULL! Cannot update UI.")
        }
    }

    /**
     * Fetches the latest geopolitical assessment from the server.
     */
    fun fetchLatestAssessment()
    {
        MainScope().launch {
            try
            {
                Logger.debug(LogCategory.NETWORK, "[GEOPOLITICS DEBUG] Fetching latest assessment from server...")
                val invoker = WebSocketRpcBridge.rpcInvoker ?: throw Exception("RPC invoker not available")
                
                val response = invoker.invoke<String>(
                    "actionHistory.getLatestAssessment",
                    "dummy"
                )
                
                Logger.debug(LogCategory.NETWORK, "[GEOPOLITICS DEBUG] Raw RPC Response: $response")

                val error = response.error
                if (error != null) {
                    throw Exception("RPC error: ${error.message}")
                }
                
                val geoData = RpcJson.decode<GeopoliticsUpdateData>(response.result)
                latestGeopoliticalAssessment = geoData.assessment
                Logger.debug(LogCategory.NETWORK, "[GEOPOLITICS DEBUG] Fetched assessment. Length: ${latestGeopoliticalAssessment.length}")
                window?.updateGeopolitics(latestGeopoliticalAssessment)
            }
            catch (e: Exception)
            {
                Logger.error(LogCategory.NETWORK, "[GEOPOLITICS DEBUG] Failed to fetch latest assessment: ${e.message}")
            }
        }
    }
}