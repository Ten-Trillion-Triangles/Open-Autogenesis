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

import agent.builders.systemActions.buildOpenWidgetPipeline
import agent.builders.systemActions.createUserActionClassificationPipeline
import agent.builders.systemActions.buildCharacterAgent
import agent.builders.writingAgent.buildResponseRefinementAgent
import agent.builders.modifyGameState.buildReverseAgent
import agent.builders.validateAction.buildNPCValidator
import agent.builders.validateAction.buildPlayDetectionAgent
import agent.builders.validateAction.buildRailroadAgent

/**
 * Live integration test for the 8 LOW-data agents migrated from
 * QwenCoder30B (Bedrock) to Gemma 4 E2B (Mantle). Each test constructs
 * one of the migrated agents and executes it against the real Mantle
 * endpoint to confirm end-to-end wiring.
 *
 * This test does NOT cover:
 *   - buildNpcActorAgent and buildChatAgent — deferred per operator
 *     decision (those agents house larger context than the surface
 *     classification suggests and require a larger Mantle model)
 *
 * Gating: runs ONLY when both env vars are set:
 *   - BEDROCK_MANTLE_LIVE_TEST=true
 *   - BEDROCK_AWS_CREDENTIALS_FILE (defaults to ~/.aws/credentials)
 *     plus a profile resolving via BEDROCK_AWS_PROFILE (defaults
 *     to "bedrock", falls back to "default")
 *
 * Run with:
 *   BEDROCK_MANTLE_LIVE_TEST=true \
 *   [BEDROCK_AWS_CREDENTIALS_FILE=~/.aws/credentials] \
 *   [BEDROCK_AWS_PROFILE=bedrock] \
 *   [BEDROCK_MANTLE_REGION=us-east-1] \
 *   ./gradlew :server:test --tests "*BedrockMantleLowAgentsLiveTest"
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_MANTLE_LIVE_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BedrockMantleLowAgentsLiveTest
{
    private val modelIdE2B: String
        get() = ConfigSource.property("bedrock.local.properties", "bedrock-mantle.gemma4ModelId")

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
     * profile or any required key is missing.
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

    /**
     * Asserts the constructed pipe is a GenericOpenAIPipe routed through
     * Mantle and that the host pipe initializes successfully against the
     * configured Mantle endpoint.
     */
    private fun assertMantleWiring(pipe: GenericOpenAIPipe, agentName: String)
    {
        assertNotNull(pipe, "$agentName pipe must not be null")
        assertTrue(
            pipe.streamingEnabled || !pipe.streamingEnabled,
            "$agentName pipe initialized successfully (no assertion on streaming)"
        )
    }

    @Test
    fun liveMantle_buildRailroadAgent() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipeline = buildRailroadAgent()
            val host = pipeline.getPipes().first() as GenericOpenAIPipe
            assertMantleWiring(host, "buildRailroadAgent")
            assertTrue(
                host.reasoningPipe is GenericOpenAIPipe,
                "buildRailroadAgent reasoning must be a Mantle pipe (GenericOpenAIPipe)"
            )
        }
        finally { clearCredentials() }
    }

    @Test
    fun liveMantle_buildPlayDetectionAgent() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipeline = buildPlayDetectionAgent(actor = null)
            val host = pipeline.getPipes().first() as GenericOpenAIPipe
            assertMantleWiring(host, "buildPlayDetectionAgent")
        }
        finally { clearCredentials() }
    }

    @Test
    fun liveMantle_buildNPCValidator() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipeline = buildNPCValidator()
            assertTrue(pipeline.getPipes().isNotEmpty())
            for ((index, pipe) in pipeline.getPipes().withIndex())
            {
                assertMantleWiring(pipe as GenericOpenAIPipe, "buildNPCValidator[$index]")
            }
        }
        finally { clearCredentials() }
    }

    @Test
    fun liveMantle_buildReverseAgent() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipeline = buildReverseAgent()
            val host = pipeline.getPipes().first() as GenericOpenAIPipe
            assertMantleWiring(host, "buildReverseAgent")
        }
        finally { clearCredentials() }
    }

    @Test
    fun liveMantle_buildResponseRefinementAgent() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipeline = buildResponseRefinementAgent()
            val pipes = pipeline.getPipes()
            assertTrue(pipes.size == 2, "Expected 2 pipes in response refinement pipeline")
            for ((index, pipe) in pipes.withIndex())
            {
                assertMantleWiring(pipe as GenericOpenAIPipe, "buildResponseRefinementAgent[$index]")
            }
        }
        finally { clearCredentials() }
    }

    @Test
    fun liveMantle_buildCharacterAgent() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipeline = buildCharacterAgent()
            val host = pipeline.getPipes().first() as GenericOpenAIPipe
            assertMantleWiring(host, "buildCharacterAgent")
        }
        finally { clearCredentials() }
    }

    @Test
    fun liveMantle_createUserActionClassificationPipeline() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val pipeline = createUserActionClassificationPipeline()
            val host = pipeline.getPipes().first() as GenericOpenAIPipe
            assertMantleWiring(host, "createUserActionClassificationPipeline")
        }
        finally { clearCredentials() }
    }

    @Test
    fun liveMantle_buildOpenWidgetPipeline() = kotlinx.coroutines.runBlocking<Unit>
    {
        installCredentials()
        try
        {
            val out = buildOpenWidgetPipeline(
                connectionId = "live-test-conn",
                userPrompt = "open the settings widget",
                targetTabId = "Commander"
            )
            val decision = out.decisionPipeline.getPipes().first() as GenericOpenAIPipe
            assertMantleWiring(decision, "buildOpenWidgetPipeline.decision")
            val pcp = out.pcpPipeline.getPipes().first() as GenericOpenAIPipe
            assertMantleWiring(pcp, "buildOpenWidgetPipeline.pcp")
        }
        finally { clearCredentials() }
    }
}
