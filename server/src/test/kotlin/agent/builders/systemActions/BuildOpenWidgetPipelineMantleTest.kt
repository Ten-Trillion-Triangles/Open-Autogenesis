package agent.builders.systemActions

import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.GenericOpenAIEnv
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Structural assertions for the Mantle wiring of the open-widget agent.
 *
 * `buildOpenWidgetPipeline` returns an [OpenWidgetPipeline] with two
 * sub-pipelines (decision + pcp) plus the PCP pipe directly. Both pipes
 * are produced by `configureBasePipe`, which currently returns a
 * `BedrockMultimodalPipe`. After migration it must return a
 * [GenericOpenAIPipe] routed through Bedrock Mantle via
 * [GenericOpenAIPipe.setBedrockMantle] with Gemma 4 E2B, with a
 * `mantleStructuredCotBuilder` reasoning pipe.
 */
class BuildOpenWidgetPipelineMantleTest
{
    @BeforeTest
    fun installBearerCredentials()
    {
        GenericOpenAIEnv.setApiKey("test-key-not-used-for-network")
    }

    @Test
    fun `decision pipeline host pipe is GenericOpenAIPipe on Mantle`()
    {
        val out = buildOpenWidgetPipeline(
            connectionId = "test-conn",
            userPrompt = "open the settings widget",
            targetTabId = "Commander"
        )
        val decisionHost = out.decisionPipeline.getPipes().first()
        assertIs<GenericOpenAIPipe>(
            decisionHost,
            "decision pipeline host pipe must be GenericOpenAIPipe for Mantle, " +
                "but was ${decisionHost::class.simpleName}"
        )
    }

    @Test
    fun `pcp pipeline host pipe is GenericOpenAIPipe on Mantle`()
    {
        val out = buildOpenWidgetPipeline(
            connectionId = "test-conn",
            userPrompt = "open the settings widget",
            targetTabId = "Commander"
        )
        val pcpHost = out.pcpPipeline.getPipes().first()
        assertIs<GenericOpenAIPipe>(
            pcpHost,
            "pcp pipeline host pipe must be GenericOpenAIPipe for Mantle, " +
                "but was ${pcpHost::class.simpleName}"
        )
    }

    @Test
    fun `decision pipe has a Mantle reasoning pipe`()
    {
        val out = buildOpenWidgetPipeline(
            connectionId = "test-conn",
            userPrompt = "open the settings widget",
            targetTabId = "Commander"
        )
        val decisionHost = out.decisionPipeline.getPipes().first()
        val reasoning = decisionHost.reasoningPipe
        assertNotNull(reasoning, "decision pipe must wire a reasoning pipe")
        assertIs<GenericOpenAIPipe>(
            reasoning,
            "decision pipe reasoning must be GenericOpenAIPipe, " +
                "but was ${reasoning::class.simpleName}"
        )
        assertTrue(
            reasoning.pipeName.contains("mantle", ignoreCase = true),
            "decision pipe reasoning name must reflect Mantle factory, " +
                "but was '${reasoning.pipeName}'"
        )
    }
}