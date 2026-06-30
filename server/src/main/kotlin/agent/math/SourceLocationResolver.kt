package agent.math

import agent.builders.validateAction.PathValidationResult
import agent.builders.validateAction.SourceLocationResult
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.Player
import structs.Territory
import structs.World

/**
 * Analyzes player action text to detect explicit source territories, or optimizes
 * source selection when not specified.
 *
 * @param player The player taking the action
 * @param actionText The player's action text
 * @param targetTerritories The target territories from ActionTargetTypeObj
 * @param world The game world state
 * @return SourceLocationResult with resolved/optimized source territories
 */
fun detectSourceLocation(
    player: Player,
    actionText: String,
    targetTerritories: List<String>,
    world: World
): SourceLocationResult {
    // Step 1: Check if player explicitly specified source territories
    val explicitSources = parseExplicitSourceLocations(actionText, player, world)

    if (explicitSources.isNotEmpty()) {
        // Validate explicit sources are owned by player
        val playerOwned = getPlayerOwnedTerritories(player)
        val playerOwnedNames = playerOwned.map { it.name.lowercase() }

        val validatedSources = explicitSources.filter { sourceName ->
            playerOwnedNames.any { it.equals(sourceName.lowercase(), ignoreCase = true) }
        }

        if (validatedSources.isNotEmpty()) {
            return SourceLocationResult(
                sourceTerritories = validatedSources,
                wasSpecified = true,
                wasOptimized = false,
                optimizationReason = "Player explicitly specified acting from: ${validatedSources.joinToString()}"
            )
        }
    }

    // Step 2: Optimize source selection if not specified
    return optimizeSourceLocation(player, targetTerritories, world)
}

/**
 * Parses action text to find explicit source location references.
 * Handles phrases like "from my northern territories", "from The Citadel", etc.
 */
private fun parseExplicitSourceLocations(
    actionText: String,
    player: Player,
    world: World
): List<String> {
    val playerOwnedTerritories = getPlayerOwnedTerritories(player)
    val playerOwnedNames = playerOwnedTerritories.map { it.name.lowercase() }.toSet()

    val detectedSources = mutableListOf<String>()
    val lowerAction = actionText.lowercase()

    // Check for explicit territory mentions that player owns
    playerOwnedTerritories.forEach { territory ->
        val territoryLower = territory.name.lowercase()
        if (lowerAction.contains("from $territoryLower") ||
            lowerAction.contains("from my $territoryLower") ||
            lowerAction.contains("originating from $territoryLower") ||
            lowerAction.contains("launching from $territoryLower") ||
            lowerAction.contains("striking from $territoryLower") ||
            lowerAction.contains("attacking from $territoryLower") ||
            lowerAction.contains("moving from $territoryLower") ||
            lowerAction.contains("advancing from $territoryLower")) {
            detectedSources.add(territory.name)
        }
    }

    // Check for directional/regional groupings
    val avgY = playerOwnedTerritories.map { it.yPos }.average()
    val avgX = playerOwnedTerritories.map { it.xPos }.average()

    val directionalPatterns = mapOf(
        "northern" to playerOwnedTerritories.filter { it.yPos > avgY },
        "southern" to playerOwnedTerritories.filter { it.yPos < avgY },
        "eastern" to playerOwnedTerritories.filter { it.xPos > avgX },
        "western" to playerOwnedTerritories.filter { it.xPos < avgX }
    )

    directionalPatterns.forEach { (direction, territories) ->
        if (lowerAction.contains("my $direction territories") ||
            lowerAction.contains("$direction territories") ||
            lowerAction.contains("my $direction holdings") ||
            lowerAction.contains("$direction holdings") ||
            lowerAction.contains("my $direction frontier")) {
            detectedSources.addAll(territories.map { it.name })
        }
    }

    return detectedSources.distinct()
}

/**
 * Optimizes source territory selection when player doesn't specify.
 * Criteria: minimize distance, avoid hostile territory crossing for Military actions.
 */
private fun optimizeSourceLocation(
    player: Player,
    targetTerritories: List<String>,
    world: World
): SourceLocationResult {
    val playerOwnedTerritories = getPlayerOwnedTerritories(player)
    if (playerOwnedTerritories.isEmpty()) {
        return SourceLocationResult(
            sourceTerritories = emptyList(),
            wasSpecified = false,
            wasOptimized = false,
            optimizationReason = "Player owns no territories"
        )
    }

    val targetObjects = targetTerritories.mapNotNull { name ->
        world.mapTiles.find { it.name.equals(name, ignoreCase = true) }
    }

    if (targetObjects.isEmpty()) {
        return SourceLocationResult(
            sourceTerritories = playerOwnedTerritories.map { it.name },
            wasSpecified = false,
            wasOptimized = true,
            optimizationReason = "No valid targets found - defaulting to all owned territories"
        )
    }

    // Find territories adjacent to ANY target (most optimal)
    val adjacentToTargets = playerOwnedTerritories.filter { source ->
        targetObjects.any { target -> world.isAdjacentTo(source, target) }
    }

    if (adjacentToTargets.isNotEmpty()) {
        return SourceLocationResult(
            sourceTerritories = adjacentToTargets.map { it.name },
            wasSpecified = false,
            wasOptimized = true,
            optimizationReason = "Selected territories adjacent to target(s)"
        )
    }

    // Find closest territories by BFS distance
    val distances = playerOwnedTerritories.map { source ->
        val minDistance = targetObjects.map { target ->
            getTerritoryDistanceBFS(source, target, world) ?: Int.MAX_VALUE
        }.minOrNull() ?: Int.MAX_VALUE
        source to minDistance
    }.sortedBy { it.second }

    val closestSource = distances.firstOrNull()
    if (closestSource != null && closestSource.second != Int.MAX_VALUE) {
        return SourceLocationResult(
            sourceTerritories = listOf(closestSource.first.name),
            wasSpecified = false,
            wasOptimized = true,
            optimizationReason = "Selected closest territory to target: ${closestSource.first.name} (distance: ${closestSource.second})"
        )
    }

    // Fallback
    return SourceLocationResult(
        sourceTerritories = playerOwnedTerritories.map { it.name },
        wasSpecified = false,
        wasOptimized = true,
        optimizationReason = "Defaulted to all owned territories"
    )
}

