@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package org.ttt.autogenesis.kvisionapp

import globals.KEnv
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcDirection
import structs.resume.ResumeAvailabilityNotification
import ui.ResumeOrNewDialog

/**
 * Subscribes to the server-pushed [ResumeAvailabilityNotification] over the
 * main-server WebSocket and shows the existing [ResumeOrNewDialog] when one
 * arrives.
 *
 * Replaces the previous client-pulled `MatchmakingClient.hasRunningGame()`
 * call from `MainMenu.init` (see `MainMenu.kt:124`). The server is the
 * source of truth for whether a saved game exists; the client no longer
 * races the auto-restore.
 *
 * Single registration per session — the listener attaches once when the
 * WebSocket is ready and stays attached for the lifetime of the page.
 *
 * **Wiring status (Phase B, task 9):** the listener is registered and
 * dispatches the notification. The actual dialog mount is a stub that
 * logs the arrival. Task 10 (Phase B) wires the full MainMenu flow —
 * the dialog mount will call into MainMenu's public methods instead of
 * the current log-only stub. The dialog constructor and three-button
 * callback wiring are unchanged from the previous pull-check path.
 */
object ResumeAvailabilityListener
{
    private val json = Json { ignoreUnknownKeys = true }
    private var registered: Boolean = false

    /**
     * Callbacks the dialog invokes. Set by [org.ttt.autogenesis.kvisionapp.Main] (or
     * [ui.MainMenu]) right after the listener is registered, so the dialog can
     * route Resume / New Game / Cancel clicks back to the menu instead of
     * relying on a static global.
     *
     * Each is nullable so the listener can be reset between sessions (e.g.
     * after logout).
     */
    var dialogOnResume: ((ResumeAvailabilityNotification) -> Unit)? = null
    var dialogOnNewGame: (() -> Unit)? = null
    var dialogOnCancel: (() -> Unit)? = null

    /**
     * Dispatches the supplied [ResumeAvailabilityNotification] to the
     * registered [dialogOnResume] callback, when one is set. This is the
     * side-effect-free callback path that the dialog's Resume button uses
     * (see [mountResumeDialog]). Exposed for unit testing so jsTest can
     * assert the callback wiring without instantiating a KVision [Root]
     * or a real [ResumeOrNewDialog] widget.
     */
    internal fun invokeResumeCallback(payload: ResumeAvailabilityNotification)
    {
        val onResume = dialogOnResume
        if (onResume == null)
        {
            Logger.warn(LogCategory.NETWORK, "ResumeAvailabilityListener: dialogOnResume not set; resume click ignored")
            return
        }
        onResume(payload)
    }

    /**
     * Registers the `client.resumeAvailable` notification handler on the
     * shared [WebSocketRpcBridge.rpcRegistry].
     *
     * This is idempotent (subsequent calls are no-ops) and does NOT wait
     * for a WS connection to exist — the registry is a process-global
     * map, so a handler registered before the first WS connect is
     * automatically available to every [WebSocketRpcClient] that joins
     * the registry afterwards. Waiting for a connection caused a real
     * race in dev mode (2026-06-25): the server-extend push fires within
     * ~500ms of the WS handshake, but the async `waitForConnection()` +
     * `registerHandlers` round-trip took 800ms+ on first connect, so the
     * push arrived at the client BEFORE the handler was registered and
     * `dispatchNotification` silently dropped it. Registering eagerly
     * (and re-registering on reconnect via the `if (registered) return`
     * early-out below) is both correct and idempotent.
     */
    fun register()
    {
        if (registered) return
        registered = true
        WebSocketRpcBridge.registerHandlers {
            register("client.resumeAvailable", RpcDirection.CLIENT) { ctx, payload ->
                onResumeAvailableRaw(payload)
                null
            }
        }
        Logger.info(LogCategory.NETWORK, "ResumeAvailabilityListener: registered handler for client.resumeAvailable")
    }

