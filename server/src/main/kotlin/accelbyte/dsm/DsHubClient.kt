package accelbyte.dsm

import accelbyte.AccelByteSdkProvider
import accelbyte.session.ServerClaimedEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.server.config.AccelByteConfig
import java.net.http.HttpClient
import java.net.URI
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * AccelByte DS Hub WebSocket client using Java's built-in WebSocket API (Java 11+).
 *
 * The DS Hub is the WebSocket channel through which AMS notifies a dedicated server
 * that it has been assigned to a game session. The server connects on startup using
 * its DS ID (AB_DS_ID) and listens for the "serverClaimed" topic message.
 *
 * Message format (per AccelByte docs):
 * {
 *   "MessageID": "",
 *   "Code": 200,
 *   "topic": "serverClaimed",
 *   "payload": {
 *     "sessionId": "...",
 *     "gameMode": "...",
 *     "matchingAllies": ["userId1", "userId2"]
 *   }
 * }
 *
 * Connection is authenticated via Bearer token from the AccelByte SDK's token repository.
 */
object DsHubClient
{
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _onServerClaimed = MutableSharedFlow<ServerClaimedEvent>(replay = 0, extraBufferCapacity = 64)
    /** Flow of ServerClaimedEvent emitted when the DS is assigned to a session. */
    val onServerClaimed: Flow<ServerClaimedEvent> = _onServerClaimed.asSharedFlow()

