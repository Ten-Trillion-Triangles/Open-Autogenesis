package org.ttt.autogenesis.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

actual class WebSocketRpcClient actual constructor(
    private val config: WebSocketRpcClientConfig,
    rpcRegistry: RpcRegistry,
    coroutineScope: CoroutineScope?,
    metadata: Map<String, String>,
    private val logger: (Throwable) -> Unit
) {
    private val internalScope: CoroutineScope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ownsScope: Boolean = coroutineScope == null
    private val httpClient: HttpClient = createClient()
    private var connectionJob: Job? = null
    private var sessionReady: Boolean = false
    private var onConnectedCallback: (() -> Unit)? = null
    private var keepReconnecting: Boolean = true
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private val reconnectBaseDelayMillis = 1000L
    private val reconnectMaxDelayMillis = 10_000L
    private var session: DefaultClientWebSocketSession? = null
    private val invoker: RpcInvoker
    private val messageHandler: RpcMessageHandler
    private val assembler = MultipartAssembler()
    
    // Network error throttling to prevent log spam when server is unavailable
    private var lastErrorLogTime: Long = 0
    private val MIN_ERROR_LOG_INTERVAL_MS = 30_000L // Log errors at most once every 30 seconds

    init {
        invoker = RpcInvoker(sender = { send(it) })
        messageHandler = RpcMessageHandler(
            connectionId = config.playerId,
            rpcRegistry = rpcRegistry,
            invoker = invoker,
            sender = { send(it) },
            metadata = metadata
        )
    }

    actual val connectionId: String
        get() = config.playerId

    actual val rpcInvoker: RpcInvoker
        get() = invoker

    actual fun connect() {
        if (connectionJob?.isActive == true) return

        keepReconnecting = true
        cancelConnectionJob()
        cancelReconnectJob()
        connectionJob = internalScope.launch {
            try {
                logDebug("Starting WebSocket connection to ${config.buildWebSocketUrl()}")
                httpClient.webSocket(urlString = config.buildWebSocketUrl()) {
                    session = this
                    logDebug("WebSocket connection established")
                    reconnectAttempts = 0
                    sessionReady = false
                    onConnectedCallback?.invoke()

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val raw = frame.readText()
                                logDebug("Received WebSocket payload (length=${raw.length})")
                                
                                val completePayload = if (raw.contains("\"type\":\"multipart\"")) {
                                    try {
                                        val multipart = raw.toRpcMessage(RpcJson) as? RpcMessage.Multipart
                                        if (multipart != null) {
                                            assembler.addChunk(multipart)
                                        } else null
                                    } catch (e: Throwable) {
                                        logWarn("Failed to decode multipart chunk: ${e.message}")
                                        null
                                    }
                                } else raw

                                if (completePayload == null) continue

                                val message = try {
                                    completePayload.toRpcMessage(RpcJson)
                                } catch (err: Throwable) {
                                    logWarn("Failed to parse WebSocket payload as RPC message: ${err.message}")
                                    reportError(err)
                                    continue
                                }

                                if (message is RpcMessage.Notification && message.method == "session.ready") {
                                    sessionReady = true
                                    logInfo("Session ready notification received")
                                    continue
                                }

                                try {
                                    messageHandler.handle(message)
                                } catch (err: CancellationException) {
                                    throw err
                                } catch (err: Throwable) {
                                    logger(err)
                                }
                            }
                        }
                    } finally {
                        logDebug("WebSocket connection closed")
                        sessionReady = false
                        session = null
                    }
                }
            } catch (err: Throwable) {
                logError("WebSocket connection error: ${err.message ?: err::class.simpleName}")
                if (err is CancellationException) throw err
                reportError(err)
            } finally {
                logDebug("WebSocket job finished")
                sessionReady = false
                session = null
                connectionJob = null
                if (keepReconnecting) {
                    incrementReconnectAttempts()
                    scheduleReconnect()
                }
            }
        }
    }

    actual fun disconnect() {
        keepReconnecting = false
        sessionReady = false
        cancelConnectionJob()
        cancelReconnectJob()
    }

    actual fun isConnected(): Boolean =
        connectionJob?.isActive == true

    actual fun isSessionReady(): Boolean =
        sessionReady && isConnected()

    actual fun onConnected(callback: () -> Unit) {
        onConnectedCallback = callback
    }

    actual suspend fun close() {
        disconnect()
        try {
            httpClient.close()
        } catch (err: Throwable) {
            logger(err)
        }
        if (ownsScope) {
            internalScope.cancel()
        }
    }

    private suspend fun send(message: RpcMessage) {
        val json = message.toJson(RpcJson)
        val activeSession = session ?: throw IllegalStateException("WebSocket session missing for ${config.playerId}")
        try {
            if (json.length < 60000) {
                activeSession.send(Frame.Text(json))
            } else {
                val messageId = "${kotlinx.browser.window.performance.now()}_${(0..1000000).random()}"
                val chunks = json.chunked(30000)
                val totalChunks = chunks.size
                logInfo("Chunking large outgoing RPC message (${json.length} characters) into $totalChunks chunks")
                chunks.forEachIndexed { index, data ->
                    val multipart = RpcMessage.Multipart(
                        messageId = messageId,
                        chunkIndex = index,
                        totalChunks = totalChunks,
                        data = data
                    )
                    activeSession.send(Frame.Text(multipart.toJson(RpcJson)))
                }
            }
        } catch (err: CancellationException) {
            throw err
        } catch (err: Throwable) {
            logger(err)
            throw err
        }
    }

    private fun cancelConnectionJob() {
        connectionJob?.cancel()
        connectionJob = null
    }

    private fun cancelReconnectJob() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun scheduleReconnect() {
        if (!keepReconnecting) return
        if (reconnectJob?.isActive == true) return

        val delayMillis = nextReconnectDelayMillis()
        reconnectJob = internalScope.launch {
            logDebug("Reconnecting WebSocket in ${delayMillis}ms (attempt $reconnectAttempts)")
            delay(delayMillis)
            reconnectJob = null
            if (keepReconnecting && connectionJob?.isActive != true) {
                logDebug("Attempting WebSocket reconnect (attempt $reconnectAttempts)")
                connect()
            }
        }
    }

    private fun incrementReconnectAttempts() {
        reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(10)
    }

    private fun nextReconnectDelayMillis(): Long {
        val multiplier = reconnectAttempts.coerceAtLeast(1)
        return (reconnectBaseDelayMillis * multiplier).coerceAtMost(reconnectMaxDelayMillis)
    }

    private fun createClient(): HttpClient =
        HttpClient {
            install(WebSockets)
        }

    private fun reportError(err: Throwable) {
        logger(err)
    }

    private fun logDebug(message: String) {
        Logger.debug(LogCategory.NETWORK, message)
    }

    private fun logInfo(message: String) {
        Logger.info(LogCategory.NETWORK, message)
    }

    private fun logWarn(message: String) {
        Logger.warn(LogCategory.NETWORK, message)
    }

    private fun logError(message: String) {
        val currentTime = kotlinx.browser.window.performance.now().toLong()
        if (currentTime - lastErrorLogTime >= MIN_ERROR_LOG_INTERVAL_MS) {
            Logger.error(LogCategory.NETWORK, message)
            lastErrorLogTime = currentTime
        }
        // Silently suppress throttled errors - no logging
    }
}