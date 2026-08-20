package structs.matchmaking

import kotlinx.serialization.Serializable
import structs.Commander

/**
 * Bundle to combine a player's AccelByte identity, websocket id, and selected commander for quick access.
 */
@Serializable
data class PlayerSessionBundle(
    var accelByteUserName: String,
    var accelByteId: String,
    var websocketId: String,
    var commander: Commander? = null,
    /**
     * Optional alias for a secondary controller (e.g., Python debugger) to join
     * the same player slot as the primary WebSocket connection.
     * When non-blank, the game server matches this against existing player
     * connection IDs so the secondary session shares the same player context.
     */
    var playerAlias: String = ""
)

/**
 * Combines critical data about a game session in progress. AccelByte game sessions must be queried as rest api calls
 * so this data class allows us to keep track of it since AccelByte does not provide a method to do so that's reasonable
 * in the sdk.
 */
@Serializable
data class GameSessionStatus(
    var sessionId: String = "",
    var maxPlayers: Int = 4,
    var currentPlayers: Int = 0,
    var players: MutableList<PlayerSessionBundle> = mutableListOf(),
    var isFull: Boolean = false,
    var isStarted: Boolean = false,
    var serverUrl: String = "",
    var gameType: GameType = GameType.MULTIPLAYER,
    var aiOpponentCount: Int = 3,
    var aiOnly: Boolean = false,
    /**
     * When true, the receiving DS must restore the saved snapshot for
     * [resumeUserId] before sending initial sync. The DS discovers the
     * snapshot via the same VFS / cloud-save proxy the local server
     * uses (server.hasRunningGame → server.restoreRunningGame).
     *
     * Default false to preserve the existing fresh-session path.
     * Phase C of the resume-game-architecture plan sets this to true
     * when server-extend builds a single-player match2 ticket for a
     * Resume click.
     */
    var resumeFromVfs: Boolean = false,
    /**
     * AccelByte user id whose saved snapshot the DS must restore.
     * Ignored unless [resumeFromVfs] is true. Empty in the fresh-session
     * path.
     */
    var resumeUserId: String = ""
)