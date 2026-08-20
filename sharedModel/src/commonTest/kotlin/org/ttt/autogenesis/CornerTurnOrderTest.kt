package org.ttt.autogenesis

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import structs.Player
import structs.Territory
import structs.World
import structs.assignRandomStartingTiles
import structs.determineStartingTurnOrder

private fun buildTerritory(name: String, x: Double, y: Double): Territory
{
    return Territory(
        name = name,
        xPos = x,
        yPos = y
    )
}

private fun buildPlayer(name: String): Player
{
    return Player(name = name)
}

class CornerTurnOrderTest
{
    @Test
    fun ordersPlayersClockwiseStartingFromSouthwest()
    {
        val swTerritory = buildTerritory("Southwest", x = 25.0, y = 75.0)
        val seTerritory = buildTerritory("Southeast", x = 75.0, y = 75.0)
        val neTerritory = buildTerritory("Northeast", x = 75.0, y = 25.0)
        val nwTerritory = buildTerritory("Northwest", x = 25.0, y = 25.0)

        val world = World(
            mapTiles = mutableListOf(swTerritory, seTerritory, neTerritory, nwTerritory)
        )

        val swPlayer = Player(name = "SW", startingTile = swTerritory)
        val sePlayer = Player(name = "SE", startingTile = seTerritory)
        val nePlayer = Player(name = "NE", startingTile = neTerritory)
        val nwPlayer = Player(name = "NW", startingTile = nwTerritory)

        val unorderedPlayers = listOf(nePlayer, nwPlayer, swPlayer, sePlayer)
        val orderedPlayers = determineStartingTurnOrder(world, unorderedPlayers)

        assertEquals(listOf(swPlayer, sePlayer, nePlayer, nwPlayer), orderedPlayers)
    }

    @Test
    fun throwsWhenPlayerIsNotInAQuadrantCorner()
    {
        val swTerritory = buildTerritory("Southwest", x = 25.0, y = 75.0)
        val seTerritory = buildTerritory("Southeast", x = 75.0, y = 75.0)
        val centralTerritory = buildTerritory("Center", x = 50.0, y = 50.0)

        val world = World(
            mapTiles = mutableListOf(swTerritory, seTerritory, centralTerritory, buildTerritory("Northwest", x = 25.0, y = 25.0))
        )

        val players = listOf(
            Player(name = "SW", startingTile = swTerritory),
            Player(name = "SE", startingTile = seTerritory),
            Player(name = "CENTER", startingTile = centralTerritory),
            Player(name = "NW", startingTile = buildTerritory("Northwest", x = 25.0, y = 25.0))
        )

        assertFailsWith<IllegalStateException>
        {
            determineStartingTurnOrder(world, players)
        }
    }

    @Test
    fun supportsVariablePlayerCount()
    {
        val swTerritory = buildTerritory("Southwest", x = 25.0, y = 75.0)
        val neTerritory = buildTerritory("Northeast", x = 75.0, y = 25.0)

        val world = World(
            mapTiles = mutableListOf(swTerritory, neTerritory, buildTerritory("Other", 0.0, 0.0))
        )

        val swPlayer = Player(name = "SW", startingTile = swTerritory)
        val nePlayer = Player(name = "NE", startingTile = neTerritory)

        val unorderedPlayers = listOf(nePlayer, swPlayer)
        val orderedPlayers = determineStartingTurnOrder(world, unorderedPlayers)

        assertEquals(listOf(swPlayer, nePlayer), orderedPlayers)
    }

    @Test
    fun throwsWhenPlayerCountIsInvalid()
    {
        val swTerritory = buildTerritory("Southwest", x = 25.0, y = 75.0)
        val world = World(mapTiles = mutableListOf(swTerritory))

        // 1 player is invalid
        val onePlayer = listOf(Player(name = "SW", startingTile = swTerritory))
        assertFailsWith<IllegalArgumentException>
        {
            determineStartingTurnOrder(world, onePlayer)
        }

        // 5 players is invalid
        val fivePlayers = (1..5).map { Player(name = "P$it", startingTile = swTerritory) }
        assertFailsWith<IllegalArgumentException>
        {
            determineStartingTurnOrder(world, fivePlayers)
        }
    }

    @Test
    fun assignsRandomStartingTilesUniquely()
    {
        val territories = (1..5).map { index ->
            buildTerritory("Tile$index", x = index * 10.0, y = index * 5.0)
        }

        val world = World(
            mapTiles = territories.toMutableList()
        )

        val players = listOf(
            buildPlayer("Player1"),
            buildPlayer("Player2"),
            buildPlayer("Player3"),
            buildPlayer("Player4")
        )

        assignRandomStartingTiles(world, players, Random(42))

        val startingTiles = players.map { it.startingTile }
        assertEquals(4, startingTiles.distinctBy { it.name }.size)
        players.forEach { player ->
            assertTrue(player.capturedTerritory.contains(player.startingTile))
        }
    }

    @Test
    fun throwsWhenNotEnoughTilesForRandomAssignment()
    {
        val tile = buildTerritory("Solo", x = 0.0, y = 0.0)
        val world = World(
            mapTiles = mutableListOf(tile)
        )

        val players = listOf(
            buildPlayer("Player1"),
            buildPlayer("Player2")
        )

        assertFailsWith<IllegalArgumentException>
        {
            assignRandomStartingTiles(world, players, Random(0))
        }
    }
}