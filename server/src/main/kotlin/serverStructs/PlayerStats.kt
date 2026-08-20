package serverStructs

import kotlinx.serialization.Serializable
import structs.Player

/**
 * Defines player stats that are relevant to the game server for managing the state of the game.
 * This includes various upkeep like any uuid, session data, connection status, weather an npc
 * has replaced the player etc.
 *
 * @param playerData Object that houses in game data about the player. This includes their commander, stats,
 * what territories and resources they own, and player history.
 * @param accelByteUserId User id for AccelByte services. Required to address most user queries outside game
 * records and game level admin actions.
 * @param playerID Id that maps a player to the web socket rpc system. Same thing as the player index variable
 * from Revolution. Any RPC's require this id if we're sending an rpc from the server down the client.
 * @param isConnected If true, the player is still connected, and sending active heartbeat signals to the server.
 * This value is always false prior the game starting, and only becomes true at the start of the game, or connecting
 * back mid-game. When this is false, and too much time has passed, and LLM opponent will take over the player's turn
 * and game until they return.
 * @param isControlledByNpc True when an LLM has taken over this player's role, or if there was never a player here
 * to begin with and an LLM had to be invoked to fill that spot.
 * @param turnActive Determines if this player is the current turn player, that's allowed to take an action.
 *
 */
@Serializable
data class PlayerStats(
    var playerData : Player = Player(),
    var accelByteUserId : String = "",
    var playerID : String = "",
    var isConnected : Boolean = true,
    var isControlledByNpc : Boolean = false,
    var turnActive : Boolean = false,
)