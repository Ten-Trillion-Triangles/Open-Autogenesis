package ui.billing

import io.kvision.core.AlignItems
import io.kvision.core.Col
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
import org.ttt.autogenesis.network.RpcJson
import structs.account.UsageLedger

/**
 * In-game SHOP modal — Stitch V1 (credits-first, tabbed).
 *
 * Tabs:
 * - CREDITS: 4-card grid (500/1,250 + bonus/3,000 POPULAR/8,000) with prices and PURCHASE buttons.
 * - SUBSCRIPTIONS: 3 tier rows (FREE/PRO/ELITE) with feature lists.
 * - UPGRADE: comparison matrix (informational).
 *
 * The header shows the live credit balance from [BillingState]. PURCHASE/CHOOSE
 * buttons apply a local credit delta via [BillingState.applyLocalCreditDelta]
 * and log the action. The server-side payment integration is a future pass.
 */
class ShopOverlay : BillingOverlayWindow(
    modalTitle = "SHOP",
    modalWidth = 960,
    modalMaxHeight = 720
)
{
    private enum class Tab { CREDITS, SUBSCRIPTIONS, UPGRADE }

    private var activeTab: Tab = Tab.CREDITS
    private val tabPanels: MutableMap<Tab, VPanel> = mutableMapOf()
    private val tabButtons: MutableMap<Tab, Button> = mutableMapOf()
    private val balancePill = span("💎 —")

    init
    {
        // BillingOverlayWindow's super init block intentionally does NOT call
        // buildBody(), so we can wait until our own field initializers (like
        // balancePill) have completed. This avoids a TypeError where
        // addCssClass() is called on an undefined field reference.
        initContent()
    }

    override fun buildContent(host: VPanel)
    {
        // Top sub-header: balance pill on the right.
        Logger.debug(LogCategory.UI, "ShopOverlay.buildBody[1/6]: adding subheader")
        host.add(
            hPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.SPACEBETWEEN) {
                addCssClass("billing-modal-subheader")
                width = CssSize(100, UNIT.perc)
                paddingLeft = 24.px
                paddingRight = 24.px
                paddingTop = 0.px
                paddingBottom = 8.px

                span("Credits never expire. Cancel anytime.") {
                    addCssClass("billing-modal-subtitle")
                }

                hPanel(spacing = 8, alignItems = AlignItems.CENTER) {
                    addCssClass("credits-container")
                    balancePill.addCssClass("credit-amount")
                    add(balancePill)
                }
            }
        )

        // Tab strip
        Logger.debug(LogCategory.UI, "ShopOverlay.buildBody[2/6]: adding tab strip")
        host.add(buildTabStrip())

        // Tab content panels
        Logger.debug(LogCategory.UI, "ShopOverlay.buildBody[3/6]: adding credits tab")
        host.add(buildCreditsTab().also { tabPanels[Tab.CREDITS] = it })
        Logger.debug(LogCategory.UI, "ShopOverlay.buildBody[4/6]: adding subscriptions tab")
        host.add(buildSubscriptionsTab().also { tabPanels[Tab.SUBSCRIPTIONS] = it })
        Logger.debug(LogCategory.UI, "ShopOverlay.buildBody[5/6]: adding upgrade tab")
        host.add(buildUpgradeTab().also { tabPanels[Tab.UPGRADE] = it })

        // "GO MONTHLY" footer strip
        Logger.debug(LogCategory.UI, "ShopOverlay.buildBody[6/6]: adding footer")
        host.add(buildFooterStrip())

        refreshBalance()
        selectTab(Tab.CREDITS)

        // Keep the balance pill in sync with cache updates.
        BillingState.addListener { refreshBalance() }
    }

    /**
     * Re-pulls the credit balance from the cache, then refreshes the visible
     * pill. Called on open and whenever [BillingState] notifies of a change.
     */
    private fun refreshBalance()
    {
        scope.launch {
            try
            {
                val status = BillingState.billing ?: BillingState.refreshBilling()
                balancePill.content = "💎 ${BillingFormatting.formatCredits(status.credits)}"
                Logger.debug(LogCategory.UI, "ShopOverlay: balance updated to ${status.credits}")
            }
            catch (e: Throwable)
            {
                Logger.warn(LogCategory.UI, "ShopOverlay: refreshBalance failed: ${e.message}")
            }
        }
    }

    private fun buildTabStrip(): HPanel
    {
        return hPanel(spacing = 8) {
                addCssClass("billing-tabs")
            width = CssSize(100, UNIT.perc)
            paddingLeft = 24.px
            paddingRight = 24.px
            paddingTop = 4.px
            paddingBottom = 12.px

            tabButtons[Tab.CREDITS] = button("CREDITS") {
                addCssClass("billing-tab")
                onClick { selectTab(Tab.CREDITS) }
            }
            tabButtons[Tab.SUBSCRIPTIONS] = button("SUBSCRIPTIONS") {
                addCssClass("billing-tab")
                onClick { selectTab(Tab.SUBSCRIPTIONS) }
            }
            tabButtons[Tab.UPGRADE] = button("UPGRADE") {
                addCssClass("billing-tab")
                onClick { selectTab(Tab.UPGRADE) }
            }
        }
    }

    private fun selectTab(tab: Tab)
    {
        activeTab = tab
        tabButtons.forEach { (key, btn) ->
            if (key == tab) btn.addCssClass("billing-tab-active") else btn.removeCssClass("billing-tab-active")
        }
        tabPanels.forEach { (key, panel) ->
            panel.display = if (key == tab) Display.FLEX else Display.NONE
        }
    }

    private fun buildCreditsTab(): VPanel = vPanel(spacing = 16) {
        width = CssSize(100, UNIT.perc)
        paddingLeft = 24.px
        paddingRight = 24.px
        paddingTop = 4.px
        paddingBottom = 8.px
        display = Display.NONE

        h4("BUY CREDITS") {
            addCssClass("label-caps")
            color = Color("#a0a8cc")
        }

        hPanel(spacing = 12) {
            width = CssSize(100, UNIT.perc)

            creditCard(credits = 500, price = "$4.99", popular = false, hasBonus = false)
            creditCard(credits = 1_250, price = "$9.99", popular = false, hasBonus = true)
            creditCard(credits = 3_000, price = "$19.99", popular = true, hasBonus = true)
            creditCard(credits = 8_000, price = "$49.99", popular = false, hasBonus = false)
        }
    }

    private fun HPanel.creditCard(credits: Int, price: String, popular: Boolean, hasBonus: Boolean)
    {
        val cardClass = if (popular) "shop-credit-card shop-credit-card-popular" else "shop-credit-card"
        add(SimplePanel(className = cardClass) {
            width = CssSize(25, UNIT.perc)
            padding = 16.px
            alignItems = AlignItems.CENTER
            justifyContent = JustifyContent.CENTER

            if (popular)
            {
                span("MOST POPULAR") {
                    addCssClass("shop-credit-popular-badge")
                }
            }
            if (hasBonus)
            {
                span("BONUS +10%") {
                    addCssClass("shop-credit-bonus-ribbon")
                }
            }

            icon("fas fa-gem") {
                addCssClass("credit-icon")
                color = Color("#7a86e8")
            }
            span("$credits") {
                addCssClass("credit-amount")
                fontSize = CssSize(28, UNIT.px)
            }
            span("Credits") {
                addCssClass("label-caps")
                color = Color("#a0a8cc")
            }
            span(price) {
                addCssClass("billing-credits-price")
                fontSize = CssSize(20, UNIT.px)
                fontWeight = FontWeight.BOLD
            }
            button("PURCHASE") {
                addCssClass("btn")
                addCssClass("btn-add-credits")
                width = CssSize(100, UNIT.perc)
                marginTop = 8.px
                onClick { onPurchase(credits, price) }
            }
        })
    }

    private fun buildSubscriptionsTab(): VPanel = vPanel(spacing = 12) {
        width = CssSize(100, UNIT.perc)
        paddingLeft = 24.px
        paddingRight = 24.px
        paddingTop = 4.px
        paddingBottom = 8.px
        display = Display.NONE

        h4("CHOOSE YOUR PLAN") {
            addCssClass("label-caps")
            color = Color("#a0a8cc")
        }

        hPanel(spacing = 8) {
            addCssClass("billing-tabs")
            button("MONTHLY") { addCssClass("billing-tab"); addCssClass("billing-tab-active") }
            button("ANNUAL") { addCssClass("billing-tab") }
            span("Save 20%") { addCssClass("shop-credit-bonus-ribbon") }
        }

        tierRow("FREE", "$0", listOf("Basic access for casual users", "500 Monthly Credits", "Standard Agent Access"), isCurrent = false, isBestValue = false)
        tierRow("PRO COMMANDER", "$9.99", listOf("Enhanced power for serious operators", "2,000 Monthly Credits", "Advanced Agent Suite", "Priority Processing"), isCurrent = true, isBestValue = false)
        tierRow("ELITE COMMANDER", "$19.99", listOf("The ultimate toolkit for master agents", "10,000 Monthly Credits", "All exclusive Elite agents"), isCurrent = false, isBestValue = true)
    }

    private fun VPanel.tierRow(name: String, price: String, features: List<String>, isCurrent: Boolean, isBestValue: Boolean)
    {
        val rowClasses: List<String> = when
        {
            isCurrent -> listOf("shop-tier-row", "shop-tier-row-current")
            isBestValue -> listOf("shop-tier-row", "shop-tier-row-best")
            else -> listOf("shop-tier-row")
        }
        add(
            hPanel(alignItems = AlignItems.CENTER) {
                rowClasses.forEach { addCssClass(it) }
                width = CssSize(100, UNIT.perc)
                padding = 12.px

                vPanel(spacing = 4) {
                    width = CssSize(70, UNIT.perc)
                    h3(name) {
                        color = Color("#ffffff")
                        addCssClass("billing-modal-title")
                    }
                    features.forEach { f ->
                        div {
                            icon("fas fa-check") {
                                color = Color("#7a86e8")
                                marginRight = 8.px
                            }
                            span(f) { color = Color("#dde0f5") }
                        }
                    }
                }
                vPanel(spacing = 8, alignItems = AlignItems.CENTER) {
                    width = CssSize(30, UNIT.perc)
                    if (isCurrent) span("CURRENT PLAN") { addCssClass("label-caps"); color = Color("#5e6adc") }
                    if (isBestValue) span("BEST VALUE") { addCssClass("shop-credit-bonus-ribbon") }
                    span("$price") {
                        addCssClass("credit-amount")
                        fontSize = CssSize(28, UNIT.px)
                    }
                    span("/month") { color = Color("#a0a8cc") }
                    button(if (isCurrent) "CURRENT" else "CHOOSE") {
                        if (isCurrent) { addCssClass("btn"); addCssClass("btn-secondary-action") } else { addCssClass("btn"); addCssClass("btn-add-credits") }
                        width = CssSize(100, UNIT.perc)
                        onClick { onChoose(name, price) }
                    }
                }
            }
        )
    }

    private fun buildUpgradeTab(): VPanel = vPanel(spacing = 12) {
        width = CssSize(100, UNIT.perc)
        paddingLeft = 24.px
        paddingRight = 24.px
        paddingTop = 4.px
        paddingBottom = 8.px
        display = Display.NONE

        h4("COMPARE PLANS") {
            addCssClass("label-caps")
            color = Color("#a0a8cc")
        }

        vPanel(spacing = 0) {
            width = CssSize(100, UNIT.perc)
            addCssClass("shop-tier-row")
            comparisonHeader()
            comparisonRow("Monthly Credits", "500", "2,000", "10,000")
            comparisonRow("Agent Runs", "10", "50", "Unlimited")
            comparisonRow("Narrative Generations", "5", "20", "Unlimited")
            comparisonRow("Multiplayer Sessions", "1", "5", "20")
            comparisonRow("Priority Processing", "—", "✓", "✓")
            comparisonRow("Exclusive Elite Agents", "—", "—", "✓")
        }
    }

    private fun VPanel.comparisonHeader()
    {
        add(
            hPanel(alignItems = AlignItems.CENTER) {
                width = CssSize(100, UNIT.perc)
                padding = 8.px
                span("Feature") { addCssClass("label-caps"); color = Color("#a0a8cc"); width = CssSize(40, UNIT.perc) }
                span("Free") { addCssClass("label-caps"); color = Color("#a0a8cc"); width = CssSize(20, UNIT.perc) }
                span("Pro") { addCssClass("label-caps"); color = Color("#a0a8cc"); width = CssSize(20, UNIT.perc) }
                span("Elite") { addCssClass("label-caps"); color = Color("#a0a8cc"); width = CssSize(20, UNIT.perc) }
            }
        )
    }

    private fun VPanel.comparisonRow(feature: String, free: String, pro: String, elite: String)
    {
        add(
            hPanel(alignItems = AlignItems.CENTER) {
                width = CssSize(100, UNIT.perc)
                padding = 8.px
                addCssClass("billing-comparison-row")
                span(feature) { color = Color("#dde0f5"); width = CssSize(40, UNIT.perc) }
                span(free) { color = Color("#a0a8cc"); width = CssSize(20, UNIT.perc); justifyContent = JustifyContent.CENTER }
                span(pro) { color = Color("#dde0f5"); width = CssSize(20, UNIT.perc) }
                span(elite) { color = Color("#dde0f5"); width = CssSize(20, UNIT.perc) }
            }
        )
    }

    private fun buildFooterStrip(): VPanel = vPanel(spacing = 4) {
        width = CssSize(100, UNIT.perc)
        paddingLeft = 24.px
        paddingRight = 24.px
        paddingTop = 16.px
        paddingBottom = 24.px

        hPanel(alignItems = AlignItems.CENTER) {
                addCssClass("shop-credit-card")
            width = CssSize(100, UNIT.perc)
            padding = 16.px

            span("GO MONTHLY") { addCssClass("shop-credit-popular-badge") }
            span("Subscribe and get 20% bonus credits every month + exclusive Elite-tier agents.") {
                color = Color("#dde0f5")
                marginLeft = 16.px
                marginRight = 16.px
            }
            button("VIEW PLANS") {
                addCssClass("btn")
                addCssClass("btn-secondary-action")
                marginLeft = CssSize(16, UNIT.px)
                onClick { selectTab(Tab.SUBSCRIPTIONS) }
            }
        }

        span("Prices in USD. Credits never expire. Cancel anytime.") {
            addCssClass("label-caps")
            color = Color("#a0a8cc")
            marginTop = 8.px
        }
    }

    private fun onPurchase(credits: Int, price: String)
    {
        Logger.info(LogCategory.NETWORK, "ShopOverlay: PURCHASE pressed for $credits credits @ $price (mock — local credit delta applied)")
        BillingState.applyLocalCreditDelta(credits.toDouble())
    }

    private fun onChoose(tier: String, price: String)
    {
        Logger.info(LogCategory.NETWORK, "ShopOverlay: CHOOSE pressed for tier=$tier @ $price/month (mock)")
    }
}
