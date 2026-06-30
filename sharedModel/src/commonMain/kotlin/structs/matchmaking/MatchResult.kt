package structs.matchmaking

import kotlinx.serialization.Serializable

/**
 * Notifies a matched player of their server assignment after matchmaking completes.
 * Sent from server-extend to matched players via the existing isServerReady + resolveUrl RPC.
 *
 * @param sessionId The AccelByte game session ID
 * @param serverUrl The game server IP:port to connect to
 * @param expectedPlayerIds List of all AccelByte user IDs in the matched session
 */
@Serializable
data class MatchResult(
    val sessionId: String,
    val serverUrl: String,
    val expectedPlayerIds: List<String>
)
