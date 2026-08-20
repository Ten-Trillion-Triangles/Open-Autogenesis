package maps

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.server.vfs.VirtualFileSystem
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import structs.accelbyte.cloudsave.CloudPlayerMaps

/**
 * Persists per-player map catalogues to durable backing storage.
 *
 * The production implementation is [CloudSaveCatalogPersister] (writes the
 * catalogue to AGS as a JSON Player Record keyed `cloud-player-maps` via the
 * server-extend VirtualFileSystem which proxies to AGS Player Records).
 * [NoOpCataloguePersister] is the safe default used until the production
 * persister is wired into the server-extend startup path.
 * [InMemoryCatalogPersister] is the test seam.
 *
 * The persister is wired into [PlayerMapRepository] as a write-through on every
 * mutation (addEntry, removeEntry). Reads happen lazily — the repository is
 * the authoritative cache and rehydrates from the persister on every
 * `listPlayerMaps` request (see [PlayerMapRepository.rehydrate]).
 */
interface CataloguePersister
{
    /**
     * Read the catalogue for the given user. Returns an empty [CloudPlayerMaps]
     * when no catalogue exists for the user.
     */
    suspend fun read(userId: String): CloudPlayerMaps

    /**
     * Persist the catalogue for the given user, overwriting any prior value.
     */
    suspend fun write(userId: String, catalogue: CloudPlayerMaps)
}

/**
 * In-memory persister used by unit tests. Per-user state lives in a process-wide
 * map; cleared implicitly when the JVM restarts.
 */
class InMemoryCatalogPersister : CataloguePersister
{
    private val store: MutableMap<String, CloudPlayerMaps> = mutableMapOf()

    override suspend fun read(userId: String): CloudPlayerMaps =
        store[userId] ?: CloudPlayerMaps()

    override suspend fun write(userId: String, catalogue: CloudPlayerMaps)
    {
        store[userId] = catalogue
    }
}

/**
 * No-op persister used until the production JSON-record path is wired.
 * Writes are silently dropped; reads always return empty. Keeps production
 * behaviour deterministic while the wiring is in flight.
 */
object NoOpCataloguePersister : CataloguePersister
{
    override suspend fun read(userId: String): CloudPlayerMaps = CloudPlayerMaps()

    override suspend fun write(userId: String, catalogue: CloudPlayerMaps)
    {
        Logger.debug(
            LogCategory.DATABASE,
            "NoOpCataloguePersister: dropping write for $userId (${catalogue.maps.size} entries)"
        )
    }
}

/**
 * Production persister (bug fix 2026-08-14): persists per-player map
 * catalogues to AGS as JSON Player Records keyed [CATALOGUE_RECORD_KEY]
 * (`cloud-player-maps`). Uses [VirtualFileSystemManager.forUser] to obtain
 * the per-user VFS proxy and reads/writes through [VirtualFileSystem].
 *
 * Round-trip contract:
 *   - `write(userId, cat)` serialises [CloudPlayerMaps] to JSON, wraps it in
 *     an envelope `{ value: <serialised> }` (matching the UserRecordModels
 *     envelope used by [account.ByoCredentialStore] and other JSON record
 *     holders), and PUTs it to AGS via the VFS proxy.
 *   - `read(userId)` fetches the record, unwraps the envelope, and decodes
 *     the JSON back to [CloudPlayerMaps]. Missing or empty records return
 *     [CloudPlayerMaps] with no maps.
 *
 * Failure modes:
 *   - AGS not reachable: throws on the read/write call (the proxy layer
 *     surfaces the underlying HTTP error). The repository's `rehydrate`
 *     caller does not currently catch — production wiring should ensure
 *     the VFS is healthy before calls land. Tests inject
 *     `FakeVirtualFileSystem` so this path is never hit in unit tests.
 *
 * Wiring:
 *   - Default persister swap: call `PlayerMapRepository.setPersister(
 *     CloudSaveCatalogPersister())` from the server-extend startup path
 *     (e.g. `ServerExtend.kt` after `AccelByteConfig` initializes).
 *
 * Test seam:
 *   - The internal `vfsFactory` lets tests swap in `FakeVirtualFileSystem`.
 *   - Production code should never call `setVfsFactory` directly.
 */
class CloudSaveCatalogPersister : CataloguePersister
{
    companion object
    {
        /**
         * The AGS Player Record key the catalogue is stored under. Matches
         * the convention documented in the server-extend AGENTS.md section
         * on Map Storage.
         */
        const val CATALOGUE_RECORD_KEY: String = "cloud-player-maps"
    }

    /**
     * Factory that yields the per-user [VirtualFileSystem]. Production uses
     * [VirtualFileSystemManager.forUser]; tests inject [account.FakeVirtualFileSystem].
     */
    @Volatile
    internal var vfsFactory: (String) -> VirtualFileSystem = { userId ->
        VirtualFileSystemManager.forUser(userId)
    }

    override suspend fun read(userId: String): CloudPlayerMaps
    {
        val vfs = vfsFactory(userId)
        val result = vfs.fetchUserRecord(userId, CATALOGUE_RECORD_KEY)
        val record = result.getOrNull() ?: return CloudPlayerMaps()
        val value = record.value ?: return CloudPlayerMaps()
        // The AGS Player Record response wraps our stored JSON in an
        // outer `{"value": ...}` envelope. Our `write` adds another
        // `{"value": ...}` wrap so the JSON-envelope convention used by
        // `ByoCredentialStore` (and other JSON-record holders) is
        // preserved end-to-end. Unwrap both layers if present.
        val unwrappedOnce = unwrapEnvelope(value) ?: return CloudPlayerMaps()
        val unwrappedTwice = unwrapEnvelope(unwrappedOnce) ?: unwrappedOnce
        return try
        {
            RpcJson.decodeFromJsonElement(CloudPlayerMaps.serializer(), unwrappedTwice)
        }
        catch (err: Throwable)
        {
            Logger.warn(
                LogCategory.DATABASE,
                "CloudSaveCatalogPersister: failed to decode catalogue for user=$userId; " +
                    "returning empty. err=${err.message}"
            )
            CloudPlayerMaps()
        }
    }

    /**
     * If [node] is a JSON object with a single `value` key, return the
     * inner element. Otherwise return [node] unchanged.
     */
    private fun unwrapEnvelope(node: JsonElement): JsonElement? =
        if (node is JsonObject && node.containsKey("value"))
        {
            node["value"]
        }
        else
        {
            null
        }

    override suspend fun write(userId: String, catalogue: CloudPlayerMaps)
    {
        val vfs = vfsFactory(userId)
        val payload: JsonElement = RpcJson.encodeToJsonElement(CloudPlayerMaps.serializer(), catalogue)
        val envelope = buildJsonObject {
            put("value", payload)
        }
        val result = vfs.saveUserRecord(userId, CATALOGUE_RECORD_KEY, envelope)
        result.getOrThrow()
    }
}
