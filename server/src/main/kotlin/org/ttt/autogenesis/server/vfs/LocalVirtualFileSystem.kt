package org.ttt.autogenesis.server.vfs

import commonGlobals.VfsSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.logging.logOperationSuspend
import structs.accelbyte.cloudsave.BulkGameRecordResponse
import structs.accelbyte.cloudsave.BulkPlayerRecordResponse
import structs.accelbyte.cloudsave.GameRecordResponse
import structs.accelbyte.cloudsave.PlayerRecordResponse
import structs.accelbyte.common.AccelByteJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID

private const val LOCAL_NAMESPACE = "local-vfs"
private const val GAME_DIR_NAME = "game-records"
private const val PLAYER_DIR_NAME = "player-records"

class LocalVirtualFileSystem private constructor(private val basePath: Path) : VirtualFileSystem {
    override val mode: VfsMode = VfsMode.LOCAL
    override val description: String = "Local virtual filesystem at $basePath"
    private val gameRoot: Path = basePath.resolve(GAME_DIR_NAME)
    private val playerRoot: Path = basePath.resolve(PLAYER_DIR_NAME)

    init {
        Files.createDirectories(gameRoot)
        Files.createDirectories(playerRoot)
    }

    companion object {
        internal fun create(pathHint: String): LocalVirtualFileSystem {
            val resolved = resolvePath(pathHint)
            Files.createDirectories(resolved)
            return LocalVirtualFileSystem(resolved)
        }

        private fun resolvePath(pathHint: String): Path {
            val candidate = Paths.get(pathHint)
            return if (candidate.isAbsolute) candidate
            else Paths.get(System.getProperty("user.dir")).resolve(candidate).normalize()
        }
    }

