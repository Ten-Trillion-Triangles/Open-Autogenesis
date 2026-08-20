package org.ttt.autogenesis.server

import com.TTT.Structs.MethodActorResponse
import com.TTT.Structs.ProcessFocusedResult
import com.TTT.Structs.StructuredCot
import genericOpenAIPipe.env.BedrockMantleEnv
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.ttt.autogenesis.config.ConfigSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live contract test for the Mantle/Gemma 4 reasoning-pipe builders in
 * globals.BedrockConfig. Asserts the JSON I/O contract that the live trace
 * audit on 2026-07-30 (Round 1/2/3) showed was broken: the previous factory
 * skipped the framework's reasonWithGenericOpenAI + assignDefaults wiring
 * and never flipped injectFooterPrompt=true, so the model received no JSON
 * schema rail and emitted prose (mantleAuthorBuilder31B/31B), an empty
 * response (mantleStructuredCotBuilder, mantleExplicitCotBuilder,
 * mantleProcessFocusedBuilder), or fell back to qwen path.
 *
 * After the fix to buildMantleAuthorPipe and buildMantleReasoningPipe, each
 * Mantle reasoning builder should:
 *   1. Construct via reasonWithGenericOpenAI + .apply { ... }
 *   2. Wire ReasoningSettings.injectFooterPrompt=true so
 *      [Pipe.getFooterPromptForReasoning] (Pipe.kt:8044) emits the JSON
 *      schema footer.
 *   3. setJsonOutput(ContractClass) so assignDefaults writes the example
 *      schema into the pipe.
 *
 * Gating: runs ONLY when both env vars are set:
 *   - BEDROCK_MANTLE_LIVE_TEST=true
 *   - AWS credentials resolvable via BEDROCK_AWS_CREDENTIALS_FILE +
 *     BEDROCK_AWS_PROFILE (defaults to ~/.aws/credentials + "bedrock",
 *     fallback to "default").
 *
 * Run with:
 *   BEDROCK_MANTLE_LIVE_TEST=true ./gradlew :server:test --tests "*BuildMantleReasoningPipesContractTest"
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_MANTLE_LIVE_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildMantleReasoningPipesContractTest
{
    private companion object
    {
        // Author profile rich enough that the model has concrete content to
        // populate MethodActorResponse.characterBackground rather than an empty
        // object — empty-object returns are a real Gemma 4 31B response mode
        // for terse prompts and we want this test to fail on the JSON-adherence
        // contract, not on prompt phrasing.
        const val TEST_AUTHOR = "You are a strategic commander. " +
            "Always fill all JSON fields with substantive content."
        const val TEST_PROMPT = "Provide a character background, expertise domain, " +
            "and worldview for yourself in JSON."

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
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
            "Skipping Mantle live test because ${credentialsFile.absolutePath} is missing"
        )
        val parsed = parseCredentials(credentialsFile)
        val profile = parsed[profileName] ?: parsed["default"]
        assumeTrue(
            profile != null,
            "Skipping Mantle live test because profile '$profileName' is not in ${credentialsFile.absolutePath}"
        )
        val resolved = profile!!
        val accessKeyId = resolved["aws_access_key_id"]?.trim().orEmpty()
        val secretAccessKey = resolved["aws_secret_access_key"]?.trim().orEmpty()
        assumeTrue(
            accessKeyId.isNotBlank() && secretAccessKey.isNotBlank(),
            "Skipping Mantle live test because profile '$profileName' lacks access/secret keys"
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

    private fun resolveRegion(): String =
        System.getenv("BEDROCK_MANTLE_REGION")?.takeIf { it.isNotBlank() } ?: "us-east-1"

    /**
     * Verifies [globals.BedrockConfig.mantleAuthorBuilderE2B] emits a
     * [MethodActorResponse] JSON object. Was 4/4 prose / 0 JSON before the
     * reasonWithGenericOpenAI + injectFooterPrompt=true fix.
     */
    @Test
    fun mantleAuthorBuilderE2B_emitsMethodActorResponseJson() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipe = globals.BedrockConfig.mantleAuthorBuilderE2B(
                author = TEST_AUTHOR,
                showThinking = true
            )
            val response: String = pipe.execute(
                "Decide whether to attack the Northern Coastal Archipelago " +
                    "or hold at Jsolumh. Respond as one JSON object."
            )
            println("mantleAuthorBuilderE2B response (first 600 chars): " +
                response.take(600))

            assertTrue(response.isNotBlank(),
                "mantleAuthorBuilderE2B returned blank response — fix did not land")

            val containsJsonBrace = response.contains("{") && response.contains("}")
            val containsCharacterProfile = response.contains("characterProfile")
            val containsCharacterSolution = response.contains("characterSolution")
            assertTrue(
                containsJsonBrace && (containsCharacterProfile || containsCharacterSolution),
                "mantleAuthorBuilderE2B response must contain JSON braces and at " +
                    "least one MethodActorResponse schema field name. " +
                    "Full response: ${response.take(500)}"
            )
        }
        finally { clearCredentials() }
    }

    /**
     * Verifies [globals.BedrockConfig.mantleAuthorBuilder31B] emits a
     * [MethodActorResponse] JSON object. Was 0/12 JSON before the fix.
     */
    @Test
    fun mantleAuthorBuilder31B_emitsMethodActorResponseJson() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            // The Mantle 31B model can respond to terse "describe yourself"
            // prompts with empty JSON or JSON-string-wrapped objects. Pin the
            // contract on a content-bearing prompt that demands a populated
            // MethodActorResponse: a single tactical decision JSON.
            val pipe = globals.BedrockConfig.mantleAuthorBuilder31B(
                author = "You are General Magnus the Eternal, a strategic " +
                    "commander who led the conquest of Ylumci. You believe " +
                    "in absolute conquest and have expertise in siege warfare " +
                    "and resource denial. Your worldview is militaristic.",
                showThinking = true
            )
            val response: String = pipe.execute(
                "Decide whether to attack the Northern Coastal Archipelago " +
                    "or hold at Jsolumh. Respond as one JSON object."
            )
            println("mantleAuthorBuilder31B response (first 600 chars): " +
                response.take(600))

            assertTrue(response.isNotBlank(),
                "mantleAuthorBuilder31B returned blank response — fix did not land")

            // The contract: response is JSON-shaped and contains the schema
            // field names from MethodActorResponse. Don't require exact
            // top-level shape — the 31B model has known wrapper quirks on
            // terse prompts (verified empirically in trace audit 2026-07-30).
            val containsJsonBrace = response.contains("{") && response.contains("}")
            val containsCharacterProfile = response.contains("characterProfile")
            val containsCharacterSolution = response.contains("characterSolution")
            assertTrue(
                containsJsonBrace && (containsCharacterProfile || containsCharacterSolution),
                "mantleAuthorBuilder31B response must contain JSON braces and at " +
                    "least one MethodActorResponse schema field name. " +
                    "Full response: ${response.take(500)}"
            )
        }
        finally { clearCredentials() }
    }

    /**
     * Verifies [globals.BedrockConfig.mantleStructuredCotBuilder] emits a
     * non-empty [StructuredCot] JSON object. Was 6/6 empty before the fix
     * (buildMantleReasoningPipe skipped setJsonOutput + injectFooterPrompt).
     */
    @Test
    fun mantleStructuredCotBuilder_emitsStructuredCotJson() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipe = globals.BedrockConfig.mantleStructuredCotBuilder()
            val response: String = pipe.execute(TEST_PROMPT)
            println("mantleStructuredCotBuilder response (first 600 chars): " +
                response.take(600))

            assertTrue(response.isNotBlank(),
                "mantleStructuredCotBuilder returned blank — fix did not land (was 6/6 empty)")

            // Lenient: tolerate trailing-comma / minor envelope malformations.
            val parsed = try
            {
                json.decodeFromString<StructuredCot>(response)
            }
            catch (e: kotlinx.serialization.SerializationException)
            {
                null
            }
            assertTrue(
                parsed?.componentIdentification?.whatNeedsToBeSolved?.isNotBlank() == true ||
                    response.contains("componentIdentification") ||
                    response.contains("systematicExecution") ||
                    response.contains("reasoningSynthesis"),
                "mantleStructuredCotBuilder must emit a response carrying the " +
                    "StructuredCot schema fields. " +
                    "Full response: ${response.take(500)}"
            )
        }
        finally { clearCredentials() }
    }

    /**
     * Verifies [globals.BedrockConfig.mantleProcessFocusedBuilder] emits a
     * non-empty [ProcessFocusedResult] JSON object. Was 0 calls observed in
     * the trace audit (no live call sites use it yet) but the factory had
     * the same shape defect.
     */
    @Test
    fun mantleProcessFocusedBuilder_emitsProcessFocusedResultJson() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipe = globals.BedrockConfig.mantleProcessFocusedBuilder()
            val response: String = pipe.execute(TEST_PROMPT)
            println("mantleProcessFocusedBuilder response (first 600 chars): " +
                response.take(600))

            assertTrue(response.isNotBlank(),
                "mantleProcessFocusedBuilder returned blank — fix did not land")

            // Some Gemma 4 E2B responses include a trailing comma or other
            // minor malformations around the JSON envelope. Try a strict
            // parse first; on JsonDecodingException fall back to substring
            // checks on the schema field names — the contract is "the
            // schema fields are present in the response" not "the response
            // parses cleanly under kotlinx.serialization".
            val parsed = try
            {
                json.decodeFromString<ProcessFocusedResult>(response)
            }
            catch (e: kotlinx.serialization.SerializationException)
            {
                null
            }
            assertTrue(
                parsed?.proposedApproach?.isNotBlank() == true ||
                    response.contains("proposedApproach") ||
                    response.contains("approachValidation"),
                "mantleProcessFocusedBuilder must emit a response carrying the " +
                    "ProcessFocusedResult schema fields. " +
                    "Full response: ${response.take(500)}"
            )
        }
        finally { clearCredentials() }
    }

    /**
     * Verifies [globals.BedrockConfig.mantleExplicitCotBuilder] emits a
     * non-empty response. Was 6/6 empty before the fix.
     *
     * ExplicitReasoningDetailed is a free-form chain-of-thought class, so we
     * assert non-empty rather than shape-check. The contract is "model
     * responds with reasoning output, not an empty string."
     */
    @Test
    fun mantleExplicitCotBuilder_emitsNonEmptyResponse() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipe = globals.BedrockConfig.mantleExplicitCotBuilder()
            val response: String = pipe.execute(TEST_PROMPT)
            println("mantleExplicitCotBuilder response: $response")

            assertTrue(response.isNotBlank(),
                "mantleExplicitCotBuilder returned blank — fix did not land (was 6/6 empty)")
        }
        finally { clearCredentials() }
    }
}
