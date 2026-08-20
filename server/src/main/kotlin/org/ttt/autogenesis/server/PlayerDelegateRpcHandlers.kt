package org.ttt.autogenesis.server

import gameState.WorldManager
import kotlinx.coroutines.sync.withLock
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod
import structs.rpcRequests.DELEGATE_INSTRUCTIONS_MAX_LENGTH
import structs.rpcRequests.SetDelegateInstructionsRequest

/**
 * Server-side RPC handlers for the "delegate" feature, which lets a player author
 * free-form guidance for the AI that takes over their character when they are
 * unreachable (see `agent.builders.playerAgent.buildPlayerAgent` and the
 * `##PLAYER DELEGATE GUIDANCE##` context slot it injects into the planning stage).
 *
 * The data lives on `structs.Player.delegateInstructions` so it travels with the
 * world snapshot (AccelByte CloudSave) and is broadcast to every connected client
 * via the existing `UiSignalRpcHandlers.broadcastWorldUpdate` path. No new
 * persistence layer is introduced.
 *
 * The handler is auto-discovered by the `rpc-ksp` annotation processor; a
 * `GeneratedPlayerDelegateRpcHandlers.kt` is produced at build time and registered
 * with the `RpcRegistry` via the standard `RpcRegistrationCollector` flow. See
 * `server/build/generated/ksp/main/...` for the generated bindings.
 */
object PlayerDelegateRpcHandlers
{
    /**
     * Persists [SetDelegateInstructionsRequest.instructions] onto the named player and
     * broadcasts the updated world to all clients.
     *
     * Validation:
     * - `playerName` must match a player in `WorldManager.world.activePlayers`.
     * - `instructions` is null OR its trimmed length is within
     *   [DELEGATE_INSTRUCTIONS_MAX_LENGTH]. Excess characters are truncated; null/blank
     *   input is normalized to `null` so the agent's `(none provided)` fallback fires.
     *
     * Concurrency: the player mutation runs under `WorldManager.worldMutex` so it is
     * serialized with the rest of the world-state writers. The broadcast happens
     * *after* the lock is released so a slow SSE channel cannot stall other writers.
     *
     * @param ctx RPC call context (used for logging the caller's connectionId).
     * @param request The new instructions payload from the client.
     * @return True if the player's `delegateInstructions` was updated, false if the
     *         player was not found.
     */
    @RpcMethod(name = "player.setDelegateInstructions", direction = RpcDirection.SERVER)
    suspend fun setDelegateInstructions(ctx: RpcCallContext, request: SetDelegateInstructionsRequest): Boolean
    {
        Logger.debug(
            LogCategory.NETWORK,
            "PlayerDelegateRpcHandlers.setDelegateInstructions: ENTRY player='${request.playerName}' conn=${ctx.connectionId} incomingLength=${request.instructions?.length ?: 0}"
        )
        if(request.playerName.isBlank())
        {
            Logger.warn(LogCategory.NETWORK, "PlayerDelegateRpcHandlers.setDelegateInstructions: blank playerName (conn=${ctx.connectionId}); ignoring")
            return false
        }

        val normalizedInstructions: String? = request.instructions
            ?.take(DELEGATE_INSTRUCTIONS_MAX_LENGTH)
            ?.takeIf { it.isNotBlank() }

        val player = WorldManager.world.activePlayers.firstOrNull { it.name == request.playerName }
        if(player == null)
        {
            Logger.warn(LogCategory.NETWORK, "PlayerDelegateRpcHandlers.setDelegateInstructions: player '${request.playerName}' not found (conn=${ctx.connectionId}); ignoring")
            return false
        }

        WorldManager.worldMutex.withLock {
            player.delegateInstructions = normalizedInstructions
        }

        Logger.info(
            LogCategory.NETWORK,
            "PlayerDelegateRpcHandlers.setDelegateInstructions: player='${player.name}' length=${normalizedInstructions?.length ?: 0} (conn=${ctx.connectionId})"
        )

        // Broadcast outside the lock so a slow subscriber cannot stall world writers.
        UiSignalRpcHandlers.broadcastWorldUpdate(WorldManager.world)
        return true
    }
}