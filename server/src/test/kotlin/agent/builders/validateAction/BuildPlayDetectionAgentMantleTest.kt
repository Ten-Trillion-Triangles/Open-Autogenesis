package agent.builders.validateAction

import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.GenericOpenAIEnv
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Structural assertions for the Mantle wiring of the play detection agent.
 *
 * Migration target: `buildPlayDetectionAgent` returns a Pipeline whose primary
 * pipe is a [GenericOpenAIPipe] routed through Bedrock Mantle (Gemma 4 E2B).
 * The reasoning pipe is also a Mantle pipe sourced from
 * `BedrockConfig.mantleExplicitCotBuilder` (the original used
 * `BedrockConfig.explicitCotBuilder`, so we swap to its Mantle sibling).
 */
class BuildPlayDetectionAgentMantleTest
{
    @BeforeTest
    fun installBearerCredentials()
    {
        GenericOpenAIEnv.setApiKey("test-key-not-used-for-network")
    }

    @Test
    fun `host pipe is GenericOpenAIPipe not BedrockMultimodalPipe`()
    {
        val pipeline = buildPlayDetectionAgent(actor = null)
        val host = pipeline.getPipes().first()
        assertIs<GenericOpenAIPipe>(
            host,
            "buildPlayDetectionAgent host pipe must be GenericOpenAIPipe for Mantle, " +
                "but was ${host::class.simpleName}"
        )
    }

    @Test
    fun `reasoning pipe is GenericOpenAIPipe routed through mantleExplicitCotBuilder`()
    {
        val pipeline = buildPlayDetectionAgent(actor = null)
        val host = pipeline.getPipes().first()
        val reasoning = host.reasoningPipe
        assertNotNull(reasoning, "buildPlayDetectionAgent must wire a reasoning pipe")
        assertIs<GenericOpenAIPipe>(
            reasoning,
            "buildPlayDetectionAgent reasoning pipe must be GenericOpenAIPipe " +
                "(mantleExplicitCotBuilder), but was ${reasoning::class.simpleName}"
        )
        assertTrue(
            reasoning.pipeName.contains("mantle", ignoreCase = true),
            "Reasoning pipe name must reflect Mantle factory output, " +
                "but was '${reasoning.pipeName}'"
        )
    }
}
