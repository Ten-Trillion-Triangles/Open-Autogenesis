package structs

import enums.ObstacleType
import enums.TerritorySize
import enums.TerritoryType
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Defines a map tile in the game or otherwise tangible location that can be captured by a player.
 *
 * @property militaryThreatStat Strategic scoring that describes how threatening the territory is militarily.
 * @property diplomacyThreatStat Diplomatic threat scoring for soft-power/negotiation impact.
 */
@kotlinx.serialization.Serializable
data class Territory(
    var name: String = "",
    var type: TerritoryType = TerritoryType.Land,
    var description: String = "",
    var ruler: String = "",
    var resource: Resource = Resource(),
    var size: TerritorySize = TerritorySize.Medium,
    var xPos: Double = 0.0,
    var yPos: Double = 0.0,
    var pointValue: Int = 2,
    @kotlinx.serialization.Transient var northBorders: MutableList<Border> = mutableListOf(),
    @kotlinx.serialization.Transient var westBorders: MutableList<Border> = mutableListOf(),
    @kotlinx.serialization.Transient var eastBorders: MutableList<Border> = mutableListOf(),
    @kotlinx.serialization.Transient var southBorders: MutableList<Border> = mutableListOf(),
    @kotlinx.serialization.Transient var northEastBorders: MutableList<Border> = mutableListOf(),
    @kotlinx.serialization.Transient var northWestBorders: MutableList<Border> = mutableListOf(),
    @kotlinx.serialization.Transient var southEastBorders: MutableList<Border> = mutableListOf(),
    @kotlinx.serialization.Transient var southWestBorders: MutableList<Border> = mutableListOf(),
    var adjacentTerritoryNames: MutableList<String> = mutableListOf(),
    var northObstacleType: ObstacleType? = null,
    var westObstacleType: ObstacleType? = null,
    var eastObstacleType: ObstacleType? = null,
    var southObstacleType: ObstacleType? = null,
    var northEastObstacleType: ObstacleType? = null,
    var northWestObstacleType: ObstacleType? = null,
    var southEastObstacleType: ObstacleType? = null,
    var southWestObstacleType: ObstacleType? = null,
    var isCaptured: Boolean = false,
    var isDestroyed: Boolean = false,
    var militaryThreatStat: Int = 0,
    var diplomacyThreatStat: Int = 0
)
{
    override fun equals(other: Any?): Boolean
    {
        if (this === other) return true
        if (other !is Territory) return false

        if (name != other.name) return false
        if (type != other.type) return false
        if (description != other.description) return false
        if (ruler != other.ruler) return false
        if (resource != other.resource) return false
        if (size != other.size) return false
        if (xPos != other.xPos) return false
        if (yPos != other.yPos) return false
        if (pointValue != other.pointValue) return false
        if (adjacentTerritoryNames != other.adjacentTerritoryNames) return false
        if (northObstacleType != other.northObstacleType) return false
        if (westObstacleType != other.westObstacleType) return false
        if (eastObstacleType != other.eastObstacleType) return false
        if (southObstacleType != other.southObstacleType) return false
        if (northEastObstacleType != other.northEastObstacleType) return false
        if (northWestObstacleType != other.northWestObstacleType) return false
        if (southEastObstacleType != other.southEastObstacleType) return false
        if (southWestObstacleType != other.southWestObstacleType) return false
        if (isCaptured != other.isCaptured) return false
        if (isDestroyed != other.isDestroyed) return false
        if (militaryThreatStat != other.militaryThreatStat) return false
        if (diplomacyThreatStat != other.diplomacyThreatStat) return false

        return true
    }

    override fun hashCode(): Int
    {
        var result = name.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + ruler.hashCode()
        result = 31 * result + resource.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + xPos.hashCode()
        result = 31 * result + yPos.hashCode()
        result = 31 * result + pointValue
        result = 31 * result + adjacentTerritoryNames.hashCode()
        result = 31 * result + (northObstacleType?.hashCode() ?: 0)
        result = 31 * result + (westObstacleType?.hashCode() ?: 0)
        result = 31 * result + (eastObstacleType?.hashCode() ?: 0)
        result = 31 * result + (southObstacleType?.hashCode() ?: 0)
        result = 31 * result + (northEastObstacleType?.hashCode() ?: 0)
        result = 31 * result + (northWestObstacleType?.hashCode() ?: 0)
        result = 31 * result + (southEastObstacleType?.hashCode() ?: 0)
        result = 31 * result + (southWestObstacleType?.hashCode() ?: 0)
        result = 31 * result + isCaptured.hashCode()
        result = 31 * result + isDestroyed.hashCode()
        result = 31 * result + militaryThreatStat
        result = 31 * result + diplomacyThreatStat
        return result
    }

    /**
     * Fetch all the names of bordering territories and compile them into the string list.
     * This is required to pass as context to the llm's without the problem of recursion caused
     * by attempting to serialize the border data itself.
     *
     * This function should be called by the [World] or [WorldManager] class when a map is loaded.
     */
    fun setTerritoryNamesForLlm()
    {
        /**
         * Condense into one blob of data. We'll need to recursively pick this apart
         * afterward.
         */
        val allBorders = listOf<List<Border>>(
            northBorders,
            westBorders,
            eastBorders,
            southBorders,
            northEastBorders,
            northWestBorders,
            southEastBorders,
            southWestBorders
        )

        //Blob of names to merge into the main struct data.
        val llmFriendlyNames = mutableListOf<String>()

        //First iterate through each list of borders.
        allBorders.forEach { borders ->

            //Then recursively break into the list and pull out each name by string.
            borders.forEach { border ->
                border.adjacentTerritory?.name?.let { llmFriendlyNames.add(it) }
            }
        }

        //Now pushback into our struct data.
        adjacentTerritoryNames.clear()
        adjacentTerritoryNames.addAll(llmFriendlyNames.distinct())
        Logger.debug(LogCategory.GENERAL, "Territory.setTerritoryNamesForLlm: ${name} adjacency list=${adjacentTerritoryNames.size}")
    }
}