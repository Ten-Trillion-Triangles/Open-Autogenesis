package structs

import enums.CommanderTrait
import enums.CommanderType

/**
 * Data class that defines a commander. Commanders are characters controlled by players that act as their "king" in the
 * game. Each commander has a type, a personality trait, name, and description that defines what powers they have.
 */
@kotlinx.serialization.Serializable
data class Commander(
    var name: String =  "",
    var description: String = "",
    var empire: String = "",
    var type: CommanderType = CommanderType.Land,
    var trait: CommanderTrait = CommanderTrait.Balanced
    )