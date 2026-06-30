package ui.gameplay

import io.kvision.core.AlignItems
import io.kvision.core.Component
import io.kvision.core.FontWeight
import io.kvision.core.JustifyItems
import io.kvision.core.Overflow
import io.kvision.html.*
import io.kvision.panel.*
import io.kvision.utils.px
import io.kvision.utils.perc
import io.kvision.window.Window
import ui.gameplay.networking.ActionHistoryClientHandlers
import structs.GameHistory as GameHistoryData
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import ui.gameplay.AgentWorkStreamManager
import org.ttt.autogenesis.network.ThinkingUpdateData

/**
 * A modal window that displays the player's game history across three tabbed views:
 * - **Story**: Chronological narrative of all turns with results
 * - **Details**: Detailed breakdown of the most recent turn (resources, territories, etc.)
 * - **Geopolitics**: Current geopolitical assessment from the server
 *
 * This window supports both live gameplay updates via [ActionHistoryClientHandlers] and
 * a demo mode for testing that simulates turn updates.
 *
 * ## Visual Feedback
 * The window provides visual feedback through:
 * - Window-level flash animation when new data arrives
 * - Tab-specific flash animations to indicate which content updated
 *
 * ## Demo Mode
 * When [demoMode] is enabled, the window runs an infinite loop that creates and updates
 * mock history entries every 2 seconds to demonstrate the update mechanism.
 *
 * @see ActionHistoryClientHandlers for the RPC integration that feeds this window
 * @see GameHistoryData for the data structure representing each turn
 */
class GameHistoryWindow : Window(className = "login-widget-window")
{
    /**
     * Scrollable panel containing the chronological story view of all turns.
     * Each entry is built by [buildStoryEntry] and added via [refreshStoryPanel].
     */
    private val storyPanel = vPanel {
        spacing = 12
        padding = 12.px
        overflow = Overflow.AUTO
        alignItems = AlignItems.STRETCH
        width = 100.perc
        height = 100.perc
    }

    /**
     * Panel displaying detailed breakdown of the most recent turn.
     * Hosting a list of per-turn detail cards rendered via [createDetailCard] / [refreshDetailCard].
     */
    private val summaryPanel = vPanel {
        spacing = 10
        padding = 12.px
        alignItems = AlignItems.STRETCH
        overflow = Overflow.AUTO
        width = 100.perc
        height = 100.perc
    }

    /**
     * Map per-turn detail card panels by GameHistory ID.
     * Used to refresh the same card in place when a turnaround is updated.
     */
    private val detailCardMap = mutableMapOf<String, VPanel>()

    /**
     * Master list of all game history entries received so far.
     * Entries are identified by their [GameHistoryData.id] field to support updates.
     * This list is cleared when [demoMode] is disabled.
     */
    private val historyEntries = mutableListOf<GameHistoryData>()

    /**
     * Scrollable panel displaying the current geopolitical assessment text.
     * Updated via [updateGeopolitics] when the server broadcasts new assessments.
     */
    private val geopoliticsPanel = vPanel {
        spacing = 12
        padding = 12.px
        overflow = Overflow.AUTO
        alignItems = AlignItems.STRETCH
        width = 100.perc
        height = 100.perc
    }

    /**
     * Cached copy of the current geopolitical assessment string.
     * Used to preserve state and for potential future reference.
     */
    private var currentGeopoliticalAssessment: String = ""

    /**
     * Timer ID for the window-level flash animation.
     * Cleared and reset on each [flashWindow] call to prevent overlapping animations.
     */
    private var flashTimer: Int? = null

    /**
     * Timer ID for tab-specific flash animations.
     * Currently unused but reserved for future tab flash coordination.
     */
    private var tabFlashTimer: Int? = null

    /**
     * Coroutine job handle for the demo mode loop.
     * Cancelled when demo mode is disabled or when starting a new loop.
     * @see startDemoLoop
     * @see stopDemoLoop
     */
    private var demoJob: kotlinx.coroutines.Job? = null

    /**
     * Coroutine scope tied to the main UI thread.
     * Used to launch the demo mode coroutine that simulates turn updates.
     */
    private val scope = kotlinx.coroutines.MainScope()

    /**
     * Reference to the tab panel container.
     * Stored to enable programmatic tab manipulation and class additions if needed.
     * Currently used primarily for structural reference.
     */
    private var tabsPanel: io.kvision.panel.TabPanel? = null
    
    // Custom Stack Widget Properties
    private var activeTabIndex = 0
    private var stackPanel: io.kvision.panel.StackPanel? = null
    private val tabButtons = mutableListOf<io.kvision.html.Button>()

