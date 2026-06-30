package org.ttt.autogenesis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import structs.Npc
import structs.Player
import structs.Resource
import structs.Territory
import structs.World
import structs.findTerritoryByName
import structs.resolveTerritoryName

class TerritoryResolutionTest
{
    private companion object
    {
        const val TERRITORY_NAME = "North Ridge"
        const val TERRITORY_ALIAS = " north ridge "
    }

    private fun buildTerritory(name: String): Territory
    {
        return Territory(
            name = name,
            resource = Resource(name = "Stone"),
            pointValue = 3
        )
    }

    @Test
    fun resolvesTerritoryFromWorldRegardlessOfCasing()
    {
        val territory = buildTerritory(TERRITORY_NAME)
        val world = World(
            mapTiles = mutableListOf(territory)
        )

        val result = world.findTerritoryByName(TERRITORY_ALIAS)

        assertSame(territory, result)
    }

    @Test
    fun resolvesPlayerStartingTileByName()
    {
        val startingTile = buildTerritory(TERRITORY_NAME)
        val player = Player(startingTile = startingTile)

        val result = player.findTerritoryByName(TERRITORY_ALIAS)

        assertSame(startingTile, result)
    }

    @Test
    fun resolvesPlayerCapturedTerritories()
    {
        val captured = buildTerritory(TERRITORY_NAME)
        val player = Player(
            capturedTerritory = mutableListOf(captured)
        )

        val result = player.findTerritoryByName(TERRITORY_ALIAS)

        assertSame(captured, result)
    }

    @Test
    fun resolvesNpcCapturedTerritories()
    {
        val captured = buildTerritory(TERRITORY_NAME)
        val npc = Npc(
            capturedTerritory = mutableListOf(captured)
        )

        val result = npc.findTerritoryByName(TERRITORY_ALIAS)

        assertSame(captured, result)
    }

    @Test
    fun resolvesViaFuzzyHelper()
    {
        val territory = buildTerritory(TERRITORY_NAME)
        val world = World(
            mapTiles = mutableListOf(territory)
        )

        val fuzzyResult = world.resolveTerritoryName("north ridgee")

        assertSame(territory, fuzzyResult)
    }

    @Test
    fun fuzzyHelperReturnsNullWhenTooDifferent()
    {
        val territory = buildTerritory(TERRITORY_NAME)
        val world = World(
            mapTiles = mutableListOf(territory)
        )

        assertNull(world.resolveTerritoryName("mountain peak"))
    }

    @Test
    fun resolvesViaTokenFallbackWhenSuffixMisspelled()
    {
        val territory = buildTerritory("Illyrikon Regio")
        val world = World(
            mapTiles = mutableListOf(territory)
        )

        val fuzzyResult = world.resolveTerritoryName("Illyrikon Rejio")

        assertSame(territory, fuzzyResult)
    }

    @Test
    fun tokenFallbackRequiresMultipleTokens()
    {
        val territoryOne = buildTerritory("Chalybes Regio")
        val territoryTwo = buildTerritory("Media Regio")
        val world = World(
            mapTiles = mutableListOf(territoryOne, territoryTwo)
        )

        assertNull(world.resolveTerritoryName("Regio"))
    }

    @Test
    fun missingNamesReturnNull()
    {
        val territory = buildTerritory(TERRITORY_NAME)
        val world = World(
            mapTiles = mutableListOf(territory)
        )

        assertNull(world.findTerritoryByName("unknown"))
        assertNull(Player().findTerritoryByName("unknown"))
        assertNull(Npc().findTerritoryByName("unknown"))
    }
}
