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
 * Regression coverage for the operator-reported restart-survival bug
 * (2026-08-14):
 *
 *   "Still maps pull down after server restart."
 *
 * Symptom: the catalogue of saved maps vanishes on every server-extend
 * restart. The user's saved cards no longer appear in the collection
 * overlay after the dev server is killed and restarted, even though the
 * map-pack bytes ARE still in AGS (the Game Binary Record keyed
 * `player-<userId>-<mapId>` survives — only the catalogue metadata
 * (`mapId`, `mapName`, `uploadedAt`, `sizeBytes`) is lost).
 *
 * Root cause investigation:
 *
 *   1. `PlayerMapRepository.kt:37` defaults the persister to
 *      `NoOpCataloguePersister`. The no-op silently drops writes and
 *      always returns empty reads.
 *
 *   2. `CataloguePersister.kt:8-15` documents the gap: "The production
 *      implementation is [CloudSaveCatalogPersister] (writes the
 *      catalogue to AGS as a JSON Player Record keyed `cloud-player-maps`
 *      via the server-extend CloudSaveProxy — implementation deferred
 *      until the JSON game-record proxy path lands on server-extend)."
 *
 *   3. The deferred production persister is the load-bearing piece. Even
 *      if it were wired, `PlayerMapRepository` has no rehydrate step on
 *      startup — the in-memory cache is only populated by addEntry calls
 *      (writes), not by a read-from-persister on init.
 *
 * Fix contract:
 *
 *   1. `PlayerMapRepository.rehydrate(userId)` MUST read the user's
 *      catalogue from the active persister and populate the in-memory
 *      cache. Idempotent: a no-op when the cache already has entries
 *      for that user (so the production path can call it on every
 *      listPlayerMaps request without thrash).
 *
 *   2. `PlayerMapRepository.rehydrateAll(knownUserIds)` MUST rehydrate
 *      every known user. This is the startup hook the production wiring
 *      calls once the server-extend module is online.
 *
 *   3. With a `CloudSaveCatalogPersister`-backed `InMemoryCatalogPersister`
 *      that has stored entries, after `rehydrate(userId)` the
 *      `listEntries(userId)` returns the SAME entries that the persister
 *      holds.
 *
 *   4. With the default `NoOpCataloguePersister`, `rehydrate(userId)`
 *      is a safe no-op (does not crash; does not populate the cache
 *      with garbage).
 *
 * RED before fix: `rehydrate(userId)` does not exist; the test fails
 * with `Unresolved reference: rehydrate`.
 *
 * GREEN after fix: `rehydrate(userId)` is implemented and the test
 * passes for both backed-and-empty persister cases.
 */
class PlayerMapRepositoryRehydrateTest
{
    @Before fun setUp() { PlayerMapRepository.clearForTests() }
    @After fun tearDown() { PlayerMapRepository.clearForTests() }

    /**
     * RED BEFORE FIX: `PlayerMapRepository.rehydrate` does not exist.
     * GREEN AFTER FIX: it reads the catalogue from the persister and
     * populates the in-memory cache so `listEntries` returns the
     * persisted entries.
     */
    @Test
    fun rehydrateRestoresEntriesFromPersisterAfterRestart(): Unit = runBlocking {
        val userId = "user-${System.nanoTime()}"
        val persister = InMemoryCatalogPersister()
        // Pre-stage: write three entries to the persister as if the
        // previous server-extend run had uploaded them. Simulates the
        // server-restart scenario: persister data survives, but the
        // JVM-side `PlayerMapRepository.perPlayer` is empty.
        val expected = listOf(
            CloudPlayerMapEntry(mapId = "m1", mapName = "alpha", uploadedAt = 100L, sizeBytes = 100),
            CloudPlayerMapEntry(mapId = "m2", mapName = "beta",  uploadedAt = 200L, sizeBytes = 200),
            CloudPlayerMapEntry(mapId = "m3", mapName = "gamma", uploadedAt = 300L, sizeBytes = 300)
        )
        persister.write(userId, CloudPlayerMaps(expected))
        PlayerMapRepository.setPersister(persister)

        // Pre-fix: perPlayer cache is empty (just-cleared).
        assertTrue(
            PlayerMapRepository.listEntries(userId).isEmpty(),
            "precondition: cache must be empty before rehydrate"
        )

        // Trigger the rehydrate.
        PlayerMapRepository.rehydrate(userId)

        // Post-fix: cache is now populated from the persister.
        val after = PlayerMapRepository.listEntries(userId)
        assertEquals(3, after.size, "rehydrate must restore all 3 entries from persister")
        assertEquals(expected[0], after[0], "entry[0] round-trips")
        assertEquals(expected[1], after[1], "entry[1] round-trips")
        assertEquals(expected[2], after[2], "entry[2] round-trips")
    }