    override suspend fun saveGameRecord(key: String, payload: JsonElement, updateIfExists: Boolean): Result<GameRecordResponse> =
        logOperationSuspend(
            LogCategory.DATABASE,
            action = "LocalVFS.saveGameRecord",
            inputs = "key=$key updateIfExists=$updateIfExists payload=${payload.safeSummary()}"
        ) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val existing = readGameRecord(key)
                    if (existing != null && !updateIfExists) {
                        error("Game record '$key' already exists in local mode and updates are disabled.")
                    }
                    val record = buildGameRecord(key, payload, existing)
                    writeRecord(gamePath(key), AccelByteJson.encodeToString(GameRecordResponse.serializer(), record))
                    record
                }
            }
        }

    override suspend fun fetchGameRecord(key: String): Result<GameRecordResponse> =
        logOperationSuspend(
            LogCategory.DATABASE,
            action = "LocalVFS.fetchGameRecord",
            inputs = "key=$key"
        ) {
            runCatching {
                withContext(Dispatchers.IO) {
                    readGameRecord(key) ?: error("Game record '$key' not found in local storage.")
                }
            }
        }

    override suspend fun bulkFetchGameRecords(keys: List<String>): Result<BulkGameRecordResponse> =
        logOperationSuspend(
            LogCategory.DATABASE,
            action = "LocalVFS.bulkFetchGameRecords",
            inputs = "keys=${keys.describeKeys()}"
        ) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val records = keys.mapNotNull { readGameRecord(it) }
                    BulkGameRecordResponse(records = records)
                }
            }
        }

    override suspend fun deleteGameRecord(key: String): Result<Unit> =
        logOperationSuspend(
            LogCategory.DATABASE,
            action = "LocalVFS.deleteGameRecord",
            inputs = "key=$key"
        ) {
            runCatching {
                withContext(Dispatchers.IO) {
                    Files.deleteIfExists(gamePath(key))
                    Unit
                }
            }
        }

    override suspend fun saveUserRecordFromJsonString(userId: String, key: String, jsonPayload: String): Result<PlayerRecordResponse> {
        return runCatching {
            // For local VFS, convert JSON string to JsonElement and use existing method
            val jsonElement = kotlinx.serialization.json.Json.parseToJsonElement(jsonPayload)
            saveUserRecord(userId, key, jsonElement).getOrThrow()
        }
    }

    override suspend fun saveUserRecord(userId: String, key: String, payload: JsonElement): Result<PlayerRecordResponse> =
        logOperationSuspend(
            LogCategory.DATABASE,
            action = "LocalVFS.saveUserRecord",
            inputs = "userId=$userId key=$key payload=${payload.safeSummary()}"
        ) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val existing = readUserRecord(userId, key)
                    val response = buildPlayerRecord(userId, key, payload, existing)
                    writeRecord(userPath(userId, key), AccelByteJson.encodeToString(PlayerRecordResponse.serializer(), response))
                    response
                }
            }
        }

    override suspend fun fetchUserRecord(userId: String, key: String): Result<PlayerRecordResponse>
    {
        // Bypassing logOperationSuspend here because first-time-visit
        // "not found" is a normal condition, not an error. We log the
        // lifecycle (start, outcome) ourselves at appropriate levels:
        //   - DEBUG on entry
        //   - INFO on success
        //   - WARN on first-time-visit not-found (caller will return
        //     a sensible default)
        //   - ERROR on unexpected IO failures
        Logger.debug(
            LogCategory.DATABASE,
            "➡ LocalVFS.fetchUserRecord [userId=$userId key=$key]"
        )
        return try
        {
            val existing = withContext(Dispatchers.IO) { readUserRecord(userId, key) }
            if (existing == null)
            {
                Logger.warn(
                    LogCategory.DATABASE,
                    "LocalVFS.fetchUserRecord: userId=$userId key=$key — record not found (first-time visit; caller should return default)"
                )
                Result.failure(RecordNotFoundException(userId, key))
            }
            else
            {
                Logger.info(
                    LogCategory.DATABASE,
                    "✅ LocalVFS.fetchUserRecord [userId=$userId key=$key]"
                )
                Result.success(existing)
            }
        }
        catch (e: Exception)
        {
            if (e is RecordNotFoundException)
            {
                // Already logged as WARN above; return the failure.
                Result.failure(e)
            }
            else
            {
                Logger.error(
                    LogCategory.DATABASE,
                    "❌ LocalVFS.fetchUserRecord [userId=$userId key=$key]: ${e.message ?: e::class.simpleName}"
                )
                Result.failure(e)
            }
        }
    }

    override suspend fun bulkFetchUserRecords(userId: String, keys: List<String>): Result<BulkPlayerRecordResponse> =
        logOperationSuspend(
            LogCategory.DATABASE,
            action = "LocalVFS.bulkFetchUserRecords",
            inputs = "userId=$userId keys=${keys.describeKeys()}"
        ) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val records = keys.mapNotNull { readUserRecord(userId, it) }
                    BulkPlayerRecordResponse(records = records)
                }
            }
        }

    override suspend fun deleteUserRecord(userId: String, key: String): Result<Unit> =
        logOperationSuspend(
            LogCategory.DATABASE,
            action = "LocalVFS.deleteUserRecord",
            inputs = "userId=$userId key=$key"
        ) {
            runCatching {
                withContext(Dispatchers.IO) {
                    Files.deleteIfExists(userPath(userId, key))
                    Unit
                }
            }
        }

    private fun gamePath(key: String): Path = gameRoot.resolve("${sanitizeKey(key)}.json")

    private fun userPath(userId: String, key: String): Path {
        val dir = playerRoot.resolve(userId)
        Files.createDirectories(dir)
        return dir.resolve("${sanitizeKey(key)}.json")
    }

    private fun sanitizeKey(key: String): String = VfsSanitizer.sanitize(key)

    private fun readGameRecord(key: String): GameRecordResponse? {
        val path = gamePath(key)
        return if (Files.exists(path)) {
            AccelByteJson.decodeFromString(GameRecordResponse.serializer(), Files.readString(path, StandardCharsets.UTF_8))
        } else null
    }

    private fun readUserRecord(userId: String, key: String): PlayerRecordResponse? {
        val path = userPath(userId, key)
        return if (Files.exists(path)) {
            AccelByteJson.decodeFromString(PlayerRecordResponse.serializer(), Files.readString(path, StandardCharsets.UTF_8))
        } else null
    }

    private fun writeRecord(path: Path, payload: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, payload, StandardCharsets.UTF_8)
    }

    private fun nowIso(): String = Instant.now().toString()

    private fun buildGameRecord(key: String, payload: JsonElement, existing: GameRecordResponse?): GameRecordResponse {
        val timestamp = nowIso()
        return GameRecordResponse(
            key = key,
            value = payload,
            tags = existing?.tags,
            setBy = "local-vfs",
            version = (existing?.version ?: 0) + 1,
            createdAt = existing?.createdAt ?: timestamp,
            updatedAt = timestamp,
            namespace = LOCAL_NAMESPACE,
            userId = existing?.userId,
            isPublicRecord = existing?.isPublicRecord,
            checksum = UUID.randomUUID().toString(),
            metadata = buildJsonObject {
                put("generatedBy", JsonPrimitive("local-vfs"))
                put("timestamp", JsonPrimitive(timestamp))
            }
        )
    }

    private fun buildPlayerRecord(
        userId: String,
        key: String,
        payload: JsonElement,
        existing: PlayerRecordResponse?
    ): PlayerRecordResponse {
        val timestamp = nowIso()
        return PlayerRecordResponse(
            key = key,
            namespace = LOCAL_NAMESPACE,
            userId = userId,
            isPublicRecord = false,
            createdAt = existing?.createdAt ?: timestamp,
            updatedAt = timestamp,
            tags = existing?.tags,
            setBy = "local-vfs",
            value = payload
        )
    }

    private fun JsonElement.safeSummary(maxLength: Int = 180): String =
        toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLength)

    private fun List<String>.describeKeys(maxEntries: Int = 6): String {
        val preview = take(maxEntries)
        val description = preview.joinToString(",")
        return if (size > maxEntries) "$description,..." else description
    }
}

/**
 * Sentinel exception for the "no record for this user yet" condition.
 * Thrown by [LocalVirtualFileSystem.fetchUserRecord] when a brand new
 * user has no entry on disk; the caller is expected to return a
 * sensible default (empty ledger, default account settings) without
 * treating the absence as an error.
 */
class RecordNotFoundException(val userId: String, val key: String) :
    RuntimeException("User record '$key' for '$userId' not found in local storage.")