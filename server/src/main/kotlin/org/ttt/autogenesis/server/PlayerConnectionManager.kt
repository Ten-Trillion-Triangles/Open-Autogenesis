package org.ttt.autogenesis.server

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import kotlinx.coroutines.CompletableDeferred
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.LogPriority
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Role of a player session in the game lifecycle.
 *
 * - [PRIMARY]: Browser connection — game shuts down if all PRIMARYs disconnect.
 * - [CONTROLLER]: Python/AI debugger connection — game continues if PRIMARYs disconnect
 *   but at least one CONTROLLER remains.
 */
internal enum class SessionRole
{
    /** Browser client — game terminates if all PRIMARY sessions disconnect. */
    PRIMARY,
    /** Python/AI controller — game survives without any PRIMARY connections. */
    CONTROLLER
}

/**
 * Represents a connected player session with WebSocket and RPC capabilities.
 * Uses an asynchronous sending queue to prevent network backpressure from blocking the server.
 *
 * @param playerId Unique identifier for the player
 * @param session WebSocket session for communication
 * @param invoker RPC invoker for making calls to the client
 * @param role Session role: PRIMARY (browser) or CONTROLLER (Python). Defaults to PRIMARY.
 * @param accelbyteId AccelByte user id backing this WebSocket session, when known.
 * Empty when the session is anonymous (e.g. a CONTROLLER connection, or a
 * pre-auth handshake). Carried on the session so RPC handlers can resolve
 * the human user id before [gameState.WorldManager.playerStats] is populated
 * (e.g. on a fresh dedicated server before the matchmaker injects the
 * human player's stats).
 */
