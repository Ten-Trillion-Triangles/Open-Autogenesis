package org.ttt.autogenesis.server

import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.BedrockMantleEnv
import genericOpenAIPipe.env.GenericOpenAIEnv
import org.ttt.autogenesis.config.ConfigSource
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live Mantle integration test for `g31bBudgetSettings` migration.
 * Gated on `BEDROCK_MANTLE_LIVE_TEST=true` + `BEDROCK_G31B_LIVE_TEST=true` so
 * it does NOT run by default — the second gate prevents accidental
 * execution against the E2B model when only E2B is configured.
 *
 * Run:
 *   BEDROCK_MANTLE_LIVE_TEST=true BEDROCK_G31B_LIVE_TEST=true \
 *     BEDROCK_AWS_PROFILE=BedrockKey \
 *     ./gradlew :server:test --tests "*BedrockMantleG31bLiveTest" --rerun-tasks
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_MANTLE_LIVE_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "BEDROCK_G31B_LIVE_TEST", matches = "true")
class BedrockMantleG31bLiveTest
{
    private val modelId31B: String
        get() = ConfigSource.property("bedrock.local.properties", "bedrock-mantle.gemma31ModelId")

    private val profileName: String
        get() = System.getenv("BEDROCK_AWS_PROFILE") ?: "bedrock"

    private val credentialsFile: java.io.File
        get()
        {
            val envPath = System.getenv("BEDROCK_AWS_CREDENTIALS_FILE")
            return envPath?.let { java.io.File(it) }
                ?: java.io.File(System.getProperty("user.home"), ".aws/credentials")
        }

    @Suppress("RedundantNullableReturnType", "UNUSED_PARAMETER")
    private fun installCredentials()
    {
        assumeTrue(
            credentialsFile.exists(),
            "Skipping Mantle g31b live test because ${credentialsFile.absolutePath} is missing"
        )
        val parsed = parseCredentials(credentialsFile)
        val profile = parsed[profileName] ?: parsed["default"]
        assumeTrue(
            profile != null,
            "Skipping Mantle g31b live test because profile '$profileName' is not in ${credentialsFile.absolutePath}"
        )
        val resolved = profile!!
        val accessKeyId = resolved["aws_access_key_id"]?.trim().orEmpty()
        val secretAccessKey = resolved["aws_secret_access_key"]?.trim().orEmpty()
        assumeTrue(
            accessKeyId.isNotBlank() && secretAccessKey.isNotBlank(),
            "Skipping Mantle g31b live test because profile '$profileName' lacks access/secret keys"
        )
        BedrockMantleEnv.setAccessKeyId(accessKeyId)
        BedrockMantleEnv.setSecretAccessKey(secretAccessKey)
    }

    private fun clearCredentials()
    {
        BedrockMantleEnv.clearAccessKeyId()
        BedrockMantleEnv.clearSecretAccessKey()
    }

    private fun parseCredentials(file: java.io.File): Map<String, Map<String, String>>
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
    fun liveMantle_g31b_init_succeeds() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipe = GenericOpenAIPipe()
                .setBedrockMantle(
                    region = resolveRegion(),
                    modelId = modelId31B
                )
                .setTokenBudget(globals.BedrockConfig.g31bBudgetSettings)
                .setMaxTokens(8000)
                .setTemperature(0.0)
                .init()

            assertNotNull(pipe, "Mantle 31B pipe must construct successfully")
            assertTrue(
                pipe is GenericOpenAIPipe,
                "pipe must be a GenericOpenAIPipe"
            )

            val response = pipe.execute("Confirm you are operating. Reply with exactly 'pong'.")
            assertNotNull(response, "Mantle 31B response must not be null")
            assertTrue(
                response.contains("pong", ignoreCase = true),
                "Expected Mantle 31B response to contain 'pong'. Got: $response"
            )
        }
        finally { clearCredentials() }
    }
}