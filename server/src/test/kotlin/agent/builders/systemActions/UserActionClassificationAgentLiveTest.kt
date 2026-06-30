package agent.builders.systemActions

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.ttt.autogenesis.logging.LogPriority
import org.ttt.autogenesis.logging.Logger

class UserActionClassificationAgentLiveTest {

    @Test
    fun `test narrative action classification`() = runBlocking {
        Logger.configure(LogPriority.DEBUG, true, serverType = "test")
        
        val pipeline = createUserActionClassificationPipeline()
        pipeline.init(true)
        
        val narrativePrompt = "Lord Maple Tree summons his most accomplished scientists to analyze the strange anomalies in sector 7-G."
        
        val result = pipeline.execute(MultimodalContent(narrativePrompt))
        println("Raw Result: ${result.text}")
        
        val classification = com.TTT.Util.extractJson<UserActionClassification>(result.text)
        assertNotNull(classification, "Classification should not be null")
        assertEquals(ActionType.GAMEPLAY, classification.actionType, "Narrative action should be classified as GAMEPLAY")
        println("Reasoning: ${classification.reasoning}")
    }

    @Test
    fun `test question action classification`() = runBlocking {
        Logger.configure(LogPriority.DEBUG, true, serverType = "test")
        
        val pipeline = createUserActionClassificationPipeline()
        pipeline.init(true)
        
        val questionPrompt = "What are my current resources and how much military points do I have left?"
        
        val result = pipeline.execute(MultimodalContent(questionPrompt))
        println("Raw Result: ${result.text}")
        
        val classification = com.TTT.Util.extractJson<UserActionClassification>(result.text)
        assertNotNull(classification, "Classification should not be null")
        assertEquals(ActionType.QUESTION, classification.actionType, "Question should be classified as QUESTION")
    }

    @Test
    fun `test ui command action classification`() = runBlocking {
        Logger.configure(LogPriority.DEBUG, true, serverType = "test")
        
        val pipeline = createUserActionClassificationPipeline()
        pipeline.init(true)
        
        val uiPrompt = "Open the galactic map and show me the diplomatic relations tab."
        
        val result = pipeline.execute(MultimodalContent(uiPrompt))
        println("Raw Result: ${result.text}")
        
        val classification = com.TTT.Util.extractJson<UserActionClassification>(result.text)
        assertNotNull(classification, "Classification should not be null")
        assertEquals(ActionType.UI_COMMAND, classification.actionType, "UI command should be classified as UI_COMMAND")
    }
}
