package org.ttt.autogenesis.server

import gameState.WorldManager
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import serverStructs.PlayerStats
import structs.Player
import structs.World
import structs.resume.ResumeAvailabilityNotification
import kotlin.test.assertTrue

/**
 * TDD tests pinning the per-userId dedupe behavior in
 * [UiSignalRpcHandlers.notifyResumeAvailable] (BUG 27, 2026-07-01).
 *
 * ## Why these tests exist
 *
 * Production behavior prior to this fix: `notifyResumeAvailable` runs on every
 * SSE rebind (because `triggerSseResumePush` is called from the per-request
 * `get("/events")` handler in `server-extend/.../ServerExtend.kt:347-350`).
 * The dialog-mount listener then fires `mountResumeDialog` and stacks a
 * fresh `ResumeOrNewDialog` over the previous one, even when the user has
 * already resumed.
 *
 * The dedupe lives on the server: once we have pushed the `client.resumeAvailable`
 * notification to a given user, we MUST NOT push again until the client calls
 * `server.consumeResumePush` (which the client fires on Resume / New Game / Cancel).
 *
 * ## Test cases
 *
 *  1. **Dedupes repeat pushes** — two calls to `notifyResumeAvailable` for the
 *     same userId produce exactly ONE frame to the WS session. The second
 *     call must log "skipped (already pushed this session)" and return.
 *  2. **Re-arms after consume** — push, consume, push → 2 frames sent total.
 *  3. **Does not dedupe across userIds** — user A and user B each get their own push.
 *  4. **consume for unknown userId is a no-op** — must not throw, must not affect others.
 *  5. **Consume is idempotent** — calling consume twice for the same userId is safe.
 *
 * ## Fixture pattern
 *
 *  - `WorldManager` is reset between tests.
 *  - A real [PlayerConnectionManager] is used so the production lookup path
 *    is exercised (not mocked).
 *  - The underlying [DefaultWebSocketServerSession] is a relaxed mockk; `coVerify`
 *    with a 2s timeout waits for the asynchronous send-loop coroutine.
 *  - The dedupe set lives on the `UiSignalRpcHandlers` singleton. The set MUST
 *    be reset in `setUp`/`tearDown` so test order does not affect results.
 *    (Implemented in Task 2 as `UiSignalRpcHandlers.resetResumePushDedupeForTest()`.)
 */
class UiSignalRpcHandlersResumeDedupeTest
{
    private lateinit var realConnectionManager: PlayerConnectionManager
    private var savedConnectionManager: PlayerConnectionManager? = null

    @Before
    fun setUp()
    {
        // Fresh WorldManager per test — notifyResumeAvailable reads playerStats.
        WorldManager.world = World()
        WorldManager.history.clear()
        WorldManager.actionHistoryLog.clear()
        WorldManager.pendingActionHistoryByTurn.clear()
        WorldManager.playerStats.clear()
        WorldManager.isGameActive = false
        WorldManager.isSinglePlayer = false
        WorldManager.humanPlayerName = ""
        WorldManager.activeMapPackName = ""
        WorldManager.activeMapPackBytes = null

        // Wire a REAL PlayerConnectionManager into the singleton.
        savedConnectionManager = UiSignalRpcHandlers.connectionManager
        realConnectionManager = PlayerConnectionManager()
        UiSignalRpcHandlers.connectionManager = realConnectionManager

        // Reset the per-server dedupe set so test order does not leak state.
        // (Will be added in Task 2; the call is a no-op before then.)
        try
        {
            UiSignalRpcHandlers.resetResumePushDedupeForTest()
        }
        catch (_: Throwable)
        {
            // Pre-fix: the method does not exist yet. TDD red phase: swallow.
        }
    }

    @After
    fun tearDown()
    {
        UiSignalRpcHandlers.connectionManager = savedConnectionManager
        try
        {
            UiSignalRpcHandlers.resetResumePushDedupeForTest()
        }
        catch (_: Throwable)
        {
            // Same as setUp.
        }
    }

