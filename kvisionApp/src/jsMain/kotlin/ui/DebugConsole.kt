package ui

import globals.World
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.Player
import ui.MatchmakingClient

/**
 * Singleton console object that exposes UI-trigger functions to the DebugSignalBridge.
 * This allows the Python debug server to drive browser UI actions via HTTP signals
 * that are polled by DebugSignalBridge.
 *
 * Architecture:
 * - Python controller runs the actual game session (matchmaking, game logic, TPipe AI)
 * - Browser acts as a display/observer — Python sends signals to drive the UI
 * - Browser does NOT run its own matchmaking when Python is in control (would create
 *   a second game session). Instead it just renders whatever game Python started.
 * - The browser IS connected to the same game server WebSocket as Python, so it
 *   receives game state broadcasts automatically.
 *
 * Signal flow:
 *   Python sends HTTP POST /debug/signal "GAME_STARTED:PlayerName:aiCount"
 *   → DebugSignalBridge polls → dispatchGAME_STARTED()
 *   → Browser adds GameplayUI to the stack (WITHOUT calling MatchmakingClient)
 *   → Browser's WebSocket is already receiving game state from Python's session
 *   → Browser renders the game view automatically
 *
 * Usage:
 *   DebugConsole.triggerLoginAsGuest()     — no-op in skipLogin mode
 *   DebugConsole.triggerGameStarted()       — browser shows game view (no matchmaking)
 *   DebugConsole.triggerCloseOverlay()      — close any open dialogs
 */
object DebugConsole
{

    private val scope = MainScope()

    /**
     * Trigger guest login on the current LoginPage.
     * In skipLogin mode (which we use), LoginPage is null and this is a no-op.
     */
    fun triggerLoginAsGuest()
    {
        val loginPage = globals.KEnv.currentLoginPage
        if(loginPage != null)
        {
            Logger.info(LogCategory.NETWORK, "DebugConsole: triggerLoginAsGuest() → calling guestLogin()")
            loginPage.guestLogin()
        }
        else
        {
            // In skipLogin mode, LoginPage is never created — this is expected.
            // The browser is already "logged in" via skipLogin credentials.
            Logger.info(LogCategory.NETWORK, "DebugConsole: triggerLoginAsGuest() — no-op (skipLogin mode, no LoginPage)")
        }
    }

    /**
     * Signal that the game has started and the browser should display the GameplayUI.
     *
     * IMPORTANT: This does NOT call MatchmakingClient.requestSinglePlayerMatch().
     * The Python controller already started the game session via its own matchmaking
     * call. If the browser also called requestSinglePlayerMatch(), it would create
     * a SECOND game session (and likely fail or conflict).
     *
     * Instead, this just adds the GameplayUI to the browser's stack.
     * The browser's WebSocket (connected via skipLogin's WebSocketRpcBridge.connect)
     * is already receiving game state broadcasts from the same game server that
     * Python is playing on. So the GameplayUI will automatically show the correct
     * game state.
     */
    fun triggerGameStarted()
    {
        // Track that this was called (for debugging)
        js("window.__triggerGameStartedCalled = true")
        kotlin.js.console.info("DEBUG: triggerGameStarted() called — adding GameplayUI")
        Logger.info(LogCategory.NETWORK, "DebugConsole: triggerGameStarted() — adding GameplayUI to browser stack")

        try
        {
            val stack = globals.KEnv.appStack
            val mainRoot = globals.KEnv.mainRoot
            if(stack != null && mainRoot != null)
            {
                // Check if GameplayUI is already added using the robust global reference
                val existing = globals.KEnv.currentGameplayUI
                if(existing != null)
                {
                    Logger.info(LogCategory.NETWORK, "DebugConsole: GameplayUI already exists, activating it")
                    if(stack.getChildren().contains(existing) == false)
                    {
                        stack.add(existing)
                    }
                    stack.activeIndex = stack.getChildren().indexOf(existing)
                }
                else
                {
                    Logger.info(LogCategory.NETWORK, "DebugConsole: No GameplayUI found - instantiating now")
                    val gameplayUI = ui.gameplay.GameplayUI()
                    // Note: GameplayUI self-registers to globals.KEnv.currentGameplayUI in its init block
                    stack.add(gameplayUI)
                    stack.activeIndex = stack.getChildren().size - 1
                    Logger.info(LogCategory.NETWORK, "DebugConsole: GameplayUI added to stack at index ${stack.activeIndex}")
                }
            }
            else
            {
                Logger.warn(LogCategory.NETWORK, "DebugConsole: triggerGameStarted() — appStack or mainRoot is null")
            }
        }
        catch(e: Exception)
        {
            Logger.error(LogCategory.NETWORK, "DebugConsole: triggerGameStarted() failed: ${e.message}")
        }
    }

    /**
     * Trigger closing any open overlay dialogs.
     * This dispatches an Escape key event that CollectionOverlay responds to.
     */
    fun triggerCloseOverlay()
    {
        Logger.info(LogCategory.NETWORK, "DebugConsole: triggerCloseOverlay()")
        try
        {
            kotlinx.browser.window.asDynamic().dispatchEvent(
                kotlinx.browser.window.asDynamic().KeyboardEvent("keydown", object
                {
                    val key = "Escape"
                    val keyCode = 27
                    val which = 27
                    val bubbles = true
                })
            )
        }
        catch(e: Exception)
        {
            Logger.warn(LogCategory.NETWORK, "DebugConsole: triggerCloseOverlay failed: ${e.message}")
        }
    }

    /**
     * Trigger the collection overlay to open.
     * This is a no-op in debug mode since we bypass collection via MatchmakingClient directly.
     */
    fun triggerOpenCollection()
    {
        Logger.info(LogCategory.NETWORK, "DebugConsole: triggerOpenCollection() — no-op in debug mode")
    }

