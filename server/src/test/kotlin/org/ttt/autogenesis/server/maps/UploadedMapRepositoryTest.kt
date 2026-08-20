package org.ttt.autogenesis.server.maps

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UploadedMapRepositoryTest
{
    @BeforeTest
    @AfterTest
    fun resetRepository()
    {
        UploadedMapRepository.clear()
    }

    @Test
    fun registerAndList_returnsMetadata()
    {
        val firstBytes = byteArrayOf(1, 2, 3)
        val secondBytes = byteArrayOf(4, 5, 6, 7)

        val first = UploadedMapRepository.registerMap("First", "conn-1", firstBytes)
        val second = UploadedMapRepository.registerMap(null, "conn-2", secondBytes)

        val maps = UploadedMapRepository.listUploadedMaps()

        assertEquals(2, maps.size)
        assertEquals(first.mapId, maps[0].mapId)
        assertEquals("First", maps[0].mapName)
        assertEquals("conn-1", maps[0].uploadedBy)
        assertEquals(first.sizeBytes, maps[0].sizeBytes)

        assertEquals(second.mapId, maps[1].mapId)
        assertTrue(maps[1].mapName.startsWith("uploaded-map-"))
    }

    @Test
    fun getMapBytes_returnsCopy()
    {
        val payload = byteArrayOf(10, 20)
        val metadata = UploadedMapRepository.registerMap("copy-test", null, payload)

        val stored = UploadedMapRepository.getMapBytes(metadata.mapId)
        assertNotNull(stored)
        assertEquals(2, stored.size)
        assertEquals(payload[0], stored[0])
        assertEquals(payload[1], stored[1])

        // ensure caller mutation does not affect stored bytes
        stored[0] = 99
        val reread = UploadedMapRepository.getMapBytes(metadata.mapId)
        assertEquals(10, reread?.get(0))
    }
}