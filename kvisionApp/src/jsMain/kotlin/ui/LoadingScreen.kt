package ui

import io.kvision.core.AlignItems
import io.kvision.core.CssSize
import io.kvision.core.JustifyContent
import io.kvision.core.Position
import io.kvision.core.UNIT
import io.kvision.html.Button
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.panel.VPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.ttt.autogenesis.kvisionapp.assets.AssetLoader
import org.ttt.autogenesis.kvisionapp.assets.AssetPipeline
import org.ttt.autogenesis.kvisionapp.audio.AudioEngine
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

private fun Double.format3(): String = (kotlin.math.floor(this * 1000.0) / 1000.0).toString()

/**
 * Full-viewport pre-game loading screen that gates the Web Audio context
 * behind a user click and surfaces real asset-loading progress.
 *
 * ## Behavior
 *
 *  1. **Idle** (initial state) — shows the cinematic background, AUTOGENESIS
 *     wordmark, and a gold "CLICK TO ENTER" button.
 *  2. **Click** — synchronously calls [AudioEngine.initContext] on the
 *     user-gesture call stack (B1 fix from [MainMenu] / [AudioEngine]); this
 *     satisfies browser autoplay policy so the AudioContext is `running` by
 *     the time the first audio plays.
 *  3. **Loading** — runs the [AssetPipeline] for every [AssetLoader] supplied
 *     to the constructor, reporting aggregate progress into the on-screen
 *     bar and per-asset status text.
 *  4. **Done** — completes the [readyDeferred] and (optionally) invokes the
 *     `onReady` callback so the caller can transition to login or main menu.
 *
 * ## Failure handling
 *
 * Asset loaders report [org.ttt.autogenesis.kvisionapp.assets.LoadResult.Failed]
 * individually; the pipeline continues regardless. The loading screen never
 * traps the user on a bad asset.
 *
 * ## Click-state machine
 *
 * Subsequent clicks during [ClickState.LOADING] or [ClickState.DONE] are
 * no-ops, so spamming the CTA is safe.
 *
 * @param assets ordered list of asset loaders to run after the user click.
 * @param onReady optional callback fired on the calling coroutine when the
 *   pipeline completes (success or partial failure).
 */
