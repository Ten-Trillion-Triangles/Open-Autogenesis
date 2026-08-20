package org.ttt.autogenesis.network

import kotlinx.coroutines.CancellationException

/**
 * Helper responsible for routing incoming RPC messages through a registry
 * while exposing the session invoker for stream/responses.
 *
 * @param connectionId Unique identifier for the connection supplying context for handlers.
 * @param rpcRegistry Registry that owns RPC method handlers for this endpoint.
 * @param invoker Invoker for streaming and response handling.
 * @param sender Function used to serialize and transmit messages back to the remote peer.
 * @param metadata Optional metadata included in every RPC context.
 */
class RpcMessageHandler(
    private val connectionId : String,
    private val rpcRegistry : RpcRegistry,
    private val invoker : RpcInvoker,
    private val sender : suspend (RpcMessage) -> Unit,
    private val metadata : Map<String, String> = emptyMap()
)
{
    private fun buildContext() : RpcCallContext =
        RpcCallContext(connectionId = connectionId, metadata = metadata, sender = sender)

    /**
     * Processes a single RPC message emitted by the transport.
     */
    suspend fun handle(message : RpcMessage)
    {
        when (message) {
            is RpcMessage.Request -> {
                val context = buildContext()
                val response = rpcRegistry.dispatch(message, context)
                response?.let { sender(it) }
            }
            is RpcMessage.Notification -> {
                val context = buildContext()
                rpcRegistry.dispatchNotification(message, context)
            }
            is RpcMessage.Response -> invoker.handleResponse(message)
            is RpcMessage.StreamChunk -> invoker.handleStreamChunk(message)
            is RpcMessage.StreamCancel -> {
                if (!invoker.handleStreamCancel(message)) {
                    rpcRegistry.cancelStream(message.id)
                }
            }
            is RpcMessage.ConnectionState -> Unit
            is RpcMessage.Multipart -> Unit
        }
    }
}