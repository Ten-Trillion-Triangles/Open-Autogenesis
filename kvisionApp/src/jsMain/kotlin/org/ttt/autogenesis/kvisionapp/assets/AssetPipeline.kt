package org.ttt.autogenesis.kvisionapp.assets

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

private fun Double.format3(): String = (kotlin.math.floor(this * 1000.0) / 1000.0).toString()

/**
 * Sequences a list of [AssetLoader]s and aggregates their progress into a
 * single `0.0..1.0` total for the [LoadingScreen][ui.LoadingScreen] widget.
 *
 * Aggregation is per-loader equal weight: with N loaders, each loader
 * contributes `1.0 / N` of the total fraction. A loader's [LoadProgress] is
 * linearly mapped into its share (`loaderFraction * (1.0 / N)`).
 *
 * ## Failure tolerance
 *
 * If a loader returns [LoadResult.Failed] the pipeline logs the failure and
 * continues with the remaining loaders. The pipeline completes when every
 * loader has either succeeded or failed — the UI is never blocked by a
 * single bad asset.
 *
 * @param loaders ordered list of asset loaders to run in sequence.
 */
class AssetPipeline(private val loaders: List<AssetLoader>)
{
    /**
     * Snapshot of pipeline state after [run] completes.
     *
     * @param totalFraction always `1.0` after a successful run.
     * @param succeeded list of loader ids that returned [LoadResult.Done].
     * @param failed list of `(loaderId, reason)` pairs for failed loaders.
     */
    data class Result(
        val totalFraction: Double,
        val succeeded: List<String>,
        val failed: List<Pair<String, String>>
    )

    /**
     * Run every loader in [loaders] sequentially, emitting aggregate progress
     * through [onProgress] on the calling coroutine.
     *
     * @param onProgress invoked with the new aggregate fraction after each
     *   loader's progress event. The fraction is monotonically non-decreasing.
     * @return a [Result] summarizing per-loader outcomes.
     */
    suspend fun run(onProgress: (Double) -> Unit): Result
    {
        Logger.info(LogCategory.NETWORK, "AssetPipeline: run START, ${loaders.size} loader(s), ids=${loaders.map { it.id }}")
        if(loaders.isEmpty())
        {
            Logger.info(LogCategory.NETWORK, "AssetPipeline: empty loader list, emitting single 1.0 progress event")
            onProgress(1.0)
            return Result(1.0, emptyList(), emptyList())
        }

        val perLoaderShare = 1.0 / loaders.size
        var completedLoaders = 0
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        val previousAggregate = 0.0
        onProgress(previousAggregate)
        Logger.debug(LogCategory.NETWORK, "AssetPipeline: per-loader share=${perLoaderShare.format3()}")

        for((idx, loader) in loaders.withIndex())
        {
            Logger.info(LogCategory.NETWORK, "AssetPipeline: [${idx + 1}/${loaders.size}] starting loader id='${loader.id}' label='${loader.humanLabel}'")
            var lastLoaderFraction = 0.0
            val outcome = try
            {
                loader.load { progress ->
                    // Clamp to [0, 1] to defend against buggy loaders that report
                    // out-of-range values.
                    val clamped = progress.fraction.coerceIn(0.0, 1.0)
                    lastLoaderFraction = clamped
                    val aggregate = (completedLoaders * perLoaderShare) + (clamped * perLoaderShare)
                    val pct = (aggregate * 100.0).toInt()
                    Logger.debug(
                        LogCategory.NETWORK,
                        "AssetPipeline: loader '${loader.id}' progress fraction=${clamped.format3()} msg='${progress.message}' -> aggregate=${aggregate.format3()} ($pct%)"
                    )
                    onProgress(aggregate.coerceIn(0.0, 1.0))
                }
            }
            catch(err: Throwable)
            {
                Logger.error(
                    LogCategory.NETWORK,
                    "AssetPipeline: loader '${loader.id}' threw ${err::class.simpleName}: ${err.message}"
                )
                LoadResult.Failed(err.message ?: "unknown")
            }

            when(outcome)
            {
                is LoadResult.Done ->
                {
                    succeeded.add(loader.id)
                    Logger.info(LogCategory.NETWORK, "AssetPipeline: loader '${loader.id}' DONE (lastLoaderFraction=${lastLoaderFraction.format3()})")
                }
                is LoadResult.Failed ->
                {
                    failed.add(loader.id to outcome.reason)
                    Logger.warn(
                        LogCategory.NETWORK,
                        "AssetPipeline: loader '${loader.id}' FAILED: ${outcome.reason}"
                    )
                }
            }
            completedLoaders += 1
            // Ensure we land exactly on the boundary after a loader completes,
            // even if its final progress event was below 1.0.
            val boundary = (completedLoaders * perLoaderShare).coerceIn(0.0, 1.0)
            Logger.debug(LogCategory.NETWORK, "AssetPipeline: emitting boundary fraction=${boundary.format3()} after loader '${loader.id}'")
            onProgress(boundary)
        }

        Logger.info(LogCategory.NETWORK, "AssetPipeline: run COMPLETE, succeeded=${succeeded.size} failed=${failed.size}")
        onProgress(1.0)
        return Result(1.0, succeeded, failed)
    }
}