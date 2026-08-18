package agent.builders.writingAgent

import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.GenericOpenAIEnv
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Structural assertions for the Mantle wiring of the response refinement agent.
 *
 * `buildResponseRefinementAgent` returns a Pipeline with two pipes (detect,
 * refine). Both must be migrated to Mantle via
 * [GenericOpenAIPipe.setBedrockMantle] with Gemma 4 E2B. Reasoning pipes
 * come from `BedrockConfig.mantleExplicitCotBuilder` and
 * `BedrockConfig.mantleStructuredCotBuilder` (Mantle siblings of the
 * Bedrock reasoning builders the original used).
 */
class BuildResponseRefinementAgentMantleTest
{
    @BeforeTest
    fun installBearerCredentials()
    {
        GenericOpenAIEnv.setApiKey("test-key-not-used-for-network")
    }

    @Test
    fun `both response refinement pipes are GenericOpenAIPipe on Mantle`()
    {
        val pipeline = buildResponseRefinementAgent()
        val pipes = pipeline.getPipes()
        assertEquals(2, pipes.size, "Expected 2 pipes in response refinement pipeline")
        for ((index, pipe) in pipes.withIndex())
        {
            assertIs<GenericOpenAIPipe>(
                pipe,
                "response refinement pipe[$index] must be GenericOpenAIPipe for Mantle, " +
                    "but was ${pipe::class.simpleName}"
            )
        }
    }

    @Test
    fun `each response refinement pipe has a Mantle reasoning pipe`()
    {
        val pipeline = buildResponseRefinementAgent()
        val pipes = pipeline.getPipes()
        for ((index, pipe) in pipes.withIndex())
        {
            val reasoning = pipe.reasoningPipe
            assertNotNull(reasoning, "response refinement pipe[$index] must wire a reasoning pipe")
            assertIs<GenericOpenAIPipe>(
                reasoning,
                "response refinement pipe[$index] reasoning must be GenericOpenAIPipe, " +
                    "but was ${reasoning::class.simpleName}"
            )
            assertTrue(
                reasoning.pipeName.contains("mantle", ignoreCase = true),
                "response refinement pipe[$index] reasoning name must reflect Mantle factory, " +
                    "but was '${reasoning.pipeName}'"
            )
        }
    }
}