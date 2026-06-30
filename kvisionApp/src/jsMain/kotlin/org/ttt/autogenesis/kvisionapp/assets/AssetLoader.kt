package org.ttt.autogenesis.kvisionapp.assets

/**
 * A single asset that can be loaded asynchronously before the main UI is shown.
 *
 * Implementations encapsulate one concrete resource (e.g. an MP3 chunked out of
 * webpack, a remote image, a JSON data file). The [LoadingScreen][ui.LoadingScreen]
 * widget drives an ordered list of [AssetLoader]s via [AssetPipeline] and
 * surfaces aggregate progress to the user.
 *
 * Implementations MUST be safe to invoke from a coroutine and MUST report
 * progress monotonically (0.0..1.0) so the pipeline can sum fractions.
 *
 * Implementations MUST resolve the underlying failure case via [LoadResult.Failed]
 * rather than throwing — [AssetPipeline] catches the [LoadResult] and continues.
 */
interface AssetLoader
{
    /**
     * Stable identifier used in logs and as a [data-testid] suffix. Should be
     * kebab-case, e.g. `main-menu-music`.
     */
    val id: String

    /**
     * Human-readable label shown to the user while the asset is loading,
     * e.g. `Main menu music`. Localizable strings belong to the caller.
     */
    val humanLabel: String

    /**
     * Load the asset, reporting progress through [progress] as a fraction
     * in `0.0..1.0` and a short message (e.g. `Downloading...`).
     *
     * @param progress callback invoked zero or more times on the loading coroutine.
     * @return [LoadResult.Done] on success, [LoadResult.Failed] on terminal failure.
     */
    suspend fun load(progress: (LoadProgress) -> Unit): LoadResult
}

/**
 * Terminal result of a single [AssetLoader.load] call.
 */
sealed interface LoadResult
{
    /** Asset is fully downloaded and decoded (or whatever its terminal state is). */
    object Done : LoadResult

    /** Asset could not be loaded; [reason] is a short human-readable diagnostic. */
    data class Failed(val reason: String) : LoadResult
}

/**
 * Progress event emitted by an [AssetLoader] mid-load.
 *
 * @param fraction monotonically non-decreasing value in `0.0..1.0`.
 * @param message optional short status string; `null` means "no change".
 */
data class LoadProgress(val fraction: Double, val message: String? = null)
