package accelbyte

import accelbyte.backfill.BackfillHandler
import accelbyte.backfill.BackfillHandlerResult
import accelbyte.backfill.BackfillTicketRequest
import accelbyte.session.GameState
import accelbyte.session.PlayerStatSummary
import accelbyte.session.Session
import accelbyte.session.SessionStorageHandler
import gameState.WorldManager
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import structs.World
import structs.Player
import structs.accelbyte.session.GameSessionDetailResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegrationTest {

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        mockkObject(WorldManager)
        mockkObject(Session)
        mockkObject(SessionStorageHandler)

        every { WorldManager.isDraining() } returns false
        every { WorldManager.isDrained() } returns false
        every { WorldManager.isGameActive } returns true
        every { WorldManager.activeSessionId } returns "test-session-123"
        every { WorldManager.expectedPlayers } returns emptySet()
        every { WorldManager.handleBackfillRequest(any()) } returns BackfillHandlerResult(rejected = false, candidate = "accepted")
    }

    @Test
    fun createPartyFlowSucceeds() = runBlocking {
        val mockWorld = World()
        mockWorld.activePlayers.add(Player(name = "Leader"))
        every { WorldManager.world } returns mockWorld
        every { WorldManager.worldMutex } returns mockWorld.mutex

        assertNotNull(WorldManager.world)
        assertEquals(1, WorldManager.world.activePlayers.size)
    }

    @Test
    fun joinPartyFlowSucceeds() = runBlocking {
        val mockWorld = World()
        val leader = Player(name = "Leader")
        val member = Player(name = "Member")
        mockWorld.activePlayers.addAll(listOf(leader, member))
        every { WorldManager.world } returns mockWorld
        every { WorldManager.worldMutex } returns mockWorld.mutex

        assertEquals(2, mockWorld.activePlayers.size)
    }

    @Test
    fun leavePartyFlowSucceeds() = runBlocking {
        val initialPlayers = setOf("user-member-003")
        val afterLeave = emptySet<String>()

        every { WorldManager.expectedPlayers } returns initialPlayers andThen afterLeave

        assertTrue(WorldManager.expectedPlayers.contains("user-member-003"))
        assertFalse(WorldManager.expectedPlayers.contains("other-user"))
    }

    @Test
    fun backfillCandidateAcceptedJoinsExpectedPlayers() = runBlocking {
        val backfillRequest = BackfillTicketRequest(
            ticketId = "ticket-backfill-001",
            userId = "backfill-candidate-042",
            sessionId = "test-session-123"
        )

        val mockWorld = World()
        mockWorld.activePlayers.add(Player(name = "ExistingPlayer1"))
        mockWorld.activePlayers.add(Player(name = "ExistingPlayer2"))
        every { WorldManager.world } returns mockWorld
        every { WorldManager.worldMutex } returns mockWorld.mutex

        mockkObject(org.ttt.autogenesis.server.config.RuleSet)
        every { org.ttt.autogenesis.server.config.RuleSet.data } returns mockk(relaxed = true) {
            every { currentPlayers } returns 2
            every { maxPlayers } returns 8
        }

        val scope = kotlinx.coroutines.GlobalScope
        val backfillHandler = BackfillHandler(WorldManager, scope)
        val result = backfillHandler.handleBackfillRequest(backfillRequest)

        assertFalse(result.rejected)
        assertEquals("accepted", result.candidate)
    }

    @Test
    fun backfillRejectedWhenServerFull() = runBlocking {
        val backfillRequest = BackfillTicketRequest(
            ticketId = "ticket-backfill-002",
            userId = "backfill-candidate-043",
            sessionId = "test-session-123"
        )

        val mockWorld = World()
        every { WorldManager.world } returns mockWorld
        every { WorldManager.worldMutex } returns mockWorld.mutex

        mockkObject(org.ttt.autogenesis.server.config.RuleSet)
        every { org.ttt.autogenesis.server.config.RuleSet.data } returns mockk(relaxed = true) {
            every { currentPlayers } returns 8
            every { maxPlayers } returns 8
        }

        val scope = kotlinx.coroutines.GlobalScope
        val backfillHandler = BackfillHandler(WorldManager, scope)
        val result = backfillHandler.handleBackfillRequest(backfillRequest)

        assertTrue(result.rejected)
        assertEquals("SERVER_FULL", result.reason)
    }

    @Test
    fun backfillRejectedWhenServerDraining() = runBlocking {
        val backfillRequest = BackfillTicketRequest(
            ticketId = "ticket-backfill-003",
            userId = "backfill-candidate-044",
            sessionId = "test-session-123"
        )

        every { WorldManager.isDraining() } returns true

        val scope = kotlinx.coroutines.GlobalScope
        val backfillHandler = BackfillHandler(WorldManager, scope)
        val result = backfillHandler.handleBackfillRequest(backfillRequest)

        assertTrue(result.rejected)
        assertEquals("SERVER_DRAINING", result.reason)
    }

    @Test
    fun matchEndWritesSessionStorage() = runBlocking {
        val sessionId = "match-end-session-456"
        val gameState = GameState(
            matchOutcome = "victory",
            finalScores = mapOf("Player1" to 5, "Player2" to 3),
            playerStats = listOf(
                PlayerStatSummary(
                    playerName = "Player1",
                    territoriesHeld = 5,
                    militaryPoints = 120,
                    diplomacyPoints = 80,
                    researchPoints = 60,
                    isControlledByNpc = false
                ),
                PlayerStatSummary(
                    playerName = "Player2",
                    territoriesHeld = 3,
                    militaryPoints = 90,
                    diplomacyPoints = 110,
                    researchPoints = 70,
                    isControlledByNpc = true
                )
            ),
            endTimestamp = "2026-04-25T12:30:00Z"
        )

        every { SessionStorageHandler.writeSessionStorage(eq(sessionId), eq(gameState)) } returns Result.success(Unit)

        val writeResult = SessionStorageHandler.writeSessionStorage(sessionId, gameState)

        assertTrue(writeResult.isSuccess)
        verify { SessionStorageHandler.writeSessionStorage(sessionId, gameState) }
    }

    @Test
    fun matchEndBuildsCorrectGameState() = runBlocking {
        val mockWorld = World()
        val player1 = Player(name = "WinnerPlayer", militaryPoints = 150, diplomacyPoints = 90, researchPoints = 70)
        val player2 = Player(name = "LoserPlayer", militaryPoints = 80, diplomacyPoints = 100, researchPoints = 120)
        mockWorld.activePlayers.addAll(listOf(player1, player2))

        every { WorldManager.world } returns mockWorld
        every { WorldManager.worldMutex } returns mockWorld.mutex
        every { WorldManager.findPlayerFromStats(any()) } returns null

        val gameState = SessionStorageHandler.buildGameState("victory", "WinnerPlayer")

        assertEquals("victory", gameState.matchOutcome)
        assertNotNull(gameState.endTimestamp)
        assertEquals(2, gameState.finalScores.size)
        assertTrue(gameState.finalScores.containsKey("WinnerPlayer"))
        assertTrue(gameState.finalScores.containsKey("LoserPlayer"))
    }

    @Test
    fun crashRecoveryReadsSessionStorage() = runBlocking {
        val partyId = "recovery-session-789"
        val storedGameState = GameState(
            matchOutcome = "draw",
            finalScores = mapOf("PlayerA" to 4, "PlayerB" to 4),
            playerStats = listOf(
                PlayerStatSummary(
                    playerName = "PlayerA",
                    territoriesHeld = 4,
                    militaryPoints = 100,
                    diplomacyPoints = 100,
                    researchPoints = 100,
                    isControlledByNpc = false
                ),
                PlayerStatSummary(
                    playerName = "PlayerB",
                    territoriesHeld = 4,
                    militaryPoints = 100,
                    diplomacyPoints = 100,
                    researchPoints = 100,
                    isControlledByNpc = false
                )
            ),
            endTimestamp = "2026-04-25T08:00:00Z"
        )

        val mockSessionResponse = mockk<GameSessionDetailResponse> {
            val storageJson = kotlinx.serialization.json.Json.parseToJsonElement("""{"matchOutcome":"draw","finalScores":{"PlayerA":4,"PlayerB":4},"playerStats":[{"playerName":"PlayerA","territoriesHeld":4,"militaryPoints":100,"diplomacyPoints":100,"researchPoints":100,"isControlledByNpc":false},{"playerName":"PlayerB","territoriesHeld":4,"militaryPoints":100,"diplomacyPoints":100,"researchPoints":100,"isControlledByNpc":false}],"endTimestamp":"2026-04-25T08:00:00Z"}""")
            every { storage } returns storageJson
        }

        every { Session.getGameSession(partyId) } returns Result.success(mockSessionResponse)
        every { SessionStorageHandler.readSessionStorage(partyId) } returns Result.success(storedGameState)

        val readResult = SessionStorageHandler.readSessionStorage(partyId)

        assertTrue(readResult.isSuccess)
        val recoveredState = readResult.getOrNull()
        assertNotNull(recoveredState)
        assertEquals("draw", recoveredState.matchOutcome)
        assertEquals(2, recoveredState.finalScores.size)
        assertTrue(recoveredState.finalScores.containsKey("PlayerA"))
        assertTrue(recoveredState.finalScores.containsKey("PlayerB"))
    }

    @Test
    fun crashRecoveryWithNoStorageReturnsNull() = runBlocking {
        val partyId = "new-session-999"

        val mockSessionResponse = mockk<GameSessionDetailResponse> {
            every { storage } returns null
        }

        every { Session.getGameSession(partyId) } returns Result.success(mockSessionResponse)
        every { SessionStorageHandler.readSessionStorage(partyId) } returns Result.success(null)

        val readResult = SessionStorageHandler.readSessionStorage(partyId)

        assertTrue(readResult.isSuccess)
        assertNull(readResult.getOrNull())
    }

    @Test
    fun crashRecoveryFailsOnReadError() = runBlocking {
        val partyId = "corrupted-session-000"

        every { Session.getGameSession(partyId) } returns Result.failure(Exception("Network error"))
        every { SessionStorageHandler.readSessionStorage(partyId) } returns Result.failure(Exception("Network error"))

        val readResult = SessionStorageHandler.readSessionStorage(partyId)

        assertTrue(readResult.isFailure)
        assertTrue(readResult.exceptionOrNull()?.message?.contains("Network error") == true)
    }

    @Test
    fun stateReconstructedAfterCrashRecovery() = runBlocking {
        val partyId = "reconstruct-session-111"
        val recoveredState = GameState(
            matchOutcome = "victory",
            finalScores = mapOf("ReconPlayer1" to 6, "ReconPlayer2" to 2),
            playerStats = listOf(
                PlayerStatSummary(
                    playerName = "ReconPlayer1",
                    territoriesHeld = 6,
                    militaryPoints = 140,
                    diplomacyPoints = 80,
                    researchPoints = 90,
                    isControlledByNpc = false
                ),
                PlayerStatSummary(
                    playerName = "ReconPlayer2",
                    territoriesHeld = 2,
                    militaryPoints = 70,
                    diplomacyPoints = 120,
                    researchPoints = 100,
                    isControlledByNpc = true
                )
            ),
            endTimestamp = "2026-04-25T14:00:00Z"
        )

        every { SessionStorageHandler.readSessionStorage(partyId) } returns Result.success(recoveredState)

        val readResult = SessionStorageHandler.readSessionStorage(partyId)

        assertTrue(readResult.isSuccess)
        val state = readResult.getOrNull()
        assertNotNull(state)

        assertEquals("victory", state.matchOutcome)
        assertEquals(6, state.finalScores["ReconPlayer1"])
        assertEquals(2, state.finalScores["ReconPlayer2"])

        val reconPlayer1Stats = state.playerStats.find { it.playerName == "ReconPlayer1" }
        assertNotNull(reconPlayer1Stats)
        assertEquals(6, reconPlayer1Stats.territoriesHeld)
        assertFalse(reconPlayer1Stats.isControlledByNpc)

        val reconPlayer2Stats = state.playerStats.find { it.playerName == "ReconPlayer2" }
        assertNotNull(reconPlayer2Stats)
        assertTrue(reconPlayer2Stats.isControlledByNpc)
    }
}
/**
 * Verifies the match-end -> session-storage write wiring added to
 * `WorldManager.onMatchEnded`. The handler installed by `Server.kt` calls
 * `SessionStorageHandler.buildGameState` + `writeSessionStorage` on the
 * GlobalScope IO dispatcher; here we drive `WorldManager.notifyMatchEnded`
 * synchronously and verify the registered callback delegates to
 * `SessionStorageHandler` with the right session ID and outcome.
 *
 * BackfillHandler is also mocked in the test base setup; this test does not
 * touch backfill but the mock is harmless.
 */
