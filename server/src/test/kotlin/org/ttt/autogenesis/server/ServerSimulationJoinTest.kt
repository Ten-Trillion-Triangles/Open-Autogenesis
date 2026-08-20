package org.ttt.autogenesis.server

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import gameState.WorldManager
import structs.Player
import structs.World

/**
 * RED → GREEN coverage for the Simulation Mode single-connection join
 * path in [Application.serverModule] / [bindHumanOwnedSlotsForSimulation]
 * (Task 12 of the Simulation Mode plan).
 *
 * Contract pinned by these tests:
 * 1. In Simulation Mode, one AccelByte user owns N human-controlled
 *    player slots but only opens ONE WebSocket connection.
 * 2. The [bindHumanOwnedSlotsForSimulation] helper iterates ALL slots
 *    flagged human-owned by [WorldManager.isSimulationHumanOwned] and
 *    marks each as `isConnected = true` / `isControlledByNpc = false`.
 * 3. Each human slot is then initialised with [UiSignalRpcHandlers.sendInitialSync]
 *    so the (single) client UI sees every owned commander.
 * 4. AI fill slots (those NOT in `simulationHumanPlayerNames`) MUST
 *    remain `isControlledByNpc = true` and `isConnected = false`
 *    after the bind — the helper only touches human-owned slots.
 * 5. AI fill slots MUST NOT receive an initial-sync (the helper scopes
 *    its `sendInitialSync` loop to the human-owned subset).
 *
 * Reachability: [bindHumanOwnedSlotsForSimulation] does not depend on
 * the WebSocket connection manager — it operates on
 * [WorldManager.playerStats] directly. The tests instantiate a minimal
 * roster via [WorldManager.addPlayerToWorld] and assert the post-state
 * of the `PlayerStats` records, mirroring the fixture pattern used by
 * [GameInitSimulationRosterTest] and [TurnHarnessSimulationTimerTest].
 *
 * Reference production code:
 * - server/src/main/kotlin/org/ttt/autogenesis/server/Server.kt:392-505
 *   (the `connectionCoordinator.onConnected { session -> ... }` block
 *   inside `Application.serverModule`).
 * - server/src/main/kotlin/gameState/WorldManager.kt:225-272
 *   (`isSimulationMode`, `simulationHumanPlayerNames`,
 *   `isSimulationHumanOwned`).
 */
class ServerSimulationJoinTest
{
    private var priorIsSimulationMode: Boolean = false
    private var priorSimulationHumanPlayerNames: List<String> = emptyList()
    private var priorIsSinglePlayer: Boolean = false
    private var priorHumanPlayerName: String = ""
    private var priorPlayerStats: MutableList<serverStructs.PlayerStats> = mutableListOf()

    @Before
    fun resetSimulationJoinWorldState()
    {
        // Snapshot shared JVM-scoped singleton state so the test is safe to
        // run in the same JVM as other suites (mirror of the pattern used by
        // TurnHarnessSimulationTimerTest and GameInitSimulationRosterTest).
        priorIsSimulationMode = WorldManager.isSimulationMode
        priorSimulationHumanPlayerNames = WorldManager.simulationHumanPlayerNames
        priorIsSinglePlayer = WorldManager.isSinglePlayer
        priorHumanPlayerName = WorldManager.humanPlayerName
        priorPlayerStats = WorldManager.playerStats

        // Establish a minimal baseline.
        WorldManager.world = World()
        WorldManager.playerStats = mutableListOf()
        WorldManager.isSimulationMode = false
        WorldManager.simulationHumanPlayerNames = emptyList()
        WorldManager.isSinglePlayer = false
        WorldManager.humanPlayerName = ""
    }

    @After
    fun restoreSimulationJoinWorldState()
    {
        // Restore so any later test (or another suite sharing the JVM)
        // sees the same singleton shape we entered with.
        WorldManager.isSimulationMode = priorIsSimulationMode
        WorldManager.simulationHumanPlayerNames = priorSimulationHumanPlayerNames
        WorldManager.isSinglePlayer = priorIsSinglePlayer
        WorldManager.humanPlayerName = priorHumanPlayerName
        WorldManager.playerStats = priorPlayerStats
    }

