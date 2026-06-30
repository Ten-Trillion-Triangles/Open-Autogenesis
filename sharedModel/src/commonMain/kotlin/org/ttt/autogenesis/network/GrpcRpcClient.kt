package org.ttt.autogenesis.network

import kotlinx.coroutines.CoroutineScope

/**
 * Cross-platform gRPC client for RPC communication.
 * Handles bidirectional streaming and message routing through the [RpcRegistry].
 *
 * @param config Client configuration containing endpoint and player ID
 * @param rpcRegistry Registry containing RPC method handlers
 * @param coroutineScope Optional coroutine scope for async operations
 * @param metadata Additional metadata to send with requests
 * @param logger Error logging function
 */
expect class GrpcRpcClient(
    config: GrpcRpcClientConfig,
    rpcRegistry: RpcRegistry,
    coroutineScope: CoroutineScope? = null,
    metadata: Map<String, String> = emptyMap(),
    logger: (Throwable) -> Unit = {}
)
{
    /**
     * Unique identifier for this client connection.
     */
    val connectionId: String
    
    /**
     * RPC invoker for making outbound RPC calls.
     */
    val rpcInvoker: RpcInvoker

    /**
     * Establishes the gRPC connection to the server.
     */
    /**
     * Wait for in-flight RPC requests to drain. Same contract as
     * [RestRpcClient.awaitInFlightRequests] — see that method for
     * the full rationale.
     */
    suspend fun awaitInFlightRequests(timeoutMillis: Long = 2_000L): Boolean

    fun connect()
    
    /**
     * Disconnects from the gRPC server.
     */
    fun disconnect()
    
    /**
     * Checks if the client is currently connected.
     *
     * @return true if connected, false otherwise
     */
    fun isConnected(): Boolean
    
    /**
     * Checks if the session is ready for RPC communication.
     *
     * @return true if session is ready, false otherwise
     */
    fun isSessionReady(): Boolean
    
    /**
     * Closes the client and releases all resources.
     */
    suspend fun close()
}
