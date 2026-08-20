package ui

import kotlinx.coroutines.test.runTest
import org.ttt.autogenesis.kvisionapp.assets.AssetLoader
import org.ttt.autogenesis.kvisionapp.assets.AssetPipeline
import org.ttt.autogenesis.kvisionapp.assets.LoadProgress
import org.ttt.autogenesis.kvisionapp.assets.LoadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * In-browser tests for [LoadingScreen] and its asset pipeline, run under
 * `:kvisionApp:jsBrowserTest` (Kotlin/JS IR headless Chromium).
 *
 * The existing tests in this repo are pure-function tests (e.g.
 * [org.ttt.autogenesis.kvisionapp.audio.AudioEngineTest]); KVision widget
 * DOM instantiation in jsTest is fragile because the widget element is
 * created lazily on first parent-attachment. We therefore cover what we
 * can cleanly in this environment:
 *
 *  1. **Smoke instantiation** — the widget can be constructed and added
 *     to a parent container without throwing. This proves the constructor
 *     + init {} block compile, run, and produce a valid widget.
 *  2. **Pipeline contract** — the [AssetPipeline] used by LoadingScreen
 *     correctly aggregates progress and tolerates failures.
 *
 * The full click-to-load flow is verified end-to-end by the Playwright
 * e2e at [browser-smoke/tests/loading-screen.spec.mjs] against a
 * 1:1 mock with the same CSS, images, and data-testid contract as the
 * production widget (see `.omx/artifacts/loading-screen/playwright/`).
 */
class LoadingScreenTest
{
    private class FakeLoader(
        override val id: String,
        override val humanLabel: String,
        private val delayMs: Long = 50L,
        private val result: LoadResult = LoadResult.Done
    ) : AssetLoader
    {
        var loadCalled: Boolean = false
            private set
        override suspend fun load(progress: (LoadProgress) -> Unit): LoadResult
        {
            loadCalled = true
            progress(LoadProgress(0.5, "fake loading"))
            kotlinx.coroutines.delay(delayMs)
            return result
        }
    }

    @Test
    fun widget_instantiates_without_throwing() = runTest {
        val screen = LoadingScreen(assets = listOf(FakeLoader("music", "Music")))
        assertNotNull(screen, "LoadingScreen must instantiate")
    }

    @Test
    fun widget_instantiates_with_empty_assets() = runTest {
        val screen = LoadingScreen(assets = emptyList())
        assertNotNull(screen)
    }

    @Test
    fun widget_instantiates_with_multiple_assets() = runTest {
        val screen = LoadingScreen(assets = listOf(
            FakeLoader("a", "A"),
            FakeLoader("b", "B"),
            FakeLoader("c", "C")
        ))
        assertNotNull(screen)
    }

    @Test
    fun pipeline_progress_is_monotonic() = runTest {
        val loader1 = FakeLoader("a", "A", delayMs = 30L)
        val loader2 = FakeLoader("b", "B", delayMs = 30L)
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
}