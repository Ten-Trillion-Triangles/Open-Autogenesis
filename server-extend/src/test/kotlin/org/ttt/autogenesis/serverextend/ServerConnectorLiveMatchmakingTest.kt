package org.ttt.autogenesis.serverextend

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import matchmaking.ServerConnector
import matchmaking.UrlHandoverRegistry
import net.accelbyte.sdk.api.match2.models.ApiMatchTicketResponse
import net.accelbyte.sdk.api.match2.models.ApiMatchTicketStatus
import net.accelbyte.sdk.api.match2.wrappers.MatchTickets
import net.accelbyte.sdk.api.session.models.ApimodelsDSInformationResponse
import net.accelbyte.sdk.api.session.models.ApimodelsGameSessionResponse
import net.accelbyte.sdk.api.session.models.ApimodelsPublicConfiguration
import net.accelbyte.sdk.api.session.models.ApimodelsUserResponse
import net.accelbyte.sdk.api.session.wrappers.GameSession
import net.accelbyte.sdk.api.session.models.ModelsGameServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import structs.matchmaking.GameRequest
import structs.matchmaking.GameSessionStatus
import structs.matchmaking.GameType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the live PvP matchmaking flow in [ServerConnector] — a match2 ticket plus
 * session polling round-trip resolves into a `GameTicket` with a real server URL, and the
 * resolved [GameSessionStatus] is forwarded to the dedicated game server via
 * [ServerConnector.notifyGameServer].
 *
 * The AccelByte SDK wrappers ([MatchTickets], [GameSession]) are injected through the
 * `internal var` testable seam added on [ServerConnector]. [ServerConnector.notifyGameServer]
 * is spied on with `mockkObject` so the forwarded payload can be asserted without opening a
 * real WebSocket. [ServerConnector.MATCHMAKING_TIMEOUT_MS] and
 * [ServerConnector.MATCHMAKING_POLL_INTERVAL_MS] are temporarily shortened to keep the
 * suite fast.
 */
class ServerConnectorLiveMatchmakingTest
{
    private val originalDebugMode = globals.ExtendConfig.debugMode
    private val originalTimeoutMs = ServerConnector.MATCHMAKING_TIMEOUT_MS
    private val originalPollIntervalMs = ServerConnector.MATCHMAKING_POLL_INTERVAL_MS
    private val originalNamespace: String? = System.getenv("AB_NAMESPACE")
    private val originalRegistry = ServerConnector.urlHandoverRegistry

    @Before
    fun setup()
    {
        // Ensure we're exercising the live code path, not the dev fast-path.
        globals.ExtendConfig.debugMode = false
        // Make sure AB_NAMESPACE is set so AccelByteConfig.getNamespace() doesn't throw.
        if(System.getenv("AB_NAMESPACE").isNullOrBlank())
        {
            System.setProperty("AB_NAMESPACE", "test-namespace")
        }
        // Shrink the timeout and poll interval for fast tests.
        ServerConnector.MATCHMAKING_TIMEOUT_MS = 4_000L
        ServerConnector.MATCHMAKING_POLL_INTERVAL_MS = 50L
    }

    @After
    fun tearDown()
    {
        globals.ExtendConfig.debugMode = originalDebugMode
        ServerConnector.MATCHMAKING_TIMEOUT_MS = originalTimeoutMs
        ServerConnector.MATCHMAKING_POLL_INTERVAL_MS = originalPollIntervalMs
        if(originalNamespace == null)
        {
            System.clearProperty("AB_NAMESPACE")
        }
        ServerConnector.urlHandoverRegistry = originalRegistry
        unmockkAll()
    }

