package org.ttt.autogenesis.server.maps

import kotlin.test.Test
import kotlin.test.assertTrue

class MapResourceRegistryTest
{
    @Test
    fun listPackagedMaps_includesStartMap()
    {
        val maps = MapResourceRegistry.listPackagedMaps()
        assertTrue(maps.isNotEmpty(), "Expected at least one packaged map resource")
        assertTrue(
            maps.any { it.equals("maps/IO-map.map", ignoreCase = true) },
            "Expected maps/IO-map.map to appear in the discovered list"
        )
    }
}
