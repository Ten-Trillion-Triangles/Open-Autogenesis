package gameState

import kotlinx.serialization.Serializable

@Serializable
enum class ResourceAction
{
    GRANT,
    DESTROY
}

@Serializable
data class ResourceAdjustment(
    var resourceName: String = "",
    var action: ResourceAction = ResourceAction.GRANT,
    var description: String = "",
    var abilities: String = "",
    var depletable: Boolean = false,
    var destructible: Boolean = false
)