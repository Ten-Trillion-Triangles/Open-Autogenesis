package structs

import enums.ResourceType

/**
 * Defines a resource a player can have. Resources can be used by players to boost the chances of their play
 * or affect the outcome of a play.
 *
 * @param name Defines the name of the resource.
 * @param type Denotes that type of resource this is.
 * @param depletable This resource has some kind of theoretical quantity that could be fully depleted.
 * In some cases the supply is renewable however so this isn't an absolute factor. However, the agent
 * can decide that a character has expended their entire supply of the resource.
 * @param destructible If true, the resource could be destroyed or killed in a permanent way.
 * @param description Describes what the resource is to the agent and players.
 * @param abilities Describes what the resource can and can't be used for, any limits it has,
 * and how it can be used. Is used by the agents to affect and impact plays.
 */
@kotlinx.serialization.Serializable
data class Resource(
    var name: String = "",
    var type: ResourceType = ResourceType.Military,
    var depletable : Boolean = false,
    var destructible: Boolean = false,
    var isDestroyedOrDepleted: Boolean = false,
    var description: String = "",
    var abilities: String = ""
)
