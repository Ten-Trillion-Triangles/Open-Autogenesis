package interfaces

data class ActorInternals(
    var name: String = "",
    var isNpc: Boolean = false
)

/**
 * Interface class to allow both an npc, and a player to be passed a single variable type.
 */
interface Actor {

    fun getInternals(): ActorInternals = ActorInternals()
}
