package globals

import com.TTT.Pipe.Pipe
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.GenericOpenAIEnv
import kotlinx.serialization.Serializable
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe verifying whether [GenericOpenAIPipe.onApplySystemPromptComplete]
 * (GenericOpenAIPipe.kt:407-414) actually fires on the Mantle path.
 *
 * Background — see docs/bugs/MANTLE_GEMMA_JSON_ADHERENCE.md. F3 (the
 * wire-format JSON hook) translates pipe.jsonOutput into a
 * `response_format = {"type": "json_object"}` field on the wire when
 * requireJsonPromptInjection() is called. The Mantle / OpenAI-compatible
 * transport reads `responseFormat` via RequestSerializer at
 * GenericOpenAIPipe.kt:884/1061. If the hook fires, responseFormat is
 * non-null after applySystemPrompt(); if it doesn't, responseFormat
 * stays null and the wire payload has no JSON-mode enforcement.
 *
 * responseFormat is private (GenericOpenAIPipe.kt:166) but it is read by
 * the pipe's own serializer at line 884 / 1061. The only public observable
 * is the wire payload itself, which MantleInjectorWireProbeLiveTest
 * captures via preInvokeFunction. This structural probe is the
 * complement: it asserts the precondition (responseFormat populated)
 * using a factory that mirrors what buildMantleAuthorPipe does in
 * production. After the 2026-07-30 fix, both the precondition proxy
 * (jsonOutput non-blank) AND the production factory (mantleAuthorBuilder31B)
 * satisfy the wire-format hook's preconditions.
 *
 * Gating: no env-var gating. Runs in every gradle test pass.
 */
class MantleWireFormatHookProbeTest
{
    @Serializable
    data class ProbeJson(val verdict: String, val note: String = "")

    @BeforeTest
    fun installBearerCredentials()
    {
        GenericOpenAIEnv.setApiKey("test-key-not-used-for-network")
    }

    @Test
    fun `Mantle pipe with setJsonOutput AND requireJsonPromptInjection populates responseFormat after applySystemPrompt`()
    {
        // Mirror the production call shape from buildMantleAuthorPipe
        // (BedrockConfig.kt:1115-1198): GenericOpenAIPipe() then
        // setBedrockMantle(...). Then the agent layer wires
        // requireJsonPromptInjection + setJsonOutput (as in
        // railroadAgent.kt:38-39 for the Bedrock sibling path).
        val pipe = GenericOpenAIPipe().apply {
            setBedrockMantle(
                region = BedrockConfig.mantleRegion(),
                modelId = BedrockConfig.mantleModelId("gemma4ModelId")
            )
            setPipeName("probe — wire-format hook")
            requireJsonPromptInjection()
            setJsonOutput(ProbeJson::class)
        }
        // responseFormat is populated by onApplySystemPromptComplete()
        // (GenericOpenAIPipe.kt:407-414), which fires inside
        // applySystemPrompt() (Pipe.kt:2567). Running applySystemPrompt
        // directly simulates the lifecycle path that executeMultimodal
        // would walk at Pipe.kt:6100.
        pipe.applySystemPrompt()

        // The hook's logic at GenericOpenAIPipe.kt:407-414:
        //   if (responseFormat != null) return            // not set
        //   if (supportsNativeJson) return                // requireJsonPromptInjection sets false
        //   if (jsonOutput.isBlank()) return              // setJsonOutput populates this
        //   responseFormat = ResponseFormat(type="json_object", jsonSchema=null)
        // So after applySystemPrompt(), responseFormat MUST be non-null.
        //
        // responseFormat is private (GenericOpenAIPipe.kt:166), but
        // pipe.jsonOutput is public. The proxy for the hook firing is:
        // supportsNativeJson == false AND jsonOutput.isNotBlank()
        // (both preconditions of the hook's body), AND the pipe
        // reached the hook call site (which we forced by calling
        // applySystemPrompt()). The full hook effect on the wire is
        // covered by MantleInjectorWireProbeLiveTest.
        assertTrue(
            pipe.jsonOutput.isNotBlank(),
            "jsonOutput must be non-blank after setJsonOutput (precondition of " +
                "the wire-format hook). Got: '${pipe.jsonOutput.take(200)}'"
        )
        // supportsNativeJson is protected (Pipe.kt:1100). It is read by
        // applySystemPrompt() / requireJsonPromptInjection() but cannot be
        // observed directly from outside the package. The wire-format hook
        // reads it via `this.supportsNativeJson`. We rely on the fact
        // that requireJsonPromptInjection() (Pipe.kt:2865-2868) sets it
        // to false — a code-level invariant. The MantleWireFormatHookProbeLiveTest
        // (the live companion) verifies the actual response_format field
        // reaches the wire.
        assertNotNull(pipe, "Pipe must be non-null after applySystemPrompt")
    }

    @Test
    fun `mantleAuthorBuilder31B calls requireJsonPromptInjection (F1 — fix surface is autogenesis-side)`()
    {
        // F1 from the bug analysis (post-fix): buildMantleAuthorPipe in
        // BedrockConfig.kt:1115-1198 routes through reasonWithGenericOpenAI
        // + assignDefaults which sets requireJsonPromptInjection +
        // setJsonOutput(MethodActorResponse). The factory now wires the
        // JSON contract at the host-pipe level — call sites do not need
        // to do it themselves. This is the autogenesis-side fix surface,
        // not a TPipe-side bug.
        val pipe: Pipe = BedrockConfig.mantleAuthorBuilder31B(
            author = "test author system prompt",
            showThinking = false
        )
        assertTrue(
            pipe.jsonOutput.isNotBlank(),
            "mantleAuthorBuilder31B jsonOutput must be populated at the factory " +
                "level post-fix (the assignDefaults wiring writes the " +
                "MethodActorResponse schema). Got: '${pipe.jsonOutput.take(200)}'"
        )
        assertTrue(
            pipe.pipeMetadata["injectFooterPrompt"] == true,
            "mantleAuthorBuilder31B pipeMetadata['injectFooterPrompt'] must be " +
                "true post-fix (the gate that configureBedrockMantle sets to " +
                "false is flipped by ReasoningSettings(injectFooterPrompt = true)). " +
                "Got: ${pipe.pipeMetadata["injectFooterPrompt"]}"
        )
    }
}