package org.ttt.autogenesis.gameState

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import gameState.ChangeType
import gameState.UniverseChange
import gameState.WorldManager
import serverStructs.PlayerStats
import structs.Player
import structs.Territory
import structs.World
import structs.ui.GameEventType
import structs.ui.WorldRuleMetadata

class WorldManagerWorldUpdatesTest
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
    fun applyUniverseChanges_removesMapTileAndLogsChanges()
    {
        val player = Player(name = "Commander")
        WorldManager.world.activePlayers.add(player)
        val playerStats = PlayerStats(
            playerData = player,
            accelByteUserId = "accel",
            playerID = "id",
            isConnected = true,
            turnActive = true
        )
        WorldManager.playerStats.add(playerStats)

        val disappearingTile = Territory(name = "Oblivion")
        WorldManager.world.mapTiles.add(disappearingTile)

        val terrainChange = UniverseChange(
            changeType = ChangeType.TERRAIN_REMOVED,
            before = "Oblivion guarded the eastern ridge",
            after = "Oblivion vanished into the void",
            details = "The eastern ridge collapsed into a void",
            affectedTerritories = listOf("Oblivion")
        )

        val magicChange = UniverseChange(
            changeType = ChangeType.MAGIC_DISCOVERED,
            details = "New ley lines glitter across the horizon",
            after = "Ley politics now influence every battle"
        )

        runBlocking {
            WorldManager.applyUniverseChanges(
                changes = listOf(terrainChange, magicChange),
                turnNumber = 2,
                timestampMillis = 6000L,
                playerName = player.name
            )
        }

        assertFalse(WorldManager.world.mapTiles.any { it.name.equals("Oblivion", ignoreCase = true) })
        assertTrue(WorldManager.world.worldRules.contains("Ley politics now influence every battle"))

        assertEquals(2, WorldManager.actionHistoryLog.size)
        assertTrue(WorldManager.actionHistoryLog.all { it.event.eventType == GameEventType.WORLD_RULE })

        val metadata = WorldManager.actionHistoryLog.map { it.event.metadata as? WorldRuleMetadata }
        assertEquals("terrain_removed", metadata[0]?.ruleName)
        assertEquals("Ley politics now influence every battle", metadata[1]?.effect)
    }
}