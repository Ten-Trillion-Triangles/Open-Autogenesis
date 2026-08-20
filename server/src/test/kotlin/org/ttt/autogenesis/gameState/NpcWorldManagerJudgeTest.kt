package org.ttt.autogenesis.gameState

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import agent.builders.judgeOutcome.Results
import agent.builders.judgeOutcome.TerritoryExchange
import gameState.WorldManager
import structs.Npc
import structs.Resource
import enums.ResourceType
import structs.Territory
import structs.World
import structs.ui.GameEventType

class NpcWorldManagerJudgeTest {

    @BeforeTest
    fun resetWorldState() {
        WorldManager.world = World()
        WorldManager.history.clear()
        WorldManager.actionHistoryLog.clear()
        WorldManager.pendingActionHistoryByTurn.clear()
        WorldManager.playerStats.clear()
    }

    @Test
    fun applyNpcJudgeResults_updatesNpcAndWorld() {
        val npc = Npc(name = "GoblinKing")
        WorldManager.world.npc.add(npc)

        val targetTerritory = Territory(name = "Cave Entrance", ruler = "Neutral", pointValue = 1)
        WorldManager.world.mapTiles.add(targetTerritory)

        val results = Results(
            assetsGained = mutableListOf("StolenGold"),
            territoryGained = mutableListOf("Cave Entrance")
        )

        runBlocking {
            WorldManager.applyNpcJudgeResults(npc.name, results)
        }

        // Verify assets
        assertTrue(npc.resources.any { it.name == "StolenGold" })

        // Verify territory
        assertEquals(npc.name, targetTerritory.ruler)
        assertTrue(targetTerritory.isCaptured)
        assertTrue(npc.capturedTerritory.any { it.name == "Cave Entrance" })

        // Verify logging
        val historyEvents = WorldManager.actionHistoryLog.map { it.event.eventType }
        assertTrue(GameEventType.JUDGE in historyEvents)
        assertTrue(GameEventType.RESOURCE_GRANT in historyEvents)
        assertTrue(GameEventType.TERRITORY in historyEvents)
    }

    @Test
    fun applyNpcJudgeResults_handlesLosses() {
        val npc = Npc(name = "FallenKnight")
        WorldManager.world.npc.add(npc)
        val sword = Resource(name = "BrokenSword", type = ResourceType.Military)
        npc.resources.add(sword)

        val castle = Territory(name = "RuinedCastle", ruler = npc.name, isCaptured = true)
        npc.capturedTerritory.add(castle)
        WorldManager.world.mapTiles.add(castle)

        val results = Results(
            assetsLost = mutableListOf("BrokenSword"),
            territoryLost = mutableListOf("RuinedCastle")
        )

        runBlocking {
            WorldManager.applyNpcJudgeResults(npc.name, results)
        }

        // Verify assets removed
        assertFalse(npc.resources.any { it.name == "BrokenSword" })

        // Verify territory removed
        assertEquals("", castle.ruler)
        assertFalse(castle.isCaptured)
        assertFalse(npc.capturedTerritory.any { it.name == "RuinedCastle" })
    }

    @Test
    fun applyKarmaChange_updatesWorldKarma() {
        WorldManager.world.karmaPoints = 50

        runBlocking {
            WorldManager.applyKarmaChange(isPositive = true)
        }
        assertEquals(55, WorldManager.world.karmaPoints)

        runBlocking {
            WorldManager.applyKarmaChange(isPositive = false)
        }
        assertEquals(50, WorldManager.world.karmaPoints)
    }
}