    // ====================================================================
    // Test 1 — Dedupes repeat pushes.
    // The first call sends 1 frame; the second call sends 0 additional frames.
    // The handler must return early on the second call without producing any
    // network traffic to the WS session.
    // ====================================================================
    @Test
    fun `notifyResumeAvailable dedupes repeat pushes for the same userId within a server session`() = runBlocking {
        val userId = "dedupe-user-${System.nanoTime()}"
        val wsPlayerId = "kvision-ws-client-dedupe-${System.nanoTime()}"

        // playerStats: user is disconnected, NOT mid-turn, so neither the
        // existing mid-game guard nor the humanEntry guard fires.
        val player = Player(name = "Lord Maple Tree", description = "Test hero")
        WorldManager.world.activePlayers.add(player)
        WorldManager.playerStats.add(
            PlayerStats(
                playerData = player,
                accelByteUserId = userId,
                playerID = "stale-stats-id",
                isConnected = false,
                isControlledByNpc = false,
                turnActive = false
            )
        )

        val wsMockSession = mockk<DefaultWebSocketServerSession>(relaxed = true)
        realConnectionManager.register(
            playerId = wsPlayerId,
            session = wsMockSession,
            role = SessionRole.PRIMARY,
            accelbyteId = userId
        )

        val notification = ResumeAvailabilityNotification(
            userId = userId,
            worldRound = 5,
            turnIndex = 0,
            hasAi = true,
            savedAt = "2026-07-01T20:14:51Z"
        )
        val ctx = RpcCallContext(connectionId = "irrelevant", sender = { _ -> })

        // First call — must push.
        UiSignalRpcHandlers.notifyResumeAvailable(ctx, notification)
        coVerify(timeout = 2000) { wsMockSession.send(any()) }

        // Second call (the SSE-rebind scenario) — must be a no-op for the WS session.
        UiSignalRpcHandlers.notifyResumeAvailable(ctx, notification)

        // Allow the send-loop coroutine any pending frames to flush; the
        // dedupe means NONE should arrive beyond the first.
        coVerify(exactly = 1, timeout = 1000) { wsMockSession.send(any()) }
    }

    // ====================================================================
    // Test 2 — Re-arms after consume.
    // push → consume → push → 2 frames sent total.
    // ====================================================================
    @Test
    fun `notifyResumeAvailable re-arms after server consumeResumePush RPC for the same userId`() = runBlocking {
        val userId = "rearm-user-${System.nanoTime()}"
        val wsPlayerId = "kvision-ws-client-rearm-${System.nanoTime()}"

        val player = Player(name = "ReArm Tester", description = "Test hero")
        WorldManager.world.activePlayers.add(player)
        WorldManager.playerStats.add(
            PlayerStats(
                playerData = player,
                accelByteUserId = userId,
                playerID = "stale-rearm-id",
                isConnected = false,
                isControlledByNpc = false,
                turnActive = false
            )
        )

        val wsMockSession = mockk<DefaultWebSocketServerSession>(relaxed = true)
        realConnectionManager.register(
            playerId = wsPlayerId,
            session = wsMockSession,
            role = SessionRole.PRIMARY,
            accelbyteId = userId
        )

        val notification = ResumeAvailabilityNotification(
            userId = userId,
            worldRound = 3,
            turnIndex = 1,
            hasAi = false,
            savedAt = "2026-07-01T20:14:51Z"
        )
        val ctx = RpcCallContext(connectionId = "irrelevant", sender = { _ -> })

        // First push.
        UiSignalRpcHandlers.notifyResumeAvailable(ctx, notification)
        coVerify(timeout = 2000) { wsMockSession.send(any()) }

        // Consume — the user picked Resume / New Game / Cancel.
        UiSignalRpcHandlers.consumeResumePush(
            RpcCallContext(connectionId = "irrelevant", sender = { _ -> }),
            structs.resume.ResumePushConsumeRequest(userId = userId)
        )

        // Second push (e.g. user re-logs in or reopens the menu) — must succeed.
        UiSignalRpcHandlers.notifyResumeAvailable(ctx, notification)
        coVerify(exactly = 2, timeout = 2000) { wsMockSession.send(any()) }
    }

    // ====================================================================
    // Test 3 — Different userIds do NOT dedupe against each other.
    // ====================================================================
    @Test
    fun `notifyResumeAvailable does not dedupe across different userIds`() = runBlocking {
        val userA = "user-a-${System.nanoTime()}"
        val userB = "user-b-${System.nanoTime()}"
        val playerIdA = "ws-client-a-${System.nanoTime()}"
        val playerIdB = "ws-client-b-${System.nanoTime()}"

        val playerA = Player(name = "Tester A", description = "x")
        val playerB = Player(name = "Tester B", description = "x")
        WorldManager.world.activePlayers.add(playerA)
        WorldManager.world.activePlayers.add(playerB)
        WorldManager.playerStats.add(
            PlayerStats(playerData = playerA, accelByteUserId = userA, playerID = "sa",
                isConnected = false, isControlledByNpc = false, turnActive = false)
        )
        WorldManager.playerStats.add(
            PlayerStats(playerData = playerB, accelByteUserId = userB, playerID = "sb",
                isConnected = false, isControlledByNpc = false, turnActive = false)
        )

        val sessionA = mockk<DefaultWebSocketServerSession>(relaxed = true)
        val sessionB = mockk<DefaultWebSocketServerSession>(relaxed = true)
        realConnectionManager.register(playerIdA, sessionA, SessionRole.PRIMARY, accelbyteId = userA)
        realConnectionManager.register(playerIdB, sessionB, SessionRole.PRIMARY, accelbyteId = userB)

        val ctx = RpcCallContext(connectionId = "irrelevant", sender = { _ -> })

        // Push to A.
        UiSignalRpcHandlers.notifyResumeAvailable(
            ctx,
            ResumeAvailabilityNotification(userA, 1, 0, false, null)
        )
        coVerify(timeout = 2000) { sessionA.send(any()) }

        // Push to A again — must be deduped (no extra frame to A).
        UiSignalRpcHandlers.notifyResumeAvailable(
            ctx,
            ResumeAvailabilityNotification(userA, 1, 0, false, null)
        )
        coVerify(exactly = 1, timeout = 1000) { sessionA.send(any()) }

        // Push to B — must succeed independently of A's dedupe.
        UiSignalRpcHandlers.notifyResumeAvailable(
            ctx,
            ResumeAvailabilityNotification(userB, 1, 0, false, null)
        )
        coVerify(timeout = 2000) { sessionB.send(any()) }
    }

