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
import net.accelbyte.sdk.api.session.models.ModelsGameServer
import net.accelbyte.sdk.api.session.wrappers.GameSession
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import structs.matchmaking.GameRequest
import structs.matchmaking.GameSessionStatus
import structs.matchmaking.GameTicket
import structs.matchmaking.GameType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD tests for [ServerConnector.requestResume] (Phase C of the
 * resume-game-architecture plan).
 *
 * The plan's review flagged that `requestResume` previously returned
 * `Boolean`, which left the client with no `serverUrl` for the resumed
 * DS — the client never `connectToGameServer`'d, so the live-mode
 * resume flow was broken end-to-end. These tests pin the desired
 * behaviour: the resume RPC returns a [GameTicket] with a real
 * `serverUrl` and `sessionId`, and the [GameSessionStatus] forwarded to
 * the DS carries `resumeFromVfs = true` and the calling `accelByteId`
 * as `resumeUserId` (so the DS's `setGameMode` handler in
 * `server/gameInit/GameInit.kt` can rehydrate from VFS).
 */
class ServerConnectorRequestResumeTest
{
    private val originalDebugMode = globals.ExtendConfig.debugMode
    private val originalTimeoutMs = ServerConnector.MATCHMAKING_TIMEOUT_MS
    private val originalPollIntervalMs = ServerConnector.MATCHMAKING_POLL_INTERVAL_MS
    private val originalNamespace: String? = System.getenv("AB_NAMESPACE")
    private val originalRegistry = ServerConnector.urlHandoverRegistry

    @Before
    fun setup()
    {
        // Make sure AB_NAMESPACE is set so AccelByteConfig.getNamespace() doesn't throw.
        if (System.getenv("AB_NAMESPACE").isNullOrBlank())
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
        if (originalNamespace == null)
        {
            System.clearProperty("AB_NAMESPACE")
        }
        ServerConnector.urlHandoverRegistry = originalRegistry
        unmockkAll()
    }

    /**
     * Confirms that a successful live resume round-trip returns a
     * [GameTicket] with the resolved `serverUrl` and `sessionId`, and
     * that the forwarded [GameSessionStatus] carries `resumeFromVfs = true`
     * and the calling `accelByteId` as `resumeUserId`.
     */
    @Test
    fun `requestResume returns a GameTicket with resumeFromVfs set on the forwarded GameSessionStatus`() = runBlocking {
        globals.ExtendConfig.debugMode = false

        val matchTickets = mockk<MatchTickets>(relaxed = false)
        val gameSession = mockk<GameSession>(relaxed = false)

        val ticketId = "ticket-resume-abc-123"
        val matchId = "match-resume-123"
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

        val member = ApimodelsUserResponse().apply {
            id = "resume-user-1"
            platformUserID = "resume-steam-1"
        }

        val gameServer = ModelsGameServer().apply {
            ip = "10.0.0.42"
            port = 7777
        }
        val dsInfo = ApimodelsDSInformationResponse().apply {
            server = gameServer
        }
        val session = ApimodelsGameSessionResponse().apply {
            this.id = sessionId
            this.dsInformation = dsInfo
            this.members = listOf(member)
            this.configuration = ApimodelsPublicConfiguration().apply { maxPlayers = 1 }
        }
        every { gameSession.getGameSession(any()) } returns session

        // Inject mocks through the testable seam on ServerConnector.
        ServerConnector.matchTicketsWrapper = matchTickets
        ServerConnector.gameSessionWrapper = gameSession

        // Spy on notifyGameServer so we can capture the forwarded GameSessionStatus
        // without opening a real WebSocket.
        val capturedStatus = slot<GameSessionStatus>()
        mockkObject(ServerConnector)
        coEvery { ServerConnector.notifyGameServer(capture(capturedStatus)) } returns true

        val request = GameRequest(
            userName = "resume-user",
            gameType = GameType.SINGLEPLAYER,
            accelByteId = "resume-accelbyte-id"
        )

        val ticket: GameTicket = ServerConnector.requestResume(
            RpcCallContext(connectionId = "unit-test-resume-player") { error("not expected") },
            request
        )

        // The returned ticket must carry a usable serverUrl + sessionId so
        // the client can reconnect the WS to the resumed DS.
        assertTrue(ticket.serverUrl.isNotBlank(), "requestResume must return a GameTicket with a non-blank serverUrl")
        assertEquals(matchId, ticket.sessionId, "requestResume must return the matched sessionId")
        assertEquals("10.0.0.42:7777", ticket.serverUrl, "requestResume must return the resolved DS URL")

        // notifyGameServer was called with a GameSessionStatus that carries
        // resumeFromVfs = true and resumeUserId = <calling accelByteId>.
        coVerify(exactly = 1) { ServerConnector.notifyGameServer(any()) }
        val forwarded = capturedStatus.captured
        assertTrue(forwarded.resumeFromVfs, "forwarded GameSessionStatus must have resumeFromVfs=true")
        assertEquals("resume-accelbyte-id", forwarded.resumeUserId, "forwarded GameSessionStatus must have resumeUserId set to the calling accelByteId")
    }

