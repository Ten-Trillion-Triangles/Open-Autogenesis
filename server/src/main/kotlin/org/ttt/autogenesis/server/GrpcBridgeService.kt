package org.ttt.autogenesis.server

import io.grpc.Context as GrpcContext
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.stub.ServerCalls
import io.grpc.ServerInterceptor
import io.grpc.ServerServiceDefinition
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcInvoker
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.network.RpcMessageHandler
import org.ttt.autogenesis.network.RpcRegistry
import org.ttt.autogenesis.network.grpc.GRPC_BRIDGE_SERVICE_NAME
import org.ttt.autogenesis.network.grpc.PLAYER_ID_CTX_KEY
import org.ttt.autogenesis.network.grpc.PLAYER_ID_METADATA_KEY
import org.ttt.autogenesis.network.grpc.RPC_INVOKE_METHOD_DESCRIPTOR
import org.ttt.autogenesis.network.grpc.RPC_METHOD_DESCRIPTOR
import org.ttt.autogenesis.network.grpc.RpcEnvelope
import java.util.UUID

private val bridgeJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

private fun RpcMessage.toBridgeJson(): String =
    bridgeJson.encodeToString(RpcMessage.serializer(), this)

private fun String.toBridgeRpcMessage(): RpcMessage =
    bridgeJson.decodeFromString(RpcMessage.serializer(), this)

/**
 * gRPC service implementation that handles bidirectional streaming RPC communication.
 * Processes incoming RPC messages and routes them through the [RpcRegistry].
 *
 * @param rpcRegistry The registry containing RPC method handlers
 */
class GrpcBridgeService(private val rpcRegistry: RpcRegistry) : io.grpc.BindableService
{
    /**
     * Binds the gRPC service definition with the streaming RPC method.
     *
     * @return Configured [ServerServiceDefinition] for the RPC bridge
     */
    override fun bindService(): ServerServiceDefinition =
        ServerServiceDefinition.builder(GRPC_BRIDGE_SERVICE_NAME)
            .addMethod(RPC_METHOD_DESCRIPTOR, ServerCalls.asyncBidiStreamingCall { responseObserver ->
                val ctx: GrpcContext = GrpcContext.current()
                val playerId = PLAYER_ID_CTX_KEY.get(ctx) ?: UUID.randomUUID().toString()
                GrpcStreamHandler(playerId, rpcRegistry, responseObserver)
            })
            .addMethod(RPC_INVOKE_METHOD_DESCRIPTOR, ServerCalls.asyncUnaryCall { request, responseObserver ->
                val ctx: GrpcContext = GrpcContext.current()
                val playerId = PLAYER_ID_CTX_KEY.get(ctx) ?: UUID.randomUUID().toString()
                val handler = GrpcUnaryHandler(playerId, rpcRegistry, responseObserver)
                handler.onNext(request)
                handler.onCompleted()
            })
            .build()
}

/**
 * Server interceptor that extracts player ID from gRPC metadata headers.
 * Adds the player ID to the gRPC context for downstream handlers.
 */
internal class PlayerIdInterceptor : ServerInterceptor
{
    /**
     * Intercepts incoming gRPC calls to extract and set player ID context.
     *
     * @param call The server call being intercepted
     * @param headers Metadata headers from the client
     * @param next The next handler in the interceptor chain
     * @return Server call listener with player ID context set
     */
    override fun <ReqT, RespT> interceptCall(
        call: io.grpc.ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: io.grpc.ServerCallHandler<ReqT, RespT>
    ): io.grpc.ServerCall.Listener<ReqT>
    {
        val playerId = headers.get(PLAYER_ID_METADATA_KEY) ?: UUID.randomUUID().toString()
        val context = GrpcContext.current().withValue(PLAYER_ID_CTX_KEY, playerId)
        return Contexts.interceptCall(context, call, headers, next)
    }
}

/**
 * Handles individual gRPC stream connections for RPC message processing.
 * Manages the lifecycle of a single client connection and routes messages through [RpcMessageHandler].
 *
 * @param playerId Unique identifier for the connected player
 * @param rpcRegistry Registry containing RPC method handlers
 * @param responseObserver gRPC response stream observer for sending messages back to client
 */
