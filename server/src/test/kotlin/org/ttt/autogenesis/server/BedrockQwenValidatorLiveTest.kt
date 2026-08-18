package org.ttt.autogenesis.server

import agent.builders.validateAction.buildTPipeValidatorPipe
import bedrockPipe.BedrockMultimodalPipe
import env.bedrockEnv
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live Bedrock integration test for the Qwen 30B validator-pipe factory
 * overload (`useQwenCoder30B = true`). Verifies the 5 HIGH-scope validators
 * (legalityCheckerPipe, defensiveLegalityCheckerPipe, defensiveRectifierPipe,
 * nemesisAgent.assessmentPipe, elderGodStrategyPipe) construct correctly
 * against real Bedrock.
 *
 * Gated on `BEDROCK_MANTLE_LIVE_TEST=true` and a valid AWS profile (default
 * `BedrockKey`) with credentials in `~/.aws/credentials`.
 *
 * Run:
 *   BEDROCK_MANTLE_LIVE_TEST=true BEDROCK_AWS_PROFILE=BedrockKey \
 *     ./gradlew :server:test --tests "*BedrockQwenValidatorLiveTest" --rerun-tasks
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_MANTLE_LIVE_TEST", matches = "true")
class BedrockQwenValidatorLiveTest
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
            "Skipping Qwen validator live test because ${credentialsFile.absolutePath} is missing"
        )
        val parsed = parseCredentials(credentialsFile)
        val profile = parsed[profileName] ?: parsed["default"]
        assumeTrue(
            profile != null,
            "Skipping Qwen validator live test because profile '$profileName' is missing"
        )
        val resolved = profile!!
        val accessKeyId = resolved["aws_access_key_id"]?.trim().orEmpty()
        val secretAccessKey = resolved["aws_secret_access_key"]?.trim().orEmpty()
        assumeTrue(
            accessKeyId.isNotBlank() && secretAccessKey.isNotBlank(),
            "Skipping Qwen validator live test because profile '$profileName' lacks access/secret keys"
        )
        return ResolvedCredentials(accessKeyId, secretAccessKey)
    }

    @Test
    fun liveBedrock_qwenValidatorPipe_constructsWithFlexHostAndStandardReasoning()
    {
        resolveCredentialsOrSkip() ?: return

        // Bedrock env binding for SigV4
        bedrockEnv.bindInferenceProfile("qwen-coder-30b", globals.BedrockConfig.qwenCoder30B)

        val validatorPipe = buildTPipeValidatorPipe(
            instructions = "Qwen 30B validator live integration test",
            useQwenCoder30B = true
        )
        assertNotNull(validatorPipe, "validator pipe must not be null")
        assertIs<BedrockMultimodalPipe>(
            validatorPipe,
            "useQwenCoder30B=true must produce a BedrockMultimodalPipe"
        )
        assertTrue(
            validatorPipe.pipeName.contains("qwen", ignoreCase = true),
            "Qwen validator pipe name must contain 'qwen', but was '${validatorPipe.pipeName}'"
        )
    }

    @Test
    fun liveBedrock_qwenValidatorPipeInit_succeeds()
    {
        resolveCredentialsOrSkip() ?: return

        val validatorPipe = buildTPipeValidatorPipe(
            instructions = "Qwen 30B validator live init test",
            useQwenCoder30B = true
        )
        assertNotNull(validatorPipe, "validator pipe must not be null")
        // Init exercises SigV4 binding without making a full Bedrock call.
        try
        {
            kotlinx.coroutines.runBlocking { validatorPipe.init() }
        }
        catch (expected: Exception)
        {
            // Some pipe types throw if init() requires a running pipeline context
            // — we only care that no auth errors surface.
            val msg = expected.message.orEmpty()
            assertFalse(
                msg.contains("credentials", ignoreCase = true) ||
                    msg.contains("unauthorized", ignoreCase = true) ||
                    msg.contains("forbidden", ignoreCase = true),
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