    /**
     * Confirms that when the resume ticket never reports a match within
     * the polling window, [ServerConnector.requestResume] returns an
     * empty [GameTicket] (blank sessionId/serverUrl) so the client can
     * show a "resume failed" message. `notifyGameServer` is never
     * invoked because there is no DS to notify.
     */
    @Test
    fun `requestResume returns an empty GameTicket when match never resolves`() = runBlocking {
        globals.ExtendConfig.debugMode = false

        val matchTickets = mockk<MatchTickets>(relaxed = false)
        val gameSession = mockk<GameSession>(relaxed = false)

        val ticketId = "ticket-resume-timeout-456"
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
            userName = "resume-user",
            gameType = GameType.SINGLEPLAYER,
            accelByteId = "resume-accelbyte-id"
        )

        val ticket: GameTicket = ServerConnector.requestResume(
            RpcCallContext(connectionId = "unit-test-resume-player") { error("not expected") },
            request
        )

        assertTrue(ticket.sessionId.isBlank(), "failed resume must return blank sessionId")
        assertTrue(ticket.serverUrl.isBlank(), "failed resume must return blank serverUrl")
        // The DS was never contacted because no match was found.
        coVerify(exactly = 0) { ServerConnector.notifyGameServer(any()) }
    }

    /**
     * Confirms that in dev mode, [ServerConnector.requestResume] returns
     * a [GameTicket] with `serverUrl = "127.0.0.1:9080"` (the local main
     * server). The client uses this URL to reconnect the WS to the local
     * DS, which rehydrates the saved snapshot via
     * `server.setGameMode(resumeFromVfs = true)`.
     */
    @Test
    fun `requestResume in dev mode returns a GameTicket with the local serverUrl and resumeFromVfs set`() = runBlocking {
        globals.ExtendConfig.debugMode = true

        // Spy on notifyGameServer — dev mode calls it directly.
        val capturedStatus = slot<GameSessionStatus>()
        mockkObject(ServerConnector)
        coEvery { ServerConnector.notifyGameServer(capture(capturedStatus)) } returns true

        val request = GameRequest(
            userName = "dev-resume-user",
            gameType = GameType.SINGLEPLAYER,
            accelByteId = "dev-resume-accelbyte-id"
        )

        val ticket: GameTicket = ServerConnector.requestResume(
            RpcCallContext(connectionId = "unit-test-resume-player") { error("not expected") },
            request
        )

        // Dev mode GameTicket carries the local DS URL.
        assertEquals("127.0.0.1:9080", ticket.serverUrl, "dev-mode requestResume must return the local DS URL")
        assertTrue(ticket.sessionId.isNotBlank(), "dev-mode requestResume must return a non-blank sessionId")

        // The forwarded GameSessionStatus must carry the resume fields.
        coVerify(exactly = 1) { ServerConnector.notifyGameServer(any()) }
        val forwarded = capturedStatus.captured
        assertTrue(forwarded.resumeFromVfs, "dev-mode forwarded GameSessionStatus must have resumeFromVfs=true")
        assertEquals(
            "dev-resume-accelbyte-id",
            forwarded.resumeUserId,
            "dev-mode forwarded GameSessionStatus must have resumeUserId set to the calling accelByteId"
        )
        assertEquals(GameType.SINGLEPLAYER, forwarded.gameType, "dev-mode forwarded GameSessionStatus must have gameType=SINGLEPLAYER")
    }

    /**
     * Confirms that an empty `accelByteId` is rejected early with an
     * empty [GameTicket] so the client can show a "resume failed"
     * message without going through the full match2 round-trip.
     */
    @Test
    fun `requestResume returns an empty GameTicket when accelByteId is blank`() = runBlocking {
        globals.ExtendConfig.debugMode = false

        val request = GameRequest(
            userName = "blank-user",
            gameType = GameType.SINGLEPLAYER,
            accelByteId = ""
        )

        val ticket: GameTicket = ServerConnector.requestResume(
            RpcCallContext(connectionId = "unit-test-resume-player") { error("not expected") },
            request
        )

        assertFalse(ticket.serverUrl.isNotBlank(), "blank accelByteId must return an empty GameTicket")
        assertTrue(ticket.sessionId.isBlank(), "blank accelByteId must return an empty GameTicket")
    }

    /**
     * Confirms the no-leak invariant on the dev-mode path when
     * [ServerConnector.notifyGameServer] returns false: the dev
     * session is NOT written to [ServerConnector.gameSessions], and
     * therefore [ServerConnector.resolveUrl] is never called for the
     * sessionId, and therefore the [UrlHandoverRegistry] is never
     * written either.
     *
     * This is the staleness path for "DS handshake failed": the URL
     * was about to be handed back but the DS never acked, so no
     * client can ever have received it. The test pins the invariant
     * that no entry leaks into the registry in this state.
     */
    @Test
    fun `dev mode requestResume with failing notifyGameServer leaves registry empty for the new sessionId`() = runBlocking {
        globals.ExtendConfig.debugMode = true

        val registry = UrlHandoverRegistry()
        ServerConnector.urlHandoverRegistry = registry
        mockkObject(ServerConnector)
        coEvery { ServerConnector.notifyGameServer(any()) } returns false // DS did not ack

        val request = GameRequest(
            userName = "failing-dev-resume",
            gameType = GameType.SINGLEPLAYER,
            accelByteId = "failing-dev-resume-accelbyte"
        )

        val ticket: GameTicket = ServerConnector.requestResume(
            RpcCallContext(connectionId = "unit-test-failing-dev-resume") { error("not expected") },
            request
        )

        // notifyGameServer returned false, so the dev session was never
        // written to gameSessions and the returned ticket is empty.
        assertTrue(ticket.serverUrl.isBlank(), "failing dev resume must return blank serverUrl")
        assertTrue(ticket.sessionId.isBlank(), "failing dev resume must return blank sessionId")

        // Invariant: the registry must NOT contain the new dev
        // sessionId (it was never written because notifyGameServer
        // failed and the gameSession was never registered). A
        // future refactor that pre-writes the registry before the
        // notifyGameServer ack would break this — a stale entry
        // would live in the registry even though no client ever
        // received the URL. The sessionIdsForPlayer listener would
        // not evict it (the player has no gameSession yet), and the
        // AGS poller would not evict it (the session never existed
        // on the platform). So the no-pre-write invariant MUST hold.
        assertNull(registry.get(ticket.sessionId),
            "failing dev resume must not write a URL to the registry")
    }
}
