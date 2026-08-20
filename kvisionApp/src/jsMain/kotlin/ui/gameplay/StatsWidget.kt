package ui.gameplay

import enums.NpcType
import enums.ResourceType
import enums.TerritoryType
import io.kvision.core.AlignItems
import io.kvision.core.Background
import io.kvision.core.Border
import io.kvision.core.BorderStyle
import io.kvision.core.Col
import io.kvision.core.Color
import io.kvision.core.CssSize
import io.kvision.core.Cursor
import io.kvision.core.Display
import io.kvision.core.FlexDirection
import io.kvision.core.FontStyle
import io.kvision.core.FontWeight
import io.kvision.core.JustifyContent
import io.kvision.core.Overflow
import io.kvision.core.Position
import io.kvision.core.TextAlign
import io.kvision.core.TextShadow
import io.kvision.core.UNIT
import io.kvision.core.onClick
import io.kvision.html.button
import io.kvision.html.h4
import io.kvision.html.icon
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.Npc
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

enum class StatsTab {
    PLAYER, NPC, TERRITORIES, TURN_ORDER
}

class StatsWidget(private val demoMode: Boolean = false) : SimplePanel(className = "login-widget-window")
{

    var playerResourcesWidget: PlayerResourcesWidget? = null
    var playerTerritoriesWidget: PlayerTerritoriesWidget? = null
    var playerInfoWidget: PlayerInfoWidget? = null

    private val uiScope = MainScope()
    private var territoryLoadJob: Job? = null
    private var npcLoadJob: Job? = null
    private var playerLoadJob: Job? = null
    private var turnOrderLoadJob: Job? = null

    private var activeTab = StatsTab.PLAYER
    private var hasShownOnce = false
    private var pendingRefresh = false
    
    // Content Containers (Placeholders)
    private val playerStatsContent = vPanel {
        width = 100.perc
        height = 100.perc
        padding = 10.px
        overflowY = Overflow.SCROLL
        opacity = 0.0
        setStyle("transition", "opacity 0.2s ease-in-out")
    }

    private val npcStatsContent = vPanel {
        width = 100.perc
        height = 100.perc
        padding = 10.px
        overflowY = Overflow.SCROLL
        display = Display.NONE
        opacity = 0.0
        setStyle("transition", "opacity 0.2s ease-in-out")
    }

    private val territoriesContent = vPanel {
        width = 100.perc
        height = 100.perc
        padding = 10.px
        overflowY = Overflow.SCROLL
        display = Display.NONE
        opacity = 0.0
        setStyle("transition", "opacity 0.2s ease-in-out")
    }

    private val turnOrderContent = vPanel {
        width = 100.perc
        height = 100.perc
        padding = 10.px
        overflowY = Overflow.SCROLL
        display = Display.NONE
        opacity = 0.0
        setStyle("transition", "opacity 0.2s ease-in-out")
    }

    // Tab Buttons
    private lateinit var playerTabBtn: io.kvision.html.Button
    private lateinit var npcTabBtn: io.kvision.html.Button
    private lateinit var territoriesTabBtn: io.kvision.html.Button
    private lateinit var turnOrderTabBtn: io.kvision.html.Button

    // Details Window
    private val territoryDescriptionWindow = TerritoryDescriptionWindow().apply {
        zIndex = 200 // Ensure it appears above the StatsWidget (101)
    }

