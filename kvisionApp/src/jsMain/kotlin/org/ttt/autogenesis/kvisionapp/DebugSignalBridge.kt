package org.ttt.autogenesis.kvisionapp

import globals.KEnv
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit
import ui.DebugConsole
import kotlin.js.Promise
import kotlin.js.console

/**
 * Debug signal bridge that polls a Python debug server and dispatches signals
 * to KVision UI handlers. This allows the Python controller to drive the browser
 * UI by sending HTTP signals that map to internal KVision event handlers.
 *
 * Architecture:
 * - Python debug server runs on port 7075 (in controller.py)
 * - KVision polls GET /debug/signal every 500ms when active
 * - Signal is a string like "GAME_STARTED:LordMapleTree:3" or "CLOSE_OVERLAY"
 * - DebugSignalBridge dispatches to DebugConsole which calls the appropriate handlers
 *
 * Key insight: The browser WebSocket (from skipLogin's WebSocketRpcBridge.connect)
 * is already connected to the game server. When Python starts a game, the browser
 * automatically receives game state. The GAME_STARTED signal just tells the browser
 * to display the GameplayUI — it does NOT call MatchmakingClient (which would
 * create a second game session).
 *
 * Signal flow:
 *   Python POST /debug/signal "GAME_STARTED:PlayerName:aiCount"
 *   → DebugSignalBridge polls → dispatch() → dispatchGameStarted()
 *   → World.localPlayer set → DebugConsole.triggerGameStarted()
 *   → GameplayUI added to browser stack (no matchmaking needed)
 */
object DebugSignalBridge
{

    private const val DEBUG_SERVER_URL = "/debug"
    private const val POLL_INTERVAL_MS = 500L

    private var isPolling = false
    private var lastSignal = ""
    private val scope = MainScope()

    // Signal types
    object Signals
    {
        const val LOGIN_AS_GUEST = "LOGIN_AS_GUEST"
        const val GAME_STARTED = "GAME_STARTED"  // format: "GAME_STARTED:playerName:aiCount"
        const val OPEN_COLLECTION = "OPEN_COLLECTION"
        const val CLOSE_OVERLAY = "CLOSE_OVERLAY"
        const val AUTO_SELECT_COMMANDER = "AUTO_SELECT_COMMANDER"  // format: "AUTO_SELECT_COMMANDER:commanderName"
        // New UI control signals
        const val OPEN_WIDGET = "OPEN_WIDGET"  // format: "OPEN_WIDGET:widgetName"
        const val CLOSE_WIDGET = "CLOSE_WIDGET"
        const val EXECUTE_COMMAND = "EXECUTE_COMMAND"  // format: "EXECUTE_COMMAND:text" — atomic set+submit
        const val CAPTURE_SCREENSHOT = "CAPTURE_SCREENSHOT"
        const val SHOW_TURN_RESOLUTION = "SHOW_TURN_RESOLUTION"
        const val SHOW_MAP = "SHOW_MAP"  // force browser to display map view
    }

