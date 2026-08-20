package agent.runners

import agent.builders.judgeOutcome.AssetExchange
import agent.builders.judgeOutcome.Results
import agent.builders.judgeOutcome.StatBuff
import agent.builders.judgeOutcome.MultiActorStatChanges
import agent.builders.validateAction.ActionTargetType
import agent.builders.validateAction.ActionTargetTypeObj
import agent.builders.validateAction.PlayType
import com.TTT.Pipeline.Pipeline
import gameState.WorldManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import structs.Npc
import structs.Player
import structs.Resource
import structs.World
import enums.NpcType
import enums.ResourceType
import serverStructs.PlayerStats

// `WorldManager` is a Kotlin `object` singleton whose `world` field is
// `@Volatile var`. Several other tests reassign that field via
// `loadMapFromPack`. Gradle's default test config runs all tests in a
// single shared JVM with parallel test execution enabled, so a concurrent
// test can reassign `WorldManager.world` between our `@Before` and our
// `applyJudgeResults` call even though we install a fresh `World()` here.
// `ExecutionMode.SAME_THREAD` forces this class to run serially rather
// than in parallel — the cheapest reliable fix for shared-singleton state
// without enabling JVM forking.
@Execution(ExecutionMode.SAME_THREAD)
class MultiActorConsequenceTest {

    /**
     * The original [WorldManager.world] reference captured before this test
     * runs, restored in [tearDown]. Necessary because [WorldManager] is a
     * global singleton and several other tests (e.g. `ChunkingSmokeTest`,
     * `WorldManagerJudgeTest`, `WorldManagerWorldUpdatesTest`, ...) replace
     * `world` via [WorldManager.loadMapFromPack]. Without restoring the
     * captured reference, this test would either mutate the wrong world or
     * operate against a freshly-loaded world that lacks the test fixtures
     * we just installed.
     *
     * [WorldManager.world] is `@Volatile var` so the reference itself can
     * be reassigned by parallel-running tests between any two reads. To
     * guarantee our player/NPC fixtures land on the SAME world that
     * [WorldManager.applyJudgeResults] operates on, we install a fresh,
     * private [World] instance in [setup] and reassign
     * [WorldManager.world] back to the saved reference in [tearDown]. This
     * also closes the parallel-test race because the new world is owned by
     * this test instance for the duration of `@Before` → `@After`.
     */
    private lateinit var savedWorld: World

    @Before
    fun setup() {
        // Capture the original world reference for tear-down restoration,
        // then install a fresh empty World so any concurrent or out-of-order
        // mutation by another test cannot reach our fixtures.
        savedWorld = WorldManager.world
        WorldManager.world = World()
        WorldManager.history.clear()
    }

    @After
    fun tearDown() {
        // Restore the original world reference. This protects against tests
        // that ran after us and assumed `WorldManager.world` pointed at a
        // known state — they will see the saved instance, not the empty
        // World we installed in `setup()`.
        WorldManager.world = savedWorld
        // Clear any state on the saved instance so the next test gets a
        // clean slate regardless of execution order.
        savedWorld.activePlayers.clear()
        savedWorld.npc.clear()
        WorldManager.history.clear()
    }

    @Test
    fun testMultiActorAssetAndStatConsequences() = runBlocking {
        // 1. Setup Player and NPC
        val player = Player(name = "Attacker")
        player.resources.add(Resource(name = "Gold", type = ResourceType.Economic))
        WorldManager.world.activePlayers.add(player)

        val npc = Npc(name = "TargetNPC", type = NpcType.Passive)
        npc.resources.add(Resource(name = "Ancient Relic", type = ResourceType.Supernatural))
        npc.militaryReadiness = 70
        WorldManager.world.npc.add(npc)

        // 2. Mock Results with Asset Exchange and Multi-Actor Stat Changes
        val results = Results(
            resultSummary = "Attacker defeated TargetNPC and seized the Ancient Relic.",
            assetExchanges = mutableListOf(
                AssetExchange(assetName = "Ancient Relic", from = "TargetNPC", to = "Attacker")
            )
        )

        // Simulate judge's MultiActorStatChanges
        val statChanges = MultiActorStatChanges(
            changes = mutableMapOf(
                "TargetNPC" to StatBuff(militaryReadiness = -15)
            )
        )

        // 3. Apply results manually via WorldManager to verify logic
        // (In a real turn, judge's transformationFunction does this).
        //
        // `WorldManager.world` is a `@Volatile var` shared across the JVM
        // with no mutex; concurrent tests (e.g. `ChunkingSmokeTest`,
        // `WorldManagerWorldUpdatesTest`) reassign it via
        // `loadMapFromPack`. Between our `@Before` setup and this call
        // an interleaved test can replace the world we just populated.
        // We pin the local Player + NPC into `WorldManager.world`
        // immediately before `applyJudgeResults` and capture a reference
        // to that world so the post-call stat update operates on the
        // SAME object that processed the judge results.
        val pinnedWorld = World()
        pinnedWorld.activePlayers.add(player)
        pinnedWorld.npc.add(npc)
        WorldManager.world = pinnedWorld

        val turnNumber = 1
        val timestamp = System.currentTimeMillis()

        WorldManager.applyJudgeResults(
            playerName = "Attacker",
            wasSuccessful = true,
            results = results,
            turnNumber = turnNumber,
            timestampMillis = timestamp
        )

        // Simulate Judge's transformationFunction for stats. Operate on the
        // captured `pinnedWorld` reference rather than re-reading
        // `WorldManager.world`, which may have been reassigned again by a
        // concurrent test. `pinnedWorld.findNpcByName("TargetNPC")` returns
        // the SAME `npc` object we just mutated above.
        statChanges.changes.forEach { (name, buff) ->
            val targetNpcForStatUpdate = pinnedWorld.findNpcByName(name)
            if (targetNpcForStatUpdate != null) {
                targetNpcForStatUpdate.militaryReadiness = (targetNpcForStatUpdate.militaryReadiness + buff.militaryReadiness).coerceIn(0, 100)
            }
        }

        // 4. Assertions
        // Asset Transfer
        assertTrue(player.resources.any { it.name == "Ancient Relic" }, "Player should have gained the relic")
        assertFalse(npc.resources.any { it.name == "Ancient Relic" }, "NPC should have lost the relic")

        // Stat Penalty
        assertEquals(55, npc.militaryReadiness, "NPC military readiness should have decreased by 15")
    }
}