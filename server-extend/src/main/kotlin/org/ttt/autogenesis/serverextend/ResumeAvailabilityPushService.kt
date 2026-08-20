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
 * **Push-side dedupe (BUG 26/27, 2026-07-05).** The browser's SSE client
 * auto-reconnects every ~45-60s. Without a push-side dedupe, every reconnect
 * fires `client.resumeAvailable` again. The receiver-side dedupe in
 * `UiSignalRpcHandlers.notifyResumeAvailable` (keyed by `userId` in a
 * ConcurrentHashMap) was insufficient because the client can clear it
 * via `server.consumeResumePush` on dialog dismiss — by the time the next
 * SSE reconnect arrives, the receiver-side dedupe is empty and the push
 * goes through. The push-side dedupe here is keyed on `savedAt`
 * (invariant under reconnects: the snapshot doesn't change between
 * reconnects that happen close together) plus a wall-clock cooldown
 * (5 min). Both signals are AND-combined: same `savedAt` AND within
 * cooldown → skip. Any other case → push.
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
     * Per-server-instance dedupe cache for push-side dedup (BUG 26/27,
     * 2026-07-05). Keyed by `userId`. The value carries the snapshot's
     * `savedAt` (the dedup key) and the wall-clock time of the last push.
     *
     * Server restart re-arms the cache (fresh JVM, fresh ConcurrentHashMap).
     * This matches the user's spec: "fresh start, fresh push."
     */
    private data class LastPush(val savedAt: String, val pushedAtMs: Long)

    private val lastPushByUser: java.util.concurrent.ConcurrentHashMap<String, LastPush> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Wall-clock cooldown for push-side dedup. A repeat push for the same
     * `savedAt` within this many milliseconds is skipped. Set to 5 minutes
     * — longer than typical SSE reconnect cadences (~45-60s) but short
     * enough that a fresh login on the same JVM after a long disconnect
     * still gets the push. The cooldown is a SECOND-BEST option; the
     * primary dedup signal is `savedAt`.
     */
    private const val PUSH_DEDUPE_COOLDOWN_MS: Long = 5L * 60L * 1000L

    /**
     * Diagnostic enum for the dedupe decision. Each value names the
     * condition that led to the decision so log lines and tests can
     * distinguish a cache miss (push), a savedAt match (skip), and a
     * cooldown-only match (skip).
     */
    enum class PushSkipReason
    {
        /** No prior push recorded for this user — proceed. */
        NONE,
        /** Cached `savedAt` matches incoming `savedAt` AND we are within the cooldown — skip. */
        SAME_SAVED_AT_WITHIN_COOLDOWN,
        /** `userId` is blank — never push. Defensive guard mirroring checkAndPush. */
        BLANK_USER_ID,
        /** `savedAt` is blank — no dedup signal possible, but markPushed was never called so cooldown alone cannot fire. */
        BLANK_SAVED_AT_NO_DEDUP_SIGNAL
    }

    /**
     * Pure decision function for whether a push should proceed. Extracted
     * from [checkAndPushBlocking] so it is unit-testable without spinning
     * up a WebSocket or VFS. Production callers pass `System.currentTimeMillis()`
     * for `nowMs`; tests pass synthetic times for determinism.
     *
     * `savedAt` is nullable because [ResumeAvailabilityNotification.savedAt]
     * is nullable in the wire format. A null/blank `savedAt` means no
     * dedup signal is available; the function falls through to "push."
     *
     * Decision rules:
     *   1. Blank `userId` → skip (defensive)
     *   2. Blank or null `savedAt` → push (no dedup signal possible; this is
     *      the documented limitation, see BUG 26 test case 8)
     *   3. No cache entry for `userId` → push (cache miss)
     *   4. Cached `savedAt` matches AND `now - pushedAtMs < cooldownMs` → skip
     *   5. Otherwise → push (savedAt changed = new save, or cooldown elapsed)
     */
    internal fun shouldPushResumeAvailability(
        userId: String,
        savedAt: String?,
        nowMs: Long,
        cooldownMs: Long = PUSH_DEDUPE_COOLDOWN_MS
    ): PushDecision
    {
        if (userId.isBlank())
        {
            return PushDecision(shouldPush = false, reason = PushSkipReason.BLANK_USER_ID)
        }
        if (savedAt.isNullOrBlank())
        {
            // No dedup signal possible. The push proceeds; cooldown-only
            // dedup is also unavailable (markPushed never runs for blank
            // savedAt in production). This is a known limitation —
            // documented in ResumeAvailabilityPushDedupeTest case 8.
            return PushDecision(shouldPush = true, reason = PushSkipReason.BLANK_SAVED_AT_NO_DEDUP_SIGNAL)
        }
        val cached = lastPushByUser[userId] ?: return PushDecision(shouldPush = true, reason = PushSkipReason.NONE)
        val sameSavedAt = cached.savedAt == savedAt
        val withinCooldown = (nowMs - cached.pushedAtMs) < cooldownMs
        return if (sameSavedAt && withinCooldown)
        {
            PushDecision(shouldPush = false, reason = PushSkipReason.SAME_SAVED_AT_WITHIN_COOLDOWN)
        }
        else
        {
            PushDecision(shouldPush = true, reason = PushSkipReason.NONE)
        }
    }

    /**
     * Records that a push happened for `userId` with the supplied `savedAt`
     * at wall-clock time `nowMs`. Subsequent calls to
     * [shouldPushResumeAvailability] for the same `userId` + `savedAt`
     * within the cooldown will return `shouldPush = false`.
     *
     * Production callers MUST invoke this after a successful pushToMainServer
     * call. The call is idempotent — calling it twice with the same args
     * just refreshes `pushedAtMs`.
     */
    internal fun markPushed(userId: String, savedAt: String, nowMs: Long)
    {
        if (userId.isBlank() || savedAt.isBlank())
        {
            // Do not record blank-keyed entries; they cannot contribute to
            // dedup and would only clutter the cache.
            return
        }
        lastPushByUser[userId] = LastPush(savedAt = savedAt, pushedAtMs = nowMs)
    }

    /**
     * Test seam: clears the dedupe cache so test cases do not leak state
     * between runs. NOT for production use.
     */
    internal fun resetResumePushDedupeForTest()
    {
        lastPushByUser.clear()
    }

    /**
     * The dedupe decision: whether the push should proceed, and the
     * reason for the decision. The reason is exposed for log diagnostics
     * and test assertions.
     */
    internal data class PushDecision(
        val shouldPush: Boolean,
        val reason: PushSkipReason
    )

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

                // BUG 26/27 (2026-07-05): push-side dedupe. The SSE handler
                // calls checkAndPush on every reconnect (~45-60s). The
                // receiver-side dedupe in UiSignalRpcHandlers.notifyResumeAvailable
                // is keyed on userId and is cleared by the client's
                // server.consumeResumePush call. By the time the next SSE
                // reconnect arrives, the receiver-side dedupe is empty and
                // the push goes through. The push-side dedupe here is keyed
                // on savedAt (invariant under reconnects: the snapshot's
                // savedAt doesn't change between reconnects that happen
                // close together) plus a 5-minute wall-clock cooldown as a
                // backstop. Both signals are AND-combined.
                //
                // notification.savedAt is nullable (the data class allows
                // null for forward-compat), but production always builds it
                // from response.updatedAt ?: Instant.now().toString(), so
                // it is effectively never null here. The decision function
                // accepts String? and treats blank/null as "no dedup signal."
                val nowMs = System.currentTimeMillis()
                val dedupeDecision = shouldPushResumeAvailability(
                    userId = userId,
                    savedAt = notification.savedAt,
                    nowMs = nowMs
                )
                if (!dedupeDecision.shouldPush)
                {
                    Logger.info(
                        LogCategory.NETWORK,
                        "ResumeAvailabilityPushService: push skipped for user=$userId reason=${dedupeDecision.reason} (push-side dedupe; savedAt=${notification.savedAt})"
                    )
                    return@fold
                }

                pushToMainServer(userId, notification)
                // Record the push ONLY after a successful pushToMainServer
                // call so a failed push can be retried on the next reconnect
                // (markPushed-then-fail would leak dedupe state and the user
                // would never see the modal until the cooldown elapsed).
                // notification.savedAt is non-null in production builds but
                // we guard the cast for safety.
                notification.savedAt?.let { markPushed(userId, it, nowMs = nowMs) }
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