package agent.builders.validateAction

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
 * Structural assertions for the Mantle wiring of the NPC validator.
 *
 * `buildNPCValidator` returns a Pipeline with three pipes (legality checker,
 * rectifier, style reapply). Each must be a [GenericOpenAIPipe] routed through
 * Bedrock Mantle, with a Mantle reasoning pipe via the corresponding
 * Mantle factory. The error-logging branch pipes stay on Bedrock because
 * `buildBranchFailureAgent` is intentionally hardcoded to Bedrock
 * (it logs an `AgentRetry` JSON envelope via the standard Converse API).
 */
class BuildNPCValidatorMantleTest
{
    @BeforeTest
    fun installBearerCredentials()
    {
        GenericOpenAIEnv.setApiKey("test-key-not-used-for-network")
    }

    @Test
    fun `all three NPC validator pipes are GenericOpenAIPipe on Mantle`()
    {
        val pipeline = buildNPCValidator()
        val pipes = pipeline.getPipes()
        assertEquals(3, pipes.size, "Expected 3 pipes in NPC validator pipeline")
        for ((index, pipe) in pipes.withIndex())
        {
            assertIs<GenericOpenAIPipe>(
                pipe,
                "NPC validator pipe[$index] must be GenericOpenAIPipe for Mantle, " +
                    "but was ${pipe::class.simpleName}"
            )
        }
    }

    @Test
    fun `each NPC validator pipe has a Mantle reasoning pipe`()
    {
        val pipeline = buildNPCValidator()
        val pipes = pipeline.getPipes()
        for ((index, pipe) in pipes.withIndex())
        {
            val reasoning = pipe.reasoningPipe
            assertNotNull(reasoning, "NPC validator pipe[$index] must wire a reasoning pipe")
            assertIs<GenericOpenAIPipe>(
                reasoning,
                "NPC validator pipe[$index] reasoning must be GenericOpenAIPipe " +
                    "(mantleStructuredCotBuilder or mantleExplicitCotBuilder), " +
                    "but was ${reasoning::class.simpleName}"
            )
            assertTrue(
                reasoning.pipeName.contains("mantle", ignoreCase = true),
                "NPC validator pipe[$index] reasoning name must reflect Mantle factory " +
                    "output, but was '${reasoning.pipeName}'"
            )
        }
    }

    @Test
    fun `each NPC validator pipe retains a Bedrock error-logging branch pipe`()
    {
        val pipeline = buildNPCValidator()
        val pipes = pipeline.getPipes()
        for ((index, pipe) in pipes.withIndex())
        {
            val branch = pipe.branchPipe
            assertNotNull(branch, "NPC validator pipe[$index] must wire a branch pipe")
            assertIs<BedrockMultimodalPipe>(
                branch,
                "NPC validator pipe[$index] branch must remain on Bedrock " +
                    "(buildBranchFailureAgent is intentionally hardcoded to Bedrock), " +
                    "but was ${branch::class.simpleName}"
            )
        }
    }
}