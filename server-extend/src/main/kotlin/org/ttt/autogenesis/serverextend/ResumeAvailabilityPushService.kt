@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package org.ttt.autogenesis.serverextend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.network.RpcRegistry
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.WebSocketRpcClient
import org.ttt.autogenesis.network.WebSocketRpcClientConfig
import org.ttt.autogenesis.network.registerRpcSystem
import org.ttt.autogenesis.server.vfs.RecordNotFoundException
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import structs.resume.ResumeAvailabilityNotification
import java.time.Instant

/**
 * Detects whether a given AccelByte user has a saved running-game record
 * and, if so, asks the connected main server to push a
 * [ResumeAvailabilityNotification] to the user's WebSocket so the browser
 * can render the [ResumeOrNewDialog].
 *
 * This is the server-extend side of the resume-game push architecture
 * (Phase B of the resume-game-architecture plan). server-extend is the
 * natural place for the check because it owns the SSE channel the client
 * uses for matchmaking, and it has the cloud-save proxy that the main
 * server doesn't need to know about.
 *
 * The service is best-effort: any failure to read the VFS record, open
 * a WebSocket to the main server, or invoke the resumeAvailable RPC logs
 * at WARN and returns. The user simply does not see the modal and
 * falls through to the normal Play button flow.
 *
 * **Bridge contract** — the call to the main server uses the same
 * `WebSocketRpcClient` + `rpcInvoker.invoke` pattern as
 * [org.ttt.autogenesis.serverextend.matchmaking.ServerConnector.notifyGameServer].
 * The RPC method name is `client.resumeAvailable` (RpcDirection.SERVER
 * on the main server's side — see `UiSignalRpcHandlers.notifyResumeAvailable`).
 */
