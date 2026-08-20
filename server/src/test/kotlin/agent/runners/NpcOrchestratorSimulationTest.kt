package agent.runners

import gameState.WorldManager
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import structs.Npc
import structs.GameHistory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

@Ignore("Manual execution only")
class NpcOrchestratorSimulationTest
{

    @Before
    fun setup()
    {
        // Reset WorldManager state
        WorldManager.history.clear()
        WorldManager.world.npc.clear()
        WorldManager.world.karmaPoints = 0
    }

    @Test
    fun testNpcTurnResolutionFlow() = runBlocking {
        val npc = Npc(
            name = "Overlord Test",
            description = "A powerful villain seeking total map dominance."
        )
        WorldManager.world.npc.add(npc)

        val turnAction = "Overlord Test deploys shadow scouts to infiltrate the Northern Spire."

        // Since we can't easily mock the TPipe pipelines in a unit test without a lot of ceremony,
        // we test the orchestrator's integration logic and history commitment.
        // In a real environment, this would invoke the LLM via TPipe.
        
        // However, for this simulation test, we want to ensure the function exists and 
        // follows the expected pattern of adding to history.
        
        // Note: In a real test environment, we'd use a mock pipeline or a test environment
        // that allows TPipe execution. Here we verify the orchestrator's logic.
        
        executeNpcTurn(npc, turnAction)

        // Verify history commitment
        assertTrue(WorldManager.history.isNotEmpty(), "History should not be empty after NPC turn")
        val lastEntry = WorldManager.history.last()
        assertEquals(npc.name, lastEntry.turnPlayer)
        assertEquals(turnAction, lastEntry.turnAction)
        assertTrue(lastEntry.turnStory.isNotEmpty(), "Turn story should be populated")
    }
}