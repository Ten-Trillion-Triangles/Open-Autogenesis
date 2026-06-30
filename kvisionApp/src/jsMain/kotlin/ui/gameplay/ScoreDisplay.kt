package ui.gameplay

import io.kvision.core.AlignItems
import io.kvision.core.Background
import io.kvision.core.Border
import io.kvision.core.BorderStyle
import io.kvision.core.Color
import io.kvision.core.Display
import io.kvision.core.JustifyContent
import io.kvision.core.JustifyItems
import io.kvision.core.onEvent
import io.kvision.core.Overflow
import io.kvision.core.Position
import io.kvision.core.TextAlign
import io.kvision.html.Icon
import io.kvision.html.P
import io.kvision.html.icon
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.panel.HPanel
import io.kvision.panel.SimplePanel
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timer.CountdownTimer
import timer.TimerSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import structs.AgentUsage

/**
 * Helper class to animate number transitions.
 */
class NumberTicker(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onUpdate: (Int) -> Unit
) {
    private var currentValue = 0
    private var targetValue = 0
    private var animationJob: kotlinx.coroutines.Job? = null

    fun update(newValue: Int) {
        if (newValue == targetValue) return

        targetValue = newValue

        // If already animating, cancel previous job
        animationJob?.cancel()

        animationJob = scope.launch {
            val startValue = currentValue
            val diff = targetValue - startValue
            val steps = 20 // Number of steps in animation
            val duration = 800L // Total duration in ms
            val stepTime = duration / steps

            for (i in 1..steps) {
                val progress = i.toDouble() / steps
                val x = 1.0 - progress
                // Ease-out cubic function: 1 - (1 - x)^3
                val easeProgress = 1.0 - (x * x * x)

                currentValue = (startValue + (diff * easeProgress)).roundToInt()
                onUpdate(currentValue)
                delay(stepTime)
            }
            // Ensure we land exactly on target
            currentValue = targetValue
            onUpdate(currentValue)
        }
    }

    fun snapTo(value: Int) {
        animationJob?.cancel()
        currentValue = value
        targetValue = value
        onUpdate(value)
    }
}

/**
 * Top-level bar that renders the main player score, resource indicators, placement badge, and turn timer.
 */
class ScoreDisplay(isDemo: Boolean = false) : HPanel() {
    private var scoreText: P? = null
    private var placementIcon: Icon? = null
    private var placementText: P? = null
    private var lastScore: Int = 0

    private val scoreTicker = NumberTicker(MainScope()) { value ->
        scoreText?.content = value.toString()
    }

    private val turnTimerWidget = TurnTimerWidget(isDemo)

    private var militaryPanel: PointPanel? = null
    private var diplomaticPanel: PointPanel? = null
    private var researchPanel: PointPanel? = null
    private var summitPanel: PointPanel? = null

    /**
     * Invoked when the player clicks the DELEGATE button in the score bar.
     * Wired by [GameplayUI] to open the per-player [DelegateWidget] (their
     * "stand-in" guidance for the AI that takes over when they step away).
     */
    var onDelegateClick: (() -> Unit)? = null

    var demoMode = isDemo
        set(value) {
            field = value
            if (value) {
                Logger.debug(LogCategory.UI, "ScoreDisplay: Starting timer for demo mode")
                startTurnTimer(minutes = 2, seconds = 0)
                updateScore(1250)
                updateResources("500", "500", "1,500", "300")
                updatePlacement(1)
            } else {
                Logger.debug(LogCategory.UI, "ScoreDisplay: Stopping timer")
                stopTurnTimer()
                updateScore(0)
                updateResources("0", "0", "0", "0")
                placementIcon?.icon = ""
                placementText?.content = ""
            }
        }

