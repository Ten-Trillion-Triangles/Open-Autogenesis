package accelbyte.cloudsave

import accelbyte.AccelByteSdkProvider
import kotlinx.serialization.json.JsonElement
import net.accelbyte.sdk.api.cloudsave.models.*
import net.accelbyte.sdk.api.cloudsave.operations.admin_game_record.*
import net.accelbyte.sdk.api.cloudsave.operations.admin_record.*
import net.accelbyte.sdk.api.cloudsave.operations.public_game_record.*
import net.accelbyte.sdk.api.cloudsave.wrappers.AdminGameRecord
import net.accelbyte.sdk.api.cloudsave.wrappers.AdminRecord
import net.accelbyte.sdk.api.cloudsave.wrappers.PublicGameRecord
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.logOperation
import structs.accelbyte.cloudsave.*

/**
 * Server-side helpers for public/admin game record APIs backed by AccelByte Cloud Save.
 */
object GameRecord
{
    private val sdk get() = AccelByteSdkProvider.sdk
    private val namespace get() = AccelByteSdkProvider.namespace
    private val publicWrapper by lazy { PublicGameRecord(sdk) }
    private val adminGameWrapper by lazy { AdminGameRecord(sdk) }
    private val adminRecordWrapper by lazy { AdminRecord(sdk) }

    /**
     * Retrieves a specific game record by its key.
     * 
     * @param key Unique identifier for the game record
     * @return [Result] containing [GameRecordResponse] with record details
     */
    fun fetchRecord(key : String) : Result<GameRecordResponse> = runCatching {
        val op = GetGameRecordHandlerV1.builder()
            .namespace(namespace)
            .key(key)
            .build()
        publicWrapper.getGameRecordHandlerV1(op).toSharedRecord()
    }

    /**
     * Creates a new game record with the provided request data.
     * 
     * @param key Unique identifier for the new game record
     * @param request [GameRecordRequest] containing record creation details
     * @return [Result] containing [GameRecordResponse] with created record details
     */
    fun createRecord(key : String, request : GameRecordRequest) : Result<GameRecordResponse> =
        logOperation(LogCategory.DATABASE, "GameRecord.createRecord", "key=$key") {
            runCatching {
                val op = PostGameRecordHandlerV1.builder()
                    .namespace(namespace)
                    .key(key)
                    .body(request.toModelsGameRecordRequest())
                    .build()
                publicWrapper.postGameRecordHandlerV1(op).toSharedRecord()
            }
        }

    /**
     * Replaces an existing game record with new data.
     * 
     * @param key Unique identifier for the game record to replace
     * @param request [GameRecordRequest] containing replacement data
     * @return [Result] containing updated [GameRecordResponse]
     */
    fun replaceRecord(key : String, request : GameRecordRequest) : Result<GameRecordResponse> =
        logOperation(LogCategory.DATABASE, "GameRecord.replaceRecord", "key=$key") {
            runCatching {
                val op = PutGameRecordHandlerV1.builder()
                    .namespace(namespace)
                    .key(key)
                    .body(request.toModelsGameRecordRequest())
                    .build()
                publicWrapper.putGameRecordHandlerV1(op).toSharedRecord()
            }
        }

    /**
     * Deletes a game record by its key.
     * 
     * @param key Unique identifier for the game record to delete
     * @return [Result] indicating success or failure of deletion
     */
    fun deleteRecord(key : String) : Result<Unit> = runCatching {
        val op = DeleteGameRecordHandlerV1.builder()
            .namespace(namespace)
            .key(key)
            .build()
        publicWrapper.deleteGameRecordHandlerV1(op)
    }

    /**
     * Retrieves multiple game records in a single bulk operation.
     * 
     * @param request [BulkGameRecordRequest] containing keys of records to fetch
     * @return [Result] containing [BulkGameRecordResponse] with requested records
     */
    fun bulkFetch(request : BulkGameRecordRequest) : Result<BulkGameRecordResponse> = runCatching {
        val op = GetGameRecordsBulk.builder()
            .namespace(namespace)
            .body(request.toModelsBulkRequest())
            .build()
        publicWrapper.getGameRecordsBulk(op).toBulkResponse()
    }

