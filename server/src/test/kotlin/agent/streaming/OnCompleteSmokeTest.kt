package agent.streaming

import com.TTT.Pipe.Pipe
import com.TTT.Pipe.StreamingCallbackManager
import com.TTT.Pipe.StreamingExecutionMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression test for the Phase-6 WriterAgent streaming-stall class
 * captured in `/tmp/autogenesis-safety-test/findings.md` issues 1, 4, 5, 6.
 *
 * Before TPipe `fa3d88fa` (2026-08-17) added `Pipe.emitStreamEnd()` and the
 * `StreamingCallbackManager` completion-callback API, a streaming pipe that
 * emitted without ever calling its completion signal left downstream consumers
 * (the orchestrator's `awaitAll`, the UI's `isComplete=true` flag, and the
 * AgentWorkStreamDispatcher) blind to whether the LLM had finished.
 *
 * This test pins the contract:
 *
 *  - `emitStreamEnd()` fires every registered completion callback exactly
 *    once.
 *  - Completion callbacks are isolated from chunk-callback state — they
 *    fire even when no chunks were emitted.
 *  - Errors thrown by one completion callback do not block subsequent
 *    completion callbacks.
 *
 * The Autogenesis-side wiring at `AgentWorkStreamStreaming.kt:128-160` adds
 * an `onComplete` callback that calls
 * `AgentWorkStreamDispatcher.notifyPipelineCompleteMany(connectionIds)` so
 * the dispatcher finally flips `isComplete=true` once the LLM has actually
 * finished generating. This test is the unit-level proof that the
 * underlying TPipe API behaves correctly; the Playwright e2e probe in
 * `kvisionApp-e2e/probes/streaming-stall-recovery-probe.mjs` is the
 * live-system proof.
 */
class OnCompleteSmokeTest
{

    /**
     * Mirrors the `publicEmitStreamEnd` exposure in TPipe's
     * `PipeEmitStreamEndTest` so we can drive `emitStreamEnd()` from
     * outside a Pipe subclass without smuggling a subclass through the
     * test harness.
     */
    private class PublicEmitPipe : Pipe()
    {
        override suspend fun generateText(promptInjector: String): String = ""
        override fun truncateModuleContext(): Pipe = this
        suspend fun publicEmitStreamEnd() = emitStreamEnd()
    }

    @Test
    fun `emitStreamEnd fires completion callback exactly once`() = runBlocking {
        val pipe = PublicEmitPipe()
        var completeCount = 0
        pipe.obtainStreamingCallbackManager().addCompleteCallback { completeCount++ }

        pipe.publicEmitStreamEnd()

        assertEquals(1, completeCount, "emitStreamEnd must fire completion callbacks exactly once")
    }

    @Test
    fun `emitStreamEnd fires multiple completion callbacks in registration order`() = runBlocking {
        val pipe = PublicEmitPipe()
        val callOrder = mutableListOf<String>()
        pipe.obtainStreamingCallbackManager().addCompleteCallback { callOrder.add("first") }
        pipe.obtainStreamingCallbackManager().addCompleteCallback { callOrder.add("second") }
        pipe.obtainStreamingCallbackManager().addCompleteCallback { callOrder.add("third") }

        pipe.publicEmitStreamEnd()

        assertEquals(listOf("first", "second", "third"), callOrder)
    }

    @Test
    fun `emitStreamEnd does not fire chunk callbacks`() = runBlocking {
        val pipe = PublicEmitPipe()
        var chunkCount = 0
        var completeCount = 0
        pipe.obtainStreamingCallbackManager().addCallback { chunkCount++ }
        pipe.obtainStreamingCallbackManager().addCompleteCallback { completeCount++ }

        pipe.publicEmitStreamEnd()

        assertEquals(0, chunkCount, "emitStreamEnd must not invoke chunk callbacks")
        assertEquals(1, completeCount)
    }

    @Test
    fun `emitStreamEnd with no subscribers is a no-op`() = runBlocking {
        val pipe = PublicEmitPipe()
        // Must not throw when no completion callbacks are registered.
        pipe.publicEmitStreamEnd()
    }

    @Test
    fun `completion callback errors do not block subsequent completion callbacks`() = runBlocking {
        val manager = StreamingCallbackManager(
            executionMode = StreamingExecutionMode.SEQUENTIAL,
            onError = { _, _ -> /* swallow per TPipe contract */ }
        )
        var firstTracked = 0
        var secondTracked = 0
        manager.addCompleteCallback {
            firstTracked++
            throw IllegalStateException("boom")
        }
        manager.addCompleteCallback { secondTracked++ }

        manager.emitCompleteToAll()

        assertEquals(1, firstTracked, "throwing callback must still have run")
        assertEquals(1, secondTracked, "subsequent callback must still fire after one throws")
    }

    /**
     * The Autogenesis-side wiring pattern: register both a chunk callback
     * (for live UI updates) and a completion callback (for the
     * `isComplete=true` flip). After the LLM finishes, both must have
     * fired; before the completion callback fires, the AgentWorkStream
     * window sees chunks but never sees completion — the symptom of the
     * pre-fix streaming-stall class.
     */
    @Test
    fun `chunk callbacks fire during stream, completion callback fires at stream end`() = runBlocking {
        val pipe = PublicEmitPipe()
        val manager = pipe.obtainStreamingCallbackManager()
        val chunks = mutableListOf<String>()
        var completes = 0
        manager.addCallback { chunk -> chunks.add(chunk) }
        manager.addCompleteCallback { completes++ }

        manager.emitToAll("alpha")
        manager.emitToAll("beta")
        assertTrue(completes == 0, "completes must not fire before emitCompleteToAll")
        assertEquals(listOf("alpha", "beta"), chunks)

        pipe.publicEmitStreamEnd()

        assertEquals(2, chunks.size)
        assertEquals(1, completes)
    }
}
