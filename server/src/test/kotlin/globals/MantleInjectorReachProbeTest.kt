package globals

import com.TTT.Pipe.Pipe
import genericOpenAIPipe.env.GenericOpenAIEnv
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe verifying whether TPipe system-prompt injectors reach the wire
 * for the Mantle / Gemma pipe family.
 *
 * Background — see docs/bugs/MANTLE_GEMMA_JSON_ADHERENCE.md. The Gemma 4
 * cutover caused Mantle-routed pipes to emit prose or empty responses
 * where the call sites expect JSON. The 2026-07-30 fix to
 * [globals.BedrockConfig.buildMantleAuthorPipe] and
 * [globals.BedrockConfig.buildMantleReasoningPipe] routes both factories
 * through [Defaults.reasoning.ReasoningBuilder.reasonWithGenericOpenAI]
 * + `.apply { ... }` so [Defaults.reasoning.ReasoningBuilder.assignDefaults]
 * wires requireJsonPromptInjection, setJsonOutput(MethodActorResponse /
 * StructuredCot / ProcessFocusedResult / ExplicitReasoningDetailed),
 * and the JSON-completion footer prompt.
 *
 * These tests are pure structural assertions — no network. They read
 * the public pipe surface (jsonInput, jsonOutput, pipeMetadata,
 * reasoningPipe) and assert what is THERE. After the 2026-07-30 fix
 * every assertion in this class passes — that is the verification of
 * the fix.
 *
 * Gating: no env-var gating. Runs in every gradle test pass.
 */
class MantleInjectorReachProbeTest
{
    @BeforeTest
    fun installBearerCredentials()
    {
        GenericOpenAIEnv.setApiKey("test-key-not-used-for-network")
    }

    // -----------------------------------------------------------------
    // Probe A — mantleAuthorBuilder31B wires the JSON contract
    // -----------------------------------------------------------------

    @Test
    fun `mantleAuthorBuilder31B calls requireJsonPromptInjection at factory level`()
    {
        // Post-fix: buildMantleAuthorPipe routes through
        // reasonWithGenericOpenAI which calls assignDefaults, which sets
        // requireJsonPromptInjection and setJsonOutput(MethodActorResponse)
        // on the pipe. jsonOutput is no longer blank at the factory level.
        val pipe: Pipe = BedrockConfig.mantleAuthorBuilder31B(
            author = "test author system prompt",
            showThinking = false
        )
        assertTrue(
            pipe.jsonOutput.isNotBlank(),
            "Expected mantleAuthorBuilder31B to populate jsonOutput via " +
                "reasonWithGenericOpenAI.assignDefaults.setJsonOutput(MethodActorResponse). " +
                "Got: '${pipe.jsonOutput.take(200)}'"
        )
        assertTrue(
            pipe.jsonInput.isBlank(),
            "Expected mantleAuthorBuilder31B to leave jsonInput blank (the " +
                "author roleplay factory has no JSON input schema). " +
                "Got: '${pipe.jsonInput.take(200)}'"
        )
    }

    @Test
    fun `mantleAuthorBuilder31B has no reasoning pipe attached`()
    {
        // Post-fix: buildMantleAuthorPipe still does not call setReasoningPipe.
        // The pipe is itself the host roleplay pipe — outer call sites wire
        // it via setReasoningPipe when used as a reasoning chain component.
        val pipe: Pipe = BedrockConfig.mantleAuthorBuilder31B(
            author = "test author system prompt",
            showThinking = true
        )
        assertTrue(
            pipe.reasoningPipe == null,
            "Expected mantleAuthorBuilder31B to return a host pipe with no " +
                "reasoning pipe attached. Got reasoningPipe=${pipe.reasoningPipe}"
        )
    }

    @Test
    fun `mantleAuthorBuilder31B pipeMetadata has injectFooterPrompt set to true (enabling JSON injection)`()
    {
        // Post-fix: ReasoningSettings(injectFooterPrompt = true) flows through
        // assignDefaults which writes pipeMetadata["injectFooterPrompt"] = true.
        // getFooterPromptForReasoning() at Pipe.kt:8044 reads this from the
        // reasoning pipe (or this pipe when used standalone) and emits the
        // JSON-completion footer prompt.
        val pipe: Pipe = BedrockConfig.mantleAuthorBuilder31B(
            author = "test author system prompt",
            showThinking = false
        )
        assertTrue(
            pipe.pipeMetadata["injectMiddlePrompt"] == false,
            "mantleAuthorBuilder31B pipeMetadata['injectMiddlePrompt'] must " +
                "remain false (default ReasoningSettings default; middle-prompt " +
                "injection is not used by RolePlay). " +
                "Got: ${pipe.pipeMetadata["injectMiddlePrompt"]}"
        )
        assertTrue(
            pipe.pipeMetadata["injectFooterPrompt"] == true,
            "mantleAuthorBuilder31B pipeMetadata['injectFooterPrompt'] must be " +
                "true (the fix flips the gate that configureBedrockMantle set to " +
                "false). Got: ${pipe.pipeMetadata["injectFooterPrompt"]}"
        )
    }

