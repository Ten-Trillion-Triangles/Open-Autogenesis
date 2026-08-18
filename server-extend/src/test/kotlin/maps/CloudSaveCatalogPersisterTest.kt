package maps

import account.FakeVirtualFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.server.vfs.VirtualFileSystem
import structs.accelbyte.cloudsave.CloudPlayerMapEntry
import structs.accelbyte.cloudsave.CloudPlayerMaps
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the production [CloudSaveCatalogPersister]
 * (bug fix 2026-08-14).
 *
 * Pre-fix: only [NoOpCataloguePersister] and [InMemoryCatalogPersister]
 * existed. The production wiring defaulted to [NoOpCataloguePersister],
 * so on every server-extend restart the catalogue vanished even though
 * the map-pack bytes were still on AGS. The operator's bug report:
 *   "Still maps pull down after server restart."
 *
 * Post-fix: [CloudSaveCatalogPersister] is the production persister,
 * backed by [VirtualFileSystemManager] which proxies to AGS Player
 * Records. The catalogue is stored under the key `cloud-player-maps`.
 *
 * Test contract:
 *   - `write(userId, cat)` writes a JSON Player Record at
 *     `cloud-player-maps` whose envelope is `{ value: <catalogue json> }`.
 *   - `read(userId)` returns the [CloudPlayerMaps] decoded from the
 *     envelope.
 *   - Round-trip: write 3 entries, read them back, all 3 are byte-equal.
 *   - On missing record, read returns empty [CloudPlayerMaps].
 *   - Read/write are routed through the per-user VFS (so each user
 *     has their own partition).
 *
 * The tests use [account.FakeVirtualFileSystem] (the test seam used by
 * the other JSON-record holders — `ByoCredentialStore`,
 * `ByoCredentialsRpc`) to avoid hitting real AGS. Production code paths
 * that wire the persister get the real `VirtualFileSystemManager.forUser`.
 */
class CloudSaveCatalogPersisterTest
{
    private lateinit var fakeVfs: FakeVirtualFileSystem
    private lateinit var persister: CloudSaveCatalogPersister

    @Before fun setUp()
    {
        PlayerMapRepository.clearForTests()
        fakeVfs = FakeVirtualFileSystem()
        // One wired persister per test, with the vfsFactory rebound to
        // the test fake. Each test re-creates it so state never leaks.
        persister = CloudSaveCatalogPersister()
        setVfsFactoryForTests(persister) { userId -> fakeVfs }
    }

    @After fun tearDown()
    {
        PlayerMapRepository.clearForTests()
    }

    /**
     * Round-trip via the production persister: write a catalogue of 3
     * entries, read it back, all 3 survive. This is the core bug-fix
     * contract — saved maps must survive a server-extend restart.
     */
    @Test
    fun writeThenReadRoundtripsThreeEntries(): Unit = runBlocking {
        val userId = "user-${System.nanoTime()}"
        val original = listOf(
            CloudPlayerMapEntry(mapId = "m1", mapName = "alpha", uploadedAt = 100L, sizeBytes = 100),
            CloudPlayerMapEntry(mapId = "m2", mapName = "beta",  uploadedAt = 200L, sizeBytes = 200),
            CloudPlayerMapEntry(mapId = "m3", mapName = "gamma", uploadedAt = 300L, sizeBytes = 300)
        )
        persister.write(userId, CloudPlayerMaps(original))
        val read = persister.read(userId)
        assertEquals(3, read.maps.size, "all 3 entries must round-trip")
        assertEquals(original[0], read.maps[0], "entry[0] round-trips")
        assertEquals(original[1], read.maps[1], "entry[1] round-trips")
        assertEquals(original[2], read.maps[2], "entry[2] round-trips")
    }

    /**
     * Missing record → empty catalogue. This is the "first ever call"
     * shape — the user has never saved a map, the record doesn't exist.
     */
    @Test
    fun readReturnsEmptyForUnknownUser(): Unit = runBlocking {
        val snap = persister.read("nobody-${System.nanoTime()}")
        assertTrue(snap.maps.isEmpty(), "read for unknown user must return empty catalogue")
    }

