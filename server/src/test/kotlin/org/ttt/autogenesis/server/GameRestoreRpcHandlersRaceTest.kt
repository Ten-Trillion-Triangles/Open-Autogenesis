package org.ttt.autogenesis.server

import gameState.WorldManager
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import serverStructs.PlayerStats
import structs.Player
import structs.World
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for the auto-restore vs. ResumeOrNewDialog race condition.
 *
 * Race window (see Server.kt:295 and MainMenu.kt:127):
 *   1. User reconnects → WS opens → `onConnected` fires.
 *   2. Server launches `TurnHarness.restoreWorldFromUserRecord` on
 *      Dispatchers.IO. That call applies the snapshot AND writes a
 *      consumed-sentinel (delete-on-restore TTL — TurnHarness.kt:1861).
 *   3. Concurrently, MainMenu on the client runs
 *      `MatchmakingClient.hasRunningGame()` on MainScope. If MainMenu's
 *      fetch lands BEFORE the IO invalidate finishes, the snapshot is
 *      still real → dialog is shown.
 *   4. User clicks Resume → `server.restoreRunningGame` RPC fires.
 *   5. By the time the RPC reaches the server, auto-restore has already
 *      invalidated the snapshot. `restoreWorldFromUserRecord` fetches the
 *      consumed-sentinel, fails to deserialize as GameSnapshot, and
 *      returns Result.failure.
 *   6. `restoreRunningGame` returns false → user sees "Resume Failed"
 *      even though the game is in fact restored and ready to play.
 *
 * The tests below pin the desired behaviour:
 *   - When auto-restore has already restored the world for the calling
 *     user, `restoreRunningGame` must return `true` (idempotent success)
 *     and not show "Resume Failed".
 *   - When auto-restore has not run and no snapshot exists, the RPC
 *     returns `false` (genuine "no save").
 *   - When auto-restore has not run and a real snapshot exists, the RPC
 *     restores it (the existing happy path) and returns `true`.
 */
class GameRestoreRpcHandlersRaceTest
{
    private var savedConnectionManager: PlayerConnectionManager? = null
    private lateinit var mockConnectionManager: PlayerConnectionManager

    @Before
    fun setup() = runBlocking {
        WorldManager.world = World()
        WorldManager.history.clear()
        WorldManager.actionHistoryLog.clear()
        WorldManager.pendingActionHistoryByTurn.clear()
        WorldManager.playerStats.clear()
        WorldManager.isGameActive = true
        WorldManager.isSinglePlayer = true
        WorldManager.humanPlayerName = "Commander Shepard"
        WorldManager.activeMapPackName = ""
        WorldManager.activeMapPackBytes = null
        // Clear the rehydrated flag from prior tests (it survives across
        // test methods because WorldManager is a singleton). Without this,
        // a previous test that left the flag set would silently pass
        // race-recovery checks in this test's setup.
        WorldManager.clearRehydratedFlag()
        TurnHarness.resetState()

        savedConnectionManager = UiSignalRpcHandlers.connectionManager
        mockConnectionManager = mockk(relaxed = true)
        UiSignalRpcHandlers.connectionManager = mockConnectionManager

        try
        {
            val tempDir = java.nio.file.Files.createTempDirectory("game-restore-race-test")
            VirtualFileSystemManager.initialize(listOf("--mode=local", "--vfs-local-dir=${tempDir.toAbsolutePath()}"))
        }
        catch (e: Exception)
        {
            // ignore re-initialization
        }
    }

    @After
    fun teardown()
    {
        UiSignalRpcHandlers.connectionManager = savedConnectionManager
    }

    /**
     * Captures the race itself: user clicks Resume AFTER auto-restore has
     * completed. The VFS now holds a consumed-sentinel; the world is in the
     * restored state for this user. `restoreRunningGame` must NOT fail.
     */
    @Test
    fun `restoreRunningGame returns true when auto-restore has already applied the snapshot`() = runBlocking {
        val userId = "test-race-restored-${System.nanoTime()}"
        val connectionId = "test-race-restored-conn-${System.nanoTime()}"

        // 1. Register the player and save a snapshot, mirroring the
        //    pre-disconnect state on the main server.
        val player = Player(name = "Commander Shepard", description = "Test hero")
        WorldManager.world.activePlayers.add(player)
        WorldManager.world.roundNumber = 7
        WorldManager.playerStats.add(
            PlayerStats(
                playerData = player,
                accelByteUserId = userId,
                playerID = connectionId,
                isConnected = true,
                isControlledByNpc = false,
                turnActive = true
            )
        )
        assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

        // 2. Simulate Server.kt:298 firing on connect — the auto-restore
        //    coroutine reads the snapshot, applies it to WorldManager, and
        //    writes the consumed-sentinel via invalidateRunningGameRecord.
        val autoRestoreResult = TurnHarness.restoreWorldFromUserRecord(userId)
        assertTrue(autoRestoreResult.getOrDefault(false), "auto-restore should succeed in setup")
        assertFalse(WorldManager.isWorldEmpty(), "world should be non-empty after auto-restore")
        assertEquals(7, WorldManager.world.roundNumber, "world should reflect the snapshot round")

        // 3. The user, who lost the race against auto-restore, clicks
        //    Resume in the ResumeOrNewDialog. restoreRunningGame fires.
        val ctx = RpcCallContext(connectionId = connectionId, sender = { _ -> })
        val restored = GameRestoreRpcHandlers.restoreRunningGame(ctx)

        // 4. Expected: idempotent success. The game is already in the
        //    resumed state, so the user must NOT see "Resume Failed".
        assertTrue(
            restored,
            "restoreRunningGame must succeed when auto-restore already applied the snapshot " +
                "(race recovered via world-is-non-empty + playerStats match)"
        )

        // 5. World state must still reflect the snapshot.
        assertEquals(7, WorldManager.world.roundNumber, "round number must survive the race recovery path")
    }

