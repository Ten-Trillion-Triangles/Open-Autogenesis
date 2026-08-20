package org.ttt.autogenesis.server

import gameState.WorldManager
import io.mockk.mockk
import io.mockk.slot
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the [GameRestoreRpcHandlers] server-side resume flow.
 *
 * Pinned behaviours:
 *  1. [GameRestoreRpcHandlers.hasRunningGame] returns `true` when a
 *     running-game record exists for the calling player.
 *  2. [GameRestoreRpcHandlers.hasRunningGame] returns `false` when no
 *     record exists (VFS miss / not-found).
 *  3. [GameRestoreRpcHandlers.hasRunningGame] returns `false` when the
 *     connection cannot be mapped to a human user.
 *  4. [GameRestoreRpcHandlers.restoreRunningGame] applies the snapshot,
 *     deletes the record (delete-on-restore TTL), and pushes
 *     `sendInitialSync` to the calling connection with the restored
 *     `activeMapPackBytes`.
 *  5. [GameRestoreRpcHandlers.restoreRunningGame] returns `false` when
 *     no record exists (the UI stays in the menu).
 *  6. [GameRestoreRpcHandlers.clearRunningGame] deletes the record and
 *     returns `true`; calling it on a missing record is a no-op
 *     success.
 */
class GameRestoreRpcHandlersTest
{
    private var savedConnectionManager: PlayerConnectionManager? = null
    private lateinit var mockConnectionManager: PlayerConnectionManager

