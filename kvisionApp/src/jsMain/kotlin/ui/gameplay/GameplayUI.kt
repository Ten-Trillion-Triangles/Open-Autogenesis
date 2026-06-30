package ui.gameplay

import io.kvision.core.AlignItems
import io.kvision.core.JustifyContent
import io.kvision.core.JustifyItems
import io.kvision.html.div
import io.kvision.panel.SimplePanel
import io.kvision.panel.StackPanel
import io.kvision.panel.dockPanel
import io.kvision.panel.hPanel
import io.kvision.panel.stackPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import ui.MapViewer
import ui.fetchMapPackFromUrl
import ui.loadMapPackFile
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.kvisionapp.WebSocketRpcBridge
import org.ttt.autogenesis.network.ActionSubmitRequest
import org.ttt.autogenesis.network.OpenWidgetData
import org.ttt.autogenesis.network.OpenWidgetType
import org.ttt.autogenesis.network.CommandClassificationData
import globals.World
import ui.gameplay.networking.UiSignalClientHandlers
import org.ttt.autogenesis.network.GameOverData

/**
 * Root gameplay UI that orchestrates map, HUD, and auxiliary widgets.
 * Includes the score display, gameplay history, command input box, stats, resources,
 * characters, and map rules buttons, as well as the main game board centered in the screen.
 * Can spawn additional pop-up windows for various tasks as gameplay proceeds.
 */
class GameplayUI : SimplePanel()
{
    var centerStackPanel: StackPanel? = null
    var scoreDisplay: ScoreDisplay? = null
    var mapViewer: MapViewer? = null
    var commandBox: CommandBox? = null
    var historyWindow: GameHistoryWindow? = null
    private var awaitingCommandClassification = false
    private var isCounterPlayMode = false
    private var isAiRunning = false
    var worldStatsWidget: WorldStatsWidget? = null
    var playerResourcesWidget: PlayerResourcesWidget? = null
    var statsWidget: StatsWidget? = null
    var playerTerritoriesWidget: PlayerTerritoriesWidget? = null
    var playerInfoWidget: PlayerInfoWidget? = null
    var settingsWidget: SettingsWidget? = null
    var territoryDescriptionWindow: TerritoryDescriptionWindow? = null
    var turnResolutionWidget: TurnResolutionWidget? = null
    var promptStatusWidget: PromptStatusWidget? = null
    var gameEndWidget: GameEndWidget? = null
    var delegateWidget: DelegateWidget? = null

    private var disconnectedSince: Double? = null
    private var connectionMonitorId: Int? = null

    private var isTransitioning = false

