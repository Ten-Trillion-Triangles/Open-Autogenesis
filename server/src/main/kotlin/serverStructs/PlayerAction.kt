package serverStructs

import structs.Player

@kotlinx.serialization.Serializable
data class PlayerAction(
    var player: Player = Player(),
    var actionDescription: String = ""
)
