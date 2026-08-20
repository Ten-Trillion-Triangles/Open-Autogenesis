package network

import gameState.WorldManager
import org.ttt.autogenesis.network.RpcInvoker
import org.ttt.autogenesis.network.decode
import org.ttt.autogenesis.server.PlayerConnectionManager
import org.ttt.autogenesis.server.PlayerSession
import structs.responses.PlayerConnectResponse

/**
 * Handles dsm connections, and reconnections to the main game services. This assumes initial connection has been completed,
 * and the user has been logged in client side to AccelByte. Server session is active, and ready to accept players for
 * the actual gameplay portion. Handles the initial handshake steps, collection id and information and other
 * required setup.
 */

/**
 * Called when a connection is established via websocket. Presumes the socket id has already been formed and
 * now we're ready to start the handshake process.
 *
 * @param sessionId Id produced when connecting to the games rpc websocket system. [org.ttt.autogenesis.network.RpcInvoker]
 *
 */
suspend fun connectPlayerToDsm(sessionId : String)
{
    var connectionResponse : PlayerConnectResponse?

    try {
        val response = PlayerConnectionManager().callClient(
            playerId = sessionId,
            method = "playerConnect",
            params = null,
            timeoutMillis = 5000
        )
        
        if(response?.result != null) {
            connectionResponse = org.ttt.autogenesis.network.RpcJson.decode(response.result)
        }
    } catch(e: Exception) {
        connectionResponse = null
    }

    //Determine if we're in a connection state, or a reconnection state.
    if(!WorldManager.isGameActive) {

    }
}
