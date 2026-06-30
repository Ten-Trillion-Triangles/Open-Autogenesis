package org.ttt.autogenesis.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import kotlin.time.TimeSource

/**
 * Configuration required to establish a REST-based RPC connection.
 *
 * @param baseUrl Base server URL (scheme + host + optional path prefix)
 * @param playerId Identifier used to correlate SSE and RPC requests with a player session
 * @param eventsPath Relative path to the SSE stream (defaults to `/events`)
 * @param rpcPath Relative path where RPC payloads are accepted (defaults to `/rpc`)
 */
data class RestRpcClientConfig(
    val baseUrl : String,
    val playerId : String,
    val eventsPath : String = "/events",
    val rpcPath : String = "/rpc",
    val suppressSseLogs : Boolean = true,
    val isGuestMode : Boolean = false,
    val drainTimeoutMillis : Long = 250L,
    // TDD seam: field added so wire-level contract test can compile.
    // Wiring through `buildEndpointUrl` (Fix 1 GREEN) is intentionally
    // absent here — see `RestRpcClientConfigUrlTest`. `internal` so the
    // commonTest source set can read it back for assertions.
    internal val accelbyteId : String? = null,
    /**
     * Origin tag for the connection. Carried on the wire as a
     * `?origin=server` query parameter so the receiver can
     * distinguish game-client traffic from game-server traffic
     * (see [RpcOrigin]). Defaults to [RpcOrigin.GAME_CLIENT] for
     * backwards compatibility with every existing call site.
     */
    val origin : RpcOrigin = RpcOrigin.GAME_CLIENT,
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl cannot be blank" }
        require(playerId.isNotBlank()) { "playerId cannot be blank" }
        require(drainTimeoutMillis >= 0L) { "drainTimeoutMillis cannot be negative" }
    }
}

/**
 * HTTP-backed RPC connection that consumes Server-Sent Events for incoming traffic and
 * posts RPC payloads back to the REST server.
 */
