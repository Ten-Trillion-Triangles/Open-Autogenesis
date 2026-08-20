package org.ttt.autogenesis.server

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import gameState.WorldManager
import structs.Player
import structs.World

/**
 * RED → GREEN coverage for the Simulation Mode turn-timer gate in
 * [TurnHarness.awaitPlayerAction].
 *
 * The contract: when the actor's name is in
 * [WorldManager.simulationHumanPlayerNames] AND [WorldManager.isSimulationMode]
 * is true, the timer that normally short-circuits `awaitPlayerAction` after
 * [WorldManager.TURN_TIMEOUT_MS] must be disabled. The function then awaits
 * indefinitely — the human must submit a real action (or the WS bridge must
 * drop / close). Without this gate, simulation-mode players would be hit with
 * AI takeover after 302s, which is the exact bug Simulation Mode exists to
 * avoid.
 *
 * Test strategy: set the debug override
 * `AUTOGENESIS_DEBUG_SHORT_TURN_TIMEOUT_MS=50` so the production timer would
 * otherwise fire inside 50ms. Wrap the call in `withTimeoutOrNull(100)`. If
 * the gate works, the inner suspend does NOT short-circuit; the outer 100ms
 * timeout wins, so we observe `elapsed >= 95ms`. If the gate is missing, the
 * inner `withTimeoutOrNull(50)` fires inside ~50ms, the function returns null
 * quickly, and `elapsed < 95ms`.
 *
 * Reachability: [WorldManager.isReachable] returns true as soon as no
 * `PlayerStats` exist for the actor (see the `stats == null` early-return
 * branch in WorldManager.kt). We exploit that early-return by NOT calling
 * `addPlayerToWorld` — that keeps the test hermetic, with no need to stub
 * the connection manager or instantiate a fake `PlayerSession`.
 */
class TurnHarnessSimulationTimerTest
{
    private var priorDebugShortProp: String? = null
    private var priorIsSimulationMode: Boolean = false
    private var priorSimulationHumanPlayerNames: List<String> = emptyList()
    private var priorHumanPlayerName: String = ""
    private var priorActiveTurnActor: String = ""

    @BeforeTest
    fun capturePriorState()
    {
        // Snapshot any prior state so the test is safe to run inside a shared
        // JVM that other suites (running on the same Gradle test JVM, in
        // particular under `--rerun-tasks` parallel variants) may also touch.
        priorDebugShortProp = System.getProperty("AUTOGENESIS_DEBUG_SHORT_TURN_TIMEOUT_MS")
        priorIsSimulationMode = WorldManager.isSimulationMode
        priorSimulationHumanPlayerNames = WorldManager.simulationHumanPlayerNames
        priorHumanPlayerName = WorldManager.humanPlayerName
        priorActiveTurnActor = WorldManager.activeTurnActor

        // Reset minimum world shape so addPlayerToWorld / isReachable see a
        // coherent baseline (mirrors TurnHarnessTest.resetState).
        WorldManager.world = World()
        WorldManager.playerStats.clear()
        WorldManager.isGameActive = false
        WorldManager.isSinglePlayer = false
        WorldManager.humanPlayerName = ""
        WorldManager.activeTurnActor = ""
        runBlocking { TurnHarness.resetState() }
    }

    @AfterTest
    fun restorePriorState()
    {
        if (priorDebugShortProp != null) {
            System.setProperty("AUTOGENESIS_DEBUG_SHORT_TURN_TIMEOUT_MS", priorDebugShortProp)
        } else {
            System.clearProperty("AUTOGENESIS_DEBUG_SHORT_TURN_TIMEOUT_MS")
        }
        WorldManager.isSimulationMode = priorIsSimulationMode
        WorldManager.simulationHumanPlayerNames = priorSimulationHumanPlayerNames
        WorldManager.humanPlayerName = priorHumanPlayerName
        WorldManager.activeTurnActor = priorActiveTurnActor
    }

    @Test
    fun simulationHumanOwnedActorSkipsTimerAndAwaitsIndefinitely() = runBlocking {
        // Arrange — enter Simulation Mode and register Alpha as a human-owned slot.
        WorldManager.isSimulationMode = true
        WorldManager.simulationHumanPlayerNames = listOf("Alpha")

        // Install a tiny debug override so any path that IGNORES the simulation
        // gate would short-circuit inside ~50ms.
        System.setProperty("AUTOGENESIS_DEBUG_SHORT_TURN_TIMEOUT_MS", "50")

        try {
            // No addPlayerToWorld — by design. isReachable() takes its
            // "no PlayerStats found → default to reachable=true" branch when
            // playerStats is empty, which keeps the test hermetic without
            // wiring up a connection manager.
            val player = Player(name = "Alpha")
            WorldManager.activeTurnActor = "Alpha"

            val start = System.currentTimeMillis()
            val outerResult = withTimeoutOrNull(100) {
                // Function signature is (player: Player) — single arg, no actionChannel.
                TurnHarness.awaitPlayerAction(player)
            }
            val elapsed = System.currentTimeMillis() - start

            // If the gate is broken, awaitPlayerAction returns inside ~50ms and
            // elapsed will be <<95. With the gate in place, the inner
            // Long.MAX_VALUE timeout means the inner suspend never short-circuits
            // and the outer 100ms wins, so elapsed is ~95–110ms.
            assertTrue(
                elapsed >= 95,
                "Simulation human-owned actor must NOT short-circuit on timer; elapsed=$elapsed (outerResult=$outerResult)"
            )
        } finally {
            // Restore in case the test re-runs in-process.
            System.clearProperty("AUTOGENESIS_DEBUG_SHORT_TURN_TIMEOUT_MS")
            WorldManager.isSimulationMode = priorIsSimulationMode
            WorldManager.simulationHumanPlayerNames = priorSimulationHumanPlayerNames
        }
    }
}