package accelbyte.dsm

import accelbyte.AccelByteSdkProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

private val jsonFormat = Json { ignoreUnknownKeys = true; encodeDefaults = false }
private val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(jsonFormat)
    }
}

internal object DsmControllerRest
{
    private val sdk get() = AccelByteSdkProvider.sdk
    private val namespace get() = AccelByteSdkProvider.namespace

    private fun baseUrl(): String {
        val rawUrl = sdk.sdkConfiguration.configRepository.getBaseURL()
        Logger.debug(LogCategory.NETWORK, "Raw base URL from SDK config: '$rawUrl'")
        
        if (rawUrl.isBlank()) {
            error("Environment variable AB_BASE_URL must be set before calling DSM controller APIs")
        }
        
        val sanitizedUrl = accelbyte.UrlValidator.sanitizeUrl(rawUrl)
        val trimmedUrl = sanitizedUrl.trimEnd('/')
        Logger.debug(LogCategory.NETWORK, "Sanitized and trimmed base URL: '$trimmedUrl'")
        
        // Validate URL format using the validator
        if (!accelbyte.UrlValidator.validateAndLogUrl("DSM Base", trimmedUrl)) {
            error("Invalid base URL format: '$trimmedUrl'. Must be a valid HTTP/HTTPS URL")
        }
        
        return trimmedUrl
    }

    private fun ensureToken(): String {
        val tokenRepo = sdk.sdkConfiguration.tokenRepository
        val hasToken = runCatching { tokenRepo.isTokenAvailable() }.getOrDefault(false)
        if (!hasToken) {
            Logger.warn(LogCategory.AUTH, "Client token missing, attempting login for DSM controller")
            runCatching { sdk.loginClient() }.getOrNull() ?: error("Unable to acquire client token before contacting DSM controller")
            Logger.info(LogCategory.AUTH, "Client token acquired for DSM controller")
        }
        return runCatching { tokenRepo.getToken() }
            .getOrNull()
            ?.takeUnless { it.isBlank() }
            ?: error("DSM controller client token is empty")
    }

    private fun request(
        method: HttpMethod,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: JsonElement? = null
    ): Result<JsonElement> = runCatching {
        val token = ensureToken()

        val requestDetails = "method=$method path=$path query=${query.formatQuery()} body=${body.safeSummary()}"
        Logger.debug(LogCategory.NETWORK, "DSM request starting: $requestDetails")

        val fullUrl = "${baseUrl()}$path"
        Logger.debug(LogCategory.NETWORK, "Full URL being constructed: '$fullUrl'")

        val httpResponse: HttpResponse = runBlocking {
            httpClient.request {
                this.method = method
                url(fullUrl)
                header(HttpHeaders.Authorization, "Bearer $token")
                query.forEach { (key, value) ->
                    value?.let { url.parameters.append(key, it) }
                }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        }
        val text = runBlocking { httpResponse.body<String>() }
        Logger.debug(LogCategory.NETWORK, "DSM response (${httpResponse.status.value}) for $path length=${text.length}")
        if (text.isBlank()) JsonNull else Json.parseToJsonElement(text)
    }

    fun fetchMessages(): Result<JsonElement> =
        request(HttpMethod.Get, "/dsmcontroller/v1/messages")

    fun listServers(count: Int, offset: Int, region: String?): Result<JsonElement> =
        request(
            HttpMethod.Get,
            "/dsmcontroller/namespaces/$namespace/servers",
            mapOf("count" to count.toString(), "offset" to offset.toString(), "region" to region)
        )

    fun registerServer(payload: JsonElement): Result<JsonElement> =
        request(HttpMethod.Post, "/dsmcontroller/namespaces/$namespace/servers/register", body = payload)

    fun shutdownServer(payload: JsonElement): Result<JsonElement> =
        request(HttpMethod.Post, "/dsmcontroller/namespaces/$namespace/servers/shutdown", body = payload)

    fun heartbeatServer(payload: JsonElement): Result<JsonElement> =
        request(HttpMethod.Put, "/dsmcontroller/namespaces/$namespace/servers/heartbeat", body = payload)

    fun countDetailed(region: String?): Result<JsonElement> =
        request(
            HttpMethod.Get,
            "/dsmcontroller/namespaces/$namespace/servers/count/detailed",
            mapOf("region" to region)
        )

    fun registerLocalServer(payload: JsonElement): Result<JsonElement> =
        request(HttpMethod.Post, "/dsmcontroller/namespaces/$namespace/servers/local/register", body = payload)

    fun deregisterLocalServer(payload: JsonElement): Result<JsonElement> =
        request(HttpMethod.Post, "/dsmcontroller/namespaces/$namespace/servers/local/deregister", body = payload)

    fun getSessionByPod(podName: String): Result<JsonElement> =
        request(HttpMethod.Get, "/dsmcontroller/namespaces/$namespace/servers/$podName/session")

    fun getSessionTimeout(podName: String): Result<JsonElement> =
        request(
            HttpMethod.Get,
            "/dsmcontroller/namespaces/$namespace/servers/$podName/config/sessiontimeout"
        )

    fun listDeployments(count: Int, offset: Int, name: String?): Result<JsonElement> =
        request(
            HttpMethod.Get,
            "/dsmcontroller/namespaces/$namespace/configs/deployments",
            mapOf("count" to count.toString(), "offset" to offset.toString(), "name" to name)
        )

    fun getDeployment(deployment: String): Result<JsonElement> =
        request(HttpMethod.Get, "/dsmcontroller/namespaces/$namespace/configs/deployments/$deployment")

    fun createDeployment(deployment: String, payload: JsonElement): Result<JsonElement> =
        request(
            HttpMethod.Post,
            "/dsmcontroller/namespaces/$namespace/configs/deployments/$deployment",
            body = payload
        )

    fun deleteDeployment(deployment: String): Result<JsonElement> =
        request(HttpMethod.Delete, "/dsmcontroller/namespaces/$namespace/configs/deployments/$deployment")

    fun createSession(payload: JsonElement): Result<JsonElement> =
        request(HttpMethod.Post, "/dsmcontroller/namespaces/$namespace/sessions", body = payload)

    fun claimSession(payload: JsonElement): Result<JsonElement> =
        request(HttpMethod.Put, "/dsmcontroller/namespaces/$namespace/sessions/claim", body = payload)

    fun getSession(sessionId: String): Result<JsonElement> =
        request(HttpMethod.Get, "/dsmcontroller/namespaces/$namespace/sessions/$sessionId")

    fun cancelSession(sessionId: String): Result<JsonElement> =
        request(
            HttpMethod.Delete,
            "/dsmcontroller/namespaces/$namespace/sessions/$sessionId/cancel"
        )
}

private fun JsonElement?.safeSummary(maxLength: Int = 200): String =
    this?.toString()?.replace(Regex("\\s+"), " ")?.trim()?.take(maxLength) ?: "null"

private fun Map<String, String?>.formatQuery(): String =
    entries.joinToString(",") { "${it.key}=${it.value ?: "null"}" }