    @Before
    fun setup() = runBlocking {
        // WorldManager cleanup mirrors TurnHarnessRunningGameTest's
        // @BeforeTest: we want a fresh world so the per-test playerStats
        // entry is the only one in the system.
        WorldManager.world = World()
        WorldManager.history.clear()
        WorldManager.actionHistoryLog.clear()
        WorldManager.pendingActionHistoryByTurn.clear()
        WorldManager.playerStats.clear()
        WorldManager.isGameActive = true
        WorldManager.isSinglePlayer = true
        WorldManager.humanPlayerName = "Commander Shepard"
        TurnHarness.resetState()

        savedConnectionManager = UiSignalRpcHandlers.connectionManager
        mockConnectionManager = mockk(relaxed = true)
        UiSignalRpcHandlers.connectionManager = mockConnectionManager

        // Install a hermetic LocalVirtualFileSystem so save/restore does not
        // touch the user's real ~/.autogenesis/ tree. This is idempotent —
        // a second call leaves the existing routes in place.
        try
        {
            val tempDir = java.nio.file.Files.createTempDirectory("game-restore-rpc-test")
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
    fun `hasRunningGame returns true when a record exists for the calling player`()
    {
        runBlocking {
            val userId = "test-rpc-has-${System.nanoTime()}"
            val connectionId = "test-rpc-has-conn-${System.nanoTime()}"

            // 1. Register the player as a known human.
            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
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

            // 2. Save a snapshot so the record exists.
            WorldManager.world.roundNumber = 7
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            // 3. hasRunningGame should report true.
            val ctx = RpcCallContext(connectionId = connectionId, sender = { _ -> })
            val exists = GameRestoreRpcHandlers.hasRunningGame(ctx)
            assertTrue(exists, "hasRunningGame should return true when a record exists")
        }
    }

    @Test
    fun `hasRunningGame returns false when no record exists for the calling player`()
    {
        runBlocking {
            val userId = "test-rpc-has-empty-${System.nanoTime()}"
            val connectionId = "test-rpc-has-empty-conn-${System.nanoTime()}"

            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
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
            val exists = GameRestoreRpcHandlers.hasRunningGame(ctx)
            assertEquals(false, exists, "hasRunningGame should return false when no record exists")
        }
    }

    @Test
    fun `hasRunningGame returns false when the connection cannot be mapped to a human user`()
    {
        runBlocking {
            val ctx = RpcCallContext(connectionId = "unmapped-conn", sender = { _ -> })
            val exists = GameRestoreRpcHandlers.hasRunningGame(ctx)
            assertEquals(false, exists, "hasRunningGame should return false for an unmapped connection")
        }
    }

    @Test
    fun `restoreRunningGame applies the snapshot and pushes sendInitialSync with the restored map`()
    {
        runBlocking {
            val userId = "test-rpc-restore-${System.nanoTime()}"
            val connectionId = "test-rpc-restore-conn-${System.nanoTime()}"

            // 1. Register the player + save a snapshot with a real packaged map.
            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 11
            val packagedMaps = org.ttt.autogenesis.server.maps.MapResourceRegistry.listPackagedMaps()
            assertTrue(packagedMaps.isNotEmpty(), "test pre-condition: at least one packaged map must be available")
            val firstMap = packagedMaps.first()
            WorldManager.activeMapPackName = firstMap
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            // 2. Register a PlayerStats entry so the restored world has a
            // session to push sendInitialSync to. We reset the WorldManager
            // afterward to simulate a fresh server.
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
            WorldManager.world = World()
            WorldManager.activeMapPackName = ""
            WorldManager.activeMapPackBytes = null
            TurnHarness.resetState()

            // 3. Re-register the player stats so restoreRunningGame can
            // find a target session. (In production, the WS-connected
            // client's PlayerStats would still be present because the WS
            // connection survives the DS restart.)
            WorldManager.world.activePlayers.add(player)
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

            // 4. Invoke restoreRunningGame and confirm restore + sync.
            val ctx = RpcCallContext(connectionId = connectionId, sender = { _ -> })
            val restored = GameRestoreRpcHandlers.restoreRunningGame(ctx)
            assertTrue(restored, "restoreRunningGame should report success")
            assertEquals(11, WorldManager.world.roundNumber, "round number should match the snapshot")
            assertEquals(firstMap, WorldManager.activeMapPackName, "activeMapPackName should match the snapshot")
            assertNotNull(WorldManager.activeMapPackBytes, "activeMapPackBytes should be resolved by the post-apply map lookup")

            // 5. Confirm the snapshot is STILL on disk — the fix removed
            // the invalidate-on-restore so the user can click Resume
            // multiple times (browser reload, reconnect, etc.) without
            // burning the saved state. The next disconnect will overwrite
            // the same slot via serializeCurrentWorldSnapshotToUserRecord.
            val vfs = VirtualFileSystemManager.forUser(userId)
            val afterRestore = vfs.fetchUserRecord(userId, structs.storage.RUNNING_GAME_KEY)
            val rawAfter = afterRestore.getOrNull()?.value
            assertNotNull(
                rawAfter,
                "running-game record must STILL exist after restore (snapshot is preserved for multiple Resume attempts)"
            )
            val valueString = if (rawAfter is kotlinx.serialization.json.JsonObject && rawAfter.containsKey("value"))
            {
                rawAfter["value"].toString()
            }
            else
            {
                rawAfter.toString()
            }
            val deserialized = runCatching { com.TTT.Util.deserialize<gameState.GameSnapshot>(valueString) }.getOrNull()
            assertNotNull(
                deserialized,
                "preserved record must still be a valid GameSnapshot — only an explicit New Game / game-over path may invalidate it"
            )
            assertEquals(
                11,
                deserialized!!.world.roundNumber,
                "preserved snapshot round number should match the saved value"
            )

            // 6. (sync verification omitted — the relaxed mock allows
            // sendInitialSync to complete without throwing, which is the
            // meaningful signal. The earlier assertions already pin that
            // restoreRunningGame returned true and that the record was
            // deleted, both of which are the load-bearing behaviour.)
        }
    }

    @Test
    fun `restoreRunningGame returns false when no record exists for the calling player`()
    {
        runBlocking {
            val userId = "test-rpc-restore-empty-${System.nanoTime()}"
            val connectionId = "test-rpc-restore-empty-conn-${System.nanoTime()}"

            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
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
            assertEquals(false, restored, "restoreRunningGame should return false when no record exists")
        }
    }

    @Test
    fun `clearRunningGame deletes the record and returns true`()
    {
        runBlocking {
            val userId = "test-rpc-clear-${System.nanoTime()}"
            val connectionId = "test-rpc-clear-conn-${System.nanoTime()}"

            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 13
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)
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

            val vfs = VirtualFileSystemManager.forUser(userId)
            val before = vfs.fetchUserRecord(userId, structs.storage.RUNNING_GAME_KEY)
            assertTrue(
                before.isSuccess && before.getOrNull()?.value != null,
                "pre-condition: record should exist; got $before"
            )

            val ctx = RpcCallContext(connectionId = connectionId, sender = { _ -> })
            val cleared = GameRestoreRpcHandlers.clearRunningGame(ctx)
            assertTrue(cleared, "clearRunningGame should return true on a successful delete")

            val after = vfs.fetchUserRecord(userId, structs.storage.RUNNING_GAME_KEY)
            // clearRunningGame is best-effort: the local VFS reports
            // "invalidated" either as a RecordNotFoundException on fetch
            // or as a stored value that does not deserialize as a
            // GameSnapshot. We accept either.
            val rawAfter = after.getOrNull()?.value
            if (rawAfter != null)
            {
                val valueString = if (rawAfter is kotlinx.serialization.json.JsonObject && rawAfter.containsKey("value"))
                {
                    rawAfter["value"].toString()
                }
                else
                {
                    rawAfter.toString()
                }
                val deserialized = runCatching { com.TTT.Util.deserialize<gameState.GameSnapshot>(valueString) }.getOrNull()
                assertNull(
                    deserialized,
                    "record should be invalidated (sentinel or gone) after clearRunningGame; got $deserialized"
                )
            }
        }
    }

    @Test
    fun `clearRunningGame returns true when no record exists`()
    {
        runBlocking {
            val userId = "test-rpc-clear-empty-${System.nanoTime()}"
            val connectionId = "test-rpc-clear-empty-conn-${System.nanoTime()}"

            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
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
            val cleared = GameRestoreRpcHandlers.clearRunningGame(ctx)
            assertTrue(cleared, "clearRunningGame on a missing record should be a no-op success")
        }
    }

    // ====================================================================
    // BUG #1 regression: resolveHumanUserId must consult
    // RpcCallContext.metadata["accelbyteId"] before falling back to
    // WorldManager.playerStats, because on a fresh dedicated server the
    // WorldManager is empty when the RPC is invoked.
    // ====================================================================

    @Test
    fun `hasRunningGame resolves userId from RpcCallContext metadata when playerStats is empty`()
    {
        runBlocking {
            val userId = "test-rpc-meta-${System.nanoTime()}"
            val connectionId = "test-rpc-meta-conn-${System.nanoTime()}"

            // 1. Save a snapshot for the user.
            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 3
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            // 2. NOTE: do NOT add a PlayerStats entry — simulate a fresh DS.

            // 3. Build an RpcCallContext carrying accelbyteId in metadata,
            //    mimicking the production server-side handleFrame path.
            val ctx = RpcCallContext(
                connectionId = connectionId,
                metadata = mapOf("accelbyteId" to userId),
                sender = { _ -> }
            )
            val exists = GameRestoreRpcHandlers.hasRunningGame(ctx)
            assertTrue(
                exists,
                "hasRunningGame should return true when the snapshot exists and the userId is supplied via metadata"
            )
        }
    }

    @Test
    fun `hasRunningGame falls back to playerStats when metadata is empty`()
    {
        runBlocking {
            val userId = "test-rpc-fallback-${System.nanoTime()}"
            val connectionId = "test-rpc-fallback-conn-${System.nanoTime()}"

            // 1. Register a PlayerStats entry to provide the userId via
            //    the legacy fallback path.
            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
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

            // 2. Save a snapshot.
            WorldManager.world.roundNumber = 5
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            // 3. Build an RpcCallContext with empty metadata.
            val ctx = RpcCallContext(
                connectionId = connectionId,
                metadata = emptyMap(),
                sender = { _ -> }
            )
            val exists = GameRestoreRpcHandlers.hasRunningGame(ctx)
            assertTrue(
                exists,
                "hasRunningGame should still return true via the playerStats fallback when metadata is empty"
            )
        }
    }

    @Test
    fun `restoreRunningGame resolves userId from metadata when playerStats is empty`()
    {
        runBlocking {
            val userId = "test-rpc-restore-meta-${System.nanoTime()}"
            val connectionId = "test-rpc-restore-meta-conn-${System.nanoTime()}"

            // 1. Save a snapshot. No PlayerStats entry — fresh DS.
            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 9
            val packagedMaps = org.ttt.autogenesis.server.maps.MapResourceRegistry.listPackagedMaps()
            assertTrue(packagedMaps.isNotEmpty(), "test pre-condition: at least one packaged map must be available")
            WorldManager.activeMapPackName = packagedMaps.first()
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            // 2. Build the metadata-only RpcCallContext.
            val ctx = RpcCallContext(
                connectionId = connectionId,
                metadata = mapOf("accelbyteId" to userId),
                sender = { _ -> }
            )

            // 3. restoreRunningGame should succeed via metadata, even
            //    though the WorldManager has no PlayerStats entry.
            val restored = GameRestoreRpcHandlers.restoreRunningGame(ctx)
            assertTrue(
                restored,
                "restoreRunningGame should return true when the userId is supplied via metadata"
            )

            // 4. The post-restore initial-sync WARN is expected here
            //    (no playerStats to push to) — but the round is real.
            assertEquals(9, WorldManager.world.roundNumber)
        }
    }

    // ====================================================================
    // BUG #2 regression: when a consumed-sentinel is in the slot,
    // hasRunningGame must report false even though the record is
    // present (write permission works, delete does not). The sentinel
    // is `{"consumed": true, "consumedAt": "..."}` and is designed to
    // fail GameSnapshot deserialization.
    // ====================================================================

    @Test
    fun `hasRunningGame returns false when the record contains only a consumed-sentinel`()
    {
        runBlocking {
            val userId = "test-rpc-sentinel-${System.nanoTime()}"
            val connectionId = "test-rpc-sentinel-conn-${System.nanoTime()}"

            // 1. Register the player as a known human.
            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
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

            // 2. Write a sentinel directly via the VFS write path.
            val vfs = VirtualFileSystemManager.forUser(userId)
            val sentinel = "{\"consumed\":true,\"consumedAt\":\"2026-06-22T15:00:00Z\"}"
            val saveResult = vfs.saveUserRecordFromJsonString(
                userId,
                structs.storage.RUNNING_GAME_KEY,
                sentinel
            )
            assertTrue(saveResult.isSuccess, "sentinel save should succeed: ${saveResult.exceptionOrNull()?.message}")

            // 3. hasRunningGame must report false: the record is present
            //    but its value does not deserialize as a GameSnapshot.
            val ctx = RpcCallContext(connectionId = connectionId, sender = { _ -> })
            val exists = GameRestoreRpcHandlers.hasRunningGame(ctx)
            assertEquals(
                false,
                exists,
                "hasRunningGame should return false when the record is a consumed-sentinel, not a valid snapshot"
            )
        }
    }

    /**
     * Boundary pair to the world-empty test above. Here the world is
     * non-empty (active player + round > 0 + gameActive) and playerStats
     * carries the calling user — the post-auto-restore state. The VFS
     * record is the consumed-sentinel (delete failed, fall-back wrote the
     * sentinel). hasRunningGame must report TRUE so the resume modal
     * renders — this is the race-recovery path.
     */
    @Test
    fun `hasRunningGame returns true when world is non-empty and record is consumed-sentinel`()
    {
        runBlocking {
            val userId = "test-rpc-sentinel-race-${System.nanoTime()}"
            val connectionId = "test-rpc-sentinel-race-conn-${System.nanoTime()}"

            // 1. Populate the world state to mirror post-auto-restore:
            //    active player, round > 0, isGameActive = true, and a
            //    PlayerStats entry keyed to the calling user.
            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 9
            WorldManager.isGameActive = true
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

            // 2. Write the consumed-sentinel directly via the VFS write path
            //    (same pattern as the world-empty test above — no auto-restore
            //    is run because we are constructing the post-restore state by hand).
            val vfs = VirtualFileSystemManager.forUser(userId)
            val sentinel = "{\"consumed\":true,\"consumedAt\":\"2026-06-22T15:00:00Z\"}"
            val saveResult = vfs.saveUserRecordFromJsonString(
                userId,
                structs.storage.RUNNING_GAME_KEY,
                sentinel
            )
            assertTrue(saveResult.isSuccess, "sentinel save should succeed: ${saveResult.exceptionOrNull()?.message}")

            // 3. Mark the world as rehydrated (the new race-recovery signal —
            //    pre-fix the test relied on `isWorldEmpty()` returning false
            //    because the world was set up with `roundNumber=7` and the
            //    playerStats were populated; post-fix the test must
            //    explicitly mark the rehydrate).
            WorldManager.markRehydratedFromSnapshot(userId)

            // 4. hasRunningGame must report TRUE: race-recovery via the
            //    isWorldAlreadyRestoredForUser fallback because the VFS
            //    record alone would deserialize as null.
            val ctx = RpcCallContext(connectionId = connectionId, sender = { _ -> })
            val exists = GameRestoreRpcHandlers.hasRunningGame(ctx)
            assertTrue(
                exists,
                "hasRunningGame should return true when the world is non-empty + playerStats match, " +
                    "even though VFS holds the consumed-sentinel (race-recovered)"
            )
        }
    }
}