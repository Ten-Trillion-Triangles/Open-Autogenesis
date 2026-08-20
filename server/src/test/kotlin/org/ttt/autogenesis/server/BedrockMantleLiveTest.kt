package org.ttt.autogenesis.server

import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.BedrockMantleEnv
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.ttt.autogenesis.config.ConfigSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live integration test for Bedrock Mantle via GenericOpenAIPipe against
 * the Google Gemma 4 family (E2B + 31B), exercising both raw execute()
 * and the reasoning-pipe builders (mantleAuthorBuilderE2B /
 * mantleAuthorBuilder31B) sourced from globals.BedrockConfig.
 *
 * Gating: runs ONLY when both env vars are set:
 *   - BEDROCK_MANTLE_LIVE_TEST=true (JUnit 5 class-level gate)
 *   - BEDROCK_AWS_CREDENTIALS_FILE (defaults to ~/.aws/credentials) plus
 *     a profile that resolves via BEDROCK_AWS_PROFILE (defaults to
 *     "bedrock", falls back to "default") (per-test assumeTrue)
 *
 * Credentials flow: ~/.aws/credentials -> INI parser -> env vars via
 * BedrockMantleEnv.setAccessKeyId / setSecretAccessKey -> Mantle pipe.
 * No AWS keys appear in source.
 *
 * Region is selected at runtime via BEDROCK_MANTLE_REGION (defaults to
 * us-east-1). Model IDs are read from bedrock.local.properties under
 * the bedrock-mantle.* keys.
 *
 * Run with:
 *   BEDROCK_MANTLE_LIVE_TEST=true \
 *   [BEDROCK_AWS_CREDENTIALS_FILE=~/.aws/credentials] \
 *   [BEDROCK_AWS_PROFILE=bedrock] \
 *   [BEDROCK_MANTLE_REGION=us-east-1] \
 *   ./gradlew :server:test --tests "*BedrockMantleLiveTest"
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_MANTLE_LIVE_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BedrockMantleLiveTest
{
    private companion object
    {
        const val TEST_PROMPT = "Reply with the single word 'pong'."
        const val MAX_TOKENS = 32
    }

    private val modelId4B: String
        get() = ConfigSource.property("bedrock.local.properties", "bedrock-mantle.gemma4ModelId")
    private val modelId31B: String
        get() = ConfigSource.property("bedrock.local.properties", "bedrock-mantle.gemma31ModelId")

    private val profileName: String
        get() = System.getenv("BEDROCK_AWS_PROFILE") ?: "bedrock"

    private val credentialsFile: File
        get()
        {
            val envPath = System.getenv("BEDROCK_AWS_CREDENTIALS_FILE")
            return envPath?.let(::File)
                ?: File(System.getProperty("user.home"), ".aws/credentials")
        }

    /**
     * Loads access/secret from the credentials file's profile and pushes
     * them onto BedrockMantleEnv. Aborts via assumeTrue when the file or
     * profile or any required key is missing, so the test is skipped
     * (not failed) on machines without AWS credentials configured.
     */
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

    @Test
    fun testMantleGemma4B() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val region = resolveRegion()
            val modelId = modelId4B
            val pipe = GenericOpenAIPipe()
                .setBedrockMantle(region, modelId)
                .setMaxTokens(MAX_TOKENS)
                .setTemperature(0.0)
                .init()

            println("Sending Mantle Chat Completions request (region=$region, model=$modelId)...")
            val response = pipe.execute(TEST_PROMPT)
            println("Gemma 4B response: $response")

            assertNotNull(response, "Gemma 4B response must not be null")
            assertTrue(
                response.contains("pong", ignoreCase = true),
                "Expected Gemma 4B response to contain 'pong'. Got: $response"
            )
        }
        finally { clearCredentials() }
    }

    @Test
    fun testMantleGemma31B() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val region = resolveRegion()
            val modelId = modelId31B
            val pipe = GenericOpenAIPipe()
                .setBedrockMantle(region, modelId)
                .setMaxTokens(MAX_TOKENS)
                .setTemperature(0.0)
                .init()

            println("Sending Mantle Chat Completions request (region=$region, model=$modelId)...")
            val response = pipe.execute(TEST_PROMPT)
            println("Gemma 31B response: $response")

            assertNotNull(response, "Gemma 31B response must not be null")
            assertTrue(
                response.contains("pong", ignoreCase = true),
                "Expected Gemma 31B response to contain 'pong'. Got: $response"
            )
        }
        finally { clearCredentials() }
    }

    /**
     * Exercises globals.BedrockConfig.mantleAuthorBuilderE2B end-to-end:
     * the builder should produce a configured Pipe wired with the tuned
     * TruncationSettings and routed to Gemma 4 E2B on Mantle. The
     * reasoning path inside the builder must round-trip a prompt
     * through the configured pipe and produce a non-null response.
     */
    @Test
    fun testMantleAuthorBuilderE2B() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipe = globals.BedrockConfig.mantleAuthorBuilderE2B(
                author = "You are a concise assistant. $TEST_PROMPT",
                showThinking = false
            )

            println("Sending Mantle author-builder request via E2B...")
            val response = pipe.execute(TEST_PROMPT)
            println("MantleAuthorE2B response: $response")

            assertNotNull(response, "mantleAuthorBuilderE2B response must not be null")
            assertTrue(
                response.isNotBlank(),
                "mantleAuthorBuilderE2B response must not be blank"
            )
        }
        finally { clearCredentials() }
    }

    /**
     * Same shape as the E2B case, but for the 31B builder. The 31B model
     * has a 256K context window vs the E2B's 128K; the builder passes
     * the larger maxTokens budget. The Mantle response must round-trip
     * and not be null or blank.
     */
    @Test
    fun testMantleAuthorBuilder31B() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipe = globals.BedrockConfig.mantleAuthorBuilder31B(
                author = "You are a concise assistant. $TEST_PROMPT",
                showThinking = false
            )

            println("Sending Mantle author-builder request via 31B...")
            val response = pipe.execute(TEST_PROMPT)
            println("MantleAuthor31B response: $response")

            assertNotNull(response, "mantleAuthorBuilder31B response must not be null")
            assertTrue(
                response.isNotBlank(),
                "mantleAuthorBuilder31B response must not be blank"
            )
        }
        finally { clearCredentials() }
    }
}