class LoadingScreen(
    private val assets: List<AssetLoader>,
    private val onReady: (() -> Unit)? = null
) : VPanel(spacing = 0)
{
    /**
     * Internal state machine. Transitions: IDLE -> LOADING -> DONE.
     * DONE is terminal; LOADING -> LOADING is allowed but no-op for clicks.
     */
    private enum class ClickState { IDLE, LOADING, DONE }

    private var clickState: ClickState = ClickState.IDLE
    private val readyDeferred = CompletableDeferred<Unit>()

    private lateinit var ctaButton: Button
    private lateinit var progressFill: Div
    private lateinit var statusLabel: Span
    private lateinit var footerLabel: Span

    init
    {
        Logger.info(LogCategory.UI, "LoadingScreen: init START, assets=${assets.size}")

        // Root container: full viewport, absolutely positioned above all other UI.
        addCssClass("loading-screen-root")
        setAttribute("data-testid", "loading-screen-root")
        position = Position.ABSOLUTE
        width = 100.perc
        height = 100.perc
        zIndex = 9999
        Logger.debug(LogCategory.UI, "LoadingScreen: root container configured (zIndex=9999)")

        // Background image layer (separate from the centered content column).
        div {
            addCssClass("loading-screen-bg")
            setAttribute("data-testid", "loading-screen-bg")
        }
        Logger.debug(LogCategory.UI, "LoadingScreen: background layer added")

        // Centered content column.
        vPanel(
            alignItems = AlignItems.CENTER,
            justify = JustifyContent.CENTER,
            spacing = 0
        ) {
            addCssClass("loading-screen-center")
            setAttribute("data-testid", "loading-screen-center")
            width = 100.perc
            height = 100.perc
            Logger.debug(LogCategory.UI, "LoadingScreen: center column configured")

            // Wordmark rendered as CSS text for perfect transparency and
            // resolution-independence. The image-based wordmark had a baked-in
            // dark background that created a visible rectangle against the
            // deep-space background image; text is transparent and the
            // gold drop-shadow filter creates the glow.
            div {
                addCssClass("loading-screen-wordmark")
                setAttribute("data-testid", "loading-screen-wordmark")
                setAttribute("role", "heading")
                setAttribute("aria-level", "1")
                +"AUTOGENESIS"
            }
            Logger.debug(LogCategory.UI, "LoadingScreen: wordmark added (CSS text)")

            // Spacer.
            div {
                addCssClass("loading-screen-spacer")
                height = 40.px
            }

            // CTA button (idle = "CLICK TO ENTER"; loading = disabled with throbber).
            ctaButton = button("CLICK TO ENTER", className = "loading-screen-cta") {
                setAttribute("data-testid", "loading-screen-cta")
                onClick {
                    Logger.info(LogCategory.UI, "LoadingScreen: onClick event fired on CTA")
                    handleCtaClick()
                }
            }
            Logger.debug(LogCategory.UI, "LoadingScreen: CTA button bound (initial state=IDLE)")

            // Spacer.
            div {
                addCssClass("loading-screen-spacer")
                height = 24.px
            }

            // Progress bar (track + fill).
            div {
                addCssClass("loading-screen-progress-track")
                setAttribute("data-testid", "loading-screen-progress-track")
                progressFill = div {
                    addCssClass("loading-screen-progress-fill")
                    setAttribute("data-testid", "loading-screen-progress-fill")
                    width = CssSize(0, UNIT.perc)
                }
            }
            Logger.debug(LogCategory.UI, "LoadingScreen: progress track + fill added")

            // Spacer.
            div {
                addCssClass("loading-screen-spacer")
                height = 12.px
            }

            // Status text (current asset + sub-state).
            statusLabel = span("Ready when you are") {
                addCssClass("loading-screen-status")
                setAttribute("data-testid", "loading-screen-status")
            }
            Logger.debug(LogCategory.UI, "LoadingScreen: status label added")

            // Spacer.
            div {
                addCssClass("loading-screen-spacer")
                height = 24.px
                flexGrow = 1
            }

            // Footer text (decorative sci-fi flavor, mirrors Stitch mockup).
            footerLabel = span("SYSTEM STATUS: CALIBRATING CHRONO-DRIVE...") {
                addCssClass("loading-screen-footer")
                setAttribute("data-testid", "loading-screen-footer")
            }
            Logger.debug(LogCategory.UI, "LoadingScreen: footer label added")
        }

        renderIdle()
        Logger.info(LogCategory.UI, "LoadingScreen: init DONE, state=IDLE, awaiting user click")
    }

    /**
     * Click handler for the CTA button. Synchronous audio-context init must
     * run on the user-gesture call stack; the asset pipeline is launched on
     * the main scope so it does not block the click handler.
     *
     * Exposed as [internal] so jsTest can drive the click without a real DOM
     * event (real browser testing is in [browser-smoke/tests/loading-screen.spec.mjs]).
     */
    internal fun handleCtaClick()
    {
        Logger.info(LogCategory.UI, "LoadingScreen: handleCtaClick ENTER, currentState=$clickState")
        if(clickState != ClickState.IDLE)
        {
            Logger.debug(LogCategory.UI, "LoadingScreen: click ignored (state=$clickState, not IDLE)")
            return
        }
        clickState = ClickState.LOADING
        Logger.info(LogCategory.UI, "LoadingScreen: state IDLE -> LOADING")

        // B1 fix (propagated from MainMenu): initContext() MUST run synchronously
        // on the user-gesture call stack so AudioContext.resume() still satisfies
        // browser autoplay policy. Do NOT defer to a coroutine.
        Logger.info(LogCategory.SYSTEM, "LoadingScreen: calling AudioEngine.initContext() synchronously on user-gesture stack")
        try
        {
            AudioEngine.initContext()
            Logger.info(LogCategory.SYSTEM, "LoadingScreen: AudioEngine.initContext() returned, AudioContext state should now be 'running'")
        }
        catch(err: Throwable)
        {
            Logger.error(LogCategory.SYSTEM, "LoadingScreen: AudioEngine.initContext() threw: ${err.message}")
            throw err
        }

        renderLoading(0.0, "Loading...")
        Logger.info(LogCategory.UI, "LoadingScreen: rendered LOADING state, fraction=0.0")

        Logger.info(LogCategory.NETWORK, "LoadingScreen: launching asset pipeline on MainScope, ${assets.size} loader(s) total")
        MainScope().launch {
            Logger.debug(LogCategory.NETWORK, "LoadingScreen: pipeline coroutine started")
            try
            {
                val pipeline = AssetPipeline(assets)
                Logger.debug(LogCategory.NETWORK, "LoadingScreen: AssetPipeline instance created")
                val result = pipeline.run { fraction ->
                    val percent = (fraction * 100.0).toInt()
                    val activeLabel = assets.getOrNull((fraction * assets.size).toInt().coerceIn(0, assets.size - 1))?.humanLabel
                        ?: "assets"
                    Logger.debug(
                        LogCategory.NETWORK,
                        "LoadingScreen: progress callback fraction=${fraction.format3()} percent=$percent% activeLabel='$activeLabel'"
                    )
                    renderLoading(fraction, "Loading $activeLabel... $percent%")
                }
                Logger.info(
                    LogCategory.NETWORK,
                    "LoadingScreen: pipeline.run returned: succeeded=${result.succeeded.size} failed=${result.failed.size}"
                )
                if(result.failed.isNotEmpty())
                {
                    Logger.warn(
                        LogCategory.NETWORK,
                        "LoadingScreen: ${result.failed.size}/${assets.size} assets failed: ${result.failed}"
                    )
                }
                clickState = ClickState.DONE
                Logger.info(LogCategory.UI, "LoadingScreen: state LOADING -> DONE")
                renderDone(result)
                readyDeferred.complete(Unit)
                Logger.info(LogCategory.UI, "LoadingScreen: readyDeferred.complete() called, awaiting onReady callback")
                onReady?.invoke()
                Logger.info(LogCategory.UI, "LoadingScreen: onReady callback completed (returned)")
            }
            catch(err: Throwable)
            {
                Logger.error(LogCategory.NETWORK, "LoadingScreen: pipeline crashed: ${err.message} ${err::class.simpleName}")
                clickState = ClickState.DONE
                Logger.warn(LogCategory.UI, "LoadingScreen: state LOADING -> DONE (crash recovery)")
                renderDoneWithError(err.message ?: "Unknown error")
                readyDeferred.complete(Unit)
                onReady?.invoke()
            }
        }
        Logger.info(LogCategory.UI, "LoadingScreen: handleCtaClick EXIT, pipeline coroutine dispatched")
    }

    /**
     * Suspend until the asset pipeline has finished (success, partial
     * failure, or pipeline crash). Resolves even on failure so the caller
     * can always proceed to the main UI.
     */
    suspend fun awaitReady()
    {
        Logger.info(LogCategory.UI, "LoadingScreen: awaitReady() called by caller, suspending on readyDeferred")
        readyDeferred.await()
        Logger.info(LogCategory.UI, "LoadingScreen: awaitReady() resumed, readyDeferred resolved")
    }

    /**
     * Apply the idle-state visuals: CTA enabled, progress bar empty, status
     * text at default.
     */
    private fun renderIdle()
    {
        Logger.debug(LogCategory.UI, "LoadingScreen: renderIdle() CTA=enabled, progress=0%, status='Ready when you are'")
        ctaButton.text = "CLICK TO ENTER"
        ctaButton.disabled = false
        addOrReplaceCssClass(ctaButton, "loading-screen-cta--idle", "loading-screen-cta--loading")
        progressFill.width = CssSize(0, UNIT.perc)
        statusLabel.content = "Ready when you are"
    }

    /**
     * Apply the loading-state visuals: CTA disabled, progress bar at
     * [fraction], status text set to [message].
     */
    private fun renderLoading(fraction: Double, message: String)
    {
        Logger.debug(LogCategory.UI, "LoadingScreen: renderLoading fraction=${fraction.format3()} message='$message'")
        ctaButton.disabled = true
        ctaButton.text = ""
        addOrReplaceCssClass(ctaButton, "loading-screen-cta--loading", "loading-screen-cta--idle")
        progressFill.width = CssSize((fraction * 100.0).coerceIn(0.0, 100.0), UNIT.perc)
        statusLabel.content = message
    }

    /**
     * Apply the done-state visuals after a successful (or partial-success)
     * pipeline run. The status text becomes "Ready" and the progress bar
     * sits at 100%.
     */
    private fun renderDone(result: AssetPipeline.Result)
    {
        Logger.info(LogCategory.UI, "LoadingScreen: renderDone succeeded=${result.succeeded} failed=${result.failed}")
        progressFill.width = CssSize(100, UNIT.perc)
        ctaButton.disabled = true
        ctaButton.text = ""
        addOrReplaceCssClass(ctaButton, "loading-screen-cta--done", "loading-screen-cta--loading")
        val message = if(result.failed.isEmpty())
        {
            "Ready"
        }
        else
        {
            "Ready (${result.failed.size} asset(s) unavailable)"
        }
        statusLabel.content = message
        footerLabel.content = "SYSTEM STATUS: ONLINE"
    }

    /**
     * Apply the done-state visuals after the pipeline itself crashed
     * (not just a failed loader). The status text surfaces the error
     * message so debug-builds can read it.
     */
    private fun renderDoneWithError(errorMessage: String)
    {
        Logger.warn(LogCategory.UI, "LoadingScreen: renderDoneWithError msg='$errorMessage'")
        progressFill.width = CssSize(100, UNIT.perc)
        ctaButton.disabled = true
        ctaButton.text = ""
        addOrReplaceCssClass(ctaButton, "loading-screen-cta--done", "loading-screen-cta--loading")
        statusLabel.content = "Ready (loading reported an issue)"
        footerLabel.content = "SYSTEM STATUS: $errorMessage".take(160)
    }

    /**
     * Replace [removeClass] with [addClass] on [widget] atomically. If the
     * widget already has [addClass], this is a no-op for the add.
     */
    private fun addOrReplaceCssClass(widget: io.kvision.core.Widget, addClass: String, removeClass: String)
    {
        widget.removeCssClass(removeClass)
        widget.addCssClass(addClass)
    }
}