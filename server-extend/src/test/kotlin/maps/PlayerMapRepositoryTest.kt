package maps

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [PlayerMapRepository] — verifies the synchronous per-player
 * map catalogue contract. The repository is the authoritative cache; the
 * catalogue-persistence write-through is wired separately in Task 3.5.
 *
 * Tests use [PlayerMapRepository.clearForTests] to reset state between
 * cases — the repository is a JVM singleton, so test isolation matters.
 */
class PlayerMapRepositoryTest
{
    @Before fun setUp() { PlayerMapRepository.clearForTests() }
    @After fun tearDown() { PlayerMapRepository.clearForTests() }

    @Test
    fun addEntryThenGetEntryRoundtrips(): Unit = runBlocking {
        PlayerMapRepository.addEntry(
            userId = "user-1",
            mapId = "abc",
            mapName = "level1",
            sizeBytes = 1024
        )
        val entry = PlayerMapRepository.getEntry("user-1", "abc")
        assertNotNull(entry, "entry should be retrievable after addEntry")
        assertEquals("level1", entry.mapName)
        assertEquals(1024, entry.sizeBytes)
        assertTrue(entry.uploadedAt > 0L, "uploadedAt should default to current epoch millis")
    }

    @Test
    fun addEntryOverwritesExistingEntryForSameKey(): Unit = runBlocking {
        PlayerMapRepository.addEntry("user-1", "abc", "old-name", 100)
        PlayerMapRepository.addEntry("user-1", "abc", "new-name", 200)
        val entry = PlayerMapRepository.getEntry("user-1", "abc")
        assertEquals("new-name", entry?.mapName)
        assertEquals(200, entry?.sizeBytes)
    }

    @Test
    fun listEntriesReturnsAllForUserInInsertionOrder(): Unit = runBlocking {
        PlayerMapRepository.addEntry("user-1", "a", "alpha", 100)
        PlayerMapRepository.addEntry("user-1", "b", "beta", 200)
        PlayerMapRepository.addEntry("user-2", "c", "gamma", 300)
        val user1Maps = PlayerMapRepository.listEntries("user-1")
        assertEquals(2, user1Maps.size)
        assertEquals("a", user1Maps[0].mapId)
        assertEquals("b", user1Maps[1].mapId)
    }

    @Test
    fun listEntriesReturnsEmptyForUnknownUser(): Unit = runBlocking {
        val entries = PlayerMapRepository.listEntries("nobody-${System.nanoTime()}")
        assertTrue(entries.isEmpty())
    }

    @Test
    fun removeEntryReturnsTrueWhenPresent(): Unit = runBlocking {
        PlayerMapRepository.addEntry("user-1", "abc", "alpha", 100)
        assertTrue(PlayerMapRepository.removeEntry("user-1", "abc"))
        assertNull(PlayerMapRepository.getEntry("user-1", "abc"))
    }

    @Test
    fun removeEntryReturnsFalseWhenAbsent(): Unit = runBlocking {
        assertFalse(PlayerMapRepository.removeEntry("user-1", "never-existed"))
    }

    @Test
    fun snapshotForUserReturnsCloudPlayerMapsWrapper(): Unit = runBlocking {
        PlayerMapRepository.addEntry("user-1", "a", "alpha", 100)
        PlayerMapRepository.addEntry("user-1", "b", "beta", 200)
        val snap = PlayerMapRepository.snapshotForUser("user-1")
        assertEquals(2, snap.maps.size)
        assertEquals("alpha", snap.maps[0].mapName)
        assertEquals("beta", snap.maps[1].mapName)
    }

    @Test
    fun snapshotForUserReturnsEmptyWrapperForUnknownUser(): Unit = runBlocking {
        val snap = PlayerMapRepository.snapshotForUser("nobody-${System.nanoTime()}")
        assertTrue(snap.maps.isEmpty())
    }

    @Test
    fun clearForTestsRemovesEverything(): Unit = runBlocking {
        PlayerMapRepository.addEntry("user-1", "a", "alpha", 100)
        PlayerMapRepository.addEntry("user-2", "b", "beta", 200)
        PlayerMapRepository.clearForTests()
        assertTrue(PlayerMapRepository.listEntries("user-1").isEmpty())
        assertTrue(PlayerMapRepository.listEntries("user-2").isEmpty())
    }

    @Test
    fun findByNameReturnsNullForEmptyCatalogue()
    {
        val hit = PlayerMapRepository.findByName("user-1", "Arctica")
        assertNull(hit, "Empty catalogue must return null")
    }

    @Test
    fun findByNameMatchesExactCaseAndTrims(): Unit = runBlocking {
        PlayerMapRepository.addEntry("user-1", "id-1", "Arctica", 100)
        assertEquals("id-1", PlayerMapRepository.findByName("user-1", "Arctica")?.mapId)
        assertEquals("id-1", PlayerMapRepository.findByName("user-1", "  Arctica  ")?.mapId)
        assertEquals("id-1", PlayerMapRepository.findByName("user-1", "arctica")?.mapId)
        assertEquals("id-1", PlayerMapRepository.findByName("user-1", "ARCTICA")?.mapId)
    }

    @Test
    fun findByNameScopesByUser(): Unit = runBlocking {
        PlayerMapRepository.addEntry("user-1", "id-1", "Arctica", 100)
        PlayerMapRepository.addEntry("user-2", "id-2", "Arctica", 100)
        assertEquals("id-1", PlayerMapRepository.findByName("user-1", "Arctica")?.mapId)
        assertEquals("id-2", PlayerMapRepository.findByName("user-2", "Arctica")?.mapId)
    }

    @Test
    fun findByNameReturnsNullWhenNameDoesNotMatch(): Unit = runBlocking {
        PlayerMapRepository.addEntry("user-1", "id-1", "Arctica", 100)
        assertNull(PlayerMapRepository.findByName("user-1", "Greenland"))
    }
}