    private val _onDrainSignal = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 64)
    /** Flow emitted when DS Hub sends a drain signal. */
    val onDrainSignal: Flow<Unit> = _onDrainSignal.asSharedFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var _isConnected = false
    /** Whether the DS Hub WebSocket is currently connected. */
    val isConnected: Boolean get() = _isConnected

    private var heartbeatJob: Job? = null
    private var reconnectAttempt = 0
    private val maxReconnectAttempts = 5
    private val baseReconnectDelayMs = 1_000L
    private var currentServerId: String? = null

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .build()
    }

    /**
     * Initiates a WebSocket connection to the DS Hub.
     * Logs a warning and skips if AB_DS_HUB_URL is not configured (dev mode).
     *
     * @param serverId The DS ID assigned by AMS (from AB_DS_ID env var)
     */
    fun connect(serverId: String)
    {
        if (_isConnected)
        {
            Logger.warn(LogCategory.NETWORK, "DsHubClient: Already connected, ignoring connect request")
            return
        }

        val hubUrl = AccelByteConfig.getDsHubUrl()
        if (hubUrl.isBlank())
        {
            Logger.warn(LogCategory.NETWORK, "DsHubClient: AB_DS_HUB_URL not configured — skipping DS Hub connection (dev mode)")
            return
        }

        currentServerId = serverId
        scope.launch {
            doConnect(hubUrl, serverId)
        }
    }

    private suspend fun doConnect(hubUrl: String, serverId: String)
    {
        try
        {
            Logger.info(LogCategory.NETWORK, "DsHubClient: Connecting to DS Hub at $hubUrl with serverId=$serverId")

            // Build the WebSocket URI — DS Hub URL is typically wss://<host>/dshub
            val wsUri = buildWsUri(hubUrl)
            Logger.debug(LogCategory.NETWORK, "DsHubClient: WS URI = $wsUri")

            // Get the auth token from the SDK's token repository
            // If token not available, trigger login to acquire one before connecting
            val token = AccelByteSdkProvider.sdk.sdkConfiguration.tokenRepository.let { repo ->
                try {
                    if (repo.isTokenAvailable()) repo.token as? String else null
                } catch (_: Throwable) {
                    null
                }
            } ?: run {
                // Token unavailable — attempt login to refresh credentials
                Logger.warn(LogCategory.NETWORK, "DsHubClient: Token not available, attempting login...")
                AccelByteSdkProvider.sdk.loginClient()
                AccelByteSdkProvider.sdk.sdkConfiguration.tokenRepository.let { repo ->
                    try {
                        repo.token as? String
                    } catch (_: Throwable) {
                        null
                    }
                }
            } ?: ""

            // Build the WebSocket with proper Listener via HttpClient builder
            val textBuilder = StringBuilder()
            val wsFuture: CompletableFuture<WebSocket> = httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer $token")
                .header("X-DsId", serverId)
                .header("Sec-WebSocket-Protocol", "DSHub")
                .buildAsync(wsUri, object : WebSocket.Listener {
                    override fun onOpen(ws: WebSocket)
                    {
                        Logger.info(LogCategory.NETWORK, "DsHubClient: WebSocket opened")
                        _isConnected = true
                    }

                    override fun onText(ws: WebSocket, data: CharSequence, isLast: Boolean): CompletionStage<*>?
                    {
                        textBuilder.append(data)
                        if (isLast)
                        {
                            val message = textBuilder.toString()
                            textBuilder.clear()
                            handleMessage(message)
                        }
                        return null
                    }

                    override fun onClose(ws: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>?
                    {
                        Logger.warn(LogCategory.NETWORK, "DsHubClient: WebSocket closed code=$statusCode reason=$reason")
                        _isConnected = false
                        webSocket = null
                        scheduleReconnect()
                        return null
                    }

                    override fun onError(ws: WebSocket, error: Throwable)
                    {
                        Logger.error(LogCategory.NETWORK, "DsHubClient: WebSocket error: ${error.message}")
                        _isConnected = false
                        scheduleReconnect()
                    }
                })

            webSocket = wsFuture.join()
            reconnectAttempt = 0
            Logger.info(LogCategory.NETWORK, "DsHubClient: WebSocket created, waiting for onOpen...")

            // Start heartbeat
            startHeartbeat()
        }
        catch (err: Throwable)
        {
            Logger.error(LogCategory.NETWORK, "DsHubClient: Failed to connect to DS Hub: ${err.message}")
            _isConnected = false
            scheduleReconnect()
        }
    }

    private fun buildWsUri(hubUrl: String): URI
    {
        // DS Hub URL is typically wss://<host>/dshub
        val base = hubUrl.trimEnd('/')
        val urlWithPath = if (base.contains("/dshub")) base else "$base/dshub"
        return if (urlWithPath.startsWith("ws://") || urlWithPath.startsWith("wss://")) {
            URI.create(urlWithPath)
        } else {
            // Assume HTTPS/WSS if not specified
            URI.create("wss://$urlWithPath")
        }
    }

    /**
     * Disconnects from the DS Hub cleanly.
     */
    fun disconnect()
    {
        Logger.info(LogCategory.NETWORK, "DsHubClient: Disconnecting from DS Hub")
        heartbeatJob?.cancel()
        heartbeatJob = null
        try
        {
            webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "Server shutting down")
        }
        catch (err: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "DsHubClient: Error during disconnect: ${err.message}")
        }
        finally
        {
            _isConnected = false
            webSocket = null
        }
    }

    /**
     * Parses an incoming DS Hub JSON message and emits the appropriate event.
     *
     * Expected message format:
     * {
     *   "MessageID": "...",
     *   "Code": 200,
     *   "topic": "serverClaimed",
     *   "payload": { "sessionId": "...", "gameMode": "...", "matchingAllies": [...] }
     * }
     */
    private fun handleMessage(raw: String)
    {
        Logger.debug(LogCategory.NETWORK, "DsHubClient: Received message: ${raw.take(200)}")

        try
        {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            val jsonObj = json as? kotlinx.serialization.json.JsonObject
            if (jsonObj == null)
            {
                Logger.warn(LogCategory.NETWORK, "DsHubClient: Message is not a JSON object")
                return
            }

            val topic = (jsonObj["topic"] as? kotlinx.serialization.json.JsonPrimitive)?.content

            when (topic)
            {
                "serverClaimed", "MatchmakingV2ServerClaimed" ->
                {
                    val payload = jsonObj["payload"] as? kotlinx.serialization.json.JsonObject
                    if (payload != null)
                    {
                        val sessionId = (payload["sessionId"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?: (payload["session_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?: ""
                        val gameMode = (payload["gameMode"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?: (payload["game_mode"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?: ""
                        val matchingAlliesArray = payload["matchingAllies"] as? kotlinx.serialization.json.JsonArray
                        val matchingAllies = matchingAlliesArray?.mapNotNull {
                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                        } ?: emptyList()

                        val event = ServerClaimedEvent(
                            sessionId = sessionId,
                            gameMode = gameMode,
                            matchingAllies = matchingAllies
                        )

                        Logger.info(
                            LogCategory.NETWORK,
                            "DsHubClient: Server claimed — sessionId=$sessionId, gameMode=$gameMode, players=${matchingAllies.size}"
                        )

                        scope.launch {
                            _onServerClaimed.emit(event)
                        }
                    }
                    return
                }
                "DSHUB_CONNECTED" ->
                {
                    val payload = jsonObj["payload"] as? kotlinx.serialization.json.JsonObject
                    Logger.info(LogCategory.NETWORK, "DsHubClient: DS Hub acknowledged connection payload=${payload ?: "none"}")
                    return
                }
                "ServerDrain", "DRAIN_SIGNAL", "drain" ->
                {
                    Logger.info(LogCategory.SYSTEM, "DsHubClient: Drain signal received")
                    scope.launch {
                        _onDrainSignal.emit(Unit)
                    }
                    return
                }
                else ->
                {
                    Logger.warn(LogCategory.NETWORK, "DsHubClient: Unknown topic received: $topic")
                }
            }
        }
        catch (err: Exception)
        {
            Logger.error(LogCategory.NETWORK, "DsHubClient: Failed to parse message: ${err.message}")
        }
    }

    private fun startHeartbeat()
    {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _isConnected)
            {
                delay(30_000L)
                if (_isConnected)
                {
                    try
                    {
                        webSocket?.sendPing(java.nio.ByteBuffer.wrap(ByteArray(0)))
                        Logger.debug(LogCategory.NETWORK, "DsHubClient: Ping sent")
                    }
                    catch (err: Exception)
                    {
                        Logger.warn(LogCategory.NETWORK, "DsHubClient: Ping failed: ${err.message}")
                        _isConnected = false
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    private fun scheduleReconnect()
    {
        val serverId = currentServerId ?: return

        if (reconnectAttempt >= maxReconnectAttempts)
        {
            Logger.error(LogCategory.NETWORK, "DsHubClient: Max reconnect attempts ($maxReconnectAttempts) reached — giving up")
            return
        }
        reconnectAttempt++
        val delayMs = baseReconnectDelayMs * (1 shl (reconnectAttempt - 1))  // 1s, 2s, 4s, 8s, 16s
        Logger.info(LogCategory.NETWORK, "DsHubClient: Scheduling reconnect attempt $reconnectAttempt in ${delayMs}ms")
        scope.launch {
            delay(delayMs)
            if (!_isConnected)
            {
                val hubUrl = AccelByteConfig.getDsHubUrl()
                if (hubUrl.isNotBlank())
                {
                    doConnect(hubUrl, serverId)
                }
            }
        }
    }
}