package org.ttt.autogenesis.serverextend

import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression coverage for the SSE /events `accelbyteId` stamp bug
 * (2026-08-13).
 *
 * Bug: the `server.extend.uploadMapGate` handler saves the catalogue
 * under the SSE connectionId (`rest-client-...`), but the client
 * reads it back with the AccelByte userId (`accelbyteId` query
 * parameter). Different partitions → listPlayerMaps always returns
 * empty → Collection overlay shows "No maps match" even after
 * reload (the operator's screenshot).
 *
 * Root cause is two-layered:
 *   1. `MapUploadGate.uploadMapGate` hardcoded `userId = playerId =
 *      context.connectionId` for the catalogue save. Pinned by
 *      [network.MapUploadGateCatalogueUserIdTest] — the gate now
 *      resolves `accelbyteId` from the live `RestPlayerSession`.
 *   2. The SSE handler at ServerExtend.kt:406 calls
 *      `connectionManager.register(playerId, origin)` WITHOUT the
 *      `accelbyteId` query param. The session is therefore created
 *      with `accelbyteId = ""`. The gate's
 *      `resolveAccelbyteUserIdForSave` falls back to the connectionId.
 *
 * This file pins layer 2 — the SSE registration MUST thread the
 * `accelbyteId` query parameter through `register()`. The fix is
 * one extra positional argument at the call site. The test
 * deterministically fails (RED) against the pre-fix code and
 * passes (GREEN) against the patched code.
 *
 * Verification: open `http://localhost:7070/events?accelbyteId=X`
 * and read back the session's `accelbyteId` field via
 * `findSession()`. Before the fix, `accelbyteId` is always blank
 * regardless of the URL parameter. After the fix, the parameter is
 * stamped on the session.
 */
class ServerExtendSseAccelbyteIdTest
{
    /**
     * RED before fix: connect SSE with `accelbyteId=004c...` query
     * parameter, then read back the live session's accelbyteId via
     * the test hook. Before the fix the session's accelbyteId is
     * `""` (the SSE handler did not thread the query param). After
     * the fix it carries `004c...`.
     *
     * The test asserts the upstream layer so the gate (layer 1) can
     * resolve accelbyteId from the live session, NOT fall back to
     * the connectionId.
     */
    @Test
    fun sseRegisterAccelbyteIdIsStampedOnSession(): Unit = runBlocking {
        testApplication {
            application { serverModule() }
            // Trigger application startup so [serverModule] runs and the
            // test hook [testHooks_lastConnectionManager] is populated.
            client.get("/player")
            val m = testHooks_lastConnectionManager
            assertNotNull(
                m,
                "testHooks_lastConnectionManager must be set after serverModule() runs"
            )

            val accelbyteId = "00000000-0000-0000-0000-000000000001"
            val playerId = "rest-client-sse-stamp-test"

            try {
                coroutineScope {
                    val sseJob = launch(Dispatchers.IO) {
                        // The accelbyteId query parameter on the SSE URL
                        // is the canonical wire signal the client sends.
                        // Production wires this from `Main.kt:110-127`
                        // (?skipLogin=true → accelbyteId=guest-user; real
                        // OAuth login → AccelByteEnv.userId from the
                        // accelbyte SDK).
                        client.get(
                            "/events?playerId=$playerId&accelbyteId=$accelbyteId"
                        )
                    }
                    // Poll for the session registration. The SSE handler
                    // reaches `connectionManager.register(playerId, origin,
                    // accelbyteId)` and the test asserts the session's
                    // accelbyteId field is non-blank.
                    var session = m.findSession(playerId)
                    var attempts = 0
                    while (session == null && attempts < 100)
                    {
                        delay(50)
                        session = m.findSession(playerId)
                        attempts += 1
                    }
                    assertNotNull(
                        session,
                        "RestPlayerSession must be registered for playerId=$playerId " +
                                "within 5s of the SSE open. The SSE handler is " +
                                "expected to call connectionManager.register(...) " +
                                "synchronously before responding."
                    )

                    // THE PINNED CONTRACT: the session must carry the
                    // accelbyteId from the SSE URL. Without this stamp
                    // (the bug), MapUploadGate.resolveAccelbyteUserIdForSave
                    // falls back to the connectionId and saves/reads the
                    // catalogue in two different partitions.
                    assertEquals(
                        accelbyteId, session.accelbyteId,
                        "RestPlayerSession.accelbyteId must equal the URL query " +
                                "parameter. Got '${session.accelbyteId}' for playerId=" +
                                "$playerId. Without this stamp, MapUploadGate saves under " +
                                "the connectionId and the client's listPlayerMaps (under " +
                                "AccelByteEnv.userId) returns empty — the operator's " +
                                "'collection does not update' bug. Fix: ServerExtend.kt SSE " +
                                "handler threads the accelbyteId query param into " +
                                "connectionManager.register(playerId, origin, accelbyteId)."
                    )

                    sseJob.cancel()
                }
            }
            finally
            {
                m.deregister(playerId)
            }
        }
    }

    /**
     * RED before fix: legacy clients (curl probes, no `?accelbyteId=`
     * in the URL) must still register cleanly with `accelbyteId = ""`
     * so the gate's `resolveAccelbyteUserIdForSave` fallback path
     * (returns the connectionId) keeps working.
     */
    @Test
    fun sseRegisterWithoutAccelbyteIdCarriesEmptyStringForFallback(): Unit = runBlocking {
        testApplication {
            application { serverModule() }
            client.get("/player")
            val m = testHooks_lastConnectionManager
            assertNotNull(m)

            val playerId = "rest-client-sse-noaccelbyteid"

            try {
                coroutineScope {
                    val sseJob = launch(Dispatchers.IO) {
                        client.get("/events?playerId=$playerId")
                    }
                    var session = m.findSession(playerId)
                    var attempts = 0
                    while (session == null && attempts < 100)
                    {
                        delay(50)
                        session = m.findSession(playerId)
                        attempts += 1
                    }
                    assertNotNull(session)
                    assertEquals(
                        "", session.accelbyteId,
                        "Legacy SSE clients without accelbyteId must register " +
                                "with an empty string so the gate's connectionId " +
                                "fallback keeps working."
                    )
                    sseJob.cancel()
                }
            }
            finally
            {
                m.deregister(playerId)
            }
        }
    }

    private suspend fun delay(ms: Long) = kotlinx.coroutines.delay(ms)
}
