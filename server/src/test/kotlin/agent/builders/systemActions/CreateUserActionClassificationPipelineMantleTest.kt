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
 * Structural assertions for the Mantle wiring of the user action
 * classification pipeline.
 *
 * `createUserActionClassificationPipeline` returns a Pipeline with a single
 * host pipe (the classification pipe). The host pipe and its reasoning pipe
 * (the explicit-CoT helper, currently private) must be migrated to Mantle
 * via [GenericOpenAIPipe.setBedrockMantle] with Gemma 4 E2B. The
 * `createCustomValidatorPipe` helper stays on Bedrock because it targets
 * qwenCoder30B, which uses the Converse API surface not exposed via Mantle.
 */
class CreateUserActionClassificationPipelineMantleTest
{
    @BeforeTest
    fun installBearerCredentials()
    {
        GenericOpenAIEnv.setApiKey("test-key-not-used-for-network")
    }

    @Test
    fun `classification pipe host is GenericOpenAIPipe on Mantle`()
    {
        val pipeline = createUserActionClassificationPipeline()
        val host = pipeline.getPipes().first()
        assertIs<GenericOpenAIPipe>(
            host,
            "createUserActionClassificationPipeline host pipe must be GenericOpenAIPipe " +
                "for Mantle, but was ${host::class.simpleName}"
        )
    }

    @Test
    fun `classification pipe has a Mantle reasoning pipe`()
    {
        val pipeline = createUserActionClassificationPipeline()
        val host = pipeline.getPipes().first()
        val reasoning = host.reasoningPipe
        assertNotNull(reasoning, "classification pipe must wire a reasoning pipe")
        assertIs<GenericOpenAIPipe>(
            reasoning,
            "classification pipe reasoning must be GenericOpenAIPipe " +
                "(mantleExplicitCotBuilder), but was ${reasoning::class.simpleName}"
        )
        assertTrue(
            reasoning.pipeName.contains("mantle", ignoreCase = true),
            "classification pipe reasoning name must reflect Mantle factory, " +
                "but was '${reasoning.pipeName}'"
        )
    }
}