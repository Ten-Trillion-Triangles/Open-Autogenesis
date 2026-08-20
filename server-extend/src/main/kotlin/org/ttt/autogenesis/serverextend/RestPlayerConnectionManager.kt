package org.ttt.autogenesis.serverextend

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.ConnectionStatus
import org.ttt.autogenesis.network.PlayerConnectionEvent
import network.UsageTrackingTelemetrySink
import org.ttt.autogenesis.network.RpcInvoker
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.network.RpcOrigin
import org.ttt.autogenesis.network.RpcRequestHandle
import org.ttt.autogenesis.network.RpcRegistry
import org.ttt.autogenesis.network.RpcTelemetrySink
import org.ttt.autogenesis.network.toJson
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents an active player session in the REST-based server.
 * 
 * Manages outgoing message flow and RPC invocation for a specific player connection.
 * Messages are queued in a buffered channel and streamed via Server-Sent Events.
 * 
 * @param playerId Unique identifier for this player session
 * @param outgoing Channel for queuing outbound messages to the client
 * @param invoker RPC invoker for making calls to the connected client
 */
internal data class RestPlayerSession(
    val playerId : String,
    private val outgoing : Channel<String>,
    val invoker : RpcInvoker,
    /**
     * Origin tag assigned at registration time. The per-session
     * [UsageTrackingTelemetrySink] reads this from the manager's
     * [RestPlayerConnectionManager.sessions] map to classify each
     * inbound Request as game-client or game-server traffic. See
     * [globals.RpcUsageTracker] for the downstream consumer.
     */
    val origin : RpcOrigin = RpcOrigin.GAME_CLIENT
)
{
    /**
     * Provides a flow of outgoing messages for streaming to the client.
     * 
     * @return [Flow] of JSON strings to be sent as Server-Sent Events
     */
    fun outgoingFlow() : Flow<String> = outgoing.receiveAsFlow()

    /**
     * Sends an RPC message to the connected client.
     * 
     * Serializes the message to JSON and queues it for delivery. Silently
     * handles channel closure if the client has disconnected.
     * 
     * @param message RPC message to send to the client
     */
    suspend fun sendRpcMessage(message : RpcMessage)
    {
        val payload = message.toJson(RpcJson)
        try {
            outgoing.send(payload)
        } catch (_: Throwable) {
            // Silently handle closed channel - client disconnected
        }
    }

    /**
     * Closes the outgoing message channel.
     * 
     * Should be called when the player session is terminated to clean up resources.
     */
    fun close()
    {
        outgoing.close()
    }
}

/**
 * Result of registering a new player connection.
 * 
 * Contains the created session and information about whether this was a new
 * connection or a reconnection that replaced an existing session.
 * 
 * @param session The active player session for this connection
 * @param outcome Details about the connection type (new vs reconnection)
 */
internal data class ConnectionRegistration(
    val session : RestPlayerSession,
    val outcome : ConnectionOutcome
)

/**
 * Describes the outcome of a player connection attempt.
 */
internal sealed class ConnectionOutcome
{
    /** New player connection with no previous session */
    object New : ConnectionOutcome()
    
    /**
     * Player reconnection that replaced an existing session.
     * 
     * @param previousSession The session that was replaced
     */
    data class Reconnected(val previousSession : RestPlayerSession) : ConnectionOutcome()
}

/**
 * Internal events for player connection lifecycle management.
 */
internal sealed interface PlayerLifecycleEvent
{
    /**
     * Player established a new connection.
     * 
     * @param session The newly created player session
     */
    data class Connected(val session : RestPlayerSession) : PlayerLifecycleEvent
    
    /**
     * Player reconnected, replacing an existing session.
     * 
     * @param session The new player session
     * @param previousSession The session that was replaced
     */
    data class Reconnected(val session : RestPlayerSession, val previousSession : RestPlayerSession) : PlayerLifecycleEvent
    
    /**
     * Player disconnected and session was terminated.
     * 
     * @param playerId ID of the player who disconnected
     */
    data class Disconnected(val playerId : String) : PlayerLifecycleEvent
}

/**
 * Manages REST-based player connections and session lifecycle.
 * 
 * Provides thread-safe registration/deregistration of player sessions,
 * broadcasting capabilities, and RPC invocation methods for client communication.
 * Sessions are tracked by player ID and support reconnection scenarios.
 * 
 * Connection lifecycle events are emitted via [lifecycleEvents] for monitoring
 * and coordination with other system components.
 */
