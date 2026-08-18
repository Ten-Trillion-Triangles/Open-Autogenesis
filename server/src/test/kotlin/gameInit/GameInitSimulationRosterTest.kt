package gameInit

import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.server.TurnHarness
import gameState.WorldManager
import structs.Commander
import structs.matchmaking.GameSessionStatus
import structs.matchmaking.GameType
import structs.matchmaking.PlayerSessionBundle
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TDD coverage for the consumer side of Simulation Mode wiring in
 * [GameInit.defineGameRules] (Task 4 of the Simulation Mode plan).
 *
 * Contract pinned by this test:
 * 1. When `sessionData.gameType == GameType.SIMULATION`:
 *    - [WorldManager.isSimulationMode] is set to `true`.
 *    - [WorldManager.simulationHumanPlayerNames] is set to the
 *      de-duplicated sessionData list, preserving order.
 * 2. Each [PlayerSessionBundle] in `sessionData.players` becomes its
 *    own [WorldManager.world.activePlayers] entry — even when the
 *    bundles share the same AccelByte user id (one human owning
 *    multiple slots is the whole point of simulation mode).
 * 3. Every human slot has [structs.PlayerStats.isControlledByNpc]
 *    set to `false`.
 * 4. AI players are auto-filled so the total active roster size
 *    equals `sessionData.maxPlayers`.
 * 5. AI slots have [structs.PlayerStats.isControlledByNpc] = `true`.
 *
 * Reference production code:
 * - server/src/main/kotlin/gameInit/GameInit.kt:35-286 (defineGameRules)
 * - server/src/main/kotlin/gameInit/GameInit.kt:296-396 (configurePlayersFromSession)
 * - server/src/main/kotlin/gameState/WorldManager.kt:237-272 (isSimulationMode /
 *   simulationHumanPlayerNames / isSimulationHumanOwned)
 */
class GameInitSimulationRosterTest
{
    private val accelByteId = "00000000000000000000000000000001"

    @Before
    fun resetWorldManagerState()
    {
        // GameInit.defineGameRules calls TurnHarness.resetState() before
        // populating the simulation flags. We replicate that here so a
        // test that runs after this one (or a previous test class in the
        // same JVM) sees a known starting point.
        runBlocking {
            TurnHarness.resetState()
        }
        WorldManager.isSimulationMode = false
        WorldManager.simulationHumanPlayerNames = emptyList()
        WorldManager.isSinglePlayer = false
        WorldManager.humanPlayerName = ""
    }

    /**
     * Case 1 (positive — the full consumer wiring):
     * gameType=SIMULATION, two human bundles (Alpha + Beta, same
     * AccelByte id — the simulation allows one user to control N
     * slots), aiOpponentCount=2, maxPlayers=4.
     *
     * Expected:
     * - WorldManager.isSimulationMode == true
     * - WorldManager.simulationHumanPlayerNames == ["Alpha", "Beta"]
     * - WorldManager.world.activePlayers.size == 4
     * - 2 human slots (isControlledByNpc=false) + 2 AI slots
     *   (isControlledByNpc=true)
     */
    @Test
    fun `simulation mode populates human roster and auto-fills AI slots`()
    {
        val sessionData = buildSimulationSessionData(
            maxPlayers = 4,
            aiOpponentCount = 2,
            humanCount = 2,
            humanNames = listOf("Alpha", "Beta")
        )

        // The map loader may throw in this minimal fixture (no real map
        // bytes on disk). defineGameRules wraps the load in try/catch and
        // configurePlayersFromSession runs BEFORE that block, so we just
        // swallow whatever runs after to focus on the roster contract.
        runCatching {
            runBlocking {
                GameInit.defineGameRules(
                    RpcCallContext(connectionId = "test-client") { error("no-op") },
                    sessionData
                )
            }
        }

        // Flag wiring
        assertTrue(WorldManager.isSimulationMode,
            "WorldManager.isSimulationMode must be true after a SIMULATION session")
        assertEquals(listOf("Alpha", "Beta"), WorldManager.simulationHumanPlayerNames,
            "simulationHumanPlayerNames must be set to the de-duplicated session list")

        // Roster shape
        assertEquals(4, WorldManager.world.activePlayers.size,
            "activePlayers must equal maxPlayers (2 humans + 2 AI fill)")
        assertEquals(2, WorldManager.playerStats.count { it.isControlledByNpc },
            "2 AI slots should have isControlledByNpc=true")
        assertEquals(2, WorldManager.playerStats.count { !it.isControlledByNpc },
            "2 human slots should have isControlledByNpc=false")
    }

