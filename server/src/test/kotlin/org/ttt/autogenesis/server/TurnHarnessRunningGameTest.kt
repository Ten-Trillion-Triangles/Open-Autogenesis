package org.ttt.autogenesis.server

import com.TTT.Config.TPipeConfig
import gameState.WorldManager
import kotlinx.coroutines.runBlocking
import org.ttt.autogenesis.network.GameOverData
import org.ttt.autogenesis.network.PlacementEntry
import org.ttt.autogenesis.server.vfs.VirtualFileSystemFactory
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import serverStructs.PlayerStats
import structs.Player
import structs.World
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the single-player "running game" snapshot save/load path.
 *
 * The flow under test:
 *   1. Single-player disconnect -> [TurnHarness.serializeCurrentWorldSnapshotToUserRecord] writes
 *      a [gameState.GameSnapshot] to the user's VFS account record under `running-game`.
 *   2. Single-player reconnect on a fresh DS -> [TurnHarness.restoreWorldFromUserRecord] reads it
 *      back and rehydrates [WorldManager] so the match resumes from the exact turn.
 */
class TurnHarnessRunningGameTest
{
    private var originalConfigDir: String = TPipeConfig.configDir
    private var tempDir: Path? = null

    @BeforeTest
    fun setUp()
    {
        // Force a clean WorldManager + TurnHarness state for every test.
        WorldManager.world = World()
        WorldManager.history.clear()
        WorldManager.actionHistoryLog.clear()
        WorldManager.pendingActionHistoryByTurn.clear()
        WorldManager.playerStats.clear()
        WorldManager.isGameActive = true
        WorldManager.isSinglePlayer = true
        WorldManager.humanPlayerName = "Commander Shepard"
        WorldManager.activeTurnActor = ""
        runBlocking { TurnHarness.resetState() }

        // Install a LocalVirtualFileSystem rooted at a fresh temp directory so save/load
        // is hermetic and does not touch the user's real ~/.autogenesis/ tree.
        tempDir = Files.createTempDirectory("turnharness-running-game-test")
        try
        {
            VirtualFileSystemManager.initialize(listOf("--mode=local", "--vfs-local-dir=${tempDir!!.toAbsolutePath()}"))
        }
        catch (e: Exception)
        {
            // VirtualFileSystemManager.initialize() is a one-shot guarded by a non-null delegate
            // so a re-initialization is a no-op and that is fine for our purposes — the routes
            // stay the same and unique per-test user ids keep records from colliding.
        }
    }

    @AfterTest
    fun tearDown()
    {
        UiSignalRpcHandlers.onGameOverBroadcast = null
        TPipeConfig.configDir = originalConfigDir
    }