    /**
     * Confirms that a successful live match round-trip:
     *  - returns a [GameTicket] with the resolved server URL and sessionId
     *  - forwards a [GameSessionStatus] whose player list contains the expected
     *    AccelByte user IDs to the game server via [ServerConnector.notifyGameServer]
     */
    @Test
    fun `live match2 round trip resolves ticket and forwards session status to game server`() = runBlocking {
        val matchTickets = mockk<MatchTickets>(relaxed = false)
        val gameSession = mockk<GameSession>(relaxed = false)

        val ticketId = "ticket-abc-123"
        val matchId = "match-123"
        val sessionId = matchId

        val createdTicket = ApiMatchTicketResponse().apply { matchTicketID = ticketId }
        val notMatched = ApiMatchTicketStatus().apply {
            matchFound = false
            this.matchTicketID = ticketId
            isActive = true
        }
        val matched = ApiMatchTicketStatus().apply {
            matchFound = true
            this.matchTicketID = ticketId
            this.sessionID = matchId
            isActive = true
        }

        every { matchTickets.createMatchTicket(any()) } returns createdTicket
        every { matchTickets.matchTicketDetails(any()) } returnsMany listOf(notMatched, matched)
        every { matchTickets.deleteMatchTicket(any()) } returns mockk(relaxed = true)

        val member1 = ApimodelsUserResponse().apply {
            id = "u-1"
            platformUserID = "steam-1"
        }
        val member2 = ApimodelsUserResponse().apply {
            id = "u-2"
            platformUserID = "steam-2"
        }

        val gameServer = ModelsGameServer().apply {
            ip = "10.0.0.5"
            port = 7777
        }
        val dsInfo = ApimodelsDSInformationResponse().apply {
            server = gameServer
        }
        val session = ApimodelsGameSessionResponse().apply {
            this.id = sessionId
            this.dsInformation = dsInfo
            this.members = listOf(member1, member2)
            this.configuration = ApimodelsPublicConfiguration().apply { maxPlayers = 4 }
        }
        every { gameSession.getGameSession(any()) } returns session

        // Inject mocks through the testable seam on ServerConnector.
        ServerConnector.matchTicketsWrapper = matchTickets
        ServerConnector.gameSessionWrapper = gameSession

        // Spy on notifyGameServer so we can capture the forwarded GameSessionStatus without
        // opening a real WebSocket.
        val captured = slot<GameSessionStatus>()
        mockkObject(ServerConnector)
        coEvery { ServerConnector.notifyGameServer(capture(captured)) } returns true

        val request = GameRequest(
            userName = "test-user",
            gameType = GameType.MULTIPLAYER,
            accelByteId = "test-accelbyte-id"
        )

        val acknowledged = ServerConnector.requestGame(
            RpcCallContext(connectionId = "unit-test-player") { error("not expected") },
            request
        )

        assertTrue(acknowledged, "expected requestGame to return true when match is found and notifyGameServer is acknowledged")

        // Verify the matchmaking wrappers were called as expected.
        coVerify(atLeast = 1) { matchTickets.createMatchTicket(any()) }
        coVerify(atLeast = 2) { matchTickets.matchTicketDetails(any()) }
        coVerify(exactly = 1) { gameSession.getGameSession(any()) }

        // Verify notifyGameServer was called with a GameSessionStatus carrying the resolved
        // session ID and server URL. The requester is the only player on the
        // notifyGameServer-bound session (requestGame adds the local requester; the full
        // matched-roster is populated server-side when GameInit.defineGameRules runs).
        coVerify(exactly = 1) { ServerConnector.notifyGameServer(any()) }
        val forwarded = captured.captured
        assertEquals(matchId, forwarded.sessionId)
        assertEquals("10.0.0.5:7777", forwarded.serverUrl)
        assertEquals(1, forwarded.players.size)
        assertEquals("test-accelbyte-id", forwarded.players.first().accelByteId)
        assertEquals(GameType.MULTIPLAYER, forwarded.gameType)
    }