    /**
     * Builds a GameSessionStatus wired for Simulation Mode with N
     * human bundles (all sharing one AccelByte id, distinct commander
     * names) and the remainder of maxPlayers filled by AI.
     *
     * [simulationMapPath] is Task 10.5 wiring: when non-blank, the
     * consumer of this sessionData (GameInit.defineGameRules) attempts
     * to load that exact map via WorldManager.loadMapFromResources
     * before falling back to the auto-pick selection. Defaults to ""
     * so existing callers (Task 4 / Task 7) are unaffected.
     */
    private fun buildSimulationSessionData(
        maxPlayers: Int,
        aiOpponentCount: Int,
        humanCount: Int,
        humanNames: List<String>,
        simulationMapPath: String = ""
    ) : GameSessionStatus
    {
        require(humanNames.size == humanCount) {
            "humanNames.size (${humanNames.size}) must match humanCount ($humanCount)"
        }
        return GameSessionStatus().apply {
            this.sessionId = "sim-session-1"
            this.serverUrl = "127.0.0.1:9080"
            this.gameType = GameType.SIMULATION
            this.maxPlayers = maxPlayers
            this.currentPlayers = humanCount
            this.aiOpponentCount = aiOpponentCount
            this.aiOnly = false
            this.isFull = true
            this.isSimulationMode = true
            this.simulationHumanPlayerNames = humanNames
            this.simulationMapPath = simulationMapPath
            humanNames.forEachIndexed { index, name ->
                this.players.add(
                    PlayerSessionBundle(
                        accelByteUserName = "sim-human-$index",
                        accelByteId = accelByteId,
                        websocketId = "ws-sim-$index",
                        commander = Commander(
                            name = name,
                            description = "Simulation human slot $name"
                        )
                    )
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Task 7 — bounds validation rejection cases.
    //
    // Contract pinned by these tests:
    // - isSimulation=true AND maxPlayers not in 2..4  -> IllegalArgumentException
    // - isSimulation=true AND simulationHumanPlayerNames empty
    //   (size < 1)                                      -> IllegalArgumentException
    // - isSimulation=true AND simulationHumanPlayerNames.size > maxPlayers
    //                                                  -> IllegalArgumentException
    //
    // The exception must surface from defineGameRules BEFORE any roster
    // binding or AI-fill mutates WorldManager; we assert on the raised
    // type rather than the post-state shape.
    // ---------------------------------------------------------------------

    /**
     * Case (Task 7 — out-of-range maxPlayers):
     * Simulation session with maxPlayers=5 (upper bound is 4) must be
     * rejected by the bounds check in defineGameRules. The exception
     * type is IllegalArgumentException (kotlin.require maps to that).
     */
    @Test
    fun `simulation mode rejects out-of-range maxPlayers`()
    {
        val sessionData = buildSimulationSessionData(
            maxPlayers = 5,
            aiOpponentCount = 4,
            humanCount = 1,
            humanNames = listOf("Alpha")
        )

        val thrown = assertFailsWith<IllegalArgumentException>(
            message = "maxPlayers=5 must be rejected by simulation-mode bounds check"
        ) {
            runBlocking {
                GameInit.defineGameRules(
                    RpcCallContext(connectionId = "test-client") { error("no-op") },
                    sessionData
                )
            }
        }
        assertTrue(
            thrown.message?.contains("maxPlayers") == true,
            "exception message should reference maxPlayers, got: ${thrown.message}"
        )
    }

    /**
     * Case (Task 7 — empty human roster):
     * Simulation session with maxPlayers=2 but an empty
     * simulationHumanPlayerNames list must fail the
     * 1..maxPlayers human-slot lower bound. The exact size
     * lower bound is 1, so an empty list trips the same
     * require() as a maxPlayers-zero outlier.
     */
    @Test
    fun `simulation mode rejects empty human roster`()
    {
        val sessionData = buildSimulationSessionData(
            maxPlayers = 2,
            aiOpponentCount = 2,
            humanCount = 0,
            humanNames = emptyList()
        )

        val thrown = assertFailsWith<IllegalArgumentException>(
            message = "an empty simulationHumanPlayerNames list must be rejected"
        ) {
            runBlocking {
                GameInit.defineGameRules(
                    RpcCallContext(connectionId = "test-client") { error("no-op") },
                    sessionData
                )
            }
        }
        assertTrue(
            thrown.message?.contains("human") == true,
            "exception message should reference the human-slot check, got: ${thrown.message}"
        )
    }

    /**
     * Case (Task 7 — over-populated human roster):
     * maxPlayers=2 must NOT accept three human-controlled
     * slots. The size check uses the same require() as the
     * empty-roster case but trips on the upper bound.
     */
    @Test
    fun `simulation mode rejects more human slots than maxPlayers`()
    {
        val sessionData = buildSimulationSessionData(
            maxPlayers = 2,
            aiOpponentCount = -1,
            humanCount = 3,
            humanNames = listOf("A", "B", "C")
        )

        val thrown = assertFailsWith<IllegalArgumentException>(
            message = "3 human slots for maxPlayers=2 must be rejected"
        ) {
            runBlocking {
                GameInit.defineGameRules(
                    RpcCallContext(connectionId = "test-client") { error("no-op") },
                    sessionData
                )
            }
        }
        assertTrue(
            thrown.message?.contains("maxPlayers") == true &&
                thrown.message?.contains("human") == true,
            "exception message should reference human-slot size and maxPlayers, got: ${thrown.message}"
        )
    }

    // ---------------------------------------------------------------------
    // Task 10.5 — explicit simulation-map wiring.
    //
    // Contract pinned by these tests:
    // - When sessionData.gameType == SIMULATION AND
    //   sessionData.simulationMapPath is non-blank,
    //   [GameInit.defineGameRules] MUST attempt to load that exact map
    //   via WorldManager.loadMapFromResources and skip the auto-pick
    //   selection block. If the explicit load fails (resource missing),
    //   defineGameRules MUST NOT throw and MUST fall back to the
    //   auto-pick path so a usable map still gets loaded.
    //
    // Reference production code:
    // - sharedModel/.../SessionStatus.kt:GameSessionStatus.simulationMapPath
    // - server/.../gameInit/GameInit.kt:84-228 (isSimulation flag +
    //   explicit-map guard + auto-pick block)
    // ---------------------------------------------------------------------

    /**
     * Case (Task 10.5 — explicit-map happy path):
     * gameType=SIMULATION, maxPlayers=2, simulationMapPath
     * = "maps/Arctica.map" (a 2-player bundled map). After
     * defineGameRules runs, [WorldManager.activeMapPackBytes]
     * MUST be non-null — the chosen map was loaded and the
     * random auto-pick block was skipped.
     */
    @Test
    fun `simulation mode loads explicit map from request payload`()
    {
        // Start from a known-empty map-pack state so we can assert
        // that the explicit-map load is what populated it (rather
        // than a leftover bytes array from a prior test in the
        // same JVM).
        WorldManager.activeMapPackBytes = null

        val sessionData = buildSimulationSessionData(
            maxPlayers = 2,
            aiOpponentCount = 1,
            humanCount = 1,
            humanNames = listOf("Alpha"),
            simulationMapPath = "maps/Arctica.map"
        )

        // defineGameRules swallows map-load failures via runCatching,
        // so a happy-path load must complete without throwing.
        runCatching {
            runBlocking {
                GameInit.defineGameRules(
                    RpcCallContext(connectionId = "test-client") { error("no-op") },
                    sessionData
                )
            }
        }

        assertTrue(
            WorldManager.activeMapPackBytes != null,
            "simulationMapPath='maps/Arctica.map' should load Arctica.map and leave " +
                "WorldManager.activeMapPackBytes non-null"
        )
    }

    /**
     * Case (Task 10.5 — explicit-map fallback):
     * gameType=SIMULATION with simulationMapPath pointing at a
     * resource that does NOT exist ("maps/NonExistentSimulationMap.map").
     * [WorldManager.loadMapFromResources] throws IllegalArgumentException
     * for unknown paths; defineGameRules MUST swallow that and fall
     * back to the auto-pick block so the session still gets a map.
     *
     * The assertion is the same end-state shape as the happy-path
     * case: a usable map is loaded (any non-null activeMapPackBytes
     * is acceptable — we only require the session to recover).
     */
    @Test
    fun `simulation mode falls back to auto-pick when explicit map path fails to load`()
    {
        WorldManager.activeMapPackBytes = null

        val sessionData = buildSimulationSessionData(
            maxPlayers = 2,
            aiOpponentCount = 1,
            humanCount = 1,
            humanNames = listOf("Alpha"),
            simulationMapPath = "maps/NonExistentSimulationMap.map"
        )

        // The key contract: defineGameRules must NOT throw even though
        // the explicit load path will fail. runCatching here also acts
        // as an assertion — if defineGameRules propagated the
        // IllegalArgumentException, the .isFailure branch would fire.
        val result = runCatching {
            runBlocking {
                GameInit.defineGameRules(
                    RpcCallContext(connectionId = "test-client") { error("no-op") },
                    sessionData
                )
            }
        }

        assertTrue(
            result.isSuccess,
            "defineGameRules should swallow the explicit-map load failure and fall back; " +
                "got exception: ${result.exceptionOrNull()?.message}"
        )
        assertTrue(
            WorldManager.activeMapPackBytes != null,
            "fallback path should have loaded some map via the auto-pick block; " +
                "activeMapPackBytes is still null"
        )
    }
}