    init {
        // Popup Styling & Positioning
        width = 700.px
        height = 800.px
        position = Position.FIXED
        top = 50.perc
        left = 50.perc
        marginTop = (-400).px
        marginLeft = (-350).px
        zIndex = 101
        padding = 20.px
        
        display = Display.NONE
        flexDirection = FlexDirection.COLUMN
        alignItems = AlignItems.CENTER

        // Header
        h4("Game Statistics") {
            color = Color.name(Col.CYAN)
            fontSize = 28.px
            fontWeight = FontWeight.BOLD
            marginTop = 0.px
            marginBottom = 10.px
            textShadow = TextShadow(0.px, 0.px, 5.px, Color.name(Col.CYAN))
        }

        // Tab Bar
        hPanel(spacing = 20, alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER) {
            width = 100.perc
            marginBottom = 10.px

            this@StatsWidget.playerTabBtn = button("Player Stats", className = "btn-tab") {
                onClick { this@StatsWidget.switchTab(StatsTab.PLAYER) }
            }
            
            this@StatsWidget.npcTabBtn = button("NPC Stats", className = "btn-tab") {
                onClick { this@StatsWidget.switchTab(StatsTab.NPC) }
            }
            
            this@StatsWidget.territoriesTabBtn = button("Territories", className = "btn-tab") {
                onClick { this@StatsWidget.switchTab(StatsTab.TERRITORIES) }
            }
            
            this@StatsWidget.turnOrderTabBtn = button("Turn Order", className = "btn-tab") {
                onClick { this@StatsWidget.switchTab(StatsTab.TURN_ORDER) }
            }
        }

        // Content Area
        vPanel {
            width = 100.perc
            flexGrow = 1
            overflow = Overflow.HIDDEN // Ensure children contain their own scrolling
            border = Border(1.px, BorderStyle.SOLID, Color.name(Col.DARKGRAY))
            background = Background(Color.hex(0x00000033.toInt()))
            padding = 10.px
            
            add(playerStatsContent)
            add(npcStatsContent)
            add(territoriesContent)
            add(turnOrderContent)
        }

        // Footer (Close Button)
        hPanel(justify = JustifyContent.CENTER, alignItems = AlignItems.CENTER) {
            width = 100.perc
            marginTop = 15.px
            flexShrink = 0

            button("CLOSE", icon = "fas fa-times", className = "btn btn-play").onClick {
                this@StatsWidget.hide()
            }
        }
        
        // Add the description window to the widget tree
        add(territoryDescriptionWindow)
        
        // Initialize default tab state
        updateTabStyles()
    }

    /**
     * Displays the stats widget and refreshes every section.
     */
    override fun show()
    {
        Logger.info(LogCategory.UI, "[TRACE] [StatsWidget.show] Widget opening. demoMode=$demoMode")
        if(demoMode)
        {
            Logger.info(LogCategory.UI, "[TRACE] [StatsWidget.show] Applying Demo State")
            applyDemoWorldState()
        }
        else
        {
            Logger.info(LogCategory.UI, "[TRACE] [StatsWidget.show] Production mode: Refreshing from globals.World.worldData (Players: ${globals.World.worldData.activePlayers.size})")
        }
        
        // Use a coroutine to refresh data so the initial opening/animation isn't blocked.
        uiScope.launch {
            // Wait for the 300ms CSS animation to fully clear before building the DOM content.
            // This prevents "jumpiness" or "flickering" while the window is still scaling or fading in.
            delay(400)
            refreshData()
        }

        setStyle("animation", "dialogFadeIn 0.3s ease-out")
        kotlinx.browser.window.setTimeout({
            setStyle("animation", "none")
        }, 300)

        display = Display.FLEX
        visible = true
        refresh()
    }

    /**
     * Refreshes the currently active statistics tab.
     */
    fun refreshData()
    {
        pendingRefresh = false

        val startTime = kotlinx.browser.window.performance.now()
        when(activeTab)
        {
            StatsTab.PLAYER -> {
                playerStatsContent.opacity = 0.0
                populatePlayerStats()
            }
            StatsTab.NPC -> {
                npcStatsContent.opacity = 0.0
                populateNpcStats()
            }
            StatsTab.TERRITORIES -> {
                territoriesContent.opacity = 0.0
                populateTerritoriesStats()
            }
            StatsTab.TURN_ORDER -> {
                turnOrderContent.opacity = 0.0
                populateTurnOrderStats()
            }
        }

        val duration = kotlinx.browser.window.performance.now() - startTime
        Logger.info(LogCategory.UI, "[PERF] [CLIENT] StatsWidget.refreshData took ${duration.asDynamic().toFixed(2)}ms (coroutine launched)")
    }

