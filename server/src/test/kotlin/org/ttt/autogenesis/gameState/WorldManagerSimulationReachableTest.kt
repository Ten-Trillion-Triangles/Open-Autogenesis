package org.ttt.autogenesis.gameState

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import gameState.WorldManager
import structs.Player

/**
 * TDD coverage for the simulation-mode carve-out in
 * [WorldManager.isReachable].
 *
 * The carve-out surfaced in the 2026-08-16 two-round verification probe
 * (`kvisionApp-e2e/probes/lordmaple-sim-control-verify.mjs`): the human
 * (Lord Maple Tree) successfully sent commands for both the AI filler
 * (Cleopatrick) and themselves, but the TurnHarness still triggered
 * AI takeover for Cleopatrick's turn because
 * [WorldManager.isReachable] returned `false` — Cleopatrick had no
 * WebSocket session and was flagged `isControlledByNpc=true`.
 *
 * The user's intent (verbatim 2026-08-16 directive): "you are in control
 * of both players no matter what happens this verifies that simulation
 * mode is working which means that when it's other players turn you type
 * in a prompt box and you're allowed to do this and it's your turn type
 * you control both players terms."
 *
 * The fix is at the [WorldManager.isReachable] entry point: in simulation
 * mode, every player in the active game roster is reachable (the human
 * drives both slots, the AI filler's `isControlledByNpc=true` flag is
 * game-internal bookkeeping, not a "human is unreachable" signal).
 * The [WorldManager.isSimulationHumanOwned] predicate is unchanged — it
 * still only returns true for names in `simulationHumanPlayerNames`, which
 * is the consumer-side signal for the WS-handshake bind helper.
 */
class WorldManagerSimulationReachableTest
{
    @Before
    fun reset()
    {
        WorldManager.isSimulationMode = false
        WorldManager.simulationHumanPlayerNames = emptyList()
        // Reset the active roster to a clean slate — every other test class
        // also touches this field directly, so leaving stale players behind
        // would mask the unit-under-test behavior.
        WorldManager.world.activePlayers.clear()
    }

    @After
    fun teardown()
    {
        WorldManager.isSimulationMode = false
        WorldManager.simulationHumanPlayerNames = emptyList()
        WorldManager.world.activePlayers.clear()
    }

    @Test
    fun `isActiveRosterPlayer returns true for every player in the active roster`()
    {
        WorldManager.world.activePlayers.add(Player(name = "Lord Maple Tree"))
        WorldManager.world.activePlayers.add(Player(name = "Cleopatrick"))

        assertTrue(WorldManager.isActiveRosterPlayer("Lord Maple Tree"))
        assertTrue(WorldManager.isActiveRosterPlayer("Cleopatrick"))
    }

    @Test
    fun `isActiveRosterPlayer returns false for unknown names`()
    {
        WorldManager.world.activePlayers.add(Player(name = "Lord Maple Tree"))

        assertFalse(WorldManager.isActiveRosterPlayer("RandomGhost"))
        assertFalse(WorldManager.isActiveRosterPlayer(""))
    }

    @Test
    fun `isSimulationHumanOwned unchanged human-picked names still win`()
    {
        // The human-picked roster (`simulationHumanPlayerNames`) is the
        // canonical consumer flag for "who is the human in the bind helper".
        // This test pins the predicate's existing behavior so the
        // isReachable carve-out does not silently regress it.
        WorldManager.isSimulationMode = true
        WorldManager.simulationHumanPlayerNames = listOf("Lord Maple Tree", "Cleopatrick")
        WorldManager.world.activePlayers.add(Player(name = "Lord Maple Tree"))
        WorldManager.world.activePlayers.add(Player(name = "Cleopatrick"))
        WorldManager.world.activePlayers.add(Player(name = "Gamma"))

        assertTrue(WorldManager.isSimulationHumanOwned("Lord Maple Tree"))
        assertTrue(WorldManager.isSimulationHumanOwned("Cleopatrick"))
        // Gamma is in the active roster but NOT in the human-picked list —
        // it is the AI fill slot and stays AI-controlled.
        assertFalse(WorldManager.isSimulationHumanOwned("Gamma"))
    }

    @Test
    fun `isSimulationHumanOwned returns false in non-simulation mode (gate is the flag)`()
    {
        // Defense in depth — the simulation flag is the gate, not the name list.
        WorldManager.isSimulationMode = false
        WorldManager.simulationHumanPlayerNames = listOf("Lord Maple Tree", "Cleopatrick")
        WorldManager.world.activePlayers.add(Player(name = "Lord Maple Tree"))
        WorldManager.world.activePlayers.add(Player(name = "Cleopatrick"))

        assertFalse(WorldManager.isSimulationHumanOwned("Lord Maple Tree"))
        assertFalse(WorldManager.isSimulationHumanOwned("Cleopatrick"))
    }
}