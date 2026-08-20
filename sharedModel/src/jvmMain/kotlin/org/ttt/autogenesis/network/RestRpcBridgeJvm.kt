package org.ttt.autogenesis.network

import kotlinx.coroutines.CancellationException
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import kotlin.math.absoluteValue
import kotlin.random.Random

/**
 * JVM implementation of RestRpcBridge.
 * Optimized for server environments with proper logging integration.
 */
actual object RestRpcBridge
{
    private val rpcRegistry = RpcRegistry(RpcDirection.CLIENT)
    private var client : RestRpcClient? = null
    private var currentPlayerId : String? = null
    private var currentAccelbyteId : String? = null
    private val onConnectedCallbacks = mutableListOf<() -> Unit>()

    /**
     * The AccelByte user ID the current session is bound to, or `null`
     * for an anonymous session. Mirrors the JS implementation so tests
     * can assert idempotency on `(playerId, accelbyteId)`.
     */
    val connectionAccelbyteId : String?
        get() = currentAccelbyteId

    actual val rpcInvoker : RpcInvoker?
        get() = client?.rpcInvoker

    actual val isConnected : Boolean
        get() = client?.isConnected() == true

    actual val isSessionReady : Boolean
        get() = client?.isSessionReady() == true

    actual fun registerHandlers(block : RpcRegistry.() -> Unit)
    {
        block(rpcRegistry)
    }

    actual suspend fun connect(
        playerId : String,
        baseUrl : String,
        autoReconnect : Boolean,
        suppressSseLogs : Boolean,
        isGuestMode : Boolean,
        accelbyteId : String?,
        origin : RpcOrigin
    )
    {
        Logger.info(
            LogCategory.NETWORK,
            "RestRpcBridge: connect() invoked with playerId=$playerId accelbyteId=$accelbyteId target=$baseUrl guestMode=$isGuestMode origin=$origin"
        )
        if(currentPlayerId == playerId && currentAccelbyteId == accelbyteId && isConnected)
        {
            Logger.info(LogCategory.NETWORK, "RestRpcBridge: already connected as $playerId (accelbyteId=$accelbyteId, origin=$origin)")
            return
        }

        // Tear down any previous client. `close()` is suspending and now
        // drains in-flight RPCs, so a rebind mid-request is safe.
        client?.let {
            it.disconnect()
            runCatching {
                it.close()
            }
        }

        val restClient = RestRpcClient(
            RestRpcClientConfig(
                baseUrl = baseUrl,
                accelbyteId = accelbyteId,
                playerId = playerId,
                suppressSseLogs = suppressSseLogs,
                isGuestMode = isGuestMode,
                origin = origin
            ),
            rpcRegistry = rpcRegistry
        )
        restClient.connect(autoReconnect)
        Logger.info(LogCategory.NETWORK, "RestRpcBridge: connected to $baseUrl as $playerId (accelbyteId=$accelbyteId)")
        client = restClient
        currentPlayerId = playerId
        currentAccelbyteId = accelbyteId
        // Fire callbacks AFTER client is fully assigned so rpcInvoker is accessible
        onConnectedCallbacks.forEach { it.invoke() }
        onConnectedCallbacks.clear()
    }

        /**
     * Delegate to the active [RestRpcClient]. See
     * [RestRpcClient.awaitInFlightRequests] for the full contract.
     */
    actual suspend fun awaitInFlightRequests(timeoutMillis: Long): Boolean
    {
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
            catch(err : CancellationException)
            {
                throw err
            }
            finally
            {
                client = null
                currentPlayerId = null
                currentAccelbyteId = null
            }
        }
    }

    /**
     * Register a callback to be invoked once the SSE connection is established.
     * If already connected, the callback is invoked immediately.
     * The callback is cleared after invocation.
     */
    actual fun onConnected(fn: () -> Unit)
    {
        if (client?.isConnected() == true)
        {
            fn()
        }
        else
        {
            onConnectedCallbacks.add(fn)
        }
    }

    actual fun generatePlayerId() : String =
        "${RestRpcBridgeConfig.PLAYER_ID_PREFIX}-${Random.nextInt().absoluteValue}"
}