    init
    {
        Logger.debug(LogCategory.UI, "[GameplayUI] init called")
        // Close the race condition window by registering globally as soon as possible.
        globals.KEnv.currentGameplayUI = this

        // Stable e2e selector — see kvisionApp-e2e/probes/resume-e2e.mjs.
        // Lets Playwright distinguish a real gameplay mount from the
        // MainMenu (different testid) or from a transient messageBox.
        addCssClass("gameplay-ui")
        setAttribute("data-testid", "gameplay-ui")

        // Test-mode hook: expose `this` on `window.gameplayUI` so
        // Playwright can drive the gameplay surface directly. The
        // assignment is gated on KEnv.testMode; in production
        // builds it is a no-op.
        if(globals.KEnv.testMode)
        {
            try
            {
                kotlinx.browser.window.asDynamic().gameplayUI = this
                Logger.info(LogCategory.SYSTEM, "GameplayUI: testMode active, window.gameplayUI exposed for e2e")
            }
            catch(e: Throwable)
            {
                Logger.warn(LogCategory.SYSTEM, "GameplayUI: failed to expose window.gameplayUI: ${e.message}")
            }
        }

        setStyle("background-image", "url('img/AutogenesisBlank.png')")
        setStyle("background-size", "cover")
        setStyle("background-repeat", "no-repeat")
        setStyle("background-position", "center center")
        setStyle("background-attachment", "fixed")
        width = 100.perc
        height = io.kvision.core.CssSize(100, io.kvision.core.UNIT.vh)
        minHeight = io.kvision.core.CssSize(100, io.kvision.core.UNIT.vh)
        zIndex = 1

        historyWindow = GameHistoryWindow().apply {
            zIndex = 1000
        }

        add(historyWindow!!)
        historyWindow?.show()
        
        // Instantiate and add WorldStatsWidget (hidden by default)
        worldStatsWidget = WorldStatsWidget(demo = false)
        add(worldStatsWidget!!)
        
        // Instantiate and add PlayerResourcesWidget (hidden by default)
        playerResourcesWidget = PlayerResourcesWidget(demo = false)
        add(playerResourcesWidget!!)

        // Instantiate and add TerritoryDescriptionWindow (hidden by default)
        territoryDescriptionWindow = TerritoryDescriptionWindow()
        // Wire close callback
        territoryDescriptionWindow?.onClose = {
            // Optional: logic when window closes, if any
        }

        territoryDescriptionWindow?.zIndex = 106
        add(territoryDescriptionWindow!!)

        // Instantiate and add PlayerTerritoriesWidget (hidden by default)
        playerTerritoriesWidget = PlayerTerritoriesWidget()
        playerTerritoriesWidget?.onTerritoryClick = { territory ->
            territoryDescriptionWindow?.show(territory)
        }
        add(playerTerritoriesWidget!!)

        // Instantiate and add PlayerInfoWidget (hidden by default)
        playerInfoWidget = PlayerInfoWidget()
        add(playerInfoWidget!!)

        // Instantiate and add StatsWidget (hidden by default)
        statsWidget = StatsWidget(demoMode = false)
        statsWidget?.playerResourcesWidget = playerResourcesWidget
        statsWidget?.playerTerritoriesWidget = playerTerritoriesWidget
        statsWidget?.playerInfoWidget = playerInfoWidget
        add(statsWidget!!)
        
        // Instantiate and add SettingsWidget (hidden by default).
        // showSurrender=true here so the in-game SURRENDER button appears;
        // MainMenu's instance uses the default false (no button).
        settingsWidget = SettingsWidget(showSurrender = true)
        add(settingsWidget!!)
        
        // Instantiate and add PromptStatusWidget (hidden by default)
        promptStatusWidget = PromptStatusWidget(demo = false)
        add(promptStatusWidget!!)
        
        // Initialize NeuralLinkManager
        NeuralLinkManager.init(this)
        AgentWorkStreamManager.init(this)
        
        // Instantiate and add GameEndWidget
        gameEndWidget = GameEndWidget(demo = false)
        add(gameEndWidget!!)

        // Instantiate and add DelegateWidget (hidden by default; opened via the DELEGATE
        // button in the score bar, the /delegate slash command, or OpenWidgetType.DELEGATE)
        delegateWidget = DelegateWidget()
        add(delegateWidget!!)

        // Example: If we want to open a default channel or allow it via command
        // NeuralLinkManager.openWindow("Commander", "COMMANDER")
        UiSignalClientHandlers.attachNeuralLinkManager() // Update handler to point to Manager

        dockPanel {
            width = 100.perc
            height = 100.perc
            zIndex = 2

            // Left spacer (400px) — mirrors the GameHistoryWindow's footprint
            // (width=400, left=0, top=0, height=calc(100vh - 200px)). Without
            // this, the center dock cell extends behind the floating history
            // panel and the map stretches the full viewport width. The score
            // bar's `paddingLeft = 400.px` (below) keeps the score display out
            // from under the same panel.
            left {
                vPanel {
                    width = 400.px
                    minWidth = 400.px
                    height = 100.perc
                }
            }

            up {
                hPanel {
                    alignItems = AlignItems.CENTER
                    width = 100.perc
                    height = 100.px
                    justifyItems = JustifyItems.STRETCH
                    paddingLeft = 400.px


                    scoreDisplay = scoreDisplay(demoMode = false) {
                        // Optional initializers
                        onDelegateClick = {
                            Logger.debug(LogCategory.UI, "GameplayUI: Opening Delegate Widget from score bar")
                            delegateWidget?.show()
                        }
                    }

                }

            }

            center {

                centerStackPanel = stackPanel {
                    width = 100.perc
                    height = 100.perc
                    zIndex = 3

                    mapViewer = MapViewer().apply {
                        width = 100.perc
                        height = 100.perc

                        // Explicitly null this out or handle it to prevent unwanted popups
                        onTerritoryClick = { territory ->
                            Logger.debug(LogCategory.UI, "GameplayUI: Map territory clicked - '${territory.name}'.")
                            if(!isTransitioning)
                            {
                                territoryDescriptionWindow?.show(territory)
                            }
                            else
                            {
                                Logger.debug(LogCategory.UI, "GameplayUI: Ignoring territory click during transition")
                            }
                        }
                    }

                    add(mapViewer!!)

                    // Test-mode hook: when the e2e harness is driving
                    // the UI, expose the live MapViewer on `window`
                    // and a stable `loadMapForTest(bytes)` helper.
                    // The helper runs through Kotlin (not JS), so the
                    // minified `loadMapPack` symbol is irrelevant
                    // and the call site is type-safe.
                    if(globals.KEnv.testMode)
                    {
                        try
                        {
                            mapViewer?.let { mv ->
                                kotlinx.browser.window.asDynamic().mapViewer = mv
                                kotlinx.browser.window.asDynamic().loadMapForTest = { bytes: Array<Number> ->
                                    val ba = ByteArray(bytes.size)
                                    for(i in 0 until bytes.size)
                                    {
                                        val v = bytes[i]
                                        ba[i] = v.toByte()
                                    }
                                    kotlinx.coroutines.MainScope().launch {
                                        mv.loadMapPack(ba)
                                    }
                                }
                            }
                            Logger.info(LogCategory.SYSTEM, "GameplayUI: testMode active, window.mapViewer and window.loadMapForTest exposed for e2e")
                        }
                        catch(e: Throwable)
                        {
                            Logger.warn(LogCategory.SYSTEM, "GameplayUI: failed to expose testMode hooks: ${e.message}")
                        }
                    }

                    Logger.debug(LogCategory.UI, "GameplayUI: Instantiating TurnResolutionWidget with demoMode=false")
                    turnResolutionWidget = TurnResolutionWidget(
                        onSwitchToMap = { showMap() },
                        onSetCommandInteractive = { enabled -> commandBox?.setInteractive(enabled) },
                        onPromptCommandEntry = {
                            commandBox?.highlightForResponse()
                            commandBox?.focusInput()
                        },
                        onRequestShow = { showTurnResolution() },
                        onDemoUpdateScore = { score -> scoreDisplay?.updateScore(score) },
                        onDemoUpdateResources = { mil, dip, res, sum -> scoreDisplay?.updateResources(mil, dip, res, sum) },
                        onDemoUpdateWorld = { world -> updateWorldState(world) },
                        onCounterPlayModeChange = { enabled -> setCounterPlayMode(enabled) },
                        onCounterIgnoreCallback = {
                            MainScope().launch {
                                val invoker = WebSocketRpcBridge.rpcInvoker
                                if(invoker != null)
                                {
                                    val request = org.ttt.autogenesis.network.CounterActionSubmitRequest(
                                        playerName = globals.World.localPlayer.name,
                                        response = "[IGNORE]"
                                    )
                                    invoker.invoke("game.submitCounterAction", request)
                                }
                            }
                        },
                        demoMode = false
                    )
                    UiSignalClientHandlers.attachWidget(turnResolutionWidget!!)
                    add(turnResolutionWidget!!)
                }

            }

            down {
                commandBox = CommandBox()
                commandBox?.apply {
                    paddingLeft = 30.px
                    onWorldClick = {
                        Logger.debug(LogCategory.UI, "GameplayUI: Opening World Stats Widget")
                        worldStatsWidget?.show()
                    }
                    onResourcesClick = {
                        Logger.debug(LogCategory.UI, "GameplayUI: Opening Player Resources Widget")
                        playerResourcesWidget?.show()
                    }
                    onStatsClick = {
                        Logger.debug(LogCategory.UI, "GameplayUI: Opening Stats Widget")
                        statsWidget?.show()
                    }
                    onSettingsClick = {
                        Logger.debug(LogCategory.UI, "GameplayUI: Opening Settings Widget")
                        settingsWidget?.show()
                    }
                    onSend = fun(command: String) {
                        Logger.debug(LogCategory.UI, "GameplayUI: Sending command '$command'")

                        val trimmed = command.trim()
                        if(trimmed.isEmpty())
                        {
                            return
                        }

                        val commandName = trimmed.substringBefore(" ").lowercase()
                        val isSlashCommand = trimmed.startsWith("/")

                        when(commandName)
                        {
                            "/prompts" -> {
                                togglePromptStatus()
                                commandBox?.clearInput()
                                return
                            }
                            "/chat" -> {
                                if (trimmed == "/chat") {
                                    NeuralLinkManager.openWindow("Commander", "COMMANDER", "/chat")
                                    commandBox?.clearInput()
                                    return
                                }
                            }
                            "/agentstream" -> {
                                AgentWorkStreamManager.openStream()
                                commandBox?.clearInput()
                                return
                            }
                            "/gameover" -> {
                                showGameEnd(demo = true)
                                commandBox?.clearInput()
                                return
                            }
                            "/delegate" -> {
                                Logger.debug(LogCategory.UI, "GameplayUI: Opening Delegate Widget via /delegate")
                                delegateWidget?.show()
                                commandBox?.clearInput()
                                return
                            }
                        }

                        val wasCounterPlay = isCounterPlayMode
                        
                        // Immediately indicate that the player is waiting for a response
                        if(wasCounterPlay)
                        {
                            isCounterPlayMode = false
                        }
                        else
                        {
                            awaitingCommandClassification = true
                        }
                        updateCanPlayerAct()

                        MainScope().launch {
                            val invoker = WebSocketRpcBridge.rpcInvoker
                            if(invoker != null)
                            {
                                try
                                {
                                    if(wasCounterPlay)
                                    {
                                        // Counter play mode: submit to game.submitCounterAction
                                        Logger.info(LogCategory.UI, "GameplayUI: Submitting counter play response")
                                        val request = org.ttt.autogenesis.network.CounterActionSubmitRequest(
                                            playerName = globals.World.localPlayer.name,
                                            response = command
                                        )
                                        invoker.invoke("game.submitCounterAction", request)
                                    }
                                    else
                                    {
                                        // Normal mode: submit to server.sendPrompt
                                        invoker.invoke("server.sendPrompt", command)
                                    }
                                }
                                catch(e: Exception)
                                {
                                    Logger.error(LogCategory.UI, "GameplayUI: Failed to submit prompt: ${e.message}")
                                    if(wasCounterPlay) isCounterPlayMode = true
                                    else awaitingCommandClassification = false
                                    updateCanPlayerAct()
                                }
                            }
                            else
                            {
                                Logger.warn(LogCategory.UI, "GameplayUI: Cannot submit prompt, WebSocketRpcBridge.rpcInvoker is null (Demo Mode?)")
                                if(wasCounterPlay) isCounterPlayMode = true
                                else awaitingCommandClassification = false
                                updateCanPlayerAct()
                            }
                        }

                        if(turnResolutionWidget?.isDemoMode() == true)
                        {
                            showTurnResolution()
                            turnResolutionWidget?.showPlayerAction()
                        }
                        else if (wasCounterPlay)
                        {
                            showTurnResolution()
                            turnResolutionWidget?.showWaitingForOtherPlayerTurn("Response submitted. Waiting for turn resolution...")
                        }
                    }
                }
                add(commandBox!!)
                updateCanPlayerAct()
            }
        }

        // Attach to the client signal handlers AFTER ALL WIDGETS ARE INITIALIZED!
        // This ensures pending Map Pack and World Data flushed by retroactive sync are stored in actual widgets instead of discarded.
        Logger.debug(LogCategory.UI, "GameplayUI: Attaching to UiSignalClientHandlers")
        UiSignalClientHandlers.attachGameplayUI(this)

        // Connection Monitor
        connectionMonitorId = kotlinx.browser.window.setInterval({
            if(WebSocketRpcBridge.isConnected)
            {
                disconnectedSince = null
            }
            else
            {
                if(disconnectedSince == null)
                {
                    disconnectedSince = kotlinx.browser.window.performance.now()
                }
                else if(kotlinx.browser.window.performance.now() - disconnectedSince!! >= 15000)
                {
                    connectionMonitorId?.let { kotlinx.browser.window.clearInterval(it) }
                    connectionMonitorId = null
                    
                    val messageBox = ui.MessageBox(
                        boxTitle = "Connection Lost",
                        message = "The connection to the server has been lost for over 15 seconds. Please refresh the page or close the application.",
                        showOk = false,
                        showCancel = false
                    )
                    this@GameplayUI.add(messageBox)
                }
            }
        }, 1000)
    }

