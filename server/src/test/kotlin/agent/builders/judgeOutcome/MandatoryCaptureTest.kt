package agent.builders.judgeOutcome

import agent.builders.validateAction.ActionTargetType
import agent.builders.validateAction.ActionTargetTypeObj
import agent.builders.validateAction.PlayType
import gameState.WorldManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import structs.Border
import structs.Player
import structs.Resource
import structs.Territory
import structs.World

class MandatoryCaptureTest
{
    @BeforeTest
    fun resetWorld()
    {
        WorldManager.world = World()
        WorldManager.history.clear()
        WorldManager.actionHistoryLog.clear()
        WorldManager.pendingActionHistoryByTurn.clear()
        WorldManager.playerStats.clear()
    }

    @Test
    fun addsTerritoryForMilitarySuccess()
    {
        val (player, territory) = setupAdjacentCaptureScenario()

        val results = Results()
        val targetData = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf(territory.name)
        )

        enforceMandatoryTerritoryCapture(
            results = results,
            playerName = player.name,
            targetData = targetData,
            playTypeContext = PlayTypeContext(playType = PlayType.Military.name, wasSuccessful = true),
            wasSuccessful = true,
            actionIntent = "Hostile"
        )

        assertTrue(results.territoryGained.any { it.equals(territory.name, ignoreCase = true) })
    }

    @Test
    fun addsTerritoryForNonAdjacentMilitarySuccess()
    {
        val player = Player(name = "Commander")
        val territory = Territory(name = "Distant Land", ruler = "Rival", isCaptured = true)
        
        WorldManager.world.activePlayers.add(player)
        WorldManager.world.mapTiles.add(territory)

        val results = Results()
        val targetData = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf(territory.name)
        )

        enforceMandatoryTerritoryCapture(
            results = results,
            playerName = player.name,
            targetData = targetData,
            playTypeContext = PlayTypeContext(playType = PlayType.Military.name, wasSuccessful = true),
            wasSuccessful = true,
            actionIntent = "Hostile"
        )

        assertTrue(results.territoryGained.any { it.equals(territory.name, ignoreCase = true) })
    }

    @Test
    fun doesNotAddTerritoryWhenPlayTypeIsNotMilitary()
    {
        val (player, territory) = setupAdjacentCaptureScenario()
        val results = Results()

        enforceMandatoryTerritoryCapture(
            results = results,
            playerName = player.name,
            targetData = ActionTargetTypeObj(
                type = ActionTargetType.Territory,
                targets = listOf(territory.name)
            ),
            playTypeContext = PlayTypeContext(playType = PlayType.Diplomatic.name, wasSuccessful = true),
            wasSuccessful = true,
            actionIntent = "Hostile"
        )

        assertFalse(results.territoryGained.any { it.equals(territory.name, ignoreCase = true) })
    }

    @Test
    fun awardsHiddenResourceForCapturedTerritory()
    {
        val resourceName = "Hidden Beryl Deposit"
        val (player, territory) = setupTerritoryWithHiddenResource(resourceName)
        val results = Results().apply {
            territoryGained.add(territory.name)
        }

        enforceHiddenResourceForCapturedTerritories(
            results = results,
            player = player,
            playerName = player.name,
            playTypeContext = PlayTypeContext(playType = PlayType.Military.name, wasSuccessful = true),
            wasSuccessful = true,
            actionIntent = "Hostile"
        )

        assertTrue(results.assetsGained.any { it.equals(resourceName, ignoreCase = true) })
    }

    @Test
    fun doesNotAwardHiddenResourceWhenPlayerAlreadyOwnsIt()
    {
        val resourceName = "Hidden Beryl Deposit"
        val (player, territory) = setupTerritoryWithHiddenResource(resourceName)
        player.resources.add(Resource(name = resourceName))
        val results = Results().apply {
            territoryGained.add(territory.name)
        }

        enforceHiddenResourceForCapturedTerritories(
            results = results,
            player = player,
            playerName = player.name,
            playTypeContext = PlayTypeContext(playType = PlayType.Military.name, wasSuccessful = true),
            wasSuccessful = true,
            actionIntent = "Hostile"
        )

        assertFalse(results.assetsGained.any { it.equals(resourceName, ignoreCase = true) })
    }

    private fun setupAdjacentCaptureScenario(): Pair<Player, Territory>
    {
        val player = Player(name = "Commander")
        val home = Territory(name = "Home Base", ruler = player.name, isCaptured = true)
        player.startingTile = home
        player.capturedTerritory.add(home)

        val frontier = Territory(name = "Frontier", ruler = "Rival", isCaptured = true)
        home.northBorders.add(Border(adjacentTerritory = frontier))
        frontier.southBorders.add(Border(adjacentTerritory = home))

        WorldManager.world.activePlayers.add(player)
        WorldManager.world.mapTiles.addAll(listOf(home, frontier))

        return player to frontier
    }

    private fun setupTerritoryWithHiddenResource(resourceName: String): Pair<Player, Territory>
    {
        val player = Player(name = "Commander")
        val territory = Territory(
            name = "Hidden Vale",
            ruler = "Rival",
            isCaptured = true
        ).apply {
            resource = Resource(name = resourceName)
        }

        WorldManager.world.activePlayers.add(player)
        WorldManager.world.mapTiles.add(territory)

        return player to territory
    }
}