    /**
     * Controls whether the window runs in demo mode with simulated turn updates.
     *
     * When set to `true`:
     * - Starts [startDemoLoop] which creates mock history entries every 2 seconds
     * - Useful for testing UI updates without requiring a live game session
     *
     * When set to `false`:
     * - Stops the demo loop via [stopDemoLoop]
     * - Clears all history entries and UI panels
     * - Resets the window to a clean state for live gameplay
     *
     * **Default**: `true` (demo mode active on initialization)
     *
     * **Warning**: Disabling demo mode will clear all existing history data.
     */
    var demoMode: Boolean = false
        set(value)
        {
            field = value
            if (value)
            {
                Logger.debug(LogCategory.UI, "[GameHistoryWindow] Demo Mode ACTIVATED")
                startDemoLoop()
            }
            else
            {
                Logger.debug(LogCategory.UI, "[GameHistoryWindow] Demo Mode DEACTIVATED")
                stopDemoLoop()
                historyEntries.clear()
                storyPanel.removeAll()
                clearDetailCards()
            }
        }

    init {
        width = 400.px
        caption = "Game History"
        position = io.kvision.core.Position.ABSOLUTE
        left = 0.px
        top = 0.px
        setStyle("height", "calc(100vh - 200px)")
        justifyItems = JustifyItems.CENTER
        captionContainer.justifyItems = JustifyItems.CENTER
        captionContainer.alignSelf = AlignItems.CENTER
        captionContainer.justifySelf = JustifyItems.CENTER
        captionContainer.align = Align.CENTER
        captionContainer.fontSize = 20.px
        
        // Root Container: Flex Column
        vPanel {
            width = 100.perc
            height = 100.perc
            
            // 1. Header (Buttons) - Fixed Height
            hPanel(justify = io.kvision.core.JustifyContent.CENTER) {
                width = 100.perc
                height = 42.px
                addCssClass("gh-header-container")
                
                val btnStory = button("Story", icon = "fas fa-book-open", className = "gh-tab-button") {
                    onClick { switchTab(0) }
                }
                val btnDetails = button("Details", icon = "fas fa-list", className = "gh-tab-button") {
                    onClick { switchTab(1) }
                }
                val btnGeopolitics = button("Geopolitics", icon = "fas fa-globe", className = "gh-tab-button") {
                    onClick { switchTab(2) }
                }
                val btnWorkStream = button("Work Stream", icon = "fas fa-stream", className = "gh-tab-button") {
                    onClick { AgentWorkStreamManager.openStream() }
                }
                
                tabButtons.add(btnStory)
                tabButtons.add(btnDetails)
                tabButtons.add(btnGeopolitics)
            }
            
            // 2. Content (StackPanel) - Fills remaining space
            stackPanel = stackPanel {
                width = 100.perc
                height = 100.perc // Will be controlled by flex parent, but good to be explicit
                addCssClass("gh-stack-container")
                // Important: Flex property to fill vertical space
                flexGrow = 1
                overflow = Overflow.HIDDEN
                
                // Index 0: Story
                add(storyPanel)
                // Index 1: Details
                add(summaryPanel)
                // Index 2: Geopolitics
                add(geopoliticsPanel)
            }
        }
        
        // Initialize Default Tab
        switchTab(0)

        updateGeopolitics("Pending assessment...", silent = true)
        ActionHistoryClientHandlers.attachWindow(this)
        ActionHistoryClientHandlers.fetchLatestAssessment()
        
        // Trigger demo mode if initialized to true
        if (demoMode)
        {
            Logger.debug(LogCategory.UI, "[GameHistoryWindow] Demo Mode Initialized to TRUE")
            startDemoLoop()
        }
    }
    
    private fun switchTab(index: Int)
    {
        val oldIndex = activeTabIndex
        activeTabIndex = index
        stackPanel?.activeIndex = index
        
        // Update Buttons
        tabButtons.forEachIndexed { i, btn ->
            if (i == index)
            {
                btn.addCssClass("gh-tab-button-active")
                btn.removeCssClass("gh-tab-glow") // active clears glow/unread status
            }
            else
            {
                btn.removeCssClass("gh-tab-button-active")
            }
        }
        
        // Scroll Logic on Click: Always scroll to bottom of the new View
        kotlinx.browser.window.setTimeout({
             val targetPanel = when (index)
             {
               0 -> storyPanel
               1 -> summaryPanel
               2 -> geopoliticsPanel
               else -> null
            }
            targetPanel?.getElement()?.let { el ->
                 el.scrollTop = el.scrollHeight.toDouble()
            }
        }, 50)
    }