    /**
     * Confirms that when the matchmaking ticket never reports a match within the polling
     * window, [ServerConnector.requestGame] returns `false` and [ServerConnector.notifyGameServer]
     * is never invoked (no GameSessionStatus to forward).
     */
    @Test
    fun `live match2 timeout cancels ticket and requestGame returns false`() = runBlocking {
        val matchTickets = mockk<MatchTickets>(relaxed = false)
        val gameSession = mockk<GameSession>(relaxed = false)

        val ticketId = "ticket-abc-456"
        val createdTicket = ApiMatchTicketResponse().apply { matchTicketID = ticketId }
        val neverMatched = ApiMatchTicketStatus().apply {
            matchFound = false
            this.matchTicketID = ticketId
            isActive = true
        }

        every { matchTickets.createMatchTicket(any()) } returns createdTicket
        every { matchTickets.matchTicketDetails(any()) } returns neverMatched
        every { matchTickets.deleteMatchTicket(any()) } returns mockk(relaxed = true)

        ServerConnector.matchTicketsWrapper = matchTickets
        ServerConnector.gameSessionWrapper = gameSession
        mockkObject(ServerConnector)
        coEvery { ServerConnector.notifyGameServer(any()) } returns true

        val request = GameRequest(
            userName = "test-user",
            gameType = GameType.MULTIPLAYER,
            accelByteId = "test-accelbyte-id"
        )

        val acknowledged = ServerConnector.requestGame(
            RpcCallContext(connectionId = "unit-test-player") { error("not expected") },
            request
        )

        assertEquals(false, acknowledged)
        coVerify(atLeast = 1) { matchTickets.createMatchTicket(any()) }
        coVerify(atLeast = 1) { matchTickets.matchTicketDetails(any()) }
        // The ticket should have been cancelled via deleteMatchTicket when the loop timed out.
        coVerify(atLeast = 1) { matchTickets.deleteMatchTicket(any()) }
        // The DS was never contacted because no match was found.
        coVerify(exactly = 0) { ServerConnector.notifyGameServer(any()) }
    }

    /**
     * Pins the staleness invariant for the matchmaking-timeout path:
     *   - The URL registry is never written by the timeout path (no
     *     match means no GameSessionStatus, so resolveUrl is never
     *     called for the timeout's sessionId).
     *   - Unrelated registry entries (owned by other players, other
     *     sessions, prior unrelated captures) MUST survive the
     *     timeout — the listener must not be over-eager.
     *
     * The test pre-populates a stale entry under a different
     * sessionId, drives the live timeout path, and asserts both
     * invariants. See
     * `.hermes/plans/2026-06-28_090808-server-extend-url-handover-registry.md`
     * for the listener set.
     */
    @Test
    fun `live match2 timeout does not touch the URL registry and preserves unrelated entries`() = runBlocking {
        val matchTickets = mockk<MatchTickets>(relaxed = false)
        val gameSession = mockk<GameSession>(relaxed = false)

        val ticketId = "ticket-abc-789"
        val createdTicket = ApiMatchTicketResponse().apply { matchTicketID = ticketId }
        val neverMatched = ApiMatchTicketStatus().apply {
            matchFound = false
            this.matchTicketID = ticketId
            isActive = true
        }

        every { matchTickets.createMatchTicket(any()) } returns createdTicket
        every { matchTickets.matchTicketDetails(any()) } returns neverMatched
        every { matchTickets.deleteMatchTicket(any()) } returns mockk(relaxed = true)

        ServerConnector.matchTicketsWrapper = matchTickets
        ServerConnector.gameSessionWrapper = gameSession
        mockkObject(ServerConnector)
        coEvery { ServerConnector.notifyGameServer(any()) } returns true

        // Pre-populate a stale entry under an unrelated sessionId. A
        // future refactor that broadens the timeout path's eviction
        // into a wildcard (e.g. clearAll) would wipe this and the
        // assertion would catch it.
        val registry = UrlHandoverRegistry()
        val unrelatedSessionId = "unrelated-stale-session-xyz"
        registry.put(unrelatedSessionId, "10.0.0.99:7777")
        ServerConnector.urlHandoverRegistry = registry

        val acknowledged = ServerConnector.requestGame(
            RpcCallContext(connectionId = "unit-test-timeout-stale") { error("not expected") },
            GameRequest(
                userName = "timeout-stale-user",
                gameType = GameType.MULTIPLAYER,
                accelByteId = "timeout-stale-accelbyte"
            )
        )

        assertEquals(false, acknowledged, "timeout must return false")

        // Invariant 1: the registry must NOT contain a blank sessionId
        // (the timeout path returns blank sessionId, so resolveUrl
        // can never capture under a blank key).
        assertNull(registry.get(""),
            "the timeout path must never write a blank sessionId into the registry")

        // Invariant 2: the unrelated entry survives.
        assertEquals("10.0.0.99:7777", registry.get(unrelatedSessionId),
            "the timeout path must NOT clear unrelated registry entries")
    }
}
