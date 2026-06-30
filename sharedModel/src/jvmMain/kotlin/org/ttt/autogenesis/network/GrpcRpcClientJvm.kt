package org.ttt.autogenesis.network

import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.grpc.PLAYER_ID_METADATA_KEY
import org.ttt.autogenesis.network.grpc.RPC_METHOD_DESCRIPTOR
import org.ttt.autogenesis.network.grpc.RpcEnvelope

/**
 * JVM implementation of the gRPC RPC client using standard gRPC libraries.
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
    private var handlerScope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var channel: ManagedChannel? = null
    private var requestObserver: StreamObserver<RpcEnvelope>? = null
    private val invoker = RpcInvoker(sender = { message -> sendMessage(message) })
    private var messageHandler: RpcMessageHandler? = null
    private val sessionReadyFlag = AtomicBoolean(false)
    private val isConnectedFlag = AtomicBoolean(false)

    actual val connectionId: String
        get() = config.playerId

    actual val rpcInvoker: RpcInvoker
        get() = invoker

    /**
     * Establishes the gRPC connection and starts message processing.
     * Sets up the managed channel, call, and response observer.
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
        if(isConnectedFlag.get()) {
            logger(RuntimeException("GrpcRpcClientJvm already connected"))
            return
        }

        resetScope()
        val observer = ResponseObserver()
        val headers = buildMetadata()
        val (host, port) = parseEndpoint(config.endpoint)
        val builder = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))

        val managedChannel = builder.build()
        channel = managedChannel
        val call = managedChannel.newCall(RPC_METHOD_DESCRIPTOR, CallOptions.DEFAULT)
        requestObserver = ClientCalls.asyncBidiStreamingCall(call, observer)

        val handler = RpcMessageHandler(
            connectionId = config.playerId,
            rpcRegistry = rpcRegistry,
            invoker = invoker,
            sender = { message -> sendMessage(message) },
            metadata = metadata
        )

        messageHandler = handler
        isConnectedFlag.set(true)
    }

    /**
     * Disconnects from the gRPC server and cleans up resources.
     */
    actual fun disconnect()
    {
        requestObserver?.let {
            try {
                it.onCompleted()
            } catch(_: Throwable) {
            }
        }
        requestObserver = null
        channel?.shutdownNow()
        channel = null
        handlerScope.cancel()
        sessionReadyFlag.set(false)
        isConnectedFlag.set(false)
        messageHandler = null
    }

    actual fun isConnected(): Boolean =
        isConnectedFlag.get()

    actual fun isSessionReady(): Boolean =
        sessionReadyFlag.get()

    /**
     * Closes the client and releases all resources.
     */
    actual suspend fun close()
    {
        disconnect()
        handlerScope.cancel()
    }

    /**
     * Resets the coroutine scope for clean reconnection.
     */
    private fun resetScope()
    {
        handlerScope.cancel()
        handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /**
     * Builds gRPC metadata headers including player ID and custom metadata.
     *
     * @return Configured metadata object for gRPC calls
     */
    private fun buildMetadata(): Metadata =
        Metadata().apply {
            metadata.forEach { (key, value) ->
                put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value)
            }
            put(PLAYER_ID_METADATA_KEY, config.playerId)
        }

    /**
     * Stream observer that handles incoming gRPC responses.
     * Processes RPC messages and manages session state.
     */
    private inner class ResponseObserver : StreamObserver<RpcEnvelope> {
        /**
         * Processes incoming RPC envelope messages.
         *
         * @param value The RPC envelope containing the message payload
         */
        override fun onNext(value: RpcEnvelope) {
            val handler = messageHandler ?: return
            handlerScope.launch {
                try {
                    val message = value.payload.toRpcMessage(RpcJson)
                    if (message is RpcMessage.Notification && message.method == "session.ready") {
                        sessionReadyFlag.set(true)
                    }
                    handler.handle(message)
                } catch(err: Throwable) {
                    logger(err)
                }
            }
        }

        /**
         * Handles stream errors by logging and cleaning up connection state.
         *
         * @param t The error that occurred on the stream
         */
        override fun onError(t: Throwable) {
            logger(t)
            handlerScope.cancel(CancellationException("gRPC client stream error", t))
            isConnectedFlag.set(false)
        }

        /**
         * Handles stream completion by cleaning up resources.
         */
        override fun onCompleted() {
            handlerScope.cancel()
            isConnectedFlag.set(false)
        }
    }

    /**
     * Sends an RPC message to the server through the request stream.
     *
     * @param message The RPC message to send
     * @throws IllegalStateException if the request observer is not available
     */
    private suspend fun sendMessage(message: RpcMessage)
    {
        val observer = requestObserver ?: throw IllegalStateException("gRPC request observer missing")
        val envelope = RpcEnvelope(message.toJson(RpcJson))
        observer.onNext(envelope)
    }

    /**
     * Parses a host:port endpoint for the JVM gRPC channel builder.
     *
     * @param endpoint Host and port string, optionally prefixed with `http://` or `https://`.
     * @return Parsed host and port pair.
     */
    private fun parseEndpoint(endpoint: String): Pair<String, Int>
    {
        val normalizedEndpoint = endpoint
            .trim()
            .removePrefix("https://")
            .removePrefix("http://")

        val host = normalizedEndpoint.substringBefore(":").trim()
        val portText = normalizedEndpoint.substringAfter(":").trim()
        val port = portText.toIntOrNull() ?: error("Invalid gRPC endpoint port in '$endpoint'")

        require(host.isNotBlank()) { "Invalid gRPC endpoint host in '$endpoint'" }
        return host to port
    }
}
