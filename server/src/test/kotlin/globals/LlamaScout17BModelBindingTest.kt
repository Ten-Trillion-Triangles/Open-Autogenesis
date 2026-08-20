package globals

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression-pinning tests for the Llama 4 Scout 17B Bedrock binding
 * (`us.meta.llama4-scout-17b-instruct-v1:0`). Asserts the constants, the
 * token budget shape, and the reasoning-pipe factory exist in
 * BedrockConfig.kt after this migration.
 */
@Ignore("Requires bedrock.llamaScout17B key in ~/.autogenesis/config/bedrock.properties — skip in environments without AWS Bedrock credentials")
class LlamaScout17BModelBindingTest
{
    @Test
    fun `llamaScout17B constant resolves to the cross-region inference ID`()
    {
        assertEquals(
            "us.meta.llama4-scout-17b-instruct-v1:0",
            BedrockConfig.llamaScout17B,
            "llamaScout17B must be the cross-region (US) inference ID — " +
                "this is the inference profile ID we'll bind in BedrockConfig.kt " +
                "and the autogenesis-conf override file"
        )
    }

    @Test
    fun `llamaScout17BModelName accessor is non-blank`()
    {
        // The actual ARN value comes from ~/.autogenesis/config/bedrock.properties
        // under the `bedrock.llamaScout17B` key. We can't assert the ARN value
        // directly because that's per-environment — but we can assert the
        // accessor returns a non-blank string (which means the ConfigSource
        // resolved the key successfully).
        val resolved = BedrockConfig.llamaScout17BModelName
        assertNotNull(resolved, "llamaScout17BModelName must resolve")
        assertTrue(
            resolved.isNotBlank(),
            "llamaScout17BModelName must be a non-blank ARN string from ConfigSource"
        )
    }

    @Test
    fun `llamaScoutBudgetSettings matches the model card's Bedrock ceiling`()
    {
        // Per AWS Bedrock Llama 4 Scout 17B model card: 10M native context
        // window (3.5M supported on Bedrock), 8K max output.
        assertEquals(8000, BedrockConfig.llamaScoutBudgetSettings.maxTokens)
        assertEquals(3500000, BedrockConfig.llamaScoutBudgetSettings.contextWindowSize)
    }

    @Test
    fun `bedrockLlamaStructuredCotBuilder factory exists`()
    {
        // The factory is a wrapper over structuredCotBuilder(model = llamaScout17B).
        // We assert it produces a non-null pipe configured for the Llama Scout
        // model. The Pipe abstract type doesn't expose getModelName() directly,
        // so we verify the factory exists by producing a non-null pipe and
        // confirming the function reference exists.
        val pipe = BedrockConfig.bedrockLlamaStructuredCotBuilder(
            depthLevel = Defaults.reasoning.ReasoningDepth.Med,
            durationLevel = Defaults.reasoning.ReasoningDuration.Short
        )
        assertNotNull(pipe, "reasoning pipe must not be null")
        // Sanity-check: factory must produce a pipe — the model string is fixed
        // at factory-time via structuredCotBuilder(model = llamaScout17B) so we
        // can't introspect from outside. The plan pins this via the live test.
    }
}
