package org.ttt.autogenesis.network

import kotlinx.coroutines.CancellationException
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import kotlin.math.absoluteValue
import kotlin.random.Random

/**
 * JavaScript implementation of RestRpcBridge.
 * Optimized for browser environments and KVision integration.
 */
actual object RestRpcBridge
{
    private val rpcRegistry = RpcRegistry(RpcDirection.CLIENT)
    private var client : RestRpcClient? = null
    private var currentPlayerId : String? = null
    private var currentAccelbyteId : String? = null
    private val onConnectedCallbacks = mutableListOf<() -> Unit>()

    /**
     * The AccelByte user ID the current SSE inbound is bound to, or
     * `null` for an anonymous / pre-login session. Exposed for tests so
     * they can assert idempotency without spinning up a real server.
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
        console.info("RestRpcBridge: connect() invoked with playerId=$playerId accelbyteId=$accelbyteId target=$baseUrl guestMode=$isGuestMode origin=$origin")
        Logger.info(
            LogCategory.NETWORK,
            "RestRpcBridgeJs.connect: playerId=$playerId accelbyteId=$accelbyteId baseUrl=$baseUrl guestMode=$isGuestMode suppressSseLogs=$suppressSseLogs origin=$origin"
        )
        if(currentPlayerId == playerId && currentAccelbyteId == accelbyteId && isConnected)
        {
            console.info("RestRpcBridge: already connected as $playerId (accelbyteId=$accelbyteId)")
            return
        }

        // Tear down any previous client. `close()` is suspending and now
        // drains in-flight RPCs, so a no-op reconnect is safe even when
        // the previous client was actively serving requests.
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
        check(restClient.waitForConnection()) { "SSE connection timed out - server may be unavailable" }
        console.info("RestRpcBridge: connected to $baseUrl as $playerId (accelbyteId=$accelbyteId)")
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
