package structs

import enums.ObstacleType

/**
 * Defines a border between two territories. Denotes what obstacle is on
 * the border, and what territory is across said border.
 *
 * @param obstacleType Type of obstacle on the border.
 * @param adjacentTerritory Territory that is across this border.
 */
@kotlinx.serialization.Serializable
data class Border(
    var obstacleType: ObstacleType? = null,
    @kotlinx.serialization.Transient var adjacentTerritory: Territory? = null
)
{
    override fun equals(other: Any?): Boolean
    {
        if (this === other) return true
        if (other !is Border) return false

        if (obstacleType != other.obstacleType) return false
        // Use name comparison instead of object reference comparison for stability
        if (adjacentTerritory?.name != other.adjacentTerritory?.name) return false

        return true
    }

    override fun hashCode(): Int
    {
        var result = obstacleType?.hashCode() ?: 0
        // Use name hashCode instead of object reference hashCode for stability
        result = 31 * result + (adjacentTerritory?.name?.hashCode() ?: 0)
        return result
    }
}