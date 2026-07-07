package ui

import globals.AccelByteEnv
import globals.ClientDebug
import globals.WebsocketConfig
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.serializer
import org.ttt.autogenesis.kvisionapp.ServerExtendBridge
import org.ttt.autogenesis.kvisionapp.WebSocketRpcBridge
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcJson
import structs.Commander
import structs.matchmaking.GameRequest
import structs.matchmaking.GameTicket
import structs.matchmaking.GameType
import structs.resume.ResumePushConsumeRequest

/**
 * Result of a multiplayer matchmaking attempt.
 *
 * - [Ready] indicates a successful match with a server URL the client should reconnect to.
 * - [Cancelled] indicates the user or backend cancelled the match.
 * - [Failed] wraps an error reason suitable for surfacing in the UI.
 */
sealed class MatchOutcome
{
    data class Ready(val serverUrl: String, val sessionId: String) : MatchOutcome()
    data object Cancelled : MatchOutcome()
    data class Failed(val reason: String) : MatchOutcome()
}

/**
 * Client-side helper that drives the matchmaking handshake against server-extend.
 *
 * Two flows are exposed:
 * - [requestSinglePlayerMatch] runs the dev/local path against the in-process game server.
 * - [requestMultiplayerMatch] drives the live PvP path: create match ticket → poll for ready →
 *   resolve server URL → reconnect [WebSocketRpcBridge] to that URL.
 */
object MatchmakingClient
{
    /**
     * Polling interval for [isServerReady] during the multiplayer flow. Matches server-extend's
     * `MATCHMAKING_TIMEOUT_MS` cadence (3 s server-side) at 1 s to surface readiness quickly.
     */
    private const val POLL_INTERVAL_MS: Long = 1_000L

    /**
     * Hard cap on the total time we spend polling for a multiplayer match. Mirrors the server-side
     * `MATCHMAKING_TIMEOUT_MS` value (180 s).
     */
    private const val MATCHMAKING_TIMEOUT_MS: Long = 180_000L

    /**
     * Maximum time we wait for [WebSocketRpcBridge.connect] to establish a session after the
     * matchmaking flow has resolved a server URL.
     */
    private const val CONNECT_TIMEOUT_MS: Long = 5_000L

    /**
     * Sends a single-player game request to server-extend.
     *
     * @param commander Commander the player intends to use during gameplay; may be null in AI-only mode.
     * @param aiOpponentCount Number of AI players to spawn alongside the human player. Defaults to three.
     * @param aiOnly When `true`, the request asks the helper service to bootstrap only AI opponents for testing/demo runs.
     * @return `true` when the backend acknowledges the session, `false` otherwise.
     */
    suspend fun requestSinglePlayerMatch(
        commander: Commander?,
        aiOpponentCount: Int = 3,
        aiOnly: Boolean = false
    ): Boolean
    {
        // Ensure WebSocket is connected so we have a valid ID to send to server-extend
        WebSocketRpcBridge.waitForConnection()

        val playerName = AccelByteEnv.displayName
            .takeIf { it.isNotBlank() }
            ?: AccelByteEnv.userName
                .takeIf { it.isNotBlank() }
            ?: "Commander"

        val websocketId = WebsocketConfig.websocketId
        val request = GameRequest(
            userName = playerName,
            gameType = GameType.SINGLEPLAYER,
            accelByteId = AccelByteEnv.userId,
            websocketId = websocketId,
            selectedCommander = commander,
            aiOpponentCount = aiOpponentCount,
            aiOnly = aiOnly
        )

        // Force REST/SSE transport for matchmaking: the active transport may be gRPC,
        // which has a null rpcInvoker for server.extend.requestGame. Forcing REST/SSE
        // ensures rpcInvoker resolves to SharedRestRpcBridge which has a live SSE connection.
        ServerExtendBridge.forceRestTransportForMatchmaking()

        val invoker = ServerExtendBridge.rpcInvoker
        Logger.info(LogCategory.NETWORK, "MatchmakingClient: Sending game request to server-extend for ${request.userName} (ID: ${request.accelByteId}, WS: ${request.websocketId})")
        Logger.debug(LogCategory.NETWORK, "MatchmakingClient: Commander Selection: ${commander?.name ?: "NONE"} (Type: ${commander?.type}, Trait: ${commander?.trait})")
        Logger.debug(LogCategory.NETWORK, "MatchmakingClient: AI Config: count=${request.aiOpponentCount}, aiOnly=${request.aiOnly}")

        val response = invoker?.invoke(
            "server.extend.requestGame",
            request,
            GameRequest.serializer()
        ) ?: throw Exception("RPC Invoker not initialized")
        Logger.debug(LogCategory.NETWORK, "MatchmakingClient: Received response from server-extend: ${kotlin.js.JSON.stringify(response)}")

        val rpcError = response.error
        val success = if (rpcError != null) {
            Logger.error(LogCategory.NETWORK, "MatchmakingClient: server.extend.requestGame failed with RPC error: ${rpcError.message} (code: ${rpcError.code})")
            false
        } else {
            response.result?.let {
                try {
                    val decoded = RpcJson.decodeFromJsonElement(serializer<Boolean>(), it)
                    Logger.info(LogCategory.NETWORK, "MatchmakingClient: server.extend.requestGame result: $decoded")
                    decoded
                } catch (e: Exception) {
                    Logger.error(LogCategory.NETWORK, "MatchmakingClient: Failed to decode result as Boolean: ${it}")
                    false
                }
            } ?: run {
                Logger.warn(LogCategory.NETWORK, "MatchmakingClient: server.extend.requestGame result is null")
                false
            }
        }

        if(!success && rpcError == null)
        {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient: server.extend.requestGame was rejected (returned false)")
        }

        return success
    }