private class GrpcStreamHandler(
    playerId: String,
    rpcRegistry: RpcRegistry,
    private val responseObserver: StreamObserver<RpcEnvelope>
) : StreamObserver<RpcEnvelope>
{
    private val handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val invoker = RpcInvoker(sender = { message -> sendMessage(message) })
    private val messageHandler = RpcMessageHandler(
        connectionId = playerId,
        rpcRegistry = rpcRegistry,
        invoker = invoker,
        sender = { message -> sendMessage(message) }
    )

    /**
     * Processes incoming RPC messages from the client stream.
     *
     * @param value The RPC envelope containing the message payload
     */
    override fun onNext(value: RpcEnvelope)
    {
        handlerScope.launch {
            try
            {
                val message = value.payload.toBridgeRpcMessage()
                messageHandler.handle(message)
            }
            catch(err: CancellationException)
            {
                throw err
            }
            catch(err: Throwable)
            {
                Logger.error(LogCategory.NETWORK, "gRPC handler error: ${err.message}")
            }
        }
    }

    /**
     * Handles stream errors by cancelling the handler scope.
     *
     * @param t The error that occurred on the stream
     */
    override fun onError(t: Throwable)
    {
        handlerScope.cancel(CancellationException("gRPC stream error", t))
    }

    /**
     * Handles stream completion by cleaning up resources.
     */
    override fun onCompleted()
    {
        handlerScope.cancel()
        responseObserver.onCompleted()
    }

    /**
     * Sends an RPC message back to the client through the response stream.
     *
     * @param message The RPC message to send
     */
    private suspend fun sendMessage(message: RpcMessage)
    {
        responseObserver.onNext(RpcEnvelope(message.toBridgeJson()))
    }
}

/**
 * Handles unary RPC bridge calls for browser grpc-web clients.
 *
 * The browser transport does not support client-side streaming, so each
 * request is handled as a single envelope and the immediate response is
 * returned inline.
 */
private class GrpcUnaryHandler(
    private val playerId: String,
    private val rpcRegistry: RpcRegistry,
    private val responseObserver: StreamObserver<RpcEnvelope>
) : StreamObserver<RpcEnvelope>
{
    override fun onNext(value: RpcEnvelope)
    {
        try
        {
            val message = value.payload.toBridgeRpcMessage()
            when(message)
            {
                is RpcMessage.Request -> {
                    val context = RpcCallContext(connectionId = playerId, sender = { _ -> Unit })
                    val response = runBlockingDispatch(context, message)
                    responseObserver.onNext(RpcEnvelope(response.toBridgeJson()))
                }
                is RpcMessage.Notification -> {
                    val context = RpcCallContext(connectionId = playerId, sender = { _ -> Unit })
                    runBlockingNotification(context, message)
                    responseObserver.onNext(RpcEnvelope(RpcMessage.Response(id = "", result = null, error = null).toBridgeJson()))
                }
                else -> {
                    responseObserver.onNext(
                        RpcEnvelope(
                            RpcMessage.Response(
                                id = when(message) {
                                    is RpcMessage.Response -> message.id
                                    is RpcMessage.StreamChunk -> message.id
                                    is RpcMessage.StreamCancel -> message.id
                                    else -> ""
                                },
                                error = org.ttt.autogenesis.network.RpcError(
                                    code = 400,
                                    message = "Unsupported unary grpc-web message type"
                                )
                            ).toBridgeJson()
                        )
                    )
                }
            }
        }
        catch(err: Throwable)
        {
            Logger.error(LogCategory.NETWORK, "gRPC unary handler error: ${err.message}")
            responseObserver.onNext(
                RpcEnvelope(
                    RpcMessage.Response(
                        id = "",
                        error = org.ttt.autogenesis.network.RpcError(
                            code = 500,
                            message = err.message ?: "Unary bridge error"
                        )
                ).toBridgeJson()
                )
            )
        }
    }

    override fun onError(t: Throwable)
    {
        Logger.error(LogCategory.NETWORK, "gRPC unary bridge error: ${t.message}")
    }

    override fun onCompleted()
    {
        responseObserver.onCompleted()
    }

    private fun runBlockingDispatch(context: RpcCallContext, request: RpcMessage.Request): RpcMessage.Response
    {
        val response = kotlinx.coroutines.runBlocking {
            rpcRegistry.dispatch(request, context)
        }
        return response ?: RpcMessage.Response(
            id = request.id,
            error = org.ttt.autogenesis.network.RpcError(
                code = 501,
                message = "Streaming RPCs are not supported in grpc-web mode"
            )
        )
    }

    private fun runBlockingNotification(context: RpcCallContext, notification: RpcMessage.Notification)
    {
        kotlinx.coroutines.runBlocking {
            rpcRegistry.dispatchNotification(notification, context)
        }
    }
}