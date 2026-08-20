package org.ttt.autogenesis.kvisionapp

import globals.AccelByteEnv
import globals.KEnv
import globals.ServerExtendConfig
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.GrpcRpcBridge as SharedGrpcRpcBridge
import org.ttt.autogenesis.network.RpcInvoker
import org.ttt.autogenesis.network.RpcOrigin
import org.ttt.autogenesis.network.RpcRegistry
import org.ttt.autogenesis.network.ServerExtendTransport
import org.ttt.autogenesis.network.RestRpcBridge as SharedRestRpcBridge

/**
 * Transport-aware bridge facade for server-extend.
 *
 * This keeps the existing REST/SSE path as a fallback while allowing the UI to
 * switch to the shared gRPC bridge when the transport mode requires it.
 */
object ServerExtendBridge
{
    /**
     * Returns the active RPC invoker for the configured transport, or null when disconnected.
     */
    val rpcInvoker: RpcInvoker?
        get() = when(activeTransport())
        {
            ServerExtendTransport.REST_SSE -> SharedRestRpcBridge.rpcInvoker
            ServerExtendTransport.GRPC_BIDI, ServerExtendTransport.GRPC_WEB -> SharedGrpcRpcBridge.rpcInvoker
        }

    /**
     * Returns whether the currently selected transport has an active connection.
     */
    val isConnected: Boolean
        get() = when(activeTransport())
        {
            ServerExtendTransport.REST_SSE -> SharedRestRpcBridge.isConnected
            ServerExtendTransport.GRPC_BIDI, ServerExtendTransport.GRPC_WEB -> SharedGrpcRpcBridge.isConnected
        }

    /**
     * Returns whether the active transport is ready for RPC work.
     */
    val isSessionReady: Boolean
        get() = when(activeTransport())
        {
            ServerExtendTransport.REST_SSE -> SharedRestRpcBridge.isSessionReady
            ServerExtendTransport.GRPC_BIDI, ServerExtendTransport.GRPC_WEB -> SharedGrpcRpcBridge.isSessionReady
        }

    /**
     * Registers client-side RPC handlers on the transport selected for server-extend.
     *
     * @param block Lambda that configures the shared RPC registry.
     */
    fun registerHandlers(block: RpcRegistry.() -> Unit)
    {
        when(activeTransport())
        {
            ServerExtendTransport.REST_SSE -> SharedRestRpcBridge.registerHandlers(block)
            ServerExtendTransport.GRPC_BIDI, ServerExtendTransport.GRPC_WEB -> SharedGrpcRpcBridge.registerHandlers(block)
        }
    }

    /**
     * Connects the selected transport to server-extend.
     *
     * The REST/SSE path accepts an optional [accelbyteId] which is
     * propagated to the shared bridge so the post-login rebind is a
     * single-call operation. When omitted, the current
     * [AccelByteEnv.userId] is used; this keeps the post-auth flow
     * (`loadSavedCommanders` / `MatchmakingClient`) bound to the
     * authenticated user without each caller having to thread the id.
     *
     * @param playerId Optional player identifier used by both transport implementations.
     * @param autoReconnect Retained for REST/SSE compatibility; ignored by gRPC transports.
     * @param suppressSseLogs Retained for REST/SSE compatibility; ignored by gRPC transports.
     * @param accelbyteId Optional AccelByte user ID; defaults to the
     *                    current [AccelByteEnv.userId] when null.
     */
    suspend fun connect(
        playerId: String = generatePlayerId(),
        autoReconnect: Boolean = true,
        suppressSseLogs: Boolean = true,
        accelbyteId: String? = null,
        origin: RpcOrigin = RpcOrigin.GAME_CLIENT
    )
    {
        val transport = activeTransport()
        when(transport)
        {
            ServerExtendTransport.REST_SSE ->
            {
                val resolvedAccelbyteId = accelbyteId ?: AccelByteEnv.userId.takeIf { it.isNotBlank() }
                Logger.info(
                    LogCategory.NETWORK,
                    "ServerExtendBridge: connecting REST/SSE transport (accelbyteId=$resolvedAccelbyteId, origin=$origin)"
                )
                SharedRestRpcBridge.connect(
                    playerId = playerId,
                    baseUrl = ServerExtendConfig.defaultServerUrl,
                    autoReconnect = autoReconnect,
                    suppressSseLogs = suppressSseLogs,
                    isGuestMode = KEnv.skipLogin,
                    accelbyteId = resolvedAccelbyteId,
                    origin = origin
                )
            }

            ServerExtendTransport.GRPC_BIDI, ServerExtendTransport.GRPC_WEB ->
            {
                val endpoint = when(transport)
                {
                    ServerExtendTransport.GRPC_BIDI -> ServerExtendConfig.defaultGrpcServerUrl
                    ServerExtendTransport.GRPC_WEB -> ServerExtendConfig.defaultGrpcWebServerUrl
                    ServerExtendTransport.REST_SSE -> error("gRPC endpoint requested for REST transport")
                }

                Logger.info(
                    LogCategory.NETWORK,
                    "ServerExtendBridge: connecting $transport transport at $endpoint"
                )
                SharedGrpcRpcBridge.connect(
                    playerId = playerId,
                    endpoint = endpoint
                )
            }
        }
    }