    /**
     * Live-mode resume path. Phase C of the resume-game-architecture plan.
     *
     * Sends `server.extend.requestResume` to server-extend with a single-player
     * [GameRequest]. server-extend builds a single-player match2 ticket tagged
     * `resume: true`, routes it to a free DS, and the DS rehydrates the saved
     * snapshot on `server.setGameMode`. The returned [GameTicket] carries the
     * `serverUrl` of the resumed DS; the caller must reconnect the WS to that
     * URL via [connectToGameServer] so the existing `client.initialSync` path
     * delivers the resumed state.
     *
     * The same `forceRestTransportForMatchmaking` switch as the new-game path
     * is required because the active transport may be gRPC (which has a null
     * rpcInvoker for `server.extend.*` calls).
     *
     * @return The resolved [GameTicket] on success (caller should reconnect
     *   the WS via [connectToGameServer]); `null` if server-extend rejected
     *   the request, the match2 ticket never resolved, or the RPC returned
     *   no `serverUrl`.
     */
    suspend fun requestResumeLive(): GameTicket?
    {
        WebSocketRpcBridge.waitForConnection()

        val playerName = AccelByteEnv.displayName
            .takeIf { it.isNotBlank() }
            ?: AccelByteEnv.userName.takeIf { it.isNotBlank() }
            ?: "Commander"

        val request = GameRequest(
            userName = playerName,
            gameType = GameType.SINGLEPLAYER,
            accelByteId = AccelByteEnv.userId,
            websocketId = WebsocketConfig.websocketId,
            selectedCommander = null,
            aiOpponentCount = 0,
            aiOnly = false
        )

        ServerExtendBridge.forceRestTransportForMatchmaking()

        val invoker = ServerExtendBridge.rpcInvoker
        Logger.info(LogCategory.NETWORK, "MatchmakingClient: Sending resume request to server-extend for ${request.userName} (ID: ${request.accelByteId})")

        val response = invoker?.invoke(
            "server.extend.requestResume",
            request,
            GameRequest.serializer()
        ) ?: throw Exception("RPC Invoker not initialized")

        val rpcError = response.error
        if (rpcError != null)
        {
            Logger.error(LogCategory.NETWORK, "MatchmakingClient: server.extend.requestResume failed with RPC error: ${rpcError.message} (code: ${rpcError.code})")
            return null
        }

        val ticket: GameTicket? = response.result?.let { element ->
            try
            {
                val decoded = RpcJson.decodeFromJsonElement(GameTicket.serializer(), element)
                if (decoded.serverUrl.isBlank())
                {
                    Logger.warn(LogCategory.NETWORK, "MatchmakingClient: requestResume returned a GameTicket with blank serverUrl; treating as failure")
                    null
                }
                else
                {
                    Logger.info(LogCategory.NETWORK, "MatchmakingClient: requestResume returned sessionId=${decoded.sessionId} serverUrl=${decoded.serverUrl}")
                    decoded
                }
            }
            catch (e: Throwable)
            {
                Logger.error(LogCategory.NETWORK, "MatchmakingClient: failed to decode requestResume GameTicket result: $element (${e.message})")
                null
            }
        } ?: run {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient: requestResume result is null")
            null
        }

        return ticket
    }

