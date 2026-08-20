package matchmaking

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import net.accelbyte.sdk.api.match2.models.ApiMatchTicketRequest
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
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import org.ttt.autogenesis.server.vfs.VirtualFileSystem
import structs.account.AccountPlan
import structs.account.AccountSettings
import structs.account.BillingStatus
import structs.matchmaking.GameRequest
import structs.matchmaking.GameType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that [ServerConnector.executeLiveMatchmaking] stamps the match2
 * ticket with the cost attributes derived from the requester's
 * [AccountSettings]. Mocks the SDK wrappers through the existing testable
 * seams on [ServerConnector] and uses a fake [AccountSettingsLookup] to
 * return a known cost profile.
 */
class TicketEnrichmentTest
{
    private val originalDebugMode = globals.ExtendConfig.debugMode
    private val originalTimeoutMs = ServerConnector.MATCHMAKING_TIMEOUT_MS
    private val originalPollIntervalMs = ServerConnector.MATCHMAKING_POLL_INTERVAL_MS
    private val originalLookup = ServerConnector.accountSettingsLookup

    @Before
    fun setUp()
    {
        globals.ExtendConfig.debugMode = false
        if(System.getenv("AB_NAMESPACE").isNullOrBlank())
        {
            System.setProperty("AB_NAMESPACE", "test-namespace")
        }
        ServerConnector.MATCHMAKING_TIMEOUT_MS = 4_000L
        ServerConnector.MATCHMAKING_POLL_INTERVAL_MS = 50L
    }

    @After
    fun tearDown()
    {
        globals.ExtendConfig.debugMode = originalDebugMode
        ServerConnector.MATCHMAKING_TIMEOUT_MS = originalTimeoutMs
        ServerConnector.MATCHMAKING_POLL_INTERVAL_MS = originalPollIntervalMs
        ServerConnector.accountSettingsLookup = originalLookup
        unmockkAll()
    }

    @Test
    fun ticketAttributesCarryCostClassAndSubsidy() = runBlocking {
        val matchTickets = mockk<MatchTickets>(relaxed = false)
        val gameSession = mockk<GameSession>(relaxed = false)

        val ticketId = "ticket-enr-1"
        val matchId = "match-enr-1"
        val createdTicket = ApiMatchTicketResponse().apply { matchTicketID = ticketId }
        val matched = ApiMatchTicketStatus().apply {
            matchFound = true
            this.matchTicketID = ticketId
            this.sessionID = matchId
            isActive = true
        }
        every { matchTickets.createMatchTicket(any()) } returns createdTicket
        every { matchTickets.matchTicketDetails(any()) } returns matched
        every { matchTickets.deleteMatchTicket(any()) } returns mockk(relaxed = true)
        every { gameSession.getGameSession(any()) } returns apimodelsGameSession(matchId)

        ServerConnector.matchTicketsWrapper = matchTickets
        ServerConnector.gameSessionWrapper = gameSession

        // Replace the cost lookup with one that always returns a PRO user.
        ServerConnector.accountSettingsLookup = object : AccountSettingsLookup()
        {
            override suspend fun fetch(userId: String): AccountSettings = AccountSettings(
                accelByteUserId = userId,
                billingStatus = BillingStatus(plan = AccountPlan.PRO, autoRenew = true, credits = 1500.0)
            )
        }

        // Spy on notifyGameServer so we don't open a real WebSocket.
        mockkObject(ServerConnector)
        coEvery { ServerConnector.notifyGameServer(any()) } returns true

        val request = GameRequest(
            userName = "enr-user",
            gameType = GameType.MULTIPLAYER,
            accelByteId = "u-enr-1"
        )
        val acknowledged = ServerConnector.requestGame(
            RpcCallContext(connectionId = "enr-player") { error("not expected") },
            request
        )
        assertTrue(acknowledged)

        // Capture the body of createMatchTicket and assert the cost attribute set is present.
        val capturedOp = slot<net.accelbyte.sdk.api.match2.operations.match_tickets.CreateMatchTicket>()
        coVerify(atLeast = 1) { matchTickets.createMatchTicket(capture(capturedOp)) }
        val attrs = capturedOp.captured.body.attributes as Map<String, JsonPrimitive>
        assertEquals("PRO", attrs["cost_class"]?.content)
        assertEquals("2", attrs["cost_subsidy"]?.content, "PRO yields a 2-slot subsidy")
        assertEquals("1500.0", attrs["wallet_credits"]?.content)
        assertEquals("false", attrs["byo_api_key"]?.content)
    }