    init {
        addCssClass("score-display-widget")
        spacing = 10
        width = 100.perc
        height = 100.px
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.SPACEBETWEEN

        hPanel(spacing = 5, alignItems = AlignItems.CENTER) {
            p("Main Score:") {
                addCssClass("main-score-label")
                fontSize = 24.px
                fontWeight = io.kvision.core.FontWeight.BOLD
                color = io.kvision.core.Color.name(io.kvision.core.Col.WHITE)
                paddingLeft = 20.px
                textShadow = io.kvision.core.TextShadow(0.px, 0.px, 5.px, io.kvision.core.Color.name(io.kvision.core.Col.CYAN))
            }

            scoreText = p("0") {
                addCssClass("main-score-text")
                // Inline styles removed to allow CSS animation to take full precedence without specificity issues
            }
        }

        hPanel(spacing = 30, alignItems = AlignItems.CENTER) {
            militaryPanel = PointPanel("fas fa-gavel", "fas fa-cube", "Military", "0").also { add(it) }
            militaryPanel?.paddingBottom = 10.px
            diplomaticPanel = PointPanel("fas fa-handshake", "fas fa-coins", "Diplomatic", "0").also { add(it) }
            diplomaticPanel?.paddingBottom = 10.px
            researchPanel = PointPanel("fas fa-flask", "fas fa-gem", "Research", "0").also { add(it) }
            researchPanel?.paddingBottom = 10.px
            summitPanel = PointPanel("fas fa-mountain", "fas fa-coins", "Summit", "0").also { add(it) }
            summitPanel?.paddingBottom = 10.px
        }

        hPanel(alignItems = AlignItems.CENTER, spacing = 12) {
            vPanel(alignItems = AlignItems.CENTER, spacing = 2) {
                placementIcon = icon("") {
                    fontSize = 32.px
                    color = io.kvision.core.Color.name(io.kvision.core.Col.GOLD)
                }

                placementText = p("") {
                    fontSize = 14.px
                    fontWeight = io.kvision.core.FontWeight.BOLD
                    color = io.kvision.core.Color.name(io.kvision.core.Col.WHITE)
                    marginBottom = 0.px
                }
            }

            // Always-visible DELEGATE entry point. Built as a SimplePanel (not KVision
            // Button) so we sidestep the .btn / .btn-primary defaults that fight any
            // inline styling. The hover lift is provided by the dedicated CSS class
            // `score-delegate-button` defined in night-mode.css. The player can update
            // their guidance for the AI that takes over when they step away, even
            // while the AI is currently running their turn.
            val delegateButton = SimplePanel().apply {
                addCssClass("score-delegate-button")
                marginRight = 12.px
                setStyle("padding", "6px 12px")
                setStyle("display", "inline-flex")
                setStyle("flex-direction", "column")
                setStyle("align-items", "center")
                setStyle("justify-content", "center")
                setStyle("gap", "3px")
                setStyle("cursor", "pointer")
                setStyle("user-select", "none")

                onEvent {
                    click = { _ ->
                        Logger.debug(LogCategory.UI, "ScoreDisplay: DELEGATE button clicked")
                        onDelegateClick?.invoke()
                    }
                }

                icon("fas fa-scroll") {
                    fontSize = 22.px
                    color = io.kvision.core.Color.name(io.kvision.core.Col.WHITE)
                }
                span("DELEGATE") {
                    fontSize = 12.px
                    fontWeight = io.kvision.core.FontWeight.BOLD
                    color = io.kvision.core.Color.name(io.kvision.core.Col.WHITE)
                    setStyle("line-height", "1")
                }
            }
            add(delegateButton)

            add(turnTimerWidget)
        }

        demoMode = false
    }

    /**
     * Updates the displayed score value.
     *
     * @param newScore The score to display.
     */
    /**
     * Updates the displayed score value.
     *
     * @param newScore The score to display.
     */
    fun updateScore(newScore: Int) {
        if (newScore != lastScore) {
            flashScore()
            lastScore = newScore
        }
        scoreTicker.update(newScore)
    }

    private fun flashScore() {
        Logger.debug(LogCategory.UI, "ScoreDisplay: Flashing Score!")
        val el = scoreText
        if (el != null) {
            el.removeCssClass("score-change-anim")
            // Force Reflow to allow restarting animation
            val domEl = el.getElement()
            domEl?.offsetWidth

            el.addCssClass("score-change-anim")

            // Clean up later just to be safe, though reflow logic handles restart
            kotlinx.browser.window.setTimeout({
                el.removeCssClass("score-change-anim")
            }, 800)
        }
    }