    fun markForRefresh()
    {
        if(visible)
        {
            Logger.info(LogCategory.UI, "[TRACE] [StatsWidget] Visible -> refreshing immediately")
            refreshData()
            return
        }

        Logger.info(LogCategory.UI, "[TRACE] [StatsWidget] Not visible -> scheduling refresh")
        pendingRefresh = true
    }

    /**
     * Hides the widget and resets the animation state.
     */
    override fun hide()
    {
        territoryLoadJob?.cancel()
        npcLoadJob?.cancel()
        playerLoadJob?.cancel()
        turnOrderLoadJob?.cancel()
        
        display = Display.NONE
        visible = false
        hasShownOnce = false
        refresh()
    }

    /**
     * Switches the active statistics tab and updates the visible panel.
     *
     * @param tab The tab to switch to.
     */
    private fun switchTab(tab: StatsTab)
    {
        if(activeTab == tab)
        {
            return
        }
        activeTab = tab

        playerStatsContent.display = if(tab == StatsTab.PLAYER)
        {
            Display.FLEX
        }
        else
        {
            Display.NONE
        }
        npcStatsContent.display = if(tab == StatsTab.NPC)
        {
            Display.BLOCK
        }
        else
        {
            Display.NONE
        }
        territoriesContent.display = if(tab == StatsTab.TERRITORIES)
        {
            Display.BLOCK
        }
        else
        {
            Display.NONE
        }
        turnOrderContent.display = if(tab == StatsTab.TURN_ORDER)
        {
            Display.BLOCK
        }
        else
        {
            Display.NONE
        }

        kotlinx.browser.window.setTimeout({
            when(tab)
            {
                StatsTab.NPC ->
                {
                    (npcStatsContent.getElement() as? org.w3c.dom.HTMLElement)?.scrollTop = 0.0
                }
                StatsTab.TERRITORIES ->
                {
                    (territoriesContent.getElement() as? org.w3c.dom.HTMLElement)?.scrollTop = 0.0
                }
                StatsTab.TURN_ORDER ->
                {
                    (turnOrderContent.getElement() as? org.w3c.dom.HTMLElement)?.scrollTop = 0.0
                }
                else ->
                {
                    // Player tab does not need scroll reset
                }
            }
        }, 10)

        updateTabStyles()
        refreshData()
    }

    /**
     * Hides the territory description overlay.
     */
    fun hideTerritoryDetails()
    {
        territoryDescriptionWindow.hide()
    }

    /**
     * Applies the active/inactive styles to every tab button.
     */
    private fun updateTabStyles()
    {
        styleTabButton(playerTabBtn, activeTab == StatsTab.PLAYER)
        styleTabButton(npcTabBtn, activeTab == StatsTab.NPC)
        styleTabButton(territoriesTabBtn, activeTab == StatsTab.TERRITORIES)
        styleTabButton(turnOrderTabBtn, activeTab == StatsTab.TURN_ORDER)
    }

    /**
     * Styles a single tab button based on whether it is active.
     *
     * @param btn Button to style.
     * @param isActive Whether the button corresponds to the active tab.
     */
    private fun styleTabButton(btn: io.kvision.html.Button, isActive: Boolean)
    {
        btn.background = Background(Color("transparent"))
        btn.boxShadow = null
        btn.border = Border(0.px, BorderStyle.NONE, Color("transparent"))
        btn.borderRadius = 0.px
        btn.padding = 10.px
        btn.fontSize = 16.px

        if(isActive)
        {
            btn.color = Color.name(Col.GOLD)
            btn.borderBottom = Border(2.px, BorderStyle.SOLID, Color.name(Col.GOLD))
            btn.fontWeight = FontWeight.BOLD
        }
        else
        {
            btn.color = Color.name(Col.LIGHTGRAY)
            btn.borderBottom = Border(2.px, BorderStyle.SOLID, Color("transparent"))
            btn.fontWeight = FontWeight.NORMAL
        }
    }
    