    @Test
    fun byoKeyTicketAttributesOverrideThePlan() = runBlocking {
        val matchTickets = mockk<MatchTickets>(relaxed = false)
        val gameSession = mockk<GameSession>(relaxed = false)

        val ticketId = "ticket-enr-2"
        val matchId = "match-enr-2"
        val createdTicket = ApiMatchTicketResponse().apply { matchTicketID = ticketId }
        val matched = ApiMatchTicketStatus().apply {
            matchFound = true
            this.matchTicketID = ticketId
            this.sessionID = matchId
            isActive = true
        }
        every { matchTickets.createMatchTicket(any()) } returns createdTicket
        every { matchTickets.matchTicketDetails(any()) } returns matched
        every { matchTickets.deleteMatchTicket(any()) } returns mockk(relaxed = true)
        every { gameSession.getGameSession(any()) } returns apimodelsGameSession(matchId)

        ServerConnector.matchTicketsWrapper = matchTickets
        ServerConnector.gameSessionWrapper = gameSession

        ServerConnector.accountSettingsLookup = object : AccountSettingsLookup()
        {
            override suspend fun fetch(userId: String): AccountSettings = AccountSettings(
                accelByteUserId = userId,
                bringYourOwnApiKey = true,
                billingStatus = BillingStatus(plan = AccountPlan.FREE, autoRenew = false, credits = 0.0)
            )
            // The test simulates a player who has actually submitted a BYO key, so the
            // slot must report as existing. Without this override the new self-healing
            // logic in [AccountSettingsLookup] would downgrade the cost class to PRO.
            override suspend fun byoSlotExists(userId: String): Boolean = true
        }

        mockkObject(ServerConnector)
        coEvery { ServerConnector.notifyGameServer(any()) } returns true

        val request = GameRequest(
            userName = "byo-user",
            gameType = GameType.MULTIPLAYER,
            accelByteId = "u-enr-2"
        )
        val acknowledged = ServerConnector.requestGame(
            RpcCallContext(connectionId = "byo-player") { error("not expected") },
            request
        )
        assertTrue(acknowledged)

        val capturedOp = slot<net.accelbyte.sdk.api.match2.operations.match_tickets.CreateMatchTicket>()
        coVerify(atLeast = 1) { matchTickets.createMatchTicket(capture(capturedOp)) }
        val attrs = capturedOp.captured.body.attributes as Map<String, JsonPrimitive>
        assertEquals("BYO_KEY", attrs["cost_class"]?.content, "BYO flag must take precedence over FREE plan")
        assertEquals("4", attrs["cost_subsidy"]?.content)
        assertEquals("true", attrs["byo_api_key"]?.content)
    }

    @Test
    fun blankAccelByteIdFallsBackToFreeDefaults() = runBlocking {
        val matchTickets = mockk<MatchTickets>(relaxed = false)
        val gameSession = mockk<GameSession>(relaxed = false)
        val ticketId = "ticket-enr-3"
        val matchId = "match-enr-3"
        every { matchTickets.createMatchTicket(any()) } returns ApiMatchTicketResponse().apply { matchTicketID = ticketId }
        every { matchTickets.matchTicketDetails(any()) } returns ApiMatchTicketStatus().apply {
            matchFound = true
            this.matchTicketID = ticketId
            this.sessionID = matchId
            isActive = true
        }
        every { matchTickets.deleteMatchTicket(any()) } returns mockk(relaxed = true)
        every { gameSession.getGameSession(any()) } returns apimodelsGameSession(matchId)
        ServerConnector.matchTicketsWrapper = matchTickets
        ServerConnector.gameSessionWrapper = gameSession
        mockkObject(ServerConnector)
        coEvery { ServerConnector.notifyGameServer(any()) } returns true

        val request = GameRequest(
            userName = "anon",
            gameType = GameType.MULTIPLAYER,
            accelByteId = "" // <-- deliberately blank
        )
        val acknowledged = ServerConnector.requestGame(
            RpcCallContext(connectionId = "anon") { error("not expected") },
            request
        )
        assertTrue(acknowledged)

        val capturedOp = slot<net.accelbyte.sdk.api.match2.operations.match_tickets.CreateMatchTicket>()
        coVerify(atLeast = 1) { matchTickets.createMatchTicket(capture(capturedOp)) }
        // When accelByteId is blank, no attribute lookup happens and the
        // attributes map is empty. The matchmaker treats missing keys as FREE.
        val attrs = capturedOp.captured.body.attributes
        assertNotNull(attrs)
        assertTrue(attrs.isEmpty(),
            "blank accelByteId must skip cost enrichment (got: $attrs)")
    }

    private fun apimodelsGameSession(matchId: String): ApimodelsGameSessionResponse
    {
        val member = ApimodelsUserResponse().apply {
            id = "u-enr-1"
            platformUserID = "steam-enr-1"
        }
        return ApimodelsGameSessionResponse().apply {
            this.id = matchId
            this.dsInformation = ApimodelsDSInformationResponse().apply {
                server = ModelsGameServer().apply { ip = "10.0.0.5"; port = 7777 }
            }
            this.members = listOf(member)
            this.configuration = ApimodelsPublicConfiguration().apply { maxPlayers = 4 }
        }
    }
}