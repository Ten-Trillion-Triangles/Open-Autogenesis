package structs

import enums.TerritoryType
import kotlin.math.sqrt
import kotlin.random.Random
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

@kotlinx.serialization.Serializable
data class FourCornerTerritories(
    val nw: Territory,
    val ne: Territory,
    val sw: Territory,
    val se: Territory
)

data class MapBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    val centerX: Double,
    val centerY: Double
)

enum class Corner
{
    NW, NE, SW, SE
}

/**
 * Collects every directly adjacent territory to the provided tile.
 *
 * This helper traverses all border lists (including diagonals) and returns a deduplicated list
 * so callers can reason about reachability or threat propagation without repeating the same tile.
 */
fun getAdjacentTerritories(territory: Territory): List<Territory>
{
    val adjacent = mutableListOf<Territory>()
    
    territory.northBorders.forEach { it.adjacentTerritory?.let { t -> adjacent.add(t) } }
    territory.southBorders.forEach { it.adjacentTerritory?.let { t -> adjacent.add(t) } }
    territory.eastBorders.forEach { it.adjacentTerritory?.let { t -> adjacent.add(t) } }
    territory.westBorders.forEach { it.adjacentTerritory?.let { t -> adjacent.add(t) } }
    territory.northEastBorders.forEach { it.adjacentTerritory?.let { t -> adjacent.add(t) } }
    territory.northWestBorders.forEach { it.adjacentTerritory?.let { t -> adjacent.add(t) } }
    territory.southEastBorders.forEach { it.adjacentTerritory?.let { t -> adjacent.add(t) } }
    territory.southWestBorders.forEach { it.adjacentTerritory?.let { t -> adjacent.add(t) } }
    
    return adjacent.distinct()
}

/**
 * Computes the spatial bounding box around a list of territories.
 +
 * The bounds are used to locate the world center so we can map each quadrant to a player when assigning Corners.
 */