    @Test
    fun `mantleAuthorBuilder31B system prompt is wrapped by RolePlay envelope`()
    {
        // Post-fix: assignDefaults at ReasoningBuilder.kt:218-223 wraps the
        // author character in the RolePlay envelope:
        //   targetSystemPrompt = rolePlayPrompt(depth, duration)
        //   targetSystemPrompt += "ROLE PLAY AS THE FOLLOWING CHARACTER: <author>"
        // The pipe's getSystemPromptText() reflects this wrapping.
        val author = "test author system prompt"
        val pipe: Pipe = BedrockConfig.mantleAuthorBuilder31B(
            author = author,
            showThinking = false
        )
        val sys = pipe.getSystemPromptText()
        assertTrue(
            sys.contains(author),
            "System prompt must include the author character verbatim. " +
                "Got len=${sys.length} first200='${sys.take(200)}'"
        )
        assertTrue(
            sys.contains("ROLE PLAY", ignoreCase = true) ||
                sys.contains("role-play", ignoreCase = true) ||
                sys.contains("roleplay", ignoreCase = true),
            "System prompt must include the RolePlay envelope marker. " +
                "Got first200='${sys.take(200)}'"
        )
    }

    // -----------------------------------------------------------------
    // Probe B — mantleStructuredCotBuilder wires the JSON contract
    // -----------------------------------------------------------------

    @Test
    fun `mantleStructuredCotBuilder calls requireJsonPromptInjection at factory level`()
    {
        // Post-fix: buildMantleReasoningPipe routes through
        // reasonWithGenericOpenAI which calls assignDefaults with
        // ReasoningMethod.StructuredCot, which sets
        // setJsonOutput(StructuredCot::class).
        val pipe: Pipe = BedrockConfig.mantleStructuredCotBuilder()
        assertTrue(
            pipe.jsonOutput.isNotBlank(),
            "Expected mantleStructuredCotBuilder to populate jsonOutput via " +
                "reasonWithGenericOpenAI.assignDefaults.setJsonOutput(StructuredCot). " +
                "Got: '${pipe.jsonOutput.take(200)}'"
        )
        assertTrue(
            pipe.jsonInput.isBlank(),
            "Expected mantleStructuredCotBuilder to leave jsonInput blank (the " +
                "StructuredCot factory has no JSON input schema). " +
                "Got: '${pipe.jsonInput.take(200)}'"
        )
    }

    @Test
    fun `mantleStructuredCotBuilder pipeMetadata has injectFooterPrompt set to true (enabling JSON injection)`()
    {
        // Post-fix: ReasoningSettings(injectFooterPrompt = true) flows through
        // assignDefaults, which writes the metadata key.
        val pipe: Pipe = BedrockConfig.mantleStructuredCotBuilder()
        assertTrue(
            pipe.pipeMetadata["injectMiddlePrompt"] == false,
            "mantleStructuredCotBuilder pipeMetadata['injectMiddlePrompt'] must " +
                "remain false (StructuredCot does not use middle-prompt injection). " +
                "Got: ${pipe.pipeMetadata["injectMiddlePrompt"]}"
        )
        assertTrue(
            pipe.pipeMetadata["injectFooterPrompt"] == true,
            "mantleStructuredCotBuilder pipeMetadata['injectFooterPrompt'] must " +
                "be true (the fix flips the gate that configureBedrockMantle set to " +
                "false). Got: ${pipe.pipeMetadata["injectFooterPrompt"]}"
        )
    }

    @Test
    fun `mantleStructuredCotBuilder has no reasoning pipe of its own (it IS a reasoning pipe)`()
    {
        // Post-fix: Mantle reasoning builders are themselves meant to be
        // attached as reasoning pipes. They must not recursively attach
        // another reasoning pipe. Confirm the layered shape.
        val pipe: Pipe = BedrockConfig.mantleStructuredCotBuilder()
        assertTrue(
            pipe.reasoningPipe == null,
            "mantleStructuredCotBuilder must not attach its own reasoningPipe " +
                "(it IS the reasoning pipe). Got reasoningPipe=${pipe.reasoningPipe}"
        )
        assertNotNull(pipe, "mantleStructuredCotBuilder must return a non-null pipe")
    }
}