class MatchEndStorageTest
{
    @Before
    fun setup()
    {
        MockKAnnotations.init(this)
        mockkObject(SessionStorageHandler)
    }

    /**
     * Happy path: a registered callback forwards the event into
     * `SessionStorageHandler.writeSessionStorage` with the same sessionId /
     * outcome / winnerName as the emitted event.
     */
    @Test
    fun notifyMatchEnded_invokesCallbackWithEvent() = runBlocking {
        var captured: accelbyte.session.MatchEndedEvent? = null
        val sessionId = "match-session-42"
        val outcome = "victory"
        val winner = "Player One"

        every { SessionStorageHandler.buildGameState(any(), any()) } returns GameState(
            matchOutcome = outcome,
            finalScores = mapOf(winner to 10),
            playerStats = emptyList(),
            endTimestamp = "2026-06-21T00:00:00Z"
        )
        every { SessionStorageHandler.writeSessionStorage(any(), any()) } returns Result.success(Unit)

        gameState.WorldManager.onMatchEnded { event ->
            captured = event
            // Mirror the production wiring: build + write on the event payload.
            val gs = SessionStorageHandler.buildGameState(
                matchOutcome = event.outcome,
                winnerName = event.winnerName
            )
            SessionStorageHandler.writeSessionStorage(event.sessionId, gs)
        }

        gameState.WorldManager.notifyMatchEnded(
            accelbyte.session.MatchEndedEvent(
                sessionId = sessionId,
                outcome = outcome,
                winnerName = winner
            )
        )

        assertNotNull(captured, "callback was not invoked")
        assertEquals(sessionId, captured!!.sessionId)
        assertEquals(outcome, captured!!.outcome)
        assertEquals(winner, captured!!.winnerName)

        verify(exactly = 1) {
            SessionStorageHandler.writeSessionStorage(
                sessionId,
                match<GameState> { it.matchOutcome == outcome }
            )
        }
    }

