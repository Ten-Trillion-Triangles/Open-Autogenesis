package gameState

import org.junit.After
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TDD coverage for the Simulation Mode flag trio on [WorldManager]:
 *   - [WorldManager.isSimulationMode]
 *   - [WorldManager.simulationHumanPlayerNames]
 *   - [WorldManager.isSimulationHumanOwned]
 *
 * Task 2 of the Simulation Mode plan (.hermes/plans/2026-08-06_simulation-mode.md).
 * The predicate is called by TurnHarness (Task 5) and PromptManager (Task 6) to
 * gate AI takeover and the turn timer for human-owned slots in simulation mode.
 *
 * Tests set the new fields directly on the singleton WorldManager and reset them
 * in @After so they do not leak into other tests that share the same JVM-scoped
 * object (matching the existing PromptManagerTest pattern of mutating
 * WorldManager fields directly without a dedicated reset helper).
 */
class WorldManagerSimulationFlagTest
{

    @After
    fun teardown()
    {
        // Leave the singleton clean for any later test that touches these flags.
        WorldManager.isSimulationMode = false
        WorldManager.simulationHumanPlayerNames = emptyList()
    }

    @Test
    fun `isSimulationHumanOwned returns true for human slots and false for AI fill`()
    {
        WorldManager.isSimulationMode = true
        WorldManager.simulationHumanPlayerNames = listOf("Alpha", "Beta")

        assertTrue(WorldManager.isSimulationHumanOwned("Alpha"))
        assertTrue(WorldManager.isSimulationHumanOwned("Beta"))
        assertFalse(WorldManager.isSimulationHumanOwned("Gamma"))
    }

    @Test
    fun `isSimulationHumanOwned returns false when simulation mode is disabled`()
    {
        // Predicate must be gated on isSimulationMode — the name list alone is
        // not enough to claim a slot as "human-owned". Without the gate, a
        // misconfigured caller (e.g. leftover names from a prior game) could
        // accidentally shield an AI slot from takeover in a non-simulation game.
        WorldManager.isSimulationMode = false
        WorldManager.simulationHumanPlayerNames = listOf("Alpha")

        assertFalse(WorldManager.isSimulationHumanOwned("Alpha"))
    }
}
