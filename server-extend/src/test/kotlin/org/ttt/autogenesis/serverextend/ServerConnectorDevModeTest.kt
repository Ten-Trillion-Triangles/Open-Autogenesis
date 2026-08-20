package org.ttt.autogenesis.serverextend

import globals.ExtendConfig
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import matchmaking.ServerConnector
import matchmaking.UrlHandoverRegistry
import org.junit.After
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import structs.matchmaking.GameRequest
import structs.matchmaking.GameTicket
import structs.matchmaking.GameType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse

/**
 * Verifies the server-extend matchmaking handler still returns the local dev ticket,
 * and that calling resolveUrl captures the URL into the UrlHandoverRegistry.
 */
class ServerConnectorDevModeTest
{
    private val originalDebugMode = ExtendConfig.debugMode
    private val originalRegistry = ServerConnector.urlHandoverRegistry

    /**
     * Restores the debug-mode flag and the default UrlHandoverRegistry after each test.
     */
    @After
    fun restoreDebugMode()
    {
        ExtendConfig.debugMode = originalDebugMode
        ServerConnector.urlHandoverRegistry = originalRegistry
        unmockkAll()
    }

    /**
     * Confirms the dev-mode matchmaking handler returns the expected local game ticket.
     */
    @Test
    fun `dev-mode matchmaking handler returns local ticket`() = runTest {
        ExtendConfig.debugMode = true

        val request = GameRequest(
            userName = "test-user",
            gameType = GameType.SINGLEPLAYER,
            accelByteId = "test-accelbyte-id"
        )

        val ticket = ServerConnector.invokeMatchMaking(
            RpcCallContext(connectionId = "unit-test-player")
            {
                error("The dev-mode matchmaking handler should not emit outbound messages")
            },
            request
        )

        assertNotNull(ticket)
        assertEquals("127.0.0.1:9080", ticket.serverUrl)
        assertFalse(ticket.matchmakingStarted)
    }

    /**
     * Confirms that calling resolveUrl in dev mode captures the (sessionId -> url)
     * entry into the UrlHandoverRegistry. The capture point is the moment
     * server-extend is about to hand the URL back to a client; every staleness
     * listener wired elsewhere in the module reads this map to evict stale
     * entries. See
     * `.hermes/plans/2026-06-28_090808-server-extend-url-handover-registry.md`
     * for the listener set.
     *
     * The flow exercised here is the production dev-mode hand-off:
     *   1. Client calls `server.extend.requestGame` (which in dev mode
     *      generates a UUID sessionId and writes the GameSessionStatus).
     *   2. Client calls `server.extend.resolveUrl` with the same
     *      sessionId, which reads the status back AND captures it
     *      into the registry.
     *
     * `requestGame` returns `Boolean` so the sessionId is read out of
     * `ServerConnector.gameSessions` via reflection-style access
     * through the `internal` field. We rebuild a `GameTicket` with
     * the discovered sessionId and pass it to `resolveUrl`.
     */
    @Test
    fun `resolveUrl in dev mode captures the URL into the UrlHandoverRegistry`() = runTest {
        ExtendConfig.debugMode = true

        val registry = UrlHandoverRegistry()
        ServerConnector.urlHandoverRegistry = registry

        // Spy on notifyGameServer so requestGame sees the WS as acked
        // without opening a real socket. requestGame returns Boolean,
        // so this is the only way to drive it in a unit test.
        mockkObject(ServerConnector)
        coEvery { ServerConnector.notifyGameServer(any()) } returns true

        val ack = ServerConnector.requestGame(
            RpcCallContext(connectionId = "unit-test-capture")
            {
                error("The dev-mode requestGame handler should not emit outbound messages")
            },
            GameRequest(
                userName = "capture-user",
                gameType = GameType.SINGLEPLAYER,
                accelByteId = "capture-accelbyte-id"
            )
        )
        assertEquals(true, ack, "requestGame must ack in dev mode (notifyGameServer spy returns true)")

        // Discover the sessionId written by requestGame by reading the
        // internal gameSessions key set under its mutex. The singleton
        // may already hold entries from earlier tests in the same JVM
        // run, so we look for the specific sessionId that was just
        // registered by matching against the playerBundle we passed in
        // via context.connectionId. requestGame writes the dev session
        // with a PlayerSessionBundle whose websocketId is the
        // connectionId ("unit-test-capture") when the request's
        // websocketId is blank.
        val sessionIds = ServerConnector.gameSessionIds()
        val newSessionId = sessionIds.firstOrNull { sid ->
            val status = ServerConnector.gameSessionStatus(sid)
            status?.players?.any { it.websocketId == "unit-test-capture" } == true
        }
        assertNotNull(newSessionId,
            "expected requestGame to register a dev session owned by the connectionId 'unit-test-capture'")
        val sessionId = newSessionId

        val resolved = ServerConnector.resolveUrl(
            RpcCallContext(connectionId = "unit-test-capture")
            {
                error("resolveUrl should not emit outbound messages")
            },
            GameTicket(sessionId = sessionId, serverUrl = "")
        )
        assertEquals("127.0.0.1:9080", resolved,
            "resolveUrl must echo the stored serverUrl")

        assertEquals("127.0.0.1:9080", registry.get(sessionId),
            "registry must contain ($sessionId -> 127.0.0.1:9080) after resolveUrl")
    }
}