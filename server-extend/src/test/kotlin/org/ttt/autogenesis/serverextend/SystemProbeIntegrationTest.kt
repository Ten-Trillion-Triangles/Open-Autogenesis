package org.ttt.autogenesis.serverextend

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.SystemProbeResponse
import org.ttt.autogenesis.serverextend.serverModule
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test for the server-extend system probe over the REST RPC endpoint.
 *
 * Boots the full [serverExtendModule] in-process and calls `serverextend.system.probe`
 * via a real HTTP POST to `/rpc/probe`. This validates the end-to-end path:
 * JSON serialization → Ktor routing → RPC dispatch → handler execution →
 * JSON response — without mocking the transport or registry.
 */
class SystemProbeIntegrationTest
{
    private val serdeJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Parses the raw HTTP response body (an `RpcMessage.Response` envelope) and
     * extracts the `result` field as a [SystemProbeResponse].
     */
    private fun parseProbeResponse(body: String): SystemProbeResponse
    {
        val envelope = serdeJson.parseToJsonElement(body).jsonObject
        val resultField = envelope["result"]
        assertTrue(resultField != null && resultField !is JsonNull, "result field must not be null: $body")
        val resultJson = serdeJson.encodeToString(JsonElement.serializer(), resultField)
        return RpcJson.decodeFromString(SystemProbeResponse.serializer(), resultJson)
    }

    @Test
    fun `serverextend system probe returns valid SystemProbeResponse over REST`() = testApplication {
        application {
            serverModule()
        }

        val response = client.post("/rpc/probe") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "method": "serverextend.system.probe",
                    "params": null,
                    "id": 1
                }
                """.trimIndent()
            )
        }

        assertEquals(200, response.status.value, "Probe should return HTTP 200: ${response.bodyAsText()}")
        val probe = parseProbeResponse(response.bodyAsText())
        assertEquals("serverextend", probe.module)
        assertTrue(probe.timestamp > 0, "Timestamp should be positive")
        assertTrue(probe.subsystems.isNotEmpty(), "Should report at least one subsystem")
    }

    @Test
    fun `serverextend system probe response contains expected subsystem names`() = testApplication {
        application {
            serverModule()
        }

        val response = client.post("/rpc/probe") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "method": "serverextend.system.probe",
                    "params": null,
                    "id": 2
                }
                """.trimIndent()
            )
        }

        val probe = parseProbeResponse(response.bodyAsText())
        val subsystemNames = probe.subsystems.map { it.subsystem }

        // Every probe should include these core subsystems
        listOf("AccelByteConfig", "AwsCredentialsBootstrap", "VirtualFileSystemManager").forEach { name ->
            assertTrue(
                subsystemNames.contains(name),
                "Core subsystem '$name' not found in probe response. Got: $subsystemNames"
            )
        }
    }

    @Test
    fun `serverextend system probe detail fields contain no credentials`() = testApplication {
        application {
            serverModule()
        }

        val response = client.post("/rpc/probe") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "method": "serverextend.system.probe",
                    "params": null,
                    "id": 3
                }
                """.trimIndent()
            )
        }

        val probe = parseProbeResponse(response.bodyAsText())

        // Credential patterns that must NEVER appear in any detail field
        val secretPatterns = listOf("client_secret", "aws_secret_access_key", "AB_CLIENT_SECRET", "AB_CLIENT_SECRET=")
        probe.subsystems.forEach { status ->
            val detail = status.detail ?: return@forEach
            secretPatterns.forEach { pattern ->
                assertTrue(
                    !detail.contains(pattern, ignoreCase = true),
                    "Credential pattern '$pattern' found in subsystem '${status.subsystem}' detail: $detail"
                )
            }
        }
    }

    @Test
    fun `serverextend system probe returns proper response envelope`() = testApplication {
        application {
            serverModule()
        }

        val response = client.post("/rpc/probe") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "method": "serverextend.system.probe",
                    "params": null,
                    "id": 99
                }
                """.trimIndent()
            )
        }

        val text = response.bodyAsText()
        val envelope = serdeJson.parseToJsonElement(text).jsonObject

        // The response must contain an `id` field matching the request
        val idField = envelope["id"]
        assertNotNull(idField, "Response should contain 'id' field: $text")
        assertTrue(idField is JsonPrimitive, "id should be a primitive: $idField")

        // result must be present and not null
        val resultField = envelope["result"]
        assertTrue(resultField != null && resultField !is JsonNull, "Response should contain non-null 'result' field: $text")

        // error must be absent or null
        val errorField = envelope["error"]
        assertTrue(errorField == null || errorField is JsonNull, "Response should not contain an error: $text")
    }

    @Test
    fun `serverextend system probe handles unknown method gracefully`() = testApplication {
        application {
            serverModule()
        }

        val response = client.post("/rpc/probe") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "method": "serverextend.nonexistent.probe",
                    "params": null,
                    "id": 5
                }
                """.trimIndent()
            )
        }

        val text = response.bodyAsText()
        val envelope = serdeJson.parseToJsonElement(text).jsonObject

        // Should return a JSON-RPC error response, not crash
        assertTrue(envelope.containsKey("error") || !envelope.containsKey("result"))
    }
}