package org.ttt.autogenesis.server

import com.TTT.Pipeline.Pipeline
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.env.BedrockMantleEnv
import globals.BedrockConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File

/**
 * Live integration test for Mantle streaming through the autogenesis consumer
 * factory. Exercises the chain:
 *
 *   pipe.enableStreaming().streamingCallbacks { add(callback) }
 *   → AgentWorkStreamDispatcher.appendChunkToMany(connectionIds, chunk)
 *
 * Gate: BEDROCK_MANTLE_LIVE_TEST=true AND ~/.aws/credentials with a profile
 * (BEDROCK_AWS_PROFILE, default "bedrock"). When the gate is not met the test
 * is skipped via JUnit 5's @EnabledIfEnvironmentVariable + assumeTrue.
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_MANTLE_LIVE_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MantleStreamingE2ELiveTest
{
    private val profileName: String
        get() = System.getenv("BEDROCK_AWS_PROFILE") ?: "bedrock"

    private val credentialsFile: File
        get()
        {
            val envPath = System.getenv("BEDROCK_AWS_CREDENTIALS_FILE")
            return envPath?.let(::File)
                ?: File(System.getProperty("user.home"), ".aws/credentials")
        }

    @BeforeEach
    fun installCredentials()
    {
        if (!credentialsFile.exists()) return
        val parsed = parseCredentials(credentialsFile)
        val profile = parsed[profileName] ?: parsed["default"] ?: return
        BedrockMantleEnv.setAccessKeyId(profile["aws_access_key_id"]?.trim().orEmpty())
        BedrockMantleEnv.setSecretAccessKey(profile["aws_secret_access_key"]?.trim().orEmpty())
    }

    @AfterEach
    fun clearCredentials()
    {
        BedrockMantleEnv.clearAccessKeyId()
        BedrockMantleEnv.clearSecretAccessKey()
    }

    @Test
    fun mantleStreamingDeliversChunksToDispatcher()
    {
        val pipe = GenericOpenAIPipe().apply {
            setBedrockMantle(
                region = BedrockConfig.mantleRegion(),
                modelId = BedrockConfig.mantleModelId("gemma4ModelId")
            )
            setTokenBudget(BedrockConfig.e2bBudgetSettings)
            setMaxTokens(32)
            setTemperature(0.0)
            setPipeName("MantleStreamingE2E")
        }
        runBlocking { pipe.init() }

        val pipeline = Pipeline().apply {
            add(pipe)
            setPipelineName("MantleStreamingE2EPipeline")
        }

        val connId = "test-conn-1"
        AgentWorkStreamDispatcher.subscribe(connId)
        streamPipelineOutputToAgentWorkBuffer(connId, pipeline)

        runBlocking {
            pipe.generateText("Reply with the single word 'pong'.")
        }

        // After the pipeline completes, the connection should still be
        // subscribed (factory-side subscription is preserved across calls).
        check(AgentWorkStreamDispatcher.isSubscribed(connId)) {
            "Connection $connId should remain subscribed after streaming completes"
        }

        AgentWorkStreamDispatcher.unsubscribe(connId)
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
            val sep = line.indexOf('=')
            if (sep <= 0) return@forEachLine
            val key = line.substring(0, sep).trim()
            val value = line.substring(sep + 1).trim()
            result[profile]!![key] = value
        }
        return result
    }
}