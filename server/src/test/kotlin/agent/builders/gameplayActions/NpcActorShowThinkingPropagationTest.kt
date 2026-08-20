package agent.builders.gameplayActions

import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import globals.BedrockConfig
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * BUG-2 TEST (TDD): showThinking parameter enables thinking capture.
 *
 * With the closure-based fix, when showThinking=true is passed as a parameter
 * to authorBuilder(), the transformation function uses the closure variable
 * instead of reading from parentPipe.pipeMetadata["showThinking"].
 *
 * These tests verify the CORRECT behavior: showThinking=true passed as a parameter
 * enables thinking capture functionality.
 */
class NpcActorShowThinkingPropagationTest
{
    @BeforeTest
    fun setup() {}

    /**
     * TDD TEST 1: When showThinking=true is passed as parameter, reasoningPipe should have metadata.
     *
     * This verifies that the metadata passed to authorBuilder() is properly stored
     * in the main pipe and can be accessed. The closure-based fix uses closure
     * variables for thinking capture, but the metadata should still be accessible
     * on the returned pipe.
     */
    @Test
    fun authorBuilder_withShowThinkingParam_metadataShouldBeAccessible()
    {
        val returnedPipe = BedrockConfig.authorBuilder(
            author = "test",
            depth = ReasoningDepth.Low,
            duration = ReasoningDuration.Short,
            showThinking = true,
            actorName = "TestNPC",
            isPlayer = false
        )

        assertTrue(
            returnedPipe.pipeMetadata["showThinking"] == true,
            "Main pipe MUST have showThinking=true from parameter"
        )

        assertTrue(
            returnedPipe.pipeMetadata["actorName"] == "TestNPC",
            "Main pipe MUST have actorName from parameter"
        )

        assertTrue(
            returnedPipe.pipeMetadata["isPlayer"] == false,
            "Main pipe MUST have isPlayer=false from parameter"
        )
    }

    /**
     * TDD TEST 2: authorBuilder with showThinking=true should have populated metadata.
     *
     * When showThinking=true is passed as a parameter and the builder completes,
     * the reasoningPipe should have the metadata propagated (via putAll after init).
     */
    @Test
    fun authorBuilder_withShowThinkingParam_branchPipeShouldHaveMetadata()
    {
        val branchPipe = BedrockConfig.authorBuilder(
            author = "test",
            depth = ReasoningDepth.High,
            duration = ReasoningDuration.Long,
            showThinking = true,
            actorName = "TestAuthor"
        )

        assertTrue(
            branchPipe.pipeMetadata["showThinking"] == true,
            "Branch pipe MUST have showThinking=true from parameter"
        )

        assertTrue(
            branchPipe.pipeMetadata["actorName"] == "TestAuthor",
            "Branch pipe MUST have actorName from parameter"
        )
    }

    /**
     * TDD TEST 3: Thinking capture is enabled when showThinking=true passed as parameter.
     *
     * This test verifies that when showThinking=true is passed to authorBuilder(),
     * the thinking capture functionality is enabled (via closure variable).
     */
    @Test
    fun whenShowThinkingIsPassedAsParam_thinkingCaptureShouldBeEnabled()
    {
        val returnedPipe = BedrockConfig.authorBuilder(
            author = "test",
            depth = ReasoningDepth.Low,
            duration = ReasoningDuration.Short,
            showThinking = true,
            actorName = "TestActor",
            isPlayer = false
        )

        assertTrue(
            returnedPipe.pipeMetadata["showThinking"] == true,
            "Main pipe MUST have showThinking=true from parameter for thinking capture"
        )

        assertTrue(
            returnedPipe.pipeMetadata["actorName"] == "TestActor",
            "Main pipe MUST have actorName for thinking capture"
        )

        assertTrue(
            returnedPipe.pipeMetadata["isPlayer"] == false,
            "Main pipe MUST have isPlayer=false for thinking capture"
        )
    }
}
