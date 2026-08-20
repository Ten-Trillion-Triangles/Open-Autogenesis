package maps

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import structs.accelbyte.cloudsave.CloudPlayerMapEntry
import structs.accelbyte.cloudsave.CloudPlayerMaps
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [CataloguePersister] — verifies the round-trip of per-player
 * map catalogues through the test-seam [InMemoryCatalogPersister]. The
 * production [CloudSaveCatalogPersister] is a follow-up task that depends on
 * the JSON game-record proxy being available (separate feature).
 *
 * Tests use [PlayerMapRepository.clearForTests] between cases to keep state
 * isolated — the persister is process-wide state.
 */
class CataloguePersisterTest
{
    @Before fun setUp() { PlayerMapRepository.clearForTests() }
    @After fun tearDown() { PlayerMapRepository.clearForTests() }

    @Test
    fun writeThenReadRoundtripsEntries(): Unit = runBlocking {
        val persister = InMemoryCatalogPersister()
        val userId = "user-${System.nanoTime()}"
        val entry = CloudPlayerMapEntry(mapId = "abc", mapName = "level1", uploadedAt = 100L, sizeBytes = 1024)
        persister.write(userId, CloudPlayerMaps(listOf(entry)))
        val read = persister.read(userId)
        assertEquals(1, read.maps.size)
        assertEquals(entry, read.maps[0])
    }

    @Test
    fun readReturnsEmptyWrapperForUnknownUser(): Unit = runBlocking {
        val persister = InMemoryCatalogPersister()
        val snap = persister.read("nobody-${System.nanoTime()}")
        assertTrue(snap.maps.isEmpty(), "read for unknown user must return empty catalogue")
    }

    @Test
    fun writeOverwritesPriorCatalogue(): Unit = runBlocking {
        val persister = InMemoryCatalogPersister()
        val userId = "user-${System.nanoTime()}"
        val v1 = CloudPlayerMaps(listOf(CloudPlayerMapEntry("a", "alpha", 100L, 100)))
        val v2 = CloudPlayerMaps(listOf(
            CloudPlayerMapEntry("b", "beta", 200L, 200),
            CloudPlayerMapEntry("c", "gamma", 300L, 300)
        ))
        persister.write(userId, v1)
        persister.write(userId, v2)
        val read = persister.read(userId)
        assertEquals(2, read.maps.size)
        assertEquals("b", read.maps[0].mapId)
        assertEquals("c", read.maps[1].mapId)
    }

    @Test
    fun noOpCataloguePersisterAlwaysReturnsEmpty(): Unit = runBlocking {
        val noOp = NoOpCataloguePersister
        noOp.write("any-user", CloudPlayerMaps(listOf(CloudPlayerMapEntry("x", "y", 1L, 1))))
        val snap = noOp.read("any-user")
        assertTrue(snap.maps.isEmpty(), "no-op persister must never persist anything")
    }
}
