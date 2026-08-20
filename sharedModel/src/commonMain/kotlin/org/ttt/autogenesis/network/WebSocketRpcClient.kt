package org.ttt.autogenesis.network

import kotlinx.coroutines.CoroutineScope

/**
 * WebSocket-backed RPC connection that uses WebSocket for bidirectional communication.
 *
 * Provides the same interface as RestRpcClient but uses WebSocket transport instead of SSE+HTTP.
 */
expect class WebSocketRpcClient(
    config: WebSocketRpcClientConfig,
    rpcRegistry: RpcRegistry,
    coroutineScope: CoroutineScope? = null,
    metadata: Map<String, String> = emptyMap(),
    logger: (Throwable) -> Unit = {}
) {
    val connectionId: String
    val rpcInvoker: RpcInvoker

    /**
     * Establishes WebSocket connection and starts message processing.
     */
    fun connect()

    /**
     * Disconnects WebSocket and stops reconnection attempts.
     */
    fun disconnect()

    /**
     * Returns true if WebSocket connection is active.
     */
    fun isConnected(): Boolean

    /**
     * Returns true if session is ready for RPC calls.
     */
    fun isSessionReady(): Boolean

    /**
     * Sets callback for connection established events.
     */
    fun onConnected(callback: () -> Unit)

    /**
     * Closes connection and releases all resources.
     */
    suspend fun close()
}