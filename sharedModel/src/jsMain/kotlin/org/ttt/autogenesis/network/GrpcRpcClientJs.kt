package org.ttt.autogenesis.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlinx.coroutines.await
import kotlin.js.Promise
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

@JsModule("@autogenesis/grpc/grpc-web-client.js")
@JsNonModule
private external val GrpcWebClientModule: dynamic

@JsModule("@autogenesis/grpc/grpc-helpers.js")
@JsNonModule
private external fun createAsyncIterator(stream: dynamic): dynamic

@JsModule("@autogenesis/grpc/grpc-helpers.js")
@JsNonModule
private external fun assignAsyncIterator(iterator: dynamic): dynamic

/**
 * JavaScript implementation of the gRPC RPC client using Connect/grpc-web.
 * Handles bidirectional streaming communication with the gRPC server.
 *
 * @param config Client configuration containing endpoint and player ID
 * @param rpcRegistry Registry containing RPC method handlers
 * @param coroutineScope Optional coroutine scope for async operations
 * @param metadata Additional metadata to send with requests
 * @param logger Error logging function
 */
actual class GrpcRpcClient actual constructor(
    private val config: GrpcRpcClientConfig,
    private val rpcRegistry: RpcRegistry,
    coroutineScope: CoroutineScope?,
    private val metadata: Map<String, String>,
    private val logger: (Throwable) -> Unit
)
{
    private var handlerScope: CoroutineScope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sendChannel: Channel<RpcMessage> = createChannel()
    private var rpcClient: dynamic = null
    private var streamJob: Job? = null
    private var messageHandler: RpcMessageHandler = buildHandler()
    private var isConnectedFlag: Boolean = false
    private var isSessionReadyFlag: Boolean = false
    private var grpcWebMode: Boolean = false

    private val invoker: RpcInvoker = RpcInvoker(sender = { sendMessage(it) })

    actual val connectionId: String
        get() = config.playerId

    actual val rpcInvoker: RpcInvoker
        get() = invoker

    actual fun isConnected(): Boolean =
        isConnectedFlag

    actual fun isSessionReady(): Boolean =
        isSessionReadyFlag

    /**
     * Establishes the gRPC connection and starts message processing.
     * Sets up the transport, client, and response stream handling.
     */
    /**
     * Drain by polling the [RpcInvoker] pending map, identical to the
     * REST bridge's contract.
     */
    actual suspend fun awaitInFlightRequests(timeoutMillis: Long): Boolean
    {
        val invoker = rpcInvoker ?: return true
        val startMark = kotlin.time.TimeSource.Monotonic.markNow()
        while (startMark.elapsedNow().inWholeMilliseconds < timeoutMillis)
        {
            if (invoker.pendingCount() == 0) return true
            kotlinx.coroutines.yield()
        }
        return invoker.pendingCount() == 0
    }

    actual fun connect()
    {
        if(isConnectedFlag)
        {
            Logger.warn(LogCategory.NETWORK, "GrpcRpcClientJs already connected as ${config.playerId}")
            return
        }

        resetScope()
        sendChannel = createChannel()
        messageHandler = buildHandler()
        isSessionReadyFlag = false
        grpcWebMode = isGrpcWebEndpoint(config.endpoint)

        try
        {
            @Suppress("UNUSED_VARIABLE")
            val grpcWebClientModule = GrpcWebClientModule
            val createGrpcWebClient: dynamic = js("globalThis.__autogenesisCreateGrpcWebClient")
            rpcClient = createGrpcWebClient(config.endpoint)
            if(grpcWebMode)
            {
                isConnectedFlag = true
                isSessionReadyFlag = true
                Logger.info(LogCategory.NETWORK, "GrpcRpcClientJs connected to ${config.endpoint} as ${config.playerId} in grpc-web unary mode")
                return
            }
            val iterable = sendChannel.asGrpcIterable(handlerScope)
            val responses = rpcClient.stream(iterable, buildCallOptions())
            isConnectedFlag = true
            Logger.info(LogCategory.NETWORK, "GrpcRpcClientJs connected to ${config.endpoint} as ${config.playerId}")
            streamJob = handlerScope.launch {
                consumeResponses(responses)
            }
        }
        catch(err: Throwable)
        {
            val stack = runCatching { err.asDynamic().stack as? String }.getOrNull()
            val message = buildString {
                append("GrpcRpcClientJs failed to connect: ")
                append(err.message ?: err.toString())
                if(stack != null)
                {
                    append('\n').append(stack)
                }
            }
            Logger.error(
                LogCategory.NETWORK,
                message
            )
            isConnectedFlag = false
            throw err
        }
    }

    /**
     * Disconnects from the gRPC server and cleans up resources.
     */
    actual fun disconnect()
    {
        streamJob?.cancel()
        streamJob = null
        sendChannel.cancel()
        sendChannel = createChannel()
        isConnectedFlag = false
        isSessionReadyFlag = false
        rpcClient = null
        grpcWebMode = false
    }

    /**
     * Closes the client and releases all resources.
     */
    actual suspend fun close()
    {
        disconnect()
        handlerScope.cancel()
    }

    /**
     * Creates a new message channel for outbound RPC messages.
     *
     * @return Unlimited capacity channel for RPC messages
     */
    private fun createChannel(): Channel<RpcMessage> =
        Channel(Channel.UNLIMITED)

    /**
     * Resets the coroutine scope for clean reconnection.
     */
    private fun resetScope()
    {
        handlerScope.cancel()
        handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /**
     * Creates the RPC message handler for processing incoming messages.
     *
     * @return Configured message handler with connection context
     */
    private fun buildHandler(): RpcMessageHandler =
        RpcMessageHandler(
            connectionId = config.playerId,
            rpcRegistry = rpcRegistry,
            invoker = invoker,
            sender = { message -> sendMessage(message) },
            metadata = metadata
        )

    /**
     * Sends an RPC message through the outbound channel.
     *
     * @param message The RPC message to send
     */
    private suspend fun sendMessage(message: RpcMessage)
    {
        if(grpcWebMode)
        {
            val envelope = buildEnvelope(message)
            @Suppress("UNCHECKED_CAST")
            val response = (rpcClient.invoke(envelope, buildCallOptions()) as Promise<dynamic>).await()
            val payload = response?.payload as? String ?: return
            val responseMessage = payload.toRpcMessage(RpcJson)
            if(responseMessage is RpcMessage.Response)
            {
                messageHandler.handle(responseMessage)
            }
            return
        }

        if(!sendChannel.isClosedForSend)
        {
            sendChannel.send(message)
        }
    }

    private fun buildCallOptions(): dynamic
    {
        val headers = js("{}")
        metadata.forEach { (key, value) -> headers[key] = value }
        headers["player-id"] = config.playerId
        val options = js("{}")
        options.headers = headers
        return options
    }

    private suspend fun consumeResponses(stream: dynamic)
    {
        val iterator = createAsyncIterator(stream)
        try
        {
            while(true)
            {
                val result = iterator.next().await()
                if(result.done as Boolean) break
                handleEnvelope(result.value)
            }
        }
        catch(err: CancellationException)
        {
            // cancellation is expected when the coroutine is cancelled
        }
        catch(err: Throwable)
        {
            logger(err)
        }
        finally
        {
            isConnectedFlag = false
            isSessionReadyFlag = false
        }
    }

    private fun handleEnvelope(envelope: dynamic)
    {
        val payload = envelope?.payload as? String ?: return
        try
        {
            val message = payload.toRpcMessage(RpcJson)
            if(message is RpcMessage.Notification && message.method == "session.ready")
            {
                isSessionReadyFlag = true
            }
            handlerScope.launch {
                try
                {
                    messageHandler.handle(message)
                }
                catch(err: Throwable)
                {
                    logger(err)
                }
            }
        }
        catch(err: Throwable)
        {
            logger(err)
        }
    }

    private fun Channel<RpcMessage>.asGrpcIterable(scope: CoroutineScope): dynamic
    {
        val iterator = assignAsyncIterator(js("({})"))
        iterator.next = {
            scope.promise {
                try
                {
                    val message = receive()
                    val envelope = buildEnvelope(message)
                    js("({ done: false, value: envelope })")
                }
                catch(err: ClosedReceiveChannelException)
                {
                    js("({ done: true })")
                }
            }
        }
        return iterator
    }

    private fun buildEnvelope(message: RpcMessage): dynamic
    {
        val payload = message.toJson(RpcJson)
        val envelope = js("{}")
        envelope.payload = payload
        return envelope
    }

    private fun isGrpcWebEndpoint(endpoint: String): Boolean =
        endpoint.startsWith("http://") || endpoint.startsWith("https://")
}