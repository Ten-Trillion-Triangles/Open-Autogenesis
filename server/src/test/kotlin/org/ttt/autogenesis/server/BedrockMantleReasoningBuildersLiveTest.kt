package org.ttt.autogenesis.server

import genericOpenAIPipe.env.BedrockMantleEnv
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live integration test for the three Bedrock Mantle reasoning builders:
 *   - mantleStructuredCotBuilder  (ReasoningMethod.StructuredCot, depth Med)
 *   - mantleProcessFocusedBuilder (ReasoningMethod.processFocusedCot, depth Low)
 *   - mantleExplicitCotBuilder    (ReasoningMethod.ExplicitCot, depth Low)
 *
 * Each builder is exercised against both Gemma 4 family members on Mantle
 * (E2B = 128K context via `gemma4ModelId`, 31B = 256K via `gemma31ModelId`),
 * producing six end-to-end tests per run.
 *
 * Gating: runs ONLY when BEDROCK_MANTLE_LIVE_TEST=true (class-level).
 * Per-test assumeTrue aborts (does not fail) when AWS credentials are
 * missing — same pattern as BedrockMantleLiveTest.
 *
 * Run with:
 *   BEDROCK_MANTLE_LIVE_TEST=true \
 *   [BEDROCK_AWS_CREDENTIALS_FILE=~/.aws/credentials] \
 *   [BEDROCK_AWS_PROFILE=bedrock] \
 *   [BEDROCK_MANTLE_REGION=us-east-1] \
 *   ./gradlew :server:test --tests "*BedrockMantleReasoningBuildersLiveTest"
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_MANTLE_LIVE_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BedrockMantleReasoningBuildersLiveTest
{
    private companion object
    {
        const val TEST_PROMPT = "Reply with the single word 'pong'."
        const val MAX_TOKENS = 64
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
            "Skipping Mantle reasoning-builder live test because ${credentialsFile.absolutePath} is missing"
        )
        val parsed = parseCredentials(credentialsFile)
        val profile = parsed[profileName] ?: parsed["default"]
        assumeTrue(
            profile != null,
            "Skipping Mantle reasoning-builder live test because profile '$profileName' is not in ${credentialsFile.absolutePath}"
        )
        val resolved = profile!!
        val accessKeyId = resolved["aws_access_key_id"]?.trim().orEmpty()
        val secretAccessKey = resolved["aws_secret_access_key"]?.trim().orEmpty()
        assumeTrue(
            accessKeyId.isNotBlank() && secretAccessKey.isNotBlank(),
            "Skipping Mantle reasoning-builder live test because profile '$profileName' lacks access/secret keys"
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

    // ---- structuredCoT ----

    @Test
    fun testMantleStructuredCotE2B() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipe = globals.BedrockConfig.mantleStructuredCotBuilder(
                modelKey = "gemma4ModelId",
                maxTokens = MAX_TOKENS
            )
            println("Sending Mantle structured-cot request via E2B...")
            val response = pipe.execute(TEST_PROMPT)
            println("MantleStructuredCotE2B response: $response")

            assertNotNull(response, "mantleStructuredCotBuilder (E2B) response must not be null")
            assertTrue(response.isNotBlank(), "mantleStructuredCotBuilder (E2B) response must not be blank")
        }
        finally { clearCredentials() }
    }

    @Test
    fun testMantleStructuredCot31B() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipe = globals.BedrockConfig.mantleStructuredCotBuilder(
                modelKey = "gemma31ModelId",
                maxTokens = MAX_TOKENS
            )
            println("Sending Mantle structured-cot request via 31B...")
            val response = pipe.execute(TEST_PROMPT)
            println("MantleStructuredCot31B response: $response")

            assertNotNull(response, "mantleStructuredCotBuilder (31B) response must not be null")
            assertTrue(response.isNotBlank(), "mantleStructuredCotBuilder (31B) response must not be blank")
        }
        finally { clearCredentials() }
    }

    // ---- processFocusedCoT ----

    @Test
    fun testMantleProcessFocusedE2B() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipe = globals.BedrockConfig.mantleProcessFocusedBuilder(
                modelKey = "gemma4ModelId",
                maxTokens = MAX_TOKENS
            )
            println("Sending Mantle process-focused request via E2B...")
            val response = pipe.execute(TEST_PROMPT)
            println("MantleProcessFocusedE2B response: $response")

            assertNotNull(response, "mantleProcessFocusedBuilder (E2B) response must not be null")
            assertTrue(response.isNotBlank(), "mantleProcessFocusedBuilder (E2B) response must not be blank")
        }
        finally { clearCredentials() }
    }

    @Test
    fun testMantleProcessFocused31B() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipe = globals.BedrockConfig.mantleProcessFocusedBuilder(
                modelKey = "gemma31ModelId",
                maxTokens = MAX_TOKENS
            )
            println("Sending Mantle process-focused request via 31B...")
            val response = pipe.execute(TEST_PROMPT)
            println("MantleProcessFocused31B response: $response")

            assertNotNull(response, "mantleProcessFocusedBuilder (31B) response must not be null")
            assertTrue(response.isNotBlank(), "mantleProcessFocusedBuilder (31B) response must not be blank")
        }
        finally { clearCredentials() }
    }

    // ---- explicitCoT ----

    @Test
    fun testMantleExplicitCotE2B() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipe = globals.BedrockConfig.mantleExplicitCotBuilder(
                modelKey = "gemma4ModelId",
                maxTokens = MAX_TOKENS
            )
            println("Sending Mantle explicit-cot request via E2B...")
            val response = pipe.execute(TEST_PROMPT)
            println("MantleExplicitCotE2B response: $response")

            assertNotNull(response, "mantleExplicitCotBuilder (E2B) response must not be null")
            assertTrue(response.isNotBlank(), "mantleExplicitCotBuilder (E2B) response must not be blank")
        }
        finally { clearCredentials() }
    }

    @Test
    fun testMantleExplicitCot31B() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipe = globals.BedrockConfig.mantleExplicitCotBuilder(
                modelKey = "gemma31ModelId",
                maxTokens = MAX_TOKENS
            )
            println("Sending Mantle explicit-cot request via 31B...")
            val response = pipe.execute(TEST_PROMPT)
            println("MantleExplicitCot31B response: $response")

            assertNotNull(response, "mantleExplicitCotBuilder (31B) response must not be null")
            assertTrue(response.isNotBlank(), "mantleExplicitCotBuilder (31B) response must not be blank")
        }
        finally { clearCredentials() }
    }
}