    /**
     * When the user simply has no saved game at all, the RPC must still
     * report `false`. The race-recovery path must not over-reach.
     */
    @Test
    fun `restoreRunningGame returns false when there is no snapshot and the world is empty`() = runBlocking {
        val userId = "test-race-empty-${System.nanoTime()}"
        val connectionId = "test-race-empty-conn-${System.nanoTime()}"

        // No snapshot saved, no auto-restore, world is empty.
        val player = Player(name = "Commander Shepard", description = "Test hero")
        WorldManager.playerStats.add(
            PlayerStats(
                playerData = player,
                accelByteUserId = userId,
                playerID = connectionId,
                isConnected = true,
                isControlledByNpc = false,
                turnActive = true
            )
        )

        val ctx = RpcCallContext(connectionId = connectionId, sender = { _ -> })
        val restored = GameRestoreRpcHandlers.restoreRunningGame(ctx)

        assertEquals(false, restored, "no snapshot and no auto-restore must return false")
    }

    /**
     * Happy-path regression guard: when the VFS still holds a real
     * snapshot (auto-restore did NOT run, e.g. fresh dev server), the
     * existing restore flow must still work.
     */
    @Test
    fun `restoreRunningGame restores from VFS when no auto-restore has run`() = runBlocking {
        val userId = "test-race-fresh-${System.nanoTime()}"
        val connectionId = "test-race-fresh-conn-${System.nanoTime()}"

        val player = Player(name = "Commander Shepard", description = "Test hero")
        WorldManager.world.activePlayers.add(player)
        WorldManager.world.roundNumber = 4
        WorldManager.playerStats.add(
            PlayerStats(
                playerData = player,
                accelByteUserId = userId,
                playerID = connectionId,
                isConnected = true,
                isControlledByNpc = false,
                turnActive = true
            )
        )
        assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

        // Simulate a fresh DS: world is empty, no auto-restore.
        WorldManager.world = World()
        WorldManager.world.activePlayers.add(player)
        assertTrue(WorldManager.isWorldEmpty())

        val ctx = RpcCallContext(connectionId = connectionId, sender = { _ -> })
        val restored = GameRestoreRpcHandlers.restoreRunningGame(ctx)

        assertTrue(restored, "fresh DS with a snapshot must restore")
        assertEquals(4, WorldManager.world.roundNumber, "round number must be applied from the snapshot")
    }

    /**
     * With the preserved-snapshot contract: the VFS still has a valid
     * GameSnapshot (NOT a consumed-sentinel) after restore, so a round-5
     * game is offered as a Resume even after the in-process flag is
     * cleared. This is the intended "reload and resume" behavior the
     * user asked for: "you should be able to retry a restore without
     * needing to burn tokens on a full turn redo from scratch".
     */
    @Test
    fun `restoreRunningGame returns true when world is empty but valid snapshot is preserved`() = runBlocking {
        val userId = "test-race-sentinel-only-${System.nanoTime()}"
        val connectionId = "test-race-sentinel-only-conn-${System.nanoTime()}"

        val player = Player(name = "Commander Shepard", description = "Test hero")
        WorldManager.world.activePlayers.add(player)
        WorldManager.world.roundNumber = 5
        WorldManager.playerStats.add(
            PlayerStats(
                playerData = player,
                accelByteUserId = userId,
                playerID = connectionId,
                isConnected = true,
                isControlledByNpc = false,
                turnActive = true
            )
        )
        assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

        // Run auto-restore, then wipe the world to simulate a server
        // that crashed between auto-restore and the user clicking Resume.
        TurnHarness.restoreWorldFromUserRecord(userId)
        assertFalse(WorldManager.isWorldEmpty())
        WorldManager.world = World()
        // Clear the rehydrated flag too — in a real crash scenario the
        // JVM restarts entirely so the flag is gone. Pre-fix the test
        // relied on the buggy `isWorldEmpty()` check returning true on
        // a round-1 game; the race-recovery now reads the rehydrated flag
        // AND falls through to the VFS check (which still holds the
        // valid GameSnapshot under the preserved-snapshot contract).
        WorldManager.clearRehydratedFlag()
        assertTrue(WorldManager.isWorldEmpty(), "world must be empty after the crash sim")

        val ctx = RpcCallContext(connectionId = connectionId, sender = { _ -> })
        val restored = GameRestoreRpcHandlers.restoreRunningGame(ctx)

        assertEquals(
            true,
            restored,
            "VFS holds a valid preserved GameSnapshot — restore must succeed (reloads without burning the save)"
        )
    }

