package org.ttt.autogenesis.serverextend

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis

/**
 * Integration tests that exercise the real SSE/POST race that produced the
 * 2026-06-18 master-record softlock.
 *
 * The race is forced deterministically by setting
 * [RestPlayerConnectionManager.registerDelayMillis] to a value (e.g. 50ms)
 * that holds the SSE handler inside [RestPlayerConnectionManager.register]
 * long enough for the Ktor dispatcher to also start serving the POST. With
 * the pre-fix handler, the POST ran `findSession()` first and 404'd. With
 * the post-fix handler, `awaitSession()` waits for the SSE registration
 * to land and the POST returns 202.
 *
 * Production code leaves [RestPlayerConnectionManager.registerDelayMillis]
 * at 0, so this hook is a no-op in production.
 *
 * The test reaches the [RestPlayerConnectionManager] instance via the
 * [testHooks_lastConnectionManager] test hook, which [serverModule] populates
 * during application startup. We trigger startup with a `/player` health-check
 * GET before reading the hook, then flip the delay for the race test.
 */
class ServerExtendHttpRaceTest
{
    private val validRpcJson = """{"type":"notification","method":"session.ping"}"""

    /**
     * Race test #1: with the SSE registration artificially delayed by 50ms,
     * the POST that arrives concurrently must still be accepted (202), not
     * rejected as 404. This is the exact softlock the bug fix closes.
     */
    @Test
    fun postRpcRacingSseRegistrationIsAccepted() : Unit = runBlocking {
        testApplication {
            application { serverModule() }
            // Trigger application startup so [serverModule] runs and the
            // test hook is populated.
            client.get("/player")
            val m = testHooks_lastConnectionManager
            assertNotNull(m, "testHooks_lastConnectionManager should be set after serverModule() runs")
            m.registerDelayMillis = 50

            try {
                coroutineScope {
                    val sseJob = launch(Dispatchers.IO) {
                        // Open the SSE channel. The server will call
                        // `connectionManager.register(playerId)`, but with the
                        // 50ms artificial delay it will block in the manager
                        // long enough for our POST to land first.
                        client.get("/events?playerId=race-1")
                    }
                    // Give the SSE coroutine a chance to actually start and
                    // enter the server-side handler.
                    delay(20)
                    val postResponse = client.post("/rpc?playerId=race-1") {
                        headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(validRpcJson)
                    }
                    assertEquals(HttpStatusCode.Accepted, postResponse.status,
                        "POST /rpc that races SSE registration must be accepted (202), not 404")
                    sseJob.cancel()
                }
            } finally {
                m.registerDelayMillis = 0
            }
            Unit
        }
    }

    /**
     * Race test #2: a POST with no SSE channel at all must still return 404,
     * not hang forever. Proves the awaitSession timeout in the handler is
     * wired up and respects its budget (~1s + jitter).
     */
    @Test
    fun postRpcWithNoSseEventuallyReturns404() : Unit = runBlocking {
        testApplication {
            application { serverModule() }
            client.get("/player")
            val m = testHooks_lastConnectionManager
            assertNotNull(m, "testHooks_lastConnectionManager should be set after serverModule() runs")
            m.registerDelayMillis = 0

            val elapsed = measureTimeMillis {
                val response = client.post("/rpc?playerId=ghost-1") {
                    headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(validRpcJson)
                }
                assertEquals(HttpStatusCode.NotFound, response.status,
                    "POST /rpc with no SSE must return 404, was: ${response.status}")
            }
            // awaitSession default timeout is 1000ms with 25ms poll. Allow some
            // jitter on slow CI but enforce that we did wait.
            assertTrue(elapsed >= 900, "expected ~1000ms wait, was ${elapsed}ms (too fast — did the handler wait?)")
            assertTrue(elapsed < 3000, "expected <3000ms wait, was ${elapsed}ms (handler is overshooting)")
            Unit
        }
    }

    /**
     * Regression test: the existing happy path (POST after SSE) must still
     * return 202. Mirrors the assertions in [ServerExtendHttpTest] but uses
     * the rewritten handler.
     */
    @Test
    fun postRpcAfterSseEstablishedIsAccepted() : Unit = runBlocking {
        testApplication {
            application { serverModule() }
            client.get("/player")
            val m = testHooks_lastConnectionManager
            assertNotNull(m, "testHooks_lastConnectionManager should be set after serverModule() runs")
            m.registerDelayMillis = 0

            coroutineScope {
                val sseJob = launch(Dispatchers.IO) {
                    client.get("/events?playerId=happy-1")
                }
                // Yield long enough for the SSE handler to complete registration.
                delay(100)
                val postResponse = client.post("/rpc?playerId=happy-1") {
                    headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(validRpcJson)
                }
                assertEquals(HttpStatusCode.Accepted, postResponse.status,
                    "POST /rpc after SSE should be accepted, was: ${postResponse.status}")
                sseJob.cancel()
            }
            Unit
        }
    }
}