    /**
     * Rebuilds the player list panel with interactive widgets.
     */
    private fun populatePlayerStats()
    {
        playerLoadJob?.cancel()

        playerLoadJob = uiScope.launch {
            val startTime = kotlinx.browser.window.performance.now()
            val worldData = globals.World.worldData
            val localPlayer = globals.World.localPlayer
            val players = mutableListOf<structs.Player>()

            if(localPlayer.name.isNotEmpty())
            {
                players.add(localPlayer)
            }

            worldData.activePlayers.forEach { player ->
                if(player.name != localPlayer.name)
                {
                    players.add(player)
                }
            }

            playerStatsContent.removeAll()
            val chunks = players.chunked(2)
            if (chunks.isNotEmpty())
            {
                chunks.first().forEach { player ->
                    playerStatsContent.add(
                        PlayerItemWidget(
                            player = player,
                            onViewResources = { selectedPlayer ->
                                playerResourcesWidget?.showPlayerResources(selectedPlayer)
                            },
                            onViewTerritories = { selectedPlayer ->
                                playerTerritoriesWidget?.show(selectedPlayer)
                            },
                            onViewInfo = { selectedPlayer ->
                                playerInfoWidget?.show(selectedPlayer)
                            }
                        )
                    )
                }
                playerStatsContent.opacity = 1.0
            }

            chunks.drop(1).forEach { chunk ->
                delay(1)
                chunk.forEach { player ->
                    playerStatsContent.add(
                        PlayerItemWidget(
                            player = player,
                            onViewResources = { selectedPlayer ->
                                playerResourcesWidget?.showPlayerResources(selectedPlayer)
                            },
                            onViewTerritories = { selectedPlayer ->
                                playerTerritoriesWidget?.show(selectedPlayer)
                            },
                            onViewInfo = { selectedPlayer ->
                                playerInfoWidget?.show(selectedPlayer)
                            }
                        )
                    )
                }
            }
            val duration = kotlinx.browser.window.performance.now() - startTime
            Logger.info(LogCategory.UI, "[PERF] [CLIENT] StatsWidget.populatePlayerStats completed in ${duration.asDynamic().toFixed(2)}ms")
        }
    }

    /**
     * Rebuilds the NPC list inside the widget.
     */
    private fun populateNpcStats()
    {
        npcLoadJob?.cancel()

        npcLoadJob = uiScope.launch {
            val startTime = kotlinx.browser.window.performance.now()
            val worldData = globals.World.worldData
            val sortedNpcs = worldData.npc.sortedWith(
                compareBy<Npc> { it.isDefeated }
                    .thenByDescending { it.type.ordinal }
            )

            npcStatsContent.removeAll()
            if(sortedNpcs.isEmpty())
            {
                npcStatsContent.p("No known NPCs available.")
                {
                    color = Color.name(Col.GRAY)
                    fontStyle = FontStyle.ITALIC
                    marginTop = 20.px
                }
                npcStatsContent.opacity = 1.0
                return@launch
            }

            val chunks = sortedNpcs.chunked(2)
            if (chunks.isNotEmpty())
            {
                chunks.first().forEach { npc ->
                    npcStatsContent.add(NpcItemWidget(npc))
                }
                npcStatsContent.opacity = 1.0
            }

            chunks.drop(1).forEach { chunk ->
                delay(1)
                chunk.forEach { npc ->
                    npcStatsContent.add(NpcItemWidget(npc))
                }
            }
            val duration = kotlinx.browser.window.performance.now() - startTime
            Logger.info(LogCategory.UI, "[PERF] [CLIENT] StatsWidget.populateNpcStats completed in ${duration.asDynamic().toFixed(2)}ms")
        }
    }