    override fun dispose()
    {
        connectionMonitorId?.let { kotlinx.browser.window.clearInterval(it) }
        super.dispose()
    }

    /**
     * Switches focus to the turn resolution view.
     */
    fun showTurnResolution()
    {
        statsWidget?.hideTerritoryDetails()
        centerStackPanel?.activeIndex = 1
        territoryDescriptionWindow?.hide()
    }

    fun openWidget(data: OpenWidgetData)
    {
        when(data.widget)
        {
            OpenWidgetType.WORLD_STATS -> worldStatsWidget?.show()
            OpenWidgetType.PLAYER_RESOURCES -> playerResourcesWidget?.show()
            OpenWidgetType.STATS -> statsWidget?.show()
            OpenWidgetType.SETTINGS -> settingsWidget?.show()
            OpenWidgetType.PROMPT_STATUS -> togglePromptStatus()
            OpenWidgetType.NEURAL_LINK -> {
                val tabId = data.tabId ?: "Commander"
                val title = data.title ?: "COMMANDER"
                val commandContext = data.commandContext ?: "/ask"
                NeuralLinkManager.openWindow(tabId, title, commandContext)
            }
            OpenWidgetType.AGENT_STREAM -> AgentWorkStreamManager.openStream()
            OpenWidgetType.DELEGATE -> {
                Logger.debug(LogCategory.UI, "GameplayUI: Opening Delegate Widget via OpenWidgetType.DELEGATE")
                delegateWidget?.show()
            }
        }
    }

