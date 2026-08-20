package structs.storage

import structs.accelbyte.common.AccelByteJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual object MasterRecordStorage
{
    private var baseUrl: String? = null
    private var namespace: String? = null
    private var accessToken: String? = null
    
    actual fun configureSdk(provider : (() -> Any)?)
    {
        // No-op on JVM; configuration happens through environment or direct calls
    }
    
    /**
     * Configure the AccelByte connection details for direct API calls
     */
    fun configure(baseUrl: String, namespace: String, accessToken: String) {
        this.baseUrl = baseUrl
        this.namespace = namespace
        this.accessToken = accessToken
    }

    actual suspend fun fetchMasterRecord(userId : String) : Result<MasterRecord>
    {
        if (userId.startsWith("guest")) return fetchLocalMasterRecord(userId)
        return withContext(Dispatchers.IO) {
            runCatching {
                val client = HttpClient(CIO)
                try {
                    val response = client.get("$baseUrl/cloudsave/v1/admin/namespaces/$namespace/users/$userId/records/$MASTER_RECORD_KEY") {
                        header("Authorization", "Bearer $accessToken")
                        header("Accept", "application/json")
                    }
                    
                    if (response.status.isSuccess()) {
                        val responseText = response.bodyAsText()
                        val jsonResponse = AccelByteJson.parseToJsonElement(responseText)
                        val valueElement = if (jsonResponse is kotlinx.serialization.json.JsonObject) {
                            jsonResponse["value"]
                        } else null
                        
                        valueElement?.toMasterRecord() ?: MasterRecord()
                    } else {
                        MasterRecord()
                    }
                } finally {
                    client.close()
                }
            }
        }
    }

    actual suspend fun saveMasterRecord(userId : String, record : MasterRecord) : Result<MasterRecord>
    {
        if (userId.startsWith("guest")) return saveLocalMasterRecord(userId, record)
        return withContext(Dispatchers.IO) {
            runCatching {
                val client = HttpClient(CIO)
                try {
                    val jsonElement = record.toJsonElement()
                    val requestBody = """{"value":${AccelByteJson.encodeToString(jsonElement)}}"""
                    
                    val response = client.put("$baseUrl/cloudsave/v1/admin/namespaces/$namespace/users/$userId/records/$MASTER_RECORD_KEY") {
                        header("Authorization", "Bearer $accessToken")
                        header("Content-Type", "application/json")
                        setBody(requestBody)
                    }
                    
                    if (response.status.isSuccess()) {
                        record
                    } else {
                        throw Exception("Failed to save master record: ${response.status} - ${response.bodyAsText()}")
                    }
                } finally {
                    client.close()
                }
            }
        }
    }

    private suspend fun fetchLocalMasterRecord(userId : String) : Result<MasterRecord>
    {
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = localMasterRecordFile(userId)
                if (file.exists()) {
                    val json = AccelByteJson.parseToJsonElement(file.readText())
                    AccelByteJson.decodeFromJsonElement(MasterRecord.serializer(), json)
                } else {
                    MasterRecord()
                }
            }
        }
    }

    private suspend fun saveLocalMasterRecord(userId : String, record : MasterRecord) : Result<MasterRecord>
    {
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = localMasterRecordFile(userId)
                file.parentFile.mkdirs()
                file.writeText(AccelByteJson.encodeToString(record.toJsonElement()))
                record
            }
        }
    }

    private fun localMasterRecordFile(userId : String) : File
    {
        val base = File(System.getProperty("user.home"), ".autogenesis/player-records/$userId")
        return File(base, "$MASTER_RECORD_KEY.json")
    }

    private fun JsonElement?.toMasterRecord(default : MasterRecord = MasterRecord()) : MasterRecord
    {
        return this?.let { AccelByteJson.decodeFromJsonElement(MasterRecord.serializer(), it) } ?: default
    }

    private fun MasterRecord.toJsonElement() : JsonElement
    {
        return AccelByteJson.encodeToJsonElement(MasterRecord.serializer(), this)
    }
}