    /**
     * Drives the live PvP matchmaking flow against server-extend.
     *
     * The flow is:
     *  1. `server.extend.invokeMatchMaking` to start a match ticket and obtain a [sessionId].
     *  2. Poll `server.extend.isServerReady` every [POLL_INTERVAL_MS] until the session is ready,
     *     cancelled, or the timeout fires.
     *  3. Call `server.extend.resolveUrl` to retrieve the resolved dedicated-server URL.
     *  4. Return [MatchOutcome.Ready] with the URL and session ID.
     *
     * Errors at any step collapse to [MatchOutcome.Failed] (with a human-readable reason) so the UI
     * can surface a single `MessageBox` regardless of which step failed.
     *
     * @param commander Commander the player intends to use; may be `null` if not yet selected.
     * @param matchPool Match pool to join; defaults to `"default"`.
     * @return [MatchOutcome.Ready] on success, [MatchOutcome.Cancelled] when the backend cancels
     *         the match, [MatchOutcome.Failed] on any other error or timeout.
     */
    suspend fun requestMultiplayerMatch(
        commander: Commander?,
        matchPool: String = "default"
    ): MatchOutcome
    {
        if (AccelByteEnv.userId.isBlank())
        {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient.requestMultiplayerMatch: not logged in (no AccelByteEnv.userId)")
            return MatchOutcome.Failed("not logged in")
        }

        // Ensure WebSocket is connected so we have a valid ID to send to server-extend
        WebSocketRpcBridge.waitForConnection()

        val playerName = AccelByteEnv.displayName
            .takeIf { it.isNotBlank() }
            ?: AccelByteEnv.userName
                .takeIf { it.isNotBlank() }
            ?: "Commander"

        val request = GameRequest(
            userName = playerName,
            gameType = GameType.MULTIPLAYER,
            accelByteId = AccelByteEnv.userId,
            websocketId = WebsocketConfig.websocketId,
            selectedCommander = commander,
            matchPool = matchPool
        )

        // Force REST/SSE transport for matchmaking, same rationale as requestSinglePlayerMatch.
        ServerExtendBridge.forceRestTransportForMatchmaking()

        val invoker = ServerExtendBridge.rpcInvoker
            ?: return MatchOutcome.Failed("RPC invoker not initialized")

        Logger.info(
            LogCategory.NETWORK,
            "MatchmakingClient.requestMultiplayerMatch: requesting MULTIPLAYER match for $playerName " +
                "(ID: ${request.accelByteId}, WS: ${request.websocketId}, pool: $matchPool)"
        )

        val initialTicket = try
        {
            invokeMatchMaking(request, invoker)
        }
        catch (err: Throwable)
        {
            Logger.error(LogCategory.NETWORK, "MatchmakingClient.requestMultiplayerMatch: invokeMatchMaking failed: ${err.message}")
            return MatchOutcome.Failed("matchmaking request failed: ${err.message}")
        }

        // Dev-mode fast path: ticket already has a server URL and matchmakingStarted=false.
        if (initialTicket.serverUrl.isNotBlank() && !initialTicket.matchmakingStarted)
        {
            Logger.info(
                LogCategory.NETWORK,
                "MatchmakingClient.requestMultiplayerMatch: dev-mode fast-path server=${initialTicket.serverUrl} session=${initialTicket.sessionId}"
            )
            return MatchOutcome.Ready(initialTicket.serverUrl, initialTicket.sessionId)
        }

        if (initialTicket.sessionId.isBlank())
        {
            Logger.error(LogCategory.NETWORK, "MatchmakingClient.requestMultiplayerMatch: invokeMatchMaking returned empty sessionId")
            return MatchOutcome.Failed("no session ID returned by matchmaking")
        }

        return pollForMatch(invoker, initialTicket, playerName, matchPool)
    }

