package globals

import structs.Commander
import structs.Player
import structs.World

/**
 * Global object that houses the client side world data for a game map. This is used locally to display
 * stats, and other information about the game world from the server.
 */
object World
{
    var worldData = World()
    var localPlayer = Player()
    var availableCommanders: MutableList<Commander> = mutableListOf()
    var isPlayerTurn = false //Defines if it's the player's turn.
    var canPlayerAct = false //Linked to the above variable. Defines if the player can issue play prompts.
    var activeTurnActor = ""
}
