package accelbyte.cloudsave

import accelbyte.AccelByteSdkProvider
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlinx.serialization.json.JsonElement
import net.accelbyte.sdk.api.cloudsave.models.ModelsBulkGetPlayerRecordsRequest
import net.accelbyte.sdk.api.cloudsave.models.ModelsPlayerRecordRequest
import net.accelbyte.sdk.api.cloudsave.operations.admin_player_record.AdminDeletePlayerRecordHandlerV1
import net.accelbyte.sdk.api.cloudsave.operations.admin_player_record.AdminGetPlayerRecordsHandlerV1
import net.accelbyte.sdk.api.cloudsave.operations.admin_player_record.AdminGetPlayerPublicRecordHandlerV1
import net.accelbyte.sdk.api.cloudsave.operations.admin_player_record.AdminGetPlayerRecordHandlerV1
import net.accelbyte.sdk.api.cloudsave.operations.admin_player_record.AdminPostPlayerRecordHandlerV1
import net.accelbyte.sdk.api.cloudsave.operations.admin_player_record.AdminPutPlayerRecordHandlerV1
import net.accelbyte.sdk.api.cloudsave.operations.admin_player_record.AdminRetrievePlayerRecords
import net.accelbyte.sdk.api.cloudsave.wrappers.AdminPlayerRecord
import net.accelbyte.sdk.api.cloudsave.wrappers.AdminConcurrentRecord
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.logging.logOperation
import structs.accelbyte.cloudsave.*
import structs.accelbyte.common.AccelByteJson
import kotlin.PublishedApi

/**
 * Admin helpers for manipulating user-scoped cloud save records beyond the player's own permissions.
 */
object AdminUserRecord
{
    private val sdk get() = AccelByteSdkProvider.sdk
    private val namespace get() = AccelByteSdkProvider.namespace
    private val wrapper by lazy { AdminPlayerRecord(sdk) }
    private val concurrentWrapper by lazy { AdminConcurrentRecord(sdk) }