    /**
     * Updates all resource indicators.
     *
     * @param mil Display string for military resource points.
     * @param dip Display string for diplomatic resource points.
     * @param res Display string for research resource points.
     * @param sum Display string for summit resource points.
     */
    fun updateResources(mil: String, dip: String, res: String, sum: String) {
        militaryPanel?.updateValue(mil)
        diplomaticPanel?.updateValue(dip)
        researchPanel?.updateValue(res)
        summitPanel?.updateValue(sum)
    }

    /**
     * Updates placement visuals based on the provided rank.
     *
     * @param rank Player ranking position.
     */
    fun updatePlacement(rank: Int) {
        when (rank) {
            1 -> {
                placementIcon?.icon = "fas fa-trophy"
                placementIcon?.color = io.kvision.core.Color.name(io.kvision.core.Col.GOLD)
                placementText?.content = "1st Place"
            }
            2 -> {
                placementIcon?.icon = "fas fa-medal"
                placementIcon?.color = io.kvision.core.Color.name(io.kvision.core.Col.SILVER)
                placementText?.content = "2nd Place"
            }
            3 -> {
                placementIcon?.icon = "fas fa-medal"
                placementIcon?.color = io.kvision.core.Color.name(io.kvision.core.Col.CHOCOLATE)
                placementText?.content = "3rd Place"
            }
            else -> {
                placementIcon?.icon = "fas fa-flag"
                placementIcon?.color = io.kvision.core.Color.name(io.kvision.core.Col.GRAY)
                placementText?.content = "${rank}th Place"
            }
        }
    }

    /**
     * Triggers a flash animation on the Summit panel.
     *
     * @param isGain True for gain animation (green/gold), false for loss animation (red).
     */
    fun triggerSummitFlash(isGain: Boolean) {
        summitPanel?.triggerFlash(isGain)
    }

    /**
     * Starts the timer widget countdown.
     *
     * @param minutes Minutes component of duration.
     * @param seconds Seconds component of duration.
     */
    fun startTurnTimer(minutes: Int = 0, seconds: Int = 30) {
        turnTimerWidget.start(minutes, seconds)
    }

    /**
     * Pauses the timer widget countdown.
     */
    fun pauseTurnTimer() {
        turnTimerWidget.pause()
    }

    /**
     * Resumes the timer widget countdown.
     */
    fun resumeTurnTimer() {
        turnTimerWidget.resume()
    }

    /**
     * Stops the timer widget countdown.
     */
    fun stopTurnTimer() {
        turnTimerWidget.stop()
    }

    /**
     * Resets the timer widget countdown duration.
     *
     * @param minutes Minutes portion for reset.
     * @param seconds Seconds portion for reset.
     */
    fun resetTurnTimer(minutes: Int = 0, seconds: Int = 0) {
        turnTimerWidget.reset(minutes, seconds)
    }

    fun updateTurnTimer(remainingSeconds: Long, totalDuration: Long = 30L)
    {
        turnTimerWidget.updateExternal(remainingSeconds, totalDuration)
    }
}

/**
 * Helper panel that combines icons and values for a resource indicator.
 *
 * @param mainIconName FontAwesome icon used as the primary symbol.
 * @param subIconName FontAwesome icon used beside the value.
 * @param label Title text displayed above the value.
 * @param initialValue Starting value displayed beside the sub icon.
 */
