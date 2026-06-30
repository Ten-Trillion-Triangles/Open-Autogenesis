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
import kotlin.test.assertTrue

/**
 * TDD test for the hasRunningGame race-recovery path.
 *
 * Mirrors GameRestoreRpcHandlersRaceTest for restoreRunningGame: when
 * auto-restore on connect (Server.kt:295) has already applied the snapshot
 * AND the VFS record has been replaced with a consumed-sentinel, the
 * modal-check RPC must still report true so the ResumeOrNewDialog renders.
 *
 * The race window (see also MainMenu.kt:124 and the user's interactive-plan
 * spec for the push replacement):
 *   1. User reconnects, WS opens, onConnected fires.
 *   2. Auto-restore coroutine applies the snapshot to WorldManager and
 *      writes a consumed-sentinel to VFS.
 *   3. MainMenu's hasRunningGame check (or, after Phase B, the server-pushed
 *      resumeAvailable notification handler) needs the right answer.
 */
class GameRestoreRpcHandlersHasRunningGameRaceTest
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
        TurnHarness.resetState()

        savedConnectionManager = UiSignalRpcHandlers.connectionManager
        mockConnectionManager = mockk(relaxed = true)
        UiSignalRpcHandlers.connectionManager = mockConnectionManager

        try
        {
            val tempDir = java.nio.file.Files.createTempDirectory("has-running-race-test")
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

    @Test
    fun `hasRunningGame returns true when auto-restore has already applied the snapshot`() = runBlocking {
        val userId = "test-has-race-restored-${System.nanoTime()}"
        val connectionId = "test-has-race-restored-conn-${System.nanoTime()}"

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

        // Simulate Server.kt:298 firing on connect — the auto-restore
        // coroutine reads the snapshot, applies it, and writes the consumed-sentinel.
        val autoRestoreResult = TurnHarness.restoreWorldFromUserRecord(userId)
        assertTrue(autoRestoreResult.getOrDefault(false), "auto-restore should succeed in setup")
        assertFalse(WorldManager.isWorldEmpty(), "world should be non-empty after auto-restore")

        val ctx = RpcCallContext(connectionId = connectionId, sender = { _ -> })
        val exists = GameRestoreRpcHandlers.hasRunningGame(ctx)

        assertTrue(
            exists,
            "hasRunningGame must return true when auto-restore has already applied the snapshot " +
                "(the modal must still show; this is the race-recovery path)"
        )
    }

    @Test
    fun `hasRunningGame returns true when world is empty and record is preserved snapshot`() = runBlocking {
        val userId = "test-has-race-empty-${System.nanoTime()}"
        val connectionId = "test-has-race-empty-conn-${System.nanoTime()}"

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

        // Simulate auto-restore, then wipe the world (server crash after auto-restore).
        TurnHarness.restoreWorldFromUserRecord(userId)
        assertFalse(WorldManager.isWorldEmpty())
        WorldManager.world = World()
        // A real JVM crash restarts everything, so the rehydrated flag is
        // gone too. The fix removed invalidate-on-restore, so the VFS
        // still holds a parseable GameSnapshot (NOT a consumed-sentinel).
        // The rehydrated flag is more accurate than isWorldEmpty() — the
        // test must explicitly clear it to simulate the crash-and-restart
        // case where the in-process flag is gone.
        WorldManager.clearRehydratedFlag()
        assertTrue(WorldManager.isWorldEmpty(), "world must be empty after the crash sim")

        val ctx = RpcCallContext(connectionId = connectionId, sender = { _ -> })
        val exists = GameRestoreRpcHandlers.hasRunningGame(ctx)

        // With the fix, the snapshot is preserved across restore, so a
        // round-5 (round > 1) game is offered as a Resume even after
        // the in-process flag is cleared. The race-recovery branch
        // falls through to the VFS check, which now finds a valid
        // snapshot (NOT a consumed-sentinel).
        assertEquals(
            true,
            exists,
            "hasRunningGame must return true when the world is empty AND the VFS still has a valid GameSnapshot (preserved-on-restore contract)"
        )
    }
}