    /**
     * Lists game record keys using admin privileges with optional filtering.
     * 
     * @param query Search query string for filtering records
     * @param tags List of tags to filter records by
     * @param limit Maximum number of record keys to return
     * @param offset Number of records to skip for pagination
     * @return [Result] containing [GameRecordKeyList] with admin record keys
     */
    fun listAdminRecords(
        query : String? = null,
        tags : List<String>? = null,
        limit : Int,
        offset : Int
    ) : Result<GameRecordKeyList> = runCatching {
        val op = ListGameRecordsHandlerV1.builder()
            .namespace(namespace)
            .query(query)
            .tags(tags)
            .limit(limit)
            .offset(offset)
            .build()
        adminGameWrapper.listGameRecordsHandlerV1(op).toKeyList()
    }

    /**
     * Retrieves a game record using direct REST API to ensure consistent data format.
     * This bypasses SDK model limitations for dynamic JSON payloads.
     */
    fun adminFetchRecordDirect(key : String) : Result<GameRecordAdminResponse> =
        logOperation(LogCategory.DATABASE, "GameRecord.adminFetchRecordDirect", "key=$key") {
            runCatching {
                val baseUrl = sdk.sdkConfiguration.configRepository.getBaseURL()
                val url = "$baseUrl/cloudsave/v1/admin/namespaces/$namespace/adminrecords/$key"
                
                // Ensure client is authenticated
                sdk.loginClient()
                val token = sdk.sdkConfiguration.tokenRepository.token as String
                
                // Make direct HTTP GET request
                val client = java.net.http.HttpClient.newHttpClient()
                val request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .GET()
                    .build()
                
                val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                
                if (response.statusCode() in 200..299) {
                    val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
                    val responseJson = objectMapper.readTree(response.body())
                    
                    val valueNode = responseJson.get("value")
                    val valueElement = if (valueNode != null) {
                        structs.accelbyte.common.AccelByteJson.parseToJsonElement(valueNode.toString())
                    } else null
                    
                    GameRecordAdminResponse(
                        key = key,
                        namespace = namespace,
                        createdAt = responseJson.get("created_at")?.asText() ?: "",
                        updatedAt = responseJson.get("updated_at")?.asText() ?: "",
                        setBy = responseJson.get("set_by")?.asText(),
                        value = valueElement
                    )
                } else if (response.statusCode() == 404) {
                    throw Exception("Record not found: $key")
                } else {
                    throw Exception("AccelByte API error: ${response.statusCode()} - ${response.body()}")
                }
            }
        }

    /**
     * Retrieves a game record using admin privileges for extended access.
     * 
     * @param key Unique identifier for the game record
     * @return [Result] containing [GameRecordAdminResponse] with admin record details
     */
    fun adminFetchRecord(key : String) : Result<GameRecordAdminResponse> = adminFetchRecordDirect(key)

    /**
     * Creates a new game record using direct REST API with proper value field serialization.
     * This bypasses SDK model limitations for dynamic JSON payloads.
     */
    fun adminCreateRecordDirect(key : String, payload : JsonElement) : Result<GameRecordAdminResponse> =
        logOperation(LogCategory.DATABASE, "GameRecord.adminCreateRecordDirect", "key=$key") {
            runCatching {
                val jsonString = structs.accelbyte.common.AccelByteJson.encodeToString(payload)
                val baseUrl = sdk.sdkConfiguration.configRepository.getBaseURL()
                val url = "$baseUrl/cloudsave/v1/admin/namespaces/$namespace/adminrecords/$key"
                
                // Ensure client is authenticated
                sdk.loginClient()
                val token = sdk.sdkConfiguration.tokenRepository.token as String
                
                // Make direct HTTP POST request
                val client = java.net.http.HttpClient.newHttpClient()
                val request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonString))
                    .build()
                