    /**
     * Disconnects the selected transport and releases client resources.
     */
    suspend fun close()
    {
        when(activeTransport())
        {
            ServerExtendTransport.REST_SSE -> SharedRestRpcBridge.close()
            ServerExtendTransport.GRPC_BIDI, ServerExtendTransport.GRPC_WEB -> SharedGrpcRpcBridge.close()
        }
    }

    /**
     * Runs [block] with a temporary server-extend connection for the active transport.
     *
     * @param block The work to execute while connected.
     * @return The result produced by [block].
     */
    suspend fun <T> withTemporaryConnection(block: suspend () -> T): T
    {
        val wasConnected = isConnected
        if(!wasConnected)
        {
            connect(autoReconnect = false)
        }

        try
        {
            return block()
        }
        finally
        {
            if(!wasConnected && isConnected)
            {
                // Drain in-flight requests before closing so a request
                // whose SSE response is still in transit gets to land
                // naturally. Without this, `close()` would cancel the
                // pending deferreds via drainInFlightRequests() and the
                // caller would see `RestRpcClient closed before response`
                // even though the server did answer. 2s is well above
                // p99 SSE delivery on localhost. (Same fix applied to
                // [org.ttt.autogenesis.kvisionapp.RestRpcBridge.withTemporaryConnection].)
                when(activeTransport())
                {
                    ServerExtendTransport.REST_SSE -> SharedRestRpcBridge.awaitInFlightRequests(timeoutMillis = 2_000L)
                    ServerExtendTransport.GRPC_BIDI, ServerExtendTransport.GRPC_WEB -> SharedGrpcRpcBridge.awaitInFlightRequests(timeoutMillis = 2_000L)
                }
                close()
            }
        }
    }

    /**
     * Generates a stable player ID for the active transport.
     *
     * @return A transport-specific player identifier.
     */
    fun generatePlayerId(): String =
        when(activeTransport())
        {
            ServerExtendTransport.REST_SSE -> SharedRestRpcBridge.generatePlayerId()
            ServerExtendTransport.GRPC_BIDI, ServerExtendTransport.GRPC_WEB -> SharedGrpcRpcBridge.generatePlayerId()
        }

    /**
     * Returns the transport currently selected in [ServerExtendConfig].
     */
    fun activeTransport(): ServerExtendTransport =
        ServerExtendConfig.transport

    /**
     * Forces the REST/SSE transport for matchmaking RPCs.
     *
     * When the active transport is gRPC (which is not yet fully wired for the
     * server.extend.requestGame method), this forces REST/SSE so that
     * [rpcInvoker] returns a non-null value from [SharedRestRpcBridge].
     *
     * Does nothing if REST/SSE is already the active transport.
     *
     * @return The forced transport, or the already-active REST_SSE transport.
     */
    fun forceRestTransportForMatchmaking(): ServerExtendTransport
    {
        val current = activeTransport()
        if(current == ServerExtendTransport.REST_SSE)
        {
            return ServerExtendTransport.REST_SSE
        }
        val msg = "ServerExtendBridge: Forcing REST/SSE transport for matchmaking (was ${current})"
        Logger.info(LogCategory.NETWORK, msg)
        ServerExtendConfig.transport = ServerExtendTransport.REST_SSE
        return ServerExtendTransport.REST_SSE
    }
}