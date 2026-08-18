package globals

import agent.builders.validateAction.buildTPipeValidatorPipe
import bedrockPipe.BedrockMultimodalPipe
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.GenericOpenAIEnv
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression-pinning tests for the Qwen 30B factory overload on
 * `buildTPipeValidatorPipe(..., useQwenCoder30B = true)`. Asserts that the
 * HIGH-scope validator path routes through Bedrock QwenCoder30B +
 * `generativeBudgetSettings` with Flex-tier host + Standard-tier reasoning.
 *
 * The 12 LOW/MED-scope validators continue to use the Mantle E2B path
 * (default `useQwenCoder30B = false`).
 */
class QwenValidatorPipeTest
{
    @BeforeTest
    fun installBearerCredentials()
    {
        GenericOpenAIEnv.setApiKey("test-key-not-used-for-network")
    }

    @Test
    fun `useQwenCoder30B true returns BedrockMultimodalPipe wired with Qwen 30B`()
    {
        val pipe = buildTPipeValidatorPipe(
            instructions = "Qwen 30B validator for HIGH-scope legality check",
            useQwenCoder30B = true
        )
        assertNotNull(pipe, "validator pipe must not be null")
        assertIs<BedrockMultimodalPipe>(
            pipe,
            "useQwenCoder30B=true must return a BedrockMultimodalPipe for Qwen 30B routing, " +
                "but was ${pipe::class.simpleName}"
        )
    }

    @Test
    fun `default useQwenCoder30B stays on Mantle E2B path`()
    {
        val pipe = buildTPipeValidatorPipe(
            instructions = "Default Mantle E2B validator"
        )
        assertNotNull(pipe, "validator pipe must not be null")
        assertIs<GenericOpenAIPipe>(
            pipe,
            "default useQwenCoder30B=false must keep the Mantle E2B routing, " +
                "but was ${pipe::class.simpleName}"
        )
    }

    @Test
    fun `useQwenCoder30B explicit false is equivalent to default`()
    {
        val pipe = buildTPipeValidatorPipe(
            instructions = "Explicit Mantle E2B validator",
            useQwenCoder30B = false
        )
        assertIs<GenericOpenAIPipe>(
            pipe,
            "useQwenCoder30B=false must return a GenericOpenAIPipe (Mantle path)"
        )
    }

    @Test
    fun `qwen validator pipe carries the documented token budget shape`()
    {
        // The Qwen path should use generativeBudgetSettings (235K / 12K)
        // — already pre-migration shape, not the Mantle e2bBudgetSettings.
        val expected = BedrockConfig.generativeBudgetSettings
        assertEquals(235000, expected.contextWindowSize, "pre-migration validator budget was 235K")
        assertEquals(12000, expected.maxTokens, "pre-migration validator budget max-output was 12K")
        assertTrue(expected == BedrockConfig.generativeBudgetSettings, "sanity check")
    }

    @Test
    fun `qwen validator pipe name reflects Bedrock routing`()
    {
        val pipe = buildTPipeValidatorPipe(
            instructions = "Qwen 30B validator pipe-name check",
            useQwenCoder30B = true
        )
        // The pipe name should not say "mantle" since this is the Bedrock path.
        assertTrue(
            !pipe.pipeName.contains("mantle", ignoreCase = true) ||
                pipe.pipeName.contains("qwen", ignoreCase = true),
            "Qwen validator pipe name must reflect Bedrock/Qwen routing, but was '${pipe.pipeName}'"
        )
    }
}