    /**
     * Start polling the debug server for signals.
     * Called from Main after the app stack is initialized.
     */
    fun startPolling()
    {
        if(isPolling) return

        isPolling = true
        Logger.info(LogCategory.NETWORK, "DebugSignalBridge: Starting signal polling (server=$DEBUG_SERVER_URL, interval=${POLL_INTERVAL_MS}ms)")

        scope.launch {
            while(isPolling)
            {
                try
                {
                    val signal = pollOnce()
                    if(signal.isNotBlank() && signal != lastSignal)
                    {
                        lastSignal = signal
                        try
                        {
                            dispatch(signal)
                        }
                        catch(e: Throwable)
                        {
                            console.error("Dispatch exception: ${e.message}")
                        }
                    }
                }
                catch(e: Exception)
                {
                    // Silent on network errors — debug server may not be running yet
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop polling the debug server.
     */
    fun stopPolling()
    {
        isPolling = false
        lastSignal = ""
        Logger.info(LogCategory.NETWORK, "DebugSignalBridge: Stopped signal polling")
    }

    /**
     * Poll the debug server once and return the signal string.
     * Also sends the browser's websocketId so the Python controller knows
     * which WebSocket session to use for matchmaking routing.
     */
    private suspend fun pollOnce(): String
    {
        return try
        {
            // Include browser's WebSocket ID so Python can use it for matchmaking
            val wsId = globals.WebsocketConfig.websocketId

            val response = kotlinx.browser.window.fetch(
                "/debug/signal${
                    if(wsId.isNotBlank()) "?websocket_id=$wsId" else ""
                }",
                RequestInit(method = "GET", headers = Headers().apply
                {
                    append("Accept", "text/plain")
                })
            ).await()

            if (!response.ok) {
                return ""
            }
            response.text().await()
        }
        catch(e: Exception)
        {
            // Debug server not running — this is expected when Python hasn't started
            ""
        }
    }

    /**
     * Dispatch a signal string to the appropriate UI handler.
     */
    private fun dispatch(signal: String)
    {
        console.info("DebugSignalBridge.dispatch('$signal')")
        Logger.info(LogCategory.NETWORK, "DebugSignalBridge: Received signal '$signal'")

        try
        {
            when
            {
            signal == Signals.LOGIN_AS_GUEST ->
            {
                DebugConsole.triggerLoginAsGuest()
            }
            signal.startsWith(Signals.GAME_STARTED) ->
            {
                dispatchGameStarted(signal)
            }
            signal == Signals.OPEN_COLLECTION ->
            {
                DebugConsole.triggerOpenCollection()
            }
            signal == Signals.CLOSE_OVERLAY ->
            {
                DebugConsole.triggerCloseOverlay()
            }
            signal.startsWith(Signals.AUTO_SELECT_COMMANDER) ->
            {
                dispatchAutoSelectCommander(signal)
            }
            signal.startsWith(Signals.OPEN_WIDGET) ->
            {
                dispatchOpenWidget(signal)
            }
            signal == Signals.CLOSE_WIDGET ->
            {
                DebugConsole.triggerCloseWidget()
            }
            signal.startsWith(Signals.EXECUTE_COMMAND) ->
            {
                dispatchExecuteCommand(signal)
            }
            signal == Signals.CAPTURE_SCREENSHOT ->
            {
                DebugConsole.triggerCaptureScreenshot()
            }
            signal == Signals.SHOW_TURN_RESOLUTION ->
            {
                DebugConsole.triggerShowTurnResolution()
            }
            signal == Signals.SHOW_MAP ->
            {
                DebugConsole.triggerShowMap()
            }
            else ->
            {
                Logger.warn(LogCategory.NETWORK, "DebugSignalBridge: Unknown signal '$signal'")
            }
        }
        }
        catch(e: Throwable)
        {
            console.error("Exception in dispatch(): ${e.message}")
        }
    }

    /**
     * Signal that the game has started — browser should display GameplayUI.
     * Format: "GAME_STARTED:playerName:aiCount"
     */
    private fun dispatchGameStarted(signal: String)
    {
        val parts = signal.split(":")
        if(parts.size < 3)
        {
            Logger.warn(LogCategory.NETWORK, "DebugSignalBridge: GAME_STARTED signal has wrong format: $signal")
            return
        }
        // Format: GAME_STARTED:playerName:aiCount
        val playerName = parts[1]
        val aiCount = parts[2].toIntOrNull() ?: 1
        Logger.info(LogCategory.NETWORK, "DebugSignalBridge: dispatching GAME_STARTED — player='$playerName', aiCount=$aiCount")

        // Set up World.localPlayer so GameplayUI can render the player's territory
        // The player name from the signal maps to the commander name that Python used when
        // creating the game session. We create a minimal Player object for UI purposes.
        try
        {
            globals.World.localPlayer = structs.Player(
                name = playerName,
                commanderType = enums.CommanderType.Land,
                trait = enums.CommanderTrait.Researcher,
                description = "Debug Player"
            )
            Logger.info(LogCategory.NETWORK, "DebugSignalBridge: World.localPlayer set to '$playerName'")
        }
        catch(e: Exception)
        {
            Logger.error(LogCategory.NETWORK, "DebugSignalBridge: Failed to set World.localPlayer: ${e.message}")
        }

        DebugConsole.triggerGameStarted()
    }

    /**
     * Auto-select a commander in the selection dialog.
     * Format: "AUTO_SELECT_COMMANDER:commanderName"
     */
    private fun dispatchAutoSelectCommander(signal: String)
    {
        val parts = signal.split(":")
        if(parts.size < 2)
        {
            Logger.warn(LogCategory.NETWORK, "DebugSignalBridge: AUTO_SELECT_COMMANDER signal has wrong format: $signal")
            return
        }
        val commanderName = parts.drop(1).joinToString(":")
        DebugConsole.triggerAutoSelectCommander(commanderName)
    }

    /**
     * Open a named widget overlay.
     * Format: "OPEN_WIDGET:widgetName"
     * Valid names: worldStats, playerResources, stats, playerTerritories, playerInfo, settings, history
     */
    private fun dispatchOpenWidget(signal: String)
    {
        val widgetName = signal.substringAfter("OPEN_WIDGET:", "")
        if(widgetName.isBlank())
        {
            Logger.warn(LogCategory.NETWORK, "DebugSignalBridge: OPEN_WIDGET signal has wrong format: $signal")
            return
        }
        DebugConsole.triggerOpenWidget(widgetName)
    }

    /**
     * Execute a command text atomically — set the command box text and submit.
     * Format: "EXECUTE_COMMAND:text"
     */
    private fun dispatchExecuteCommand(signal: String)
    {
        val commandText = signal.substringAfter("EXECUTE_COMMAND:", "")
        if(commandText.isBlank())
        {
            Logger.warn(LogCategory.NETWORK, "DebugSignalBridge: EXECUTE_COMMAND signal has wrong format: $signal")
            return
        }
        DebugConsole.triggerExecuteCommand(commandText)
    }
}
