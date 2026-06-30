package org.ttt.autogenesis.server

import gameState.WorldManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.ttt.autogenesis.network.AgentWorkStreamSubscriptionRequest
import org.ttt.autogenesis.network.*
import org.ttt.autogenesis.server.AgentWorkStreamDispatcher
import structs.CountStreamRequest
import structs.CountStreamResponse
import structs.World
import agent.runners.executeNpcTurn
import structs.Npc
import enums.NpcType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Server-side RPC handlers for game functionality.
 */
object GameRpcHandlers
{
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Responds to ping requests from clients.
     *
     * @param ctx RPC call context
     * @return Ping response with connection ID and timestamp
     */
    @RpcMethod(name = "server.ping", direction = RpcDirection.SERVER)
    suspend fun ping(ctx: RpcCallContext): PingResponse
    {
        return PingResponse(
            echo = ctx.connectionId,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Provides current world state snapshot.
     *
     * @param ctx RPC call context
     * @return Current world state
     */
    @RpcMethod(name = "game.world.snapshot", direction = RpcDirection.SERVER)
    suspend fun worldSnapshot(ctx: RpcCallContext): World
    {
        return WorldManager.world
    }

    /**
     * Looks up a player by name in the active world.
     *
     * @param ctx RPC call context
     * @param request Player lookup request with name
     * @return Player lookup response with found status and data
     */
    @RpcMethod(name = "game.player.lookup", direction = RpcDirection.SERVER)
    suspend fun findPlayer(ctx: RpcCallContext, request: PlayerLookupRequest): PlayerLookupResponse
    {
        val matchedPlayer = WorldManager.world.activePlayers.firstOrNull { it.name == request.playerName }
        return PlayerLookupResponse(
            found = matchedPlayer != null,
            player = matchedPlayer
        )
    }

    /**
     * Streams sequential integers back to the caller.
     */
    @RpcMethod(name = "game.stream.count", direction = RpcDirection.SERVER)
    suspend fun streamCounts(ctx: RpcCallContext, request: CountStreamRequest): Flow<CountStreamResponse>
    {
        return flow {
            repeat(request.count) { index ->
                emit(CountStreamResponse(value = request.start + index))
            }
        }
    }

    @RpcMethod(name = "test.boolean", direction = RpcDirection.SERVER)
    suspend fun testBoolean(ctx: RpcCallContext, input: Boolean): Boolean
    {
        return input
    }

    /**
     * Submits a player action for processing.
     *
     * @param ctx RPC call context
     * @param request Action submission request
     * @return True if the action was accepted for processing
     */
    @RpcMethod(name = "game.submitAction", direction = RpcDirection.SERVER)
    suspend fun submitAction(ctx: RpcCallContext, request: ActionSubmitRequest): Boolean
    {
        Logger.info(LogCategory.NETWORK, "GameRpcHandlers.submitAction: === ENTRY === player=${request.playerName}, connection=${ctx.connectionId}")
        Logger.info(LogCategory.NETWORK, "GameRpcHandlers.submitAction: Action='${request.action.take(100)}...' (total length=${request.action.length})")
        
        val player = WorldManager.world.activePlayers.firstOrNull { it.name == request.playerName }
        if(player == null)
        {
            Logger.error(LogCategory.NETWORK, "GameRpcHandlers.submitAction: Player '${request.playerName}' NOT FOUND in activePlayers!")
            Logger.debug(LogCategory.NETWORK, "GameRpcHandlers.submitAction: activePlayers list: ${WorldManager.world.activePlayers.map { it.name }}")
            return false
        }
        Logger.info(LogCategory.NETWORK, "GameRpcHandlers.submitAction: Player found: ${player.name}")

        // Run the orchestrator in a separate scope so we can respond to the RPC quickly
        scope.launch {
            try
            {
                Logger.info(LogCategory.SYSTEM, "GameRpcHandlers.submitAction: Launching coroutine to call runNextTurn() then submitPlayerPlay()")
                Logger.info(LogCategory.SYSTEM, "GameRpcHandlers.submitAction: About to call TurnHarness.runNextTurn()...")
                TurnHarness.runNextTurn()
                Logger.info(LogCategory.SYSTEM, "GameRpcHandlers.submitAction: runNextTurn() returned. Now calling submitPlayerPlay()...")
                TurnHarness.submitPlayerPlay(ctx, player, request.action)
                Logger.info(LogCategory.SYSTEM, "GameRpcHandlers.submitAction: submitPlayerPlay() returned.")
            }
            catch(e: Exception)
            {
                Logger.error(LogCategory.GENERAL, "GameRpcHandlers.submitAction: Error executing player turn for ${request.playerName}: ${e.message}")
                e.printStackTrace()
            }
        }

        Logger.info(LogCategory.NETWORK, "GameRpcHandlers.submitAction: Returning true (Coroutine will handle processing)")
        return true
    }

    /**
     * Submits a player's counter-play response (e.g. to defensive prompt).
     *
     * @param ctx RPC call context
     * @param request Counter-action submission request
     * @return True if the response was accepted
     */
    @RpcMethod(name = "game.submitCounterAction", direction = RpcDirection.SERVER)
    suspend fun submitCounterAction(ctx: RpcCallContext, request: CounterActionSubmitRequest): Boolean
    {
        Logger.info(LogCategory.NETWORK, "Counter-action submitted by ${request.playerName}")
        agent.managers.GameResponseManager.submitResponse(request.playerName, request.response)
        return true
    }

    /**
     * Toggles the agent work stream subscription for the requesting player.
     */
    @RpcMethod(name = "game.agentWorkStream.subscribe", direction = RpcDirection.SERVER)
/**
     * Toggles the agent work stream subscription for the requesting player by subscribing/unsubscribing
     * `AgentWorkStreamDispatcher` using their connection ID.
     */
    suspend fun toggleAgentWorkStream(ctx: RpcCallContext, request: AgentWorkStreamSubscriptionRequest): Boolean
    {
        if (request.subscribe)
        {
            AgentWorkStreamDispatcher.subscribe(ctx.connectionId)
        }
        else
        {
            AgentWorkStreamDispatcher.unsubscribe(ctx.connectionId)
        }
        return true
    }

    @RpcMethod(name = "game.backfill", direction = RpcDirection.SERVER)
    suspend fun handleBackfill(ctx: RpcCallContext, request: accelbyte.backfill.BackfillTicketRequest): accelbyte.backfill.BackfillHandlerResult
    {
        Logger.info(LogCategory.NETWORK, "GameRpcHandlers: Backfill request received for userId=${request.userId}")
        return WorldManager.handleBackfillRequest(request)
    }

    /**
     * Concedes the match on behalf of the calling human player. The caller MUST be
     * the connection that owns the target player (validated via
     * [WorldManager.findPlayerFromStats].playerID). Server-side validation in
     * [TurnHarness.surrenderPlayer] also rejects AI-controlled or already-surrendered
     * players. On success the response carries the updated [SurrenderResponse.gameEnded]
     * and (when known) the resulting [SurrenderResponse.winnerName].
     */
    @RpcMethod(name = "game.surrender", direction = RpcDirection.SERVER)
    suspend fun surrender(ctx: RpcCallContext, request: SurrenderRequest): SurrenderResponse
    {
        Logger.info(LogCategory.NETWORK, "GameRpcHandlers.surrender: === ENTRY === player=${request.playerName}, connection=${ctx.connectionId}, reason='${request.reason}'")
        val stats = WorldManager.findPlayerFromStats(request.playerName)
        val callerOwnsTarget = stats != null && stats.playerID == ctx.connectionId
        if(!callerOwnsTarget)
        {
            Logger.warn(
                LogCategory.NETWORK,
                "GameRpcHandlers.surrender: connection ${ctx.connectionId} attempted to surrender ${request.playerName} but is not the owner."
            )
            return SurrenderResponse(accepted = false, reason = "not_owner")
        }
        val result = TurnHarness.surrenderPlayer(request.playerName, request.reason)
        return SurrenderResponse(
            accepted = result.accepted,
            reason = result.reason,
            gameEnded = result.gameEnded,
            winnerName = result.winnerName
        )
    }
}
