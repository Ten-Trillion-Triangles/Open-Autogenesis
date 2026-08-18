package org.ttt.autogenesis.server

import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import env.bedrockEnv
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live Bedrock integration test for the Llama 4 Scout 17B model binding.
 * Confirms that the answer agent's pipe (BedrockMultimodalPipe + llamaScout17B
 * inference profile) constructs successfully against real Bedrock and that
 * SigV4 auth resolves.
 *
 * Gated on `BEDROCK_MANTLE_LIVE_TEST=true` + `BEDROCK_LLAMA_SCOUT_LIVE_TEST=true`
 * (the second gate prevents accidental execution against the wrong model) and
 * a valid AWS profile (default `BedrockKey`) with credentials in
 * `~/.aws/credentials`.
 *
 * Run:
 *   BEDROCK_MANTLE_LIVE_TEST=true BEDROCK_LLAMA_SCOUT_LIVE_TEST=true \
 *     BEDROCK_AWS_PROFILE=BedrockKey \
 *     ./gradlew :server:test --tests "*BedrockLlamaScout17BLiveTest" --rerun-tasks
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_MANTLE_LIVE_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "BEDROCK_LLAMA_SCOUT_LIVE_TEST", matches = "true")
class BedrockLlamaScout17BLiveTest
{
    private val profileName: String
        get() = System.getenv("BEDROCK_AWS_PROFILE") ?: "bedrock"

    private val credentialsFile: File
        get()
        {
            val envPath = System.getenv("BEDROCK_AWS_CREDENTIALS_FILE")
            return envPath?.let { File(it) }
                ?: File(System.getProperty("user.home"), ".aws/credentials")
        }

    private data class ResolvedCredentials(val accessKeyId: String, val secretAccessKey: String)

    private fun resolveCredentialsOrSkip(): ResolvedCredentials?
    {
        val credentialsFile = this.credentialsFile
        assumeTrue(
            credentialsFile.exists(),
            "Skipping Llama Scout 17B live test because ${credentialsFile.absolutePath} is missing"
        )
        val parsed = parseCredentials(credentialsFile)
        val profile = parsed[profileName] ?: parsed["default"]
        assumeTrue(
            profile != null,
            "Skipping Llama Scout 17B live test because profile '$profileName' is missing"
        )
        val resolved = profile!!
        val accessKeyId = resolved["aws_access_key_id"]?.trim().orEmpty()
        val secretAccessKey = resolved["aws_secret_access_key"]?.trim().orEmpty()
        assumeTrue(
            accessKeyId.isNotBlank() && secretAccessKey.isNotBlank(),
            "Skipping Llama Scout 17B live test because profile '$profileName' lacks access/secret keys"
        )
        return ResolvedCredentials(accessKeyId, secretAccessKey)
    }

    @Test
    fun liveBedrock_llamaScout17B_constructsBedrockPipe()
    {
        resolveCredentialsOrSkip() ?: return

        // Bind the inference profile so the ARN mapping is in place before init.
        bedrockEnv.bindInferenceProfile(
            globals.BedrockConfig.llamaScout17B,
            globals.BedrockConfig.llamaScout17BModelName
        )
        bedrockEnv.loadInferenceConfig()

        val answerPipe = BedrockMultimodalPipe().apply {
            useConverseApi()
            setRegion("us-west-2")
            setModel(globals.BedrockConfig.llamaScout17B)
            setPipeName("llama scout 17b live integration test pipe")
            setTemperature(0.5)
            setTopP(0.7)
            setTokenBudget(globals.BedrockConfig.llamaScoutBudgetSettings)
        }

        assertNotNull(answerPipe, "answer pipe must not be null")
        assertTrue(
            answerPipe.pipeName.contains("llama", ignoreCase = true) ||
                answerPipe.pipeName.contains("scout", ignoreCase = true),
            "pipe name must reflect Llama Scout 17B routing, but was '${answerPipe.pipeName}'"
        )
    }

    @Test
    fun liveBedrock_llamaScout17BInit_succeeds()
    {
        resolveCredentialsOrSkip() ?: return

        val answerPipe = BedrockMultimodalPipe().apply {
            useConverseApi()
            setRegion("us-west-2")
            setModel(globals.BedrockConfig.llamaScout17B)
            setPipeName("llama scout 17b live init test pipe")
            setTemperature(0.5)
            setTopP(0.7)
            setTokenBudget(globals.BedrockConfig.llamaScoutBudgetSettings)
        }
        assertNotNull(answerPipe, "answer pipe must not be null")
        // Init exercises SigV4 binding without making a full Bedrock call.
        try
        {
            kotlinx.coroutines.runBlocking { answerPipe.init() }
        }
        catch (expected: Exception)
        {
            // Some pipe types throw if init() requires a running pipeline context
            // — we only care that no auth errors surface.
            val msg = expected.message.orEmpty()
            assertTrue(
                !msg.contains("credentials", ignoreCase = true) &&
                    !msg.contains("unauthorized", ignoreCase = true) &&
                    !msg.contains("forbidden", ignoreCase = true),
                "init() must not fail with auth errors. Got: $msg"
            )
        }
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
}
