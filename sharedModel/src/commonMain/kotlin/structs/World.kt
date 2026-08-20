package structs

import enums.TerritoryType
import enums.ObstacleType
import enums.CommanderType
import kotlinx.coroutines.sync.Mutex
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.audio.AudioTracks

/**
 * Defines the game world. This houses every aspect of this game's universe.
 *
 */
@kotlinx.serialization.Serializable
data class World(
    var name: String = "",
    var storyScenario: String = "",
    var points: Int = 0,
    var karmaPoints: Int = 0, //A nemesis will spawn once we reach 100 karma points.
    var conflictLevel: Int = 0,
    var actOfGodPoints: Int = 0,
    var roundNumber: Int = 1,
    var mapTiles: MutableList<Territory> = mutableListOf(),
    var activePlayers: MutableList<Player> = mutableListOf(),
    var npc: MutableList<Npc> = mutableListOf(),
    var turnOrder: MutableList<String> = mutableListOf(),
    var worldRules: MutableList<String> = mutableListOf(),
    var destroyedTerritories: MutableList<String> = mutableListOf(),
    var activeTurnActor: String = "", //Defines the actor (Player or npc) who's turn it currently is.
    /**
     * Audio track catalog loaded at server startup from the bundled
     * `audio/audio-tracks.json` resource (see
     * [org.ttt.autogenesis.server.audio.AudioTracksResourceLoader]). The
     * default-empty value keeps the world well-formed before the loader
     * has run; the loader replaces the field with the parsed payload as
     * soon as the map pack is unpacked in [gameInit.GameInit.defineGameRules].
     */
    var audioTracks: AudioTracks = AudioTracks()
)
{
    /**
     * Critical safety mutex to lock access to the [World] class.
     */
    @kotlinx.serialization.Transient
    val mutex = Mutex()

    /**
     * Finds an NPC by name using case-insensitive matching.
     *
     * @param name The NPC name to search for
     * @return The matching NPC or null if not found
     */
    fun findNpcByName(name: String): Npc?
    {
        val normalizedName = name.trim().lowercase()
        return npc.firstOrNull { it.name.trim().lowercase() == normalizedName }
    }

    /**
     * Finds a player by name using case-insensitive matching.
     *
     * @param name The player name to search for
     * @return The matching Player or null if not found
     */
    fun findPlayerByName(name: String): Player?
    {
        val normalizedName = name.trim().lowercase()
        return activePlayers.firstOrNull { it.name.trim().lowercase() == normalizedName }
    }

    /**
     * Checks if a player has any territories adjacent to the given territory.
     *
     * @param territory The territory to check adjacency against
     * @param player The player whose territories to check
     * @return true if the player has any adjacent territories, false otherwise
     */
    fun hasAdjacentTerritory(territory: Territory, player: Player): Boolean
    {
        val playerTerritories = mutableListOf<Territory>().apply {
            add(player.startingTile)
            addAll(player.capturedTerritory)
        }

        Logger.debug(LogCategory.GENERAL, "World.hasAdjacentTerritory: checking ${playerTerritories.size} tiles for player '${player.name}' against '${territory.name}'")

        val adjacencyFound = playerTerritories.any { playerTerritory ->
            val borderLists = listOf(
                "north" to playerTerritory.northBorders,
                "south" to playerTerritory.southBorders,
                "east" to playerTerritory.eastBorders,
                "west" to playerTerritory.westBorders,
                "northEast" to playerTerritory.northEastBorders,
                "northWest" to playerTerritory.northWestBorders,
                "southEast" to playerTerritory.southEastBorders,
                "southWest" to playerTerritory.southWestBorders
            )

            val borderMatch = borderLists.firstNotNullOfOrNull { (direction, borderList) ->
                borderList.firstOrNull { border ->
                    // Use name comparison instead of object reference comparison
                    border.adjacentTerritory?.name == territory.name
                }?.let { direction to it }
            }

            if(borderMatch != null)
            {
                val (direction, _) = borderMatch
                Logger.debug(LogCategory.GENERAL, "World.hasAdjacentTerritory: match found via ${playerTerritory.name} on $direction border")
                true
            }
            else
            {
                val borderCounts = borderLists.joinToString(", ") { "${it.first}=${it.second.size}" }
                Logger.debug(LogCategory.GENERAL, "World.hasAdjacentTerritory: no match on ${playerTerritory.name} ($borderCounts)")
                false
            }
        }

        Logger.debug(LogCategory.GENERAL, "World.hasAdjacentTerritory: adjacencyFound=$adjacencyFound for '${player.name}' vs '${territory.name}'")
        return adjacencyFound
    }

    /**
     * Checks if two water territories can be connected by a player, considering ownership of territories between them.
     *
     * @param territoryA The first territory to check
     * @param territoryB The target territory to check
     * @param player The player whose ownership to validate
     * @return true if both are water territories and no unowned territory blocks the connection
     */
    fun canConnectWaterTerritories(territoryA: Territory, territoryB: Territory, player: Player): Boolean
    {
        // Check if both territories are water type (anything other than Land)
        if(territoryA.type == TerritoryType.Land || territoryB.type == TerritoryType.Land)
        {
            return false
        }

        // Find territories that are adjacent to both A and B (territories "between" them)
        val territoriesBetween = mapTiles.filter { territory ->
            isAdjacentTo(territory, territoryA) && isAdjacentTo(territory, territoryB)
        }

        // If no territories between A and B, connection is clear
        if(territoriesBetween.isEmpty())
        {
            return true
        }

        // Check if player owns all territories between A and B
        return territoriesBetween.all { territory ->
            territory.ruler.equals(player.name, ignoreCase = true) ||
            territory == player.startingTile ||
            player.capturedTerritory.contains(territory)
        }
    }

    /**
     * Helper function to check if two territories are adjacent to each other.
     */
    fun isAdjacentTo(territory1: Territory, territory2: Territory): Boolean
    {
        return listOf(
            territory1.northBorders,
            territory1.southBorders,
            territory1.eastBorders,
            territory1.westBorders,
            territory1.northEastBorders,
            territory1.northWestBorders,
            territory1.southEastBorders,
            territory1.southWestBorders
        ).flatten().any { border ->
            border.adjacentTerritory?.name == territory2.name
        }
    }

    /**
     * Helper function to check if there are obstacles between two territories.
     *
     * @param territoryA The first territory
     * @param territoryB The second territory
     * @return true if there are obstacles blocking the path between the territories
     */
    fun hasObstacleBetween(territoryA: Territory, territoryB: Territory): Boolean
    {
        // Check borders from A to B for obstacles
        val bordersFromA = listOf(
            territoryA.northBorders,
            territoryA.southBorders,
            territoryA.eastBorders,
            territoryA.westBorders,
            territoryA.northEastBorders,
            territoryA.northWestBorders,
            territoryA.southEastBorders,
            territoryA.southWestBorders
        ).flatten()

        return bordersFromA.any { border ->
            border.adjacentTerritory?.name == territoryB.name && border.obstacleType != null
        }
    }

    /**
     * Helper function to check if there are water obstacles between two territories.
     *
     * @param territoryA The first territory
     * @param territoryB The second territory
     * @return true if there are water obstacles (River or Ocean) blocking the path between the territories
     */
    fun hasWaterObstacleBetween(territoryA: Territory, territoryB: Territory): Boolean
    {
        // Check borders from A to B for water obstacles
        val bordersFromA = listOf(
            territoryA.northBorders,
            territoryA.southBorders,
            territoryA.eastBorders,
            territoryA.westBorders,
            territoryA.northEastBorders,
            territoryA.northWestBorders,
            territoryA.southEastBorders,
            territoryA.southWestBorders
        ).flatten()

        return bordersFromA.any { border ->
            border.adjacentTerritory?.name == territoryB.name && 
            (border.obstacleType == ObstacleType.River || border.obstacleType == ObstacleType.Ocean)
        }
    }

    /**
     * Gets the obstacle type between two adjacent territories.
     *
     * @param from The starting territory
     * @param to The adjacent territory
     * @return The ObstacleType if territories are adjacent and have an obstacle, null otherwise
     */
    private fun getObstacleBetween(from: Territory, to: Territory): ObstacleType?
    {
        val allBorders = listOf(
            from.northBorders, from.southBorders, from.eastBorders, from.westBorders,
            from.northEastBorders, from.northWestBorders, from.southEastBorders, from.southWestBorders
        ).flatten()

        return allBorders.firstOrNull { it.adjacentTerritory?.name == to.name }?.obstacleType
    }

    /**
     * Calculates the shortest path distance between two territories and counts obstacles encountered.
     * Uses BFS to find the path with the fewest territories, then counts river, mountain, and ocean
     * obstacles crossed along that path.
     *
     * @param territoryA The starting territory
     * @param territoryB The destination territory
     * @return TerritoryDistance containing the distance and obstacle counts
     */
    fun getTerritoryDistance(territoryA: Territory, territoryB: Territory): TerritoryDistance
    {
        if (territoryA == territoryB)
        {
            return TerritoryDistance(distance = 0, rivers = 0, mountains = 0, oceans = 0)
        }

        val queue = mutableListOf(territoryA)
        val visited = mutableSetOf(territoryA)
        val parent = mutableMapOf<Territory, Territory>()

        while (queue.isNotEmpty())
        {
            val current = queue.removeAt(0)

            if (current == territoryB)
            {
                break
            }

            val neighbors = listOf(
                current.northBorders,
                current.southBorders,
                current.eastBorders,
                current.westBorders,
                current.northEastBorders,
                current.northWestBorders,
                current.southEastBorders,
                current.southWestBorders
            ).flatten().mapNotNull { it.adjacentTerritory }

            for (neighbor in neighbors)
            {
                if (neighbor !in visited)
                {
                    visited.add(neighbor)
                    parent[neighbor] = current
                    queue.add(neighbor)
                }
            }
        }

        val path = mutableListOf<Territory>()
        var current = territoryB
        while (current != territoryA)
        {
            path.add(current)
            current = parent[current] ?: break
        }
        path.add(territoryA)
        path.reverse()

        var rivers = 0
        var mountains = 0
        var oceans = 0

        for (i in 0 until path.size - 1)
        {
            when (getObstacleBetween(path[i], path[i + 1]))
            {
                ObstacleType.River -> rivers++
                ObstacleType.Mountain -> mountains++
                ObstacleType.Ocean -> oceans++
                null -> {}
            }
        }

        return TerritoryDistance(
            distance = path.size - 1,
            rivers = rivers,
            mountains = mountains,
            oceans = oceans
        )
    }

    /**
     * Returns the combat modifier for a commander type when crossing a specific terrain type.
     * Applies +5 for favorable terrain/obstacles and -5 for unfavorable ones, capped at +40 to -40.
     *
     * Rules:
     * - Land: Penalized crossing any obstacle (-5 per obstacle)
     * - Aquatic: Favored crossing rivers/oceans (+5 each), penalized crossing mountains (-5)
     * - Flying: Favored crossing any obstacle (+5 per obstacle)
     *
     * @param player The player whose commander type determines advantages
     * @param distance The TerritoryDistance containing obstacle counts
     * @return Modifier value between -40 and +40
     */
    fun calculateTerrainAdvantage(player: Player, distance: TerritoryDistance): Int
    {
        var modifier = 0

        when (player.commanderType)
        {
            CommanderType.Land -> {
                modifier -= (distance.rivers + distance.mountains + distance.oceans) * 5
            }
            CommanderType.Aquatic -> {
                modifier += (distance.rivers + distance.oceans) * 5
                modifier -= distance.mountains * 5
            }
            CommanderType.Flying -> {
                modifier += (distance.rivers + distance.mountains + distance.oceans) * 5
            }
        }

        return modifier.coerceIn(-40, 40)
    }

    /**
     * Returns the combat modifier for a commander type when crossing a specific terrain type.
     * Applies to Desert and Void terrain - other terrain types return 0.
     *
     * Modifier values:
     * - Desert: Land→-20, Aquatic→-40, Aerial→0
     * - Void: Land→-40, Aquatic→-40, Aerial→+10
     * - Other types: 0
     *
     * @param commanderType The type of commander (Land, Aquatic, Flying)
     * @param terrainType The terrain type to check
     * @return The modifier value for the commander-terrain combination
     */
    fun getTerrainTypeModifier(commanderType: CommanderType, terrainType: TerritoryType): Int
    {
        return when (terrainType)
        {
            TerritoryType.Desert -> when (commanderType)
            {
                CommanderType.Land -> -20
                CommanderType.Aquatic -> -40
                CommanderType.Flying -> 0
            }
            TerritoryType.Void -> when (commanderType)
            {
                CommanderType.Land -> -40
                CommanderType.Aquatic -> -40
                CommanderType.Flying -> 10
            }
            else -> 0
        }
    }

    /**
     * Calculates the combined modifier for long-range military campaigns.
     * Combines terrain advantage from obstacles with distance penalty.
     *
     * @param player The player whose commander type determines terrain advantages
     * @param territoryA The starting territory
     * @param territoryB The target territory
     * @return Combined modifier value between -40 and +40
     */
    fun calculateLongRangeModifier(player: Player, territoryA: Territory, territoryB: Territory): Int
    {
        val distance = getTerritoryDistance(territoryA, territoryB)
        val terrainAdvantage = calculateTerrainAdvantage(player, distance)
        val distancePenalty = distance.distance * 5

        // Calculate terrain type modifier for Desert/Void tiles in path (excluding start and end)
        var terrainTypeModifier = 0
        val path = getPathBetween(territoryA, territoryB)
        // Path includes start and end, so we skip first and last
        for (i in 1 until path.size - 1)
        {
            terrainTypeModifier += getTerrainTypeModifier(player.commanderType, path[i].type)
        }

        return (terrainAdvantage - distancePenalty + terrainTypeModifier).coerceIn(-40, 40)
    }

    /**
     * Gets the path between two territories using BFS.
     *
     * @param from The starting territory
     * @param to The destination territory
     * @return List of territories representing the path (including from and to)
     */
    private fun getPathBetween(from: Territory, to: Territory): List<Territory>
    {
        if (from == to)
        {
            return listOf(from)
        }

        val queue = mutableListOf(from)
        val visited = mutableSetOf(from)
        val parent = mutableMapOf<Territory, Territory>()

        while (queue.isNotEmpty())
        {
            val current = queue.removeAt(0)

            if (current == to)
            {
                break
            }

            val neighbors = listOf(
                current.northBorders,
                current.southBorders,
                current.eastBorders,
                current.westBorders,
                current.northEastBorders,
                current.northWestBorders,
                current.southEastBorders,
                current.southWestBorders
            ).flatten().mapNotNull { it.adjacentTerritory }

            for (neighbor in neighbors)
            {
                if (neighbor !in visited)
                {
                    visited.add(neighbor)
                    parent[neighbor] = current
                    queue.add(neighbor)
                }
            }
        }

        val path = mutableListOf<Territory>()
        var current = to
        while (current != from)
        {
            path.add(current)
            current = parent[current] ?: break
        }
        path.add(from)
        path.reverse()
        return path
    }
}