    /**
     * BUG 3 regression (2026-06-26): the race-recovery check used to rely
     * on `WorldManager.isWorldEmpty()` which returns true for any game with
     * `roundNumber <= 1 && history.isEmpty()` — including a round-1 game
     * that was just restored from a snapshot. A round-1 resumed game was
     * therefore indistinguishable from a fresh server, and the
     * race-recovery branch returned false. The user clicked Resume on a
     * genuinely-restored round-1 game and saw "No saved game found."
     *
     * Fix: the race-recovery now reads the `lastRehydratedAccelByteUserId`
     * flag set by `TurnHarness.applyGameSnapshot`. This test exercises the
     * round-1 path explicitly.
     */
    @Test
    fun `restoreRunningGame recovers race on round-1 game with empty history`() = runBlocking {
        val userId = "test-race-round1-${System.nanoTime()}"
        val connectionId = "test-race-round1-conn-${System.nanoTime()}"

        // 1. Setup: register the player and save a round-1 snapshot with
        //    NO history. This is the worst case for the old
        //    `isWorldEmpty()` race-recovery check (it looks identical to
        //    a fresh server).
        val player = Player(name = "Commander Shepard", description = "Test hero")
        WorldManager.world.activePlayers.add(player)
        WorldManager.world.roundNumber = 1
        WorldManager.playerStats.add(
            PlayerStats(
                playerData = player,
                accelByteUserId = userId,
                playerID = connectionId,
                isConnected = true,
                isControlledByNpc = false,
                turnActive = false
            )
        )
        assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

        // 2. Simulate auto-restore: the IO coroutine applies the snapshot
        //    AND writes the consumed-sentinel. After this the world looks
        //    empty (`roundNumber=1, history=empty`) but the rehydrated
        //    flag is set.
        val autoRestoreResult = TurnHarness.restoreWorldFromUserRecord(userId)
        assertTrue(autoRestoreResult.getOrDefault(false), "auto-restore should succeed")
        assertTrue(WorldManager.isWorldEmpty(), "round-1 game with no history is indistinguishable from empty under the old check")
        assertEquals(
            userId,
            WorldManager.lastRehydratedAccelByteUserId,
            "rehydrated flag must be set by applyGameSnapshot"
        )

        // 3. User clicks Resume.
        val ctx = RpcCallContext(connectionId = connectionId, sender = { _ -> })
        val restored = GameRestoreRpcHandlers.restoreRunningGame(ctx)

        // 4. Expected: idempotent success via race-recovery. Pre-fix this
        //    would return false ("No saved game found" surfaced to the
        //    user).
        assertTrue(
            restored,
            "round-1 resumed game MUST recover via rehydrated-flag race-recovery " +
                    "(pre-fix bug: world looked empty, race-recovery returned false, " +
                    "user saw 'No saved game found' for a game that was actually restored)"
        )
    }

    /**
     * `restoreStatus` reports whether an auto-restore is in flight for a
     * given user. Used by server-extend's `ResumeAvailabilityPushService`
     * to wait for the main server's auto-restore to finish before
     * pushing `client.resumeAvailable`. Without this gate, server-extend
     * would push based on a snapshot the main server is about to consume.
     */
    @Test
    fun `restoreStatus reflects active restore state`() = runBlocking {
        val userId = "test-restore-status-${System.nanoTime()}"

        // 1. No restore running for this user → both flags false.
        var status = GameRestoreRpcHandlers.restoreStatus(
            RpcCallContext(connectionId = "test-rs-conn-${System.nanoTime()}", sender = { _ -> }),
            org.ttt.autogenesis.network.RestoreStatusRequest(userId = userId)
        )
        assertEquals(false, status.restoreInProgress)
        assertEquals(false, status.rehydrated)

        // 2. Mark a restore in progress.
        val deferred = WorldManager.beginRestore(userId)
        status = GameRestoreRpcHandlers.restoreStatus(
            RpcCallContext(connectionId = "test-rs-conn-${System.nanoTime()}", sender = { _ -> }),
            org.ttt.autogenesis.network.RestoreStatusRequest(userId = userId)
        )
        assertEquals(true, status.restoreInProgress)
        assertEquals(false, status.rehydrated)

        // 3. Complete the restore and mark the world as rehydrated.
        WorldManager.markRehydratedFromSnapshot(userId)
        deferred.complete(Unit)
        WorldManager.endRestore(userId)

        status = GameRestoreRpcHandlers.restoreStatus(
            RpcCallContext(connectionId = "test-rs-conn-${System.nanoTime()}", sender = { _ -> }),
            org.ttt.autogenesis.network.RestoreStatusRequest(userId = userId)
        )
        assertEquals(false, status.restoreInProgress, "after endRestore, in-progress must be false")
        assertEquals(true, status.rehydrated, "the rehydrated flag must still be set after the restore finishes")
    }
}