class RestRpcClient(
    private val config : RestRpcClientConfig,
    rpcRegistry : RpcRegistry,
    coroutineScope : CoroutineScope? = null,
    customClient : HttpClient? = null,
    metadata : Map<String, String> = emptyMap(),
    private val logger : (Throwable) -> Unit = {}
) {
    private val internalScope : CoroutineScope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ownsScope : Boolean = coroutineScope == null
    private val httpClient : HttpClient
    private val ownsClient : Boolean
    private var sseJob : Job? = null
    private var sessionReady = false
    private var onConnectedCallback: (() -> Unit)? = null
    private var keepReconnecting = true
    private var reconnectAttempts = 0
    private var sseErrorStreak = 0
    private var consecutiveErrors = 0
    private var lastSseErrorMark = TimeSource.Monotonic.markNow()
    private val consecutiveErrorWindowMs = 5000L
    private var corsReconnectSuppressed = false
    private var reconnectJob : Job? = null
    private val reconnectBaseDelayMillis = 1000L
    private val reconnectMaxDelayMillis = 10000L
    private val suppressSseLogs = config.suppressSseLogs
    private val invoker : RpcInvoker
    private val messageHandler : RpcMessageHandler
    private val connectionEstablishedDeferred = CompletableDeferred<Unit>()

    // Network error throttling to prevent log spam when server is unavailable
    private var lastErrorLogMark: kotlin.time.TimeMark = TimeSource.Monotonic.markNow()
    private val MIN_ERROR_LOG_INTERVAL_MS = 30_000 // Log errors at most once every 30 seconds

    init {
        if (customClient != null) {
            httpClient = customClient
            ownsClient = false
        } else {
            httpClient = createClient()
            ownsClient = true
        }
        invoker = RpcInvoker(sender = { send(it) })
        messageHandler = RpcMessageHandler(
            connectionId = config.playerId,
            rpcRegistry = rpcRegistry,
            invoker = invoker,
            sender = { send(it) },
            metadata = metadata
        )
    }

    val connectionId : String get() = config.playerId
    val rpcInvoker : RpcInvoker get() = invoker

    fun connect(autoReconnect: Boolean = true)
    {
        if (sseJob?.isActive == true) return

        keepReconnecting = autoReconnect
        sseErrorStreak = 0
        cancelSseJob()
        cancelReconnectJob()
        sseJob = internalScope.launch {
            val connectionScope = this
            try {
                // Only log connection attempts for the first 3 attempts
                if (reconnectAttempts < 3) {
                    logDebug("Starting SSE connection to ${buildEndpointUrl(config.eventsPath)}")
                }
                val headers = mapOf(HttpHeaders.Accept to ContentType.Text.EventStream.toString())
                val sseUrl = buildEndpointUrl(config.eventsPath)
                Logger.info(LogCategory.NETWORK, "RestRpcClient.connect: Opening SSE channel to $sseUrl (playerId=${config.playerId}, guestMode=${config.isGuestMode})")
                val channel = httpClient.openSseChannel(connectionScope, sseUrl, headers)
                Logger.info(LogCategory.NETWORK, "RestRpcClient.connect: SSE channel opened for playerId=${config.playerId}")
                if (reconnectAttempts < 3) {
                    logDebug("SSE connection established")
                }
                reconnectAttempts = 0
                onConnectedCallback?.invoke()
                connectionEstablishedDeferred.complete(Unit)

                val eventLines = mutableListOf<String>()
                try {
                    while (!channel.isClosedForRead && isActive) {
                        val line = channel.readUTF8Line() ?: break
                        logDebug("Received SSE line: '$line'")
                        if (line.isBlank()) {
                            processEventLines(eventLines)
                            eventLines.clear()
                        } else {
                            eventLines.add(line)
                        }
                    }
                    processEventLines(eventLines)
                } finally {
                    // Only log close for first 3 attempts to avoid spam
                    if (reconnectAttempts < 3) {
                        logDebug("SSE connection closed")
                    }
                    channel.cancel(null)
                }
            } catch (err : Throwable) {
                logError("SSE connection error: ${err.message ?: err::class.simpleName}")
                if (err is CancellationException) throw err
                evaluateReconnectPolicy(err)
                reportError(err)
            } finally {
                // Only log for first 3 attempts to avoid spam
                if (reconnectAttempts < 3) {
                    logDebug("SSE job finished")
                }
                sessionReady = false
                sseJob = null
                if (keepReconnecting) {
                    incrementReconnectAttempts()
                    scheduleReconnect()
                }
            }
        }
    }

    fun disconnect()
    {
        keepReconnecting = false
        sessionReady = false
        cancelSseJob()
        cancelReconnectJob()
    }

    private fun cancelSseJob()
    {
        sseJob?.cancel()
        sseJob = null
    }

    private fun cancelReconnectJob()
    {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun scheduleReconnect()
    {
        if (!keepReconnecting) return
        if (reconnectJob?.isActive == true) return

        val delayMillis = nextReconnectDelayMillis()
        reconnectJob = internalScope.launch {
            // Only log reconnect attempts for the first 3 attempts to avoid spam
            if (reconnectAttempts < 3) {
                logDebug("Reconnecting SSE in ${delayMillis}ms (attempt $reconnectAttempts)")
            }
            delay(delayMillis)
            reconnectJob = null
            if (keepReconnecting && sseJob?.isActive != true) {
                if (reconnectAttempts < 3) {
                    logDebug("Attempting SSE reconnect (attempt $reconnectAttempts)")
                }
                connect()
            }
        }
    }

    private fun nextReconnectDelayMillis() : Long
    {
        val multiplier = reconnectAttempts.coerceAtLeast(1)
        return (reconnectBaseDelayMillis * multiplier).coerceAtMost(reconnectMaxDelayMillis)
    }

    private fun incrementReconnectAttempts()
    {
        reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(10)
    }

    fun isConnected() : Boolean = sseJob?.isActive == true

    fun isSessionReady() : Boolean = sessionReady && isConnected()

    fun onConnected(callback: () -> Unit) {
        onConnectedCallback = callback
    }

    suspend fun send(message : RpcMessage)
    {
        val payload = message.toJson(RpcJson)
        val targetUrl = buildEndpointUrl(config.rpcPath)
        Logger.debug(LogCategory.NETWORK, "RestRpcClient.send: Posting to $targetUrl, message type=${message::class.simpleName}")
        try {
            val response = httpClient.post(targetUrl) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            Logger.debug(LogCategory.NETWORK, "RestRpcClient.send: Response status=${response.status}, url=$targetUrl")
        } catch (err : CancellationException) {
            throw err
        } catch (err : Throwable) {
            Logger.error(LogCategory.NETWORK, "RestRpcClient.send: Failed to send to $targetUrl - ${err.message}")
            logger(err)
            throw err
        }
    }

    /**
     * Closes the SSE channel, drains every in-flight RPC, then releases the
     * HTTP client and coroutine scope.
     *
     * Pending requests are completed with a [RestRpcClientClosedException]
     * so awaiting callers fail fast instead of hanging on a closed SSE
     * channel. Each dropped request id + method is logged as a `WARN`
     * breadcrumb for the next time a transport is replaced mid-flight.
     */
    /**
     * Suspends until every in-flight request has received its response
     * (or failed) — up to [timeoutMillis]. Used by
     * [org.ttt.autogenesis.kvisionapp.RestRpcBridge.withTemporaryConnection]
     * before [close] to avoid dropping a request whose SSE response
     * is still in transit. Suspending so the event loop can run
     * other coroutines (the SSE handler, etc.) while we wait.
     *
     * @param timeoutMillis Maximum time to wait. Default 2s — well above
     *   p99 SSE delivery on localhost and tight enough to surface
     *   a wedged SSE channel in the same session.
     * @return True if all requests drained, false on timeout.
     */
    suspend fun awaitInFlightRequests(timeoutMillis: Long = 2_000L): Boolean
    {
        val startMark = TimeSource.Monotonic.markNow()
        val timeoutMs = timeoutMillis  // capture as Long for comparison
        while (startMark.elapsedNow().inWholeMilliseconds < timeoutMs)
        {
            if (invoker.pendingCount() == 0) return true
            kotlinx.coroutines.yield()
        }
        return invoker.pendingCount() == 0
    }

    suspend fun close()
    {
        disconnect()
        drainInFlightRequests()
        if (ownsClient) {
            runCatching { httpClient.close() }
        }
        if (ownsScope) {
            internalScope.cancel()
        }
    }

    /**
     * Completes every in-flight request with a [RestRpcClientClosedException].
     *
     * The grace window from [RestRpcClientConfig.drainTimeoutMillis] gives a
     * request whose HTTP POST has already been sent a small chance to land
     * its response before the SSE channel is gone. Most importantly, the
     * drop is *visible* to the caller rather than a permanent hang.
     */
    private suspend fun drainInFlightRequests()
    {
        val graceMillis = config.drainTimeoutMillis
        if(graceMillis > 0L)
        {
            withTimeoutOrNull(graceMillis) {
                // Give the HTTP POST + response a brief window to land naturally
                // before we forcibly fail the deferreds. The Invoker's `pending`
                // map is the source of truth; we just yield to let `handle`
                // complete any response already in flight.
                yield()
            }
        }
        val cause = RestRpcClientClosedException(
            "RestRpcClient closed before response (playerId=${config.playerId})"
        )
        val dropped = invoker.cancelAll(cause)
        for((id, method) in dropped)
        {
            Logger.warn(
                LogCategory.NETWORK,
                "RestRpcClient.close: dropped in-flight request id=$id method=$method playerId=${config.playerId}"
            )
        }
    }

    suspend fun waitForConnection(timeoutMillis: Long = 30_000): Boolean {
        return withTimeoutOrNull(timeoutMillis) {
            connectionEstablishedDeferred.await()
            true
        } ?: false
    }

    private suspend fun processEventLines(lines : List<String>)
    {
        if (lines.isEmpty()) return
        val payload = lines.asSequence()
            .mapNotNull { line ->
                if (line.startsWith("data:")) {
                    line.removePrefix("data:").trimStart()
                } else {
                    null
                }
            }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
            ?: return

        logDebug("Received SSE payload: $payload")

        val message = try {
            payload.toRpcMessage(RpcJson)
        } catch (err : Throwable) {
            logWarn("Failed to parse SSE payload as RPC message: ${err.message}")
            reportError(err)
            return
        }

        if (message is RpcMessage.Notification && message.method == "session.ready") {
            sessionReady = true
            logInfo("Session ready notification received")
            return
        }

        try {
            messageHandler.handle(message)
        } catch (err : CancellationException) {
            throw err
        } catch (err : Throwable) {
            logger(err)
        }
    }

    private fun createClient() : HttpClient =
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 180_000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 180_000
            }
        }

    // TDD seam: visibility widened from `private` to `internal` so the
    // wire-level contract test in `RestRpcClientConfigUrlTest` (commonTest)
    // can assert directly on the URL string. Behaviour unchanged; the
    // function is still an implementation detail of the class and not
    // intended for production call sites.
    internal fun buildEndpointUrl(path : String) : String
    {
        val builder = URLBuilder(config.baseUrl)
        builder.appendPathSegments(path)
        builder.parameters.append("playerId", config.playerId)
        if (config.isGuestMode) {
            builder.parameters.append("guestMode", "true")
        }
        if (config.origin == RpcOrigin.GAME_SERVER) {
            // GAME_CLIENT is the default and is omitted from the wire so
            // existing clients and proxies are unaffected.
            builder.parameters.append("origin", "server")
        }
        if (!config.accelbyteId.isNullOrBlank()) {
            builder.parameters.append("accelbyteId", config.accelbyteId)
        }
        return builder.buildString()
    }

    private fun URLBuilder.appendPathSegments(path : String)
    {
        val normalized = path.trim().trimStart('/').trimEnd('/')
        if (normalized.isEmpty()) return
        val segments = normalized.split('/').mapNotNull { segment ->
            val trimmed = segment.trim()
            trimmed.takeIf { it.isNotEmpty() }
        }
        if (segments.isNotEmpty()) {
            encodedPathSegments += segments
        }
    }

    private fun reportError(err : Throwable)
    {
        logger(err)
    }

    private fun evaluateReconnectPolicy(err : Throwable)
    {
        val now = TimeSource.Monotonic.markNow()
        val elapsedMillis = lastSseErrorMark.elapsedNow().inWholeMilliseconds
        consecutiveErrors = if (elapsedMillis <= consecutiveErrorWindowMs) consecutiveErrors + 1 else 1
        lastSseErrorMark = now

        if (isCorsRelatedError(err))
        {
            sseErrorStreak++
            if (!corsReconnectSuppressed && sseErrorStreak >= 3 && keepReconnecting)
            {
                suspendReconnectLoop("CORS block detected")
            }
        }
        else
        {
            sseErrorStreak = 0
            corsReconnectSuppressed = false
        }

        if (consecutiveErrors >= 6 && keepReconnecting)
        {
            suspendReconnectLoop("consecutive SSE failures")
        }
    }

    private fun isCorsRelatedError(err : Throwable) : Boolean
    {
        val message = err.message ?: return false
        return message.contains("access-control-allow-origin", ignoreCase = true)
            || message.contains("cross-origin", ignoreCase = true)
            || message.contains("cors", ignoreCase = true)
            || message.contains("NetworkError when attempting to fetch resource", ignoreCase = true)
            || message.contains("Failed to fetch", ignoreCase = true)
    }

    private fun suspendReconnectLoop(reason : String)
    {
        keepReconnecting = false
        corsReconnectSuppressed = true
        cancelReconnectJob()
        logWarn("SSE reconnect suppressed: $reason")
    }

    private fun logDebug(message : String)
    {
        if (suppressSseLogs) return
        Logger.debug(LogCategory.NETWORK, message)
    }

    private fun logInfo(message : String)
    {
        if (suppressSseLogs) return
        Logger.info(LogCategory.NETWORK, message)
    }

    private fun logWarn(message : String)
    {
        if (suppressSseLogs) return
        Logger.warn(LogCategory.NETWORK, message)
    }

    private fun logError(message : String)
    {
        val elapsedMillis = lastErrorLogMark.elapsedNow().inWholeMilliseconds
        if (elapsedMillis >= MIN_ERROR_LOG_INTERVAL_MS) {
            if (!suppressSseLogs)
            {
                Logger.error(LogCategory.NETWORK, message)
            }
            lastErrorLogMark = TimeSource.Monotonic.markNow()
        }
        // Silently suppress throttled errors - no logging
    }
}
