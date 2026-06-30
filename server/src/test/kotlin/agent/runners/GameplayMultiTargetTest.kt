package agent.runners

import agent.builders.validateAction.ActionIntent
import agent.builders.validateAction.ActionTargetType
import agent.builders.validateAction.ActionTargetTypeObj
import agent.builders.validateAction.PlayType
import agent.builders.validateAction.PlayTypeObj
import agent.builders.validateAction.buildDefensiveValidator
import agent.builders.validateAction.buildTargetDetectorAgent
import agent.builders.validateAction.buildCounterResponseIntentDetector
import agent.builders.writingAgent.buildResponseRefinementAgent
import agent.managers.GameResponseManager
import com.TTT.Context.ContextBank
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.Pipeline
import gameState.WorldManager
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.server.ActionHistoryRpcHandlers
import org.ttt.autogenesis.server.UiSignalRpcHandlers
import org.ttt.autogenesis.server.getAllConnectedClientIds
import structs.GameHistory
import structs.Player
import structs.World
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameplayMultiTargetTest {

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        mockkObject(WorldManager)
        mockkObject(UiSignalRpcHandlers)
        mockkObject(ActionHistoryRpcHandlers)
        mockkStatic("agent.runners.GameplayOrchestratorKt")
        mockkStatic("org.ttt.autogenesis.server.AgentWorkStreamStreamingKt")
        mockkStatic("agent.builders.validateAction.DefensiveValidatorKt")
        mockkStatic("agent.builders.validateAction.TargetDetectorAgentKt")
        mockkStatic("agent.builders.writingAgent.ResponseRefinementAgentKt")
        mockkStatic("agent.builders.validateAction.CounterResponseIntentDetectorKt")

        // Default behavior for WorldManager
        val world = World()
        every { WorldManager.world } returns world
        every { WorldManager.worldMutex } returns world.mutex
        coEvery { WorldManager.startManualTimer(any()) } just Runs
        every { WorldManager.stopTurnTimer() } just Runs
        
        // Default behavior for RPC handlers
        coEvery { UiSignalRpcHandlers.broadcastProgressBar(any(), any()) } just Runs
        coEvery { UiSignalRpcHandlers.broadcastCounterPlayPrompt(any(), any(), any(), any()) } just Runs
        coEvery { UiSignalRpcHandlers.broadcastNarrativeChunk(any(), any()) } just Runs
        coEvery { ActionHistoryRpcHandlers.broadcastTurnComplete(any()) } just Runs
        
        // Mock top-level function
        coEvery { getAllConnectedClientIds() } returns emptyList()
    }

    @Test
    fun testMultiTargetCounterPlay() = runBlocking {
        val attacker = Player(name = "Attacker")
        val target1 = Player(name = "Target1", militaryPoints = 100)
        val target2 = Player(name = "Target2", militaryPoints = 100)
        
        WorldManager.world.activePlayers.addAll(listOf(attacker, target1, target2))
        
        val targetType = ActionTargetTypeObj(
            type = ActionTargetType.Player,
            targets = listOf("Target1", "Target2"),
            actionIntent = ActionIntent.Hostile
        )
        val playType = PlayTypeObj(type = PlayType.Military)
        val intentHistory = GameHistory(turnPlayer = "Attacker", turnAction = "Attacking T1 and T2")
        
        // Mock reachability
        coEvery { WorldManager.isReachable(any()) } returns true
        
        // Mock response intent detection
        coEvery { detectResponseIntent(any(), any()) } returns ActionIntent.Hostile
        
        // Mock target detection for the responses (no further targets)
        val mockTargetResultNone = MultimodalContent("""{"type": "None", "targets": []}""")
        val mockTargetPipeline = mockk<Pipeline>(relaxed = true)
        coEvery { mockTargetPipeline.execute(any<MultimodalContent>()) } returns mockTargetResultNone
        every { buildTargetDetectorAgent(any()) } returns mockTargetPipeline
        
        // Mock refinement and validation agents
        val mockPipeline = mockk<Pipeline>(relaxed = true)
        coEvery { mockPipeline.execute(any<MultimodalContent>()) } returns MultimodalContent("Refined Response")

        every { buildDefensiveValidator(any(), any(), any()) } returns mockPipeline
        every { buildResponseRefinementAgent() } returns mockPipeline

        val job = launch {
            delay(200)
            GameResponseManager.submitResponse("Target1", "T1 Defends!")
            delay(200)
            GameResponseManager.submitResponse("Target2", "T2 Defends!")
        }
        
        val result = handleCounterPlay(attacker, "Attacking T1 and T2", targetType, playType, intentHistory, "test-conn")
        
        assertEquals(2, result.counterResponsesList.size, "Should have 2 counter-responses")
        assertTrue(result.counterResponsesList.any { it.contains("Target1") }, "Should contain Target1 response")
        assertTrue(result.counterResponsesList.any { it.contains("Target2") }, "Should contain Target2 response")
        
        job.join()
    }

    @Test
    fun testRecursiveCascadeCounterPlay() = runBlocking {
        val attacker = Player(name = "Attacker")
        val targetA = Player(name = "TargetA", militaryPoints = 100)
        val targetB = Player(name = "TargetB", militaryPoints = 100)
        
        WorldManager.world.activePlayers.addAll(listOf(attacker, targetA, targetB))
        
        val targetType = ActionTargetTypeObj(
            type = ActionTargetType.Player,
            targets = listOf("TargetA"),
            actionIntent = ActionIntent.Hostile
        )
        val playType = PlayTypeObj(type = PlayType.Military)
        val intentHistory = GameHistory(turnPlayer = "Attacker", turnAction = "Attacking A")
        
        coEvery { WorldManager.isReachable(any()) } returns true
        coEvery { detectResponseIntent(any(), any()) } returns ActionIntent.Hostile
        
        // First target detection (A's response targets B)
        val mockTargetResultA = MultimodalContent("""{"type": "Player", "targets": ["TargetB"]}""")
        // Second target detection (B's response targets none)
        val mockTargetResultB = MultimodalContent("""{"type": "None", "targets": []}""")
        
        val mockTargetPipeline = mockk<Pipeline>(relaxed = true)
        coEvery { mockTargetPipeline.execute(any<MultimodalContent>()) } returnsMany listOf(mockTargetResultA, mockTargetResultB)
        every { buildTargetDetectorAgent(any()) } returns mockTargetPipeline
        
        // Mock other agents
        val mockRefinePipeline = mockk<Pipeline>(relaxed = true)
        coEvery { mockRefinePipeline.execute(any<MultimodalContent>()) } returns MultimodalContent("Refined")
        
        every { buildDefensiveValidator(any(), any(), any()) } returns mockRefinePipeline
        every { buildResponseRefinementAgent() } returns mockRefinePipeline

        val job = launch {
            delay(200)
            GameResponseManager.submitResponse("TargetA", "A responds targeting B")
            delay(200)
            GameResponseManager.submitResponse("TargetB", "B responds")
        }
        
        val result = handleCounterPlay(attacker, "Attacking A", targetType, playType, intentHistory, "test-conn")
        
        assertEquals(2, result.counterResponsesList.size, "Should have 2 counter-responses in cascade")
        assertTrue(result.counterResponsesList[0].contains("TargetA"), "First response should be from TargetA")
        assertTrue(result.counterResponsesList[1].contains("TargetB"), "Second response should be from TargetB")
        
        job.join()
    }
}
