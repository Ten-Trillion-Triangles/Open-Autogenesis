package org.ttt.autogenesis.gameState

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import gameState.ResourceAction
import gameState.ResourceAdjustment
import gameState.WorldManager
import serverStructs.PlayerStats
import structs.Resource
import structs.Player
import structs.World
import structs.ui.GameEventType

class WorldManagerResourceAdjustmentsTest
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
    fun applyResourceAdjustments_grantsAndDestroysResources()
    {
        val player = Player(name = "Commander")
        WorldManager.world.activePlayers.add(player)
        WorldManager.playerStats.add(
            PlayerStats(
                playerData = player,
                accelByteUserId = "accel",
                playerID = "id",
                isConnected = true,
                turnActive = true
            )
        )

        player.resources.add(Resource(name = "DiplomaticToken"))

        val adjustments = listOf(
            ResourceAdjustment(resourceName = "Warship", action = ResourceAction.GRANT),
            ResourceAdjustment(resourceName = "DiplomaticToken", action = ResourceAction.DESTROY)
        )

        runBlocking {
            WorldManager.applyResourceAdjustments(
                playerName = player.name,
                adjustments = adjustments,
                turnNumber = 1,
                timestampMillis = 2000L
            )
        }

        assertTrue(player.resources.any { it.name == "Warship" })
        assertFalse(player.resources.any { it.name.equals("DiplomaticToken", ignoreCase = true) })

        val eventTypes = WorldManager.actionHistoryLog.map { it.event.eventType }
        assertTrue(eventTypes.contains(GameEventType.RESOURCE_GRANT))
        assertTrue(eventTypes.contains(GameEventType.RESOURCE))
    }
}