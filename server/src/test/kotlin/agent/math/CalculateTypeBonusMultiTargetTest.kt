package agent.math

import agent.builders.validateAction.ActionTargetType
import agent.builders.validateAction.ActionTargetTypeObj
import enums.CommanderTrait
import enums.CommanderType
import enums.ObstacleType
import enums.TerritoryType
import gameState.WorldManager
import io.mockk.unmockkAll
import org.junit.Before
import org.junit.Test
import structs.Border
import structs.Player
import structs.Territory
import structs.World
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the per-target loop behavior of [GameMath.calculateTypeBonus] for multi-target actions.
 *
 * Before the fix, the function short-circuited on the first target — multi-target actions
 * received either a single adjacent-terrain score or a single long-range modifier, never the
 * sum of per-target contributions. These tests fail against the pre-fix code and pass against
 * the post-fix per-target loop.
 */
class CalculateTypeBonusMultiTargetTest
{
    @Before
    fun clearMockKState()
    {
        // Clear any lingering MockK stubs from a prior test in the same JVM.
        // SummitOrchestratorTest sets up broad slot-mock stubs that can
        // interfere with GameMath's internal coroutine scopes if not cleaned.
        unmockkAll()
    }
    /**
     * Build a world with three territories:
     *   HomeBase       — owned by the player, central node
     *   AdjNeighbor    — directly south of HomeBase, shares a border
     *   FarIsolated    — two hops away from HomeBase, NOT directly adjacent to any player tile
     *
     * For a Land commander attacking both AdjNeighbor and FarIsolated:
     *   AdjNeighbor    : adjacent path with no obstacle -> +20 (Land vs Land, no-obstacle bonus)
     *   FarIsolated    : non-adjacent -> long-range modifier (a real number, dominated by -5 per hop)
     *   expected total = 20 + farModifier
     */
    private fun buildTwoHopWorld(): World
    {
        val home = Territory(name = "HomeBase", type = TerritoryType.Land, ruler = "Player1")
        val adj = Territory(name = "AdjNeighbor", type = TerritoryType.Land, ruler = "Rival")
        val far = Territory(name = "FarIsolated", type = TerritoryType.Land, ruler = "Rival")
        val bridge = Territory(name = "Bridge", type = TerritoryType.Land, ruler = "Rival")

        // HomeBase north of AdjNeighbor (so AdjNeighbor.southBorders -> HomeBase)
        adj.southBorders.add(Border(adjacentTerritory = home))
        // AdjNeighbor east of Bridge
        bridge.westBorders.add(Border(adjacentTerritory = adj))
        // FarIsolated east of Bridge, with a river on the border so any path that crosses it pays the penalty
        bridge.eastBorders.add(Border(obstacleType = ObstacleType.River, adjacentTerritory = far))
        far.westBorders.add(Border(obstacleType = ObstacleType.River, adjacentTerritory = bridge))

        return World(
            roundNumber = 5,
            mapTiles = mutableListOf(home, adj, bridge, far)
        )
    }

    @Before
    fun resetWorld()
    {
        WorldManager.world = World()
    }

    @Test
    fun calculateTypeBonus_multiTarget_sumsAdjacentAndLongRangeScores()
    {
        val world = buildTwoHopWorld()
        WorldManager.world = world
        val home = world.mapTiles.first { it.name == "HomeBase" }
        val player = Player(
            name = "Player1",
            commanderType = CommanderType.Land,
            trait = CommanderTrait.Balanced,
            startingTile = home
        )

        val targetType = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf("AdjNeighbor", "FarIsolated")
        )

        val total = GameMath.calculateTypeBonus(player, targetType)

        // Compute the expected per-target contributions against the same world.
        val adjTarget = world.mapTiles.first { it.name == "AdjNeighbor" }
        val farTarget = world.mapTiles.first { it.name == "FarIsolated" }

        // Adjacent: Land vs Land, no obstacle path -> +20
        val expectedAdjScore = 20
        // Long-range: BFS HomeBase -> FarIsolated traverses Bridge (river) -> distance >= 2
        val expectedFarModifier = world.calculateLongRangeModifier(player, home, farTarget)

        assertEquals(
            expectedAdjScore + expectedFarModifier,
            total,
            "calculateTypeBonus must sum per-target contributions (adjacent terrain score + long-range modifier)"
        )
        // Defensive: must be strictly different from any single-target contribution
        assertNotEquals(expectedAdjScore, total, "Multi-target total must not collapse to the adjacent-only score")
        assertNotEquals(expectedFarModifier, total, "Multi-target total must not collapse to the long-range-only score")
    }

    @Test
    fun calculateTypeBonus_singleTarget_matchesLegacyPerTargetScore()
    {
        val world = buildTwoHopWorld()
        WorldManager.world = world
        val home = world.mapTiles.first { it.name == "HomeBase" }
        val player = Player(
            name = "Player1",
            commanderType = CommanderType.Land,
            trait = CommanderTrait.Balanced,
            startingTile = home
        )

        val singleTargetType = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf("AdjNeighbor")
        )

        val singleTotal = GameMath.calculateTypeBonus(player, singleTargetType)

        // Single adjacent Land vs Land, no obstacle -> +20
        assertEquals(20, singleTotal, "Single adjacent target should still produce the legacy +20 per-target score")
    }

    @Test
    fun calculateTypeBonus_multiTargetAllAdjacent_sumsAllPerTargetScores()
    {
        // Build a world with two adjacent rivals, no long-range path.
        val home = Territory(name = "HomeBase", type = TerritoryType.Land, ruler = "Player1")
        val left = Territory(name = "LeftNeighbor", type = TerritoryType.Land, ruler = "Rival")
        val right = Territory(name = "RightNeighbor", type = TerritoryType.Land, ruler = "Rival")

        left.eastBorders.add(Border(adjacentTerritory = home))
        home.westBorders.add(Border(adjacentTerritory = left))
        right.westBorders.add(Border(adjacentTerritory = home))
        home.eastBorders.add(Border(adjacentTerritory = right))

        WorldManager.world = World(roundNumber = 5, mapTiles = mutableListOf(home, left, right))

        val player = Player(
            name = "Player1",
            commanderType = CommanderType.Land,
            trait = CommanderTrait.Balanced,
            startingTile = home
        )

        val targetType = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf("LeftNeighbor", "RightNeighbor")
        )

        val total = GameMath.calculateTypeBonus(player, targetType)
        // Two adjacent Land vs Land, no-obstacle paths -> +20 + +20 = +40
        assertEquals(40, total, "Two adjacent targets should sum their per-target +20 scores to +40")
    }

    @Test
    fun calculateTypeBonus_multiTarget_skipsUnresolvedTargetNames()
    {
        val world = buildTwoHopWorld()
        WorldManager.world = world
        val home = world.mapTiles.first { it.name == "HomeBase" }
        val player = Player(
            name = "Player1",
            commanderType = CommanderType.Land,
            trait = CommanderTrait.Balanced,
            startingTile = home
        )

        // "GhostTown" does not exist in the world — must be skipped, not crash the loop.
        val targetType = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf("AdjNeighbor", "GhostTown")
        )

        val total = GameMath.calculateTypeBonus(player, targetType)
        // Only AdjNeighbor resolves; the ghost is skipped, score is the single adjacent +20.
        assertEquals(20, total, "Unresolved target names must be skipped without affecting the total")
        assertTrue(total > 0, "Sum must still reflect the resolved adjacent target's positive score")
    }
}