/**
 * Validates that paths from source territories to targets don't cross hostile territories.
 * For Military actions, crossing hostile territory is suboptimal.
 */
fun validateSourceToTargetPaths(
    sourceTerritories: List<String>,
    targetTerritories: List<String>,
    player: Player,
    world: World,
    isMilitaryAction: Boolean = true
): PathValidationResult {
    val warnings = mutableListOf<String>()
    val hostileCrossed = mutableListOf<String>()
    val playersOnPath = mutableListOf<String>()
    var allPathsValid = true

    val sourceObjects = sourceTerritories.mapNotNull { name ->
        world.mapTiles.find { it.name.equals(name, ignoreCase = true) }
    }
    val targetObjects = targetTerritories.mapNotNull { name ->
        world.mapTiles.find { it.name.equals(name, ignoreCase = true) }
    }

    sourceObjects.forEach { source ->
        targetObjects.forEach { target ->
            val path = getTerritoryPathBFS(source, target, world)
            if (path != null) {
                // Check each step in path for hostile territories (exclude start and end)
                path.drop(1).dropLast(1).forEach { step ->
                    if (step.ruler.isNotBlank() &&
                        !step.ruler.equals(player.name, ignoreCase = true)) {
                        hostileCrossed.add(step.name)
                        // Track the player whose territory is on the path
                        if (!playersOnPath.contains(step.ruler)) {
                            playersOnPath.add(step.ruler)
                        }
                        if (isMilitaryAction) {
                            allPathsValid = false
                        }
                    }
                }
            }
        }
    }

    if (hostileCrossed.isNotEmpty()) {
        val distinctHostile = hostileCrossed.distinct()
        warnings.add("Path crosses hostile territories: ${distinctHostile.joinToString()}")
        Logger.warn(LogCategory.GENERAL, "validateSourceToTargetPaths: Paths cross hostile territories: $distinctHostile")
    }

    if (playersOnPath.isNotEmpty()) {
        Logger.info(LogCategory.GENERAL, "validateSourceToTargetPaths: Players on attack path: $playersOnPath")
    }

    return PathValidationResult(
        allPathsValid = allPathsValid,
        hostileTerritoriesCrossed = hostileCrossed.distinct(),
        playersOnPath = playersOnPath.distinct(),
        warnings = warnings
    )
}

/**
 * BFS pathfinding between territories. Returns null if no path exists.
 */
private fun getTerritoryPathBFS(start: Territory, end: Territory, world: World): List<Territory>? {
    val queue = mutableListOf(start)
    val visited = mutableSetOf(start)
    val parent = mutableMapOf<Territory, Territory>()

    while (queue.isNotEmpty()) {
        val current = queue.removeAt(0)
        if (current == end) {
            val path = mutableListOf<Territory>()
            var node: Territory? = end
            while (node != null) {
                path.add(node)
                node = parent[node]
            }
            return path.reversed()
        }

        val neighbors = getNeighborTerritories(current, world)

        neighbors.forEach { neighbor ->
            if (neighbor !in visited) {
                visited.add(neighbor)
                parent[neighbor] = current
                queue.add(neighbor)
            }
        }
    }
    return null
}

/**
 * Gets BFS distance (number of hops) between two territories.
 */
private fun getTerritoryDistanceBFS(start: Territory, end: Territory, world: World): Int? {
    val path = getTerritoryPathBFS(start, end, world)
    return path?.size?.minus(1) // path size - 1 = number of edges/hops
}

/**
 * Gets all neighboring territories from a territory's 8 directional border lists.
 */
private fun getNeighborTerritories(territory: Territory, world: World): List<Territory> {
    val neighbors = mutableListOf<Territory>()

    val borderLists = listOf(
        territory.northBorders,
        territory.southBorders,
        territory.eastBorders,
        territory.westBorders,
        territory.northEastBorders,
        territory.northWestBorders,
        territory.southEastBorders,
        territory.southWestBorders
    )

    borderLists.forEach { borders ->
        borders.forEach { border ->
            border.adjacentTerritory?.let { neighbor ->
                if (neighbor !in neighbors) {
                    neighbors.add(neighbor)
                }
            }
        }
    }

    return neighbors
}

/**
 * Extension to check if two territories are adjacent.
 */
private fun World.isAdjacentTo(territoryA: Territory, territoryB: Territory): Boolean {
    val neighbors = getNeighborTerritories(territoryA, this)
    return neighbors.any { it.name == territoryB.name }
}

private fun getPlayerOwnedTerritories(player: Player): List<Territory> {
    val territories = mutableListOf<Territory>()
    if (!player.startingTile.isDestroyed) {
        territories.add(player.startingTile)
    }
    territories.addAll(player.capturedTerritory.filter { !it.isDestroyed })
    return territories
}
