package org.ttt.autogenesis.server

import org.ttt.autogenesis.network.DispatchData
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.network.RpcJson
import kotlinx.serialization.serializer

object DispatchRpcHandler {
    suspend fun broadcastDispatchResult(
        usedAssets: List<String> = emptyList(),
        gainedAssets: List<String> = emptyList(),
        lostAssets: List<String> = emptyList(),
        territoryGained: List<String> = emptyList(),
        territoryLost: List<String> = emptyList(),
        territoryExchanges: List<structs.TerritoryExchange> = emptyList()
    ) {
        Logger.info(LogCategory.NETWORK, "DispatchRpcHandler: broadcasting Dispatch Result")
        val data = DispatchData(usedAssets, gainedAssets, lostAssets, territoryGained, territoryLost, territoryExchanges)
        val payload = RpcJson.encodeToJsonElement(serializer<DispatchData>(), data)
        val notification = RpcMessage.Notification("ui.dispatchResult", payload)
        
        UiSignalRpcHandlers.connectionManager?.broadcast(notification)
    }
}