    /**
     * Controls whether to show the TurnResolution flow after receiving a server-side classification.
     *
     * The handler keeps the `awaitingCommandClassification` flag while the RPC is pending so we
     * stay on the map for non-gameplay prompts.
     */
    fun handleCommandClassification(data: CommandClassificationData)
    {
        if(!awaitingCommandClassification)
        {
            Logger.debug(LogCategory.UI, "GameplayUI: Received command classification with no pending command.")
            return
        }

        awaitingCommandClassification = false
        updateCanPlayerAct()

        if(data.isGameplay)
        {
            showTurnResolution()
            turnResolutionWidget?.showPlayerAction()
        }
        else
        {
            Logger.debug(LogCategory.UI, "GameplayUI: Command classified as non-gameplay, staying on map.")
        }
    }

    /**
     * Enables counter play mode so next command submission routes to game.submitCounterAction.
     */
    fun setCounterPlayMode(enabled: Boolean)
    {
        isCounterPlayMode = enabled
        Logger.debug(LogCategory.UI, "GameplayUI: Counter play mode ${if(enabled) "enabled" else "disabled"}")
        
        // Ensure command box visual state reflects that player can now act (or no longer can)
        updateCanPlayerAct()
    }

    /**
     * Toggles the visibility of the prompt status widget.
     */
    fun togglePromptStatus()
    {
        if(promptStatusWidget?.visible == true)
        {
            promptStatusWidget?.hide()
        }
        else
        {
            promptStatusWidget?.show()
        }
    }