class PointPanel(
    mainIconName: String,
    subIconName: String,
    label: String,
    initialValue: String
) : HPanel() {
    private var pointValue: P? = null
    private var lastValueInt: Int = 0

    // Ticker for smooth transitions if values are integers
    private val ticker = NumberTicker(MainScope()) {
        pointValue?.content = it.toString()
    }

    init {
        // Parsing initial value to set ticker state if possible
        val parsed = initialValue.replace(",", "").toIntOrNull()
        if (parsed != null) {
            lastValueInt = parsed
            ticker.snapTo(parsed)
        }

        addCssClass("resource-point-panel")
        spacing = 10
        alignItems = AlignItems.CENTER

        icon(mainIconName) {
            fontSize = 28.px
            color = io.kvision.core.Color.name(io.kvision.core.Col.LIGHTBLUE)
        }

        vPanel {
            spacing = 2
            alignItems = AlignItems.FLEXSTART
            justifyItems = JustifyItems.START

            p(label) {
                fontSize = 14.px
                fontWeight = io.kvision.core.FontWeight.BOLD
                color = io.kvision.core.Color.name(io.kvision.core.Col.LIGHTGRAY)
                marginBottom = 0.px
            }

            hPanel(spacing = 5, alignItems = AlignItems.CENTER) {
                icon(subIconName) {
                    fontSize = 12.px
                    color = io.kvision.core.Color.name(io.kvision.core.Col.GOLD)
                }

                pointValue = p(initialValue) {
                    fontSize = 14.px
                    color = io.kvision.core.Color.name(io.kvision.core.Col.WHITE)
                    marginBottom = 0.px
                }
            }
        }
    }

    /**
     * Updates the displayed value for the resource indicator with animation.
     *
     * @param value New resource value text.
     */
    fun updateValue(value: String) {
        val newValInt = value.replace(",", "").toIntOrNull()

        if (newValInt != null) {
            val diff = newValInt - lastValueInt
            if (diff > 0) flashGain()
            else if (diff < 0) flashLoss()

            lastValueInt = newValInt
            ticker.update(newValInt)
        } else {
            // Fallback for non-numeric strings
            pointValue?.content = value
        }
    }

    /**
     * Deprecated: Use updateValue for animation support.
     */
    fun setPoints(value: String) {
        updateValue(value)
    }

    private fun flashGain() {
        pointValue?.removeCssClass("resource-gain-anim")
        removeCssClass("resource-container-gain")
        getElement()?.offsetWidth // Force reflow
        pointValue?.getElement()?.offsetWidth // Force reflow

        pointValue?.addCssClass("resource-gain-anim")
        addCssClass("resource-container-gain")

        kotlinx.browser.window.setTimeout({
            pointValue?.removeCssClass("resource-gain-anim")
            removeCssClass("resource-container-gain")
        }, 800)
    }

    private fun flashLoss() {
        pointValue?.removeCssClass("resource-loss-anim")
        removeCssClass("resource-container-loss")
        getElement()?.offsetWidth // Force reflow
        pointValue?.getElement()?.offsetWidth // Force reflow

        pointValue?.addCssClass("resource-loss-anim")
        addCssClass("resource-container-loss")

        kotlinx.browser.window.setTimeout({
            pointValue?.removeCssClass("resource-loss-anim")
            removeCssClass("resource-container-loss")
        }, 800)
    }

    /**
     * Triggers a flash animation on this resource panel.
     *
     * @param isGain True for gain animation (green/gold), false for loss animation (red).
     */
    fun triggerFlash(isGain: Boolean) {
        if (isGain) {
            flashGain()
        } else {
            flashLoss()
        }
    }
}

/**
 * Visual representation of a countdown timer with a filling bar and numeric label.
 */
class TurnTimerWidget(val demoMode: Boolean = false) : SimplePanel()
{
    private val countdownTimer = CountdownTimer()
    private val uiScope = MainScope()
    private var durationSeconds = 0L

    private val progressFill = SimplePanel().apply {
        position = Position.ABSOLUTE
        top = 0.px
        left = 0.px
        bottom = 0.px
        width = 100.perc
        borderRadius = 22.px
        background = Background(Color.hex(0x3b7dff))
        setStyle("transition", "width 0.35s ease")
        setStyle("z-index", "0")
    }

    private val timerLabel = span("--:--")
    {
        addCssClass("turn-timer-label")
        position = Position.ABSOLUTE
        top = 0.px
        left = 0.px
        right = 0.px
        bottom = 0.px
        display = Display.FLEX
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.CENTER
        textAlign = TextAlign.CENTER
        fontSize = 16.px
        fontWeight = io.kvision.core.FontWeight.BOLD
        color = Color.name(io.kvision.core.Col.WHITE)
        setStyle("z-index", "1")
        setStyle("pointer-events", "none")
    }

    init
    {
        addCssClass("turn-timer-widget")
        width = 240.px
        height = 54.px
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.CENTER

        val track = simplePanel {
            addCssClass("turn-timer-track")
            width = 220.px
            height = 42.px
            position = Position.RELATIVE
            borderRadius = 22.px
            border = Border(1.px, BorderStyle.SOLID, Color.hex(0x4b5fdc))
            overflow = Overflow.HIDDEN
            setStyle("box-shadow", "inset 0 0 18px rgba(0, 0, 0, 0.5)")
            add(progressFill)
            add(timerLabel)
        }

        add(track)
        updateForSnapshot(TimerSnapshot(0L, 0, 0, false, false))

        if (demoMode)
        {
            uiScope.launch {
                countdownTimer.remainingSeconds.collect {
                    updateForSnapshot(countdownTimer.timeSnapshot())
                }
            }
        }
    }