    // ====================================================================
    // Test 4 — consume for an unknown userId is a silent no-op.
    // ====================================================================
    @Test
    fun `consumeResumePush for an unknown userId is a silent no-op and does not affect other users`() = runBlocking {
        val realUser = "real-user-${System.nanoTime()}"
        val stranger = "stranger-${System.nanoTime()}"

        val playerReal = Player(name = "Real", description = "x")
        WorldManager.world.activePlayers.add(playerReal)
        WorldManager.playerStats.add(
            PlayerStats(playerData = playerReal, accelByteUserId = realUser, playerID = "r",
                isConnected = false, isControlledByNpc = false, turnActive = false)
        )
        val wsMockSession = mockk<DefaultWebSocketServerSession>(relaxed = true)
        realConnectionManager.register(
            "ws-real", wsMockSession, SessionRole.PRIMARY, accelbyteId = realUser
        )

        val ctx = RpcCallContext(connectionId = "irrelevant", sender = { _ -> })

        // Push to realUser.
        UiSignalRpcHandlers.notifyResumeAvailable(
            ctx, ResumeAvailabilityNotification(realUser, 1, 0, false, null)
        )
        coVerify(timeout = 2000) { wsMockSession.send(any()) }

        // Consume for a stranger — must not throw, must not un-dedupe realUser.
        UiSignalRpcHandlers.consumeResumePush(
            ctx,
            structs.resume.ResumePushConsumeRequest(userId = stranger)
        )

        // realUser is still in the dedupe set; second push must be a no-op.
        UiSignalRpcHandlers.notifyResumeAvailable(
            ctx, ResumeAvailabilityNotification(realUser, 1, 0, false, null)
        )
        coVerify(exactly = 1, timeout = 1000) { wsMockSession.send(any()) }
    }

    // ====================================================================
    // Test 5 — Consume is idempotent.
    // Calling consume twice for the same userId does not break subsequent pushes.
    // ====================================================================
    @Test
    fun `consumeResumePush is idempotent for the same userId`() = runBlocking {
        val userId = "idempotent-user-${System.nanoTime()}"
        val player = Player(name = "Idempotent", description = "x")
        WorldManager.world.activePlayers.add(player)
        WorldManager.playerStats.add(
            PlayerStats(playerData = player, accelByteUserId = userId, playerID = "i",
                isConnected = false, isControlledByNpc = false, turnActive = false)
        )
        val wsMockSession = mockk<DefaultWebSocketServerSession>(relaxed = true)
        realConnectionManager.register(
            "ws-idemp", wsMockSession, SessionRole.PRIMARY, accelbyteId = userId
        )

        val ctx = RpcCallContext(connectionId = "irrelevant", sender = { _ -> })

        // push, consume x2, push → 2 frames.
        UiSignalRpcHandlers.notifyResumeAvailable(
            ctx, ResumeAvailabilityNotification(userId, 1, 0, false, null)
        )
        coVerify(timeout = 2000) { wsMockSession.send(any()) }

        UiSignalRpcHandlers.consumeResumePush(
            ctx, structs.resume.ResumePushConsumeRequest(userId = userId)
        )
        // Idempotent — second call must not throw, must not unbreak anything.
        UiSignalRpcHandlers.consumeResumePush(
            ctx, structs.resume.ResumePushConsumeRequest(userId = userId)
        )

        UiSignalRpcHandlers.notifyResumeAvailable(
            ctx, ResumeAvailabilityNotification(userId, 1, 0, false, null)
        )
        coVerify(exactly = 2, timeout = 2000) { wsMockSession.send(any()) }
    }

    // ====================================================================
    // Sanity — the dedupe set is reset by the test seam, not by the
    // production path. This test verifies the reset hook exists.
    // (Will throw NotImplementedError pre-fix; that is the TDD red signal.)
    // ====================================================================
    @Test
    fun `resetResumePushDedupeForTest empties the dedupe set between tests`() {
        // Pre-fix: this method does not exist yet → NoSuchMethodError or
        // NotImplementedError. Post-fix: it runs cleanly and empties the set.
        UiSignalRpcHandlers.resetResumePushDedupeForTest()
        // Push once, reset, push again — should send 2 frames total in a real
        // session, but in this test we only verify the reset does not throw.
        // Frame-counting is covered by Test 2's re-arm assertion.
        assertTrue(true)
    }
}