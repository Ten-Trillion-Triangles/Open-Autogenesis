package gameInit

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.server.GameRestoreRpcHandlers
import org.ttt.autogenesis.server.TurnHarness
import structs.matchmaking.GameSessionStatus
import structs.matchmaking.GameType
import structs.matchmaking.PlayerSessionBundle
import kotlin.test.assertTrue

/**
 * Pins the Phase D resume-game contract for [GameInit.defineGameRules].
 *
 * When server.setGameMode arrives with `resumeFromVfs=true` and a non-blank
 * `resumeUserId`, the handler MUST:
 * 1. Invoke [GameRestoreRpcHandlers.restoreRunningGameForUser] with that id.
 * 2. Invoke it BEFORE [TurnHarness.resetState] (the fresh-state reset would
 *    wipe the rehydrated world otherwise).
 * 3. Fall through to the fresh-session path if the resume fails or the
 *    preconditions aren't met.
 *
 * The previous resume-game plan (OPERATIONS.md "Known Gaps" Task 17)
 * cancelled this test because the fixture cost was deemed too high. The
 * fixture is now minimal: we mockkObject the two singletons we care about
 * (GameRestoreRpcHandlers and TurnHarness), let the rest of the function
 * run (it may throw on map loading, which we swallow in test #1 to focus
 * on the resume-branch side effects).
 *
 * Reference production code: server/src/main/kotlin/gameInit/GameInit.kt:35-58
 */
class GameInitDefineGameRulesResumeTest
{
    private val accelByteId = "00000000000000000000000000000000"

    @Before
    fun setUp()
    {
        mockkObject(GameRestoreRpcHandlers)
        mockkObject(TurnHarness)
    }

    @After
    fun tearDown()
    {
        unmockkObject(GameRestoreRpcHandlers)
        unmockkObject(TurnHarness)
    }


    /**
     * Case 1 (positive — the bug-fix contract):
     * resumeFromVfs=true AND resumeUserId non-blank AND restore succeeds.
     * restoreRunningGameForUser MUST be called with the right id, AND it
     * MUST be called BEFORE TurnHarness.resetState.
     */
    @Test
    fun `defineGameRules with resumeFromVfs=true rehydrates before resetState`()
    {
        coEvery { GameRestoreRpcHandlers.restoreRunningGameForUser(accelByteId) } returns true
        coEvery { TurnHarness.resetState() } returns Unit
        coEvery { TurnHarness.onSessionBound(any(), any()) } returns Unit

        val sessionData = buildSessionData(
            resumeFromVfs = true,
            resumeUserId = accelByteId,
            sessionId = "live-session-1"
        )

        runCatching {
            runBlocking {
                GameInit.defineGameRules(
                    RpcCallContext(connectionId = "test-client") { error("no-op") },
                    sessionData
                )
            }
        }

        coVerify(atLeast = 1) { GameRestoreRpcHandlers.restoreRunningGameForUser(accelByteId) }
        coVerify(atLeast = 1) { TurnHarness.resetState() }
        coVerifyOrder {
            GameRestoreRpcHandlers.restoreRunningGameForUser(accelByteId)
            TurnHarness.resetState()
        }
    }


    /**
     * Case 2 (negative — empty resumeUserId):
     * resumeFromVfs=true but resumeUserId blank → branch skipped, fresh
     * session path runs (resetState IS called, restoreRunningGameForUser
     * is NEVER called).
     */
    @Test
    fun `defineGameRules skips resume branch when resumeUserId is blank`()
    {
        coEvery { GameRestoreRpcHandlers.restoreRunningGameForUser(any()) } returns true
        coEvery { TurnHarness.resetState() } returns Unit
        coEvery { TurnHarness.onSessionBound(any(), any()) } returns Unit

        val sessionData = buildSessionData(
            resumeFromVfs = true,
            resumeUserId = "",
            sessionId = "live-session-2"
        )

        runCatching {
            runBlocking {
                GameInit.defineGameRules(
                    RpcCallContext(connectionId = "test-client") { error("no-op") },
                    sessionData
                )
            }
        }

        coVerify(exactly = 0) { GameRestoreRpcHandlers.restoreRunningGameForUser(any()) }
        coVerify(atLeast = 1) { TurnHarness.resetState() }
    }


    /**
     * Case 3 (negative — fresh session):
     * resumeFromVfs=false → branch skipped, resetState runs as normal.
     */
    @Test
    fun `defineGameRules skips resume branch when resumeFromVfs is false`()
    {
        coEvery { GameRestoreRpcHandlers.restoreRunningGameForUser(any()) } returns true
        coEvery { TurnHarness.resetState() } returns Unit
        coEvery { TurnHarness.onSessionBound(any(), any()) } returns Unit

        val sessionData = buildSessionData(
            resumeFromVfs = false,
            resumeUserId = accelByteId,  // present but ignored when flag is false
            sessionId = "live-session-3"
        )

        runCatching {
            runBlocking {
                GameInit.defineGameRules(
                    RpcCallContext(connectionId = "test-client") { error("no-op") },
                    sessionData
                )
            }
        }

        coVerify(exactly = 0) { GameRestoreRpcHandlers.restoreRunningGameForUser(any()) }
        coVerify(atLeast = 1) { TurnHarness.resetState() }
    }


    /**
     * Case 4 (rejection path):
     * resumeFromVfs=true but restoreRunningGameForUser returns false →
     * fresh-session path still runs (resetState IS called), restore was
     * attempted. This is the "snapshot was missing or corrupt" recovery
     * branch; without it, the player would see an empty world.
     */
    @Test
    fun `defineGameRules falls through to fresh-session path when restore returns false`()
    {
        coEvery { GameRestoreRpcHandlers.restoreRunningGameForUser(accelByteId) } returns false
        coEvery { TurnHarness.resetState() } returns Unit
        coEvery { TurnHarness.onSessionBound(any(), any()) } returns Unit

        val sessionData = buildSessionData(
            resumeFromVfs = true,
            resumeUserId = accelByteId,
            sessionId = "live-session-4"
        )

        runCatching {
            runBlocking {
                GameInit.defineGameRules(
                    RpcCallContext(connectionId = "test-client") { error("no-op") },
                    sessionData
                )
            }
        }

        // Restore WAS attempted (positive assertion: branch entered)
        coVerify(atLeast = 1) { GameRestoreRpcHandlers.restoreRunningGameForUser(accelByteId) }
        // Fresh-session path STILL runs (fall-through)
        coVerify(atLeast = 1) { TurnHarness.resetState() }
    }


    /**
     * Builds a minimal GameSessionStatus with one human player for the test.
     * Map loading and player config may throw in this minimal fixture; the
     * tests wrap the call in runCatching to swallow those downstream errors
     * so we only assert the resume-branch side effects.
     */
    private fun buildSessionData(
        resumeFromVfs: Boolean,
        resumeUserId: String,
        sessionId: String
    ) : GameSessionStatus
    {
        return GameSessionStatus().apply {
            this.sessionId = sessionId
            this.serverUrl = "127.0.0.1:9080"
            this.gameType = GameType.SINGLEPLAYER
            this.aiOpponentCount = 0
            this.aiOnly = false
            this.maxPlayers = 1
            this.currentPlayers = 1
            this.isFull = true
            this.resumeFromVfs = resumeFromVfs
            this.resumeUserId = resumeUserId
            this.players.add(
                PlayerSessionBundle(
                    accelByteUserName = "u-1",
                    accelByteId = accelByteId,
                    websocketId = "ws-1"
                )
            )
        }
    }
}