    /**
     * Polls [isServerReady] until the ticket resolves to a server URL, is cancelled, or the timeout
     * fires. Returns the [MatchOutcome] summarizing the final state.
     */
    private suspend fun pollForMatch(
        invoker: org.ttt.autogenesis.network.RpcInvoker,
        initialTicket: GameTicket,
        playerName: String,
        matchPool: String
    ): MatchOutcome
    {
        val deadline = kotlin.js.Date.now().toLong() + MATCHMAKING_TIMEOUT_MS
        var currentTicket = initialTicket

        while (kotlin.js.Date.now().toLong() < deadline)
        {
            delay(POLL_INTERVAL_MS)

            val status = try
            {
                isServerReady(invoker, currentTicket)
            }
            catch (err: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "MatchmakingClient.requestMultiplayerMatch: isServerReady failed: ${err.message}, continuing")
                continue
            }

            if (status.isCancelled)
            {
                Logger.warn(LogCategory.NETWORK, "MatchmakingClient.requestMultiplayerMatch: backend cancelled match for $playerName (pool=$matchPool)")
                return MatchOutcome.Cancelled
            }

            if (status.isReady)
            {
                val serverUrl = try
                {
                    resolveUrl(invoker, currentTicket)
                }
                catch (err: Throwable)
                {
                    Logger.error(LogCategory.NETWORK, "MatchmakingClient.requestMultiplayerMatch: resolveUrl failed: ${err.message}")
                    return MatchOutcome.Failed("resolveUrl failed: ${err.message}")
                }

                if (serverUrl.isBlank())
                {
                    Logger.warn(LogCategory.NETWORK, "MatchmakingClient.requestMultiplayerMatch: resolveUrl returned blank for session=${currentTicket.sessionId}")
                    return MatchOutcome.Cancelled
                }

                Logger.info(
                    LogCategory.NETWORK,
                    "MatchmakingClient.requestMultiplayerMatch: match ready session=${currentTicket.sessionId} server=$serverUrl"
                )
                return MatchOutcome.Ready(serverUrl, currentTicket.sessionId)
            }
        }