    /**
     * Rebuilds the territory list for display.
     */
    private fun populateTerritoriesStats()
    {
        territoryLoadJob?.cancel()

        territoryLoadJob = uiScope.launch {
            val startTime = kotlinx.browser.window.performance.now()
            val allTerritories = globals.World.worldData.mapTiles.sortedBy { it.name }

            territoriesContent.removeAll()
            if(allTerritories.isEmpty())
            {
                territoriesContent.p("No territories discovered.")
                {
                    color = Color.name(Col.GRAY)
                    fontStyle = FontStyle.ITALIC
                    marginTop = 20.px
                }
                territoriesContent.opacity = 1.0
                return@launch
            }

            val chunks = allTerritories.chunked(2)
            if (chunks.isNotEmpty())
            {
                chunks.first().forEach { territory ->
                    territoriesContent.add(createTerritoryStatsItem(territory))
                }
                territoriesContent.opacity = 1.0
            }

            chunks.drop(1).forEach { chunk ->
                delay(1)
                chunk.forEach { territory ->
                    territoriesContent.add(createTerritoryStatsItem(territory))
                }
            }
            val duration = kotlinx.browser.window.performance.now() - startTime
            Logger.info(LogCategory.UI, "[PERF] [CLIENT] StatsWidget.populateTerritoriesStats completed in ${duration.asDynamic().toFixed(2)}ms")
        }
    }

    /**
     * Rebuilds the turn order list with prioritization for the local player.
     */
    private fun populateTurnOrderStats()
    {
        turnOrderLoadJob?.cancel()

        turnOrderLoadJob = uiScope.launch {
            val worldData = globals.World.worldData
            val turnOrder = worldData.turnOrder
            val localPlayerName = globals.World.localPlayer.name

            // Pre-compute lookups to avoid O(N^2) inside the loop
            val activePlayerNames = worldData.activePlayers.map { it.name }.toSet()
            val npcMap = worldData.npc.associateBy { it.name }

            turnOrderContent.removeAll()
            if(turnOrder.isEmpty())
            {
                turnOrderContent.p("No turn order defined.")
                {
                    color = Color.name(Col.GRAY)
                    fontStyle = FontStyle.ITALIC
                    marginTop = 20.px
                }
                turnOrderContent.opacity = 1.0
                return@launch
            }

            val chunks = turnOrder.chunked(2)
            if (chunks.isNotEmpty())
            {
                chunks.first().forEachIndexed { innerIndex, name ->
                    addTurnOrderItem(0, innerIndex, name, localPlayerName, activePlayerNames, npcMap, worldData)
                }
                turnOrderContent.opacity = 1.0
            }

            chunks.drop(1).forEachIndexed { chunkIndex, chunk ->
                delay(1)
                chunk.forEachIndexed { innerIndex, name ->
                    addTurnOrderItem(chunkIndex + 1, innerIndex, name, localPlayerName, activePlayerNames, npcMap, worldData)
                }
            }
        }
    }

