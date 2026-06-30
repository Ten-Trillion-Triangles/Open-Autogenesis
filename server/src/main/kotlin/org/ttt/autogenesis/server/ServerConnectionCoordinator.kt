package org.ttt.autogenesis.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * High-level helper that exposes connection lifecycle callbacks for the server.
 * 
 * It wraps [PlayerConnectionManager.lifecycleEvents] so callers can fire delegates when
 * a player connects or disconnects and automatically receive the [PlayerSession] with its
 * RPC invoker to begin handshakes.
 * 
 * @param manager The player connection manager to coordinate with
 */
internal class ServerConnectionCoordinator(
    manager: PlayerConnectionManager
)
{
    private var _connectedHandler: (suspend (PlayerSession) -> Unit)? = null
    private var _reconnectedHandler: (suspend (PlayerSession, PlayerSession) -> Unit)? = null
    private var _disconnectedHandler: (suspend (String) -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
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
            .onEach { event -> _disconnectedHandler?.invoke(event.playerId) }
            .launchIn(scope)
    }

    /**
     * Sets handler for when a player connects for the first time.
     * 
     * @param handler Callback to invoke with the new player session
     */
    fun onConnected(handler: suspend (PlayerSession) -> Unit)
    {
        _connectedHandler = handler
    }

    /**
     * Sets handler for when a player reconnects.
     * 
     * @param handler Callback to invoke with new and previous player sessions
     */
    fun onReconnected(handler: suspend (PlayerSession, PlayerSession) -> Unit)
    {
        _reconnectedHandler = handler
    }

    /**
     * Sets handler for when a player disconnects.
     * 
     * @param handler Callback to invoke with the disconnected player ID
     */
    fun onDisconnected(handler: suspend (String) -> Unit)
    {
        _disconnectedHandler = handler
    }
}
