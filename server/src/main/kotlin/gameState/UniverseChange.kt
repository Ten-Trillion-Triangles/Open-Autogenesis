package gameState

import kotlinx.serialization.Serializable

@Serializable
data class UniverseChange(
    val changeType: ChangeType,
    val before: String = "",
    val after: String = "",
    val details: String = "",
    val affectedTerritories: List<String> = emptyList()
)

@Serializable
enum class ChangeType
{
    TERRAIN_REMOVED,
    PHYSICS_ADDED,
    MAGIC_DISCOVERED,
    WORLD_RULE_UPDATED,
    UNKNOWN
}