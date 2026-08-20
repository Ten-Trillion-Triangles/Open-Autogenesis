package org.ttt.autogenesis.gameInit

import gameInit.MapSelectionService
import org.ttt.autogenesis.server.maps.MapResourceRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the single-player resume path's map pack resolution.
 *
 * BUG #3 in production: [gameState.WorldManager.loadMapFromResources] tags
 * packaged map packs with a `resource:` URI prefix so the loader can tell
 * packaged maps from uploaded ones. But [MapSelectionService.loadBytesByName]
 * (used by [gameState.WorldManager.applyGameSnapshot] to re-resolve the
 * bytes after a rehydrate) was treating the saved name as a verbatim
 * classpath resource path — so a saved session that originally loaded
 * `maps/IO-map.map` would now look for `resource:maps/IO-map.map` and
 * silently return `null`. The fix strips the `resource:` prefix before
 * the classpath lookup. These tests pin the new contract.
 */
class MapSelectionServiceTest
{
    @Test
    fun loadBytesByName_stripsResourcePrefix_andResolvesPackagedMap()
    {
        val packagedMaps = MapResourceRegistry.listPackagedMaps()
        assertTrue(packagedMaps.isNotEmpty(), "test pre-condition: at least one packaged map must be available")
        val firstMap = packagedMaps.first()
        val expectedBytes = MapSelectionService.loadBytesByName(firstMap)
        assertNotNull(expectedBytes, "bare-name lookup should resolve a packaged map; got null for $firstMap")
        assertTrue(expectedBytes.isNotEmpty(), "bare-name lookup should return non-empty bytes for $firstMap")

        val prefixedBytes = MapSelectionService.loadBytesByName("resource:$firstMap")
        assertNotNull(prefixedBytes, "loadBytesByName should strip the resource: prefix and resolve the same map")
        assertEquals(
            expectedBytes.toList(),
            prefixedBytes.toList(),
            "loadBytesByName should return identical bytes whether the resource: prefix is present or not"
        )
    }

    @Test
    fun loadBytesByName_withBareName_stillResolvesPackagedMap()
    {
        val packagedMaps = MapResourceRegistry.listPackagedMaps()
        assertTrue(packagedMaps.isNotEmpty(), "test pre-condition: at least one packaged map must be available")
        val firstMap = packagedMaps.first()

        val bytes = MapSelectionService.loadBytesByName(firstMap)
        assertNotNull(bytes, "bare-name lookup should resolve a packaged map")
        assertTrue(bytes.isNotEmpty(), "bare-name lookup should return non-empty bytes")
    }

    @Test
    fun loadBytesByName_withResourcePrefix_onNonExistentMap_returnsNull()
    {
        val prefixedMissing = "resource:maps/this-map-does-not-exist-${System.nanoTime()}.map"
        val bytes = MapSelectionService.loadBytesByName(prefixedMissing)
        assertNull(bytes, "loadBytesByName with a resource: prefix should still return null for a missing map")
    }
}