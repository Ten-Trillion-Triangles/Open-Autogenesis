package org.ttt.autogenesis.serverextend

import org.ttt.autogenesis.network.PlayerLookupRequest
import org.ttt.autogenesis.network.PlayerLookupResponse
import org.ttt.autogenesis.network.PingResponse
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcRegistry
import org.ttt.autogenesis.server.GameRpcHandlers
import structs.CountStreamRequest
import structs.CountStreamResponse
import structs.World

/**
 * Registers extended game RPC handlers with the provided RPC registry.
 * 
 * Binds server-side RPC methods to their corresponding handler implementations
 * in [GameRpcHandlers]. This creates the mapping between RPC method names and
 * their execution logic for the REST-based server extension.
 * 
 * Registered methods include:
 * - game.player.lookup: Player search functionality
 * - server.ping: Connection health check
 * - game.stream.count: Streaming count updates
 * - game.world.snapshot: Current world state retrieval
 * 
 * @param rpcRegistry Registry to register the RPC method handlers with
 */
internal fun registerExtendedGameRpcHandlers(rpcRegistry : RpcRegistry)
{
    // Register typed player lookup RPC with request/response serialization
    rpcRegistry.registerTyped(
        method = "game.player.lookup",
        direction = RpcDirection.SERVER,
        serializer = PlayerLookupRequest.serializer(),
        resultSerializer = PlayerLookupResponse.serializer()
    ) { ctx, payload ->
        // Delegate to existing game handler implementation
        val response = GameRpcHandlers.findPlayer(ctx, payload)
        response
    }

    // Register server ping RPC for connection health checks
    rpcRegistry.register("server.ping", RpcDirection.SERVER) { ctx, _ ->
        val response = GameRpcHandlers.ping(ctx)
        // Always send response, never null
        RpcJson.encodeToJsonElement(PingResponse.serializer(), response)
    }

    // Register streaming count RPC for real-time count updates
    rpcRegistry.registerStream(
        method = "game.stream.count",
        direction = RpcDirection.SERVER,
        serializer = CountStreamRequest.serializer(),
        resultSerializer = CountStreamResponse.serializer()
    ) { ctx, payload ->
        // Delegate to streaming handler implementation
        GameRpcHandlers.streamCounts(ctx, payload)
    }

    // Register world snapshot RPC for current game state retrieval
    rpcRegistry.register("game.world.snapshot", RpcDirection.SERVER) { ctx, _ ->
        val response = GameRpcHandlers.worldSnapshot(ctx)
        // Convert world state to JSON element for transmission
        response?.let { RpcJson.encodeToJsonElement(World.serializer(), it) }
    }
}