    /**
     * Shows the game end fanfare widget.
     * 
     * @param data The game over data. If null, the widget will attempt to show demo data or existing results.
     * @param demo Currently unused but kept for parity with other show methods; calls [GameEndWidget.show].
     */
    fun showGameEnd(data: GameOverData? = null, demo: Boolean = false)
    {
        Logger.info(LogCategory.UI, "GameplayUI: Showing Game End Widget (demo=$demo)")
        gameEndWidget?.show(data)
    }

    /**
     * Updates the prompt status widget with new data.
     */
    fun updatePromptStatus(usage: structs.AgentUsage)
    {
        isAiRunning = usage.runningPlayAgent || usage.runningAnswerAgent || usage.runningOpenAgent || usage.runningChatAgent || usage.runningClassifier
        promptStatusWidget?.updateStatus(usage)
        commandBox?.updateAgentUsage(usage)
        updateCanPlayerAct()
    }

    /**
     * Returns focus to the map view with a short transition guard.
     */
    fun showMap()
    {
        Logger.debug(LogCategory.UI, "GameplayUI.showMap() called")
        isTransitioning = true
        statsWidget?.hideTerritoryDetails()
        centerStackPanel?.activeIndex = 0
        Logger.debug(LogCategory.UI, "GameplayUI.showMap() - hiding territoryDescriptionWindow")
        territoryDescriptionWindow?.hide()
        // No longer forcing interactive true as it's always interactive now.
        // But we should ensure turn state is fresh.
        updateCanPlayerAct()

        kotlinx.browser.window.setTimeout({
            isTransitioning = false
            Logger.debug(LogCategory.UI, "GameplayUI: Transition complete, territory clicks enabled")
        }, 300)
    }