    private fun onResumeAvailableRaw(payload: JsonElement?)
    {
        val parsed: ResumeAvailabilityNotification? = payload?.let {
            runCatching { json.decodeFromJsonElement(ResumeAvailabilityNotification.serializer(), it) }.getOrNull()
        }
        if (parsed == null)
        {
            Logger.warn(LogCategory.NETWORK, "ResumeAvailabilityListener: failed to parse ResumeAvailabilityNotification payload: $payload")
            return
        }
        Logger.info(
            LogCategory.NETWORK,
            "ResumeAvailabilityListener: notification received for userId=${parsed.userId} round=${parsed.worldRound} ai=${parsed.hasAi} savedAt=${parsed.savedAt ?: "n/a"}"
        )
        // Defer to the next event-loop tick so we never call into the DOM
        // from inside the WS frame dispatch.
        window.setTimeout(
            {
                mountResumeDialog(parsed)
            },
            0
        )
    }

    /**
     * Mounts the [ResumeOrNewDialog] in the main root and wires its
     * callbacks. Called by [onResumeAvailableRaw] (deferred via setTimeout
     * in production) and by [mountResumeDialogForTest] in jsTest.
     */
    internal fun mountResumeDialog(payload: ResumeAvailabilityNotification)
    {
        val mainRoot = KEnv.mainRoot
        if (mainRoot == null)
        {
            Logger.warn(LogCategory.UI, "ResumeAvailabilityListener: KEnv.mainRoot is null; modal mount skipped (user navigated away)")
            return
        }
        val onResume = dialogOnResume
        val onNewGame = dialogOnNewGame
        val onCancel = dialogOnCancel
        if (onResume == null || onNewGame == null || onCancel == null)
        {
            // Don't drop the push — [MainMenu.wireResumeDialog] hasn't run
            // yet. Defer to a microtask so we retry after the current event
            // loop tick. If the dialog still isn't wired after a few
            // retries, give up with a warning (this is the "user navigated
            // away" case). See the 2026-06-25 race: the SSE push from
            // server-extend can arrive before MainMenu mounts in phase 2.
            pendingPayload = payload
            Logger.info(
                LogCategory.NETWORK,
                "ResumeAvailabilityListener: dialog callbacks not yet set; queuing payload for retry " +
                        "(userId=${payload.userId} round=${payload.worldRound})"
            )
            window.setTimeout({ retryPendingPayload() }, 50)
            return
        }
        pendingPayload = null
        val dialog = ResumeOrNewDialog(
            onResume = {
                invokeResumeCallback(payload)
            },
            onNewGame = onNewGame,
            onCancel = onCancel
        )
        mainRoot.add(dialog)
        Logger.info(
            LogCategory.UI,
            "ResumeAvailabilityListener: ResumeOrNewDialog mounted for userId=${payload.userId} round=${payload.worldRound}"
        )
    }

    private var pendingPayload: ResumeAvailabilityNotification? = null

    private fun retryPendingPayload()
    {
        val payload = pendingPayload ?: return
        val onResume = dialogOnResume
        val onNewGame = dialogOnNewGame
        val onCancel = dialogOnCancel
        if (onResume == null || onNewGame == null || onCancel == null) {
            // Still not wired — wait a bit longer. Don't retry indefinitely
            // (no mainRoot means the user is on a non-MainMenu screen and
            // the dialog will never mount).
            if (KEnv.mainRoot != null) {
                window.setTimeout({ retryPendingPayload() }, 200)
            } else {
                Logger.warn(
                    LogCategory.NETWORK,
                    "ResumeAvailabilityListener: pending payload could not be mounted (mainRoot null); dropping"
                )
                pendingPayload = null
            }
            return
        }
        mountResumeDialog(payload)
    }

    /**
     * Test seam: invokes [mountResumeDialog] directly without the
     * setTimeout deferral so jsTest can assert the dialog mount in a
     * deterministic way. Not used in production.
     */
    internal fun mountResumeDialogForTest(payload: ResumeAvailabilityNotification)
    {
        mountResumeDialog(payload)
    }
}