    /**
     * Round-trip preserves the per-user partition: writing for user A
     * does not leak into user B's read.
     */
    @Test
    fun readsArePartitionedByUserId(): Unit = runBlocking {
        val userA = "userA-${System.nanoTime()}"
        val userB = "userB-${System.nanoTime()}"
        persister.write(
            userA,
            CloudPlayerMaps(listOf(CloudPlayerMapEntry("a1", "alpha", 1L, 1)))
        )
        persister.write(
            userB,
            CloudPlayerMaps(listOf(CloudPlayerMapEntry("b1", "beta", 2L, 2)))
        )
        val readA = persister.read(userA)
        val readB = persister.read(userB)
        assertEquals(1, readA.maps.size)
        assertEquals("a1", readA.maps[0].mapId)
        assertEquals(1, readB.maps.size)
        assertEquals("b1", readB.maps[0].mapId)
    }

    /**
     * The write payload is the JSON envelope `{ value: <catalogue> }`
     * that the production VFS expects — match the convention used by
     * ByoCredentialStore (which is what the production server-extend
     * uses for its other JSON Player Records).
     */
    @Test
    fun writeUsesEnvelopeConvention(): Unit = runBlocking {
        val userId = "user-${System.nanoTime()}"
        persister.write(
            userId,
            CloudPlayerMaps(listOf(CloudPlayerMapEntry("x", "y", 1L, 1)))
        )
        val stored = fakeVfs.snapshot()
        val storedEntry = stored[userId to CloudSaveCatalogPersister.CATALOGUE_RECORD_KEY]
        assertTrue(storedEntry != null, "fake VFS must have the record")
        val value = storedEntry as kotlinx.serialization.json.JsonObject
        assertTrue(
            value.containsKey("value"),
            "stored record must be wrapped in { value: ... } envelope"
        )
    }

    /**
     * Overwrite path: write twice, the second write replaces the first
     * rather than appending. The AGS PUT semantics are last-write-wins;
     * the persister must mirror that.
     */
    @Test
    fun secondWriteOverwritesFirst(): Unit = runBlocking {
        val userId = "user-${System.nanoTime()}"
        persister.write(
            userId,
            CloudPlayerMaps(listOf(CloudPlayerMapEntry("a", "alpha", 1L, 1)))
        )
        persister.write(
            userId,
            CloudPlayerMaps(listOf(
                CloudPlayerMapEntry("b", "beta", 2L, 2),
                CloudPlayerMapEntry("c", "gamma", 3L, 3)
            ))
        )
        val read = persister.read(userId)
        assertEquals(2, read.maps.size)
        assertEquals("b", read.maps[0].mapId)
        assertEquals("c", read.maps[1].mapId)
    }

    /**
     * Persister wired into the production repository: save through the
     * repository, restart the in-memory cache by clearing, then rehydrate
     * and verify the entries come back. This is the operator's exact
     * scenario: "maps vanish on restart".
     */
    @Test
    fun repositoryRehydrateRestoresFromCloudSaveCatalogPersister(): Unit = runBlocking {
        PlayerMapRepository.setPersister(persister)

        val userId = "user-${System.nanoTime()}"
        PlayerMapRepository.addEntry(
            userId = userId,
            mapId = "saved1",
            mapName = "first-save",
            sizeBytes = 500
        )
        PlayerMapRepository.addEntry(
            userId = userId,
            mapId = "saved2",
            mapName = "second-save",
            sizeBytes = 750
        )
        assertEquals(
            2, PlayerMapRepository.listEntries(userId).size,
            "precondition: 2 entries in cache"
        )

        // Simulate restart: clear the in-memory cache, leave the
        // persister holding the data.
        PlayerMapRepository.clearForTests()
        PlayerMapRepository.setPersister(persister)
        assertEquals(
            0, PlayerMapRepository.listEntries(userId).size,
            "post-clear: cache is empty"
        )

        // Rehydrate from the production persister.
        PlayerMapRepository.rehydrate(userId)
        val after = PlayerMapRepository.listEntries(userId)
        assertEquals(
            2, after.size,
            "post-restart: both saved maps must pull back from the production persister"
        )
        val names = after.map { it.mapName }.toSet()
        assertTrue("first-save" in names, "first save must be present")
        assertTrue("second-save" in names, "second save must be present")
    }

    // ------------------------------------------------------------------
    // Test-only accessors for the internal vfsFactory (the production
    // code uses VirtualFileSystemManager.forUser). We can't reach
    // internal fields from a different package, so we use Java
    // reflection from the same package.
    // ------------------------------------------------------------------

    private fun setVfsFactoryForTests(
        persister: CloudSaveCatalogPersister,
        factory: (String) -> VirtualFileSystem
    )
    {
        val field = CloudSaveCatalogPersister::class.java.getDeclaredField("vfsFactory")
        field.isAccessible = true
        field.set(persister, factory)
    }
}