        Logger.warn(LogCategory.NETWORK, "MatchmakingClient.requestMultiplayerMatch: timed out after ${MATCHMAKING_TIMEOUT_MS}ms for $playerName")
        return MatchOutcome.Failed("timeout")
    }

    /**
     * Reconnects [WebSocketRpcBridge] to a resolved dedicated-game-server URL and waits until the
     * session is ready for RPC calls (up to [CONNECT_TIMEOUT_MS]).
     *
     * @param serverUrl The dedicated-server URL returned by [MatchOutcome.Ready] (e.g. "10.0.0.5:7777").
     * @return `true` if the bridge is connected and session-ready; `false` otherwise.
     */
    suspend fun connectToGameServer(serverUrl: String): Boolean
    {
        val websocketBaseUrl = when
        {
            serverUrl.startsWith("ws://") || serverUrl.startsWith("wss://") -> serverUrl
            serverUrl.isBlank() -> return false.also {
                Logger.error(LogCategory.NETWORK, "MatchmakingClient.connectToGameServer: empty serverUrl, refusing to reconnect")
            }
            else -> "ws://$serverUrl"
        }

        Logger.info(LogCategory.NETWORK, "MatchmakingClient.connectToGameServer: reconnecting WebSocketRpcBridge to $websocketBaseUrl")
        try
        {
            WebSocketRpcBridge.close()
        }
        catch (err: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient.connectToGameServer: close() reported ${err.message}")
        }

        try
        {
            WebSocketRpcBridge.connect(
                baseUrl = websocketBaseUrl,
                accelbyteId = AccelByteEnv.userId.ifBlank { null }
            )
        }
        catch (err: Throwable)
        {
            Logger.error(LogCategory.NETWORK, "MatchmakingClient.connectToGameServer: connect failed: ${err.message}")
            return false
        }

        WebsocketConfig.websocketId = WebSocketRpcBridge.connectionId ?: ""

        // Wait for the session to become ready, with a safety cap.
        val deadline = kotlin.js.Date.now().toLong() + CONNECT_TIMEOUT_MS
        while (kotlin.js.Date.now().toLong() < deadline)
        {
            if (WebSocketRpcBridge.isSessionReady)
            {
                Logger.info(LogCategory.NETWORK, "MatchmakingClient.connectToGameServer: session ready on $websocketBaseUrl")
                return true
            }
            delay(100)
        }

        Logger.error(
            LogCategory.NETWORK,
            "MatchmakingClient.connectToGameServer: session not ready within ${CONNECT_TIMEOUT_MS}ms on $websocketBaseUrl"
        )
        return false
    }

    /**
     * Result of a resume-saved-game attempt.
     *
     * - [ResumeOutcome.Restored] indicates a saved snapshot was found and the
     *   world was rehydrated on the server. The client should mount gameplay.
     * - [ResumeOutcome.NoneSaved] indicates the server has no snapshot for
     *   this user. The UI should fall through to the normal "new game" path.
     * - [ResumeOutcome.Failed] wraps a human-readable reason suitable for
     *   surfacing in a MessageBox.
     */
    sealed class ResumeOutcome
    {
        data object Restored : ResumeOutcome()
        data object NoneSaved : ResumeOutcome()
        data class Failed(val reason: String) : ResumeOutcome()
    }

    /**
     * Asks the active WebSocket target whether the calling human player has a
     * saved running-game record. Returns `false` on any error (offline, no
     * active WS, RPC error, decode failure) so the caller can treat "unknown"
     * the same as "no save" and stay on the safe side of the modal.
     *
     * Dev mode targets the in-process game server directly. Live mode routes
     * through whatever WS the matchmaker handed us (i.e. a freshly-provisioned
     * DS that has not yet auto-restored), so this still works in either mode.
     */
    suspend fun hasRunningGame(): Boolean
    {
        val invoker = try
        {
            WebSocketRpcBridge.waitForConnection()
            WebSocketRpcBridge.rpcInvoker
        }
        catch (e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient.hasRunningGame: WS not ready (${e.message}); returning false")
            return false
        }
        if (invoker == null)
        {
            Logger.debug(LogCategory.NETWORK, "MatchmakingClient.hasRunningGame: rpcInvoker is null, returning false")
            return false
        }

        return try
        {
            val response = invoker.invoke("server.hasRunningGame", null)
            val err = response.error
            if (err != null)
            {
                Logger.warn(LogCategory.NETWORK, "MatchmakingClient.hasRunningGame: RPC error ${err.code} ${err.message}")
                return false
            }
            val element = response.result
                ?: return false.also { Logger.debug(LogCategory.NETWORK, "MatchmakingClient.hasRunningGame: result is null") }
            val decoded = RpcJson.decodeFromJsonElement(serializer<Boolean>(), element)
            Logger.info(LogCategory.NETWORK, "MatchmakingClient.hasRunningGame: result=$decoded")
            decoded
        }
        catch (e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient.hasRunningGame: lookup failed (${e.message}); returning false")
            false
        }
    }

    /**
     * Asks the server to rehydrate the calling player's saved snapshot. Used
     * by the dev "Resume saved game?" path where the in-process server is
     * still alive between sessions — the server needs an explicit nudge to
     * reload `WorldManager`/`TurnHarness` from the user's VFS record.
     *
     * Returns [ResumeOutcome.Restored] when a snapshot was applied (UI mounts
     * gameplay), [ResumeOutcome.NoneSaved] when the server has no record for
     * this user (UI falls through to new-game), and [ResumeOutcome.Failed]
     * with a reason when the RPC itself errored.
     */
    suspend fun requestResume(): ResumeOutcome
    {
        val invoker = try
        {
            WebSocketRpcBridge.waitForConnection()
            WebSocketRpcBridge.rpcInvoker
        }
        catch (e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient.requestResume: WS not ready: ${e.message}")
            return ResumeOutcome.Failed(e.message ?: "WebSocket not ready")
        }
        if (invoker == null)
        {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient.requestResume: rpcInvoker is null")
            return ResumeOutcome.Failed("RPC invoker not initialized")
        }

        return try
        {
            val response = invoker.invoke("server.restoreRunningGame", null)
            val err = response.error
            if (err != null)
            {
                Logger.warn(LogCategory.NETWORK, "MatchmakingClient.requestResume: RPC error ${err.code} ${err.message}")
                return ResumeOutcome.Failed(err.message)
            }
            val element = response.result
                ?: return ResumeOutcome.Failed("server returned null result")
            val restored = RpcJson.decodeFromJsonElement(serializer<Boolean>(), element)
            Logger.info(LogCategory.NETWORK, "MatchmakingClient.requestResume: restored=$restored")
            if (restored) ResumeOutcome.Restored else ResumeOutcome.NoneSaved
        }
        catch (e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient.requestResume: invoke threw: ${e.message}")
            ResumeOutcome.Failed(e.message ?: "unknown error")
        }
    }

    /**
     * Deletes the calling player's saved running-game record.
     *
     * Called right before kicking off a new single-player match so the next
     * disconnect captures the new game's state cleanly instead of resurrecting
     * the prior session.
     *
     * Best-effort: failures are logged and `false` is returned so the UI can
     * surface a warning, but game flow is not gated on the result.
     */
    suspend fun clearRunningGame(): Boolean
    {
        val invoker = try
        {
            WebSocketRpcBridge.waitForConnection()
            WebSocketRpcBridge.rpcInvoker
        }
        catch (e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient.clearRunningGame: WS not ready: ${e.message}")
            return false
        }
        if (invoker == null)
        {
            Logger.debug(LogCategory.NETWORK, "MatchmakingClient.clearRunningGame: rpcInvoker is null, skipping")
            return false
        }

        return try
        {
            val response = invoker.invoke("server.clearRunningGame", null)
            val err = response.error
            if (err != null)
            {
                Logger.warn(LogCategory.NETWORK, "MatchmakingClient.clearRunningGame: RPC error ${err.code} ${err.message}")
                return false
            }
            val element = response.result ?: return false
            val cleared = RpcJson.decodeFromJsonElement(serializer<Boolean>(), element)
            Logger.info(LogCategory.NETWORK, "MatchmakingClient.clearRunningGame: cleared=$cleared")
            cleared
        }
        catch (e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient.clearRunningGame: invoke threw: ${e.message}")
            false
        }
    }

    // ===== Raw RPC invocations =====

    /**
     * Tells the main server the user has consumed the `client.resumeAvailable`
     * push by clicking Resume, New Game, or Cancel. The server-side dedupe
     * (`UiSignalRpcHandlers.pushedResumeThisSession`) holds the push for the
     * rest of the server session; this call removes the userId from the set
     * so a future push (e.g. the user logs back in) is allowed to go through.
     *
     * Fire-and-forget: failures are logged and the call returns `false`, but
     * the user-visible action (Resume / New Game / Cancel) does NOT block on
     * the consume RPC. A failed consume just means the next login in the same
     * JVM will not see the dialog until the server is restarted — the same
     * behavior as if the user closed the browser before the SSE timeout.
     *
     * Bug 27 (2026-07-01): the dialog was reappearing on every SSE rebind
     * because the server was pushing repeatedly and the client was mounting
     * a fresh dialog each time. The server-side dedupe in
     * `UiSignalRpcHandlers.notifyResumeAvailable` plus this client-driven
     * consume RPC close the loop.
     */
    suspend fun consumeResumePush(userId: String): Boolean
    {
        if (userId.isBlank())
        {
            Logger.debug(LogCategory.NETWORK, "MatchmakingClient.consumeResumePush: userId blank, skipping")
            return false
        }
        val invoker = try
        {
            WebSocketRpcBridge.waitForConnection()
            WebSocketRpcBridge.rpcInvoker
        }
        catch (e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient.consumeResumePush: WS not ready for userId=$userId: ${e.message}")
            return false
        }
        if (invoker == null)
        {
            Logger.debug(LogCategory.NETWORK, "MatchmakingClient.consumeResumePush: rpcInvoker is null, skipping")
            return false
        }

        return try
        {
            val payload = RpcJson.encodeToJsonElement(
                ResumePushConsumeRequest.serializer(),
                ResumePushConsumeRequest(userId = userId)
            )
            val response = invoker.invoke("server.consumeResumePush", payload)
            val err = response.error
            if (err != null)
            {
                Logger.warn(LogCategory.NETWORK, "MatchmakingClient.consumeResumePush: RPC error for userId=$userId: ${err.code} ${err.message}")
                return false
            }
            Logger.info(LogCategory.NETWORK, "MatchmakingClient.consumeResumePush: ok for userId=$userId (next push will re-arm)")
            true
        }
        catch (e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient.consumeResumePush: invoke threw for userId=$userId: ${e.message}")
            false
        }
    }

    /**
     * Fire-and-forget variant of [consumeResumePush] intended for the
     * Resume / New Game / Cancel button callbacks in
     * `ui.MainMenu.wireResumeDialog`. The button's user-visible action
     * (mount GameplayUI / open the commander selection dialog / just
     * hide the dialog) must NOT block on the consume wire — the user's
     * click should feel instant.
     *
     * The GlobalScope.launch pattern is acceptable here because:
     *   1. The RPC is idempotent and best-effort (server is permissive).
     *   2. The dispatch site is already inside an event handler; a missed
     *      tick would just leave the next push suppressed until the user
     *      restarts the server, not crash the page.
     */
    fun consumeResumePushFireAndForget(userId: String)
    {
        GlobalScope.launch {
            consumeResumePush(userId)
        }
    }

    private suspend fun invokeMatchMaking(
        request: GameRequest,
        invoker: org.ttt.autogenesis.network.RpcInvoker
    ): GameTicket
    {
        val response = invoker.invoke(
            "server.extend.invokeMatchMaking",
            request,
            GameRequest.serializer()
        )
        val rpcError = response.error
        if (rpcError != null)
        {
            Logger.error(
                LogCategory.NETWORK,
                "MatchmakingClient.requestMultiplayerMatch: RPC error ${rpcError.code}: ${rpcError.message}"
            )
            return GameTicket(sessionId = "", serverUrl = "", matchmakingStarted = false)
        }
        val ticket = response.result?.let {
            try
            {
                RpcJson.decodeFromJsonElement(GameTicket.serializer(), it)
            }
            catch (e: Exception)
            {
                Logger.error(LogCategory.NETWORK, "MatchmakingClient.requestMultiplayerMatch: failed to decode GameTicket: ${e.message}")
                GameTicket(sessionId = "", serverUrl = "", matchmakingStarted = false)
            }
        } ?: run {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient.requestMultiplayerMatch: invokeMatchMaking result was null")
            GameTicket(sessionId = "", serverUrl = "", matchmakingStarted = false)
        }
        return ticket
    }

    private suspend fun isServerReady(
        invoker: org.ttt.autogenesis.network.RpcInvoker,
        ticket: GameTicket
    ): structs.matchmaking.GameTicketStatus
    {
        val response = invoker.invoke(
            "server.extend.isServerReady",
            ticket,
            GameTicket.serializer()
        )
        val rpcError = response.error
        if (rpcError != null)
        {
            Logger.error(
                LogCategory.NETWORK,
                "MatchmakingClient.isServerReady: RPC error ${rpcError.code}: ${rpcError.message}"
            )
            return structs.matchmaking.GameTicketStatus(isReady = false, isCancelled = true)
        }
        val status = response.result?.let {
            try
            {
                RpcJson.decodeFromJsonElement(structs.matchmaking.GameTicketStatus.serializer(), it)
            }
            catch (e: Exception)
            {
                Logger.error(LogCategory.NETWORK, "MatchmakingClient.isServerReady: failed to decode status: ${e.message}")
                structs.matchmaking.GameTicketStatus(isReady = false, isCancelled = true)
            }
        } ?: run {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient.isServerReady: result was null")
            structs.matchmaking.GameTicketStatus(isReady = false, isCancelled = false)
        }
        return status
    }

    private suspend fun resolveUrl(
        invoker: org.ttt.autogenesis.network.RpcInvoker,
        ticket: GameTicket
    ): String
    {
        val response = invoker.invoke(
            "server.extend.resolveUrl",
            ticket,
            GameTicket.serializer()
        )
        val rpcError = response.error
        if (rpcError != null)
        {
            Logger.error(
                LogCategory.NETWORK,
                "MatchmakingClient.resolveUrl: RPC error ${rpcError.code}: ${rpcError.message}"
            )
            return ""
        }
        val url = response.result?.let {
            try
            {
                RpcJson.decodeFromJsonElement(serializer<String>(), it)
            }
            catch (e: Exception)
            {
                Logger.error(LogCategory.NETWORK, "MatchmakingClient.resolveUrl: failed to decode URL: ${e.message}")
                ""
            }
        } ?: run {
            Logger.warn(LogCategory.NETWORK, "MatchmakingClient.resolveUrl: result was null")
            ""
        }
        return url
    }
}
