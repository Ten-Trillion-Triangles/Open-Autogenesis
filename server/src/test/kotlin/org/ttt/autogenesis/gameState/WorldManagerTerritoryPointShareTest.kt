package org.ttt.autogenesis.gameState

import gameState.WorldManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import structs.Npc
import structs.Player
import structs.Territory
import structs.World

class WorldManagerTerritoryPointShareTest
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
    fun ownerShare_excludesDestroyedTerritories()
    {
        WorldManager.world.mapTiles.addAll(
            mutableListOf(
                Territory(name = "A", ruler = "Commander", pointValue = 10, isDestroyed = false),
                Territory(name = "B", ruler = "Commander", pointValue = 90, isDestroyed = true),
                Territory(name = "C", ruler = "Rival", pointValue = 30, isDestroyed = false)
            )
        )

        val share = WorldManager.getOwnerTerritoryPointSharePercent("Commander")
        assertEquals(25.0, share)
    }

    @Test
    fun ownerShare_usesRulerFromMapTiles()
    {
        val player = Player(name = "Commander")
        val npc = Npc(name = "Rival")
        val mapTile = Territory(name = "Citadel", ruler = npc.name, pointValue = 20, isDestroyed = false)
        val staleCaptured = Territory(name = "Citadel", ruler = player.name, pointValue = 20, isDestroyed = false)

        WorldManager.world.activePlayers.add(player)
        WorldManager.world.npc.add(npc)
        WorldManager.world.mapTiles.add(mapTile)
        player.capturedTerritory.add(staleCaptured)

        val commanderShare = WorldManager.getOwnerTerritoryPointSharePercent(player.name)
        val rivalShare = WorldManager.getOwnerTerritoryPointSharePercent(npc.name)

        assertEquals(0.0, commanderShare)
        assertEquals(100.0, rivalShare)
    }

    @Test
    fun ownerShare_isCaseInsensitiveForOwnerName()
    {
        WorldManager.world.mapTiles.addAll(
            mutableListOf(
                Territory(name = "A", ruler = "CoMmAnDeR", pointValue = 10, isDestroyed = false),
                Territory(name = "B", ruler = "Rival", pointValue = 10, isDestroyed = false)
            )
        )

        val share = WorldManager.getOwnerTerritoryPointSharePercent("commander")
        assertEquals(50.0, share)
    }

    @Test
    fun hasOwnerShare_returnsTrueWhenEqualToThreshold()
    {
        WorldManager.world.mapTiles.addAll(
            mutableListOf(
                Territory(name = "A", ruler = "Commander", pointValue = 10, isDestroyed = false),
                Territory(name = "B", ruler = "Rival", pointValue = 30, isDestroyed = false)
            )
        )

        assertTrue(WorldManager.hasOwnerTerritoryPointShare("Commander", 25.0))
        assertFalse(WorldManager.hasOwnerTerritoryPointShare("Commander", 25.1))
    }

    @Test
    fun hasOwnerShare_clampsThresholdBelowZeroAndAboveHundred()
    {
        WorldManager.world.mapTiles.addAll(
            mutableListOf(
                Territory(name = "A", ruler = "Commander", pointValue = 20, isDestroyed = false),
                Territory(name = "B", ruler = "Rival", pointValue = 80, isDestroyed = false)
            )
        )

        assertTrue(WorldManager.hasOwnerTerritoryPointShare("Commander", -10.0))
        assertFalse(WorldManager.hasOwnerTerritoryPointShare("Commander", 130.0))
    }

    @Test
    fun zeroActivePointTotal_returnsZeroPercent_andBooleanFalse()
    {
        WorldManager.world.mapTiles.addAll(
            mutableListOf(
                Territory(name = "A", ruler = "Commander", pointValue = 50, isDestroyed = true),
                Territory(name = "B", ruler = "Rival", pointValue = 50, isDestroyed = true)
            )
        )

        val share = WorldManager.getOwnerTerritoryPointSharePercent("Commander")

        assertEquals(0.0, share)
        assertFalse(WorldManager.hasOwnerTerritoryPointShare("Commander", 0.0))
        assertFalse(WorldManager.hasOwnerTerritoryPointShare("Commander", 50.0))
    }

    @Test
    fun ownerNotFoundOrNoOwnedTerritories_returnsZeroPercent_andFalse()
    {
        WorldManager.world.mapTiles.addAll(
            mutableListOf(
                Territory(name = "A", ruler = "Rival", pointValue = 10, isDestroyed = false),
                Territory(name = "B", ruler = "Rival", pointValue = 10, isDestroyed = false)
            )
        )

        val share = WorldManager.getOwnerTerritoryPointSharePercent("UnknownOwner")
        assertEquals(0.0, share)
        assertFalse(WorldManager.hasOwnerTerritoryPointShare("UnknownOwner", 1.0))
    }

    @Test
    fun destroyedTerritoryPercent_countsDestroyedTiles()
    {
        WorldManager.world.mapTiles.addAll(
            mutableListOf(
                Territory(name = "A", isDestroyed = true),
                Territory(name = "B", isDestroyed = true),
                Territory(name = "C", isDestroyed = false),
                Territory(name = "D", isDestroyed = false)
            )
        )

        val percent = WorldManager.getDestroyedTerritoryPercent()
        assertEquals(50.0, percent)
    }

    @Test
    fun hasDestroyedTerritoryShare_returnsTrueWhenEqualToThreshold()
    {
        WorldManager.world.mapTiles.addAll(
            mutableListOf(
                Territory(name = "A", isDestroyed = true),
                Territory(name = "B", isDestroyed = false),
                Territory(name = "C", isDestroyed = false),
                Territory(name = "D", isDestroyed = false)
            )
        )

        assertTrue(WorldManager.hasDestroyedTerritoryShare(25.0))
        assertFalse(WorldManager.hasDestroyedTerritoryShare(25.1))
    }

    @Test
    fun hasDestroyedTerritoryShare_clampsThresholdBelowZeroAndAboveHundred()
    {
        WorldManager.world.mapTiles.addAll(
            mutableListOf(
                Territory(name = "A", isDestroyed = true),
                Territory(name = "B", isDestroyed = false)
            )
        )

        assertTrue(WorldManager.hasDestroyedTerritoryShare(-5.0))
        assertFalse(WorldManager.hasDestroyedTerritoryShare(150.0))
    }

    @Test
    fun destroyedTerritoryShare_withNoTerritories_returnsZeroAndFalse()
    {
        val percent = WorldManager.getDestroyedTerritoryPercent()
        assertEquals(0.0, percent)
        assertFalse(WorldManager.hasDestroyedTerritoryShare(0.0))
        assertFalse(WorldManager.hasDestroyedTerritoryShare(50.0))
    }

    @Test
    fun ownerCountShare_excludesDestroyedTerritories()
    {
        WorldManager.world.mapTiles.addAll(
            mutableListOf(
                Territory(name = "A", ruler = "Commander", pointValue = 5, isDestroyed = false),
                Territory(name = "B", ruler = "Commander", pointValue = 5, isDestroyed = true),
                Territory(name = "C", ruler = "Rival", pointValue = 5, isDestroyed = false)
            )
        )

        // Commander owns 1 of 2 active tiles (B is destroyed and excluded) → 50.0%
        assertEquals(50.0, WorldManager.getOwnerTerritoryCountSharePercent("Commander"))
    }

    @Test
    fun ownerCountShare_isCaseInsensitiveForOwnerName()
    {
        WorldManager.world.mapTiles.addAll(
            mutableListOf(
                Territory(name = "A", ruler = "CoMmAnDeR", pointValue = 10, isDestroyed = false),
                Territory(name = "B", ruler = "Rival", pointValue = 10, isDestroyed = false)
            )
        )

        assertEquals(50.0, WorldManager.getOwnerTerritoryCountSharePercent("commander"))
    }

    @Test
    fun hasOwnerCountShare_returnsTrueWhenEqualToThreshold()
    {
        WorldManager.world.mapTiles.addAll(
            mutableListOf(
                Territory(name = "A", ruler = "Commander", pointValue = 10, isDestroyed = false),
                Territory(name = "B", ruler = "Commander", pointValue = 10, isDestroyed = false),
                Territory(name = "C", ruler = "Rival", pointValue = 10, isDestroyed = false),
                Territory(name = "D", ruler = "Rival", pointValue = 10, isDestroyed = false)
            )
        )

        // Commander owns 2 of 4 = 50%
        assertTrue(WorldManager.hasOwnerTerritoryCountShare("Commander", 50.0))
        assertFalse(WorldManager.hasOwnerTerritoryCountShare("Commander", 50.1))
    }

    @Test
    fun hasOwnerCountShare_clampsThresholdBelowZeroAndAboveHundred()
    {
        WorldManager.world.mapTiles.addAll(
            mutableListOf(
                Territory(name = "A", ruler = "Commander", pointValue = 5, isDestroyed = false),
                Territory(name = "B", ruler = "Rival", pointValue = 95, isDestroyed = false)
            )
        )

        assertTrue(WorldManager.hasOwnerTerritoryCountShare("Commander", -10.0))
        assertFalse(WorldManager.hasOwnerTerritoryCountShare("Commander", 130.0))
    }

    @Test
    fun ownerCountShare_zeroActiveTiles_returnsZeroPercent_andBooleanFalse()
    {
        WorldManager.world.mapTiles.addAll(
            mutableListOf(
                Territory(name = "A", ruler = "Commander", pointValue = 50, isDestroyed = true),
                Territory(name = "B", ruler = "Rival", pointValue = 50, isDestroyed = true)
            )
        )

        val share = WorldManager.getOwnerTerritoryCountSharePercent("Commander")
        assertEquals(0.0, share)
        assertFalse(WorldManager.hasOwnerTerritoryCountShare("Commander", 0.0))
        assertFalse(WorldManager.hasOwnerTerritoryCountShare("Commander", 50.0))
    }
}
