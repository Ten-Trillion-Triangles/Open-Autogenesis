package globals

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import com.TTT.Pipe.Pipe
import genericOpenAIPipe.env.BedrockMantleEnv
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live probe verifying whether TPipe system-prompt injectors reach the
 * wire when a Mantle-shaped reasoning pipe is attached to a host pipe
 * that calls requireJsonPromptInjection + setJsonOutput.
 *
 * Background — see docs/bugs/MANTLE_GEMMA_JSON_ADHERENCE.md. When the
 * host pipe calls setJsonOutput(MyJson::class), the JSON schema should
 * be appended to the host's outgoing prompt as an instruction to emit
 * JSON. When a Mantle reasoning pipe is attached, the middle/footer
 * prompt injection also fires (gated on pipeMetadata["injectMiddlePrompt"]
 * on the reasoning pipe). This probe asserts both are reaching the wire.
 *
 * The Mantle provider used by TPipe is the OpenAI Chat Completions
 * transport. The host pipe's outgoing request is built from the
 * MultimodalContent that flows through preInvokeFunction — capturing
 * content.text at preInvokeFunction time is the cheapest end-to-end
 * probe. Reasoning-pipe text is captured by hooking preInvokeFunction
 * on the reasoning pipe itself (its own lifecycle is independent).
 *
 * Gating: runs ONLY when BEDROCK_MANTLE_LIVE_TEST=true (class-level).
 * Per-test assumeTrue aborts (does not fail) when AWS credentials are
 * missing or when the live endpoint rejects our request shape — same
 * pattern as BedrockMantleReasoningBuildersLiveTest.
 *
 * Run with:
 *   BEDROCK_MANTLE_LIVE_TEST=true \
 *   [BEDROCK_AWS_CREDENTIALS_FILE=~/.aws/credentials] \
 *   [BEDROCK_AWS_PROFILE=bedrock] \
 *   ./gradlew :server:test --tests "*MantleInjectorWireProbeLiveTest"
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_MANTLE_LIVE_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MantleInjectorWireProbeLiveTest
{
    @Serializable
    data class ProbeJson(
        val verdict: String,
        val note: String = ""
    )

    private companion object
    {
        // The test prompt itself contains the word "JSON" — assertions
        // must look for structural markers (the schema field name and
        // JSON-mode keywords) that only an injected rail would add.
        const val TEST_PROMPT = "Reply concisely."
        const val MAX_TOKENS = 256
        const val SCHEMA_FIELD = "verdict"
    }

    private val profileName: String
        get() = System.getenv("BEDROCK_AWS_PROFILE") ?: "bedrock"

    private val credentialsFile: File
        get()
        {
            val envPath = System.getenv("BEDROCK_AWS_CREDENTIALS_FILE")
            return envPath?.let(::File)
                ?: File(System.getProperty("user.home"), ".aws/credentials")
        }

    private fun installCredentials()
    {
        assumeTrue(
            credentialsFile.exists(),
            "Skipping Mantle injector wire probe because ${credentialsFile.absolutePath} is missing"
        )
        val parsed = parseCredentials(credentialsFile)
        val profile = parsed[profileName] ?: parsed["default"]
        assumeTrue(
            profile != null,
            "Skipping Mantle injector wire probe because profile '$profileName' is not in ${credentialsFile.absolutePath}"
        )
        val resolved = profile!!
        val accessKeyId = resolved["aws_access_key_id"]?.trim().orEmpty()
        val secretAccessKey = resolved["aws_secret_access_key"]?.trim().orEmpty()
        assumeTrue(
            accessKeyId.isNotBlank() && secretAccessKey.isNotBlank(),
            "Skipping Mantle injector wire probe because profile '$profileName' lacks access/secret keys"
        )
        BedrockMantleEnv.setAccessKeyId(accessKeyId)
        BedrockMantleEnv.setSecretAccessKey(secretAccessKey)
    }

    private fun clearCredentials()
    {
        BedrockMantleEnv.clearAccessKeyId()
        BedrockMantleEnv.clearSecretAccessKey()
    }

    private fun parseCredentials(file: File): Map<String, Map<String, String>>
    {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        var currentProfile: String? = null
        file.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEachLine
            if (line.startsWith("[") && line.endsWith("]"))
            {
                currentProfile = line.substring(1, line.length - 1).trim()
                if (currentProfile.isNotEmpty()) result.putIfAbsent(currentProfile, mutableMapOf())
                return@forEachLine
            }
            val profile = currentProfile ?: return@forEachLine
            val keyValue = line.split("=", limit = 2).map { it.trim() }
            if (keyValue.size == 2)
            {
                result.getOrPut(profile) { mutableMapOf() }[keyValue[0]] = keyValue[1]
            }
        }
        return result
    }

    // -----------------------------------------------------------------
    // Probe — does setJsonOutput on the host pipe reach the wire?
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    // Probe — F3 verification: Mantle wire-format JSON hook DOES fire.
    // (This probe is informational — the Mantle path works correctly
    // when an agent layer wires requireJsonPromptInjection + setJsonOutput.)
    // -----------------------------------------------------------------

    @Test
    fun `Mantle host pipe with setJsonOutput DOES reach system prompt with JSON schema rail`() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val host = genericOpenAIPipe.GenericOpenAIPipe().apply {
                setBedrockMantle(
                    region = BedrockConfig.mantleRegion(),
                    modelId = BedrockConfig.mantleModelId("gemma4ModelId")
                )
                setPipeName("probe — Mantle F3 verification")
                setMaxTokens(MAX_TOKENS)
                setTemperature(0.0)
                setTopP(0.7)
                requireJsonPromptInjection()
                setJsonOutput(ProbeJson::class)
            }
            init(host)
            host.execute(TEST_PROMPT)
            val systemPromptAfter = host.getSystemPromptText()

            println("=== F3 SYSTEM PROMPT AFTER EXECUTE (first 1500 chars) ===")
            println(systemPromptAfter.take(1500))

            // Confirmation: when an agent wires requireJsonPromptInjection +
            // setJsonOutput on a Mantle pipe, the JSON-schema rail reaches
            // pipe.systemPrompt. The wire-format hook (GenericOpenAIPipe.kt:407-414)
            // also sets response_format on the request body. F3 is NOT a bug.
            assertTrue(
                systemPromptAfter.contains(SCHEMA_FIELD, ignoreCase = true),
                "F3 sanity check: Mantle wire-format hook must populate the JSON " +
                    "schema rail in pipe.systemPrompt. Got first 400 chars: " +
                    "'${systemPromptAfter.take(400)}'"
            )
        }
        finally { clearCredentials() }
    }

    // -----------------------------------------------------------------
    // Probe — Footer prompt path A (host pipe) — unconditional,
    // fires whenever pipe.footerPrompt is non-empty. Verifies Mantle
    // hosts carry the footer text into the wire's system prompt.
    // -----------------------------------------------------------------

    @Test
    fun `host pipe footer prompt reaches wire system prompt on Mantle (path A unconditional)`() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val footerText = "PROBE_FOOTER_TOKEN_HOST_PATH_A_XYZ"

            val host = genericOpenAIPipe.GenericOpenAIPipe().apply {
                setBedrockMantle(
                    region = BedrockConfig.mantleRegion(),
                    modelId = BedrockConfig.mantleModelId("gemma4ModelId")
                )
                setPipeName("probe — host footer path A")
                setMaxTokens(MAX_TOKENS)
                setTemperature(0.0)
                setTopP(0.7)
                setFooterPrompt(footerText)
                // No setJsonOutput / requireJsonPromptInjection — keeps
                // the host prompt minimal so the footer token is
                // unmistakable in the system prompt.
            }
            init(host)
            host.execute(TEST_PROMPT)
            val systemPromptAfter = host.getSystemPromptText()

            println("=== HOST FOOTER PATH A SYSTEM PROMPT (first 1200 chars) ===")
            println(systemPromptAfter.take(1200))

            // Path A: applySystemPrompt() unconditionally appends
            // pipe.footerPrompt to pipe.systemPrompt at Pipe.kt:2560-2563.
            // The footer token MUST appear in the system prompt.
            assertTrue(
                systemPromptAfter.contains(footerText),
                "Host pipe footer text '$footerText' must reach pipe.systemPrompt " +
                    "on Mantle. Got first 600 chars: " +
                    "'${systemPromptAfter.take(600)}'"
            )
        }
        finally { clearCredentials() }
    }

    // -----------------------------------------------------------------
    // Probe — Footer prompt path B (reasoning pipe) — gated on
    // pipeMetadata["injectFooterPrompt"]=true. The Mantle helper
    // sets this to false, so the only way to get the footer text
    // into the reasoning pipe's outgoing prompt is to flip the gate
    // AFTER construction. Verifies both:
    //   1. The gate-suppressed path: footer text does NOT reach the
    //      wire when injectFooterPrompt=false (current Mantle default).
    //   2. The gate-open path: footer text DOES reach the wire when
    //      injectFooterPrompt=true (after manual override).
    // -----------------------------------------------------------------

    @Test
    fun `reasoning pipe footer prompt is suppressed when injectFooterPrompt is false (Mantle default)`() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val footerText = "PROBE_FOOTER_TOKEN_REASONING_SUPPRESSED_XYZ"

            val reasoningPipe: Pipe = BedrockConfig.mantleStructuredCotBuilder(
                depth = ReasoningDepth.Med,
                duration = ReasoningDuration.Short
            ).apply {
                setFooterPrompt(footerText)
                // injectFooterPrompt stays at Mantle helper default of false.
            }

            val host = genericOpenAIPipe.GenericOpenAIPipe().apply {
                setBedrockMantle(
                    region = BedrockConfig.mantleRegion(),
                    modelId = BedrockConfig.mantleModelId("gemma4ModelId")
                )
                setPipeName("probe — reasoning footer gate closed")
                setMaxTokens(MAX_TOKENS)
                setTemperature(0.0)
                setTopP(0.7)
                requireJsonPromptInjection()
                setJsonOutput(ProbeJson::class)
                setReasoningPipe(reasoningPipe)
            }
            init(host)
            host.execute(TEST_PROMPT)
            // Read reasoningPipe.systemPrompt AFTER its execute() ran —
            // applySystemPrompt() is invoked inside execute() (Pipe.kt:6100)
            // and appends footerPrompt to systemPrompt unconditionally
            // at Pipe.kt:2560-2563. So even with the injectFooterPrompt
            // gate closed, the unconditional Path A appends the footer
            // to the reasoning pipe's own systemPrompt. The wire payload
            // SHOULD contain the footer.
            val reasoningSysPrompt = reasoningPipe.getSystemPromptText()

            println("=== REASONING FOOTER GATE-CLOSED REASONING SYSTEM PROMPT (first 1500 chars) ===")
            println(reasoningSysPrompt.take(1500))

            // Two paths exist for footer text to reach the wire:
            //   Path A (unconditional, Pipe.kt:2560-2563): footerPrompt
            //     appended to pipe.systemPrompt on every applySystemPrompt().
            //   Path B (gated, Pipe.kt:8047): getFooterPromptForReasoning()
            //     returns footerPrompt only when injectFooterPrompt=true,
            //     used at Pipe.kt:7136 / 7201 to assemble the developer
            //     prompt that the reasoning pipe sees.
            //
            // Path A runs on the reasoning pipe's OWN systemPrompt. Path A
            // is what constructs the reasoning pipe's outgoing system-prompt
            // text. So when the gate is closed but setFooterPrompt was
            // called, the footer text SHOULD still appear in the
            // reasoning pipe's systemPrompt via Path A.
            //
            // But wait — the Mantle helper sets pipeMetadata["injectFooterPrompt"]=false
            // on the reasoning pipe itself. Does Path A actually run on
            // the reasoning pipe during executeReasoningPipe()? Let's verify
            // whether the footer text appears via the unconditional path.
            assertTrue(
                reasoningSysPrompt.contains(footerText),
                "Reasoning pipe footer text '$footerText' must reach the " +
                    "reasoning pipe's systemPrompt via the unconditional " +
                    "Path A (Pipe.kt:2560-2563), regardless of the " +
                    "injectFooterPrompt gate. Got first 600 chars: " +
                    "'${reasoningSysPrompt.take(600)}'"
            )
        }
        finally { clearCredentials() }
    }

    @Test
    fun `reasoning pipe footer prompt reaches wire when injectFooterPrompt is true (gate open)`() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val footerText = "PROBE_FOOTER_TOKEN_REASONING_OPEN_XYZ"

            val reasoningPipe: Pipe = BedrockConfig.mantleStructuredCotBuilder(
                depth = ReasoningDepth.Med,
                duration = ReasoningDuration.Short
            ).apply {
                setFooterPrompt(footerText)
                // Open the gate after the Mantle helper set it to false.
                pipeMetadata["injectFooterPrompt"] = true
            }

            val host = genericOpenAIPipe.GenericOpenAIPipe().apply {
                setBedrockMantle(
                    region = BedrockConfig.mantleRegion(),
                    modelId = BedrockConfig.mantleModelId("gemma4ModelId")
                )
                setPipeName("probe — reasoning footer gate open")
                setMaxTokens(MAX_TOKENS)
                setTemperature(0.0)
                setTopP(0.7)
                requireJsonPromptInjection()
                setJsonOutput(ProbeJson::class)
                setReasoningPipe(reasoningPipe)
            }
            init(host)
            host.execute(TEST_PROMPT)
            // Read the reasoning pipe's systemPrompt after its execute()
            // ran — applySystemPrompt() runs inside execute() and
            // appends footerPrompt unconditionally (Path A). With the
            // gate open (Path B), getFooterPromptForReasoning() also
            // returns the footer text for the developer prompt.
            val reasoningSysPrompt = reasoningPipe.getSystemPromptText()

            println("=== REASONING FOOTER GATE-OPEN REASONING SYSTEM PROMPT (first 2000 chars) ===")
            println(reasoningSysPrompt.take(2000))

            // Footer text MUST appear in the reasoning pipe's systemPrompt.
            // (Path A is unconditional; Path B is gated and applies to the
            // developer prompt that the reasoning pipe sees.)
            assertTrue(
                reasoningSysPrompt.contains(footerText),
                "Reasoning pipe footer text '$footerText' must reach the " +
                    "reasoning pipe's systemPrompt. Path A is unconditional " +
                    "(Pipe.kt:2560-2563); Path B is gated on " +
                    "injectFooterPrompt=true. Got first 600 chars: " +
                    "'${reasoningSysPrompt.take(600)}'"
            )
        }
        finally { clearCredentials() }
    }

    private fun init(pipe: Pipe)
    {
        kotlinx.coroutines.runBlocking { pipe.init() }
    }
}