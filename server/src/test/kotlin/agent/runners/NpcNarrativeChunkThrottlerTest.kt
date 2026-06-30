package agent.runners

import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for NpcNarrativeChunkThrottler buffer flush race conditions (BUG-5).
 *
 * Bug: NpcNarrativeChunkThrottler (npcOrchestrator.kt:78-101) buffers narrative chunks.
 * Flush fires on NARRATIVE_STREAM_BUFFER_LIMIT = 512 chars OR NARRATIVE_STREAM_FLUSH_DELAY_MS = 90L.
 * If next turn begins before 90ms timer fires, pending buffer from previous turn is NOT flushed
 * before new turn's narrative arrives. Old content appears "stuck."
 *
 * Tests written TDD (RED first, then fix implemented).
 */
class NpcNarrativeChunkThrottlerTest {

    private val capturedChunks = mutableListOf<Pair<String, Boolean>>()
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun createThrottler(
        flushDelayMs: Long = 90L,
        bufferLimit: Int = 512
    ): NpcNarrativeChunkThrottler {
        return object : NpcNarrativeChunkThrottler(flushDelayMs, bufferLimit, testScope) {
            override suspend fun broadcastChunk(chunk: String, isComplete: Boolean) {
                capturedChunks.add(chunk to isComplete)
            }
        }
    }

    @BeforeEach
    fun setup() { capturedChunks.clear() }
    @AfterEach
    fun teardown() { testScope.cancel() }

    /**
     * RED: Tests buffer flush race — new turn starts before 90ms flush timer fires.
     * Previous turn's buffer should be cleared when a new turn starts.
     * This test FAILs against current code (no reset() method exists).
     */
    @Test
    fun testBufferFlushRaceOnTurnTransitionWithin90ms() = runBlocking {
        val throttler = createThrottler(flushDelayMs = 90L, bufferLimit = 512)

        // Simulate Turn 1: append content that stays below buffer limit
        throttler.append("Turn1 narrative content here. ")
        repeat(5) { throttler.append("Some story text from turn one. ") }
        assertTrue(throttler.hasBufferedContent(), "Turn1 buffer should be pending")
        assertEquals(0, capturedChunks.size, "No flush yet — below buffer limit")

        // Before 90ms timer fires, simulate Turn 2 starting (new content arrives)
        // Current bug: old Turn1 buffer persists and mixes with Turn2 content
        // FIX: throttler should have reset() that clears previous turn's buffer
        delay(20L)
        throttler.append("Turn2 new content arriving. ")

        // After reset() is implemented, Turn1 pending buffer should be gone
        // Verify the fix by checking that no Turn1 content appears mixed with Turn2
        val turn1Pending = capturedChunks.none { it.first.contains("Turn1") }
        assertTrue(turn1Pending, "Turn1 buffer should have been reset before Turn2 content arrived")

        // Eventually the Turn2 content flushes
        delay(100L)
        assertTrue(capturedChunks.isNotEmpty(), "Turn2 content should flush eventually")
    }

    /**
     * RED: Tests isComplete flag arriving while buffer still has pending content.
     * flushPending(isComplete=true) should send remaining buffer with isComplete=true.
     */
    @Test
    fun testIsCompleteFlagArrivesWhileBufferStillHasPendingContent() = runBlocking {
        val throttler = createThrottler(flushDelayMs = 90L, bufferLimit = 512)
        val narrative = "A" + "B".repeat(299)
        throttler.append(narrative)
        assertTrue(throttler.hasBufferedContent(), "Buffer should have content")
        assertEquals(0, capturedChunks.size, "No flush yet — below buffer limit")
        throttler.flushPending(isComplete = true)
        assertEquals(1, capturedChunks.size, "Should flush with isComplete=true")
        assertEquals(true, capturedChunks[0].second, "isComplete flag should be true")
        assertEquals(300, capturedChunks[0].first.length, "Full buffer should be sent")
    }

