package org.ttt.autogenesis.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlin.math.absoluteValue
import kotlin.random.Random

/**
 * JavaScript implementation of the gRPC RPC bridge.
 * Manages client connections and provides RPC communication for browser environments.
 */
actual object GrpcRpcBridge
{
    private const val playerIdPrefix = "kvision-grpc-client"
    private val rpcRegistry = RpcRegistry(RpcDirection.CLIENT)
    private var client: GrpcRpcClient? = null
    private var currentPlayerId: String? = null
    private var currentEndpoint: String? = null

    actual val rpcInvoker: RpcInvoker?
        get() = client?.rpcInvoker

    actual val isConnected: Boolean
        get() = client?.isConnected() == true

    actual val isSessionReady: Boolean
        get() = client?.isSessionReady() == true

    /**
     * Registers RPC method handlers for processing incoming messages.
     *
     * @param block Lambda that configures the RPC registry with method handlers
     */
    actual fun registerHandlers(block: RpcRegistry.() -> Unit)
    {
        block(rpcRegistry)
    }

    /**
     * Establishes a gRPC connection to the specified endpoint.
     * Reuses existing connection if already connected to the same player ID.
     *
     * @param playerId Unique identifier for this client connection
     * @param endpoint gRPC server endpoint in "host:port" format
     */
    actual suspend fun connect(playerId: String, endpoint: String)
    {
        if(currentPlayerId == playerId && currentEndpoint == endpoint && isConnected)
        {
            console.info("GrpcRpcBridge: already connected as $playerId")
            return
        }

        client?.let {
            it.disconnect()
            try
            {
                it.close()
            }
            catch(err: CancellationException)
            {
                throw err
            }
            catch(_: Throwable)
            {
                // Swallow errors while replacing the client
            }
        }

        val grpcClient = GrpcRpcClient(
            GrpcRpcClientConfig(endpoint = endpoint, playerId = playerId),
            rpcRegistry = rpcRegistry
        )
        try
        {
            grpcClient.connect()
            console.info("GrpcRpcBridge: connect() invoked for $playerId @ $endpoint")
        }
        catch(err: Throwable)
        {
            console.error("GrpcRpcClient failed to connect: ${err.message}")
            throw err
        }

        client = grpcClient
        currentPlayerId = playerId
        currentEndpoint = endpoint
    }

    /**
     * Closes the gRPC connection and releases all resources.
     * Handles cleanup gracefully even if errors occur during shutdown.
     */
    actual suspend fun awaitInFlightRequests(timeoutMillis: Long): Boolean {
        val c = client ?: return true
        return c.awaitInFlightRequests(timeoutMillis)
    }

    actual suspend fun close()
    {
        client?.let {
            it.disconnect()
            try
            {
                it.close()
            }
            catch(err: CancellationException)
            {
                throw err
            }
            finally
            {
                client = null
                currentPlayerId = null
                currentEndpoint = null
            }
        }
    }

    /**
     * Generates a unique player ID for client identification.
     *
     * @return A randomly generated player ID string with "kvision-grpc-client" prefix
     */
    actual fun generatePlayerId(): String =
        "$playerIdPrefix-${Random.nextInt().absoluteValue}"
}
