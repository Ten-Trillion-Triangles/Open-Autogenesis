package org.ttt.autogenesis.server.vfs

import com.TTT.Util.getHomeFolder
import kotlinx.serialization.json.JsonElement
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.accelbyte.cloudsave.BulkGameRecordResponse
import structs.accelbyte.cloudsave.BulkPlayerRecordResponse
import structs.accelbyte.cloudsave.GameRecordResponse
import structs.accelbyte.cloudsave.PlayerRecordResponse

/**
 * Virtual filesystem operation modes.
 */
enum class VfsMode
{
    /** Local filesystem storage */
    LOCAL,
    
    /** Cloud-based storage via AccelByte */
    CLOUD
}

/**
 * Abstract virtual filesystem that can persist game and player-specific JSON records.
 */
interface VirtualFileSystem
{
    /** The mode this filesystem operates in */
    val mode : VfsMode
    
    /** Human-readable description of this filesystem */
    val description : String

    /**
     * Saves a game record with the specified key and payload.
     * 
     * @param key Record identifier
     * @param payload JSON data to save
     * @param updateIfExists Whether to update if record already exists
     * @return Result containing the game record response
     */
    suspend fun saveGameRecord(key : String, payload : JsonElement, updateIfExists : Boolean = true) : Result<GameRecordResponse>
    
    /**
     * Fetches a game record by key.
     * 
     * @param key Record identifier
     * @return Result containing the game record response
     */
    suspend fun fetchGameRecord(key : String) : Result<GameRecordResponse>
    
    /**
     * Fetches multiple game records by keys.
     * 
     * @param keys List of record identifiers
     * @return Result containing bulk game record response
     */
    suspend fun bulkFetchGameRecords(keys : List<String>) : Result<BulkGameRecordResponse>
    
    /**
     * Deletes a game record by key.
     * 
     * @param key Record identifier
     * @return Result indicating success or failure
     */
    suspend fun deleteGameRecord(key : String) : Result<Unit>

    /**
     * Saves a user record with the provided JSON string directly.
     * 
     * @param userId User identifier
     * @param key Record identifier
     * @param jsonPayload Raw JSON string to save
     * @return Result containing the player record response
     */
    suspend fun saveUserRecordFromJsonString(userId : String, key : String, jsonPayload : String) : Result<PlayerRecordResponse>
    
    /**
     * Saves a user record with the specified user ID, key and payload.
     * 
     * @param userId User identifier
     * @param key Record identifier
     * @param payload JSON data to save
     * @return Result containing the player record response
     */
    suspend fun saveUserRecord(userId : String, key : String, payload : JsonElement) : Result<PlayerRecordResponse>
    
    /**
     * Fetches a user record by user ID and key.
     * 
     * @param userId User identifier
     * @param key Record identifier
     * @return Result containing the player record response
     */
    suspend fun fetchUserRecord(userId : String, key : String) : Result<PlayerRecordResponse>
    
    /**
     * Fetches multiple user records by user ID and keys.
     * 
     * @param userId User identifier
     * @param keys List of record identifiers
     * @return Result containing bulk player record response
     */
    suspend fun bulkFetchUserRecords(userId : String, keys : List<String>) : Result<BulkPlayerRecordResponse>
    
    /**
     * Deletes a user record by user ID and key.
     * 
     * @param userId User identifier
     * @param key Record identifier
     * @return Result indicating success or failure
     */
    suspend fun deleteUserRecord(userId : String, key : String) : Result<Unit>
}

/**
 * Singleton helper that creates and exposes the configured [VirtualFileSystem].
 */
object VirtualFileSystemManager
{
    @Volatile
    private var delegate : VirtualFileSystem? = null

    @Volatile
    private var guestLocalVfs : VirtualFileSystem? = null

    /**
     * Initializes the virtual filesystem with the given command line arguments.
     * 
     * @param args Command line arguments for configuration
     */
    fun initialize(args : List<String>)
    {
        synchronized(this) {
            if (delegate != null) return
            delegate = VirtualFileSystemFactory.create(args)
            Logger.info(LogCategory.DATABASE, "VirtualFileSystemManager initialized in ${delegate!!.mode} mode (${delegate!!.description})")
        }
    }

    /** Returns the current virtual filesystem instance */
    fun current() : VirtualFileSystem = delegate ?: error("VirtualFileSystemManager has not been initialized")

    /**
     * Returns the appropriate VFS for the given user. Guest users are routed to a
     * local VFS regardless of the global mode so that cloud APIs are never hit with
     * a non-existent AccelByte user id.
     */
    fun forUser(userId : String) : VirtualFileSystem
    {
        // In local dev mode, both guest-user and rest-client-<timestamp> connections
        // are local dev sessions that must route to local VFS (not AccelByte Cloud).
        if (!userId.startsWith("guest") && !userId.startsWith("rest-client")) return current()
        return guestLocalVfs ?: synchronized(this) {
            guestLocalVfs ?: LocalVirtualFileSystem.create(getHomeFolder().absolutePath).also {
                guestLocalVfs = it
                Logger.info(LogCategory.DATABASE, "VirtualFileSystemManager: created guest-local VFS (${it.description})")
            }
        }
    }
}

/**
 * Factory for creating virtual filesystem instances based on configuration.
 */
internal object VirtualFileSystemFactory
{
    private val modePrefixes = listOf("--mode=", "--server-mode=", "--vfs-mode=")
    private val localDirPrefixes = listOf("--vfs-local-dir=", "--local-vfs-path=")
    private val DEFAULT_LOCAL_DIR = getHomeFolder().absolutePath
    private const val LOCAL_PATH_ENV = "AUTOGEN_VFS_PATH"

    /**
     * Creates a virtual filesystem instance based on command line arguments.
     * 
     * @param args Command line arguments
     * @return Configured virtual filesystem instance
     */
    fun create(args : List<String>) : VirtualFileSystem
    {
        val resolvedMode = detectMode(args)
        return when (resolvedMode) {
            VfsMode.LOCAL -> LocalVirtualFileSystem.create(resolveLocalPath(args))
            VfsMode.CLOUD -> CloudVirtualFileSystem
        }
    }

    private fun detectMode(args : List<String>) : VfsMode
    {
        val explicit = args.asSequence()
            .mapNotNull { parseModeValue(it) }
            .firstOrNull()
            ?.lowercase()
        return when {
            explicit == null -> VfsMode.CLOUD
            explicit.contains("local") || explicit.contains("dev") || explicit.contains("test") -> VfsMode.LOCAL
            else -> VfsMode.CLOUD
        }
    }

    private fun parseModeValue(arg : String) : String?
    {
        return modePrefixes.firstNotNullOfOrNull { prefix ->
            if (arg.startsWith(prefix, ignoreCase = true)) {
                arg.substringAfter(prefix)
            } else null
        }
    }

    private fun resolveLocalPath(args : List<String>) : String
    {
        val fromArg = args.asSequence()
            .mapNotNull { parseLocalPathValue(it) }
            .firstOrNull()
        return fromArg
            ?: System.getenv(LOCAL_PATH_ENV)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_LOCAL_DIR
    }

    private fun parseLocalPathValue(arg : String) : String?
    {
        return localDirPrefixes.firstNotNullOfOrNull { prefix ->
            if (arg.startsWith(prefix, ignoreCase = true)) arg.substringAfter(prefix) else null
        }?.takeIf { it.isNotBlank() }
    }
}