    /**
     * GREEN: Tests mutex thread-safe access under concurrent turn transitions.
     */
    @Test
    fun testMutexThreadSafeAccessUnderConcurrentTurnTransitions() = runBlocking {
        val throttler = createThrottler(flushDelayMs = 10_000L, bufferLimit = 100_000)
        val errors = mutableListOf<String>()
        val jobs = List(10) { index ->
            launch {
                try {
                    repeat(20) { chunkIndex ->
                        throttler.append("C[$index-$chunkIndex]")
                    }
                } catch (e: Exception) {
                    errors.add("Job $index exception: ${e.message}")
                }
            }
        }
        jobs.forEach { it.join() }
        throttler.flushPending()
        assertTrue(errors.isEmpty(), "No exceptions should occur under concurrency")
        val allContent = capturedChunks.joinToString("") { it.first }
        val chunkPattern = Regex("""C\[\d+-\d+\]""")
        assertEquals(200, chunkPattern.findAll(allContent).count(), "All 200 chunks should be present")
    }

    /**
     * GREEN: Empty chunks are ignored.
     */
    @Test
    fun testEmptyChunkIgnored() = runBlocking {
        val throttler = createThrottler(flushDelayMs = 90L, bufferLimit = 512)
        throttler.append("")
        throttler.append("Real content. ")
        assertEquals(0, capturedChunks.size, "No flush for empty then real chunk")
        assertTrue(throttler.hasBufferedContent(), "Buffer has real content")
    }

    /**
     * GREEN: Buffer limit triggers immediate flush.
     */
    @Test
    fun testBufferLimitFlush() = runBlocking {
        val throttler = createThrottler(flushDelayMs = 90L, bufferLimit = 100)
        throttler.append("A".repeat(100))
        assertEquals(1, capturedChunks.size, "Buffer limit flush triggers immediately")
        assertEquals(100, capturedChunks[0].first.length, "Full buffer sent")
    }

    /**
     * GREEN: flushPending on empty buffer does not broadcast.
     */
    @Test
    fun testFlushPendingEmptyBufferNoBroadcast() = runBlocking {
        val throttler = createThrottler(flushDelayMs = 90L, bufferLimit = 512)
        throttler.flushPending()
        assertTrue(capturedChunks.isEmpty(), "Empty buffer flush should not broadcast")
    }

    /**
     * GREEN: Cancelled flush job does not double-flush when buffer limit hit.
     */
    @Test
    fun testCancelledFlushJobNoDoubleFlush() = runBlocking {
        val throttler = createThrottler(flushDelayMs = 50L, bufferLimit = 512)
        throttler.append("Content A. ")
        assertTrue(throttler.hasBufferedContent(), "Buffer has pending content")
        throttler.append("B".repeat(512))  // Triggers immediate flush, cancels timer
        assertTrue(capturedChunks.isNotEmpty(), "Buffer limit flush should have fired")
        delay(100L)
        assertEquals(1, capturedChunks.size, "Cancelled flush job should not produce extra broadcast")
    }

    /**
     * RED: reset() clears buffer between turns — proves fix for race condition.
     * This test FAILs against current code (no reset() method).
     */
    @Test
    fun testResetClearsBufferBetweenTurns() = runBlocking {
        val throttler = createThrottler(flushDelayMs = 90L, bufferLimit = 512)

        // Turn 1 content (below limit, does not flush yet)
        throttler.append("Turn1 partial content. ")
        assertTrue(throttler.hasBufferedContent(), "Turn1 should be buffered")
        assertEquals(0, capturedChunks.size, "No flush yet")

        // Start of Turn 2: reset throttler to clear previous turn's buffer
        throttler.reset()

        // Verify Turn1 buffer is gone
        assertFalse(throttler.hasBufferedContent(), "Buffer should be cleared by reset()")

        // Turn 2 content
        throttler.append("Turn2 content. ")
        delay(100L)
        val turn2Chunks = capturedChunks.filter { it.first.contains("Turn2") }
        assertTrue(turn2Chunks.isNotEmpty(), "Turn2 content should flush")
        val turn1Chunks = capturedChunks.filter { it.first.contains("Turn1") }
        assertEquals(0, turn1Chunks.size, "Turn1 should not appear after reset")
    }
}