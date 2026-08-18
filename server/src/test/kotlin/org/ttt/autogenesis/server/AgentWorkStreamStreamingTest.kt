package org.ttt.autogenesis.server

import com.TTT.Pipeline.Pipeline
import genericOpenAIPipe.GenericOpenAIPipe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the consumer factory wires streaming callbacks on both Bedrock and
 * Mantle pipes. Before the refactor, Mantle pipes were silently dropped because
 * `configureBedrockStreaming` early-returned on non-Bedrock pipes.
 *
 * The 2026-08-02 duplication regression: when a Mantle parent has a Mantle
 * reasoning child, the explicit recursion at `AgentWorkStreamStreaming.kt:107`
 * registered a second, distinct lambda on the reasoning pipe on top of the
 * one TPipe's `propagateStreamingCallback` already wired. Both lambdas stayed
 * subscribed because `StreamingCallbackManager` dedups by reference identity.
 * Every Mantle delta emitted by the reasoning pipe then fanned out twice
 * to the dispatcher, producing the "II must must" / "ApplyingApplying" pattern.
 *
 * The regression tests below pin that contract. They must continue to pass
 * after the fix; if anyone reintroduces the recursion, they fail.
 */
class AgentWorkStreamStreamingTest
{
    @AfterEach
    fun cleanupSubscribers()
    {
        // Each test subscribes a synthetic connection. Leaving them subscribed
        // across tests would leak the periodic flush coroutine and skew the
        // observedAppendCounts map. Unsubscribe explicitly.
        AgentWorkStreamDispatcher.unsubscribe("conn-1")
        AgentWorkStreamDispatcher.unsubscribe("conn-nested")
    }

    @Test
    fun configureStreamingWiresCallbackOnGenericOpenAIPipe()
    {
        val pipe = GenericOpenAIPipe()
        val pipeline = Pipeline().apply { add(pipe) }

        streamPipelineOutputToAgentWorkBuffer(listOf("conn-1"), pipeline)

        val manager = pipe.obtainStreamingCallbackManager()
        assertTrue(
            manager.getCallbacks().isNotEmpty(),
            "Mantle pipe should have a streaming callback registered after factory wiring"
        )
    }

    @Test
    fun configureStreamingDoesNotThrowOnEmptyPipeline()
    {
        // An empty pipeline must be silently skipped — no exception.
        val pipeline = Pipeline()
        streamPipelineOutputToAgentWorkBuffer(listOf("conn-1"), pipeline)
    }

    /**
     * Pins the parent + reasoning wiring contract. Both pipes must end up with
     * exactly one streaming callback after the factory runs. TPipe's
     * `propagateStreamingCallback` walks descendants at lambda-add time using
     * a visited pipeId set, so the reasoning pipe receives one callback from
     * propagation. The factory's own recursion into reasoningPipe is what
     * used to add a second one — without the recursion, this stays at one.
     */
    @Test
    fun nestedMantleReasoningPipeHasExactlyOneCallback()
    {
        val parent = GenericOpenAIPipe().setPipeName("parent")
        val reasoning = GenericOpenAIPipe().setPipeName("reasoning")
        parent.setReasoningPipe(reasoning)
        val pipeline = Pipeline().apply { add(parent) }

        streamPipelineOutputToAgentWorkBuffer(listOf("conn-nested"), pipeline)

        assertEquals(
            1,
            parent.obtainStreamingCallbackManager().callbackCount(),
            "Mantle parent should have exactly one callback after factory wiring"
        )
        assertEquals(
            1,
            reasoning.obtainStreamingCallbackManager().callbackCount(),
            "Mantle reasoning child should have exactly one callback (TPipe propagation only)"
        )
    }

    /**
     * End-to-end pin on "one dispatcher append per emit" for a nested Mantle
     * reasoning pipe. Uses the test-only `observedChunkCountFor` accessor on
     * the dispatcher (incremented inside the same `synchronized(lock)` block
     * as the buffer append) to assert that one `emitToAll` on the reasoning
     * pipe's manager produces exactly one dispatcher append.
     *
     * Before the fix this test reports countAfter == countBefore + 2.
     */
    @Test
    fun streamingDeltaFromNestedMantleReasoningPipeAppendsOnce()
    {
        val parent = GenericOpenAIPipe().setPipeName("parent-e2e")
        val reasoning = GenericOpenAIPipe().setPipeName("reasoning-e2e")
        parent.setReasoningPipe(reasoning)
        val pipeline = Pipeline().apply { add(parent) }

        AgentWorkStreamDispatcher.subscribe("conn-nested")
        streamPipelineOutputToAgentWorkBuffer(listOf("conn-nested"), pipeline)

        val before = AgentWorkStreamDispatcher.observedChunkCountFor("conn-nested")
        runBlocking { reasoning.obtainStreamingCallbackManager().emitToAll("token-x") }
        val after = AgentWorkStreamDispatcher.observedChunkCountFor("conn-nested")

        assertEquals(
            before + 1,
            after,
            "One emit from the reasoning pipe must produce exactly one dispatcher append"
        )
    }
}