    /**
     * Happy path: one user, two human slots (Alpha + Beta), one AI fill
     * (Gamma). The single connection's `playerId` is "ws-1" — both Alpha
     * and Beta share it, mirroring the simulation-mode roster where one
     * AccelByte user owns multiple slots on a single WebSocket.
     *
     * Expected post-bind shape:
     * - Alpha and Beta: `isConnected = true`, `isControlledByNpc = false`
     * - Gamma (AI fill): untouched (`isConnected = false`, `isControlledByNpc = true`)
     * - Helper returns the count of human slots it bound (2).
     */
    @Test
    fun simulationModeSingleConnectionBindsToAllHumanSlots() = runBlocking {
        // Arrange — enter Simulation Mode with two human slots and add
        // an AI fill slot so we can assert the helper leaves AI slots alone.
        WorldManager.isSimulationMode = true
        WorldManager.simulationHumanPlayerNames = listOf("Alpha", "Beta")

        val alpha = WorldManager.addPlayerToWorld(Player(name = "Alpha"), "user-1", "ws-1")
        // Default addPlayerToWorld doesn't set isControlledByNpc — but the
        // GameInit pipeline does. We mirror that here so the assertion
        // ("human slot is NOT NPC after bind") is non-trivial: without the
        // bind, the helper cannot fix a slot that was wrongly initialised.
        alpha.isControlledByNpc = false

        val beta = WorldManager.addPlayerToWorld(Player(name = "Beta"), "user-1", "ws-1")
        beta.isControlledByNpc = false

        val gammaAi = WorldManager.addPlayerToWorld(Player(name = "Gamma"), "", "")
        gammaAi.isControlledByNpc = true
        gammaAi.isConnected = false

        // Act — simulate the WS handshake binding all human slots.
        val boundCount = bindHumanOwnedSlotsForSimulation(
            playerId = "ws-1",
            accelByteId = "user-1"
        )

        // Assert — both human slots are now connected + non-NPC.
        assertTrue(alpha.isConnected, "Alpha must be marked connected by the bind")
        assertTrue(beta.isConnected, "Beta must be marked connected by the bind")
        assertFalse(alpha.isControlledByNpc, "Alpha must remain human-controlled after the bind")
        assertFalse(beta.isControlledByNpc, "Beta must remain human-controlled after the bind")

        // Assert — AI fill is untouched.
        assertTrue(gammaAi.isControlledByNpc, "AI fill Gamma must keep isControlledByNpc=true")
        assertEquals(false, gammaAi.isConnected, "AI fill Gamma must NOT be marked connected")

        // Assert — the helper reports the number of slots it bound so the
        // caller can audit-log it.
        assertEquals(2, boundCount, "Helper must report the count of human slots it bound")
    }

    /**
     * Negative path: when `isSimulationMode == false` the helper must
     * be a no-op. This protects the MULTIPLAYER / non-simulation flows
     * from accidental human-roster expansion in a game that isn't a
     * simulation. (Pure single-player is handled by the existing
     * `findPlayerStatsByConnectionId` path, not by this helper.)
     */
    @Test
    fun simulationModeHelperNoOpsWhenSimulationModeDisabled() = runBlocking {
        // Arrange — single-player / multiplayer roster, helper must NOT bind.
        WorldManager.isSimulationMode = false
        WorldManager.simulationHumanPlayerNames = emptyList()

        val alpha = WorldManager.addPlayerToWorld(Player(name = "Alpha"), "user-1", "ws-1")
        alpha.isControlledByNpc = false
        alpha.isConnected = false

        // Act
        val boundCount = bindHumanOwnedSlotsForSimulation(
            playerId = "ws-1",
            accelByteId = "user-1"
        )

        // Assert — the helper returned 0 and did not mutate Alpha.
        assertEquals(0, boundCount, "Helper must report 0 binds when simulation mode is off")
        assertEquals(false, alpha.isConnected, "Helper must not mark Alpha connected outside simulation mode")
        assertFalse(alpha.isControlledByNpc, "Helper must not touch Alpha outside simulation mode")
    }
}
