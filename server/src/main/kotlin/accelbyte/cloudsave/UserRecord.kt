package accelbyte.cloudsave

import accelbyte.AccelByteSdkProvider
import kotlinx.serialization.json.*
import net.accelbyte.sdk.api.cloudsave.models.*
import net.accelbyte.sdk.api.cloudsave.operations.public_player_record.*
import net.accelbyte.sdk.api.cloudsave.wrappers.PublicPlayerRecord
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.logOperation
import structs.accelbyte.cloudsave.*

/**
 * Helpers around the AccelByte PublicPlayerRecord wrapper so the server can manage player-scoped
 * cloud save records without touching the generated models directly.
 */
object UserRecord
{
    private val sdk get() = AccelByteSdkProvider.sdk
    private val namespace get() = AccelByteSdkProvider.namespace
    private val wrapper by lazy { PublicPlayerRecord(sdk) }

    /**
     * Lists record keys owned by the current user with optional filtering.
     * 
     * @param limit Maximum number of record keys to return
     * @param offset Number of records to skip for pagination
     * @param tags List of tags to filter records by
     * @return [Result] containing [PlayerRecordKeyList] with owned record keys
     */
    fun listOwnedRecordKeys(
        limit : Int? = null,
        offset : Int? = null,
        tags : List<String>? = null
    ) : Result<PlayerRecordKeyList> = runCatching {
        val op = RetrievePlayerRecords.builder()
            .namespace(namespace)
            .limit(limit)
            .offset(offset)
            .tags(tags)
            .build()
        wrapper.retrievePlayerRecords(op).toKeyList()
    }

    /**
     * Retrieves multiple owned records in a single bulk operation.
     * 
     * @param keys List of record keys to fetch
     * @return [Result] containing [BulkPlayerRecordResponse] with requested records
     */
    fun bulkGetOwnedRecords(keys : List<String>) : Result<BulkPlayerRecordResponse> = runCatching {
        val body = ModelsBulkGetPlayerRecordsRequest.builder().keys(keys).build()
        val op = GetPlayerRecordsBulkHandlerV1.builder().namespace(namespace).body(body).build()
        wrapper.getPlayerRecordsBulkHandlerV1(op).toBulkResponse()
    }

    /**
     * Retrieves a specific player record by user ID and key.
     * 
     * @param userId Unique identifier for the player
     * @param key Unique identifier for the record
     * @return [Result] containing [PlayerRecordResponse] with record details
     */
    fun getRecord(userId : String, key : String) : Result<PlayerRecordResponse> = runCatching {
        val op = GetPlayerRecordHandlerV1.builder()
            .namespace(namespace)
            .userId(userId)
            .key(key)
            .build()
        wrapper.getPlayerRecordHandlerV1(op).toSharedRecord()
    }

    /**
     * Creates a new player record with the provided data.
     * 
     * @param userId Unique identifier for the player
     * @param key Unique identifier for the new record
     * @param payload [JsonElement] containing the record data
     * @return [Result] containing [PlayerRecordResponse] with created record details
     */
    fun createRecord(userId : String, key : String, payload : JsonElement) : Result<PlayerRecordResponse> =
        logOperation(
            LogCategory.DATABASE,
            "UserRecord.createRecord",
            "userId=$userId key=$key"
        ) {
            runCatching {
                val op = PostPlayerRecordHandlerV1.builder()
                    .namespace(namespace)
                    .userId(userId)
                    .key(key)
                    .body(payload.toModelsPlayerRecordRequest())
                    .build()
                wrapper.postPlayerRecordHandlerV1(op).toSharedRecord()
            }
        }

    /**
     * Replaces an existing player record with new data.
     * 
     * @param userId Unique identifier for the player
     * @param key Unique identifier for the record to replace
     * @param payload [JsonElement] containing the replacement data
     * @return [Result] containing updated [PlayerRecordResponse]
     */
    fun replaceRecord(userId : String, key : String, payload : JsonElement) : Result<PlayerRecordResponse> =
        logOperation(
            LogCategory.DATABASE,
            "UserRecord.replaceRecord",
            "userId=$userId key=$key"
        ) {
            runCatching {
                val op = PutPlayerRecordHandlerV1.builder()
                    .namespace(namespace)
                    .userId(userId)
                    .key(key)
                    .body(payload.toModelsPlayerRecordRequest())
                    .build()
                wrapper.putPlayerRecordHandlerV1(op).toSharedRecord()
            }
        }

    /**
     * Deletes a player record by user ID and key.
     * 
     * @param userId Unique identifier for the player
     * @param key Unique identifier for the record to delete
     * @return [Result] indicating success or failure of deletion
     */
    fun deleteRecord(userId : String, key : String) : Result<Unit> = runCatching {
        val op = DeletePlayerRecordHandlerV1.builder()
            .namespace(namespace)
            .userId(userId)
            .key(key)
            .build()
        wrapper.deletePlayerRecordHandlerV1(op)
    }

    /**
     * Lists public record keys for a specific user with optional filtering.
     * 
     * @param userId Unique identifier for the player whose public records to list
     * @param limit Maximum number of record keys to return
     * @param offset Number of records to skip for pagination
     * @param tags List of tags to filter records by
     * @return [Result] containing [PlayerRecordKeyList] with public record keys
     */
    fun listPublicRecordKeys(
        userId : String,
        limit : Int? = null,
        offset : Int? = null,
        tags : List<String>? = null
    ) : Result<PlayerRecordKeyList> = runCatching {
        val op = GetOtherPlayerPublicRecordKeyHandlerV1.builder()
            .namespace(namespace)
            .userId(userId)
            .limit(limit)
            .offset(offset)
            .tags(tags)
            .build()
        wrapper.getOtherPlayerPublicRecordKeyHandlerV1(op).toKeyList()
    }

