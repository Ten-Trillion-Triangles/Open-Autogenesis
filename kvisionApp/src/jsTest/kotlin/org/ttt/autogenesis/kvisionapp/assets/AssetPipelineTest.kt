package org.ttt.autogenesis.kvisionapp.assets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [AssetPipeline].
 *
 * The pipeline is the aggregation engine that drives the [ui.LoadingScreen]
 * progress bar. These tests pin the contract that:
 *
 *  - the aggregate fraction is monotonically non-decreasing,
 *  - an empty pipeline completes immediately at 1.0,
 *  - a failed loader does not abort the pipeline,
 *  - successful loaders are reported in the result.
 */
class AssetPipelineTest
{
    /** A fake loader that reports a single [progressFraction] then [result]. */
    private class FakeLoader(
        override val id: String,
        override val humanLabel: String,
        private val progressFraction: Double,
        private val result: LoadResult
    ) : AssetLoader
    {
        var loadCalled: Boolean = false
            private set
        override suspend fun load(progress: (LoadProgress) -> Unit): LoadResult
        {
            loadCalled = true
            progress(LoadProgress(progressFraction, "fake"))
            return result
        }
    }

    @Test
    fun emptyPipeline_completesAtOneAndReportsNothingFailed() = runTest {
        val pipeline = AssetPipeline(emptyList())
        val seen = mutableListOf<Double>()
        val result = pipeline.run { seen.add(it) }
        assertEquals(1.0, result.totalFraction, 0.0001)
        assertEquals(0, result.failed.size)
        assertEquals(0, result.succeeded.size)
        // Empty pipeline still emits a single 1.0 progress event.
        assertEquals(listOf(1.0), seen)
    }

    @Test
    fun singleLoader_reachesOneAndIsReportedSucceeded() = runTest {
        val loader = FakeLoader("a", "A", 1.0, LoadResult.Done)
        val pipeline = AssetPipeline(listOf(loader))
        val result = pipeline.run { }
        assertEquals(1.0, result.totalFraction, 0.0001)
        assertEquals(listOf("a"), result.succeeded)
        assertEquals(0, result.failed.size)
        assertTrue(loader.loadCalled)
    }

    @Test
    fun failedLoader_doesNotAbortPipeline() = runTest {
        val success = FakeLoader("ok", "OK", 1.0, LoadResult.Done)
        val failure = FakeLoader("bad", "BAD", 0.5, LoadResult.Failed("decode error"))
        val success2 = FakeLoader("ok2", "OK2", 1.0, LoadResult.Done)
        val pipeline = AssetPipeline(listOf(success, failure, success2))
        val result = pipeline.run { }
        assertEquals(1.0, result.totalFraction, 0.0001)
        assertEquals(listOf("ok", "ok2"), result.succeeded)
        assertEquals(1, result.failed.size)
        val failedEntry = result.failed[0]
        assertEquals("bad", failedEntry.component1())
        assertEquals("decode error", failedEntry.component2())
    }

    @Test
    fun progressIsMonotonicallyNonDecreasing() = runTest {
        val loader1 = FakeLoader("a", "A", 0.5, LoadResult.Done)
        val loader2 = FakeLoader("b", "B", 0.5, LoadResult.Done)
        val pipeline = AssetPipeline(listOf(loader1, loader2))
        val seen = mutableListOf<Double>()
        pipeline.run { seen.add(it) }
        for(i in 1 until seen.size)
        {
            assertTrue(
                seen[i] >= seen[i - 1] - 0.0001,
                "Progress went backwards at index $i: ${seen[i - 1]} -> ${seen[i]}"
            )
        }
        assertEquals(1.0, seen.last(), 0.0001)
    }

    @Test
    fun progressSamplesAreInUnitRange() = runTest {
        val loader1 = FakeLoader("a", "A", 0.5, LoadResult.Done)
        val loader2 = FakeLoader("b", "B", 0.5, LoadResult.Done)
        val pipeline = AssetPipeline(listOf(loader1, loader2))
        val seen = mutableListOf<Double>()
        pipeline.run { seen.add(it) }
        for(v in seen)
        {
            assertTrue(v in 0.0..1.0, "Sample $v out of [0, 1]")
        }
        assertEquals(1.0, seen.last(), 0.0001)
    }

    @Test
    fun thrownExceptionInLoader_becomesLoadResultFailed() = runTest {
        val throwing = object : AssetLoader {
            override val id: String = "boom"
            override val humanLabel: String = "BOOM"
            override suspend fun load(progress: (LoadProgress) -> Unit): LoadResult
            {
                progress(LoadProgress(0.2, "starting"))
                throw RuntimeException("kaboom")
            }
        }
        val pipeline = AssetPipeline(listOf(throwing))
        val result = pipeline.run { }
        assertEquals(1.0, result.totalFraction, 0.0001)
        assertEquals(0, result.succeeded.size)
        assertEquals(1, result.failed.size)
        val failedEntry = result.failed[0]
        assertEquals("boom", failedEntry.component1())
        assertEquals("kaboom", failedEntry.component2())
    }
}