internal class PlayerSession(
    val playerId: String,
    val session: DefaultWebSocketServerSession,
    val invoker: RpcInvoker,
    val role: SessionRole = SessionRole.PRIMARY,
    val accelbyteId: String = ""
)
{
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sendChannel = Channel<Frame>(capacity = 64)
    private var sendJob: Job? = null
    private val assembler = MultipartAssembler()

    init {
        startSending()
    }

    private fun startSending()
    {
        sendJob = scope.launch {
            try {
                for(frame in sendChannel)
                {
                    try {
                        session.send(frame)
                    }
                    catch(e: Throwable)
                    {
                        Logger.warn(LogCategory.NETWORK, "Failed to send frame to $playerId: ${e.message}")
                    }
                }
            }
            catch(e: Throwable)
            {
                // CancellationException is the normal way the send loop terminates
// when the client disconnects (the supervisor cancels the loop);
// only escalate to ERROR for actual unexpected failures.
val level = if (e is kotlinx.coroutines.CancellationException) LogPriority.DEBUG else LogPriority.ERROR
Logger.log(level, LogCategory.NETWORK, "Sending loop ended for $playerId: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /**
     * Queues an RPC message to be sent to the client.
     * Transparently chunks large messages into multiple frames.
     */
    suspend fun sendRpcMessage(message: RpcMessage)
    {
        try {
            val json = message.toJson(RpcJson)
            val bytes = json.encodeToByteArray()

            if (bytes.size < 60000) {
                sendChannel.send(Frame.Text(json))
            } else {
                val messageId = java.util.UUID.randomUUID().toString()
                val chunks = json.chunked(30000)
                val totalChunks = chunks.size

                Logger.info(LogCategory.NETWORK, "Chunking large RPC message (${bytes.size} bytes) into $totalChunks chunks for $playerId")

                chunks.forEachIndexed { index, data ->
                    val multipart = RpcMessage.Multipart(
                        messageId = messageId,
                        chunkIndex = index,
                        totalChunks = totalChunks,
                        data = data
                    )
                    sendChannel.send(Frame.Text(multipart.toJson(RpcJson)))
                }
            }
        }
        catch(e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "Failed to queue RPC message for $playerId: ${e.message}")
        }
    }

    /**
     * Handles an incoming raw frame, reassembling if it's a multipart message.
     * @return Reassembled JSON string if complete, or null if buffering.
     */
    fun handleIncomingFrame(raw: String): String?
    {
        if(raw.contains("\"type\":\"multipart\"")) {
            try {
                val multipart = raw.toRpcMessage(RpcJson) as? RpcMessage.Multipart
                if (multipart != null) {
                    return assembler.addChunk(multipart)
                }
            } catch (e: Throwable) {
                Logger.error(LogCategory.NETWORK, "Failed to decode multipart chunk for $playerId: ${e.message}")
            }
            return null
        }
        return raw
    }

    /**
     * Queues a raw string payload to be sent as a text frame.
     */
    suspend fun sendRaw(payload: String)
    {
        try {
            sendChannel.send(Frame.Text(payload))
        }
        catch(e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "Failed to queue raw message for $playerId: ${e.message}")
        }
    }

    /**
     * Cleans up resources when the session is closed.
     */
    fun close()
    {
        sendChannel.close()
        sendJob?.cancel()
    }
}

/**
 * Result of registering a player connection.
 *
 * @param session The player session that was registered
 * @param outcome Whether this was a new connection or reconnection
 */
internal data class ConnectionRegistration(
    val session: PlayerSession,
    val outcome: ConnectionOutcome
)

/**
 * Outcome of a connection registration attempt.
 */
internal sealed class ConnectionOutcome
{
    /** New player connection */
    object New : ConnectionOutcome()

    /** Player reconnected, replacing previous session */
    data class Reconnected(val previousSession: PlayerSession) : ConnectionOutcome()
}

/**
 * Events related to player connection lifecycle.
 */
internal sealed interface PlayerLifecycleEvent
{
    /** Player connected for the first time */
    data class Connected(val session: PlayerSession) : PlayerLifecycleEvent

    /** Player reconnected, replacing previous session */
    data class Reconnected(val session: PlayerSession, val previousSession: PlayerSession) : PlayerLifecycleEvent

    /** Player disconnected */
    data class Disconnected(val playerId: String) : PlayerLifecycleEvent
}

/**
 * Manages player WebSocket connections and provides RPC communication capabilities.
 *
 * Supports multiple concurrent sessions per playerId — enabling PRIMARY (browser) and
 * CONTROLLER (Python debugger) connections to coexist for the same logical player.
 * Broadcasts go to ALL sessions regardless of role.
 *
 * Shutdown behavior: if all PRIMARY sessions for a player disconnect, the game enters
 * shutdown countdown. CONTROLLER sessions alone do NOT trigger shutdown.
 */
internal class PlayerConnectionManager
{
    /**
     * Maps playerId → list of active sessions for that player.
     * Multiple sessions arise when PRIMARY (browser) and CONTROLLER (Python) connections
     * coexist for the same logical player.
     */
    private val sessions = ConcurrentHashMap<String, MutableList<PlayerSession>>()

    private val mutex = Mutex()
    private val _lifecycleEvents = MutableSharedFlow<PlayerLifecycleEvent>(extraBufferCapacity = 8)

    /** Flow of player lifecycle events */
    val lifecycleEvents: SharedFlow<PlayerLifecycleEvent> = _lifecycleEvents

    /**
     * Registers a new player connection.
     *
     * Unlike the previous single-session implementation, this ALWAYS appends to the
     * session list for the playerId. Both PRIMARY (browser) and CONTROLLER (Python)
     * connections are retained simultaneously.
     *
     * @param playerId Unique identifier for the player
     * @param session WebSocket session for the player
     * @param role Session role (PRIMARY or CONTROLLER). Defaults to PRIMARY.
     * @param accelbyteId AccelByte user id from the WebSocket query string, if
     * known. Stored on the resulting [PlayerSession] so RPC handlers can
     * resolve the human user before player stats are populated. Default
     * `null` (treated as empty) preserves backward compatibility with
     * call-sites that pre-date the resume-flow plumbing.
     * @return Registration result with session and outcome
     */
    suspend fun register(
        playerId: String,
        session: DefaultWebSocketServerSession,
        role: SessionRole = SessionRole.PRIMARY,
        accelbyteId: String? = null
    ): ConnectionRegistration
    {
        Logger.debug(LogCategory.NETWORK, "PlayerConnectionManager: register() entry for playerId=$playerId, role=$role")

        var tempSessionRef: PlayerSession? = null
        val invokerSender: suspend (RpcMessage) -> Unit = { msg ->
            val activeSession = tempSessionRef
            if (activeSession != null) {
                activeSession.sendRpcMessage(msg)
            } else {
                Logger.error(LogCategory.NETWORK, "Attempted to send RPC before session init for $playerId")
            }
        }

        val invoker = RpcInvoker(
            sender = invokerSender,
            telemetry = RpcTelemetrySink.NoOp
        )

        val resolvedAccelbyteId = accelbyteId.orEmpty()
        val playerSession = PlayerSession(playerId, session, invoker, role, resolvedAccelbyteId)
        tempSessionRef = playerSession

        val previousSize = mutex.withLock {
            val list = sessions.getOrPut(playerId) { mutableListOf() }
            val wasEmpty = list.isEmpty()
            list.add(playerSession)
            list.size
        }

        val outcome = if (previousSize == 1) {
            // First session for this playerId
            Logger.debug(LogCategory.NETWORK, "PlayerConnectionManager: New session for $playerId (first connection)")
            _lifecycleEvents.emit(PlayerLifecycleEvent.Connected(playerSession))
            ConnectionOutcome.New
        } else {
            // Additional session (e.g., controller joining browser's session)
            Logger.debug(LogCategory.NETWORK, "PlayerConnectionManager: Additional session for $playerId (total=$previousSize), role=$role")
            _lifecycleEvents.emit(PlayerLifecycleEvent.Reconnected(playerSession, playerSession))
            ConnectionOutcome.Reconnected(playerSession)
        }
        Logger.info(LogCategory.NETWORK, "Player $playerId registered as $role (sessionCount=$previousSize, outcome=${outcome::class.simpleName})")
        return ConnectionRegistration(playerSession, outcome)
    }

    /**
     * Deregisters a specific session.
     *
     * Removes only the specified session from the list for playerId.
     * If the list becomes empty, the playerId entry is removed entirely.
     *
     * @param session The session to deregister
     */
    suspend fun deregister(session: PlayerSession)
    {
        val removed = mutex.withLock {
            val list = sessions[session.playerId]
            if (list != null) {
                val removedSession = list.find { it === session }
                if (removedSession != null) {
                    list.remove(removedSession)
                    if (list.isEmpty()) {
                        sessions.remove(session.playerId)
                    }
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        if (removed) {
            session.close()
            _lifecycleEvents.emit(PlayerLifecycleEvent.Disconnected(session.playerId))
            Logger.info(LogCategory.NETWORK, "Player session deregistered: ${session.playerId} (role=${session.role})")
        } else {
            Logger.warn(LogCategory.NETWORK, "Deregister attempt for unknown session: ${session.playerId}")
        }
    }

    /**
     * Broadcasts an RPC message to ALL connected sessions across all playerIds.
     *
     * @param message RPC message to broadcast
     */
    suspend fun broadcast(message: RpcMessage)
    {
        val targets = mutex.withLock { sessions.values.flatten() }
        Logger.debug(LogCategory.NETWORK, "Broadcasting message ${message.javaClass.simpleName} to ${targets.size} sessions")
        for (target in targets) {
            target.sendRpcMessage(message)
        }
    }

    /**
     * Broadcasts a connection event to all players.
     *
     * @param playerId Player whose connection status changed
     * @param status New connection status
     */
    suspend fun broadcastConnectionEvent(playerId: String, status: ConnectionStatus)
    {
        val event = PlayerConnectionEvent(playerId, status, System.currentTimeMillis())
        Logger.debug(LogCategory.NETWORK, "Broadcasting connection event for $playerId -> $status")
        broadcast(RpcMessage.ConnectionState(event))
    }

    /**
     * Finds the FIRST session for a playerId.
     *
     * In multi-session scenarios (PRIMARY + CONTROLLER), this returns whichever
     * session was registered first.
     */
    suspend fun findSession(playerId: String): PlayerSession? =
        mutex.withLock { sessions[playerId]?.firstOrNull() }

    /**
     * Finds ALL sessions for a playerId.
     *
     * Use this when you need to send to all connections of a single player
     * (e.g., both browser PRIMARY and Python CONTROLLER).
     */
    suspend fun findAllSessions(playerId: String): List<PlayerSession> =
        mutex.withLock { sessions[playerId]?.toList() ?: emptyList() }

    /**
     * Finds ALL sessions whose [PlayerSession.accelbyteId] matches the supplied id.
     *
     * Use this when a server-extend push arrives carrying only the AccelByte user
     * id (no WS playerId), or when the snapshot's `playerID` is a stale id that
     * no longer matches the live WS session's `playerId`. Scans every active
     * session under the existing mutex; O(N) over active sessions is fine because
     * `N` is the number of currently-connected WS clients (bounded by the game's
     * max-player count, typically ≤ 8).
     *
     * The accelbyteId is set at [register] time from the WebSocket query string
     * and remains stable for the lifetime of the session, so this lookup is
     * safe to invoke at any time after the WS handshake completes.
     *
     * @param accelbyteId The AccelByte user id to match (blank returns empty).
     * @return All matching [PlayerSession]s, or empty list if none.
     */
    suspend fun findAllSessionsByAccelbyteId(accelbyteId: String): List<PlayerSession>
    {
        if (accelbyteId.isBlank()) return emptyList()
        return mutex.withLock {
            sessions.values.flatten().filter { it.accelbyteId == accelbyteId }
        }
    }

    /** Returns all active player sessions */
    suspend fun allSessions(): List<PlayerSession> =
        mutex.withLock { sessions.values.flatten() }

    /**
     * Pings a client to verify network reachability.
     *
     * @param playerId Target player ID
     * @param timeoutMillis Timeout for the ping response
     * @return True if the client responded within the timeout, false otherwise
     */
    suspend fun ping(playerId: String, timeoutMillis: Long = 2000L): Boolean
    {
        val session = findSession(playerId) ?: return false
        return try {
            val response = callClient(playerId, "client.ping", timeoutMillis = timeoutMillis)
            response != null && response.error == null
        } catch (e: Throwable) {
            Logger.debug(LogCategory.NETWORK, "Ping to $playerId failed: ${e.message}")
            false
        }
    }

    /**
     * Makes an RPC call to a specific client.
     *
     * @param playerId Target player ID
     * @param method RPC method name
     * @param params Optional parameters
     * @param timeoutMillis Optional timeout in milliseconds
     * @return RPC response or null if player not found
     */
    suspend fun callClient(
        playerId: String,
        method: String,
        params: JsonElement? = null,
        timeoutMillis: Long? = null
    ): RpcMessage.Response?
    {
        val session = findSession(playerId)
        if(session == null)
        {
            Logger.warn(LogCategory.NETWORK, "callClient aborted: player $playerId not connected")
            return null
        }
        val handle = session.invoker.request(method, params, timeoutMillis)
        return handle.await()
    }

    /**
     * Starts a streaming RPC call toward a connected client.
     *
     * @param playerId Target player ID
     * @param method RPC method name
     * @param params Optional payload
     * @param timeoutMillis Timeout for the response/stream
     * @param bufferCapacity Buffer for incoming stream chunks
     * @return Handle that exposes notifications and eventual response, or null if player is missing
     */
    suspend fun callClientStream(
        playerId: String,
        method: String,
        params: JsonElement? = null,
        timeoutMillis: Long? = null,
        bufferCapacity: Int = 8
    ): RpcRequestHandle?
    {
        val session = findSession(playerId) ?: return null
        return session.invoker.stream(method, params, timeoutMillis, bufferCapacity)
    }

    /**
     * Starts a typed streaming RPC call toward a client.
     *
     * @param playerId Target player ID
     * @param method RPC method name
     * @param params Typed payload
     * @param timeoutMillis Timeout for the response/stream
     * @param bufferCapacity Buffer for incoming stream chunks
     * @return Handle that exposes notifications and eventual response, or null if player is missing
     */
    suspend inline fun <reified P> callClientStream(
        playerId: String,
        method: String,
        params: P,
        timeoutMillis: Long? = null,
        bufferCapacity: Int = 8
    ): RpcRequestHandle?
    {
        val element = RpcJson.encodeToJsonElement(serializer(), params)
        return callClientStream(
            playerId = playerId,
            method = method,
            params = element,
            timeoutMillis = timeoutMillis,
            bufferCapacity = bufferCapacity
        )
    }

    /**
     * Broadcasts an RPC call to all connected clients.
     *
     * @param method RPC method name
     * @param params Optional parameters
     * @param timeoutMillis Optional timeout in milliseconds
     * @return Map of player IDs to their response results
     */
    suspend fun broadcastClients(
        method: String,
        params: JsonElement? = null,
        timeoutMillis: Long? = null
    ): Map<String, Result<RpcMessage.Response>>
    {
        val sessionsToNotify = allSessions()
        Logger.debug(LogCategory.NETWORK, "Broadcasting RPC $method to ${sessionsToNotify.size} connections")
        return sessionsToNotify.associate { session ->
            session.playerId to runCatching {
                session.invoker.request(method, params, timeoutMillis).await()
            }
        }
    }

    /**
     * Checks if any PRIMARY session exists for a given playerId.
     *
     * Used by shutdown logic to determine if the game should continue
     * when a session disconnects.
     *
     * @param playerId Player ID to check
     * @return True if at least one PRIMARY session exists
     */
    suspend fun hasPrimarySession(playerId: String): Boolean =
        mutex.withLock { sessions[playerId]?.any { it.role == SessionRole.PRIMARY } == true }

    /**
     * Returns true if any PRIMARY session is connected for a player that is
     * part of the current game (i.e. has an entry in [WorldManager.playerStats]).
     *
     * Used by the single-player shutdown logic to determine if the game
     * should continue when a session disconnects. Only sessions whose
     * playerId matches an entry in [WorldManager.playerStats] count — a
     * stray browser tab sitting on the MainMenu (with no active game) does
     * NOT block the running-game snapshot write.
     */
    suspend fun hasAnyPrimarySession(): Boolean =
        mutex.withLock {
            val gamePlayerIds = gameState.WorldManager.playerStats
                .map { it.playerID }
                .toSet()
            sessions.values.any { list ->
                list.any { session ->
                    session.role == SessionRole.PRIMARY &&
                        session.playerId in gamePlayerIds
                }
            }
        }

    /**
     * Checks if any CONTROLLER session exists for a given playerId.
     *
     * @param playerId Player ID to check
     * @return True if at least one CONTROLLER session exists
     */
    suspend fun hasControllerSession(playerId: String): Boolean =
        mutex.withLock { sessions[playerId]?.any { it.role == SessionRole.CONTROLLER } == true }

    /**
     * Sends an AudioQueryState to a specific client and waits for AudioReportState.
     *
     * Used to query a client's audio engine state (e.g., on reconnect/late-join).
     *
     * @param connectionId Target player connection ID
     * @param queryId Unique identifier for this query (used to match response)
     * @return The client's reported AudioReportState, or null if no response within timeout
     */
    suspend fun queryClientAudioState(connectionId: String, queryId: String): org.ttt.autogenesis.audio.AudioReportState?
    {
        val session = findSession(connectionId)
        if (session == null)
        {
            Logger.warn(LogCategory.NETWORK, "queryClientAudioState: connection '$connectionId' not found")
            return null
        }

        val queryPayload = org.ttt.autogenesis.audio.AudioQueryState(queryId = queryId)
        val jsonPayload = RpcJson.encodeToJsonElement(serializer<org.ttt.autogenesis.audio.AudioQueryState>(), queryPayload)

        val handle = session.invoker.stream(
            method = "audio.queryState",
            params = jsonPayload,
            timeoutMillis = 5000L,
            bufferCapacity = 1
        )

        if (handle == null)
        {
            Logger.warn(LogCategory.NETWORK, "queryClientAudioState: failed to start stream for connectionId=$connectionId")
            return null
        }

        // Wait for the single response notification from the client
        try {
            val response = handle.await()
            if (response != null && response.error == null)
            {
                val result = response.result
                if (result != null)
                {
                    return RpcJson.decodeFromJsonElement(serializer<org.ttt.autogenesis.audio.AudioReportState>(), result)
                }
            }
            Logger.warn(LogCategory.NETWORK, "queryClientAudioState: invalid response for queryId=$queryId")
        }
        catch (e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "queryClientAudioState: timeout or error for queryId=$queryId: ${e.message}")
        }

        return null
    }
}