    /**
     * Retrieves multiple public records for a specific user in bulk.
     * 
     * @param userId Unique identifier for the player
     * @param keys List of record keys to fetch
     * @return [Result] containing [BulkPlayerRecordResponse] with requested public records
     */
    fun bulkGetPublicRecords(userId : String, keys : List<String>) : Result<BulkPlayerRecordResponse> =
        runCatching {
            val body = ModelsBulkGetPlayerRecordsRequest.builder().keys(keys).build()
            val op = GetOtherPlayerPublicRecordHandlerV1.builder()
                .namespace(namespace)
                .userId(userId)
                .body(body)
                .build()
            wrapper.getOtherPlayerPublicRecordHandlerV1(op).toBulkResponse()
        }

    /**
     * Retrieves public records by key across multiple users.
     * 
     * @param key Record key to fetch across users
     * @param userIds List of user IDs to fetch the record from
     * @return [Result] containing [BulkPlayerRecordResponse] with records from specified users
     */
    fun bulkGetPublicRecordByKey(key : String, userIds : List<String>) : Result<BulkPlayerRecordResponse> =
        runCatching {
            val body = ModelsBulkUserIDsRequest.builder().userIds(userIds).build()
            val op = BulkGetPlayerPublicRecordHandlerV1.builder()
                .namespace(namespace)
                .key(key)
                .body(body)
                .build()
            wrapper.bulkGetPlayerPublicRecordHandlerV1(op).toBulkResponse()
        }

    /**
     * Retrieves a specific public record by user ID and key.
     * 
     * @param userId Unique identifier for the player
     * @param key Unique identifier for the public record
     * @return [Result] containing [PlayerRecordResponse] with public record details
     */
    fun getPublicRecord(userId : String, key : String) : Result<PlayerRecordResponse> = runCatching {
        val op = GetPlayerPublicRecordHandlerV1.builder()
            .namespace(namespace)
            .userId(userId)
            .key(key)
            .build()
        wrapper.getPlayerPublicRecordHandlerV1(op).toSharedRecord()
    }

    /**
     * Creates a new public record for a player.
     * 
     * @param userId Unique identifier for the player
     * @param key Unique identifier for the new public record
     * @param payload [JsonElement] containing the record data
     * @return [Result] containing [PlayerRecordResponse] with created public record details
     */
    fun createPublicRecord(userId : String, key : String, payload : JsonElement) : Result<PlayerRecordResponse> =
        runCatching {
            val op = PostPlayerPublicRecordHandlerV1.builder()
                .namespace(namespace)
                .userId(userId)
                .key(key)
                .body(payload.toModelsPlayerRecordRequest())
                .build()
            wrapper.postPlayerPublicRecordHandlerV1(op).toSharedRecord()
        }

    /**
     * Replaces an existing public record with new data.
     * 
     * @param userId Unique identifier for the player
     * @param key Unique identifier for the public record to replace
     * @param payload [JsonElement] containing the replacement data
     * @return [Result] containing updated [PlayerRecordResponse]
     */
    fun replacePublicRecord(userId : String, key : String, payload : JsonElement) : Result<PlayerRecordResponse> =
        runCatching {
            val op = PutPlayerPublicRecordHandlerV1.builder()
                .namespace(namespace)
                .userId(userId)
                .key(key)
                .body(payload.toModelsPlayerRecordRequest())
                .build()
            wrapper.putPlayerPublicRecordHandlerV1(op).toSharedRecord()
        }

    /**
     * Deletes the current user's own public record by key.
     * 
     * @param key Unique identifier for the public record to delete
     * @return [Result] indicating success or failure of deletion
     */
    fun deleteOwnPublicRecord(key : String) : Result<Unit> = runCatching {
        val op = PublicDeletePlayerPublicRecordHandlerV1.builder()
            .namespace(namespace)
            .key(key)
            .build()
        wrapper.publicDeletePlayerPublicRecordHandlerV1(op)
    }

    private fun ModelsListPlayerRecordKeysResponse.toKeyList() : PlayerRecordKeyList =
        PlayerRecordKeyList(
            data = data?.map { it.toSharedKey() } ?: emptyList(),
            paging = paging?.toPaging()
        )

    private fun ModelsBulkGetPlayerRecordResponse.toBulkResponse() : BulkPlayerRecordResponse =
        BulkPlayerRecordResponse(records = data?.map { it.toSharedRecord() } ?: emptyList())

    private fun ModelsPlayerRecordResponse.toSharedRecord() : PlayerRecordResponse =
        PlayerRecordResponse(
            key = key,
            namespace = namespace,
            userId = userId,
            isPublicRecord = isPublic,
            createdAt = createdAt,
            updatedAt = updatedAt,
            tags = tags,
            setBy = setBy,
            value = value?.toJsonElement()
        )

    private fun ModelsPlayerRecordKeyInfo.toSharedKey() : PlayerRecordKeyInfo =
        PlayerRecordKeyInfo(key, userId)

    private fun ModelsPagination.toPaging() : PlayerRecordPagingInfo =
        PlayerRecordPagingInfo(first, last, next, previous)
}