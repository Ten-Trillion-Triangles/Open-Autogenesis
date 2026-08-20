package org.ttt.autogenesis.server

import gameState.WorldManager
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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
 * TDD tests pinning that [UiSignalRpcHandlers.notifyResumeAvailable] delivers
 * the `client.resumeAvailable` push to WebSocket sessions matched by
 * `accelbyteId`, not by `playerStats.playerID`.
 *
 * ## Why these tests exist (third bug found in the 2026-06-24 resume audit)
 *
 * Production (buggy) lookup chain at
 * `server/src/main/kotlin/org/ttt/autogenesis/server/UiSignalRpcHandlers.kt:643-667`:
 *
 *   val connectionId = WorldManager.playerStats
 *       .firstOrNull { it.accelByteUserId == userId }
 *       ?.playerID
 *       ?: ""
 *   ...
 *   val sessions = connectionManager?.findAllSessions(connectionId) ?: emptyList()
 *
 * This looks up WS sessions by `playerStats.playerID`, which is a stale id from
 * the snapshot's `playerID` field (e.g. `guest-user-conn-test`). The user's
 * ACTUAL WebSocket session is keyed by a JS-generated id
 * (`kvision-ws-client-1385884541`), so the lookup misses and the push is
 * dropped with a `sessions for connectionId=... not found` warning.
 *
 * ## The fix (pinned by these tests)
 *
 * Add `PlayerConnectionManager.findAllSessionsByAccelbyteId(accelbyteId): List<PlayerSession>`
 * that scans the sessions map and returns sessions where
 * `session.accelbyteId == accelbyteId`. Update `notifyResumeAvailable` to use
 * that lookup instead of going through `playerStats.playerID`.
 *
 * ## Test cases
 *
 *  1. **Positive (single WS session):** WS session with `playerId="kvision-ws-client-X"`
 *     and `accelbyteId="guest-user"`. `WorldManager.playerStats` has an entry
 *     with `accelByteUserId="guest-user"` but `playerID="guest-user-conn-test"`
 *     (DIFFERENT from the WS session's playerId). `notifyResumeAvailable`
 *     must deliver `client.resumeAvailable` to the WS session.
 *  2. **Negative (no matching WS session):** No WS session with the matching
 *     accelbyteId. `notifyResumeAvailable` must log a warning and NOT throw.
 *  3. **Multi-session:** Two WS sessions both carrying `accelbyteId="guest-user"`
 *     (e.g. primary browser + python controller reconnect). `notifyResumeAvailable`
 *     must deliver to BOTH.
 *
 * ## Fixture pattern
 *
 *  - `WorldManager` is reset between tests.
 *  - A real [PlayerConnectionManager] is used so we exercise the actual
 *    session-lookup code path (not a mock that hides the bug).
 *  - The underlying [DefaultWebSocketServerSession] is a relaxed mockk;
 *    `coVerify` with a 2 s timeout waits for the asynchronous
 *    [PlayerSession] send-loop coroutine to dispatch the frame.
 *  - For Test 3, both WS sessions share the same `accelbyteId` but have
 *    DIFFERENT `playerId`s (the multi-session use-case: browser primary
 *    + python controller under the same AccelByte user).
 */
class UiSignalRpcHandlersNotifyResumeByAccelbyteIdTest
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
        WorldManager.isGameActive = true
        WorldManager.isSinglePlayer = false
        WorldManager.humanPlayerName = ""
        WorldManager.activeMapPackName = ""
        WorldManager.activeMapPackBytes = null

        // Wire a REAL PlayerConnectionManager into the singleton so the
        // production handler exercises the actual session-lookup code path
        // (mocking it would hide the bug — the lookup key is what we're pinning).
        savedConnectionManager = UiSignalRpcHandlers.connectionManager
        realConnectionManager = PlayerConnectionManager()
        UiSignalRpcHandlers.connectionManager = realConnectionManager
    }

    @After
    fun tearDown()
    {
        UiSignalRpcHandlers.connectionManager = savedConnectionManager
    }

    // ====================================================================
    // Test 1 — Positive: WS session matched by accelbyteId, NOT by
    // playerStats.playerID. The production bug drops the push because
    // it looks up sessions by the stale playerStats.playerID
    // ("guest-user-conn-test") instead of by accelbyteId. After the fix
    // (PlayerConnectionManager.findAllSessionsByAccelbyteId), the WS
    // session keyed by the JS-generated id must receive the push.
    // ====================================================================
    @Test
    fun `notifyResumeAvailable delivers to WS sessions matched by accelbyteId even when playerStats playerID differs`() = runBlocking {
        val userId = "guest-user-${System.nanoTime()}"
        val wsPlayerId = "kvision-ws-client-${System.nanoTime()}"
        val stalePlayerStatsPlayerId = "guest-user-conn-test-${System.nanoTime()}"

        // 1. Set up playerStats with accelByteUserId=userId but playerID
        //    set to a STALE id that does NOT match the WS session's
        //    playerId (this is exactly the bug scenario).
        val player = Player(name = "Commander Shepard", description = "Test hero")
        WorldManager.world.activePlayers.add(player)
        WorldManager.playerStats.add(
            PlayerStats(
                playerData = player,
                accelByteUserId = userId,
                playerID = stalePlayerStatsPlayerId,
                isConnected = false,  // BUG 26: disconnected user — push must fire
                isControlledByNpc = false,
                turnActive = true
            )
        )

        // 2. Register the WS session under its JS-generated playerId,
        //    carrying accelbyteId=userId. This is the actual session that
        //    must receive the push.
        val wsMockSession = mockk<DefaultWebSocketServerSession>(relaxed = true)
        realConnectionManager.register(
            playerId = wsPlayerId,
            session = wsMockSession,
            role = SessionRole.PRIMARY,
            accelbyteId = userId
        )

        // 3. Invoke notifyResumeAvailable.
        val notification = ResumeAvailabilityNotification(
            userId = userId,
            worldRound = 7,
            turnIndex = 2,
            hasAi = true,
            savedAt = "2026-06-24T23:58:41Z"
        )
        val ctx = RpcCallContext(connectionId = "irrelevant", sender = { _ -> })
        UiSignalRpcHandlers.notifyResumeAvailable(ctx, notification)

        // 4. The WS session's websocket must have received a frame carrying
        //    the client.resumeAvailable notification. We coVerify with a
        //    2 s timeout to allow the PlayerSession.sendChannel + sendJob
        //    coroutine to dispatch the frame.
        val capturedFrame = slot<Frame>()
        coVerify(timeout = 2000) { wsMockSession.send(capture(capturedFrame)) }

        val captured = capturedFrame.captured
        assertTrue(
            captured is Frame.Text,
            "Expected a Text frame to be sent to the WS session; got ${captured::class.simpleName}"
        )
        val text = (captured as Frame.Text).readText()
        assertTrue(
            text.contains("client.resumeAvailable"),
            "Frame payload must carry the client.resumeAvailable method; got: $text"
        )
        assertTrue(
            text.contains("\"worldRound\":7") || text.contains("\"worldRound\": 7"),
            "Frame payload must carry the worldRound from the notification; got: $text"
        )
        assertTrue(
            text.contains("\"hasAi\":true") || text.contains("\"hasAi\": true"),
            "Frame payload must carry the hasAi flag; got: $text"
        )
    }

    // ====================================================================
    // Test 2 — Negative: no WS session with the matching accelbyteId.
    // notifyResumeAvailable must log a warning and return cleanly,
    // without throwing and without pushing to any session.
    //
    // We verify two things:
    //   (a) NO frame was sent to any websocket session (coVerify timeout 0).
    //   (b) The handler did not throw — the call returns normally.
    // ====================================================================
    @Test
    fun `notifyResumeAvailable does not push when no WS session matches the accelbyteId`() = runBlocking {
        val userId = "no-match-user-${System.nanoTime()}"
        val wsPlayerId = "kvision-ws-client-nomatch-${System.nanoTime()}"

        // 1. playerStats HAS the userId, so the production handler passes
        //    the "is connectionId blank?" check and reaches findAllSessions.
        val player = Player(name = "Commander Shepard", description = "Test hero")
        WorldManager.world.activePlayers.add(player)
        WorldManager.playerStats.add(
            PlayerStats(
                playerData = player,
                accelByteUserId = userId,
                playerID = "irrelevant-stats-player-id",
                isConnected = false,  // BUG 26: disconnected user — push must fire
                isControlledByNpc = false,
                turnActive = true
            )
        )

        // 2. Register a WS session with a DIFFERENT accelbyteId — it must
        //    not be touched. (Even after the fix, the lookup by userId
        //    returns empty for this session.)
        val wsMockSession = mockk<DefaultWebSocketServerSession>(relaxed = true)
        realConnectionManager.register(
            playerId = wsPlayerId,
            session = wsMockSession,
            role = SessionRole.PRIMARY,
            accelbyteId = "some-other-user-${System.nanoTime()}"
        )

        // 3. Invoke notifyResumeAvailable — must NOT throw and must NOT
        //    push anything to the registered WS session.
        val notification = ResumeAvailabilityNotification(
            userId = userId,
            worldRound = 1,
            turnIndex = 0,
            hasAi = false,
            savedAt = null
        )
        val ctx = RpcCallContext(connectionId = "irrelevant", sender = { _ -> })
        UiSignalRpcHandlers.notifyResumeAvailable(ctx, notification)

        // 4. No frame was sent to the WS session. coVerify(exactly=0, timeout=500)
        //    waits up to 500ms in case of scheduling jitter; the assertion is
        //    that the count is 0 across that window.
        coVerify(exactly = 0, timeout = 500) { wsMockSession.send(any()) }
    }

    // ====================================================================
    // Test 3 — Multi-session: TWO WS sessions both carrying
    // accelbyteId=userId (e.g. primary browser + python controller
    // reconnect under the same AccelByte user). notifyResumeAvailable
    // must deliver to BOTH.
    //
    // This pins that the fix is a many-to-many fan-out, not just
    // "find first session by accelbyteId".
    // ====================================================================
    @Test
    fun `notifyResumeAvailable finds sessions by accelbyteId across multiple WS sessions for the same user`() = runBlocking {
        val userId = "multi-session-user-${System.nanoTime()}"
        val primaryPlayerId = "kvision-ws-client-primary-${System.nanoTime()}"
        val controllerPlayerId = "kvision-ws-client-controller-${System.nanoTime()}"
        val stalePlayerStatsPlayerId = "stale-stats-${System.nanoTime()}"

        // 1. playerStats with stale playerID.
        val player = Player(name = "Commander Shepard", description = "Test hero")
        WorldManager.world.activePlayers.add(player)
        WorldManager.playerStats.add(
            PlayerStats(
                playerData = player,
                accelByteUserId = userId,
                playerID = stalePlayerStatsPlayerId,
                isConnected = false,  // BUG 26: disconnected user — push must fire
                isControlledByNpc = false,
                turnActive = true
            )
        )

        // 2. Register TWO WS sessions both with accelbyteId=userId but
        //    different playerIds (browser + controller reconnect scenario).
        val primaryMock = mockk<DefaultWebSocketServerSession>(relaxed = true)
        val controllerMock = mockk<DefaultWebSocketServerSession>(relaxed = true)
        realConnectionManager.register(
            playerId = primaryPlayerId,
            session = primaryMock,
            role = SessionRole.PRIMARY,
            accelbyteId = userId
        )
        realConnectionManager.register(
            playerId = controllerPlayerId,
            session = controllerMock,
            role = SessionRole.CONTROLLER,
            accelbyteId = userId
        )

        // 3. Invoke notifyResumeAvailable.
        val notification = ResumeAvailabilityNotification(
            userId = userId,
            worldRound = 13,
            turnIndex = 5,
            hasAi = true,
            savedAt = "2026-06-24T23:58:41Z"
        )
        val ctx = RpcCallContext(connectionId = "irrelevant", sender = { _ -> })
        UiSignalRpcHandlers.notifyResumeAvailable(ctx, notification)

        // 4. BOTH sessions must have received the client.resumeAvailable frame.
        val primaryFrame = slot<Frame>()
        val controllerFrame = slot<Frame>()
        coVerify(timeout = 2000) { primaryMock.send(capture(primaryFrame)) }
        coVerify(timeout = 2000) { controllerMock.send(capture(controllerFrame)) }

        // 5. Both frames carry the client.resumeAvailable method.
        for ((label, frame) in listOf("primary" to primaryFrame.captured, "controller" to controllerFrame.captured))
        {
            assertTrue(
                frame is Frame.Text,
                "Expected a Text frame for $label; got ${frame::class.simpleName}"
            )
            val text = (frame as Frame.Text).readText()
            assertTrue(
                text.contains("client.resumeAvailable"),
                "Frame payload for $label must carry client.resumeAvailable; got: $text"
            )
            assertTrue(
                text.contains("\"worldRound\":13") || text.contains("\"worldRound\": 13"),
                "Frame payload for $label must carry worldRound=13; got: $text"
            )
        }
    }

    // ====================================================================
    // Test 4 — Disconnected user. The BUG 26 fix: the mid-game guard at
    // notifyResumeAvailable must NOT skip the push just because the user
    // has a playerStats entry from a prior WS connect. The push must
    // fire whenever the user is NOT currently connected (isConnected=false
    // on the playerStats entry). This is the user's stated spec:
    //   - login → push fires (so dialog appears)
    //   - NEVER auto-restore (the restore is gated separately)
    // ====================================================================
    @Test
    fun `notifyResumeAvailable pushes even when the user has a playerStats entry from a prior disconnected session`() = runBlocking {
        val userId = "disconnected-user-${System.nanoTime()}"
        val wsPlayerId = "kvision-ws-client-disc-${System.nanoTime()}"

        // 1. Seed a playerStats entry for the user. isConnected=false to
        //    simulate the post-disconnect state (PlayerConnectionManager flips
        //    this on onDisconnected via Server.updatePlayerConnectionStats).
        WorldManager.playerStats.add(
            PlayerStats(
                playerData = structs.Player(name = "Disconnected Hero"),
                accelByteUserId = userId,
                isControlledByNpc = false,
                turnActive = true,
                isConnected = false  // BUG 26 (2026-06-27): disconnected user — push must fire
            )
        )
        WorldManager.humanPlayerName = "Disconnected Hero"

        // 2. Register a fresh WS session for the user (this is what the
        //    browser does on login). The push must reach this session.
        val wsMockSession = mockk<DefaultWebSocketServerSession>(relaxed = true)
        realConnectionManager.register(
            playerId = wsPlayerId,
            session = wsMockSession,
            role = SessionRole.PRIMARY,
            accelbyteId = userId
        )

        // 3. Invoke notifyResumeAvailable. The mid-game guard must NOT
        //    skip (because isConnected=false on the player's stats entry).
        val notification = ResumeAvailabilityNotification(
            userId = userId,
            worldRound = 1,
            turnIndex = 0,
            hasAi = true,
            savedAt = "2026-06-27T23:58:41Z"
        )
        val ctx = org.ttt.autogenesis.network.RpcCallContext(
            connectionId = "irrelevant",
            sender = { _ -> }
        )
        UiSignalRpcHandlers.notifyResumeAvailable(ctx, notification)

        // 4. The push must reach the user's WS session (the dialog will mount).
        val capturedFrame = io.mockk.slot<Frame>()
        coVerify(timeout = 2000) { wsMockSession.send(capture(capturedFrame)) }
        val text = (capturedFrame.captured as Frame.Text).readText()
        assertTrue(
            text.contains("client.resumeAvailable"),
            "BUG 26 (2026-06-27): push must fire for a disconnected user (isConnected=false on playerStats)"
        )
    }

    // ====================================================================
    // Test 5 — Connected user. The mid-game guard at notifyResumeAvailable
    // MUST skip the push if the user is currently connected
    // (isConnected=true on playerStats) — otherwise the dialog would
    // interrupt an active game.
    // ====================================================================
    @Test
    fun `notifyResumeAvailable skips push when user is currently connected (mid-game guard)`() = runBlocking {
        val userId = "connected-user-${System.nanoTime()}"
        val wsPlayerId = "kvision-ws-client-conn-${System.nanoTime()}"

        // 1. Seed a playerStats entry with isConnected=true AND turnActive=true
        //    (actively mid-turn).
        WorldManager.playerStats.add(
            PlayerStats(
                playerData = structs.Player(name = "Connected Hero"),
                accelByteUserId = userId,
                isControlledByNpc = false,
                turnActive = true,
                isConnected = true
            )
        )
        WorldManager.humanPlayerName = "Connected Hero"

        // 2. Register a WS session for the user.
        val wsMockSession = mockk<DefaultWebSocketServerSession>(relaxed = true)
        realConnectionManager.register(
            playerId = wsPlayerId,
            session = wsMockSession,
            role = SessionRole.PRIMARY,
            accelbyteId = userId
        )

        // 3. Invoke notifyResumeAvailable. The mid-game guard MUST skip
        //    because isConnected=true on the player's stats entry.
        val notification = ResumeAvailabilityNotification(
            userId = userId,
            worldRound = 5,
            turnIndex = 2,
            hasAi = true,
            savedAt = "2026-06-27T23:58:41Z"
        )
        val ctx = org.ttt.autogenesis.network.RpcCallContext(
            connectionId = "irrelevant",
            sender = { _ -> }
        )
        UiSignalRpcHandlers.notifyResumeAvailable(ctx, notification)

        // 4. NO frame should be sent (the guard skips).
        coVerify(timeout = 1500, exactly = 0) { wsMockSession.send(any()) }
    }

    // ====================================================================
    // Test 6 — Just connected, not yet mid-turn. BUG 26 (2026-06-27):
    // the user just logged in (fresh WS connect) but hasn't submitted a
    // turn yet (turnActive=false on their stale playerStats entry from
    // a previous session). The push MUST fire because the user is not
    // actively playing — they need the dialog to choose Resume vs New Game.
    //
    // This case is critical for the user's stated flow: after login,
    // the dialog must appear even though isConnected=true on a stale
    // playerStats entry from before the disconnect.
    // ====================================================================
    @Test
    fun `notifyResumeAvailable pushes when user just connected but turnActive is false (stale entry)`() = runBlocking {
        val userId = "stale-entry-user-${System.nanoTime()}"
        val wsPlayerId = "kvision-ws-client-stale-${System.nanoTime()}"

        // 1. Seed a playerStats entry with isConnected=true but turnActive=false.
        //    This models a user who previously played, disconnected (without
        //    the disconnect handler updating isConnected to false — a stale
        //    entry), and is now back. The fresh WS connect has populated
        //    isConnected=true; turnActive is still false because no turn
        //    loop has started yet.
        WorldManager.playerStats.add(
            PlayerStats(
                playerData = structs.Player(name = "Returning Hero"),
                accelByteUserId = userId,
                isControlledByNpc = false,
                turnActive = false,
                isConnected = true
            )
        )
        WorldManager.humanPlayerName = "Returning Hero"

        // 2. Register a WS session for the user.
        val wsMockSession = mockk<DefaultWebSocketServerSession>(relaxed = true)
        realConnectionManager.register(
            playerId = wsPlayerId,
            session = wsMockSession,
            role = SessionRole.PRIMARY,
            accelbyteId = userId
        )

        // 3. Invoke notifyResumeAvailable. The push MUST fire because the
        //    user is NOT actively playing a turn (turnActive=false).
        val notification = ResumeAvailabilityNotification(
            userId = userId,
            worldRound = 1,
            turnIndex = 0,
            hasAi = true,
            savedAt = "2026-06-27T23:58:41Z"
        )
        val ctx = org.ttt.autogenesis.network.RpcCallContext(
            connectionId = "irrelevant",
            sender = { _ -> }
        )
        UiSignalRpcHandlers.notifyResumeAvailable(ctx, notification)

        // 4. The push must reach the user's WS session.
        val capturedFrame = io.mockk.slot<Frame>()
        coVerify(timeout = 2000) { wsMockSession.send(capture(capturedFrame)) }
        val text = (capturedFrame.captured as Frame.Text).readText()
        assertTrue(
            text.contains("client.resumeAvailable"),
            "BUG 26 (2026-06-27): push must fire when user just connected but turnActive=false. " +
                "The dialog must appear after login per the user's spec, even with a stale playerStats entry."
        )
    }
}
