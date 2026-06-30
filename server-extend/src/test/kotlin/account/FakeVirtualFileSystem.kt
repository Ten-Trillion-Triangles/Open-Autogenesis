package account

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import org.ttt.autogenesis.server.vfs.VirtualFileSystem
import org.ttt.autogenesis.server.vfs.VfsMode
import structs.accelbyte.cloudsave.BulkGameRecordResponse
import structs.accelbyte.cloudsave.BulkPlayerRecordResponse
import structs.accelbyte.cloudsave.GameRecordResponse
import structs.accelbyte.cloudsave.PlayerRecordResponse

/**
 * In-memory [VirtualFileSystem] used by the BYO store tests. Persists records in a
 * [Mutex]-guarded map keyed by `(userId, key)`; never touches the real filesystem.
 *
 * Stubs out the operations [ByoCredentialStore] actually uses (save, fetch, delete)
 * with the minimum surface the store expects, and throws for the rest so we notice
 * if a future change starts to depend on something we haven't modelled.
 */
class FakeVirtualFileSystem : VirtualFileSystem
{
    override val mode: VfsMode = VfsMode.LOCAL
    override val description: String = "FakeVFS for tests"

    private val records: MutableMap<Pair<String, String>, JsonElement> = mutableMapOf()
    private val deleted: MutableSet<Pair<String, String>> = mutableSetOf()
    private val mutex: Mutex = Mutex()

    suspend fun snapshot(): Map<Pair<String, String>, JsonElement> = mutex.withLock { records.toMap() }
    suspend fun deletedKeys(): Set<Pair<String, String>> = mutex.withLock { deleted.toSet() }

    override suspend fun saveUserRecord(userId: String, key: String, payload: JsonElement): Result<PlayerRecordResponse>
    {
        return mutex.withLock {
            records[userId to key] = payload
            deleted.remove(userId to key)
            Result.success(
                PlayerRecordResponse(key = key, userId = userId, value = payload)
            )
        }
    }

    override suspend fun saveUserRecordFromJsonString(userId: String, key: String, jsonPayload: String): Result<PlayerRecordResponse>
    {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(jsonPayload)
        return saveUserRecord(userId, key, element)
    }

    override suspend fun fetchUserRecord(userId: String, key: String): Result<PlayerRecordResponse>
    {
        return mutex.withLock {
            val value = records[userId to key]
                ?: return@withLock Result.failure<PlayerRecordResponse>(
                    NoSuchElementException("FakeVFS: no record for userId=$userId key=$key")
                )
            Result.success(PlayerRecordResponse(key = key, userId = userId, value = value))
        }
    }

    override suspend fun bulkFetchUserRecords(userId: String, keys: List<String>): Result<BulkPlayerRecordResponse>
    {
        return mutex.withLock {
            val out = keys.mapNotNull { key ->
                records[userId to key]?.let { value -> PlayerRecordResponse(key = key, userId = userId, value = value) }
            }
            Result.success(BulkPlayerRecordResponse(records = out))
        }
    }

    override suspend fun deleteUserRecord(userId: String, key: String): Result<Unit>
    {
        return mutex.withLock {
            records.remove(userId to key)
            deleted.add(userId to key)
            Result.success(Unit)
        }
    }

    // --- Operations the BYO store does not use; throw to flag unexpected dependencies. ---

    override suspend fun saveGameRecord(key: String, payload: JsonElement, updateIfExists: Boolean): Result<GameRecordResponse>
    {
        error("FakeVirtualFileSystem.saveGameRecord not implemented")
    }

    override suspend fun fetchGameRecord(key: String): Result<GameRecordResponse>
    {
        error("FakeVirtualFileSystem.fetchGameRecord not implemented")
    }

    override suspend fun bulkFetchGameRecords(keys: List<String>): Result<BulkGameRecordResponse>
    {
        error("FakeVirtualFileSystem.bulkFetchGameRecords not implemented")
    }

    override suspend fun deleteGameRecord(key: String): Result<Unit>
    {
        error("FakeVirtualFileSystem.deleteGameRecord not implemented")
    }
}