fun calculateBounds(territories: List<Territory>): MapBounds
{
    if (territories.isEmpty()) return MapBounds(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    
    val minX = territories.minOf { it.xPos }
    val maxX = territories.maxOf { it.xPos }
    val minY = territories.minOf { it.yPos }
    val maxY = territories.maxOf { it.yPos }
    val centerX = (minX + maxX) / 2.0
    val centerY = (minY + maxY) / 2.0
    
    return MapBounds(minX, maxX, minY, maxY, centerX, centerY)
}

/**
 * Computes the traditional Euclidean distance between two coordinate pairs.
 *
 * @return straight-line distance. Used when choosing the “closest” tile within a quadrant.
 */
fun euclideanDistance(x1: Double, y1: Double, x2: Double, y2: Double): Double
{
    val dx = x2 - x1
    val dy = y2 - y1
    return sqrt(dx * dx + dy * dy)
}

/**
 * Determines the minimum number of border hops between `from` and `to`.
 *
 * This BFS-based graph search walks adjacency relationships so we understand connectivity independent
 * of raw coordinates; returns `Int.MAX_VALUE` if the tiles are disconnected.
 */
fun calculateGraphDistance(from: Territory, to: Territory): Int
{
    if (from == to) return 0
    
    val queue = ArrayDeque<Pair<Territory, Int>>()
    val visited = mutableSetOf<Territory>()
    
    queue.add(Pair(from, 0))
    visited.add(from)
    
    while (queue.isNotEmpty())
    {
        val (current, distance) = queue.removeFirst()
        
        val adjacent = getAdjacentTerritories(current)
        for (neighbor in adjacent)
        {
            if (neighbor == to) return distance + 1
            
            if (neighbor !in visited)
            {
                visited.add(neighbor)
                queue.add(Pair(neighbor, distance + 1))
            }
        }
    }
    
    return Int.MAX_VALUE
}

/**
 * Converts a territory type to a priority score used when selecting corner tiles (higher = more important).
 *
 * We prefer coastline/island tiles over land when filling sparse corners so the map feels varied when multiple
 * choices share the same quadrant.
 */
fun getTypePriority(type: TerritoryType): Int
{
    return when(type)
    {
        TerritoryType.Coastline -> 0
        TerritoryType.Island -> 1
        TerritoryType.Underwater -> 2
        TerritoryType.Land -> 3
        TerritoryType.Desert -> 4
        TerritoryType.Void -> 5
    }
}

/**
 * Finds the “best” territory inside a quadrant defined by `corner` and the world center.
 *
 * It filters down to quadrant candidates and picks the one with the highest `getTypePriority`
 * before breaking ties by proximity to the quadrant center via Euclidean distance.
 */
fun findCornerTerritory(territories: List<Territory>, corner: Corner, centerX: Double, centerY: Double): Territory?
{
    val inQuadrant = territories.filter { territory ->
        when(corner)
        {
            Corner.NW -> territory.xPos < centerX && territory.yPos < centerY
            Corner.NE -> territory.xPos >= centerX && territory.yPos < centerY
            Corner.SW -> territory.xPos < centerX && territory.yPos >= centerY
            Corner.SE -> territory.xPos >= centerX && territory.yPos >= centerY
        }
    }
    
    if (inQuadrant.isEmpty()) return null
    
    return inQuadrant.maxWithOrNull(compareBy(
        { -getTypePriority(it.type) },
        { euclideanDistance(it.xPos, it.yPos, centerX, centerY) }
    ))
}

/**
 * Locates the four extremal territories (NW, NE, SW, SE).
 *
 * Throws if any quadrant lacks a tile so callers can rely on the returned corners for player assignment.
 */
fun findFourCornerTerritories(world: World): FourCornerTerritories
{
    val territories = world.mapTiles
    if (territories.isEmpty()) throw IllegalArgumentException("World has no territories")
    
    val bounds = calculateBounds(territories)
    
    val nw = findCornerTerritory(territories, Corner.NW, bounds.centerX, bounds.centerY)
        ?: throw IllegalStateException("No territory found in NW corner")
    val ne = findCornerTerritory(territories, Corner.NE, bounds.centerX, bounds.centerY)
        ?: throw IllegalStateException("No territory found in NE corner")
    val sw = findCornerTerritory(territories, Corner.SW, bounds.centerX, bounds.centerY)
        ?: throw IllegalStateException("No territory found in SW corner")
    val se = findCornerTerritory(territories, Corner.SE, bounds.centerX, bounds.centerY)
        ?: throw IllegalStateException("No territory found in SE corner")
    
    return FourCornerTerritories(nw, ne, sw, se)
}

/**
 * Stores the four corner territories and assigns them to the active players (2, 3, or 4).
 *
 * For 2 players, it prioritizes opposite corners (NW/SE or NE/SW) to ensure a balanced start.
 * For 3 players, it picks 3 random corners (Triangle).
 * For 4 players, it fills all corners.
 */
fun assignCornerTerritoriesToPlayers(world: World)
{
    val players = world.activePlayers
    if (players.isEmpty()) throw IllegalArgumentException("World has no players")
    if (players.size > 4) throw IllegalArgumentException("Cannot assign corners to more than 4 players")
    
    val corners = findFourCornerTerritories(world)
    val cornerPool = when(players.size)
    {
        2 -> {
            // Pick a random pair of opposite corners
            if(Random.nextBoolean()) listOf(corners.nw, corners.se) else listOf(corners.ne, corners.sw)
        }
        3 -> {
            // Pick 3 random corners
            listOf(corners.nw, corners.ne, corners.sw, corners.se).shuffled().take(3)
        }
        else -> {
            // Fill all corners (or just the one if 1P)
            listOf(corners.nw, corners.ne, corners.sw, corners.se).shuffled()
        }
    }
    
    players.forEachIndexed { index, player ->
        val territory = cornerPool[index]
        territory.ruler = player.name
        territory.isCaptured = true
        player.startingTile = territory
        player.capturedTerritory.clear()
        player.capturedTerritory.add(territory)
        Logger.info(LogCategory.GENERAL, "Assigned player ${player.name} to corner tile ${territory.name}")
    }
}

/**
 * Determines which corner a territory resides in using the same quadrant rules that power
 * [findCornerTerritory].
 *
 * Returns null when the tile sits on the exact center or outside any defined quadrant.
 */
fun getCornerForTerritory(territory: Territory, bounds: MapBounds): Corner?
{
    if(bounds.minX == bounds.maxX && bounds.minY == bounds.maxY)
    {
        return null
    }

    if(territory.xPos == bounds.centerX && territory.yPos == bounds.centerY)
    {
        return null
    }

    return when
    {
        territory.xPos < bounds.centerX && territory.yPos >= bounds.centerY -> Corner.SW
        territory.xPos >= bounds.centerX && territory.yPos >= bounds.centerY -> Corner.SE
        territory.xPos >= bounds.centerX && territory.yPos < bounds.centerY -> Corner.NE
        territory.xPos < bounds.centerX && territory.yPos < bounds.centerY -> Corner.NW
        else -> null
    }
}

/**
 * Orders the provided players starting from the southwest corner and moving clockwise.
 * Requires each player to already own a corner-aligned [startingTile].
 */
fun determineStartingTurnOrder(world: World, players: List<Player>): List<Player>
{
    require(players.size in 2..4) { "determineStartingTurnOrder requires between 2 and 4 players" }
    require(world.mapTiles.isNotEmpty()) { "World must define map tiles before turn order can be determined" }

    val bounds = calculateBounds(world.mapTiles)
    val orderedCorners = listOf(Corner.SW, Corner.SE, Corner.NE, Corner.NW)
    val assignments = mutableMapOf<Corner, Player>()

    Logger.info(
        LogCategory.GENERAL,
        "Determining starting turn order for ${players.map { it.name }} with bounds center (${bounds.centerX}, ${bounds.centerY})"
    )

    players.forEach { player ->
        val corner = getCornerForTerritory(player.startingTile, bounds)
        if(corner == null)
        {
            Logger.warn(
                LogCategory.GENERAL,
                "Player ${player.name} has starting tile (${player.startingTile.name}) that does not resolve to a quadrant corner."
            )
            throw IllegalStateException("Player ${player.name} is not located in a recognized corner")
        }

        Logger.debug(LogCategory.GENERAL, "Player ${player.name} assigned to corner $corner")

        require(assignments.containsKey(corner).not()) { "Multiple players assigned to the $corner corner" }

        assignments[corner] = player
    }

    val orderedPlayers = orderedCorners.mapNotNull { corner ->
        assignments[corner]
    }

    Logger.info(
        LogCategory.GENERAL,
        "Resolved starting turn order: ${orderedPlayers.map { it.name }}"
    )

    return orderedPlayers
}

/**
 * Randomly assigns each player to a distinct starting tile using the pool of available map tiles.
 */
fun assignRandomStartingTiles(world: World, players: List<Player>, random: Random = Random.Default)
{
    require(players.isNotEmpty()) { "assignRandomStartingTiles requires at least one player" }
    require(world.mapTiles.isNotEmpty()) { "World must define map tiles before random assignment" }

    val availableTerritories = world.mapTiles.filter { !it.isDestroyed }.toMutableList()
    require(availableTerritories.size >= players.size) { "Not enough valid tiles for ${players.size} players" }

    availableTerritories.shuffle(random)
    Logger.info(
        LogCategory.GENERAL,
        "Randomizing ${players.size} players across ${availableTerritories.size} available tiles"
    )

    players.forEachIndexed { index, player ->
        val selectedTile = availableTerritories[index]

        Logger.debug(
            LogCategory.GENERAL,
            "Assigning player ${player.name} to random tile ${selectedTile.name} (${selectedTile.xPos}, ${selectedTile.yPos})"
        )

        player.capturedTerritory.clear()
        selectedTile.ruler = player.name
        selectedTile.isCaptured = true
        player.startingTile = selectedTile
        player.capturedTerritory.add(selectedTile)
    }

    Logger.info(
        LogCategory.GENERAL,
        "Random assignment complete: ${players.map { it.name to it.startingTile.name }}"
    )
}

/**
 * Randomly grants each player a second tile that is not adjacent to that player's own [Player.startingTile].
 *
 * The candidate pool is every tile that is still unowned and not destroyed, which naturally excludes other
 * players' starting tiles (they are already captured). For each player in input order we filter that pool
 * against [getAdjacentTerritories] of the starting tile; if at least one non-adjacent candidate exists we
 * pick one uniformly at random, otherwise we relax the adjacency constraint and pick any remaining tile so
 * the rule never silently fails. The granted tile is appended to [Player.capturedTerritory] and marked
 * captured on the [Territory] side, mirroring how the primary starting tile is recorded. When even the
 * relaxed pool is empty (e.g. a one-tile map for one player) the player is skipped with a warning and
 * no tile is double-granted because chosen tiles are removed from the working pool between iterations.
 */
fun assignSecondaryStartingTiles(world: World, players: List<Player>, random: Random = Random.Default)
{
    require(players.isNotEmpty()) { "assignSecondaryStartingTiles requires at least one player" }
    require(world.mapTiles.isNotEmpty()) { "World must define map tiles before secondary assignment" }

    val available = world.mapTiles.filter { !it.isDestroyed && !it.isCaptured }.toMutableList()

    Logger.info(
        LogCategory.GENERAL,
        "Granting secondary starting tiles to ${players.size} players across ${available.size} available tiles"
    )

    players.forEach { player ->
        // Defense in depth: the starting tile itself plus every directly adjacent tile is excluded.
        // In the normal flow the starting tile is already captured (filtered by `!it.isCaptured`),
        // but the explicit check keeps the function correct when it is called in isolation.
        val excluded = (getAdjacentTerritories(player.startingTile) + player.startingTile).toSet()
        val candidates = available.filter { it !in excluded }

        val selected = when
        {
            candidates.isNotEmpty() -> candidates.random(random).also {
                Logger.info(
                    LogCategory.GENERAL,
                    "Assigned secondary tile ${it.name} (${it.xPos}, ${it.yPos}) to ${player.name}"
                )
            }
            available.isNotEmpty() -> available.random(random).also {
                Logger.warn(
                    LogCategory.GENERAL,
                    "No non-adjacent tile available for ${player.name}; falling back to adjacent tile ${it.name}"
                )
            }
            else ->
            {
                Logger.warn(
                    LogCategory.GENERAL,
                    "No unowned tile available for ${player.name}; skipping secondary tile grant"
                )
                return@forEach
            }
        }

        selected.ruler = player.name
        selected.isCaptured = true
        player.capturedTerritory.add(selected)
        available.remove(selected)
    }

    Logger.info(
        LogCategory.GENERAL,
        "Secondary tile assignment complete: ${players.map { p -> p.name to (p.capturedTerritory.lastOrNull()?.name ?: "<none>") }}"
    )
}