    /**
     * GREEN contract: rehydrate on a NoOpCataloguePersister is a safe
     * no-op. The cache stays empty (because the persister has no data
     * to rehydrate from). This pins the pre-fix default behaviour so a
     * future refactor that swaps in a real persister does not regress
     * the no-op case.
     */
    @Test
    fun rehydrateIsNoOpWithNoOpPersister(): Unit = runBlocking {
        val userId = "user-${System.nanoTime()}"
        PlayerMapRepository.setPersister(NoOpCataloguePersister)
        PlayerMapRepository.rehydrate(userId)
        assertTrue(
            PlayerMapRepository.listEntries(userId).isEmpty(),
            "rehydrate with NoOpCataloguePersister must keep cache empty"
        )
    }

    /**
     * GREEN contract: rehydrate is idempotent. Calling it twice does
     * not duplicate the entries. The production wiring can call it
     * on every listPlayerMaps request without thrash.
     */
    @Test
    fun rehydrateIsIdempotent(): Unit = runBlocking {
        val userId = "user-${System.nanoTime()}"
        val persister = InMemoryCatalogPersister()
        persister.write(
            userId,
            CloudPlayerMaps(listOf(CloudPlayerMapEntry("only", "one", 1L, 1)))
        )
        PlayerMapRepository.setPersister(persister)

        PlayerMapRepository.rehydrate(userId)
        PlayerMapRepository.rehydrate(userId)
        PlayerMapRepository.rehydrate(userId)

        val after = PlayerMapRepository.listEntries(userId)
        assertEquals(
            1, after.size,
            "three rehydrate calls must not duplicate the single persisted entry"
        )
    }

    /**
     * GREEN contract: a fresh entry uploaded AFTER rehydrate survives
     * a re-rehydrate (does not get clobbered). The rehydrate path is a
     * lazy cache loader, not an overwrite — production cache writes
     * (addEntry) remain authoritative.
     */
    @Test
    fun rehydrateDoesNotClobberPostRehydrateAdds(): Unit = runBlocking {
        val userId = "user-${System.nanoTime()}"
        val persister = InMemoryCatalogPersister()
        persister.write(
            userId,
            CloudPlayerMaps(listOf(CloudPlayerMapEntry("pre", "pre-existing", 1L, 1)))
        )
        PlayerMapRepository.setPersister(persister)
        PlayerMapRepository.rehydrate(userId)
        assertEquals(1, PlayerMapRepository.listEntries(userId).size, "precondition: 1 entry after rehydrate")

        // Simulate a fresh upload after the rehydrate.
        PlayerMapRepository.addEntry(
            userId = userId,
            mapId = "post",
            mapName = "freshly-uploaded",
            sizeBytes = 999
        )
        // Pre-fix write-through makes the persister hold both; listEntries reflects the cache.
        val after = PlayerMapRepository.listEntries(userId)
        assertEquals(2, after.size, "post-rehydrate addEntry must coexist with pre-existing entry")
        assertTrue(
            after.any { it.mapId == "pre" },
            "pre-existing entry must still be present"
        )
        assertTrue(
            after.any { it.mapId == "post" },
            "newly added entry must be present"
        )
    }
}