internal class RestPlayerConnectionManager
{
    /** Thread-safe map of active player sessions by player ID */
    private val sessions = ConcurrentHashMap<String, RestPlayerSession>()

    /** Mutex for coordinating session registration/deregistration operations */
    private val mutex = Mutex()

    // No shared telemetry sink. Each per-session [RpcInvoker] is built
    // in [register] with a fresh [UsageTrackingTelemetrySink] that
    // captures the per-session [RpcOrigin]. The sink is a `val` on the
    // invoker's `telemetry` slot so every Request dispatch through
    // that invoker routes to the correct counter in
    // [globals.RpcUsageTracker]. See UsageTrackingTelemetrySink KDoc
    // for why the per-session shape is required (the sink interface's
    // `id` parameter is the per-RPC id, not a playerId).

    /**
     * Test-only hook that inserts an artificial `delay` at the tail of
     * [register]. Production code must leave this at 0; the Ktor
     * integration test for the SSE/POST race sets it to a small value so
     * the POST handler is guaranteed to enter [awaitSession] before the
     * SSE handler completes registration. Marked `internal` so it stays
     * out of the public API surface.
     */
    internal var registerDelayMillis: Long = 0

    /** Internal flow for emitting lifecycle events */
    private val _lifecycleEvents = MutableSharedFlow<PlayerLifecycleEvent>(extraBufferCapacity = 8)
    
    /** Public flow of player connection lifecycle events */
    val lifecycleEvents : SharedFlow<PlayerLifecycleEvent> = _lifecycleEvents

    /**
     * Registers a new player session or replaces an existing one.
     *
     * Creates a new session with buffered outgoing channel and RPC invoker.
     * If a session already exists for the player ID, the old session is closed
     * and replaced with the new one.
     *
     * @param playerId Unique identifier for the player
     * @param origin Origin tag for traffic originating from this
     *   session. Defaults to [RpcOrigin.GAME_CLIENT] for backwards
     *   compatibility with every existing test. Callers that come
     *   from the dedicated game server (`:server`) should pass
     *   [RpcOrigin.GAME_SERVER] so the [globals.RpcUsageTracker]
     *   can count them separately.
     * @return [ConnectionRegistration] containing the session and connection outcome
     */
    suspend fun register(
        playerId : String,
        origin : RpcOrigin = RpcOrigin.GAME_CLIENT
    ) : ConnectionRegistration
    {
        // Create outgoing message channel with buffering for performance
        val outgoing = Channel<String>(capacity = Channel.BUFFERED)

        // Create RPC invoker for client communication. The telemetry
        // sink is constructed per-session with the just-supplied
        // `origin` so every Request dispatched through this invoker
        // advances the matching counter in RpcUsageTracker. See
        // UsageTrackingTelemetrySink KDoc for the per-session shape
        // rationale.
        val invoker = RpcInvoker(
            sender = { message ->
                val payload = message.toJson(RpcJson)
                try {
                    outgoing.send(payload)
                } catch (_: Throwable) {
                    // Silently handle closed channel
                }
            },
            telemetry = UsageTrackingTelemetrySink(origin)
        )

        val session = RestPlayerSession(playerId, outgoing, invoker, origin)

        // Test-only artificial delay. Production leaves this at 0, so it is a
        // no-op fast path that the JIT will fold away. The delay intentionally
        // happens BEFORE we put the session into the map so a concurrent
        // findSession() call (from a racing POST) still observes a missing
        // session — which is exactly the softlock condition this hook exists
        // to reproduce.
        if(registerDelayMillis > 0)
        {
            delay(registerDelayMillis.milliseconds)
        }

        // Atomically register session and handle existing connections
        val previous = mutex.withLock { sessions.put(playerId, session) }
        
        val outcome = if (previous == null) {
            Logger.info(LogCategory.NETWORK, "RestPlayerConnectionManager registering new session for playerId=$playerId")
            _lifecycleEvents.emit(PlayerLifecycleEvent.Connected(session))
            ConnectionOutcome.New
        } else {
            Logger.info(LogCategory.NETWORK, "RestPlayerConnectionManager reconnecting playerId=$playerId, replacing previous session")
            previous.close()
            _lifecycleEvents.emit(PlayerLifecycleEvent.Reconnected(session, previous))
            ConnectionOutcome.Reconnected(previous)
        }
        
        return ConnectionRegistration(session, outcome)
    }

