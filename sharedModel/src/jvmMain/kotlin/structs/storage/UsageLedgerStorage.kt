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
import structs.account.UsageLedger

actual object UsageLedgerStorage
{
    private var baseUrl: String? = null
    private var namespace: String? = null
    private var accessToken: String? = null

    actual fun configureSdk(provider : (() -> Any)?)
    {
        // No-op on JVM; configuration happens through environment or direct calls
    }

    /**
     * Configure the AccelByte connection details for direct API calls.
     */
    fun configure(baseUrl: String, namespace: String, accessToken: String)
    {
        this.baseUrl = baseUrl
        this.namespace = namespace
        this.accessToken = accessToken
    }

    actual suspend fun fetchUsageLedger(userId : String) : Result<UsageLedger>
    {
        if (userId.startsWith("guest")) return fetchLocalLedger(userId)
        return withContext(Dispatchers.IO) {
            runCatching {
                val client = HttpClient(CIO)
                try {
                    val response = client.get("$baseUrl/cloudsave/v1/admin/namespaces/$namespace/users/$userId/records/$USAGE_LEDGER_KEY") {
                        header("Authorization", "Bearer $accessToken")
                        header("Accept", "application/json")
                    }

                    if (response.status.isSuccess()) {
                        val responseText = response.bodyAsText()
                        val jsonResponse = AccelByteJson.parseToJsonElement(responseText)
                        val valueElement = if (jsonResponse is kotlinx.serialization.json.JsonObject) {
                            jsonResponse["value"]
                        } else null

                        valueElement.toUsageLedger()
                    } else {
                        UsageLedger(accelByteUserId = userId)
                    }
                } finally {
                    client.close()
                }
            }
        }
    }

    actual suspend fun saveUsageLedger(userId : String, ledger : UsageLedger) : Result<UsageLedger>
    {
        if (userId.startsWith("guest")) return saveLocalLedger(userId, ledger)
        return withContext(Dispatchers.IO) {
            runCatching {
                val client = HttpClient(CIO)
                try {
                    val jsonElement = ledger.toJsonElement()
                    val requestBody = """{"value":${AccelByteJson.encodeToString(jsonElement)}}"""

                    val response = client.put("$baseUrl/cloudsave/v1/admin/namespaces/$namespace/users/$userId/records/$USAGE_LEDGER_KEY") {
                        header("Authorization", "Bearer $accessToken")
                        header("Content-Type", "application/json")
                        setBody(requestBody)
                    }

                    if (response.status.isSuccess()) {
                        ledger
                    } else {
                        throw Exception("Failed to save usage ledger: ${response.status} - ${response.bodyAsText()}")
                    }
                } finally {
                    client.close()
                }
            }
        }
    }

    private suspend fun fetchLocalLedger(userId : String) : Result<UsageLedger>
    {
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = localLedgerFile(userId)
                if (file.exists()) {
                    val json = AccelByteJson.parseToJsonElement(file.readText())
                    AccelByteJson.decodeFromJsonElement(UsageLedger.serializer(), json)
                } else {
                    UsageLedger(accelByteUserId = userId)
                }
            }
        }
    }

    private suspend fun saveLocalLedger(userId : String, ledger : UsageLedger) : Result<UsageLedger>
    {
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = localLedgerFile(userId)
                file.parentFile.mkdirs()
                file.writeText(AccelByteJson.encodeToString(ledger.toJsonElement()))
                ledger
            }
        }
    }

    private fun localLedgerFile(userId : String) : File
    {
        val base = File(System.getProperty("user.home"), ".autogenesis/player-records/$userId")
        return File(base, "$USAGE_LEDGER_KEY.json")
    }

    private fun JsonElement?.toUsageLedger(default : UsageLedger = UsageLedger()) : UsageLedger
    {
        return this?.let { AccelByteJson.decodeFromJsonElement(UsageLedger.serializer(), it) } ?: default
    }

    private fun UsageLedger.toJsonElement() : JsonElement
    {
        return AccelByteJson.encodeToJsonElement(UsageLedger.serializer(), this)
    }
}
