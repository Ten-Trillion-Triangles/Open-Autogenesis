package org.ttt.autogenesis.serverextend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import matchmaking.UrlHandoverRegistry

/**
 * Coordinates player connection lifecycle events for REST-based server connections.
 *
 * Listens to connection manager lifecycle events and dispatches them to registered
 * handlers. Provides a clean callback-based API for responding to player connections,
 * reconnections, and disconnections without directly coupling to the connection manager.
 *
 * The coordinator also owns the "URL handover" staleness listener: when a player
 * disconnects (closes the SSE channel, navigates away, or vanishes for any other
 * reason), every URL the [urlHandoverRegistry] has captured for a sessionId
 * owned by that player is removed. Ownership is determined by [playerSessionResolver]
 * which, in production, delegates to
 * [matchmaking.ServerConnector.sessionIdsForPlayer].
 *
 * Both [urlHandoverRegistry] and [playerSessionResolver] have no-op defaults so
 * every existing test that constructs the coordinator with the original
 * 2-parameter signature still compiles and runs unchanged. The defaults produce
 * a no-op listener — the coordinator behaves exactly as before for callers that
 * don't pass the new parameters.
 *
 * All event handlers are executed within the provided coroutine scope, allowing
 * for proper structured concurrency and cancellation support.
 *
 * @param manager Connection manager to observe for lifecycle events
 * @param scope Coroutine scope for executing event handlers
 * @param urlHandoverRegistry Registry to clear on vanish (default: a fresh
 *   empty registry, which produces a no-op listener)
 * @param playerSessionResolver Resolves the set of sessionIds owned by a
 *   playerId (default: empty set, which produces a no-op listener)
 */
internal class RestServerConnectionCoordinator(
    private val manager : RestPlayerConnectionManager,
    private val scope : CoroutineScope,
    private val urlHandoverRegistry: UrlHandoverRegistry = UrlHandoverRegistry(),
    private val playerSessionResolver: suspend (String) -> Set<String> = { emptySet() }
)
{
    /** Handler for new player connections */
    private var _connectedHandler : (suspend (RestPlayerSession) -> Unit)? = null

    /** Handler for player reconnections */
    private var _reconnectedHandler : (suspend (RestPlayerSession, RestPlayerSession) -> Unit)? = null

    /** Handler for player disconnections */
    private var _disconnectedHandler : (suspend (String) -> Unit)? = null

    init {
        // Subscribe to connection events and dispatch to appropriate handlers
        manager.lifecycleEvents
            .filterIsInstance<PlayerLifecycleEvent.Connected>()
            .onEach { event -> _connectedHandler?.invoke(event.session) }
            .launchIn(scope)

        manager.lifecycleEvents
            .filterIsInstance<PlayerLifecycleEvent.Reconnected>()
            .onEach { event -> _reconnectedHandler?.invoke(event.session, event.previousSession) }
            .launchIn(scope)

        manager.lifecycleEvents
            .filterIsInstance<PlayerLifecycleEvent.Disconnected>()
            .onEach { event ->
                // STALENESS LISTENER (vanish path): clear every URL
                // the registry holds for a sessionId owned by the
                // disconnecting player. The player may have closed
                // the SSE channel mid-hand-off, before they ever
                // connected to the DS, or after the DS became
                // unreachable. The remove-loop is a safe no-op when
                // the resolver returns an empty set (the common case
                // where the disconnecting player never owned a
                // session).
                val sessionIds = playerSessionResolver(event.playerId)
                for (sessionId in sessionIds)
                {
                    urlHandoverRegistry.remove(sessionId)
                }
                _disconnectedHandler?.invoke(event.playerId)
            }
            .launchIn(scope)
    }

    /**
     * Registers a handler for new player connections.
     *
     * The handler will be called whenever a player establishes a new connection
     * (not a reconnection). Only one handler can be registered at a time.
     *
     * @param handler Suspend function to handle new connections
     */
    fun onConnected(handler : suspend (RestPlayerSession) -> Unit)
    {
        _connectedHandler = handler
    }

    /**
     * Registers a handler for player reconnections.
     *
     * The handler will be called whenever a player reconnects, replacing an
     * existing session. Receives both the new session and the previous session
     * that was replaced. Only one handler can be registered at a time.
     *
     * @param handler Suspend function to handle reconnections
     */
    fun onReconnected(handler : suspend (RestPlayerSession, RestPlayerSession) -> Unit)
    {
        _reconnectedHandler = handler
    }

    /**
     * Registers a handler for player disconnections.
     *
     * The handler will be called whenever a player disconnects and their session
     * is terminated. Receives the player ID of the disconnected player.
     * Only one handler can be registered at a time.
     *
     * The signature is `(String) -> Unit` (not `suspend`) to match the existing
     * public API. The staleness-removal work happens in the init block's
     * lifecycle listener above, which already runs in [scope] and does not
     * block the Ktor dispatcher.
     *
     * @param handler Function to handle disconnections
     */
    fun onDisconnected(handler : (String) -> Unit)
    {
        _disconnectedHandler = { playerId -> handler(playerId) }
    }
}