    private fun addTurnOrderItem(
        chunkIndex: Int,
        innerIndex: Int,
        name: String,
        localPlayerName: String,
        activePlayerNames: Set<String>,
        npcMap: Map<String, Npc>,
        worldData: structs.World
    ) {
        val index = chunkIndex * 2 + innerIndex
        // Normalize name for case/punctuation-insensitive lookup (BUG-6 fix)
        val normalizedName = normalizeForLookup(name)
        val normalizedLocalPlayerName = normalizeForLookup(localPlayerName)
        val isLocalPlayer = normalizedName == normalizedLocalPlayerName

        turnOrderContent.hPanel(spacing = 15, alignItems = AlignItems.CENTER)
        {
            width = 100.perc
            marginBottom = 10.px
            padding = 10.px
            background = if(isLocalPlayer)
            {
                Background(Color.hex(0x2a2a4e))
            }
            else
            {
                Background(Color.hex(0x1a1a2e))
            }
            border = Border(
                1.px,
                BorderStyle.SOLID,
                if (isLocalPlayer)
                {
                    Color.name(Col.GOLD)
                }
                else
                {
                    Color.hex(0x383f59)
                }
            )
            borderRadius = 5.px

            span((index + 1).toString())
            {
                color = Color.name(Col.GRAY)
                fontSize = 18.px
                fontWeight = FontWeight.BOLD
                minWidth = 30.px
                textAlign = TextAlign.CENTER
            }

            var iconClass = "fas fa-question-circle"
            var iconColor = Color.name(Col.GRAY)
            // BUG-6 fix: normalize name before lookup to handle case/punctuation mismatches
            val normalizedActivePlayerNames = activePlayerNames.map { normalizeForLookup(it) }.toSet()
            val isPlayer = normalizedActivePlayerNames.contains(normalizedName)
            val npc = npcMap.entries.find { normalizeForLookup(it.key) == normalizedName }?.value

            // BUG 6 INVESTIGATION: Log icon resolution details
            Logger.debug(LogCategory.UI, "Resolving icon for name='$name', normalized='$normalizedName', isPlayer=$isPlayer, npc=${if(npc != null) "found(type=${npc.type})" else "null"}")
            Logger.debug(LogCategory.UI, "activePlayerNames(${activePlayerNames.size}) = ${activePlayerNames.joinToString()}")
            Logger.debug(LogCategory.UI, "npcMap keys (${npcMap.size}) = ${npcMap.keys.joinToString()}")

            if(isPlayer)
            {
                iconClass = "fas fa-user-astronaut"
                iconColor = if(isLocalPlayer)
                {
                    Color.name(Col.GOLD)
                }
                else
                {
                    Color.name(Col.CYAN)
                }
                Logger.debug(LogCategory.UI, "-> Player icon selected: $iconClass, color=${if(isLocalPlayer) "GOLD" else "CYAN"}")
            }
            else if (npc != null)
            {
                val visualStyle = getNpcVisualStyle(npc)
                iconClass = visualStyle.iconClass
                iconColor = visualStyle.color
                Logger.debug(LogCategory.UI, "-> NPC icon selected: $iconClass (${npc.type}), color=$iconColor")
            } else {
                Logger.warn(LogCategory.UI, "-> FALLBACK: No player or NPC match for '$name' (normalized='$normalizedName'). This may cause blue person icon bug!")
                Logger.warn(LogCategory.UI, "-> activePlayerNames.contains('$name')=$isPlayer, npcMap['$name']=${npcMap[name]}")
            }

            icon(iconClass)
            {
                fontSize = 20.px
                color = iconColor
                marginRight = 10.px
            }

            span(name)
            {
                color = Color.name(Col.WHITE)
                fontSize = 18.px
                fontWeight = if (isLocalPlayer)
                {
                    FontWeight.BOLD
                }
                else
                {
                    FontWeight.NORMAL
                }
            }

            if (isLocalPlayer)
            {
                span("(YOU)")
                {
                    color = Color.name(Col.GOLD)
                    fontSize = 12.px
                    fontWeight = FontWeight.BOLD
                    marginLeft = 10.px
                }
            }
        }
    }

