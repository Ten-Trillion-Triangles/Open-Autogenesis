package org.ttt.autogenesis.network

/**
 * Cross-platform gRPC RPC bridge for client-server communication.
 * Provides a unified interface for gRPC-based RPC calls across JVM and JS platforms.
 */
expect object GrpcRpcBridge
{
    /**
     * The RPC invoker for making outbound RPC calls, or null if not connected.
     */
    val rpcInvoker: RpcInvoker?
    
    /**
     * Whether the gRPC connection is currently established.
     */
    val isConnected: Boolean
    
    /**
     * Whether the session is ready for RPC communication.
     */
    val isSessionReady: Boolean

    /**
     * Registers RPC method handlers for processing incoming messages.
     *
     * @param block Lambda that configures the RPC registry with method handlers
     */
    fun registerHandlers(block: RpcRegistry.() -> Unit)

    /**
     * Establishes a gRPC connection to the specified endpoint.
     *
     * @param playerId Unique identifier for this client connection
     * @param endpoint gRPC server endpoint in "host:port" format
     */
    suspend fun connect(
        playerId: String = generatePlayerId(),
        endpoint: String = GrpcRpcBridgeConfig.development()
    )

    /**
     * Closes the gRPC connection and releases all resources.
     */
    suspend fun awaitInFlightRequests(timeoutMillis: Long = 2_000L): Boolean
    suspend fun close()

    /**
     * Generates a unique player ID for client identification.
     *
     * @return A randomly generated player ID string
     */
    fun generatePlayerId(): String
}