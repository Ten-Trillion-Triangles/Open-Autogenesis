package agent.builders.validateAction

import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.GenericOpenAIEnv
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Structural assertions for the Mantle wiring of the railroad detection agent.
 *
 * The migration target: `buildRailroadAgent` returns a Pipeline whose primary
 * pipe is a [GenericOpenAIPipe] wired to the Bedrock Mantle endpoint via
 * [GenericOpenAIPipe.setBedrockMantle]. The reasoning pipe must also be a
 * Mantle pipe (the `BedrockConfig.mantleStructuredCotBuilder` factory output).
 * The branch pipe (qwenCoder30B retry) stays on Bedrock because qwenCoder30B
 * uses the Converse API surface not exposed via Mantle.
 *
 * These tests assert only the public observable surface — pipe type and pipe
 * name — because [GenericOpenAIPipe.bedrockMantleAuth] and [Pipe.model] are
 * not accessible from outside the package. The type assertion on the host
 * pipe is sufficient to verify the migration because `BedrockConfig`'s
 * Mantle factories are the only path that produces a `GenericOpenAIPipe`
 * for these agents; the assertion that the reasoning pipe is also a
 * `GenericOpenAIPipe` pins the `mantleStructuredCotBuilder` call. Network
 * verification lives in `BedrockMantleLowAgentsLiveTest`.
 */
class BuildRailroadAgentMantleTest
{
    /**
     * Mantle pipe init() checks for credentials. Without AWS SigV4 credentials
     * in the test environment, the Mantle auth falls back to bearer mode and
     * requires an API key. Install a dummy bearer key before each test so the
     * factory's init() call inside `buildMantleReasoningPipe` doesn't throw.
     */
    @BeforeTest
    fun installBearerCredentials()
    {
        GenericOpenAIEnv.setApiKey("test-key-not-used-for-network")
    }

    @Test
    fun `host pipe is GenericOpenAIPipe not BedrockMultimodalPipe`()
    {
        val pipeline = buildRailroadAgent()
        val host = pipeline.getPipes().first()
        assertIs<GenericOpenAIPipe>(
            host,
            "buildRailroadAgent host pipe must be GenericOpenAIPipe for Mantle, " +
                "but was ${host::class.simpleName}"
        )
    }

    @Test
    fun `reasoning pipe is GenericOpenAIPipe routed through mantleStructuredCotBuilder`()
    {
        val pipeline = buildRailroadAgent()
        val host = pipeline.getPipes().first()
        val reasoning = host.reasoningPipe
        assertNotNull(reasoning, "buildRailroadAgent must wire a reasoning pipe")
        assertIs<GenericOpenAIPipe>(
            reasoning,
            "buildRailroadAgent reasoning pipe must be GenericOpenAIPipe " +
                "(mantleStructuredCotBuilder), but was ${reasoning::class.simpleName}"
        )
    }

    @Test
    fun `reasoning pipe name carries mantle factory signature`()
    {
        val pipeline = buildRailroadAgent()
        val host = pipeline.getPipes().first()
        val reasoning = host.reasoningPipe
        assertNotNull(reasoning, "buildRailroadAgent must wire a reasoning pipe")
        assertTrue(
            reasoning.pipeName.contains("mantle", ignoreCase = true),
            "Reasoning pipe name must reflect Mantle factory output, " +
                "but was '${reasoning.pipeName}'"
        )
    }

    @Test
    fun `branch pipe migrated to Mantle 31B for refusal fallback`()
    {
        val pipeline = buildRailroadAgent()
        val host = pipeline.getPipes().first()
        val branch = host.branchPipe
        assertNotNull(branch, "buildRailroadAgent must wire a branch pipe")
        assertIs<GenericOpenAIPipe>(
            branch,
            "buildRailroadAgent branch pipe must migrate to Mantle (refusal-fallback " +
                "pipe was retired from Bedrock in the qwen cutover), but was ${branch::class.simpleName}"
        )
    }
}
