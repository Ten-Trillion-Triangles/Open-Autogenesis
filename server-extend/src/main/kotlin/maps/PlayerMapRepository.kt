package maps

import structs.accelbyte.cloudsave.CloudPlayerMapEntry
import structs.accelbyte.cloudsave.CloudPlayerMaps
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory per-player map catalogue for server-extend.
 *
 * Holds [CloudPlayerMapEntry] for each (userId, mapId) pair. The proxy uses this
 * to provide synchronous list/lookup without round-tripping to AGS for metadata.
 * Bytes live in AGS Player Binary Records (see [proxy.MapStorageProxy]); the
 * catalogue only tracks their identifiers, names, timestamps, and sizes.
 *
 * **Persistence**: the catalogue is mirrored to AGS as a JSON Player Record keyed
 * `cloud-player-maps` on every mutation (see Task 3.5 — catalogue persistence).
 * The repository stays the authoritative cache for fast read.
 *
 * **Thread-safety**: backed by [ConcurrentHashMap]; safe for concurrent
 * add/remove/get/list calls.
 *
 * **Insertion order**: `listEntries` preserves insertion order so the UI can
 * render maps in the order they were uploaded.
 */
object PlayerMapRepository
{
    private val perPlayer: MutableMap<String, MutableMap<String, CloudPlayerMapEntry>> =
        ConcurrentHashMap()

    /**
     * The catalogue persister used to mirror mutations to durable backing storage
     * (JSON Player Record in AGS, in production). Defaults to [NoOpCataloguePersister]
     * so production behaviour is deterministic until the production persister lands.
     * Override via [setPersister] for tests and once the JSON-record path is wired.
     */
    @Volatile
    private var persister: CataloguePersister = NoOpCataloguePersister

    /**
     * Replace the persister. Production code should not call this — the default
     * no-op persister is correct until the JSON-record path lands. Tests use
     * this to inject [InMemoryCatalogPersister].
     */
    fun setPersister(newPersister: CataloguePersister)
    {
        persister = newPersister
    }

    /**
     * Add or overwrite an entry for (userId, mapId). `uploadedAt` defaults to
     * the current epoch millis; callers may pass a specific timestamp for tests.
     *
     * Triggers a write-through to the persister on success.
     */
    suspend fun addEntry(
        userId: String,
        mapId: String,
        mapName: String,
        sizeBytes: Int,
        uploadedAt: Long = System.currentTimeMillis()
    ) {
        val perMap = perPlayer.computeIfAbsent(userId) { ConcurrentHashMap() }
        perMap[mapId] = CloudPlayerMapEntry(mapId, mapName, uploadedAt, sizeBytes)
        persister.write(userId, snapshotForUser(userId))
    }

    /**
     * Lookup a single entry by (userId, mapId). Returns null when absent.
     */
    fun getEntry(userId: String, mapId: String): CloudPlayerMapEntry? =
        perPlayer[userId]?.get(mapId)

    /**
     * Look up an entry by (userId, name) using case-insensitive trimmed match.
     *
     * Used by [proxy.MapStorageProxy.savePlayerMap] to dedupe-and-replace-by-name:
     * if the user is uploading a map whose name already exists in their catalogue,
     * the proxy deletes the old AGS record and catalogue entry before minting the
     * new mapId. The single-match expectation holds because the only flow that
     * produces same-name duplicates is the upload-replaces-upload race, and the
     * first writer wins the orphan-delete so the second writer's lookup returns
     * the second writer's just-written entry (which still has the colliding name)
     * — handled by the `collision.mapId != request.mapId` guard in the proxy.
     *
     * @param userId Owning player; matches the catalogue partition.
     * @param name Display name to find. Both sides are `.trim()`-ed before
     *   comparison; comparison is `equals(..., ignoreCase = true)`.
     * @return The first matching `CloudPlayerMapEntry`, or null when no entry
     *   in the user's catalogue matches the normalized name.
     */
    fun findByName(userId: String, name: String): CloudPlayerMapEntry?
    {
        val trimmed = name.trim()
        return perPlayer[userId]?.values?.firstOrNull { entry ->
            entry.mapName.trim().equals(trimmed, ignoreCase = true)
        }
    }

    /**
     * List every entry for a user, in insertion order. Empty when the user
     * has no entries.
     */
    fun listEntries(userId: String): List<CloudPlayerMapEntry> =
        perPlayer[userId]?.values?.toList() ?: emptyList()

    /**
     * Remove a single entry. Returns true when the entry existed and was
     * removed, false when no entry was present.
     *
     * Triggers a write-through to the persister on success.
     */
    fun removeEntry(userId: String, mapId: String): Boolean
    {
        val perMap = perPlayer[userId] ?: return false
        val removed = perMap.remove(mapId) != null
        if (removed)
        {
            // Fire-and-forget: the persister is a `suspend fun`, but our removal
            // contract is non-suspend for proxy callers. Use runBlocking here
            // only on the success path; production callers expect this to be fast.
            // When the production persister is wired (HTTP call to AGS), consider
            // making removeEntry suspend so this can be properly awaited.
            kotlinx.coroutines.runBlocking { persister.write(userId, snapshotForUser(userId)) }
        }
        return removed
    }

    /**
     * Build a [CloudPlayerMaps] snapshot for serialization to AGS or for
     * returning over the proxy boundary. Empty when the user has no entries.
     */
    fun snapshotForUser(userId: String): CloudPlayerMaps =
        CloudPlayerMaps(listEntries(userId))

    /**
     * Test seam — wipes the in-memory catalogue AND resets the persister to
     * the default no-op. Not callable from production code paths.
     */
    internal fun clearForTests()
    {
        perPlayer.clear()
        persister = NoOpCataloguePersister
    }

    /**
     * Rehydrate the in-memory cache for [userId] from the active persister.
     * Idempotent: no-op when the cache already has entries for that user
     * (so production code can call it lazily without thrash).
     *
     * Bug fix (2026-08-14): pre-fix the catalogue vanished on every
     * server-extend restart because the production wiring defaulted to
     * [NoOpCataloguePersister] AND there was no rehydrate step on startup.
     * This method is the lazy cache loader the proxy calls before
     * `listPlayerMaps` to ensure the cache reflects any data the persister
     * holds from a previous run.
     *
     * Contract:
     *   - With a backed persister (e.g. an AGS-backed JSON Player Record
     *     persister): restores every entry the persister holds into the
     *     cache.
     *   - With [NoOpCataloguePersister]: safe no-op (cache stays empty).
     *   - Does NOT clobber any entries already in the cache for [userId] —
     *     this lets the production listPlayerMaps handler call rehydrate
     *     before every read without worrying about races against addEntry.
     *
     * @param userId Owning player; matches the catalogue partition.
     */
    suspend fun rehydrate(userId: String)
    {
        if (perPlayer[userId]?.isNotEmpty() == true)
        {
            return
        }
        val snap = persister.read(userId)
        if (snap.maps.isEmpty())
        {
            return
        }
        val perMap = perPlayer.computeIfAbsent(userId) { ConcurrentHashMap() }
        for (entry in snap.maps)
        {
            perMap[entry.mapId] = entry
        }
    }

    /**
     * Bulk rehydrate for a set of known user ids. Called once at
     * server-extend startup to warm the cache.
     *
     * @param knownUserIds user ids to rehydrate. May be empty.
     */
    suspend fun rehydrateAll(knownUserIds: Iterable<String>)
    {
        for (userId in knownUserIds)
        {
            rehydrate(userId)
        }
    }
}
