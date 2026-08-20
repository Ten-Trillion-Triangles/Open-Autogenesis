package org.ttt.autogenesis.kvisionapp

import org.ttt.autogenesis.network.GrpcRpcBridge as SharedGrpcRpcBridge
import org.ttt.autogenesis.network.GrpcRpcBridgeConfig
import org.ttt.autogenesis.network.RpcRegistry
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * KVision-specific wrapper for the shared gRPC RPC bridge.
 * Provides a simplified interface for frontend gRPC communication.
 */
object GrpcRpcBridge
{
    val rpcInvoker get() = SharedGrpcRpcBridge.rpcInvoker
    val isConnected get() = SharedGrpcRpcBridge.isConnected
    val isSessionReady get() = SharedGrpcRpcBridge.isSessionReady

    /**
     * Registers RPC method handlers for incoming messages.
     *
     * @param block Lambda that configures the RPC registry with handlers
     */
    fun registerHandlers(block: RpcRegistry.() -> Unit)
    {
        SharedGrpcRpcBridge.registerHandlers(block)
    }

    /**
     * Establishes a gRPC connection to the server.
     *
     * @param playerId Unique identifier for this client connection
     * @param endpoint gRPC server endpoint in "host:port" format
     */
    suspend fun connect(
        playerId: String = SharedGrpcRpcBridge.generatePlayerId(),
        endpoint: String = GrpcRpcBridgeConfig.development()
    )
    {
        Logger.info(LogCategory.NETWORK, "GrpcRpcBridge: requested connect to $endpoint as $playerId")
        SharedGrpcRpcBridge.connect(playerId = playerId, endpoint = endpoint)
    }

    /**
     * Closes the gRPC connection and cleans up resources.
     */
    suspend fun close()
    {
        SharedGrpcRpcBridge.close()
    }

    /**
     * Generates a unique player ID for client identification.
     *
     * @return A randomly generated player ID string
     */
    fun generatePlayerId(): String = SharedGrpcRpcBridge.generatePlayerId()
}