    /**
     * Starts the countdown timer.
     *
     * @param minutes Minutes component.
     * @param seconds Seconds component.
     */
    fun start(minutes: Int = 0, seconds: Int = 30)
    {
        if (!demoMode) return
        Logger.debug(LogCategory.UI, "TurnTimerWidget: start() called with ${minutes}m ${seconds}s")
        val totalSeconds = minutes.toLong().coerceAtLeast(0L) * 60 + seconds.toLong().coerceAtLeast(0L)
        if(totalSeconds <= 0L)
        {
            Logger.debug(LogCategory.UI, "TurnTimerWidget: Invalid duration, not starting")
            return
        }

        durationSeconds = totalSeconds
        Logger.debug(LogCategory.UI, "TurnTimerWidget: Starting countdown timer...")
        countdownTimer.start(minutes, seconds)
        updateForSnapshot(countdownTimer.timeSnapshot())
    }

    /**
     * Pauses the countdown.
     */
    fun pause()
    {
        if (demoMode) countdownTimer.pause()
    }

    /**
     * Resumes a paused countdown.
     */
    fun resume()
    {
        if (demoMode) countdownTimer.resume()
    }

    /**
     * Stops the countdown and clears progress.
     */
    fun stop()
    {
        if (demoMode)
        {
            countdownTimer.stop()
            durationSeconds = 0L
            updateForSnapshot(countdownTimer.timeSnapshot())
        }
    }

    /**
     * Resets the countdown without starting it.
     *
     * @param minutes Minutes component.
     * @param seconds Seconds component.
     */
    fun reset(minutes: Int = 0, seconds: Int = 0)
    {
        if (demoMode)
        {
            durationSeconds = minutes.toLong().coerceAtLeast(0L) * 60 + seconds.toLong().coerceAtLeast(0L)
            countdownTimer.reset(minutes, seconds)
            updateForSnapshot(countdownTimer.timeSnapshot())
        }
    }

    /**
     * Updates the timer display from external data (server pulses).
     */
    fun updateExternal(remainingSeconds: Long, totalDuration: Long = 30L)
    {
        if (demoMode) return
        this.durationSeconds = totalDuration
        val snapshot = TimerSnapshot(
            remainingSeconds = remainingSeconds,
            minutes = (remainingSeconds / 60).toInt(),
            seconds = (remainingSeconds % 60).toInt(),
            isRunning = remainingSeconds > 0,
            isPaused = false
        )
        updateForSnapshot(snapshot)
    }

    /**
     * Updates UI elements from the provided snapshot.
     *
     * @param snapshot Latest timer snapshot.
     */
    private fun updateForSnapshot(snapshot: TimerSnapshot)
    {
        val minutes = snapshot.minutes.toString().padStart(2, '0')
        val seconds = snapshot.seconds.toString().padStart(2, '0')
        timerLabel.content = "$minutes:$seconds"
        updateFill(snapshot.remainingSeconds)
    }

    /**
     * Adjusts the visual fill width based on remaining time.
     *
     * @param remainingSeconds Seconds left.
     */
    private fun updateFill(remainingSeconds: Long)
    {
        val ratio = if(durationSeconds <= 0L)
        {
            0.0
        }
        else
        {
            (remainingSeconds.toDouble() / durationSeconds).coerceIn(0.0, 1.0)
        }

        val widthPercent = (ratio * 100.0).coerceIn(0.0, 100.0)
        progressFill.width = widthPercent.perc
    }
}

/**
 * DSL builder that installs a [ScoreDisplay] into a container.
 *
 * @param init Optional initializer block.
 * @return The created [ScoreDisplay].
 */
fun io.kvision.core.Container.scoreDisplay(
    demoMode: Boolean = false,
    init: (ScoreDisplay.() -> Unit)? = null
): ScoreDisplay
{
    val scoreDisplay = ScoreDisplay(demoMode)
    init?.invoke(scoreDisplay)
    add(scoreDisplay)
    return scoreDisplay
}