    /**
     * Trigger auto-selection of a commander in the selection dialog.
     * This is a no-op since we bypass the selection dialog.
     */
    fun triggerAutoSelectCommander(commanderName: String)
    {
        Logger.warn(LogCategory.NETWORK, "DebugConsole: triggerAutoSelectCommander('$commanderName') — no-op in debug mode")
    }

    /**
     * Open a named widget overlay.
     * Valid names: worldStats, playerResources, stats, playerTerritories, playerInfo, settings, history
     */
    fun triggerOpenWidget(widgetName: String)
    {
        Logger.info(LogCategory.NETWORK, "DebugConsole: triggerOpenWidget('$widgetName')")
        try
        {
            val gameplayUI = globals.KEnv.currentGameplayUI
            if(gameplayUI == null)
            {
                Logger.warn(LogCategory.NETWORK, "DebugConsole: triggerOpenWidget — no GameplayUI active")
                return
            }
            when(widgetName.lowercase())
            {
                "worldstats" -> gameplayUI.worldStatsWidget?.show()
                "playerresources" -> gameplayUI.playerResourcesWidget?.show()
                "stats" -> gameplayUI.statsWidget?.show()
                "playerterritories" -> gameplayUI.playerTerritoriesWidget?.show()
                "playerinfo" -> gameplayUI.playerInfoWidget?.show()
                "settings" -> gameplayUI.settingsWidget?.show()
                "history" -> gameplayUI.historyWindow?.show()
                else -> Logger.warn(LogCategory.NETWORK, "DebugConsole: triggerOpenWidget — unknown widget '$widgetName'")
            }
        }
        catch(e: Exception)
        {
            Logger.error(LogCategory.NETWORK, "DebugConsole: triggerOpenWidget('$widgetName') failed: ${e.message}")
        }
    }

    /**
     * Close the currently open widget overlay.
     */
    fun triggerCloseWidget()
    {
        Logger.info(LogCategory.NETWORK, "DebugConsole: triggerCloseWidget()")
        try
        {
            val gameplayUI = globals.KEnv.currentGameplayUI
            if(gameplayUI == null)
            {
                Logger.warn(LogCategory.NETWORK, "DebugConsole: triggerCloseWidget — no GameplayUI active")
                return
            }
            // Hide all widgets — the active one will be hidden
            gameplayUI.worldStatsWidget?.hide()
            gameplayUI.playerResourcesWidget?.hide()
            gameplayUI.statsWidget?.hide()
            gameplayUI.playerTerritoriesWidget?.hide()
            gameplayUI.playerInfoWidget?.hide()
            gameplayUI.settingsWidget?.hide()
            gameplayUI.historyWindow?.hide()
            gameplayUI.territoryDescriptionWindow?.hide()
        }
        catch(e: Exception)
        {
            Logger.error(LogCategory.NETWORK, "DebugConsole: triggerCloseWidget() failed: ${e.message}")
        }
    }

    /**
     * Set command box text and submit the command atomically.
     * This is used by the Python controller to submit turns without using the browser's UI.
     */
    fun triggerExecuteCommand(commandText: String)
    {
        Logger.info(LogCategory.NETWORK, "DebugConsole: triggerExecuteCommand('$commandText')")
        try
        {
            val gameplayUI = globals.KEnv.currentGameplayUI
            if(gameplayUI == null)
            {
                Logger.warn(LogCategory.NETWORK, "DebugConsole: triggerExecuteCommand — no GameplayUI active")
                return
            }
            val commandBox = gameplayUI.commandBox
            if(commandBox == null)
            {
                Logger.warn(LogCategory.NETWORK, "DebugConsole: triggerExecuteCommand — commandBox is null")
                return
            }
            // Set the text and submit atomically
            commandBox.setAndSubmit(commandText)
        }
        catch(e: Exception)
        {
            Logger.error(LogCategory.NETWORK, "DebugConsole: triggerExecuteCommand('$commandText') failed: ${e.message}")
        }
    }

    /**
     * Trigger screenshot capture.
     * Currently a placeholder — the Python controller uses Playwright CDP to capture screenshots.
     */
    fun triggerCaptureScreenshot()
    {
        Logger.info(LogCategory.NETWORK, "DebugConsole: triggerCaptureScreenshot() — no-op (Python uses Playwright CDP)")
    }

    /**
     * Force the turn resolution panel to show.
     */
    fun triggerShowTurnResolution()
    {
        Logger.info(LogCategory.NETWORK, "DebugConsole: triggerShowTurnResolution()")
        try
        {
            val gameplayUI = globals.KEnv.currentGameplayUI
            if(gameplayUI == null)
            {
                Logger.warn(LogCategory.NETWORK, "DebugConsole: triggerShowTurnResolution — no GameplayUI active")
                return
            }
            gameplayUI.showTurnResolution()
        }
        catch(e: Exception)
        {
            Logger.error(LogCategory.NETWORK, "DebugConsole: triggerShowTurnResolution() failed: ${e.message}")
        }
    }

    /**
     * Force the map view to display.
     * This calls GameplayUI.showMap() which sets centerStackPanel.activeIndex = 0.
     */
    fun triggerShowMap()
    {
        Logger.info(LogCategory.NETWORK, "DebugConsole: triggerShowMap()")
        try
        {
            val gameplayUI = globals.KEnv.currentGameplayUI
            if(gameplayUI == null)
            {
                Logger.warn(LogCategory.NETWORK, "DebugConsole: triggerShowMap — no GameplayUI active")
                return
            }
            gameplayUI.showMap()
        }
        catch(e: Exception)
        {
            Logger.error(LogCategory.NETWORK, "DebugConsole: triggerShowMap() failed: ${e.message}")
        }
    }
}
