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

        // BUG 27 (2026-07-01) client-side dedupe. If a dialog is already
        // mounted for the same user, do not stack a second one — the user
        // already has the choice in front of them. The server's dedupe
        // is the primary defense; this is defense-in-depth in case a
        // future caller bypasses it.
        if (currentDialogUserId == payload.userId)
        {
            Logger.info(
                LogCategory.UI,
                "ResumeAvailabilityListener: mountResumeDialog skipped (userId=${payload.userId} already has a dialog mounted; user must dismiss it before a new one can mount)"
            )
            return
        }

        val dialog = ResumeOrNewDialog(
            onResume = {
                clearCurrentDialog()
                fireConsumeRpc(payload.userId)
                invokeResumeCallback(payload)
            },
            onNewGame = {
                clearCurrentDialog()
                fireConsumeRpc(payload.userId)
                onNewGame()
            },
            onCancel = {
                clearCurrentDialog()
                fireConsumeRpc(payload.userId)
                onCancel()
            }
        )
        mainRoot.add(dialog)
        currentDialogUserId = payload.userId
        mountCount += 1
        Logger.info(
            LogCategory.UI,
            "ResumeAvailabilityListener: ResumeOrNewDialog mounted for userId=${payload.userId} round=${payload.worldRound}"
        )
    }

    /**
     * Fires the `server.consumeResumePush` RPC in a fire-and-forget
     * coroutine so the server re-arms the dedupe for this userId.
     * No-ops silently if [MatchmakingClient.consumeResumePush] is not
     * importable from this context (jsTest).
     */
    private fun fireConsumeRpc(userId: String)
    {
        if (userId.isBlank()) return
        try
        {
            ui.MatchmakingClient.consumeResumePushFireAndForget(userId)
        }
        catch (e: Throwable)
        {
            Logger.warn(
                LogCategory.NETWORK,
                "ResumeAvailabilityListener: failed to dispatch consume RPC for userId=$userId: ${e.message}"
            )
        }
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

    // ====================================================================
    // BUG 27 (2026-07-01): client-side dedupe.
    //
    // The server-side dedupe in `UiSignalRpcHandlers.notifyResumeAvailable`
    // is the primary defense — once a user has been pushed, the server
    // holds the push until the client calls `server.consumeResumePush`.
    // The dedupe lives here as defense-in-depth: if any future code path
    // bypasses the server dedupe (a regression, a new caller, a
    // server-extend push variant), the client still won't stack dialogs
    // on top of each other.
    //
    // The contract:
    //   - A second push for the SAME userId while a dialog is already
    //     mounted is a no-op. The user already has the choice in front of
    //     them — re-mounting would stack a second dialog and break clicks.
    //   - A push for a DIFFERENT userId is allowed to mount a new dialog
    //     (this is the "user logs in as a different account" edge case —
    //     rare but real in dev mode).
    //   - Re-arm happens when one of the three buttons (Resume / New Game
    //     / Cancel) is clicked and the dialog is hidden. The dialog's
    //     `hide()` calls into our `clearCurrentDialogForTest` hook via
    //     the existing `onCancel`/`onNewGame` lambdas (wired in
    //     [MainMenu.wireResumeDialog]).
    //
    // The state is held as a simple `currentDialogUserId: String?` and
    // `currentDialogVisible: Boolean` — no KVision widget lifetime
    // tracking needed.
    // ====================================================================

    /**
     * Currently-mounted dialog's userId. `null` means no dialog is
     * currently in front of the user. Set when [mountResumeDialog]
     * successfully creates and adds a dialog; cleared by
     * [clearCurrentDialog].
     */
    internal var currentDialogUserId: String? = null

    /**
     * Counter incremented every time a dialog is actually mounted
     * (not on every dedupe-skipped call). jsTest reads this to
     * assert mount counts.
     */
    internal var mountCount: Int = 0
        private set

    /**
     * Test seam: explicitly clear the dedupe state. Used by jsTest's
     * `@AfterTest` to isolate cases.
     */
    internal fun clearCurrentDialogForTest()
    {
        currentDialogUserId = null
        mountCount = 0
    }

    /**
     * Production seam: invoked by the dialog's Resume / New Game /
     * Cancel handlers (via [MainMenu.wireResumeDialog]) when the user
     * dismisses the dialog. Clears the dedupe state so a subsequent
     * push (e.g. user logs back in) is allowed to mount a fresh
     * dialog.
     */
    fun clearCurrentDialog()
    {
        currentDialogUserId = null
    }
}
