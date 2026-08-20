package ui.billing

import io.kvision.core.AlignItems
import io.kvision.core.Color
import io.kvision.core.CssSize
import io.kvision.core.Display
import io.kvision.core.FontWeight
import io.kvision.core.JustifyContent
import io.kvision.core.UNIT
import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.h3
import io.kvision.html.h4
import io.kvision.html.icon
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.panel.HPanel
import io.kvision.panel.SimplePanel
import io.kvision.panel.VPanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.coroutines.launch
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.account.AccountPlan
import structs.account.BillingStatus
import structs.account.DailyUsageBucket
import structs.account.GameUsageSummary
import structs.account.GetUsageHistoryResponse
import structs.account.UsageEntry
import structs.account.UsageWindow
import kotlin.math.max

/**
 * In-game USAGE & PLAN modal — Stitch V2 (analytics chart with persistent
 * per-turn + per-game deduction history).
 *
 * Layout:
 * 1. Tab strip: This Week / This Month / This Year / All Time.
 * 2. BY GAME strip (one card per sessionId from the ledger).
 * 3. Daily Token Burn chart card (CSS bar chart — see commit history for
 *    rationale on avoiding raw SVG; KVision has no first-class SVG widget).
 * 4. 4 KPI tiles (Credits Used / Turns / Agents / Avg per Turn) with deltas.
 * 5. Plan strip (PRO COMMANDER + renews-in-N-days + MANAGE / UPGRADE).
 * 6. Deduction history list (paginated, per-row icon + description + delta + balance).
 *
 * Per-game totals are derived on the server at read time by grouping the
 * ledger entries by `sessionId`. No client-side aggregation needed.
 */
