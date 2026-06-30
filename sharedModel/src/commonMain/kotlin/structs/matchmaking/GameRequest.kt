package structs.matchmaking

import kotlinx.serialization.Serializable
import structs.Commander

@Serializable
enum class GameType
{
    SINGLEPLAYER,
    MULTIPLAYER,
    FRIEND,
}

/**
 * Defines a game request made by a client. This allows us to switch on the request type,
 * begin matchmaking, and tell the backend which player + commander should enter the session.
 */
@Serializable
data class GameRequest(
    var userName: String,
    var gameType: GameType,
    var accelByteId: String,
    var websocketId: String = "",
    var selectedCommander: Commander? = null,
    var aiOpponentCount: Int = 3,
    var aiOnly: Boolean = false,
    var matchPool: String = "default",
    /** Alias for matching a player to an existing session (e.g., Python controller joins browser's game). */
    var playerAlias: String = ""
)

/**
 * Represents a "game ticket" which is used to provide instructions back to on what to do next. This may provide
 * a server url on the spot, or instructions to check back later for a match. Can also provide an AccelByte session
 * id to give to the game server to verify the correct user has arrived.
 */
@Serializable
data class GameTicket(
    var sessionId: String,
    var serverUrl: String,
    var matchmakingStarted: Boolean = false
)

/**
 * Data class to bundle results of a game ticket check-in request. Allows the client to understand when the session is
 * ready, or if it has been cancelled.
 */
@Serializable
data class GameTicketStatus(
    var isReady: Boolean = false,
    var isCancelled: Boolean = false
)
