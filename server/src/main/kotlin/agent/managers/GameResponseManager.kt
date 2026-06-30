package agent.managers

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages suspended game responses from players.
 * Allows orchestrators to wait for player input triggered by RPC.
 */
object GameResponseManager {
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /**
     * Creates a wait point for a player response.
     * @param playerId The ID of the player being waited on.
     * @return A CompletableDeferred that will be completed when the player responds or timeouts.
     */
    fun waitForResponse(playerId: String): CompletableDeferred<String?> {
        val deferred = CompletableDeferred<String?>()
        pendingResponses[playerId] = deferred
        return deferred
    }

    /**
     * Completes a pending response for a player.
     * @param playerId The ID of the player who responded.
     * @param response The response text, or null if ignored/canceled.
     */
    fun submitResponse(playerId: String, response: String?) {
        val deferred = pendingResponses.remove(playerId)
        if (deferred != null) {
            deferred.complete(response)
        }
    }

    /**
     * Clears a pending response if it exists.
     */
    fun clearResponse(playerId: String) {
        pendingResponses.remove(playerId)
    }
}
