package structs.rpcRequests

import kotlinx.serialization.Serializable

/**
 * Hard cap (in characters) for [SetDelegateInstructionsRequest.instructions].
 *
 * The server enforces this even when the client counter is bypassed so a malicious or
 * careless client cannot blow out the planning stage's context window. Mirrors the
 * soft cap rendered by `DelegateWidget` in the KVision UI.
 */
const val DELEGATE_INSTRUCTIONS_MAX_LENGTH: Int = 1500

/**
 * Request to set (or clear) the [structs.Player.delegateInstructions] for the named player.
 *
 * Sent by the KVision `DelegateWidget` over `player.setDelegateInstructions`. The server
 * mutates the player in [gameState.WorldManager] under the world mutex and then calls
 * `UiSignalRpcHandlers.broadcastWorldUpdate` so all clients (and the agent pipelines
 * running on the server) see the new value without any new persistence path.
 *
 * Blank/null instructions are normalized to `null` so the player agent's planning
 * stage falls back to the explicit `(none provided)` rendering in its context slot.
 *
 * @param playerName The player whose instructions are being set. Must match a player in
 *                   `WorldManager.world.activePlayers`; mismatches are rejected.
 * @param instructions The new instructions, or `null` to clear. Truncated to
 *                     [DELEGATE_INSTRUCTIONS_MAX_LENGTH] characters server-side.
 */
@Serializable
data class SetDelegateInstructionsRequest(
    val playerName: String,
    val instructions: String?
)