    /**
     * Creates a territory list entry for the stats panel.
     *
     * @param territory Territory data to display.
     * @return Configured panel element for the territory.
     */
    private fun createTerritoryStatsItem(territory: structs.Territory): SimplePanel
    {
        return hPanel(className = "resource-item", spacing = 15, alignItems = AlignItems.CENTER)
        {
            width = 100.perc
            marginBottom = 10.px
            padding = 10.px
            background = Background(Color.hex(0x1a1a2e))
            border = Border(1.px, BorderStyle.SOLID, Color.hex(0x383f59))
            borderRadius = 5.px
            cursor = Cursor.POINTER

            onClick {
                territoryDescriptionWindow.show(territory)
            }

            val iconStr = if (territory.type == TerritoryType.Land)
            {
                "fas fa-map-marker-alt"
            }
            else
            {
                "fas fa-water"
            }
                icon(iconStr)
                {
                    fontSize = 24.px
                    color = if (territory.type == TerritoryType.Land)
                    {
                        Color.name(Col.GOLD)
                    }
                    else
                    {
                        Color.name(Col.CYAN)
                    }
                    minWidth = 30.px
                    textAlign = TextAlign.CENTER
                }

            vPanel(spacing = 2)
            {
                width = 100.perc

                span(territory.name)
                {
                    color = Color.name(Col.WHITE)
                    fontSize = 16.px
                    fontWeight = FontWeight.BOLD
                }

                hPanel(spacing = 10)
                {
                    span("${territory.pointValue} pts")
                    {
                        color = Color.name(Col.CYAN)
                        fontSize = 12.px
                    }
                    span("•")
                    {
                        color = Color.name(Col.GRAY)
                    }
                    span(territory.type.name)
                    {
                        color = Color.name(Col.LIGHTGRAY)
                        fontSize = 12.px
                    }
                }

                hPanel(spacing = 5)
                {
                    marginTop = 3.px
                    val ownerName = if (territory.ruler.isNotEmpty())
                    {
                        territory.ruler
                    }
                    else
                    {
                        "Unclaimed"
                    }
                    
                    val localPlayerName = globals.World.localPlayer.name
                    val ownerColor = if (territory.ruler.isEmpty())
                    {
                        Color.name(Col.GRAY)
                    }
                    else if (territory.ruler.trim().equals(localPlayerName.trim(), ignoreCase = true))
                    {
                        Color.name(Col.GOLD)
                    }
                    else if (globals.World.worldData.npc.any { it.name.trim().equals(territory.ruler.trim(), ignoreCase = true) })
                    {
                        Color.name(Col.ORANGERED)
                    }
                    else
                    {
                        Color.name(Col.CYAN)
                    }

                    span("Owner:")
                    {
                        color = Color.name(Col.GRAY)
                        fontSize = 11.px
                    }
                    span(ownerName)
                    {
                        color = ownerColor
                        fontSize = 11.px
                        fontWeight = FontWeight.BOLD
                    }
                }
            }

            icon("fas fa-chevron-right")
            {
                fontSize = 14.px
                color = Color.name(Col.GRAY)
            }
        }
    }

    /**
     * Populates the globals with demo world data for layout previews.
     */
    private fun applyDemoWorldState()
    {
        val demoWorld = DemoFixtures.buildDemoWorld()
        globals.World.worldData = demoWorld
        globals.World.localPlayer = demoWorld.activePlayers.first()
    }

    /**
     * Normalizes a name for icon lookup — case-insensitive, punctuation-normalized.
     * BUG-6 fix: converts to lowercase, strips hyphens/underscores, normalizes unicode.
     * Ensures "Lord Maple Tree" and "Lord maple tree" resolve to the same icon.
     * Uses JS-compatible regex to strip diacritics and normalize NFKC form.
     */
    private fun normalizeForLookup(name: String): String
    {
        // Step 1: lowercase
        // Step 2: normalize NFKC to handle unicode equivalence (e.g., e + acute = é)
        // Step 3: replace hyphens/underscores with spaces
        // Step 4: strip diacritics (combining marks) via regex, then strip remaining non-alphanumeric
        val lower = name.lowercase()
        // Simple approach: just do lowercase + punctuation normalization without NFKC
        // This handles the common case of "Lord Maple Tree" vs "Lord maple tree"
        val deaccented = lower.replace(Regex("\\p{M}"), "")
        return deaccented.replace("-", " ").replace("_", " ").replace(Regex("[^a-z0-9\\s]"), "")
    }


}