    /**
     * Records a new [GameHistoryData] entry or updates an existing one if the ID matches.
     *
     * ## Behavior
     * - **New Entry**: If no entry with matching [GameHistoryData.id] exists, adds it to [historyEntries]
     * - **Update**: If an entry with matching ID exists, replaces it in-place
     *
     * ## Side Effects
     * - Always calls [refreshStoryPanel] to re-render the story tab
     * - Creates or refreshes the detail card for each turn via [createDetailCard]/[refreshDetailCard]
     * - Triggers [flashWindow] to provide visual feedback
     * - For updates, triggers [flashTab] on the Story tab (index 0)
     *
     * ## Update Detection
     * Entries are matched by their `id` field. This allows the server to send an initial
     * "planning" state and then update it with results without creating duplicate entries.
     *
     * @param entry The history entry to record or update. Must have a valid `id` field.
     */
    fun recordGameHistoryEntry(entry: GameHistoryData)
    {
        Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] GameHistoryWindow.recordGameHistoryEntry Called")
        Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] Processing Entry ID: ${entry.id}")
        Logger.debug(LogCategory.UI, "[COUNTER_RESPONSE] Entry has ${entry.counterResponses.size} counter-responses")

        // Check for existing by ID
        val existingIndex = historyEntries.indexOfFirst { it.id == entry.id }

        if (existingIndex != -1)
        {
            Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] >> MATCH FOUND at index $existingIndex")
            Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] Previous Entry State: StoryLen=${historyEntries[existingIndex].turnStory.length}, Result=${historyEntries[existingIndex].turnResult}")
            Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] New Entry State: StoryLen=${entry.turnStory.length}, Result=${entry.turnResult}")

            historyEntries[existingIndex] = entry

            detailCardMap[entry.id]?.let { card ->
                 Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] Refreshing existing detail card for ID ${entry.id}")
                 refreshDetailCard(card, entry, existingIndex + 1)
            } ?: run {
                 Logger.warn(LogCategory.UI, "[GAME_HISTORY_LOG] Missing detail card for updated entry ID ${entry.id}. Rebuilding fallback card.")
                 val fallbackCard = createDetailCard(entry, existingIndex + 1)
                 detailCardMap[entry.id] = fallbackCard
                 summaryPanel.add(fallbackCard)
            }
            // Flash effects for update
            flashWindow()
            flashTab(0) // Default to flashing Story tab on update
        }
        else
        {
            // If this character already has stub entries (blank turnStory) with thinking accumulated,
            // transfer ALL their thinking to the real entry and remove the stubs to avoid duplicates.
            // NOTE: Using exact match == here, same as appendThinkingUpdate - may miss stubs with whitespace/case differences (Bug 4)
            val stubIndices = historyEntries.indices.filter { idx ->
                val e = historyEntries[idx]
                e.turnPlayer == entry.turnPlayer && e.turnStory.isBlank() && e.id != entry.id
            }.reversed()
            Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] recordGameHistoryEntry: Looking for stubs for turnPlayer='${entry.turnPlayer}', found ${stubIndices.size} stub indices")
            if (stubIndices.isNotEmpty()) {
                stubIndices.forEach { idx ->
                    val stub = historyEntries[idx]
                    Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] Stub at $idx: turnPlayer='${stub.turnPlayer}', turnResult='${stub.turnResult}', thinkingUpdates=${stub.thinkingUpdates.size}")
                }
            }
            var totalTransferred = 0
            for (idx in stubIndices) {
                val stub = historyEntries[idx]
                entry.thinkingUpdates.addAll(stub.thinkingUpdates)
                totalTransferred += stub.thinkingUpdates.size
                historyEntries.removeAt(idx)
                detailCardMap.remove(stub.id)?.let { summaryPanel.remove(it) }
            }
            if (totalTransferred > 0) {
                Logger.info(LogCategory.UI, "[GAME_HISTORY_LOG] Transferred $totalTransferred thinking updates from ${stubIndices.size} stub entries to real entry for ${entry.turnPlayer}")
            } else if (historyEntries.any { it.turnPlayer == entry.turnPlayer && it.turnStory.isBlank() && it.id != entry.id }) {
                // Found stubs but none were transferred - indicates name matching issue
                Logger.warn(LogCategory.UI, "[GAME_HISTORY_LOG] WARNING: Stubs exist for ${entry.turnPlayer} but were not transferred due to name matching issue. This may cause Bug 3 (UI stuck on prior output).")
            }

            Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] >> NO MATCH FOUND. Appending New Entry.")
            Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] New Entry ID: ${entry.id} assigned to Index ${historyEntries.size}")
            historyEntries.add(entry)
            val newCard = createDetailCard(entry, historyEntries.size)
            detailCardMap[entry.id] = newCard
            summaryPanel.add(newCard)
            // Also flash on new entry? Usually yes.
            flashWindow()
        }

        // Re-render Story Panel (simplest way to ensure order and updates)
        refreshStoryPanel()
        Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] Story Panel Refreshed.")
    }

    /**
     * Rebuilds the entire story panel from scratch using the current [historyEntries] list.
     *
     * This is called after every [recordGameHistoryEntry] to ensure the UI reflects
     * the current state, including any updates to existing entries.
     *
     * ## Performance Note
     * This performs a full re-render rather than incremental updates. For large history
     * lists, this could become inefficient, but it ensures correctness and simplicity.
     */
    private fun refreshStoryPanel()
    {
        Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] refreshStoryPanel Called. Total Entries: ${historyEntries.size}")
        storyPanel.removeAll()
        historyEntries.forEachIndexed { index, history ->
             // console.log("[GAME_HISTORY_LOG]   - Rendering Entry $index: ID=${history.id}") // Detailed loop log if needed, maybe too spammy
             storyPanel.add(buildStoryEntry(history, index + 1))
        }
        Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] refreshStoryPanel Complete.")
    }

    /**
     * Updates the geopolitical assessment text displayed in the Geopolitics tab.
     *
     * This method is called by [ActionHistoryClientHandlers] when the server broadcasts
     * a new geopolitical assessment via RPC.
     *
     * ## Side Effects
     * - Clears and rebuilds the [geopoliticsPanel] with the new text
     * - Triggers [flashWindow] to indicate new data
     * - Triggers [flashTab] on the Geopolitics tab (index 2)
     *
     * @param assessment The new assessment text to display. Rendered with PREWRAP whitespace.
     */
    fun updateGeopolitics(assessment: String, silent: Boolean = false)
    {
        Logger.debug(LogCategory.UI, "[GEOPOLITICS DEBUG] GameHistoryWindow.updateGeopolitics called.")
        
        currentGeopoliticalAssessment = assessment
        geopoliticsPanel.removeAll()
        geopoliticsPanel.add(p(currentGeopoliticalAssessment) {
            whiteSpace = io.kvision.core.WhiteSpace.PREWRAP
        })
        
        if (!silent)
        {
            flashWindow()
            flashTab(2) // Flash Geopolitics tab (index 2)
        }
    }

    /**
     * Constructs a UI panel for a single history entry in the Story tab.
     *
     * ## Display Logic
     * - Always shows: Turn number and player name (bolded)
     * - Conditionally shows:
     *   - [GameHistoryData.turnStory] if not blank (preferred, with PREWRAP)
     *   - [GameHistoryData.turnAction] if story is blank but action exists
     *   - [GameHistoryData.turnResult] if present (in light green)
     *
     * ## Fallback Behavior
     * If both `turnStory` and `turnAction` are blank, only the header is shown.
     * This can happen during initial "planning" states before the story is generated.
     *
     * @param entry The history data to render
     * @param index The 1-based turn number for display (not the array index)
     * @return A configured [vPanel] containing the formatted entry
     */
    private fun buildStoryEntry(entry: GameHistoryData, index: Int) = vPanel(spacing = 6) {
        padding = 10.px
        // logic roughly same as before
        add(p("Turn $index · ${entry.turnPlayer}") {
            fontWeight = FontWeight.BOLD
        })
        if (entry.turnStory.isNotBlank())
        {
            add(p(entry.turnStory) {
                whiteSpace = io.kvision.core.WhiteSpace.PREWRAP
            })
        }
        else if (entry.turnAction.isNotBlank())
        {
            add(p(entry.turnAction))
        }
        
        // If there's a result, show it here too? 
        // User requirements implied "append that with more data like the result".
        // Let's add result to story view if present, so the update is visible in story tab.
        if (entry.turnResult.isNotBlank())
        {
             add(p("Result: ${entry.turnResult}") {
                 fontSize = 14.px
                 color = io.kvision.core.Color.name(io.kvision.core.Col.LIGHTGREEN)
             })
        }
    }

    /**
     * Renders the Details tab with a breakdown of a single turn's data.
     *
     * This is typically called with the most recent entry to show "what just happened."
     *
     * ## Displayed Fields
     * - Player name (header)
     * - Turn result/outcome (if present)
     * - Action taken (if present)
     * - Resources used (if any)
     * - Territories won/lost (via [renderDetailList])
     * - Resources gained/lost (via [renderDetailList])
     *
     * ## Blank Handling
     * Fields are only shown if they contain data. Empty lists and blank strings are skipped.
     *
     * @param entry The history entry to summarize in the Details tab
     */
    private fun createDetailCard(entry: GameHistoryData, turnIndex: Int) = vPanel(spacing = 10) {
        padding = 12.px
        addCssClass("gh-detail-card")
        renderDetailCardContent(this, entry, turnIndex)
    }

    private fun refreshDetailCard(card: VPanel, entry: GameHistoryData, turnIndex: Int)
    {
        card.removeAll()
        renderDetailCardContent(card, entry, turnIndex)
    }

    private fun renderDetailCardContent(target: VPanel, entry: GameHistoryData, turnIndex: Int)
    {
        Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] renderDetailCardContent for Entry ID: ${entry.id}")
        Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] turnResult = '${entry.turnResult}' (length ${entry.turnResult.length})")
        Logger.debug(LogCategory.UI, "[COUNTER_RESPONSE] Rendering ${entry.counterResponses.size} counter-responses in Details card")

        target.add(p("Turn $turnIndex for ${entry.turnPlayer}") {
            fontWeight = FontWeight.BOLD
        })

        var detailsRenderedCount = 0
        if (entry.turnResult.isNotBlank())
        {
            Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] Adding turnResult to detail card: '${entry.turnResult.take(100)}'")
            target.add(p("Outcome: ${entry.turnResult}"))
            detailsRenderedCount++
        }
        else
        {
            Logger.warn(LogCategory.UI, "[GAME_HISTORY_LOG] turnResult is BLANK - not adding to detail card")
        }

        if (entry.turnAction.isNotBlank())
        {
            target.add(p("Action: ${entry.turnAction}"))
            detailsRenderedCount++
        }

        Logger.debug(LogCategory.UI, "[COUNTER_RESPONSE] Calling renderDetailList for counter responses (${entry.counterResponses.size})")
        renderDetailList("Counter-Responses", entry.counterResponses, target)

        if (entry.usingResources.isNotEmpty())
        {
            target.add(p("Resources Used: ${entry.usingResources.joinToString(", ")}"))
            detailsRenderedCount++
        }

        renderDetailList("Territories Won", entry.territoryGained, target)
        renderDetailList("Territories Lost", entry.territoryLost, target)
        renderDetailList("Resources Gained", entry.resourcesWon, target)
        renderDetailList("Resources Lost", entry.resourcesLost, target)
        renderStatBuffs(entry.statBuffsGained, target)

        renderAffectedPlayers(entry.affectedPlayers, target)
        renderExchanges(entry.territoryExchanges, entry.assetExchanges, target)

        // Render AI Thinking updates inline
        if (entry.thinkingUpdates.isNotEmpty())
        {
            target.add(p("AI Thinking") {
                fontWeight = FontWeight.BOLD
                marginTop = 15.px
            })
            entry.thinkingUpdates.forEach { thinking ->
                val badge = if (thinking.isPlayer) "[PLAYER]" else "[NPC]"
                target.add(p("$badge ${thinking.characterName}: ${thinking.thinking}") {
                    color = io.kvision.core.Color.hex(0x888888)
                    fontFamily = "monospace"
                    fontSize = 13.px
                    marginLeft = 10.px
                })
            }
        }

        Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] renderDetailCardContent Complete. Sections: $detailsRenderedCount")
    }

    /**
     * Renders stat buffs gained from abstract resources in the Details tab.
     *
     * ## Display Format
     * Shows each abstract resource and its associated stat buffs in the format:
     * "Trade Agreement → +15 Wealth, +10 Reputation"
     *
     * ## Blank Filtering
     * Only displays if there are stat buffs to show.
     *
     * @param buffs Map of resource names to formatted stat buff strings
     */
    private fun renderStatBuffs(buffs: Map<String, String>, target: VPanel)
    {
        if(buffs.isEmpty())
        {
            return
        }
        target.add(p("Stat Buffs Gained") {
            fontWeight = FontWeight.BOLD
        })
        target.add(ul {
            buffs.forEach { (resource, buffText) ->
                li("$resource → $buffText")
            }
        })
    }

    /**
     * Renders affected players section showing outcomes for all players/NPCs involved in the turn.
     *
     * Displays each affected player with color-coded outcome indicator and their specific gains/losses.
     *
     * @param affectedPlayers Map of player names to their outcomes
     */
    private fun renderAffectedPlayers(affectedPlayers: Map<String, structs.PlayerOutcome>, target: VPanel)
    {
        if (affectedPlayers.isEmpty()) {
            return
        }
        
        target.add(p("Other Players Affected") {
            fontWeight = FontWeight.BOLD
            marginTop = 15.px
        })
        
        affectedPlayers.values.forEach { outcome ->
            val outcomeColor = when (outcome.netOutcome) {
                "Positive" -> io.kvision.core.Color.name(io.kvision.core.Col.LIGHTGREEN)
                "Negative" -> io.kvision.core.Color.name(io.kvision.core.Col.LIGHTCORAL)
                else -> io.kvision.core.Color.name(io.kvision.core.Col.LIGHTGRAY)
            }
            
            val outcomeSymbol = when (outcome.netOutcome) {
                "Positive" -> "✓"
                "Negative" -> "✗"
                else -> "○"
            }
            
            target.add(p("$outcomeSymbol ${outcome.playerName} (${outcome.netOutcome.uppercase()})") {
                color = outcomeColor
                fontWeight = FontWeight.BOLD
                marginLeft = 10.px
            })
            
            if (outcome.territoriesGained.isNotEmpty()) {
                target.add(p("  Territories Gained: ${outcome.territoriesGained.joinToString(", ")}") {
                    marginLeft = 20.px
                    fontSize = 13.px
                })
            }
            if (outcome.territoriesLost.isNotEmpty()) {
                target.add(p("  Territories Lost: ${outcome.territoriesLost.joinToString(", ")}") {
                    marginLeft = 20.px
                    fontSize = 13.px
                })
            }
            if (outcome.resourcesGained.isNotEmpty()) {
                target.add(p("  Resources Gained: ${outcome.resourcesGained.joinToString(", ")}") {
                    marginLeft = 20.px
                    fontSize = 13.px
                })
            }
            if (outcome.resourcesLost.isNotEmpty()) {
                target.add(p("  Resources Lost: ${outcome.resourcesLost.joinToString(", ")}") {
                    marginLeft = 20.px
                    fontSize = 13.px
                })
            }
        }
    }

    /**
     * Renders territory and asset exchanges showing transfers between players.
     *
     * Displays exchanges in "Name: From → To" format with arrows for clarity.
     *
     * @param territoryExchanges List of territory transfers
     * @param assetExchanges List of asset transfers
     */
    private fun renderExchanges(
        territoryExchanges: List<structs.TerritoryExchange>,
        assetExchanges: List<structs.AssetExchange>,
        target: VPanel
    )
    {
        if (territoryExchanges.isNotEmpty()) {
            target.add(p("Territory Transfers") {
                fontWeight = FontWeight.BOLD
                marginTop = 15.px
            })
            target.add(ul {
                territoryExchanges.forEach { exchange ->
                    val fromText = exchange.from.ifBlank { "(Contested)" }
                    val toText = exchange.to.ifBlank { "(Contested)" }
                    li("${exchange.territoryName}: $fromText → $toText")
                }
            })
        }
        
        if (assetExchanges.isNotEmpty()) {
            target.add(p("Asset Transfers") {
                fontWeight = FontWeight.BOLD
                marginTop = 15.px
            })
            target.add(ul {
                assetExchanges.forEach { exchange ->
                    val fromText = exchange.from.ifBlank { "(Lost)" }
                    val toText = exchange.to.ifBlank { "(Destroyed)" }
                    li("${exchange.assetName}: $fromText → $toText")
                }
            })
        }
    }

    /**
     * Renders a labeled bullet list in the Details tab if the list contains valid entries.
     *
     * ## Blank Filtering
     * Filters out blank strings before rendering. If all entries are blank, nothing is added.
     *
     * ## Usage
     * Called by [renderDetailCardContent] to display territories and resources won/lost.
     *
     * @param label The section header (e.g., "Territories Won")
     * @param entries The list of items to display. Blank entries are filtered out.
     */
    private fun renderDetailList(label: String, entries: List<String>, target: VPanel)
    {
        val validEntries = entries.filter { it.isNotBlank() }
        Logger.debug(LogCategory.UI, "[COUNTER_RESPONSE] renderDetailList('$label'): ${entries.size} total, ${validEntries.size} valid entries")
        if (validEntries.isEmpty())
        {
            Logger.debug(LogCategory.UI, "[COUNTER_RESPONSE] Skipping '$label' - no valid entries")
            return
        }
        Logger.debug(LogCategory.UI, "[COUNTER_RESPONSE] Rendering '$label' section with ${validEntries.size} items")
        target.add(p(label) {
            fontWeight = FontWeight.BOLD
        })
        target.add(ul {
            validEntries.forEach { value ->
                li(value)
            }
        })
    }

    // --- Visual Effects ---

    /**
     * Triggers a visual flash animation on the entire window to indicate new data.
     *
     * ## Animation Mechanism
     * - Removes the `game-history-flash` CSS class
     * - Forces a browser reflow by accessing `offsetWidth`
     * - Re-adds the class to restart the animation
     * - Removes the class after 600ms (matching the CSS animation duration)
     *
     * ## Timer Management
     * Clears any existing [flashTimer] to prevent overlapping animations.
     * This ensures the animation always plays fully even if called rapidly.
     *
     * **CSS Dependency**: Requires `game-history-flash` keyframe animation in night-mode.css
     */
    private fun flashWindow()
    {
        Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] flashWindow Triggered")
        flashTimer?.let { kotlinx.browser.window.clearTimeout(it) }
        removeCssClass("game-history-flash")
        
        // Force Reflow
        getElement()?.offsetWidth
        
        addCssClass("game-history-flash")
        flashTimer = kotlinx.browser.window.setTimeout({
            removeCssClass("game-history-flash")
        }, 600) // Match 0.5s animation (plus buffer)
    }

    /**
     * Triggers a flash animation on a specific tab to indicate which content was updated.
     *
     * ## DOM Manipulation
     * Uses direct DOM queries to find the tab link element and manipulate its CSS classes.
     * This is necessary because KVision's TabPanel doesn't expose tab elements directly.
     *
     * ## Animation Mechanism
     * - Queries for `.nav-item .nav-link` within this window's DOM subtree
     * - Removes `tab-flash-animation` class
     * - Re-adds the class to restart the animation
     * - Removes it after 600ms
     *
     * ## Edge Cases
     * - Returns silently if the window has no ID (shouldn't happen in practice)
     * - Returns silently if the tab index is out of bounds or element not found
     * - Uses `setTimeout(..., 0)` to ensure DOM is ready before querying
     *
     * @param tabIndex The 0-based index of the tab to flash (0=Story, 1=Details, 2=Geopolitics)
     */
    /**
     * Triggers a flash animation on a specific tab to indicate which content was updated.
     *
     * ## Persistent Glow
     * Adds the `gh-tab-glow` class to the tab button. This class is persistent (infinite animation)
     * and is only removed when the user clicks the tab (via [switchTab]).
     *
     * @param tabIndex The 0-based index of the tab to flash (0=Story, 1=Details, 2=Geopolitics)
     */
    internal fun flashTab(tabIndex: Int)
    {
        // Don't glow if we are already viewing this tab
        if (tabIndex == activeTabIndex) return

        if (tabIndex in 0 until tabButtons.size)
        {
            val btn = tabButtons[tabIndex]
            btn.addCssClass("gh-tab-glow")
        }
    }

    /**
     * Receives thinking updates from ActionHistoryClientHandlers and buffers them
     * by characterName for later correlation with a GameHistory entry.
     *
     * @param data The ThinkingUpdateData broadcast from the server
     */
    fun appendThinkingUpdate(data: ThinkingUpdateData) {
        Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] appendThinkingUpdate called for ${data.characterName} (isPlayer=${data.isPlayer}), thinking length=${data.thinking.length}, timestamp=${data.timestamp}")
        Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] Current historyEntries count=${historyEntries.size}")

        // Log all current turnPlayer names for debugging name matching issues (Bug 4 - Zuzu)
        val currentTurnPlayers = historyEntries.map { it.turnPlayer }.distinct()
        Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] Current turnPlayers in history: $currentTurnPlayers")

        // Check if there's an existing entry for this character that doesn't have a result yet.
        // This includes stub entries (with turnResult = "(Planning...)") as well as pending entries.
        // NOTE: Using exact match == which may differ from MapViewer.trim().equals(ignoreCase=true)
        val existingEntry = historyEntries.findLast {
            it.turnPlayer == data.characterName && (it.turnResult.isBlank() || it.turnResult == "(Planning...)")
        }

        Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] Name matching - looking for '${data.characterName}', found existingEntry=${existingEntry?.let { "id=${it.id}, turnResult='${it.turnResult}', turnPlayer='${it.turnPlayer}'" }}")

        // Detailed name comparison debugging for Bug 4
        if (existingEntry == null) {
            historyEntries.forEachIndexed { idx, entry ->
                val nameMatch = entry.turnPlayer == data.characterName
                val trimmedMatch = entry.turnPlayer.trim().equals(data.characterName.trim(), ignoreCase = true)
                if (nameMatch != trimmedMatch) {
                    Logger.warn(LogCategory.UI, "[GAME_HISTORY_LOG] Name matching discrepancy at index $idx: exactMatch=$nameMatch, trimmedIgnoreCaseMatch=$trimmedMatch, entry.turnPlayer='${entry.turnPlayer}', data.characterName='${data.characterName}'")
                }
            }
        }

        if (existingEntry != null) {
            // REAL-TIME UPDATE: Attach to existing pending turn's thinkingUpdates list
            existingEntry.thinkingUpdates.add(data)
            Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] Attached thinking to existing entry. Total thinking updates for this entry now: ${existingEntry.thinkingUpdates.size}")
            // Refresh the detail card inline to show new thinking immediately
            detailCardMap[existingEntry.id]?.let { card ->
                refreshDetailCard(card, existingEntry, historyEntries.indexOf(existingEntry) + 1)
            }
            // Flash the Details tab so player sees the update even if on Story tab
            if (activeTabIndex != 1) {
                flashTab(1)
            }
        } else {
            // No pending turn yet — create a stub entry immediately so thinking appears in real-time.
            // This stub entry will be updated/overwritten when the actual turnComplete arrives.
            // The stub is added to historyEntries (for Details display) but does NOT appear in
            // the Story tab because buildStoryEntry skips entries with blank turnStory.
            Logger.warn(LogCategory.UI, "[GAME_HISTORY_LOG] No pending entry for ${data.characterName}, creating stub for real-time thinking. This may indicate thinking arriving after turn completed (Bug 3).")
            val stubEntry = GameHistoryData(
                turnPlayer = data.characterName,
                turnAction = "",
                turnStory = "", // Blank — Story panel skips entries with blank turnStory
                turnResult = "(Planning...)"
            )
            stubEntry.thinkingUpdates.add(data)
            historyEntries.add(stubEntry)
            Logger.debug(LogCategory.UI, "[GAME_HISTORY_LOG] Stub entry created with id=${stubEntry.id}")
            val newCard = createDetailCard(stubEntry, historyEntries.size)
            detailCardMap[stubEntry.id] = newCard
            summaryPanel.add(newCard)
            // Flash the Details tab so player sees the real-time thinking
            if (activeTabIndex != 1) {
                flashTab(1)
            }
            flashWindow()
        }

        // Don't re-render story panel for thinking updates — they appear inline in Details only
    }

    // --- Demo Mode Logic ---

    /**
     * Starts an infinite coroutine loop that simulates turn updates for testing.
     *
     * ## Loop Behavior
     * 1. Creates a "planning" entry with initial action text
     * 2. Waits 2 seconds
     * 3. Updates the same entry (by ID) with a completed story and results
     * 4. Waits 2 seconds
     * 5. Increments turn counter and repeats
     *
     * ## Lifecycle
     * - Cancels any existing [demoJob] before starting
     * - Clears [historyEntries] to start fresh
     * - Runs until [stopDemoLoop] is called or [demoMode] is set to false
     *
     * ## ID Reuse
     * The same [GameHistoryData] object is reused (via `.copy()`) to demonstrate
     * the update mechanism in [recordGameHistoryEntry].
     */
    private fun startDemoLoop()
    {
        demoJob?.cancel()
        historyEntries.clear()
        refreshStoryPanel()
        clearDetailCards()
        
        demoJob = scope.launch {
            var turnCount = 1
            while (true)
            {
                // 1. Create a "Plan" (Initial state)
                val newEntry = GameHistoryData(
                    turnPlayer = "DemoPlayer",
                    turnAction = "Executing Plan Alpha for Turn $turnCount",
                    turnStory = "The automated systems are preparing for... [Planning]",
                    usingResources = mutableListOf("Energy", "Computing")
                )
                // Using a known ID would be needed for update, but here we just reuse the object or keep ID ref.
                //recordGameHistoryEntry will use the ID in the object (randomly generated by default).
                recordGameHistoryEntry(newEntry)
                
                kotlinx.coroutines.delay(2000)
                
                // 2. "Update" the plan with result (Modify existing)
                val updatedEntry = newEntry.copy(
                     turnStory = "The automated systems successfully executed Plan Alpha. \nInitial parameters exceeded expectations.",
                     turnResult = "Success (Yield +15%)",
                     resourcesWon = mutableListOf("Data Cache", "Credits")
                )
                // recordGameHistoryEntry will see same ID and update
                recordGameHistoryEntry(updatedEntry)
                
                kotlinx.coroutines.delay(2000)
                turnCount++
            }
        }
    }

    private fun clearDetailCards()
    {
        summaryPanel.removeAll()
        detailCardMap.clear()
    }

    /**
     * Stops the demo mode coroutine loop.
     *
     * Cancels the [demoJob] and clears the reference. This is called when [demoMode]
     * is set to false or when starting a new demo loop.
     *
     * **Note**: This does NOT clear the history entries or UI panels. That is handled
     * by the [demoMode] setter.
     */
    private fun stopDemoLoop()
    {
        demoJob?.cancel()
        demoJob = null
    }
}