object ResumeAvailabilityPushService
{
    /**
     * Triggers a one-shot check. Fires the notification asynchronously;
     * callers do not need to await. Use this from the SSE connection
     * handler in [RestPlayerConnectionManager].
     */
    fun checkAndPush(userId: String)
    {
        if (userId.isBlank())
        {
            return
        }
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()) {
            try
            {
                checkAndPushBlocking(userId)
            }
            catch (e: Exception)
            {
                Logger.warn(LogCategory.SYSTEM, "ResumeAvailabilityPushService: check failed for user=$userId: ${e.message}")
            }
        }
    }

    private suspend fun checkAndPushBlocking(userId: String)
    {
        // Read the running-game record via the same VFS key the main
        // server uses, so the lookup path is identical. In dev mode this
        // routes to the local VFS; in live mode it routes through
        // CloudSaveProxy (via VirtualFileSystemManager's adapter).
        val vfs = VirtualFileSystemManager.forUser(userId)
        val fetch = vfs.fetchUserRecord(userId, RUNNING_GAME_KEY)
        fetch.fold(
            onSuccess = { response ->
                val raw = response.value
                val jsonString: String? = if (raw is JsonElement)
                {
                    if (raw is kotlinx.serialization.json.JsonObject && raw.containsKey("value"))
                    {
                        raw["value"].toString()
                    }
                    else
                    {
                        raw.toString()
                    }
                }
                else
                {
                    raw?.toString()
                }
                if (jsonString.isNullOrBlank())
                {
                    Logger.debug(LogCategory.SYSTEM, "ResumeAvailabilityPushService: user=$userId value blank, skipping push")
                    return@fold
                }
                val snapshot = runCatching { com.TTT.Util.deserialize<gameState.GameSnapshot>(jsonString) }.getOrNull()
                if (snapshot == null)
                {
                    Logger.debug(LogCategory.SYSTEM, "ResumeAvailabilityPushService: user=$userId record not a parseable snapshot, skipping push")
                    return@fold
                }
                val aiCount = snapshot.world.activePlayers.count { player ->
                    // Treat any non-human player as AI. The snapshot's
                    // humanPlayerName marks the single human; the rest
                    // are AI or NPC.
                    player.name != snapshot.humanPlayerName
                }
                val notification = ResumeAvailabilityNotification(
                    userId = userId,
                    worldRound = snapshot.world.roundNumber,
                    turnIndex = snapshot.world.activePlayers.indexOfFirst { it.name == snapshot.humanPlayerName }.coerceAtLeast(0),
                    hasAi = aiCount > 0,
                    savedAt = response.updatedAt ?: Instant.now().toString()
                )
                pushToMainServer(userId, notification)
            },
            onFailure = { err ->
                val isNotFound = err is RecordNotFoundException || (err.message ?: "").contains("not found", ignoreCase = true)
                Logger.debug(LogCategory.SYSTEM, "ResumeAvailabilityPushService: user=$userId fetch result notFound=$isNotFound (no push)")
            }
        )
    }

    /**
     * Opens a short-lived WebSocket to the main server and invokes
     * `client.resumeAvailable` on it. The main server's handler
     * [org.ttt.autogenesis.server.UiSignalRpcHandlers.notifyResumeAvailable]
     * resolves the user's WS connection and pushes a
     * `client.resumeAvailable` notification back to the browser.
     *
     * Live-mode routing is implemented by [resolveMainServerWebSocketUrl]:
     * the URL defaults to `ws://127.0.0.1:9080` (the main server's
     * default dev-mode port) but is overridable in production via the
     * `SERVER_EXTEND_MAIN_SERVER_WS_URL` env var or the
     * `serverExtend.mainServerWsUrl` JVM property. Operators deploying
     * server-extend to a cluster set the env var to the cluster-local
     * WebSocket endpoint that the main server exposes.
     */
    private suspend fun pushToMainServer(userId: String, notification: ResumeAvailabilityNotification)
    {
        val baseUrl = resolveMainServerWebSocketUrl()
        val connectorPlayerId = "server-extend-resume-push"
        Logger.debug(LogCategory.NETWORK, "ResumeAvailabilityPushService: connecting to $baseUrl for resumeAvailable push user=$userId")

        val registry = RpcRegistry(RpcDirection.CLIENT).also { registerRpcSystem() }
        val client = WebSocketRpcClient(
            WebSocketRpcClientConfig(
                baseUrl = baseUrl,
                playerId = connectorPlayerId,
                // CRITICAL: this connection MUST register as CONTROLLER,
                // not PRIMARY. The main server's onDisconnected handler
                // gates the running-game snapshot write on
                // `!hasAnyPrimarySession()`; if this long-lived
                // server-extend connection counted as PRIMARY, every
                // player disconnect in single-player mode would be
                // misclassified as "another primary is still connected"
                // and the snapshot would never be written. See
                // server/src/main/kotlin/org/ttt/autogenesis/server/Server.kt
                // ServerConnectionCoordinator.onDisconnected block for the
                // gating logic, and sharedModel/.../WebSocketRpcClientConfig.kt
                // for the role= URL parameter contract.
                role = WebSocketRpcClientConfig.Role.CONTROLLER
            ),
            registry
        )

        val readySignal = CompletableDeferred<Unit>()
        client.onConnected {
            if (!readySignal.isCompleted) readySignal.complete(Unit)
        }

        try
        {
            client.connect()
            val connected = withTimeoutOrNull(5_000) { readySignal.await() } != null
            if (!connected)
            {
                Logger.warn(LogCategory.NETWORK, "ResumeAvailabilityPushService: timed out waiting for main server WebSocket (user=$userId, push dropped)")
                return
            }

            // BUG 2 fix (2026-06-26): wait until the main server's auto-restore
            // coroutine for this user is finished (or no restore was running)
            // before pushing `client.resumeAvailable`. Without this wait,
            // server-extend reads the snapshot at one moment, the main server's
            // auto-restore consumes the snapshot milliseconds later, and the
            // user's Resume click hits the consumed-sentinel and produces
            // "No saved game found."
            //
            // Poll `server.restoreStatus` with a 100ms interval. The main
            // server sets `restoreInProgress=true` for the duration of the
            // auto-restore coroutine and clears it in a `finally` block. The
            // race window is bounded by the restore duration (typical ~600ms,
            // worst case a few seconds for a slow cloud VFS fetch).
            //
            // Cap the wait at 5s — if the main server hasn't responded by then,
            // the push is dropped to avoid blocking server-extend indefinitely.
            val restoreDone = waitForRestoreToFinish(client.rpcInvoker, userId, timeoutMs = 5_000L)
            if (!restoreDone)
            {
                Logger.warn(LogCategory.NETWORK, "ResumeAvailabilityPushService: timed out waiting for main server restore (user=$userId, push dropped to avoid stale-snapshot race)")
                return
            }

            val payload = RpcJson.encodeToJsonElement(ResumeAvailabilityNotification.serializer(), notification)
            val response: RpcMessage.Response = client.rpcInvoker.invoke("client.resumeAvailable", payload)
            val rpcError = response.error
            if (rpcError != null)
            {
                Logger.warn(LogCategory.NETWORK, "ResumeAvailabilityPushService: client.resumeAvailable RPC error for user=$userId: ${rpcError.message} (code: ${rpcError.code})")
            }
            else
            {
                Logger.info(LogCategory.NETWORK, "ResumeAvailabilityPushService: pushed resumeAvailable for user=$userId round=${notification.worldRound} hasAi=${notification.hasAi}")
            }
        }
        catch (e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "ResumeAvailabilityPushService: failed to push resumeAvailable for user=$userId: ${e.message}")
        }
        finally
        {
            kotlinx.coroutines.delay(500)
            client.close()
        }
    }

    private const val RUNNING_GAME_KEY = "running-game"

    /**
     * Polls the main server's `server.restoreStatus` RPC until the
     * auto-restore for [userId] is no longer in flight (or the [timeoutMs]
     * budget elapses). Returns `true` if the restore is finished (or was
     * never running), `false` on timeout.
     *
     * Used by [pushToMainServer] to prevent the race where server-extend
     * pushes `client.resumeAvailable` based on a snapshot the main server
     * is about to consume via its auto-restore path.
     */
    private suspend fun waitForRestoreToFinish(invoker: org.ttt.autogenesis.network.RpcInvoker, userId: String, timeoutMs: Long): Boolean
    {
        val deadline = System.currentTimeMillis() + timeoutMs
        val pollIntervalMs = 100L
        var firstAttempt = true
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                val request = org.ttt.autogenesis.network.RestoreStatusRequest(userId = userId)
                val payload = RpcJson.encodeToJsonElement(
                    org.ttt.autogenesis.network.RestoreStatusRequest.serializer(),
                    request
                )
                val response = invoker.invoke("server.restoreStatus", payload)
                val err = response.error
                if (err == null)
                {
                    val element = response.result
                    if (element != null)
                    {
                        val status = RpcJson.decodeFromJsonElement(
                            org.ttt.autogenesis.network.RestoreStatusResponse.serializer(),
                            element
                        )
                        if (!status.restoreInProgress)
                        {
                            if (!firstAttempt)
                            {
                                Logger.debug(
                                    LogCategory.SYSTEM,
                                    "ResumeAvailabilityPushService: user=$userId restore finished (rehydrated=${status.rehydrated}); proceeding with push"
                                )
                            }
                            return true
                        }
                    }
                }
                else
                {
                    Logger.debug(
                        LogCategory.SYSTEM,
                        "ResumeAvailabilityPushService: server.restoreStatus RPC error for user=$userId (code=${err.code}, message=${err.message}); retrying"
                    )
                }
            }
            catch (e: Throwable)
            {
                Logger.debug(
                    LogCategory.SYSTEM,
                    "ResumeAvailabilityPushService: server.restoreStatus poll failed for user=$userId: ${e.message}; retrying"
                )
            }
            firstAttempt = false
            kotlinx.coroutines.delay(pollIntervalMs)
        }
        Logger.warn(
            LogCategory.NETWORK,
            "ResumeAvailabilityPushService: timed out after ${timeoutMs}ms waiting for main server restore (user=$userId)"
        )
        return false
    }

    /**
     * Resolved once at service init (or read on every push) from the
     * `serverExtend.mainServerWsUrl` JVM property, then the
     * `SERVER_EXTEND_MAIN_SERVER_WS_URL` env var, then the dev default
     * `ws://127.0.0.1:9080`. The URL is the WebSocket endpoint the
     * main server exposes for client RPC — not the DS URL the client
     * reconnects to after a resume. server-extend only needs the main
     * server's URL because the main server owns the per-user WS
     * routing table.
     */
    fun resolveMainServerWebSocketUrl(): String
    {
        val fromProperty = System.getProperty("serverExtend.mainServerWsUrl")?.takeIf { it.isNotBlank() }
        if (fromProperty != null)
        {
            return normalizeWebSocketUrl(fromProperty)
        }
        val fromEnv = System.getenv("SERVER_EXTEND_MAIN_SERVER_WS_URL")?.takeIf { it.isNotBlank() }
        if (fromEnv != null)
        {
            return normalizeWebSocketUrl(fromEnv)
        }
        return DEFAULT_MAIN_SERVER_WS_URL
    }

    private fun normalizeWebSocketUrl(raw: String): String
    {
        val trimmed = raw.trim().trimEnd('/')
        // Accept either ws://, wss://, http://, or https:// — the WS
        // bridge handles both. Coerce http(s) to ws(s) so operators can
        // copy the main server's REST URL directly.
        return when
        {
            trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
            trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.removePrefix("http://")
            else -> "ws://$trimmed"
        }
    }

    private const val DEFAULT_MAIN_SERVER_WS_URL = "ws://127.0.0.1:9080"
}