    /**
     * Retrieves a user record using direct REST API to ensure consistent data format.
     */
    fun getRecordDirect(userId : String, key : String) : Result<PlayerRecordResponse>
    {
        return logOperation(
            LogCategory.DATABASE,
            "AdminUserRecord.getRecordDirect",
            "userId=$userId key=$key"
        ) {
            runCatching {
                val baseUrl = sdk.sdkConfiguration.configRepository.getBaseURL()
                val url = "$baseUrl/cloudsave/v1/admin/namespaces/$namespace/users/$userId/records/$key"
                
                // Authenticate using SDK
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
                    // Parse the response JSON
                    val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
                    val responseJson = objectMapper.readTree(response.body())
                    
                    // Extract the value field and convert to JsonElement
                    val valueNode = responseJson.get("value")
                    val valueElement = if (valueNode != null) {
                        structs.accelbyte.common.AccelByteJson.parseToJsonElement(valueNode.toString())
                    } else null
                    
                    PlayerRecordResponse(
                        key = responseJson.get("key")?.asText() ?: key,
                        namespace = responseJson.get("namespace")?.asText() ?: namespace,
                        userId = responseJson.get("user_id")?.asText() ?: userId,
                        isPublicRecord = responseJson.get("is_public")?.asBoolean(),
                        createdAt = responseJson.get("created_at")?.asText() ?: "",
                        updatedAt = responseJson.get("updated_at")?.asText() ?: "",
                        tags = responseJson.get("tags")?.map { it.asText() },
                        setBy = responseJson.get("set_by")?.asText(),
                        value = valueElement
                    )
                } else {
                    throw Exception("AccelByte API error: ${response.statusCode()} - ${response.body()}")
                }
            }
        }
    }

    /**
     * Retrieves a user record with admin credentials.
     */
    fun getRecord(userId : String, key : String) : Result<PlayerRecordResponse>
    {
        Logger.debug(LogCategory.DATABASE, "AdminUserRecord.getRecord: START userId='$userId' key='$key'")
        
        return runCatching {
            val op = AdminGetPlayerRecordHandlerV1.builder()
                .namespace(namespace)
                .userId(userId)
                .key(key)
                .build()
            
            Logger.debug(LogCategory.DATABASE, "AdminUserRecord.getRecord: Calling SDK with namespace='$namespace' userId='$userId' key='$key'")
            
            val result = wrapper.adminGetPlayerRecordHandlerV1(op).toSharedRecord()
            
            Logger.info(LogCategory.DATABASE, "AdminUserRecord.getRecord: SUCCESS - hasValue=${result.value != null} key='$key'")
            result
        }.onFailure { err ->
            Logger.error(LogCategory.DATABASE, "AdminUserRecord.getRecord: FAILED key='$key' - ${err.message}")
        }
    }

    /**
     * Lists a user's record keys with optional filtering.
     */
    fun listRecordKeys(
        userId : String,
        limit : Int? = null,
        offset : Int? = null,
        query : String? = null,
        tags : List<String>? = null
    ) : Result<PlayerRecordKeyList>
    {
        return runCatching {
            val op = AdminRetrievePlayerRecords.builder()
                .namespace(namespace)
                .userId(userId)
                .limit(limit)
                .offset(offset)
                .query(query)
                .tags(tags)
                .build()
            wrapper.adminRetrievePlayerRecords(op).toKeyList()
        }
    }

    /**
     * Bulk fetches user records by a list of keys.
     */
    fun bulkFetchRecords(userId : String, keys : List<String>) : Result<BulkPlayerRecordResponse>
    {
        return runCatching {
            val body = ModelsBulkGetPlayerRecordsRequest.builder().keys(keys).build()
            val op = AdminGetPlayerRecordsHandlerV1.builder()
                .namespace(namespace)
                .userId(userId)
                .body(body)
                .build()
            wrapper.adminGetPlayerRecordsHandlerV1(op).toBulkResponse()
        }
    }

    /**
     * Creates a new admin user record using concurrent operation with proper value field serialization.
     */
    fun createRecordConcurrent(userId : String, key : String, payload : JsonElement) : Result<PlayerRecordResponse>
    {
        return executeCreateRecordConcurrent(userId, key, payload.toModelsAdminConcurrentRecordRequest())
    }

    /**
     * Creates a new admin user record (POST) with the provided payload.
     */
    fun createRecord(userId : String, key : String, payload : JsonElement) : Result<PlayerRecordResponse>
    {
        return executeCreateRecord(userId, key, payload.toModelsPlayerRecordRequest())
    }

    /**
     * Creates a new admin user record (POST) from a typed payload.
     *
     * @param serializer optional serializer; defaults to [serializer].
     */
    inline fun <reified T> createRecord(
        userId : String,
        key : String,
        payload : T,
        serializer : KSerializer<T>? = null
    ) : Result<PlayerRecordResponse>
    {
        return executeCreateRecord(userId, key, payload.toPlayerRecordRequest(serializer))
    }

    /**
     * Updates an existing admin user record using direct REST API with proper value field serialization.
     */
    fun updateRecordDirect(userId : String, key : String, payload : JsonElement) : Result<PlayerRecordResponse>
    {
        return logOperation(
            LogCategory.DATABASE,
            "AdminUserRecord.updateRecordDirect",
            "userId=$userId key=$key"
        ) {
            runCatching {
                val jsonString = structs.accelbyte.common.AccelByteJson.encodeToString(payload)
                val baseUrl = sdk.sdkConfiguration.configRepository.getBaseURL()
                val url = "$baseUrl/cloudsave/v1/admin/namespaces/$namespace/users/$userId/records/$key"
                
                // Authenticate using SDK
                sdk.loginClient()
                val token = sdk.sdkConfiguration.tokenRepository.token as String
                
                // Create request body with value field
                val requestBody = """{"value":$jsonString}"""
                
                // Make direct HTTP PUT request
                val client = java.net.http.HttpClient.newHttpClient()
                val request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()
                
                val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                
                if (response.statusCode() in 200..299) {
                    PlayerRecordResponse(
                        key = key,
                        namespace = namespace,
                        userId = userId,
                        isPublicRecord = false,
                        createdAt = "",
                        updatedAt = "",
                        tags = null,
                        setBy = "SERVER",
                        value = payload
                    )
                } else {
                    throw Exception("AccelByte API error: ${response.statusCode()} - ${response.body()}")
                }
            }
        }
    }

    /**
     * Replaces an existing admin user record (PUT) with new data.
     */
    fun updateRecord(userId : String, key : String, payload : JsonElement) : Result<PlayerRecordResponse>
    {
        return executeUpdateRecord(userId, key, payload.toModelsPlayerRecordRequest())
    }

    /**
     * Replaces an existing admin user record (PUT) from a typed payload.
     *
     * @param serializer optional serializer; defaults to [serializer].
     */
    inline fun <reified T> updateRecord(
        userId : String,
        key : String,
        payload : T,
        serializer : KSerializer<T>? = null
    ) : Result<PlayerRecordResponse>
    {
        return executeUpdateRecord(userId, key, payload.toPlayerRecordRequest(serializer))
    }

    /**
     * Deletes a user record using admin privileges.
     */
    fun deleteRecord(userId : String, key : String) : Result<Unit>
    {
        return logOperation(
            LogCategory.DATABASE,
            "AdminUserRecord.deleteRecord",
            "userId=$userId key=$key"
        ) {
            runCatching {
                val op = AdminDeletePlayerRecordHandlerV1.builder()
                    .namespace(namespace)
                    .userId(userId)
                    .key(key)
                    .build()
                wrapper.adminDeletePlayerRecordHandlerV1(op)
            }
        }
    }

    @PublishedApi
    internal fun executeCreateRecord(userId : String, key : String, body : ModelsPlayerRecordRequest) : Result<PlayerRecordResponse>
    {
        return logOperation(
            LogCategory.DATABASE,
            "AdminUserRecord.createRecord",
            "userId=$userId key=$key"
        ) {
            runCatching {
                val op = AdminPostPlayerRecordHandlerV1.builder()
                    .namespace(namespace)
                    .userId(userId)
                    .key(key)
                    .body(body)
                    .build()
                wrapper.adminPostPlayerRecordHandlerV1(op).toSharedRecord()
            }
        }
    }

    @PublishedApi
    internal fun executeCreateRecordConcurrent(userId : String, key : String, body : net.accelbyte.sdk.api.cloudsave.models.ModelsAdminConcurrentRecordRequest) : Result<PlayerRecordResponse>
    {
        return logOperation(
            LogCategory.DATABASE,
            "AdminUserRecord.createRecordConcurrent",
            "userId=$userId key=$key"
        ) {
            runCatching {
                val op = net.accelbyte.sdk.api.cloudsave.operations.admin_concurrent_record.AdminPutPlayerRecordConcurrentHandlerV1.builder()
                    .namespace(namespace)
                    .userId(userId)
                    .key(key)
                    .body(body)
                    .build()
                val response = concurrentWrapper.adminPutPlayerRecordConcurrentHandlerV1(op)
                val valueElement = body.value?.toJsonElement()
                response.toSharedRecord(key, namespace, userId, valueElement)
            }
        }
    }

    @PublishedApi
    internal fun executeUpdateRecord(userId : String, key : String, body : ModelsPlayerRecordRequest) : Result<PlayerRecordResponse>
    {
        return logOperation(
            LogCategory.DATABASE,
            "AdminUserRecord.updateRecord",
            "userId=$userId key=$key record=${body}"
        ) {
            runCatching {
                val op = AdminPutPlayerRecordHandlerV1.builder()
                    .namespace(namespace)
                    .userId(userId)
                    .key(key)
                    .body(body)
                    .build()
                wrapper.adminPutPlayerRecordHandlerV1(op).toSharedRecord()
            }
        }
    }

    /**
     * Retrieves a user's admin-level public record by key.
     */
    fun getPublicRecord(userId : String, key : String) : Result<PlayerRecordResponse>
    {
        return runCatching {
            val op = AdminGetPlayerPublicRecordHandlerV1.builder()
                .namespace(namespace)
                .userId(userId)
                .key(key)
                .build()
            wrapper.adminGetPlayerPublicRecordHandlerV1(op).toSharedRecord()
        }
    }
}
