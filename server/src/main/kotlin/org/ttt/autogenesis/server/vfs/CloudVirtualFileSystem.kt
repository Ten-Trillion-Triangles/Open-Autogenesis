package org.ttt.autogenesis.server.vfs

import accelbyte.AccelByteSdkProvider
import accelbyte.cloudsave.AdminUserRecord
import accelbyte.cloudsave.GameRecord
import commonGlobals.VfsSanitizer
import kotlinx.serialization.json.JsonElement
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.logOperation
import structs.accelbyte.cloudsave.BulkGameRecordRequest
import structs.accelbyte.cloudsave.BulkGameRecordResponse
import structs.accelbyte.cloudsave.BulkPlayerRecordResponse
import structs.accelbyte.cloudsave.GameRecordRequest
import structs.accelbyte.cloudsave.GameRecordResponse
import structs.accelbyte.cloudsave.PlayerRecordResponse

object CloudVirtualFileSystem : VirtualFileSystem {
    override val mode: VfsMode = VfsMode.CLOUD
    override val description: String
        get() = "AccelByte Cloud Save (namespace=${AccelByteSdkProvider.namespace})"

    override suspend fun saveGameRecord(key: String, payload: JsonElement, updateIfExists: Boolean): Result<GameRecordResponse> {
        val sanitizedKey = sanitizeKey(key)
        return logOperation(
            LogCategory.DATABASE,
            "CloudVFS.saveGameRecord",
            "key=$sanitizedKey updateIfExists=$updateIfExists payload=${payload.safeSummary()}"
        ) {
            val request = GameRecordRequest(data = payload, updateIfExists = updateIfExists)
            GameRecord.createRecord(sanitizedKey, request)
        }
    }

    override suspend fun fetchGameRecord(key: String): Result<GameRecordResponse> =
        logOperation(
            LogCategory.DATABASE,
            "CloudVFS.fetchGameRecord",
            "key=${sanitizeKey(key)}"
        ) {
            GameRecord.fetchRecord(sanitizeKey(key))
        }

    override suspend fun bulkFetchGameRecords(keys: List<String>): Result<BulkGameRecordResponse> =
        logOperation(
            LogCategory.DATABASE,
            "CloudVFS.bulkFetchGameRecords",
            "keys=${keys.describeKeys()}"
        ) {
            GameRecord.bulkFetch(BulkGameRecordRequest(keys.map(::sanitizeKey)))
        }

    override suspend fun deleteGameRecord(key: String): Result<Unit> =
        logOperation(
            LogCategory.DATABASE,
            "CloudVFS.deleteGameRecord",
            "key=${sanitizeKey(key)}"
        ) {
            GameRecord.deleteRecord(sanitizeKey(key))
        }

    override suspend fun saveUserRecordFromJsonString(userId: String, key: String, jsonPayload: String): Result<PlayerRecordResponse>
    {
        val sanitizedKey = sanitizeKey(key)
        return runCatching {
            // Use direct REST API call since SDK's regular operations use empty base class
            val namespace = AccelByteSdkProvider.namespace
            val sdk = AccelByteSdkProvider.sdk
            val baseUrl = sdk.sdkConfiguration.configRepository.getBaseURL()
            val url = "$baseUrl/cloudsave/v1/admin/namespaces/$namespace/users/$userId/records/$sanitizedKey"
            
            // Authenticate using SDK
            sdk.loginClient()
            val tokenRepo = sdk.sdkConfiguration.tokenRepository
            val token = tokenRepo.token
            
            // Get access token (token is already a string based on previous testing)
            val accessToken = token as String
            
            // Create the request body with value field
            val requestBody = """{"value":$jsonPayload}"""
            
            // Make direct HTTP PUT request
            val client = java.net.http.HttpClient.newHttpClient()
            val request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                .build()
            
            val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() in 200..299) {
                PlayerRecordResponse(
                    key = sanitizedKey,
                    namespace = namespace,
                    userId = userId,
                    isPublicRecord = false,
                    createdAt = "",
                    updatedAt = "",
                    tags = null,
                    setBy = "SERVER",
                    value = structs.accelbyte.common.AccelByteJson.parseToJsonElement(jsonPayload)
                )
            } else {
                throw Exception("AccelByte API error: ${response.statusCode()} - ${response.body()}")
            }
        }
    }

    override suspend fun saveUserRecord(userId: String, key: String, payload: JsonElement): Result<PlayerRecordResponse>
    {
        val sanitizedKey = sanitizeKey(key)
        return logOperation(
            LogCategory.DATABASE,
            "CloudVFS.saveUserRecord",
            "userId=$userId key=$sanitizedKey payload=${payload.safeSummary()}"
        ) {
            runCatching {
                AdminUserRecord.updateRecordDirect(userId, sanitizedKey, payload).getOrThrow()
            }.recoverCatching {
                // If update fails (record doesn't exist), create it using direct API
                val jsonString = structs.accelbyte.common.AccelByteJson.encodeToString(payload)
                kotlinx.coroutines.runBlocking {
                    saveUserRecordFromJsonString(userId, key, jsonString).getOrThrow()
                }
            }
        }
    }

    override suspend fun fetchUserRecord(userId: String, key: String): Result<PlayerRecordResponse>
    {
        return logOperation(
            LogCategory.DATABASE,
            "CloudVFS.fetchUserRecord",
            "userId=$userId key=${sanitizeKey(key)}"
        ) {
            AdminUserRecord.getRecordDirect(userId, sanitizeKey(key))
        }
    }

    override suspend fun bulkFetchUserRecords(userId: String, keys: List<String>): Result<BulkPlayerRecordResponse>
    {
        return logOperation(
            LogCategory.DATABASE,
            "CloudVFS.bulkFetchUserRecords",
            "userId=$userId keys=${keys.describeKeys()}"
        ) {
            AdminUserRecord.bulkFetchRecords(userId, keys.map(::sanitizeKey))
        }
    }

    override suspend fun deleteUserRecord(userId: String, key: String): Result<Unit>
    {
        return logOperation(
            LogCategory.DATABASE,
            "CloudVFS.deleteUserRecord",
            "userId=$userId key=${sanitizeKey(key)}"
        ) {
            AdminUserRecord.deleteRecord(userId, sanitizeKey(key))
        }
    }

    private fun sanitizeKey(key: String): String = VfsSanitizer.sanitize(key)

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
