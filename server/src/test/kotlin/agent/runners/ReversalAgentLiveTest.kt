package agent.runners

import agent.builders.modifyGameState.buildReverseAgent
import com.TTT.Config.TPipeConfig
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.writeStringToFile
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertTrue

// @Ignore("Manual execution only")
class ReversalAgentLiveTest {

    @Test
    fun testLiveReversalExecution() = runBlocking {
        // 1. Load Inference Configuration
        // This is critical to ensure model IDs are mapped correctly (or to prove they aren't)
        println("Loading Bedrock Inference Config...")
        bedrockEnv.loadInferenceConfig()

        // 2. Build the Reversal Agent
        println("Building Reversal Agent...")
        val reverseAgent = buildReverseAgent()

        // 3. Enable DEBUG Tracing
        println("Enabling DEBUG Tracing...")
        reverseAgent.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))

        // 4. Initialize the Agent
        println("Initializing Agent (Init checks)...")
        reverseAgent.init(true)

        // 5. Define Test Content
        val testStory = """
            The hero, Sir Alaric, stood triumphant over the fallen dragon. 
            He raised his sword, gleaming in the sunlight, as the villagers cheered. 
            The kingdom was saved, and peace was restored for a thousand years.
            Everyone lived happily ever after.
        """.trimIndent()

        println("Executing Reversal Agent with story length: ${testStory.length}...")

        // 6. Execute the Agent
        // We expect this to FAIL with an empty string if the bug is present.
        val result = reverseAgent.execute(MultimodalContent(testStory))
        
        println("Execution Complete.")
        println("Result Length: ${result.text.length}")

        // 7. Save and Report Trace
        val traceContent = reverseAgent.getTraceReport(TraceFormat.HTML)
        // We don't have a helper to save to the exact user folder easily in unit test context 
        // without mimicking the full environment, but we can rely on TPipe's internal auto-save 
        // or just print where we *would* save it if we were the full app.
        // For this test, we can try to write it to the project dir for inspection.
        writeStringToFile("${TPipeConfig.getTraceDir()}/ReversalAgent/trace.html", traceContent)

        // 8. Assertions
        // If the bug matches our analysis, this assertion should FAIL (result is empty)
        assertTrue(result.text.isNotEmpty(), "Reversal Agent returned broken/empty string! Bug Reproduced.")
        
        println("Test Passed: Reversal Agent returned content.")
    }
}