                val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                
                if (response.statusCode() in 200..299) {
                    val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
                    val responseJson = objectMapper.readTree(response.body())
                    
                    GameRecordAdminResponse(
                        key = key,
                        namespace = namespace,
                        createdAt = responseJson.get("created_at")?.asText() ?: "",
                        updatedAt = responseJson.get("updated_at")?.asText() ?: "",
                        setBy = responseJson.get("set_by")?.asText(),
                        value = payload
                    )
                } else {
                    throw Exception("AccelByte API error: ${response.statusCode()} - ${response.body()}")
                }
            }
        }

    /**
     * Creates a game record using admin privileges with extended permissions.
     * 
     * @param key Unique identifier for the new game record
     * @param request [GameRecordRequest] containing record creation details
     * @return [Result] containing [GameRecordAdminResponse] with created record details
     */
    fun adminCreateRecord(key : String, request : GameRecordRequest) : Result<GameRecordAdminResponse> = 
        adminCreateRecordDirect(key, request.data)

    /**
     * Replaces an existing game record using direct REST API with proper value field serialization.
     * This bypasses SDK model limitations for dynamic JSON payloads.
     */
    fun adminReplaceRecordDirect(key : String, payload : JsonElement) : Result<GameRecordAdminResponse> =
        logOperation(LogCategory.DATABASE, "GameRecord.adminReplaceRecordDirect", "key=$key") {
            runCatching {
                val jsonString = structs.accelbyte.common.AccelByteJson.encodeToString(payload)
                val baseUrl = sdk.sdkConfiguration.configRepository.getBaseURL()
                val url = "$baseUrl/cloudsave/v1/admin/namespaces/$namespace/adminrecords/$key"
                
                // Ensure client is authenticated
                sdk.loginClient()
                val token = sdk.sdkConfiguration.tokenRepository.token as String
                
                // Make direct HTTP PUT request
                val client = java.net.http.HttpClient.newHttpClient()
                val request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(jsonString))
                    .build()
                
                val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                
                if (response.statusCode() in 200..299) {
                    // Parse the response to get updated timestamps etc.
                    val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
                    val responseJson = objectMapper.readTree(response.body())
                    
                    GameRecordAdminResponse(
                        key = key,
                        namespace = namespace,
                        createdAt = responseJson.get("created_at")?.asText() ?: "",
                        updatedAt = responseJson.get("updated_at")?.asText() ?: "",
                        setBy = responseJson.get("set_by")?.asText(),
                        value = payload
                    )
                } else {
                    throw Exception("AccelByte API error: ${response.statusCode()} - ${response.body()}")
                }
            }
        }

    /**
     * Replaces a game record using admin privileges.
     * 
     * @param key Unique identifier for the game record to replace
     * @param request [GameRecordRequest] containing replacement data
     * @return [Result] containing updated [GameRecordAdminResponse]
     */
    fun adminReplaceRecord(key : String, request : GameRecordRequest) : Result<GameRecordAdminResponse> =
        adminReplaceRecordDirect(key, request.data)

    /**
     * Deletes a game record using admin privileges.
     * 
     * @param key Unique identifier for the game record to delete
     * @return [Result] indicating success or failure of deletion
     */
    fun adminDeleteRecord(key : String) : Result<Unit> = runCatching {
        val op = AdminDeleteGameRecordHandlerV1.builder()
            .namespace(namespace)
            .key(key)
            .build()
        adminGameWrapper.adminDeleteGameRecordHandlerV1(op)
    }

    private fun ModelsGameRecordResponse.toSharedRecord() : GameRecordResponse =
        GameRecordResponse(
            key = key,
            namespace = namespace,
            createdAt = createdAt,
            updatedAt = updatedAt,
            tags = tags,
            setBy = setBy,
            value = value?.toJsonElement()
        )

    private fun ModelsBulkGetGameRecordResponse.toBulkResponse() : BulkGameRecordResponse =
        BulkGameRecordResponse(records = data?.map { it.toSharedRecord() } ?: emptyList())

    private fun ModelsListGameRecordKeysResponse.toKeyList() : GameRecordKeyList =
        GameRecordKeyList(
            keys = data ?: emptyList(),
            paging = paging?.toPaging()
        )

    private fun ModelsGameRecordAdminResponse.toAdminResponse() : GameRecordAdminResponse =
        GameRecordAdminResponse(
            key = key,
            namespace = namespace,
            tags = tags,
            createdAt = createdAt,
            updatedAt = updatedAt,
            setBy = setBy,
            ttlConfig = ttlConfig.toGameRecordTtlConfig(),
            value = value?.toJsonElement()
        )

    private fun ModelsPagination.toPaging() : GameRecordPagingInfo =
        GameRecordPagingInfo(first, last, next, previous)
}