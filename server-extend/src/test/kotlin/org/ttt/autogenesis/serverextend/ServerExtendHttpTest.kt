package org.ttt.autogenesis.serverextend

import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP endpoint tests for the server-extend REST API.
 * 
 * Validates the behavior of REST endpoints including health checks,
 * RPC validation, and proper error handling for missing or invalid
 * player connections.
 */
class ServerExtendHttpTest
{
    /**
     * Tests the player health check endpoint returns proper status.
     * 
     * Verifies that GET /player returns HTTP 200 with expected JSON
     * response indicating server availability.
     */
    @Test
    fun `player endpoint reports alive`() = testApplication {
        application {
            serverModule()
        }

        // Request health check endpoint
        val response = client.get("/player")
        
        // Verify successful response with expected status JSON
        assertEquals(HttpStatusCode.OK, response.status)
        val responseText = response.bodyAsText()
        assertTrue(responseText.contains("\"status\":\"ok\""))
        assertTrue(responseText.contains("\"server\":\"local\""))
    }

    /**
     * Tests RPC endpoint validation for player ID requirements.
     * 
     * Verifies that the RPC endpoint properly validates the required
     * playerId query parameter and returns appropriate error codes
     * for missing parameters and unknown player connections.
     */
    @Test
    fun `rpc endpoint enforces player id`() = testApplication {
        application {
            serverModule()
        }

        // Test missing playerId parameter returns bad request
        val missingId = client.post("/rpc") { setBody("{}") }
        assertEquals(HttpStatusCode.BadRequest, missingId.status)

        // Test unknown playerId returns not found
        val response = client.post("/rpc?playerId=unknown") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{}")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