    /**
     * Suspends until a session for [playerId] is registered, or until
     * [timeoutMillis] elapses.
     *
     * Closes the POST/SSE race observed in `POST /rpc`: when a freshly
     * authenticated client rebinds its REST bridge, the POST and the SSE
     * GET arrive at server-extend in either order, and the POST can run
     * before the SSE handler has called [register]. Polling under the
     * existing [mutex] gives the SSE coroutine a fair window to land its
     * registration without holding the Ktor request thread hostage.
     *
     * @param playerId The session key to look up.
     * @param timeoutMillis Total budget. Default 1000ms; tuned to bridge
     *   the observed ~400us SSE registration window with margin while
     *   still failing fast on a genuinely disconnected client.
     * @param pollIntervalMillis Gap between polls. Default 25ms yields
     *   ~40 polls per default timeout, well within CPU budget.
     * @return The matching [RestPlayerSession], or `null` on timeout.
     *   Cooperative cancellation: cancelling the calling coroutine
     *   throws [kotlinx.coroutines.CancellationException] from `delay`.
     */
    suspend fun awaitSession(
        playerId : String,
        timeoutMillis : Long = 1_000,
        pollIntervalMillis : Long = 25,
    ) : RestPlayerSession?
    {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var attempt = 0
        while(true)
        {
            val session = findSession(playerId)
            if(session != null)
            {
                if(attempt > 0)
                {
                    Logger.debug(
                        LogCategory.NETWORK,
                        "RestPlayerConnectionManager.awaitSession: session ready for playerId=$playerId after $attempt retries"
                    )
                }
                return session
            }
            if(System.currentTimeMillis() >= deadline)
            {
                Logger.warn(
                    LogCategory.NETWORK,
                    "RestPlayerConnectionManager.awaitSession: timed out waiting ${timeoutMillis}ms for playerId=$playerId"
                )
                return null
            }
            attempt++
            delay(pollIntervalMillis.milliseconds)
        }
    }

    /**
     * Deregisters a player session and cleans up resources.
     * 
     * Removes the session from active tracking and closes its resources.
     * Only removes the session if it matches the expected session instance
     * to prevent race conditions during reconnection.
     * 
     * @param playerId ID of the player to deregister
     * @param expectedSession Optional session instance that must match for removal
     */
    suspend fun deregister(playerId : String, expectedSession : RestPlayerSession? = null)
    {
        // Atomically check and remove session if it matches expectations
        val removedSession = mutex.withLock {
            val current = sessions[playerId]
            if (current != null && (expectedSession == null || current === expectedSession)) {
                sessions.remove(playerId)
            } else {
                null
            }
        }
        
        // Clean up removed session and emit disconnection event
        removedSession?.let {
            Logger.info(LogCategory.NETWORK, "RestPlayerConnectionManager deregistering session for playerId=$playerId")
            it.close()
            _lifecycleEvents.emit(PlayerLifecycleEvent.Disconnected(playerId))
        }
    }

    /**
     * Broadcasts an RPC message to all connected players.
     * 
     * Sends the message to every active session. Individual send failures
     * are silently ignored to prevent one disconnected client from affecting others.
     * 
     * @param message RPC message to broadcast to all players
     */
    suspend fun broadcast(message : RpcMessage)
    {
        // Get snapshot of current sessions to avoid holding lock during sends
        val targets = mutex.withLock { sessions.values.toList() }
        Logger.debug(LogCategory.NETWORK, "RestPlayerConnectionManager broadcasting ${message.javaClass.simpleName} to ${targets.size} targets")
        
        // Send to all targets, ignoring individual failures
        for (target in targets) {
            try {
                target.sendRpcMessage(message)
            } catch (err: Throwable) {
                Logger.warn(LogCategory.NETWORK, "Broadcast send failure for playerId=${target.playerId}: ${err.message}")
            }
        }
    }

    /**
     * Broadcasts a player connection status change to all connected players.
     * 
     * Creates a connection event message and broadcasts it to notify other
     * players about connection/disconnection events.
     * 
     * @param playerId ID of the player whose status changed
     * @param status New connection status (CONNECTED or DISCONNECTED)
     */
    suspend fun broadcastConnectionEvent(playerId : String, status : ConnectionStatus)
    {
        val event = PlayerConnectionEvent(playerId, status, System.currentTimeMillis())
        broadcast(RpcMessage.ConnectionState(event))
    }

    /**
     * Gets a snapshot of all currently active player sessions.
     * 
     * @return List of active [RestPlayerSession] instances
     */
    suspend fun allSessions() : List<RestPlayerSession> = mutex.withLock { sessions.values.toList() }

