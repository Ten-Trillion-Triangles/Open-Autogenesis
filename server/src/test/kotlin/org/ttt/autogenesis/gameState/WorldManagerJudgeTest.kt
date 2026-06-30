package org.ttt.autogenesis.gameState

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import agent.builders.judgeOutcome.Results
import gameState.WorldManager
import serverStructs.PlayerStats
import structs.Resource
import structs.Territory
import structs.Player
import structs.Npc
import structs.World
import structs.GameHistory
import structs.ui.GameEventType
import agent.builders.judgeOutcome.TerritoryExchange

class WorldManagerJudgeTest
{
    @BeforeTest
    fun resetWorldState()
    {
        WorldManager.world = World()
        WorldManager.history.clear()
        WorldManager.actionHistoryLog.clear()
        WorldManager.pendingActionHistoryByTurn.clear()
        WorldManager.playerStats.clear()
    }

    @Test
    fun applyJudgeResults_updatesWorldAndHistory()
    {
        val player = Player(name = "Commander")
        WorldManager.world.activePlayers.add(player)
        val playerStats = PlayerStats(playerData = player, accelByteUserId = "stub", playerID = "abc", isConnected = true, turnActive = true)
        WorldManager.playerStats.add(playerStats)

        val territoryGained = Territory(name = "North Ridge", ruler = "Rival", pointValue = 3)
        val lostTerritory = Territory(name = "Old Keep", ruler = player.name, pointValue = 2)
        WorldManager.world.mapTiles.addAll(mutableListOf(territoryGained, lostTerritory))
        player.resources.add(Resource(name = "DiplomaticToken"))

        val results = Results(
            assetsGained = mutableListOf("Warship"),
            territoryGained = mutableListOf("North Ridge"),
            assetsLost = mutableListOf("DiplomaticToken"),
            territoryLost = mutableListOf("Old Keep")
        )

        runBlocking {
            WorldManager.applyJudgeResults(player.name, wasSuccessful = true, results = results, turnNumber = 1, timestampMillis = 1000L)
        }

        assertTrue(player.resources.any { it.name == "Warship" })
        assertFalse(player.resources.any { it.name.equals("DiplomaticToken", ignoreCase = true) })

        assertEquals(player.name, territoryGained.ruler)
        assertTrue(territoryGained.isCaptured)
        assertFalse(lostTerritory.isCaptured)
        assertEquals("", lostTerritory.ruler)

        val historyEvents = WorldManager.actionHistoryLog.map { it.event.eventType }
        assertTrue(GameEventType.JUDGE in historyEvents)
        assertTrue(GameEventType.PLAYER_OUTCOME in historyEvents)
        assertTrue(historyEvents.count { it == GameEventType.RESOURCE_GRANT } == 1)
        assertTrue(historyEvents.count { it == GameEventType.TERRITORY } == 2)

        assertEquals(6, WorldManager.actionHistoryLog.size)
    }

    @Test
    fun applyJudgeResults_handlesTerritoryExchanges()
    {
        val player = Player(name = "Commander")
        val rival = Npc(name = "RivalLord")
        val unknownNpcName = "ShadowGeneral"

        WorldManager.world.activePlayers.add(player)
        WorldManager.world.npc.add(rival)

        val territory1 = Territory(name = "Ironwood", ruler = rival.name, isCaptured = true)
        val territory2 = Territory(name = "StormPeak", ruler = player.name, isCaptured = true)
        val territory3 = Territory(name = "NeutralGround", ruler = "", isCaptured = false)

        rival.capturedTerritory.add(territory1)
        player.capturedTerritory.add(territory2)

        WorldManager.world.mapTiles.addAll(mutableListOf(territory1, territory2, territory3))

        val results = Results(
            territoryExchanges = mutableListOf(
                TerritoryExchange(territoryName = "Ironwood", from = rival.name, to = player.name), // NPC -> Player
                TerritoryExchange(territoryName = "StormPeak", from = player.name, to = unknownNpcName), // Player -> New NPC (Neutral)
                TerritoryExchange(territoryName = "NeutralGround", from = "", to = rival.name) // Neutral -> NPC
            )
        )

        runBlocking {
            WorldManager.applyJudgeResults(player.name, wasSuccessful = true, results = results, turnNumber = 1, timestampMillis = 1000L)
        }

        // NPC -> Player
        assertEquals(player.name, territory1.ruler)
        assertTrue(territory1.isCaptured)
        assertTrue(player.capturedTerritory.any { it.name == "Ironwood" })
        assertFalse(rival.capturedTerritory.any { it.name == "Ironwood" })

        // Player -> New NPC (Neutral)
        assertEquals("", territory2.ruler)
        assertFalse(territory2.isCaptured)
        assertFalse(player.capturedTerritory.any { it.name == "StormPeak" })

        // Neutral -> NPC
        assertEquals(rival.name, territory3.ruler)
        assertTrue(territory3.isCaptured)
        assertTrue(rival.capturedTerritory.any { it.name == "NeutralGround" })
    }

    @Test
    fun stageAndFinalizeHistoryEntry_updatesExistingRecord() = runBlocking {
        val entryId = "stage-entry-test"
        val placeholder = GameHistory(turnPlayer = "Commander", turnAction = "Planning", id = entryId)
        WorldManager.history.add(placeholder)

        WorldManager.stageCurrentTurnHistory(entryId) {
            turnStory = "Staged narrative for judge"
            targetIntent = "Friendly"
            targetEntities.clear()
            targetEntities.addAll(listOf("North Gate", "Silver Harbor"))
        }

        val finalized = WorldManager.finalizeStagedHistoryEntry(entryId) {
            turnResult = "Success"
        }

        assertEquals("Staged narrative for judge", finalized.turnStory)
        assertEquals("Friendly", finalized.targetIntent)
        assertEquals(listOf("North Gate", "Silver Harbor"), finalized.targetEntities)
        assertEquals("Success", finalized.turnResult)
        assertTrue(WorldManager.history.lastOrNull() === finalized)
    }

    @Test
    fun applyJudgeResults_handlesGainThenDepose()
    {
        val player = Player(name = "Commander")
        WorldManager.world.activePlayers.add(player)
        val playerStats = PlayerStats(playerData = player, accelByteUserId = "stub", playerID = "abc", isConnected = true, turnActive = true)
        WorldManager.playerStats.add(playerStats)

        val targetTerritory = Territory(name = "New England", ruler = "Gew Zeeldox", isCaptured = true)
        WorldManager.world.mapTiles.add(targetTerritory)

        val results = Results(
            territoryGained = mutableListOf("New England"),
            territoriesDeposed = mutableListOf("New England"),
            territoryExchanges = mutableListOf(
                TerritoryExchange(territoryName = "New England", from = "Gew Zeeldox", to = "Commander")
            )
        )

        runBlocking {
            WorldManager.applyJudgeResults(player.name, wasSuccessful = true, results = results, turnNumber = 1, timestampMillis = 1000L)
        }

        assertEquals(player.name, targetTerritory.ruler, "Territory should be owned by player even if deposed (as deposition should happen first)")
        assertTrue(targetTerritory.isCaptured)
        assertTrue(player.capturedTerritory.any { it.name == "New England" })
    }
}