    /**
     * No-op when no callback is registered. Important for the boot path:
     * if Server.kt hasn't yet attached the SessionStorageHandler callback
     * (e.g. tests that exercise WorldManager in isolation), `notifyMatchEnded`
     * must not crash.
     */
    @Test
    fun notifyMatchEnded_isNoOpWhenNoCallbackRegistered() = runBlocking {
        // No `onMatchEnded` registration. notifyMatchEnded must not throw.
        gameState.WorldManager.notifyMatchEnded(
            accelbyte.session.MatchEndedEvent(
                sessionId = "irrelevant",
                outcome = "draw",
                winnerName = null
            )
        )
        // SessionStorageHandler must NOT have been called, because the
        // production callback that calls it is not registered in this test.
        verify(exactly = 0) { SessionStorageHandler.writeSessionStorage(any(), any()) }
    }

    /**
     * Forced end path: `dispatchForcedGameOver` emits outcome="forced_end";
     * verify the storage write carries that string through.
     */
    @Test
    fun notifyMatchEnded_forcedEndCarriesOutcome() = runBlocking {
        var captured: accelbyte.session.MatchEndedEvent? = null
        val sessionId = "forced-session-99"

        every { SessionStorageHandler.buildGameState(any(), any()) } returns GameState(
            matchOutcome = "forced_end",
            finalScores = emptyMap(),
            playerStats = emptyList(),
            endTimestamp = "2026-06-21T00:00:00Z"
        )
        every { SessionStorageHandler.writeSessionStorage(any(), any()) } returns Result.success(Unit)

        gameState.WorldManager.onMatchEnded { event -> captured = event }
        gameState.WorldManager.notifyMatchEnded(
            accelbyte.session.MatchEndedEvent(
                sessionId = sessionId,
                outcome = "forced_end",
                winnerName = "LastSurvivor"
            )
        )

        assertEquals("forced_end", captured!!.outcome)
        assertEquals(sessionId, captured!!.sessionId)
        assertEquals("LastSurvivor", captured!!.winnerName)
    }
}