    /**
     * Finds an active session for the specified player.
     *
     * @param playerId ID of the player to find
     * @return [RestPlayerSession] if found, null if player is not connected
     */
    suspend fun findSession(playerId : String) : RestPlayerSession? = mutex.withLock { sessions[playerId] }

    /**
     * Returns the [RpcOrigin] tag recorded for [playerId]'s current
     * session, or `null` if the player is not currently connected.
     *
     * Used by [UsageTrackingTelemetrySink] (via the
     * `connectionOrigins` lambda passed in at construction time) to
     * classify inbound traffic. Cheap: a single map lookup under the
     * existing session mutex.
     */
    suspend fun originFor(playerId : String) : RpcOrigin? = mutex.withLock { sessions[playerId]?.origin }

    /**
     * Makes an RPC call to a specific client and waits for the response.
     * 
     * Sends an RPC request to the specified player and waits for their response.
     * Returns null if the player is not connected or the call times out.
     * 
     * @param playerId ID of the target player
     * @param method RPC method name to invoke
     * @param params Optional parameters for the RPC call
     * @param timeoutMillis Optional timeout in milliseconds
     * @return [RpcMessage.Response] from the client, or null if failed
     */
    suspend fun callClient(
        playerId : String,
        method : String,
        params : JsonElement? = null,
        timeoutMillis : Long? = null
    ) : RpcMessage.Response?
    {
        val session = findSession(playerId)
        if (session == null) {
            Logger.warn(LogCategory.NETWORK, "Rest callClient aborted: player $playerId not connected")
            return null
        }
        val handle = session.invoker.request(method, params, timeoutMillis)
        return handle.await()
    }

    /**
     * Initiates a streaming RPC call to a specific client.
     * 
     * Starts a streaming RPC request that can receive multiple response chunks
     * from the client. Returns a handle for managing the stream lifecycle.
     * 
     * @param playerId ID of the target player
     * @param method RPC method name to invoke
     * @param params Optional parameters for the RPC call
     * @param timeoutMillis Optional timeout in milliseconds
     * @param bufferCapacity Buffer size for streaming responses
     * @return [RpcRequestHandle] for managing the stream, or null if player not connected
     */
    suspend fun callClientStream(
        playerId : String,
        method : String,
        params : JsonElement? = null,
        timeoutMillis : Long? = null,
        bufferCapacity : Int = 8
    ) : RpcRequestHandle?
    {
        val session = findSession(playerId)
        if (session == null) {
            Logger.warn(LogCategory.NETWORK, "callClientStream aborted: player $playerId not connected")
            return null
        }
        return session.invoker.stream(method, params, timeoutMillis, bufferCapacity)
    }

    /**
     * Initiates a streaming RPC call with typed parameters.
     * 
     * Type-safe version of [callClientStream] that automatically serializes
     * the parameters using Kotlinx Serialization.
     * 
     * @param P Type of the parameters object
     * @param playerId ID of the target player
     * @param method RPC method name to invoke
     * @param params Typed parameters object to serialize
     * @param timeoutMillis Optional timeout in milliseconds
     * @param bufferCapacity Buffer size for streaming responses
     * @return [RpcRequestHandle] for managing the stream, or null if player not connected
     */
    suspend inline fun <reified P> callClientStream(
        playerId : String,
        method : String,
        params : P,
        timeoutMillis : Long? = null,
        bufferCapacity : Int = 8
    ) : RpcRequestHandle?
    {
        val payload = RpcJson.encodeToJsonElement(serializer(), params)
        return callClientStream(playerId, method, payload, timeoutMillis, bufferCapacity)
    }

    /**
     * Broadcasts an RPC call to all connected clients and collects responses.
     * 
     * Sends the same RPC request to every connected player and waits for all
     * responses. Returns a map of player IDs to their response results, with
     * failures wrapped in Result.failure.
     * 
     * @param method RPC method name to invoke
     * @param params Optional parameters for the RPC call
     * @param timeoutMillis Optional timeout in milliseconds
     * @return Map of player IDs to their response results
     */
    suspend fun broadcastClients(
        method : String,
        params : JsonElement? = null,
        timeoutMillis : Long? = null
    ) : Map<String, Result<RpcMessage.Response>>
    {
        val sessionsToNotify = allSessions()
        Logger.debug(LogCategory.NETWORK, "RestPlayerConnectionManager broadcasting RPC $method to ${sessionsToNotify.size} players")
        return sessionsToNotify.associate { session ->
            session.playerId to runCatching {
                session.invoker.request(method, params, timeoutMillis).await()
            }
        }
    }
}