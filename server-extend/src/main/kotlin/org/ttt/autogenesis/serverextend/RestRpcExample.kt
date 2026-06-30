package org.ttt.autogenesis.serverextend

import org.ttt.autogenesis.network.RestRpcBridge
import org.ttt.autogenesis.network.RestRpcBridgeConfig

/**
 * Example usage of RestRpcBridge in server-extend module.
 */
class RestRpcExample
{
    suspend fun connectToMainServer()
    {
        RestRpcBridge.connect(
            baseUrl = RestRpcBridgeConfig.development(8080)
        )

        // Make RPC call to main server
        val response = RestRpcBridge.rpcInvoker?.invoke("main.server.status", null)
    }
}
