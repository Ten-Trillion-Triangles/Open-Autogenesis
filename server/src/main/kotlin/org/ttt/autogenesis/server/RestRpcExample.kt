package org.ttt.autogenesis.server

import org.ttt.autogenesis.network.RestRpcBridge
import org.ttt.autogenesis.network.RestRpcBridgeConfig
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcOrigin

/**
 * Example usage of RestRpcBridge in server module.
 */
class RestRpcExample
{
    suspend fun connectToServerExtend()
    {
        RestRpcBridge.registerHandlers {
            register("server.ping", RpcDirection.CLIENT) { _, _ ->
                null
            }
        }

        // Tag the bridge as GAME_SERVER so the receiver (server-extend)
        // classifies RPCs from this connection as game-server traffic in
        // its in-process RpcUsageTracker. The default would otherwise be
        // GAME_CLIENT, which would mis-count a :server DS as a player.
        RestRpcBridge.connect(
            playerId = "server-client-${System.currentTimeMillis()}",
            baseUrl = RestRpcBridgeConfig.development(7070),
            origin = RpcOrigin.GAME_SERVER
        )
    }
}
