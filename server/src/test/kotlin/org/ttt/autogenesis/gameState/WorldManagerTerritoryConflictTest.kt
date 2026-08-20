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
import serverStructs.PlayerStats
import structs.Npc
import structs.Player
import structs.Territory
import structs.World


/**
 * Tests for territory conflict resolution rules in WorldManager.
 * Verifies Rule 1 (Player > NPC), Rule 2 (Acting Player > Other Player), and Rule 3 (Bidirectional → Contested).
 */
class WorldManagerTerritoryConflictTest
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


    /**
     * Rule 1: When player and NPC are granted the same territory, player wins.
     */
    @Test
    fun applyTerritoryChanges_Rule1_PlayerWinsOverNpc()
    {
        val player = Player(name = "Commander")
        val npc = Npc(name = "ShadowKing")
        WorldManager.world.activePlayers.add(player)
        WorldManager.world.npc.add(npc)

        val territory = Territory(name = "Darkwood", ruler = "SomeRuler", pointValue = 3)
        WorldManager.world.mapTiles.add(territory)

        val results = Results(
            territoryGained = mutableListOf("Darkwood"),
            territoryExchanges = mutableListOf(
                TerritoryExchange(territoryName = "Darkwood", from = "", to = "ShadowKing")
            )
        )

        runBlocking {
            WorldManager.applyJudgeResultsUnlocked(
                playerName = player.name,
                wasSuccessful = true,
                results = results,
                turnNumber = 1,
                timestampMillis = 1000L
            )
        }

        assertEquals(player.name, territory.ruler, "Player should win over NPC")
        assertTrue(territory.isCaptured)
        assertTrue(player.capturedTerritory.any { it.name == "Darkwood" }, "Player should have Darkwood")
        assertFalse(npc.capturedTerritory.any { it.name == "Darkwood" }, "NPC should NOT have Darkwood")
    }


    /**
     * Rule 2: When acting player and another player are granted the same territory, acting player wins.
     */
    @Test
    fun applyTerritoryChanges_Rule2_ActingPlayerWins()
    {
        val actingPlayer = Player(name = "Commander")
        val rivalPlayer = Player(name = "Rival")
        WorldManager.world.activePlayers.add(actingPlayer)
        WorldManager.world.activePlayers.add(rivalPlayer)

        val territory = Territory(name = "BorderKeep", ruler = "OldRuler", pointValue = 4)
        WorldManager.world.mapTiles.add(territory)

        val results = Results(
            territoryGained = mutableListOf("BorderKeep"),
            territoryExchanges = mutableListOf(
                TerritoryExchange(territoryName = "BorderKeep", from = "", to = "Rival")
            )
        )

        runBlocking {
            WorldManager.applyJudgeResultsUnlocked(
                playerName = "Commander",
                wasSuccessful = true,
                results = results,
                turnNumber = 1,
                timestampMillis = 1000L
            )
        }

        assertEquals(actingPlayer.name, territory.ruler, "Acting player should win")
        assertTrue(territory.isCaptured)
        assertTrue(actingPlayer.capturedTerritory.any { it.name == "BorderKeep" }, "Acting player should have BorderKeep")
        assertFalse(rivalPlayer.capturedTerritory.any { it.name == "BorderKeep" }, "Rival should NOT have BorderKeep")
    }


    /**
     * Rule 3: When two players attack each other for the same territory (bidirectional),
     * the territory becomes contested (neutral, unowned).
     */
    @Test
    fun applyTerritoryChanges_Rule3_BidirectionalAttack_MarkedContested()
    {
        val playerA = Player(name = "PlayerA")
        val playerB = Player(name = "PlayerB")
        WorldManager.world.activePlayers.add(playerA)
        WorldManager.world.activePlayers.add(playerB)

        val territory = Territory(name = "ContestedZone", ruler = playerA.name, isCaptured = true)
        WorldManager.world.mapTiles.add(territory)
        playerA.capturedTerritory.add(territory)

        val results = Results(
            territoryGained = mutableListOf("ContestedZone"),
            territoryExchanges = mutableListOf(
                TerritoryExchange(territoryName = "ContestedZone", from = "PlayerA", to = "PlayerB"),
                TerritoryExchange(territoryName = "ContestedZone", from = "PlayerB", to = "PlayerA")
            )
        )

        runBlocking {
            WorldManager.applyJudgeResultsUnlocked(
                playerName = "PlayerA",
                wasSuccessful = true,
                results = results,
                turnNumber = 1,
                timestampMillis = 1000L
            )
        }

        assertEquals("", territory.ruler, "Territory should be neutral (contested)")
        assertFalse(territory.isCaptured, "Territory should not be captured")
        assertFalse(playerA.capturedTerritory.any { it.name == "ContestedZone" }, "PlayerA should NOT have ContestedZone")
        assertFalse(playerB.capturedTerritory.any { it.name == "ContestedZone" }, "PlayerB should NOT have ContestedZone")
    }


    /**
     * When there is no conflict (player gains territory that isn't in exchanges),
     * processing should proceed normally without conflicts.
     */
    @Test
    fun applyTerritoryChanges_NoConflict_ProcessesNormally()
    {
        val player = Player(name = "Commander")
        WorldManager.world.activePlayers.add(player)

        val territory = Territory(name = "NormalZone", ruler = "OldRuler", pointValue = 2)
        WorldManager.world.mapTiles.add(territory)

        val results = Results(
            territoryGained = mutableListOf("NormalZone"),
            territoryExchanges = mutableListOf()
        )

        runBlocking {
            WorldManager.applyJudgeResultsUnlocked(
                playerName = player.name,
                wasSuccessful = true,
                results = results,
                turnNumber = 1,
                timestampMillis = 1000L
            )
        }

        assertEquals(player.name, territory.ruler, "Player should own NormalZone")
        assertTrue(territory.isCaptured)
        assertTrue(player.capturedTerritory.any { it.name == "NormalZone" })
    }
}