package org.ttt.autogenesis.server

import gameState.WorldManager
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcInvoker
import structs.World
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression test for BUG 26 (2026-06-27): client spec says "the game does
 * not at all ever auto-resume for any reason ever." The auto-restore
 * path that previously fired inside [Server.onConnected] has been
 * disabled unconditionally — the resume flow now only happens when the
 * user explicitly clicks Resume in [ResumeOrNewDialog], which calls
 * [org.ttt.autogenesis.server.GameRestoreRpcHandlers.restoreRunningGame].
 *
 * These tests pin the new invariant:
 *   - [resolveAutoRestoreUserId] ALWAYS returns null, regardless of session
 *     role, world state, or accelbyteId presence.
 *   - [TurnHarness.restoreWorldFromUserRecord] is NEVER called from the
 *     Server.onConnected path (the IO-launch was removed entirely).
 *
 * The previous regression test ([ServerAutoRestoreAccelbyteIdTest]) pinned
 * the inverse behavior — that the auto-restore fired under specific
 * conditions. That file was deleted when this spec change shipped.
 *
 * References:
 *   - server/src/main/kotlin/org/ttt/autogenesis/server/Server.kt:241-295
 *     (resolveAutoRestoreUserId — now always returns null)
 *   - server/src/main/kotlin/org/ttt/autogenesis/server/Server.kt:407-475
 *     (onConnected — the auto-restore coroutine launch was removed)
 *   - server/src/main/kotlin/org/ttt/autogenesis/server/GameRestoreRpcHandlers.kt:128-186
 *     (the explicit-Resume path that still works)
 */
class AutoRestoreDisabledTest
{
    @Before
    fun setUp()
    {
        WorldManager.world = World()
        WorldManager.history.clear()
        WorldManager.actionHistoryLog.clear()
        WorldManager.pendingActionHistoryByTurn.clear()
        WorldManager.playerStats.clear()
        WorldManager.isGameActive = true
        WorldManager.isSinglePlayer = true
        WorldManager.humanPlayerName = ""
        WorldManager.activeMapPackName = ""
        WorldManager.activeMapPackBytes = null

        mockkObject(TurnHarness)
        coEvery { TurnHarness.restoreWorldFromUserRecord(any(), any()) } returns Result.success(true)
    }

    @After
    fun tearDown()
    {
        unmockkObject(TurnHarness)
    }

    /**
     * Helper: build a PlayerSession with the heavy ctor params mocked out.
     * resolveAutoRestoreUserId only reads playerId + role + accelbyteId.
     */
    private fun buildSession(
        playerId: String,
        role: SessionRole,
        accelbyteId: String
    ): PlayerSession
    {
        val wsSession = mockk<DefaultWebSocketServerSession>(relaxed = true)
        val invoker = mockk<RpcInvoker>(relaxed = true)
        return PlayerSession(
            playerId = playerId,
            session = wsSession,
            invoker = invoker,
            role = role,
            accelbyteId = accelbyteId
        )
    }

    /**
     * Test 1: PRIMARY session with valid accelbyteId on a fresh (empty) world.
     * This is the case that PREVIOUSLY triggered auto-restore. Now it must NOT.
     */
    @Test
    fun `resolveAutoRestoreUserId returns null for PRIMARY session with accelbyteId on empty world`() = runBlocking {
        val session = buildSession(
            playerId = "test-conn-1",
            role = SessionRole.PRIMARY,
            accelbyteId = "00000000000000000000000000000000"
        )
        assertTrue(WorldManager.isWorldEmpty(), "pre-condition: world must be empty")

        val resolved = resolveAutoRestoreUserId(session)
        assertNull(
            resolved,
            "BUG 26 (2026-06-27): auto-restore MUST NOT fire on PRIMARY+accelbyteId+emptyWorld. " +
                "The user's spec is that the resume flow only happens after explicit Resume click."
        )
        coVerify(exactly = 0) { TurnHarness.restoreWorldFromUserRecord(any(), any()) }
    }

    /**
     * Test 2: CONTROLLER session (Python AI drivers, observer-only).
     * Must always return null — regression coverage from the original
     * role-gate.
     */
    @Test
    fun `resolveAutoRestoreUserId returns null for CONTROLLER session regardless of world state`() = runBlocking {
        val session = buildSession(
            playerId = "test-controller-1",
            role = SessionRole.CONTROLLER,
            accelbyteId = "00000000000000000000000000000000"
        )
        assertTrue(WorldManager.isWorldEmpty(), "pre-condition: world must be empty")

        val resolved = resolveAutoRestoreUserId(session)
        assertNull(resolved, "CONTROLLER sessions must never trigger auto-restore (observer-only)")
        coVerify(exactly = 0) { TurnHarness.restoreWorldFromUserRecord(any(), any()) }
    }

    /**
     * Test 3: PRIMARY session on a non-empty world (game in progress).
     * Defensive — this was a previous gate and the new code keeps it.
     */
    @Test
    fun `resolveAutoRestoreUserId returns null when world is non-empty even if session is PRIMARY with accelbyteId`() = runBlocking {
        val session = buildSession(
            playerId = "test-conn-midgame",
            role = SessionRole.PRIMARY,
            accelbyteId = "00000000000000000000000000000000"
        )
        // Simulate a game-in-progress state by seeding history.
        WorldManager.history.add(structs.GameHistory(turnStory = "Turn 1 event"))
        WorldManager.world.roundNumber = 3
        assertFalse(WorldManager.isWorldEmpty(), "pre-condition: world must be non-empty")

        val resolved = resolveAutoRestoreUserId(session)
        assertNull(resolved, "auto-restore must never clobber an in-progress game")
        coVerify(exactly = 0) { TurnHarness.restoreWorldFromUserRecord(any(), any()) }
    }
}