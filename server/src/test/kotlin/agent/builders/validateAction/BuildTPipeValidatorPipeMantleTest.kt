package agent.builders.validateAction

import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.GenericOpenAIEnv
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Structural assertions for the Mantle wiring of the validator-pipe factory.
 *
 * `buildTPipeValidatorPipe` is the META-VALIDATOR factory that all 30+ agent
 * pipelines use to gate a host pipe's output. The migration target is to
 * swap its host pipe from `BedrockMultimodalPipe` + `qwenCoder30B` to
 * [GenericOpenAIPipe] routed through the Bedrock Mantle endpoint with
 * Gemma 4 E2B. The reasoning pipe is migrated from
 * `BedrockConfig.authorBuilder(zetaReasoning, model=qwenCoder30B)` to
 * `BedrockConfig.mantleStructuredCotBuilder(depth=Med, duration=Short)`.
 * Token budget migrates from `generativeBudgetSettings` to
 * `BedrockConfig.e2bBudgetSettings`.
 *
 * These tests assert only the public observable surface — pipe type and
 * pipe name — because the Mantle credential state and model field are not
 * accessible from outside the package. The type assertion on the host
 * pipe plus the pipeName string check is sufficient to verify the
 * migration because `BedrockConfig.mantleStructuredCotBuilder` is the
 * only path that produces a `GenericOpenAIPipe` with the matching name
 * shape for these factories. Network verification lives in
 * `BedrockMantleLowAgentsLiveTest`.
 */
class BuildTPipeValidatorPipeMantleTest
{
    @BeforeTest
    fun installBearerCredentials()
    {
        GenericOpenAIEnv.setApiKey("test-key-not-used-for-network")
    }

    @Test
    fun `factory returns GenericOpenAIPipe not BedrockMultimodalPipe`()
    {
        val validatorPipe = buildTPipeValidatorPipe(
            instructions = "Verify the prior agent completed the task.",
            schema = "{\"field\":\"value\"}"
        )
        assertIs<GenericOpenAIPipe>(
            validatorPipe,
            "buildTPipeValidatorPipe must return GenericOpenAIPipe for Mantle, " +
                "but was ${validatorPipe::class.simpleName}"
        )
    }

    @Test
    fun `factory pipe name contains mantle and not bedrock or qwen`()
    {
        val validatorPipe = buildTPipeValidatorPipe(
            instructions = "Verify the prior agent completed the task.",
            schema = ""
        )
        val name = validatorPipe.pipeName
        assertTrue(
            name.contains("mantle", ignoreCase = true),
            "validator pipe name must reflect Mantle factory output, but was '$name'"
        )
        assertFalse(
            name.contains("bedrock", ignoreCase = true),
            "validator pipe name must not mention 'bedrock' after Mantle migration, " +
                "but was '$name'"
        )
        assertFalse(
            name.contains("qwen", ignoreCase = true),
            "validator pipe name must not mention 'qwen' after Mantle migration, " +
                "but was '$name'"
        )
    }

    @Test
    fun `factory reasoning pipe is GenericOpenAIPipe on Mantle`()
    {
        val validatorPipe = buildTPipeValidatorPipe(
            instructions = "Verify the prior agent completed the task."
        )
        val reasoning = validatorPipe.reasoningPipe
        assertNotNull(reasoning, "validator pipe must wire a reasoning pipe")
        assertIs<GenericOpenAIPipe>(
            reasoning,
            "validator reasoning must be GenericOpenAIPipe (mantleStructuredCotBuilder), " +
                "but was ${reasoning::class.simpleName}"
        )
        assertTrue(
            reasoning.pipeName.contains("mantle", ignoreCase = true),
            "validator reasoning name must reflect Mantle factory, but was " +
                "'${reasoning.pipeName}'"
        )
    }
}