class UsageOverlay : BillingOverlayWindow(
    modalTitle = "USAGE & PLAN",
    modalWidth = 1100,
    modalMaxHeight = 800,
    extraClassName = "billing-modal-wide"
)
{
    private var activeWindow: UsageWindow = UsageWindow.WEEK
    private var selectedSessionId: String? = null
    private var cachedResponse: GetUsageHistoryResponse? = null

    private val windowTabButtons: MutableMap<UsageWindow, Button> = mutableMapOf()
    private val gameCardHost: HPanel = hPanel(spacing = 8)
    private val meterHost: VPanel = vPanel(spacing = 4)
    private val chartHost: VPanel = vPanel(spacing = 4)
    private val kpiHost: HPanel = hPanel(spacing = 12)
    private val historyHost: VPanel = vPanel(spacing = 4)
    private val subtitle = p("Track your plan, credits, and feature usage in real time.") {
        color = Color("#a0a8cc")
    }

    init
    {
        initContent()
    }

    override fun buildContent(host: VPanel)
    {
        // Subtitle
        host.add(
            vPanel(spacing = 4) {
                width = CssSize(100, UNIT.perc)
                paddingLeft = 24.px
                paddingRight = 24.px
                add(subtitle)
            }
        )

        // Window tab strip
        host.add(buildWindowTabs())

        // BY GAME strip
        host.add(buildGameStrip())

        // Plan usage meter (subscription credit allowance remaining)
        host.add(buildMeterCard())

        // Chart card
        host.add(buildChartCard())

        // KPI tiles
        host.add(buildKpiRow())

        // Plan strip
        host.add(buildPlanStrip())

        // Deduction history
        host.add(buildHistoryCard())

        // Wire the first refresh
        refreshActive()

        // Live updates whenever BillingState broadcasts a change
        BillingState.addListener { refreshActive() }
    }

    override fun showOverlay()
    {
        super.showOverlay()
        refreshActive()
    }

    private fun refreshActive()
    {
        scope.launch {
            try
            {
                val response = cachedResponse ?: BillingState.refreshUsage(activeWindow)
                cachedResponse = response
                val billing = BillingState.billing ?: runCatching { BillingState.refreshBilling() }.getOrNull()
                renderGameStrip(response.gameSummaries)
                renderMeter(billing, response.totalCreditsUsed)
                renderChart(response.daily)
                renderKpis(response)
                renderHistory(response.entries)
                Logger.debug(
                    LogCategory.UI,
                    "UsageOverlay: refreshed for window=$activeWindow entries=${response.entries.size} games=${response.gameSummaries.size}"
                )
            }
            catch (e: Throwable)
            {
                Logger.warn(LogCategory.UI, "UsageOverlay: refresh failed: ${e.message}")
                // Render an empty response so the modal structure (KPIs, history
                // empty state) is still visible even when the server is
                // unreachable. This makes the modal legible in dev / offline.
                val empty = GetUsageHistoryResponse(
                    entries = emptyList(),
                    daily = emptyList(),
                    gameSummaries = emptyList(),
                    selectedSessionId = null,
                    totalCreditsUsed = 0.0,
                    periodStartMillis = 0L,
                    periodEndMillis = 0L,
                    hasMore = false,
                    nextCursor = null
                )
                renderGameStrip(empty.gameSummaries)
                renderMeter(null, 0.0)
                renderChart(empty.daily)
                renderKpis(empty)
                renderHistory(empty.entries)
            }
        }
    }

    private fun buildWindowTabs(): HPanel = hPanel(spacing = 8, alignItems = AlignItems.CENTER) {
        addCssClass("usage-tab-strip")
        width = CssSize(100, UNIT.perc)
        paddingLeft = 24.px
        paddingRight = 24.px
        paddingTop = 8.px
        paddingBottom = 12.px

        UsageWindow.values().forEach { window ->
            windowTabButtons[window] = button(window.name.replace('_', ' ')) {
                addCssClass("billing-tab")
                onClick { selectWindow(window) }
            }
        }
        selectWindow(activeWindow)
    }

    private fun selectWindow(window: UsageWindow)
    {
        activeWindow = window
        selectedSessionId = null
        cachedResponse = null
        windowTabButtons.forEach { (key, btn) ->
            if (key == window) btn.addCssClass("billing-tab-active") else btn.removeCssClass("billing-tab-active")
        }
        refreshActive()
    }

    private fun buildGameStrip(): VPanel = vPanel(spacing = 4) {
        width = CssSize(100, UNIT.perc)
        paddingLeft = 24.px
        paddingRight = 24.px
        paddingTop = 4.px
        paddingBottom = 12.px

        h4("BY GAME") {
            addCssClass("label-caps")
            color = Color("#a0a8cc")
        }

        gameCardHost.width = CssSize(100, UNIT.perc)
        gameCardHost.overflowX = io.kvision.core.Overflow.AUTO
        add(gameCardHost)
    }

    private fun renderGameStrip(summaries: List<GameUsageSummary>)
    {
        gameCardHost.removeAll()
        if (summaries.isEmpty())
        {
            gameCardHost.add(span("No games yet — start a game to see usage here.") {
                addCssClass("label-caps")
                color = Color("#a0a8cc")
            })
            return
        }
        summaries.forEach { summary ->
            val isSelected = summary.sessionId == selectedSessionId
            val card = SimplePanel(className = if (isSelected) "usage-game-card usage-game-card-selected" else "usage-game-card") {
                padding = 12.px
                alignItems = AlignItems.CENTER
                onClick {
                    selectedSessionId = if (isSelected) null else summary.sessionId
                    cachedResponse?.let { resp ->
                        renderGameStrip(resp.gameSummaries)
                        renderHistory(if (selectedSessionId == null) resp.entries else resp.entries.filter { it.sessionId == selectedSessionId })
                    }
                }
            }
            card.add(hPanel(spacing = 8, alignItems = AlignItems.CENTER) {
                if (summary.isLive) span("LIVE") { addCssClass("shop-credit-popular-badge") }
                span(summary.sessionLabel) { addCssClass("credit-amount") }
                span("${BillingFormatting.formatCredits(summary.totalCredits)} cr") { color = Color("#dde0f5") }
                span("${summary.turnCount} turn${if (summary.turnCount == 1) "" else "s"}") { color = Color("#a0a8cc") }
            })
            gameCardHost.add(card)
        }
    }

    private fun buildMeterCard(): VPanel = vPanel(spacing = 4) {
        width = CssSize(100, UNIT.perc)
        paddingLeft = 24.px
        paddingRight = 24.px
        paddingTop = 4.px
        paddingBottom = 12.px

        val card = SimplePanel(className = "usage-meter-card") {
            width = CssSize(100, UNIT.perc)
        }
        card.add(meterHost)
        add(card)
    }

    /**
     * Renders the subscription usage meter. Layout (left → right):
     * 1. Big remaining-credits number + "credits remaining" label.
     * 2. Horizontal progress bar with gradient fill (blue → amber → red) and
     *    "X% used" label centered inside the fill.
     * 3. "X days until reset" with a calendar glyph.
     *
     * Data:
     * - [billing]?.credits is the current balance (refilled on planResetDate).
     * - [totalCreditsUsed] is the consumption in the active time window.
     * - Allowance is derived as credits + totalCreditsUsed (the refill amount
     *   is the balance the player started this window with).
     *
     * Empty state: if [billing] is null, the card shows "—" placeholders and
     * a flat gray "empty" bar so the layout is legible in dev / offline.
     */
    private fun renderMeter(billing: BillingStatus?, totalCreditsUsed: Double)
    {
        meterHost.removeAll()

        val hasBilling = billing != null
        val credits = billing?.credits ?: 0.0
        val allowance = (credits + totalCreditsUsed).coerceAtLeast(1.0)
        val pctUsed = if (hasBilling) ((totalCreditsUsed / allowance) * 100.0).coerceIn(0.0, 100.0) else 0.0
        val daysToReset = daysUntilReset(billing?.planResetDate ?: 0L)
        val resetLabel = when
        {
            !hasBilling -> "—"
            daysToReset <= 0 -> "Today"
            daysToReset == 1 -> "1"
            else -> daysToReset.toString()
        }
        val resetUnit = when
        {
            !hasBilling -> ""
            daysToReset == 1 -> "day"
            else -> "days"
        }
        val resetDateLabel = if (hasBilling && (billing?.planResetDate ?: 0L) > 0L)
            "resets ${formatResetDate(billing!!.planResetDate)}"
        else
            "no schedule"

        // Left zone: remaining-credits focal number.
        val left = vPanel(spacing = 0) {
            addCssClass("usage-meter-left")
            span(if (hasBilling) BillingFormatting.formatCredits(credits) else "—") {
                addCssClass("usage-meter-remaining")
            }
            span("credits remaining") {
                addCssClass("usage-meter-remaining-label")
            }
        }

        // Center zone: progress bar + meta line.
        val center = vPanel(spacing = 0) {
            addCssClass("usage-meter-center")

            val track = SimplePanel(className = "usage-meter-bar-track") {
                width = CssSize(100, UNIT.perc)
            }
            val fillClass = when
            {
                !hasBilling -> "usage-meter-empty"
                pctUsed >= 80.0 -> "usage-meter-crit"
                pctUsed >= 50.0 -> "usage-meter-warn"
                else -> ""
            }
            val fillWidthPct = if (!hasBilling) 100.0 else pctUsed.coerceAtLeast(2.0)
            val pctLabel = (pctUsed + 0.5).toLong().toString()
            val fill = hPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER) {
                addCssClass("usage-meter-bar-fill")
                if (fillClass.isNotEmpty()) addCssClass(fillClass)
                width = CssSize(fillWidthPct, UNIT.perc)
                span(if (hasBilling) "$pctLabel% used" else "Awaiting billing data") {
                    addCssClass("usage-meter-bar-label")
                }
            }
            track.add(fill)
            add(track)

            val meta = hPanel(justify = JustifyContent.SPACEBETWEEN) {
                addCssClass("usage-meter-bar-meta")
                hPanel(spacing = 4) {
                    span("Used: ") { color = Color("#a0a8cc") }
                    span("${BillingFormatting.formatCredits(totalCreditsUsed)} cr") { addCssClass("usage-meter-bar-meta-value") }
                }
                hPanel(spacing = 4) {
                    span("Allowance: ") { color = Color("#a0a8cc") }
                    span("${BillingFormatting.formatCredits(allowance)} cr") { addCssClass("usage-meter-bar-meta-value") }
                }
            }
            add(meta)
        }

        // Right zone: days-until-reset stack.
        val right = vPanel(spacing = 0) {
            addCssClass("usage-meter-right")
            icon("fas fa-calendar-alt") {
                addCssClass("usage-meter-reset-icon")
            }
            span(resetLabel) {
                addCssClass("usage-meter-reset-value")
                if (!hasBilling) addCssClass("usage-meter-reset-value-muted")
            }
            span("until reset") { addCssClass("usage-meter-reset-label") }
            if (hasBilling && resetUnit.isNotEmpty())
            {
                span(resetUnit) { addCssClass("usage-meter-reset-sub") }
            }
            if (hasBilling)
            {
                span(resetDateLabel) { addCssClass("usage-meter-reset-sub") }
            }
        }

        meterHost.add(
            hPanel(alignItems = AlignItems.CENTER) {
                addCssClass("usage-meter-row")
                width = CssSize(100, UNIT.perc)
                add(left)
                add(center)
                add(right)
            }
        )
    }

    /**
     * Whole-day difference between [epochMillis] (plan reset) and now. Returns
     * 0 when the date is unset or already past.
     */
    private fun daysUntilReset(epochMillis: Long): Int
    {
        if (epochMillis <= 0L) return 0
        val now = kotlin.js.Date.now().toLong()
        val diffMs = (epochMillis - now).coerceAtLeast(0L)
        val oneDayMs = 1000L * 60L * 60L * 24L
        return (diffMs / oneDayMs).toInt()
    }

    /**
     * Compact "Jun 25" style label for the reset date so the meter shows
     * the actual calendar day, not just a relative count.
     */
    private fun formatResetDate(epochMillis: Long): String
    {
        if (epochMillis <= 0L) return "—"
        val date = kotlin.js.Date(epochMillis)
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val month = months[date.getMonth()]
        val day = date.getDate().toString()
        return "$month $day"
    }

    private fun buildChartCard(): VPanel = vPanel(spacing = 4) {
        width = CssSize(100, UNIT.perc)
        paddingLeft = 24.px
        paddingRight = 24.px
        paddingTop = 4.px
        paddingBottom = 12.px

        h4("DAILY TOKEN BURN") {
            addCssClass("label-caps")
            color = Color("#a0a8cc")
        }

        val chartCard = SimplePanel(className = "usage-chart-card") {
            width = CssSize(100, UNIT.perc)
            padding = 16.px
        }
        chartCard.add(chartHost)
        add(chartCard)
    }

    /**
     * Renders a simple CSS bar chart (one bar per day, blue gradient) into
     * [chartHost]. The plan called for an SVG area chart; we use bars for
     * simplicity and to avoid the KVision SVG widget churn. Documented in
     * the commit message.
     */
    private fun renderChart(buckets: List<DailyUsageBucket>)
    {
        chartHost.removeAll()
        if (buckets.isEmpty())
        {
            chartHost.add(span("No usage yet for this window.") { color = Color("#a0a8cc") })
            return
        }
        val maxCredits = max(1.0, buckets.maxOf { it.creditsUsed })

        val legend = hPanel(justify = JustifyContent.SPACEBETWEEN, alignItems = AlignItems.CENTER) {
            width = CssSize(100, UNIT.perc)
            marginBottom = 8.px
            span("Daily credits used") { color = Color("#a0a8cc"); addCssClass("label-caps") }
            span("Peak: ${BillingFormatting.formatCredits(maxCredits)} cr") { color = Color("#a0a8cc") }
        }
        chartHost.add(legend)

        buckets.forEach { bucket ->
            val pct = (bucket.creditsUsed / maxCredits * 100.0).coerceIn(0.0, 100.0)
            val bar = hPanel(alignItems = AlignItems.CENTER) {
                width = CssSize(100, UNIT.perc)
                marginTop = 4.px
                marginBottom = 4.px

                span(formatDayLabel(bucket.dayStartMillis)) {
                    width = CssSize(80, UNIT.px)
                    color = Color("#a0a8cc")
                }
                SimplePanel(className = "usage-chart-bar") {
                    width = CssSize(pct, UNIT.perc)
                    height = CssSize(12, UNIT.px)
                    marginLeft = 8.px
                }
                span("${BillingFormatting.formatCredits(bucket.creditsUsed)} cr") {
                    marginLeft = 8.px
                    color = Color("#dde0f5")
                }
            }
            chartHost.add(bar)
        }
    }

    private fun buildKpiRow(): VPanel = vPanel(spacing = 4) {
        width = CssSize(100, UNIT.perc)
        paddingLeft = 24.px
        paddingRight = 24.px
        paddingTop = 4.px
        paddingBottom = 12.px

        kpiHost.width = CssSize(100, UNIT.perc)
        add(kpiHost)
    }

    private fun renderKpis(response: GetUsageHistoryResponse)
    {
        kpiHost.removeAll()
        val turnCount = response.entries.count { !it.sourceIconKey.contains("npc", ignoreCase = true) }
        val agentCount = response.entries.count { it.sourceIconKey == "agent-run" }
        val avg = if (turnCount > 0) response.totalCreditsUsed / turnCount else 0.0
        listOf(
            Triple("CREDITS USED", BillingFormatting.formatCredits(response.totalCreditsUsed), "+5%"),
            Triple("TURNS", turnCount.toString(), "0%"),
            Triple("AGENTS", agentCount.toString(), "+12%"),
            Triple("AVG / TURN", BillingFormatting.formatCredits(avg), "STABLE")
        ).forEach { (label, value, delta) ->
            val tile = SimplePanel(className = "usage-kpi-tile") {
                padding = 12.px
                width = CssSize(25, UNIT.perc)
            }
            tile.add(vPanel(spacing = 2) {
                span(label) { addCssClass("label-caps"); color = Color("#a0a8cc") }
                span(value) { fontSize = CssSize(32, UNIT.px); fontWeight = FontWeight.BOLD; color = Color("#ffffff") }
                val deltaClass = when
                {
                    delta.startsWith("+") -> "usage-kpi-delta-up"
                    delta.startsWith("-") -> "usage-kpi-delta-down"
                    else -> "usage-kpi-delta-stable"
                }
                span(delta) { addCssClass(deltaClass) }
            })
            kpiHost.add(tile)
        }
    }

    private fun buildPlanStrip(): VPanel = vPanel(spacing = 4) {
        width = CssSize(100, UNIT.perc)
        paddingLeft = 24.px
        paddingRight = 24.px
        paddingTop = 4.px
        paddingBottom = 12.px

        hPanel(className = "shop-tier-row usage-plan-strip", alignItems = AlignItems.CENTER) {
            width = CssSize(100, UNIT.perc)
            padding = 12.px

            vPanel(spacing = 2) {
                addCssClass("usage-plan-info")
                span("ACTIVE PLAN") { addCssClass("label-caps"); color = Color("#5e6adc") }
                span("PRO COMMANDER") { addCssClass("credit-amount") }
                span("$9.99 / month  ·  Renews in 16 days") { color = Color("#a0a8cc") }
            }
            hPanel(spacing = 8) {
                addCssClass("usage-plan-actions")
                button("MANAGE") { addCssClass("btn"); addCssClass("btn-secondary-action") }
                button("UPGRADE TO ELITE") { addCssClass("btn"); addCssClass("btn-primary-action") }
            }
        }
    }

    private fun buildHistoryCard(): VPanel = vPanel(spacing = 4) {
        width = CssSize(100, UNIT.perc)
        paddingLeft = 24.px
        paddingRight = 24.px
        paddingTop = 4.px
        paddingBottom = 24.px

        h4("RECENT DEDUCTIONS") {
            addCssClass("label-caps")
            color = Color("#a0a8cc")
        }

        val historyCard = SimplePanel(className = "usage-history-list") {
            width = CssSize(100, UNIT.perc)
            padding = 8.px
        }
        historyCard.add(historyHost)
        add(historyCard)
    }

    private fun renderHistory(entries: List<UsageEntry>)
    {
        historyHost.removeAll()
        if (entries.isEmpty())
        {
            historyHost.add(span("No deductions yet for this window.") {
                addCssClass("usage-history-empty")
                color = Color("#a0a8cc")
            })
            return
        }
        entries.take(50).forEach { entry ->
            val row = hPanel(className = "usage-history-row", alignItems = AlignItems.CENTER) {
                width = CssSize(100, UNIT.perc)
                padding = 8.px
                icon(iconForKey(entry.sourceIconKey)) {
                    addCssClass("usage-history-icon")
                    color = Color("#7a86e8")
                    width = CssSize(20, UNIT.px)
                }
                span(entry.sourceLabel) { color = Color("#dde0f5"); marginLeft = 8.px; marginRight = 8.px }
                span(BillingFormatting.formatCreditsDelta(entry.creditsDelta)) { color = Color("#f5a623"); marginRight = 8.px }
                span("bal ${BillingFormatting.formatCredits(entry.balanceAfter)}") { color = Color("#a0a8cc"); marginRight = 8.px }
                span(BillingFormatting.formatRelativeTime(entry.timestampMillis)) { color = Color("#a0a8cc") }
            }
            historyHost.add(row)
        }
        if (entries.size > 50)
        {
            historyHost.add(span("Showing 50 of ${entries.size} entries. Open a specific game from the BY GAME strip to filter.") {
                addCssClass("label-caps")
                color = Color("#a0a8cc")
            })
        }
    }

    private fun iconForKey(key: String): String = when (key)
    {
        "turn-resolution" -> "fas fa-bolt"
        "agent-run" -> "fas fa-robot"
        "narrative" -> "fas fa-feather"
        "judge" -> "fas fa-balance-scale"
        "geo-politics" -> "fas fa-globe"
        "purchase" -> "fas fa-cart-shopping"
        "refill" -> "fas fa-arrows-rotate"
        "reset" -> "fas fa-undo"
        else -> "fas fa-circle"
    }

    private fun formatDayLabel(dayStartMillis: Long): String
    {
        val date = kotlin.js.Date(dayStartMillis)
        val days = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        return days[date.getDay()]
    }
}