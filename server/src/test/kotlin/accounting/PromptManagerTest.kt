package accounting

import org.junit.Test
import org.junit.Before
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.ttt.autogenesis.network.RpcCallContext
import gameState.WorldManager
import serverStructs.PlayerStats
import structs.Player
import agent.builders.systemActions.UserActionClassification
import agent.builders.systemActions.ActionType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PromptManagerTest {

    @Before
    fun setup()
    {
        WorldManager.playerStats.clear()
        PromptManager.promptUsage.clear()
        WorldManager.activeTurnActor = ""
    }

    @Test
    fun `test locking prevents concurrent gameplay actions`() = runBlocking {
         // Setup
        val testConnectionId = "test-conn-lock"
        val testPlayer = Player(name = "Commander Lock")
        
        WorldManager.playerStats.add(PlayerStats(
            playerData = testPlayer,
            playerID = testConnectionId
        ))
        WorldManager.activeTurnActor = testPlayer.name

        var runnerCalls = 0
        
        // Inject Mock Runner with delay
        PromptManager.turnRunner = { context, player, action ->
            runnerCalls++
            delay(200) // Hold lock for 200ms
        }
        
        val context = RpcCallContext(connectionId = testConnectionId, sender = {})

        // Launch first request
        launch {
             PromptManager.sendPromptToServer(context, "/play action 1")
        }
        
        delay(50) // Ensure first request starts and grabs lock
        
        // Launch second request (should be rejected)
        launch {
             PromptManager.sendPromptToServer(context, "/play action 2")
        }

        // Wait for both to theoretically finish
        delay(400)

        // Only one should have run
        assertEquals(1, runnerCalls, "Turn runner should only be called once due to locking")
    }

    @Test
    fun `test slash play command routes correctly`() = runBlocking {
        // Setup
        val testPlayerId = "test-player-id" // This maps to connectionId in PlayerStats
        val testConnectionId = "test-conn"
        val testPlayer = Player(name = "Commander Test")
        
        // Populate WorldManager -> Done in setup but we need specific player
        WorldManager.playerStats.add(PlayerStats(
            playerData = testPlayer,
            playerID = testConnectionId
        ))
        WorldManager.activeTurnActor = testPlayer.name


        var runnerCalled = false
        var capturedAction = ""
        
        // Inject Mock Runner
        PromptManager.turnRunner = { context, player, action ->
            runnerCalled = true
            capturedAction = action
            delay(10) // Simulate work
        }

        // Execute
        val context = RpcCallContext(connectionId = testConnectionId, sender = {})
        PromptManager.sendPromptToServer(context, "/play attack target")

        // Wait for async execution (GlobalScope)
        var attempts = 0
        while(!runnerCalled && attempts < 20)
        {
            delay(50)
            attempts++
        }

        assertTrue(runnerCalled, "Turn runner should have been called")
        assertEquals("attack target", capturedAction)
    }

    @Test
    fun `test natural language gameplay routes correctly`() = runBlocking {
         // Setup
        val testConnectionId = "test-conn-nl"
        val testPlayer = Player(name = "Commander NLP")
        
        WorldManager.playerStats.add(PlayerStats(
            playerData = testPlayer,
            playerID = testConnectionId
        ))
        WorldManager.activeTurnActor = testPlayer.name

        var runnerCalled = false
        
        // Inject Mock Runner
        PromptManager.turnRunner = { context, player, action ->
            runnerCalled = true
        }
        
        // Inject Mock Classifier
        PromptManager.classifier = object : ActionClassifier {
            override suspend fun classify(connectionId: String, prompt: String): UserActionClassification?
            {
                return UserActionClassification(ActionType.GAMEPLAY, 0.9, "test")
            }
        }

        // Execute
        val context = RpcCallContext(connectionId = testConnectionId, sender = {})
        PromptManager.sendPromptToServer(context, "attack the objective")

        // Wait
        var attempts = 0
        while(!runnerCalled && attempts < 20)
        {
            delay(50)
            attempts++
        }

        assertTrue(runnerCalled, "Turn runner should have been called for natural language gameplay")
    }
}