    /**
     * Updates the full UI based on the new world state.
     *
     * @param world The updated world data.
     */
    fun updateWorldState(world: structs.World)
    {
        val startTime = kotlinx.browser.window.performance.now()
        Logger.info(LogCategory.UI, "[TRACE] [GameplayUI.updateWorldState] Reception Start - Round: ${world.roundNumber}")
        Logger.info(LogCategory.UI, "[TRACE] [GameplayUI.updateWorldState] Active Players in Data: ${world.activePlayers.size}")
        world.activePlayers.forEach { p ->
            Logger.info(LogCategory.UI, "[TRACE] [GameplayUI.updateWorldState]   - Player Name: '${p.name}'")
        }

        val prevWorld = globals.World.worldData
        globals.World.worldData = world
        
        // 1. Update Score Display (Local Player Data)
        // Find local player in the new data to ensure we have the latest points/resources
        val localPlayerName = globals.World.localPlayer.name
        val updatedLocalPlayer = world.activePlayers.find { it.name.trim().equals(localPlayerName.trim(), ignoreCase = true) }
        
        Logger.info(LogCategory.UI, "[TRACE] [GameplayUI.updateWorldState] LocalPlayerName: '$localPlayerName', MatchFound: ${updatedLocalPlayer != null}")

        if(updatedLocalPlayer != null)
        {
            globals.World.localPlayer = updatedLocalPlayer
            Logger.info(
                LogCategory.UI,
                "[TRACE] [GameplayUI.updateWorldState] localPlayer reassigned for '$localPlayerName' — delegateInstructionsLength=${updatedLocalPlayer.delegateInstructions?.length ?: 0}"
            )
            
            // Update Turn State
            globals.World.activeTurnActor = world.activeTurnActor
            globals.World.isPlayerTurn = (world.activeTurnActor == localPlayerName)
            updateCanPlayerAct()
            
            // Re-calculate VP from territories for display consistency if needed, 
            // or trust the player struct if it's being updated on server.
            // Using Map points to be safe as per WorldStats logic:
            val mapPoints = world.mapTiles.filter { it.ruler == localPlayerName }.sumOf { it.pointValue }
            
            scoreDisplay?.updateScore(mapPoints)
            
            // Update Resources
            // We need to parse valid resources from the list
            val mil = updatedLocalPlayer.militaryPoints.toString()
            val dip = updatedLocalPlayer.diplomacyPoints.toString()
            val res = updatedLocalPlayer.researchPoints.toString()
            val sum = updatedLocalPlayer.summitPoints.toString()
            scoreDisplay?.updateResources(mil, dip, res, sum)
            
            // Update Rank
            val sortedPlayers = world.activePlayers.map { p ->
                 val pts = world.mapTiles.filter { it.ruler == p.name }.sumOf { it.pointValue }
                 p.name to pts
            }.sortedByDescending { it.second }
            
            val rank = sortedPlayers.indexOfFirst { it.first == localPlayerName } + 1
            scoreDisplay?.updatePlacement(rank)
        }
        
        // 2. Update Map
        val mapStartTime = kotlinx.browser.window.performance.now()
        world.mapTiles.forEach { tile ->
            // Update ownership
            // Find owner color (Player or NPC)
            // Default to grey for unowned
            var color = "#AAAAAA"
            val ruler = tile.ruler
            
            if(ruler.isNotEmpty())
            {
                 val playerOwner = world.activePlayers.find { it.name == ruler }
                 if(playerOwner != null)
                 {
                     if(playerOwner.name == localPlayerName)
                     {
                         color = "#FFD700" // Gold for Local Player
                     }
                     else
                     {
                         // Use turnOrder index for other players
                         val playerIndex = world.turnOrder.indexOfFirst { it == playerOwner.name }
                         
                         val uniqueColors = listOf(
                             "#00FFFF", // Index 0: Cyan
                             "#FF00FF", // Index 1: Magenta
                             "#00FF00", // Index 2: Lime
                             "#FFA500"  // Index 3: Orange
                         )
                         
                         color = if (playerIndex != -1 && playerIndex < uniqueColors.size) {
                             uniqueColors[playerIndex]
                         } else {
                             "#00FFFF" // Fallback
                         }
                     }
                 }
                 else
                 {
                     val npcOwner = world.npc.find { it.name == ruler }
                     if(npcOwner != null)
                     {
                         color = "#FF4500" // OrangeRed for NPCs
                     }
                 }
            }
            
            // If we are in the 'World Update' step of the turn resolution (index 7), 
            // we want to force animations ONLY for territories that have been modified
            // by the turn outcomes, to highlight changes for the user.
            val isResolutionWorldUpdate = (turnResolutionWidget?.getActiveStepIndex() == 7)
            
            var forceAnimateForThisTile = false
            if (isResolutionWorldUpdate)
            {
                val prevTile = prevWorld.mapTiles.find { it.name == tile.name }
                // Force animation if ruler changed, or destruction state changed
                if (prevTile != null)
                {
                    val rulerChanged = prevTile.ruler != tile.ruler
                    val destroyedChanged = prevTile.isDestroyed != tile.isDestroyed
                    forceAnimateForThisTile = rulerChanged || destroyedChanged
                }
                else
                {
                    // New tile on map?
                    forceAnimateForThisTile = true
                }
            }

            mapViewer?.updateTerritoryOwnership(
                territoryName = tile.name, 
                owner = ruler, 
                ownerColor = color,
                activePlayerName = globals.World.activeTurnActor,
                localPlayerName = localPlayerName,
                forceAnimate = forceAnimateForThisTile
            )
            
            // Update State (Destruction/Contested symbols)
            // We need a helper to determine state from tile properties if not strictly in 'state' enum
            // derived from isDestroyed, etc.
            if(tile.isDestroyed)
            {
                mapViewer?.updateTerritoryState(tile.name, models.TerritoryState.DESTROYED)
            }
            else
            {
                // Reset to normal if not destroyed (or handle other states if we track them in World)
                mapViewer?.updateTerritoryState(tile.name, models.TerritoryState.NORMAL)
            }
        }
        val mapDuration = kotlinx.browser.window.performance.now() - mapStartTime
        
        // 3. Update Sub-Widgets if active
        val widgetsStartTime = kotlinx.browser.window.performance.now()
        // World Stats (Always update as it's an overlay)
        worldStatsWidget?.update()
        
        // Stats Widget (Tabbed)
        // We refresh if visible, or flag it for refresh when next opened.
        if(statsWidget?.visible == true)
        {
            Logger.info(LogCategory.UI, "[TRACE] [GameplayUI.updateWorldState] Refreshing statsWidget (currently visible)")
            statsWidget?.refreshData()
        }
        else
        {
            Logger.info(LogCategory.UI, "[TRACE] [GameplayUI.updateWorldState] statsWidget is NOT visible; scheduling refresh")
            statsWidget?.markForRefresh()
        }
        
        // Player Resources Widget
        if(playerResourcesWidget?.visible == true)
        {
            // If viewing specific player, try to find them again in new world data
            // We might need to track 'viewingPlayerName' in the widget to do this perfectly.
            // For now, just refresh with current logic (which pulls from currentPlayer inside the widget).
            // But 'currentPlayer' in the widget is a snapshot. We should re-inject the updated player object.
            // This is a bit complex without changing widget internal state tracking.
            // Simplified: Just call update() and let it re-render what it has (might be stale if it relies on stored player).
            // Better: Re-open it for local player if that's who we are viewing.
            
            playerResourcesWidget?.update() 
        }
        
        // Player Territories Widget
        if(playerTerritoriesWidget?.visible == true)
        {
            playerTerritoriesWidget?.update()
        }
        val widgetsDuration = kotlinx.browser.window.performance.now() - widgetsStartTime
        val totalDuration = kotlinx.browser.window.performance.now() - startTime
        
        Logger.info(LogCategory.UI, "[PERF] [CLIENT] updateWorldState completed in ${totalDuration.asDynamic().toFixed(2)}ms (Map: ${mapDuration.asDynamic().toFixed(2)}ms, Widgets: ${widgetsDuration.asDynamic().toFixed(2)}ms)")
    }

    /**
     * Re-calculates if the player is currently able to act and updates the UI.
     * The player can act if it's their turn (or counter-play mode) AND the AI is not currently processing a request.
     */
    fun updateCanPlayerAct()
    {
        val canAct = (globals.World.isPlayerTurn || isCounterPlayMode) && !isAiRunning && !awaitingCommandClassification
        globals.World.canPlayerAct = canAct
        commandBox?.setTurnState(canAct)
        Logger.debug(LogCategory.UI, "GameplayUI.updateCanPlayerAct: isPlayerTurn=${globals.World.isPlayerTurn}, isCounterPlayMode=$isCounterPlayMode, isAiRunning=$isAiRunning, awaitingClassification=$awaitingCommandClassification -> canAct=$canAct")
    }
}