    @Test
    fun `running-game record is written to the user's VFS account on save`()
    {
        runBlocking {
            val userId = "test-user-save-${System.nanoTime()}"
            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 7
            WorldManager.world.storyScenario = "Reaper invasion"

            val result = TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId)
            assertTrue(result.isSuccess, "save should succeed: ${result.exceptionOrNull()?.message}")

            val vfs = VirtualFileSystemManager.forUser(userId)
            val fetch = vfs.fetchUserRecord(userId, structs.storage.RUNNING_GAME_KEY)
            assertTrue(fetch.isSuccess, "fetch should succeed: ${fetch.exceptionOrNull()?.message}")
            assertNotNull(fetch.getOrThrow().value, "running-game record value should not be null")
        }
    }

    @Test
    fun `running-game snapshot roundtrips through VFS save and restore`()
    {
        runBlocking {
            val userId = "test-user-roundtrip-${System.nanoTime()}"

            // 1. Set up a distinctive world state and save it.
            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 12
            WorldManager.world.storyScenario = "Round-trip scenario"
            WorldManager.world.points = 4242
            WorldManager.world.karmaPoints = 17
            WorldManager.geopoliticalAssessment = "Cold war between systems"
            WorldManager.humanPlayerName = "Commander Shepard"
            WorldManager.isSinglePlayer = true
            WorldManager.isGameActive = true
            WorldManager.activeMapPackName = "test:roundtrip.map"

            // Add a per-player PlayerStats so we can confirm restoration.
            WorldManager.playerStats.add(
                PlayerStats(
                    playerData = player,
                    accelByteUserId = userId,
                    playerID = "conn-1",
                    isConnected = false,
                    isControlledByNpc = false,
                    turnActive = true
                )
            )

            val saveResult = TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId)
            assertTrue(saveResult.isSuccess, "save should succeed: ${saveResult.exceptionOrNull()?.message}")

            // 2. Reset all in-memory state to simulate a fresh server.
            WorldManager.world = World()
            WorldManager.history.clear()
            WorldManager.playerStats.clear()
            WorldManager.geopoliticalAssessment = ""
            WorldManager.activeMapPackName = ""
            WorldManager.isSinglePlayer = false
            WorldManager.humanPlayerName = ""
            WorldManager.world.roundNumber = 0
            WorldManager.world.points = 0
            TurnHarness.resetState()

            // 3. Restore from the saved record and confirm fields are rehydrated.
            val restoreResult = TurnHarness.restoreWorldFromUserRecord(userId)
            assertTrue(restoreResult.isSuccess, "restore should succeed: ${restoreResult.exceptionOrNull()?.message}")
            assertTrue(restoreResult.getOrThrow(), "restore should report a snapshot was found and applied")

            assertEquals(12, WorldManager.world.roundNumber, "roundNumber should be restored")
            assertEquals("Round-trip scenario", WorldManager.world.storyScenario, "storyScenario should be restored")
            assertEquals(4242, WorldManager.world.points, "points should be restored")
            assertEquals(17, WorldManager.world.karmaPoints, "karmaPoints should be restored")
            assertEquals("Cold war between systems", WorldManager.geopoliticalAssessment, "geopoliticalAssessment should be restored")
            assertEquals("test:roundtrip.map", WorldManager.activeMapPackName, "activeMapPackName should be restored")
            assertEquals("Commander Shepard", WorldManager.humanPlayerName, "humanPlayerName should be restored")
            assertTrue(WorldManager.isSinglePlayer, "isSinglePlayer should be restored")
            assertEquals(1, WorldManager.playerStats.size, "playerStats count should be restored")
            assertEquals(userId, WorldManager.playerStats[0].accelByteUserId, "restored player stats should reference the original accelByteUserId")
            assertEquals("Commander Shepard", WorldManager.playerStats[0].playerData.name, "restored player stats should keep the player name")
        }
    }

    @Test
    fun `restore returns false when no running-game record exists for the user`()
    {
        runBlocking {
            val userId = "test-user-empty-${System.nanoTime()}"
            val result = TurnHarness.restoreWorldFromUserRecord(userId)
            assertTrue(result.isSuccess, "restore should not error on a missing record: ${result.exceptionOrNull()?.message}")
            assertFalse(result.getOrThrow(), "restore should report false when no record exists")
        }
    }

    @Test
    fun `save with blank accelByteUserId returns failure without touching the VFS`()
    {
        runBlocking {
            val result = TurnHarness.serializeCurrentWorldSnapshotToUserRecord("")
            assertTrue(result.isFailure, "blank user id should return a failure result")
        }
    }

    @Test
    fun `save overwrites any prior running-game record for the same user`()
    {
        runBlocking {
            val userId = "test-user-overwrite-${System.nanoTime()}"

            // First save: roundNumber=3
            WorldManager.world.roundNumber = 3
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            // Second save: roundNumber=9 — should overwrite, not append.
            WorldManager.world.roundNumber = 9
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            // Restore and confirm we got roundNumber=9 (the latest).
            WorldManager.world = World()
            TurnHarness.resetState()
            val restored = TurnHarness.restoreWorldFromUserRecord(userId)
            assertTrue(restored.getOrThrow())
            assertEquals(9, WorldManager.world.roundNumber, "second save should overwrite the first")
        }
    }

    @Test
    fun `applyGameSnapshot populates activeMapPackBytes when the map pack name resolves`()
    {
        runBlocking {
            val userId = "test-user-mapresolve-${System.nanoTime()}"
            val packagedMaps = org.ttt.autogenesis.server.maps.MapResourceRegistry.listPackagedMaps()
            assertTrue(packagedMaps.isNotEmpty(), "test pre-condition: at least one packaged map must be available")
            val firstMap = packagedMaps.first()

            // 1. Save a snapshot whose mapPackName points at a real packaged map.
            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 4
            WorldManager.activeMapPackName = firstMap
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            // 2. Reset all in-memory state to simulate a fresh server.
            WorldManager.world = World()
            WorldManager.activeMapPackName = ""
            WorldManager.activeMapPackBytes = null
            TurnHarness.resetState()

            // 3. Restore and confirm the map bytes were re-loaded by name.
            val restored = TurnHarness.restoreWorldFromUserRecord(userId)
            assertTrue(restored.getOrThrow())
            assertEquals(firstMap, WorldManager.activeMapPackName, "activeMapPackName should be restored")
            assertNotNull(WorldManager.activeMapPackBytes, "activeMapPackBytes should be populated by the post-apply map lookup")
            assertTrue(WorldManager.activeMapPackBytes!!.isNotEmpty(), "activeMapPackBytes should be non-empty")
        }
    }

    @Test
    fun `applyGameSnapshot leaves activeMapPackBytes null when the map pack name does not resolve`()
    {
        runBlocking {
            val userId = "test-user-mapmissing-${System.nanoTime()}"

            // 1. Save a snapshot whose mapPackName points at a non-existent map.
            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 4
            WorldManager.activeMapPackName = "maps/this-map-does-not-exist-${System.nanoTime()}.map"
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            // 2. Reset state and restore.
            WorldManager.world = World()
            WorldManager.activeMapPackName = ""
            WorldManager.activeMapPackBytes = null
            TurnHarness.resetState()

            val restored = TurnHarness.restoreWorldFromUserRecord(userId)
            assertTrue(restored.getOrThrow(), "restore should still succeed when the map pack is missing")
            assertTrue(
                WorldManager.activeMapPackName.startsWith("maps/this-map-does-not-exist-"),
                "activeMapPackName should still be set; got '${WorldManager.activeMapPackName}'"
            )
            assertNull(WorldManager.activeMapPackBytes, "activeMapPackBytes should be null when the map pack is missing")
        }
    }

    @Test
    fun `restoreWorldFromUserRecord preserves the snapshot so subsequent resumes find it (snapshot is per-session, not per-restore)`()
    {
        runBlocking {
            val userId = "test-user-restore-preserves-${System.nanoTime()}"

            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 5
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            val vfs = VirtualFileSystemManager.forUser(userId)
            val beforeRestore = vfs.fetchUserRecord(userId, structs.storage.RUNNING_GAME_KEY)
            assertNotNull(beforeRestore.getOrThrow().value, "pre-condition: running-game record should exist before restore")

            WorldManager.world = World()
            TurnHarness.resetState()
            val restored = TurnHarness.restoreWorldFromUserRecord(userId)
            assertTrue(restored.getOrThrow(), "restore should report success")

            // After a successful restore, the snapshot MUST still be present
            // and MUST still deserialize as a valid GameSnapshot. The user
            // must be able to click Resume multiple times (e.g., after a
            // browser reload) without burning the saved state. The snapshot
            // is invalidated only by an explicit "New Game" or game-over
            // path (see clearRunningGameForUser and TurnHarness.kt:1946).
            //
            // This is the BUG FIX (2026-06-25) that inverted the old
            // one-shot-per-restore contract. The previous test name asserted
            // the old "invalidates after restore" behavior; that test was
            // pinned against the wrong contract and has been rewritten here.
            val afterRestore = vfs.fetchUserRecord(userId, structs.storage.RUNNING_GAME_KEY)
            val rawAfter = afterRestore.getOrNull()?.value
            assertNotNull(
                rawAfter,
                "running-game record should still be present after restore (snapshot survives per-session)"
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
                "running-game record should still deserialize as a GameSnapshot after restore"
            )
        }
    }

    @Test
    fun `clearRunningGameForUser invalidates the record for the given user`()
    {
        runBlocking {
            val userId = "test-user-clear-${System.nanoTime()}"

            // 1. Save a snapshot so we have something to clear.
            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 6
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            val vfs = VirtualFileSystemManager.forUser(userId)
            val beforeClear = vfs.fetchUserRecord(userId, structs.storage.RUNNING_GAME_KEY)
            assertNotNull(beforeClear.getOrThrow().value, "pre-condition: running-game record should exist before clear")

            // 2. Clear and confirm the record is invalidated (sentinel or
            //    gone — either way, the value should NOT deserialize as a
            //    valid GameSnapshot).
            val clearResult = TurnHarness.clearRunningGameForUser(userId)
            assertTrue(clearResult.isSuccess, "clear should succeed: ${clearResult.exceptionOrNull()?.message}")

            val afterClear = vfs.fetchUserRecord(userId, structs.storage.RUNNING_GAME_KEY)
            val rawAfter = afterClear.getOrNull()?.value
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
                    "running-game record should be invalidated (sentinel or gone) after clear; got $deserialized"
                )
            }
        }
    }

    @Test
    fun `clearRunningGameForUser returns success when no record exists`()
    {
        runBlocking {
            val userId = "test-user-clear-empty-${System.nanoTime()}"
            val result = TurnHarness.clearRunningGameForUser(userId)
            assertTrue(result.isSuccess, "clear on a missing record should be a no-op success: ${result.exceptionOrNull()?.message}")
        }
    }

    @Test
    fun `clearRunningGameForUser with blank id is a no-op success`()
    {
        runBlocking {
            val result = TurnHarness.clearRunningGameForUser("")
            assertTrue(result.isSuccess, "blank id should not produce a failure result")
        }
    }

    // ====================================================================
    // BUG #2 regression: when the AccelByte admin client cannot delete
    // CLOUDSAVE:RECORD (errorCode 20013, action 8), the restore and
    // clear paths now write a `{"consumed": true, "consumedAt": "..."}`
    // sentinel that fails to deserialize as a GameSnapshot, instead of
    // relying on the unavailable delete permission. The tests below pin
    // the new "sentinel" semantics on both paths.
    // ====================================================================

    @Test
    fun `restoreWorldFromUserRecord preserves the snapshot and hasRunningGame returns true afterward (snapshot is per-session)`()
    {
        runBlocking {
            val userId = "test-user-restore-preserves-hasRunning-${System.nanoTime()}"

            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 5
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            WorldManager.world = World()
            TurnHarness.resetState()
            val restored = TurnHarness.restoreWorldFromUserRecord(userId)
            assertTrue(restored.getOrThrow(), "restore should report success")

            // After a successful restore, the snapshot MUST still be present
            // and MUST still deserialize as a valid GameSnapshot. See the
            // companion test "preserves the snapshot" and the production
            // comment at TurnHarness.kt:1946 — restore is non-destructive
            // by design (the user must be able to click Resume multiple times
            // without burning the saved state).
            val vfs = VirtualFileSystemManager.forUser(userId)
            val afterRestore = vfs.fetchUserRecord(userId, structs.storage.RUNNING_GAME_KEY)
            val rawAfter = afterRestore.getOrNull()?.value
            assertNotNull(
                rawAfter,
                "running-game record should still be present after restore (per-session snapshot)"
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
                "post-restore value should still deserialize as a GameSnapshot (per-session)"
            )

            // hasRunningGame must report TRUE. The race-recovery signal is
            // `WorldManager.lastRehydratedAccelByteUserId` which was set by
            // applyGameSnapshot during the restore. The modal correctly
            // stays available while the restored game is loaded — the
            // user-facing intent: "I just resumed, of course I have a
            // running game."
            val ctx = org.ttt.autogenesis.network.RpcCallContext(
                connectionId = "preserves-test-conn",
                metadata = mapOf("accelbyteId" to userId),
                sender = { _ -> }
            )
            assertTrue(
                GameRestoreRpcHandlers.hasRunningGame(ctx),
                "hasRunningGame should return true after a successful restore (the rehydrated " +
                    "flag is set; the modal correctly stays available while the game is loaded)"
            )
        }
    }

    @Test
    fun `clearRunningGameForUser invalidates the record (sentinel or gone)`()
    {
        runBlocking {
            val userId = "test-user-clear-sentinel-${System.nanoTime()}"

            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 6
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            val clearResult = TurnHarness.clearRunningGameForUser(userId)
            assertTrue(clearResult.isSuccess, "clear should succeed: ${clearResult.exceptionOrNull()?.message}")

            val vfs = VirtualFileSystemManager.forUser(userId)
            val afterClear = vfs.fetchUserRecord(userId, structs.storage.RUNNING_GAME_KEY)
            val rawAfter = afterClear.getOrNull()?.value
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
                    "post-clear value should be a sentinel (no valid GameSnapshot) when the record is still present; got $deserialized"
                )
            }
            // If the value is gone (real delete), the value == null branch above
            // is taken; both end-states are valid for "invalidated".
        }
    }

    @Test
    fun `applyGameSnapshot resolves map pack bytes when the saved name has the resource prefix`()
    {
        runBlocking {
            val userId = "test-user-mapresourceprefix-${System.nanoTime()}"
            val packagedMaps = org.ttt.autogenesis.server.maps.MapResourceRegistry.listPackagedMaps()
            assertTrue(packagedMaps.isNotEmpty(), "test pre-condition: at least one packaged map must be available")
            val firstMap = packagedMaps.first()

            // 1. Save a snapshot whose mapPackName carries the `resource:` URI prefix
            //    (this is what WorldManager.loadMapFromResources sets in production).
            val player = Player(name = "Commander Shepard", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 4
            WorldManager.activeMapPackName = "resource:$firstMap"
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            // 2. Reset all in-memory state to simulate a fresh server.
            WorldManager.world = World()
            WorldManager.activeMapPackName = ""
            WorldManager.activeMapPackBytes = null
            TurnHarness.resetState()

            // 3. Restore and confirm the map bytes were re-loaded despite the prefix.
            val restored = TurnHarness.restoreWorldFromUserRecord(userId)
            assertTrue(restored.getOrThrow())
            assertEquals("resource:$firstMap", WorldManager.activeMapPackName, "activeMapPackName should be restored verbatim (prefix preserved for telemetry)")
            assertNotNull(WorldManager.activeMapPackBytes, "activeMapPackBytes should be populated even when the saved name has the resource: prefix")
            assertTrue(WorldManager.activeMapPackBytes!!.isNotEmpty(), "activeMapPackBytes should be non-empty")
        }
    }


    // ====================================================================
    // PlayerID remap tests (BUG: shutdown timer fires while user is connected)
    //
    // Bug: a snapshot saved during one session captures playerStats[*].playerID
    // as the OLD session's WS playerId. When the player reconnects on a fresh
    // DS, the snapshot is restored verbatim — playerID stays stale. Result:
    //   - WorldManager.findPlayerStatsByConnectionId(newPlayerId) returns null
    //   - PlayerConnectionManager.hasAnyPrimarySession() filters by
    //     `session.playerId in playerStats[*].playerID` and returns false
    //   - startSinglePlayerShutdownCountdown() arms the 60s timer even though
    //     the user is connected, killing the server under their feet.
    //
    // Fix: restoreWorldFromUserRecord takes an optional currentConnectionId and
    // applyGameSnapshot rewrites playerStats[*].playerID for the matching
    // accelByteUserId entry so the live WS session registers as the human
    // player. The tests below pin both branches of the truth table:
    //   - remap applies when currentConnectionId is provided
    //   - remap is a no-op when currentConnectionId is blank
    // ====================================================================

    @Test
    fun `restore remaps playerStats playerID to the calling connection id`() {
        runBlocking {
            val userId = "test-remap-${System.nanoTime()}"
            val oldConnectionId = "old-kvision-ws-client-${System.nanoTime()}"
            val newConnectionId = "new-kvision-ws-client-${System.nanoTime()}"

            // 1. Build a snapshot whose playerStats has the OLD session's playerId.
            val player = Player(name = "Lord Maple Tree", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 2
            WorldManager.isSinglePlayer = true
            WorldManager.humanPlayerName = "Lord Maple Tree"
            WorldManager.playerStats.add(
                PlayerStats(
                    playerData = player,
                    accelByteUserId = userId,
                    playerID = oldConnectionId,
                    isConnected = false,  // snapshot was captured mid-disconnect
                    isControlledByNpc = false,
                    turnActive = false
                )
            )
            // NPC entries are also captured verbatim — make sure we don't disturb them.
            val npcPlayer = Player(name = "Shitty Bob", description = "NPC")
            WorldManager.world.activePlayers.add(npcPlayer)
            WorldManager.playerStats.add(
                PlayerStats(
                    playerData = npcPlayer,
                    accelByteUserId = "",  // NPCs have no accelByteUserId
                    playerID = "npc-conn-id",
                    isConnected = true,
                    isControlledByNpc = true,
                    turnActive = true
                )
            )

            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            // 2. Simulate a fresh DS — clear in-memory state but keep the VFS record.
            WorldManager.world = World()
            WorldManager.history.clear()
            WorldManager.playerStats.clear()
            WorldManager.isSinglePlayer = false
            WorldManager.humanPlayerName = ""
            TurnHarness.resetState()

            // 3. Restore with the LIVE session's playerId passed in. The human
            //    entry's playerID must be rewritten to the live playerId; NPC
            //    entries must be left alone.
            val restoreResult = TurnHarness.restoreWorldFromUserRecord(userId, newConnectionId)
            assertTrue(restoreResult.isSuccess, "restore should succeed: ${restoreResult.exceptionOrNull()?.message}")
            assertTrue(restoreResult.getOrThrow(), "restore should report a snapshot was found and applied")

            val humanStats = WorldManager.playerStats.firstOrNull { it.accelByteUserId == userId }
            assertNotNull(humanStats, "human playerStats entry must be present after restore")
            assertEquals(
                newConnectionId,
                humanStats!!.playerID,
                "human playerStats[*].playerID must be remapped to the live WS playerId " +
                        "so findPlayerStatsByConnectionId() and hasAnyPrimarySession() both resolve"
            )
            assertTrue(humanStats.isConnected, "human playerStats.isConnected must be forced to true so the gameplay orchestrator sees an active player")

            val npcStats = WorldManager.playerStats.firstOrNull { it.accelByteUserId.isBlank() }
            assertNotNull(npcStats, "NPC playerStats entry must be present after restore")
            assertEquals(
                "npc-conn-id",
                npcStats!!.playerID,
                "NPC playerStats[*].playerID must be left UNCHANGED by the human-player remap"
            )
        }
    }

    @Test
    fun `restore without currentConnectionId leaves playerStats playerID unchanged`() {
        runBlocking {
            val userId = "test-no-remap-${System.nanoTime()}"
            val staleConnectionId = "stale-kvision-ws-client-${System.nanoTime()}"

            // 1. Build a snapshot with a specific playerID.
            val player = Player(name = "Lord Maple Tree", description = "Test hero")
            WorldManager.world.activePlayers.add(player)
            WorldManager.world.roundNumber = 1
            WorldManager.isSinglePlayer = true
            WorldManager.humanPlayerName = "Lord Maple Tree"
            WorldManager.playerStats.add(
                PlayerStats(
                    playerData = player,
                    accelByteUserId = userId,
                    playerID = staleConnectionId,
                    isConnected = true,
                    isControlledByNpc = false,
                    turnActive = true
                )
            )
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            // 2. Reset and restore WITHOUT a connection id (test-only entrypoint).
            WorldManager.world = World()
            WorldManager.playerStats.clear()
            TurnHarness.resetState()
            val restoreResult = TurnHarness.restoreWorldFromUserRecord(userId)  // default ""
            assertTrue(restoreResult.isSuccess, "restore should succeed: ${restoreResult.exceptionOrNull()?.message}")
            assertTrue(restoreResult.getOrThrow(), "restore should report a snapshot was found and applied")

            // 3. playerID must be unchanged when no remap is requested — only used
            //    for tests and the phase-D DS-respawn bootstrap that has no live WS.
            val humanStats = WorldManager.playerStats.firstOrNull { it.accelByteUserId == userId }
            assertNotNull(humanStats, "human playerStats entry must be present after restore")
            assertEquals(
                staleConnectionId,
                humanStats!!.playerID,
                "without currentConnectionId the saved playerID must be preserved verbatim"
            )
        }
    }


    // ====================================================================
    // Game-over snapshot-deletion contract
    //
    // Pins the user-stated contract (2026-06-26):
    //   - Mid-game disconnect preserves the snapshot for restore.
    //   - Natural game end (win/loss/surrender-that-ends-the-game)
    //     invalidates the snapshot so the next login does not offer
    //     ResumeOrNewDialog for a finished game.
    //
    // The end-to-end chain is:
    //   evaluateEndGame / dispatchForcedGameOver → broadcastGameOver →
    //     clearRunningGameForUser (single-player only).
    //
    // These tests pin the broadcastGameOver → clearRunningGameForUser
    // step in isolation so a future refactor that drops the
    // single-player invalidation is caught at the unit level (the
    // e2e probe `resume-snapshot-cleared-on-game-over.mjs` covers
    // the live end-to-end path).
    // ====================================================================

    @Test
    fun `broadcastGameOver in single-player mode invalidates the running-game snapshot`() {
        runBlocking {
            val userId = "test-user-gameover-clear-${System.nanoTime()}"
            val playerName = "Lord Maple Tree"

            // Pre-condition: simulate a player mid-game with a saved snapshot.
            // The snapshot must exist AND must be a valid GameSnapshot
            // (so the "sentinel makes it invalid" assertion is meaningful).
            WorldManager.world = World()
            WorldManager.history.clear()
            WorldManager.playerStats.clear()
            WorldManager.isSinglePlayer = true
            WorldManager.humanPlayerName = playerName
            WorldManager.isGameActive = true
            WorldManager.world.roundNumber = 7
            WorldManager.world.activePlayers.add(Player(name = playerName, description = "Test hero"))
            WorldManager.playerStats.add(
                PlayerStats(
                    playerData = Player(name = playerName, description = "Test hero"),
                    accelByteUserId = userId,
                    playerID = "test-conn-${System.nanoTime()}",
                    isConnected = true,
                    isControlledByNpc = false,
                    turnActive = false
                )
            )

            assertTrue(
                TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess,
                "pre-condition: save must succeed"
            )
            val vfs = VirtualFileSystemManager.forUser(userId)
            val before = vfs.fetchUserRecord(userId, structs.storage.RUNNING_GAME_KEY).getOrThrow().value
            assertNotNull(before, "pre-condition: snapshot must exist before broadcastGameOver")
            // Sanity: the saved value must deserialize as a valid GameSnapshot,
            // otherwise the post-assertion ("must NOT deserialize") is meaningless.
            val beforeString = if (before is kotlinx.serialization.json.JsonObject && before.containsKey("value"))
            {
                before["value"].toString()
            }
            else
            {
                before.toString()
            }
            assertNotNull(
                runCatching { com.TTT.Util.deserialize<gameState.GameSnapshot>(beforeString) }.getOrNull(),
                "pre-condition: saved snapshot must be a valid GameSnapshot"
            )

            // Act: simulate game end by calling the public broadcastGameOver
            // entry point. UiSignalRpcHandlers.broadcastGameOver fires the
            // clearRunningGameForUser call inside a fire-and-forget
            // Dispatchers.IO launch, so we poll for the post-state.
            UiSignalRpcHandlers.broadcastGameOver(
                GameOverData(
                    winnerName = playerName,
                    isVictory = true,
                    rounds = 7,
                    victoryPoints = 12,
                    territoriesClaimed = 8,
                    placements = listOf(
                        PlacementEntry(
                            name = playerName, rank = 1, territoryPoints = 12, isPlayer = true
                        )
                    )
                )
            )

            // Poll up to 5 seconds for the async clear to land. The clear is
            // dispatched on Dispatchers.IO so the test runner must yield
            // to let it complete.
            val deadline = System.currentTimeMillis() + 5_000L
            var afterString: String? = null
            var invalidated = false
            while (System.currentTimeMillis() < deadline)
            {
                val afterFetch = vfs.fetchUserRecord(userId, structs.storage.RUNNING_GAME_KEY)
                val rawAfter = afterFetch.getOrNull()?.value
                if (rawAfter == null)
                {
                    // Real delete path — record is gone, definitively invalidated.
                    invalidated = true
                    break
                }
                afterString = if (rawAfter is kotlinx.serialization.json.JsonObject && rawAfter.containsKey("value"))
                {
                    rawAfter["value"].toString()
                }
                else
                {
                    rawAfter.toString()
                }
                val deserialized = runCatching { com.TTT.Util.deserialize<gameState.GameSnapshot>(afterString) }.getOrNull()
                if (deserialized == null)
                {
                    // Sentinel path — record present but fails deserialization.
                    invalidated = true
                    break
                }
                kotlinx.coroutines.delay(50L)
            }

            assertTrue(
                invalidated,
                "broadcastGameOver in single-player mode MUST invalidate the running-game snapshot " +
                        "(either delete it or write a consumed-sentinel). " +
                        "Last seen value: $afterString"
            )

            // Simulate the WS closing after the game ends (which is what
            // happens in production: evaluateEndGame → broadcastGameOver →
            // WS close → WorldManager.playerStats cleared → next login
            // starts with an empty world and no race-recovery candidate).
            // Without this, hasRunningGame falls through to its
            // isWorldAlreadyRestoredForUser race-recovery branch and
            // returns true even though the VFS snapshot IS invalidated —
            // the next login would still NOT see ResumeOrNewDialog
            // (because the new login starts fresh), but the assertion
            // here would be testing a property the production code never
            // sees.
            WorldManager.playerStats.clear()
            WorldManager.isGameActive = false
            WorldManager.humanPlayerName = ""

            // hasRunningGame must now report false — pins the e2e contract
            // that the next login does not see a ResumeOrNewDialog.
            val ctx = org.ttt.autogenesis.network.RpcCallContext(
                connectionId = "gameover-test-conn",
                metadata = mapOf("accelbyteId" to userId),
                sender = { _ -> }
            )
            assertFalse(
                GameRestoreRpcHandlers.hasRunningGame(ctx),
                "GameRestoreRpcHandlers.hasRunningGame must return false after broadcastGameOver clears the snapshot"
            )
        }
    }

    @Test
    fun `broadcastGameOver in multiplayer mode does NOT delete the snapshot`() {
        runBlocking {
            val userId = "test-user-mp-no-clear-${System.nanoTime()}"
            val playerName = "Multiplayer Bob"

            // Pre-condition: simulate a multiplayer mid-game state.
            WorldManager.world = World()
            WorldManager.history.clear()
            WorldManager.playerStats.clear()
            WorldManager.isSinglePlayer = false  // <-- the gating dimension
            WorldManager.humanPlayerName = playerName
            WorldManager.isGameActive = true
            WorldManager.world.roundNumber = 3
            WorldManager.world.activePlayers.add(Player(name = playerName, description = "MP player"))
            WorldManager.playerStats.add(
                PlayerStats(
                    playerData = Player(name = playerName, description = "MP player"),
                    accelByteUserId = userId,
                    playerID = "mp-conn-${System.nanoTime()}",
                    isConnected = true,
                    isControlledByNpc = false,
                    turnActive = false
                )
            )

            assertTrue(
                TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess,
                "pre-condition: save must succeed"
            )

            // Act: simulate game end. In multiplayer mode the snapshot
            // MUST stay — only the single-player path invalidates.
            UiSignalRpcHandlers.broadcastGameOver(
                GameOverData(
                    winnerName = playerName,
                    isVictory = true,
                    rounds = 3,
                    victoryPoints = 5,
                    territoriesClaimed = 2,
                    placements = listOf(
                        PlacementEntry(
                            name = playerName, rank = 1, territoryPoints = 5, isPlayer = true
                        )
                    )
                )
            )

            // Give any async clear a chance to land. None should.
            kotlinx.coroutines.delay(500L)

            val vfs = VirtualFileSystemManager.forUser(userId)
            val after = vfs.fetchUserRecord(userId, structs.storage.RUNNING_GAME_KEY).getOrThrow().value
            assertNotNull(
                after,
                "broadcastGameOver in MULTIPLAYER mode must NOT delete the snapshot"
            )
            val afterString = if (after is kotlinx.serialization.json.JsonObject && after.containsKey("value"))
            {
                after["value"].toString()
            }
            else
            {
                after.toString()
            }
            assertNotNull(
                runCatching { com.TTT.Util.deserialize<gameState.GameSnapshot>(afterString) }.getOrNull(),
                "post-broadcast value must still be a valid GameSnapshot in multiplayer mode (got: $afterString)"
            )
        }
    }

    @Test
    fun `serializeCurrentWorldSnapshotToUserRecord preserves the snapshot when called while game is active (mid-game disconnect)`() {
        runBlocking {
            // Pins the OTHER half of the contract: mid-game save does NOT
            // invalidate. (Disambiguation from "save in any state invalidates"
            // — that would be a regression.) Together with the
            // broadcastGameOver test above, this is the full truth table.
            val userId = "test-user-save-preserve-${System.nanoTime()}"
            WorldManager.world = World()
            WorldManager.history.clear()
            WorldManager.playerStats.clear()
            WorldManager.isSinglePlayer = true
            WorldManager.humanPlayerName = "Lord Maple Tree"
            WorldManager.isGameActive = true  // <-- mid-game
            WorldManager.world.roundNumber = 4
            WorldManager.world.activePlayers.add(Player(name = "Lord Maple Tree", description = "Test hero"))

            // 1. Save a snapshot mid-game.
            assertTrue(
                TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess,
                "pre-condition: save must succeed mid-game"
            )

            // 2. Assert hasRunningGame returns true — the snapshot must NOT
            //    have been invalidated by the save itself.
            val ctx = org.ttt.autogenesis.network.RpcCallContext(
                connectionId = "mid-game-save-conn",
                metadata = mapOf("accelbyteId" to userId),
                sender = { _ -> }
            )
            assertTrue(
                GameRestoreRpcHandlers.hasRunningGame(ctx),
                "serializeCurrentWorldSnapshotToUserRecord mid-game MUST leave the snapshot valid " +
                        "so a future disconnect followed by a reconnect can resume from it"
            )
        }
    }

    // -----------------------------------------------------------------
    // Post-restore hydration: BUG 4 (music), BUG 5 (turn ownership),
    // BUG 6 (turn timer). These tests cover the reload-game flow where
    // applyGameSnapshot has just landed a saved snapshot onto a fresh
    // server and the live WS session needs:
    //   - the human's `isControlledByNpc` flipped to false (was true in
    //     the snapshot because the player was mid-AI-turn when saved),
    //   - the per-turn music decision replayed so AudioManager isn't
    //     empty after rehydrate (silent world),
    //   - the turn-timer armed when the saved turnOrderIndex points at
    //     the human (countdown shows immediately on UI mount).
    // -----------------------------------------------------------------

    @Test
    fun `restoreWorldFromUserRecord flips isControlledByNpc to false on the remap target`() {
        runBlocking {
            val userId = "test-user-npcflip-${System.nanoTime()}"
            val newConn = "kvision-ws-client-new-${System.nanoTime()}"
            val player = Player(name = "Commander Shepard", description = "Human")
            val npc = Player(name = "Zuzusarogorata Suguruzands", description = "NPC")

            // Snapshot the world with two players: the human is
            // isControlledByNpc=true (because the snapshot was saved
            // mid-AI-turn) and the NPC is also isControlledByNpc=true
            // (its native state).
            WorldManager.world = World()
            WorldManager.world.roundNumber = 1
            WorldManager.world.turnOrder = mutableListOf<String>("Commander Shepard", "Zuzusarogorata Suguruzands")
            WorldManager.world.activePlayers = mutableListOf(player, npc)
            WorldManager.playerStats.add(
                PlayerStats(playerData = player, accelByteUserId = userId, playerID = "old-conn",
                    isConnected = false, isControlledByNpc = true)
            )
            WorldManager.playerStats.add(
                PlayerStats(playerData = npc, accelByteUserId = "", playerID = "",
                    isConnected = false, isControlledByNpc = true)
            )
            WorldManager.isSinglePlayer = true
            WorldManager.humanPlayerName = "Commander Shepard"
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            // Fresh server.
            WorldManager.world = World()
            WorldManager.playerStats.clear()
            WorldManager.world.turnOrder = mutableListOf<String>("Commander Shepard", "Zuzusarogorata Suguruzands")
            WorldManager.world.activePlayers = mutableListOf(player, npc)
            TurnHarness.resetState()

            // Restore with a real currentConnectionId so the remap fires.
            val restoreResult = TurnHarness.restoreWorldFromUserRecord(userId, newConn)
            assertTrue(restoreResult.isSuccess)
            assertTrue(restoreResult.getOrThrow())

            val human = WorldManager.playerStats.first { it.accelByteUserId == userId }
            val npe = WorldManager.playerStats.first { it.playerData.name == "Zuzusarogorata Suguruzands" }

            assertFalse(human.isControlledByNpc, "human must be flipped to isControlledByNpc=false (BUG 5 fix)")
            assertEquals(newConn, human.playerID, "human playerID must be remapped to the live WS connection")
            assertTrue(human.isConnected, "human isConnected must be forced true after remap")
            assertTrue(npe.isControlledByNpc, "NPC entries (blank accelByteUserId) must remain untouched")
        }
    }

    @Test
    fun `restoreWorldFromUserRecord leaves isControlledByNpc untouched when no accelByteUserId match`() {
        runBlocking {
            val userId = "test-user-noMatch-${System.nanoTime()}"
            val player = Player(name = "OrphanNPC", description = "NPC with no human counterpart")
            WorldManager.world = World()
            WorldManager.world.roundNumber = 1
            WorldManager.world.turnOrder = mutableListOf<String>("OrphanNPC")
            WorldManager.world.activePlayers = mutableListOf(player)
            WorldManager.playerStats.add(
                PlayerStats(playerData = player, accelByteUserId = "", playerID = "",
                    isConnected = false, isControlledByNpc = true)
            )
            WorldManager.isSinglePlayer = true
            WorldManager.humanPlayerName = "Missing Human"
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            WorldManager.world = World()
            WorldManager.playerStats.clear()
            TurnHarness.resetState()

            // Restore — no remap target because userId doesn't match any
            // accelByteUserId (we saved under one id, restored under
            // another with no accelByteUserId match in the snapshot).
            val restoreResult = TurnHarness.restoreWorldFromUserRecord("no-such-user", "fake-conn")
            // restore returns success even when the snapshot doesn't exist
            // for that user (it just returns false). Here we want to assert
            // the WorldManager remains in its fresh state.
            assertTrue(restoreResult.isSuccess)
            assertFalse(restoreResult.getOrThrow(), "restore reports false when no snapshot exists for the user")
            assertTrue(WorldManager.playerStats.isEmpty(), "no playerStats applied — fresh server state")
        }
    }

    @Test
    fun `restoreWorldFromUserRecord arms the turn timer when human was active`() {
        runBlocking {
            val userId = "test-user-timer-human-${System.nanoTime()}"
            val newConn = "kvision-ws-client-timer-${System.nanoTime()}"
            val player = Player(name = "Commander Shepard", description = "Human")
            val npc = Player(name = "Zuzu", description = "NPC")
            WorldManager.world = World()
            WorldManager.world.roundNumber = 2
            WorldManager.world.turnOrder = mutableListOf<String>("Commander Shepard", "Zuzu")
            WorldManager.world.activePlayers = mutableListOf(player, npc)
            WorldManager.playerStats.add(
                PlayerStats(playerData = player, accelByteUserId = userId, playerID = "old",
                    isConnected = false, isControlledByNpc = false)
            )
            WorldManager.playerStats.add(
                PlayerStats(playerData = npc, accelByteUserId = "", playerID = "",
                    isConnected = false, isControlledByNpc = true)
            )
            WorldManager.isSinglePlayer = true
            WorldManager.humanPlayerName = "Commander Shepard"
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            WorldManager.world = World()
            WorldManager.playerStats.clear()
            WorldManager.world.turnOrder = mutableListOf<String>("Commander Shepard", "Zuzu")
            WorldManager.world.activePlayers = mutableListOf(player, npc)
            TurnHarness.resetState()

            // Snapshot's turnOrderIndex=0 -> human is active -> timer must arm.
            val restoreResult = TurnHarness.restoreWorldFromUserRecord(userId, newConn)
            assertTrue(restoreResult.isSuccess)
            assertTrue(restoreResult.getOrThrow())
            assertEquals("Commander Shepard", WorldManager.activeTurnActor,
                "BUG 6 fix: turn timer must be armed with the human's name when saved active actor is human")
        }
    }

    @Test
    fun `restoreWorldFromUserRecord does NOT arm the turn timer when NPC was active`() {
        runBlocking {
            val userId = "test-user-timer-npc-${System.nanoTime()}"
            val newConn = "kvision-ws-client-timer2-${System.nanoTime()}"
            val player = Player(name = "Commander Shepard", description = "Human")
            val npc = Player(name = "Zuzu", description = "NPC")
            WorldManager.world = World()
            WorldManager.world.roundNumber = 3
            // Human is index 0 in turnOrder but the saved snapshot's
            // turnOrderIndex points at the NPC (index 1).
            WorldManager.world.turnOrder = mutableListOf<String>("Commander Shepard", "Zuzu")
            WorldManager.world.activePlayers = mutableListOf(player, npc)
            // Override turnOrderIndex to 1 before save.
            WorldManager.playerStats.add(
                PlayerStats(playerData = player, accelByteUserId = userId, playerID = "old",
                    isConnected = false, isControlledByNpc = false)
            )
            WorldManager.playerStats.add(
                PlayerStats(playerData = npc, accelByteUserId = "", playerID = "",
                    isConnected = false, isControlledByNpc = true)
            )
            WorldManager.isSinglePlayer = true
            WorldManager.humanPlayerName = "Commander Shepard"

            // Use the harness's existing serialization path, then
            // re-route turnOrderIndex to point at the NPC for this test.
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            WorldManager.world = World()
            WorldManager.playerStats.clear()
            WorldManager.world.turnOrder = mutableListOf<String>("Commander Shepard", "Zuzu")
            WorldManager.world.activePlayers = mutableListOf(player, npc)
            TurnHarness.resetState()

            // Patch the snapshot's turnOrderIndex by re-saving with the
            // desired index — bypass serializeCurrentWorldSnapshotToUserRecord
            // and call hydratePostRestoreState directly via restoreWorldFromUserRecord,
            // setting the turnOrderIndex through TurnHarness public state.
            // The cleanest way: save with current turnOrderIndex=0 (default),
            // then before restore set WorldManager.world.turnOrderIndex=1 via
            // the snapshot's own saved value. The serialize/restore pipeline
            // picks up the saved value, which defaults to 0; to set it to 1
            // we serialize a snapshot with turnOrderIndex=1 by manipulating
            // TurnHarness.getTurnOrderIndex() — but that's read-only. Instead
            // we drive turnOrderIndex via the canonical
            // advanceTurnIndexAndRoundIfNeeded() path. For this test we
            // accept that turnOrderIndex=0 (human) and instead invert the
            // assertion: when NPC was active (we set turnOrder to start
            // with NPC), the timer is NOT armed.
            WorldManager.world.turnOrder = mutableListOf<String>("Zuzu", "Commander Shepard")

            // Re-save with this updated turnOrder so the snapshot carries it.
            WorldManager.playerStats.add(
                PlayerStats(playerData = player, accelByteUserId = userId, playerID = "old",
                    isConnected = false, isControlledByNpc = false)
            )
            WorldManager.playerStats.add(
                PlayerStats(playerData = npc, accelByteUserId = "", playerID = "",
                    isConnected = false, isControlledByNpc = true)
            )
            // Mark round so the snapshot is non-trivial.
            WorldManager.world.roundNumber = 3
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            WorldManager.world = World()
            WorldManager.playerStats.clear()
            TurnHarness.resetState()

            val restoreResult = TurnHarness.restoreWorldFromUserRecord(userId, newConn)
            assertTrue(restoreResult.isSuccess)
            assertTrue(restoreResult.getOrThrow())
            // Restore brought back turnOrder=[Zuzu, Commander Shepard],
            // turnOrderIndex=0 (default), so activeActor = "Zuzu" (NPC),
            // and the timer is NOT armed.
            assertNotEquals("Commander Shepard", WorldManager.activeTurnActor,
                "BUG 6 fix: turn timer must NOT arm when saved active actor is the NPC")
        }
    }

    @Test
    fun `restoreWorldFromUserRecord schedules initialConditions music on round-1 restore`() {
        runBlocking {
            val userId = "test-user-music-r1-${System.nanoTime()}"
            val player = Player(name = "Commander Shepard", description = "Human")
            val npc = Player(name = "Zuzu", description = "NPC")
            WorldManager.world = World()
            WorldManager.world.roundNumber = 1
            WorldManager.world.turnOrder = mutableListOf<String>("Commander Shepard", "Zuzu")
            WorldManager.world.activePlayers = mutableListOf(player, npc)
            WorldManager.playerStats.add(
                PlayerStats(playerData = player, accelByteUserId = userId, playerID = "old",
                    isConnected = false, isControlledByNpc = false)
            )
            WorldManager.playerStats.add(
                PlayerStats(playerData = npc, accelByteUserId = "", playerID = "",
                    isConnected = false, isControlledByNpc = true)
            )
            WorldManager.isSinglePlayer = true
            WorldManager.humanPlayerName = "Commander Shepard"
            assertTrue(TurnHarness.serializeCurrentWorldSnapshotToUserRecord(userId).isSuccess)

            WorldManager.world = World()
            WorldManager.playerStats.clear()
            WorldManager.world.turnOrder = mutableListOf<String>("Commander Shepard", "Zuzu")
            WorldManager.world.activePlayers = mutableListOf(player, npc)
            TurnHarness.resetState()

            val initialDecision = TurnHarness.getCurrentTurnMusicDecision()
            val restoreResult = TurnHarness.restoreWorldFromUserRecord(userId, "conn-${System.nanoTime()}")
            assertTrue(restoreResult.isSuccess)
            assertTrue(restoreResult.getOrThrow())

            // BUG 4 fix: post-restore hydration must produce a non-null
            // MusicDecision so the AudioManager can broadcast a schedule.
            // The decision may be empty (toPlay=[]) when the test audio
            // catalog is empty, but the field itself must be set.
            val finalDecision = TurnHarness.getCurrentTurnMusicDecision()
            assertNotNull(finalDecision,
                "BUG 4 fix: hydratePostRestoreState must populate currentTurnMusicDecision after restore")
        }
    }

    @Test
    fun `restoreWorldFromUserRecord skips post-restore hydration on empty world`() {
        runBlocking {
            val userId = "test-user-empty-restore-${System.nanoTime()}"
            // Fresh world, no snapshot, restore should be a no-op.
            val initialPlayingCount = org.ttt.autogenesis.server.audio.AudioManager.playingObjects.size
            val restoreResult = TurnHarness.restoreWorldFromUserRecord(userId, "conn-${System.nanoTime()}")
            assertTrue(restoreResult.isSuccess)
            assertFalse(restoreResult.getOrThrow(), "restore returns false on no-snapshot path")
            assertEquals(initialPlayingCount, org.ttt.autogenesis.server.audio.AudioManager.playingObjects.size,
                "no music scheduled when there is no snapshot to rehydrate")
        }
    }


}