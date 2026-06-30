package org.ttt.autogenesis.server

import gameState.WorldManager
import org.ttt.autogenesis.network.*
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.ttt.autogenesis.server.audio.AudioManager
import org.ttt.autogenesis.audio.AudioSyncState
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.network.RpcMethod
import org.ttt.autogenesis.network.RpcDirection
import structs.resume.ResumeAvailabilityNotification

/**
 * Singleton that manages UI signal broadcasting to game clients.
 */
object UiSignalRpcHandlers
{
    internal var connectionManager: PlayerConnectionManager? = null

    /**
     * Injected by [Server] setup when push-notification wiring is enabled.
     * Nullable so the rest of the system continues to function when push is
     * disabled (e.g. dev mode without a VAPID keypair provisioned).
     */
    internal var pushSubscriptionStore: org.ttt.autogenesis.server.push.PushSubscriptionStore? = null
    internal var pushNotificationService: org.ttt.autogenesis.server.push.PushNotificationService? = null
    internal var onGameOverBroadcast: (suspend (GameOverData) -> Unit)? = null

    /**
     * Broadcasts the prompt status for a specific connection.
     */
    suspend fun broadcastPromptStatus(connectionId: String, usage: structs.AgentUsage)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Dispatching 'ui.updatePromptStatus' to $connectionId (Classifier: ${usage.runningClassifier})")
        val payload = RpcJson.encodeToJsonElement(serializer<structs.AgentUsage>(), usage)
        val notification = RpcMessage.Notification("ui.updatePromptStatus", payload)
        
        val sessions = connectionManager?.findAllSessions(connectionId) ?: emptyList()
        if(sessions.isNotEmpty())
        {
            sessions.forEach { it.sendRpcMessage(notification) }
        }
        else
        {
            Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers: Cannot send prompt status, session '$connectionId' not found")
        }
    }

    /**
     * Broadcasts a map pack to all connected clients.
     */
    suspend fun broadcastMapLoad(mapPackBytes: ByteArray)
    {
        val data = org.ttt.autogenesis.network.MapLoadInstruction(mapPackBytes)
        broadcastNotification("ui.loadMapPack", data)
    }

    /**
     * Sends map, world data, player identity, and history cache to a specific connection to sync them after joining.
     */
    suspend fun sendInitialSync(connectionId: String, localPlayer: structs.Player, mapPackBytes: ByteArray?, world: structs.World, history: List<structs.GameHistory>, accelByteUserId: String? = null)
    {
        // Try to find sessions for the given connectionId first
        var sessions = connectionManager?.findAllSessions(connectionId) ?: emptyList()
        
        // If no sessions found for connectionId, and accelByteUserId is provided, try to find sessions by accelByteUserId
        // This handles the case where browser (PRIMARY) and controller (CONTROLLER) have different playerIds but share accelByteUserId
        if (sessions.isEmpty() && accelByteUserId != null) {
            Logger.debug(LogCategory.NETWORK, "UiSignalRpcHandlers: No sessions found for connectionId=$connectionId, trying accelByteUserId lookup")
            // Find stats by accelByteUserId to get the playerID that has the sessions
            val stats = gameState.WorldManager.playerStats.firstOrNull { it.accelByteUserId == accelByteUserId }
            if (stats != null)
            {
                sessions = connectionManager?.findAllSessions(stats.playerID) ?: emptyList()
                Logger.debug(LogCategory.NETWORK, "UiSignalRpcHandlers: Found sessions via accelByteUserId lookup: ${sessions.size} sessions for playerID=${stats.playerID}")
            }
        }
        
        if (sessions.isEmpty()) {
            Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers: Cannot send initial sync, no sessions found for connectionId=$connectionId (accelByteUserId=$accelByteUserId)")
            return
        }
        
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Starting initial sync for $connectionId")
        
        // 0. Sync Local Player Identity
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Syncing local player identity to $connectionId")
        sendSetLocalPlayer(connectionId, localPlayer)
        
        // 1. Sync Map
        mapPackBytes?.let { bytes ->
            Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Syncing map pack to $connectionId (${bytes.size} bytes)")
            val mapData = org.ttt.autogenesis.network.MapLoadInstruction(bytes)
            val mapPayload = RpcJson.encodeToJsonElement(kotlinx.serialization.serializer<org.ttt.autogenesis.network.MapLoadInstruction>(), mapData)
            sessions.forEach { it.sendRpcMessage(RpcMessage.Notification("ui.loadMapPack", mapPayload)) }
            Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Map pack notification sent to $connectionId")
        } ?: Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers: No map pack bytes available for sync to $connectionId")
        
        // 2. Sync World
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Syncing world state to $connectionId (Round ${world.roundNumber})")
        val worldData = org.ttt.autogenesis.network.WorldUpdateData(world)
        val worldPayload = RpcJson.encodeToJsonElement(kotlinx.serialization.serializer<org.ttt.autogenesis.network.WorldUpdateData>(), worldData)
        sessions.forEach { it.sendRpcMessage(RpcMessage.Notification("ui.updateWorld", worldPayload)) }
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: World update notification sent to $connectionId")
        
        // 3. Sync Game History
        if (history.isNotEmpty()) {
            Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Syncing ${history.size} game history entries to $connectionId")
            history.forEach { entry ->
                val payload = RpcJson.encodeToJsonElement(kotlinx.serialization.serializer<structs.GameHistory>(), entry)
                sessions.forEach { it.sendRpcMessage(RpcMessage.Notification("ui.turnComplete", payload)) }
            }
        }

        // 4. Sync Audio State (for late-join / reconnect)
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Syncing audio state to $connectionId")
        AudioManager.buildSyncState().let { audioState ->
            val payload = RpcJson.encodeToJsonElement(serializer<AudioSyncState>(), audioState)
            sessions.forEach { it.sendRpcMessage(RpcMessage.Notification("audio.syncState", payload)) }
        }

        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Initial sync notifications dispatched for $connectionId (Map Sync: ${mapPackBytes != null}, World Sync: true)")
    }

    /**
     * Broadcasts a resolution step update.
     *
     * @param step The resolution step to switch to.
     * @param message Optional message to display with the step.
     */
    suspend fun broadcastResolutionStep(step: ResolutionStep, message: String? = null)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: broadcasting ResolutionStep: $step, Message: '$message'")
        val data = ResolutionStepData(step, message)
        broadcastNotification("ui.setResolutionStep", data)
    }

    /**
     * Broadcasts an update to the progress bar.
     *
     * @param activeIndex The active step index.
     * @param instruction Optional instruction text to display.
     */
    suspend fun broadcastProgressBar(activeIndex: Int, instruction: String? = null)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: broadcasting ProgressBar: Index=$activeIndex, Instruction='$instruction'")
        val data = ProgressBarData(activeIndex, instruction)
        broadcastNotification("ui.updateProgressBar", data)
    }

    /**
     * Broadcasts a narrative chunk.
     *
     * @param chunk The narrative text chunk to broadcast.
     * @param isComplete Whether this is the final chunk.
     */
    suspend fun broadcastNarrativeChunk(chunk: String, isComplete: Boolean = false)
    {
        val data = NarrativeChunkData(chunk, isComplete)
        broadcastNotification("ui.narrativeChunk", data)
    }

    /**
     * Broadcasts a narrative update (replace/rewrite).
     *
     * @param text The new narrative text to display.
     */
    suspend fun broadcastNarrativeUpdate(text: String)
    {
        val data = NarrativeUpdateData(text)
        broadcastNotification("ui.narrativeUpdate", data)
    }

    /**
     * Broadcasts a signal to prepare the story page for a new story (wipe).
     */
    suspend fun broadcastPrepareStory(style: String? = null)
    {
        val data = PrepareStoryData(style)
        broadcastNotification("ui.prepareStory", data)
    }

    /**
     * Broadcasts command interactivity status.
     *
     * @param interactive Whether the command console should be interactive.
     */
    suspend fun broadcastCommandInteractive(interactive: Boolean)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: broadcasting CommandInteractive: $interactive")
        val data = CommandInteractiveData(interactive)
        broadcastNotification("ui.setCommandInteractive", data)
    }

    /**
     * Broadcasts a full world state update.
     *
     * @param world The updated world object.
     */
    suspend fun broadcastWorldUpdate(world: structs.World)
    {
        val data = WorldUpdateData(world)
        broadcastNotification("ui.updateWorld", data)
    }

    /**
     * Broadcasts a stream chunk to a specific agent tab.
     */
    suspend fun broadcastAgentStream(connectionId: String, tabId: String, content: String, isComplete: Boolean)
    {
        sendAgentStreamPayload(
            connectionId = connectionId,
            data = RpcMessage.AgentStreamData(
                connectionId = connectionId,
                tabId = tabId,
                content = content,
                isComplete = isComplete
            )
        )
    }

    /**
     * Sends structured answer-agent stream lifecycle events to a specific connection.
     */
    suspend fun sendAgentStreamEvent(
        connectionId: String,
        tabId: String,
        phase: AgentStreamPhase,
        streamId: String,
        sequence: Long = 0,
        delta: String = "",
        finalText: String? = null,
        commandContext: String? = null
    )
    {
        sendAgentStreamPayload(
            connectionId = connectionId,
            data = RpcMessage.AgentStreamData(
                connectionId = connectionId,
                tabId = tabId,
                content = delta,
                isComplete = phase == AgentStreamPhase.END,
                commandContext = commandContext,
                phase = phase,
                streamId = streamId,
                sequence = sequence,
                delta = delta,
                finalText = finalText
            )
        )
    }

    /**
     * Notifies a client whether a submitted command should trigger gameplay UI flows.
     *
     * The payload mirrors [CommandClassificationData] and is emitted before the gameplay state machine
     * is mutating in [PromptManager]. Non-gameplay prompts expect the UI to stay on the map.
     */
    suspend fun sendCommandClassification(connectionId: String, data: CommandClassificationData)
    {
        val payload = RpcJson.encodeToJsonElement(serializer<CommandClassificationData>(), data)
        val notification = RpcMessage.Notification("ui.commandClassification", payload)

        val sessions = connectionManager?.findAllSessions(connectionId) ?: emptyList()
        if(sessions.isNotEmpty())
        {
            sessions.forEach { it.sendRpcMessage(notification) }
        }
        else
        {
            Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers: Cannot send command classification, session '$connectionId' not found")
        }
    }

    private suspend fun sendAgentStreamPayload(connectionId: String, data: RpcMessage.AgentStreamData)
    {
        val payload = RpcJson.encodeToJsonElement(serializer<RpcMessage.AgentStreamData>(), data)
        val notification = RpcMessage.Notification("ui.agentStream", payload)
        
        val sessions = connectionManager?.findAllSessions(connectionId) ?: emptyList()
        if(sessions.isNotEmpty())
        {
            sessions.forEach { it.sendRpcMessage(notification) }
        }
        else
        {
             // If we can't find specific session, we might warn or fallback. 
             // For now, warn.
             Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers: Cannot stream to agent tab, session '$connectionId' not found")
        }
    }

    /**
     * Sends agent work stream data to a specific player.
     */
    /**
     * Forwards buffered stream chunks directly to the single player's WebSocket session.
     *
     * We encode the `AgentWorkStreamData` and notify `ui.agentWorkStream`; if the session is not
     * found the warning is logged so we can clean up stale subscriptions elsewhere.
     */
    suspend fun sendAgentWorkStream(connectionId: String, data: RpcMessage.AgentWorkStreamData)
    {
        val payload = RpcJson.encodeToJsonElement(serializer<RpcMessage.AgentWorkStreamData>(), data)
        val notification = RpcMessage.Notification("ui.agentWorkStream", payload)

        val sessions = connectionManager?.findAllSessions(connectionId) ?: emptyList()
        if(sessions.isNotEmpty())
        {
            sessions.forEach { it.sendRpcMessage(notification) }
        }
        else
        {
            Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers: Cannot send agent work stream, session '$connectionId' not found")
        }
    }

    /**
     * Internal helper to broadcast a notification to all connected clients.
     *
     * @param method The RPC method name.
     * @param data The data payload to broadcast.
     */
    private suspend inline fun <reified T> broadcastNotification(method: String, data: T)
    {
        Logger.debug(LogCategory.NETWORK, "UiSignalRpcHandlers: Internal Broadcast -> Method: '$method'")
        val payload = RpcJson.encodeToJsonElement(serializer<T>(), data)
        val notification = RpcMessage.Notification(method, payload)
        
        val manager = connectionManager
        if(manager == null)
        {
            Logger.debug(LogCategory.NETWORK, "UiSignalRpcHandlers: Cannot broadcast '$method', connectionManager is null")
            return
        }
        
        manager.broadcast(notification)
    }
    /**
     * Sends a signal to open the Neural Link window for a specific connection.
     */
    suspend fun sendOpenNeuralLink(connectionId: String)
    {
        // Send a boolean 'true' as payload to ensure type safety/serializer resolution works on client
        val payload = RpcJson.encodeToJsonElement(serializer<Boolean>(), true)
        val notification = RpcMessage.Notification("ui.openNeuralLink", payload)
        
        val sessions = connectionManager?.findAllSessions(connectionId) ?: emptyList()
        if(sessions.isNotEmpty())
        {
            sessions.forEach { it.sendRpcMessage(notification) }
        }
        else
        {
            Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers: Cannot send openNeuralLink, session '$connectionId' not found")
        }
    }

    /**
     * Sends a signal to show a message box for a specific connection.
     */
    suspend fun broadcastShowMessageBox(connectionId: String, title: String, message: String, showOk: Boolean = true, showCancel: Boolean = false, showThrobber: Boolean = false)
    {
        Logger.debug(LogCategory.NETWORK, "UiSignalRpcHandlers: broadcastShowMessageBox() invoked for connectionId=$connectionId, title=$title")
        val data = ShowMessageBoxData(title, message, showOk, showCancel, showThrobber)
        val payload = RpcJson.encodeToJsonElement(serializer<ShowMessageBoxData>(), data)
        val notification = RpcMessage.Notification("ui.showMessageBox", payload)
        
        val sessions = connectionManager?.findAllSessions(connectionId) ?: emptyList()
        if(sessions.isNotEmpty())
        {
            Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Dispatching 'ui.showMessageBox' notification to session $connectionId")
            sessions.forEach { it.sendRpcMessage(notification) }
        }
        else
        {
            Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers: Cannot send showMessageBox, session '$connectionId' not found")
        }
    }

    /**
     * Broadcasts a prompt to a player to respond to an action.
     *
     * @param actionIntent Indicates whether the action is Hostile or Friendly so the client can style appropriately.
     */
    suspend fun broadcastCounterPlayPrompt(targetId: String, attackerName: String, actionDescription: String, actionIntent: org.ttt.autogenesis.network.ActionIntent = org.ttt.autogenesis.network.ActionIntent.Hostile)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: broadcasting CounterPlayPrompt to $targetId (attacker=$attackerName, intent=$actionIntent)")
        val data = CounterPlayPrompt(targetId, attackerName, actionDescription, actionIntent)
        val payload = RpcJson.encodeToJsonElement(serializer<CounterPlayPrompt>(), data)
        val notification = RpcMessage.Notification("ui.counterPlayPrompt", payload)

        // targetId is the Player Name (e.g. "Commander Shepard"). We need the Connection ID.
        // In Demo Mode, PromptManager maps "Commander Shepard" to the active connection via valid PlayerStats.
        val playerStats = gameState.WorldManager.playerStats.find { it.playerData.name.equals(targetId, ignoreCase = true) }
        val connectionId = playerStats?.playerID

        if (connectionId != null)
        {
            val sessions = connectionManager?.findAllSessions(connectionId) ?: emptyList()
            if(sessions.isNotEmpty())
            {
                Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Dispatching 'ui.counterPlayPrompt' to $targetId (Connection: $connectionId)")
                sessions.forEach { it.sendRpcMessage(notification) }
            }
            else
            {
                 Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers: Cannot send counterPlayPrompt, session for connection '$connectionId' not found")
            }
        }
        else
        {
            Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers: Cannot send counterPlayPrompt, no active connection found for player '$targetId'")
        }
    }

    /**
     * Broadcasts the result of a Summit to all connected clients.
     *
     * @param success Whether the summit was successful.
     * @param narrativeSummary The narrative summary of what happened.
     * @param statChanges A map of player names to their stat change descriptions.
     */
    suspend fun broadcastSummitResult(
        success: Boolean,
        narrativeSummary: String,
        statChanges: Map<String, String>
    )
    {
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: broadcasting SummitResult (success=$success)")
        val data = SummitResultData(success, narrativeSummary, statChanges)
        broadcastNotification("ui.summitResult", data)
    }

    /**
     * Broadcasts the judgement effect (Success/Failure) to the UI.
     */
    suspend fun broadcastJudgementResult(isSuccess: Boolean, header: String, subtext: String)
    {
        val data = JudgementEffectData(isSuccess, header, subtext)
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Broadcasting Judgement Effect - Success: $isSuccess, Header: $header")
        broadcastNotification("ui.judgementResult", data)
    }

    /**
     * Broadcasts the latest turn timer state to all connected clients.
     */
    suspend fun broadcastTurnTimerUpdate(remainingSeconds: Long, totalDuration: Long = gameState.WorldManager.TURN_DURATION_SECONDS, isRunning: Boolean)
    {
        val data = TurnTimerUpdateData(remainingSeconds, totalDuration, isRunning)
        broadcastNotification("ui.updateTurnTimer", data)
    }

    /**
     * Sends a targeted turn timer update to a specific connection.
     */
    suspend fun sendTurnTimerUpdate(connectionId: String, remainingSeconds: Long, totalDuration: Long = gameState.WorldManager.TURN_DURATION_SECONDS, isRunning: Boolean)
    {
        val data = TurnTimerUpdateData(remainingSeconds, totalDuration, isRunning)
        val payload = RpcJson.encodeToJsonElement(serializer<TurnTimerUpdateData>(), data)
        val notification = RpcMessage.Notification("ui.updateTurnTimer", payload)
        
        val sessions = connectionManager?.findAllSessions(connectionId) ?: emptyList()
        if(sessions.isNotEmpty())
        {
            Logger.debug(LogCategory.NETWORK, "UiSignalRpcHandlers: Dispatching 'ui.updateTurnTimer' to $connectionId")
            sessions.forEach { it.sendRpcMessage(notification) }
        }
        else
        {
            Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers: Cannot send updateTurnTimer, session '$connectionId' not found")
        }
    }
    /**
     * Broadcasts the dispatch results (logistical transfer of assets).
     */
    suspend fun broadcastDispatchResult(data: DispatchData)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: broadcasting Dispatch Result")
        broadcastNotification("ui.dispatchResult", data)
    }

    suspend fun broadcastThinking(data: ThinkingUpdateData)
    {
        Logger.debug(LogCategory.NETWORK, "UiSignalRpcHandlers: Broadcasting thinking update for ${data.characterName}")
        WorldManager.recordThinkingUpdate(data, WorldManager.world.roundNumber)
        broadcastNotification("ui.thinkingUpdate", data)
    }

    /**
     * Broadcasts an updated intent string to the UI.
     */
    suspend fun broadcastIntentUpdate(intent: String)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Broadcasting Intent Update: '$intent'")
        val data = UpdateIntentData(intent)
        broadcastNotification("ui.updateIntent", data)
    }

    /**
     * Broadcasts the turn order announcement for the upcoming round.
     */
    suspend fun broadcastTurnOrderAnnouncement(data: TurnOrderAnnouncementData)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Broadcasting turn order announcement for Round ${data.roundNumber}")
        broadcastNotification("ui.announceTurnOrder", data)
    }

    /**
     * Broadcasts which actor is currently taking their turn.
     *
     * @param data Metadata describing the actor who owns the live turn.
     */
    suspend fun broadcastActiveTurn(data: ActiveTurnData)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Broadcasting active turn actor=${data.actorName}, round=${data.roundNumber}, index=${data.turnIndex}")
        broadcastNotification("ui.activeTurn", data)
    }

    /**
     * Broadcasts a Nemesis threat announcement for the upcoming round startup flow.
     */
    suspend fun broadcastNemesisThreatAnnouncement(data: NemesisThreatAnnouncementData)
    {
        Logger.info(
            LogCategory.NETWORK,
            "UiSignalRpcHandlers: Broadcasting nemesis threat for Round ${data.roundNumber} (${data.kind}) - ${data.nemesisName}, reason=${data.reason.take(100)}"
        )
        broadcastNotification("ui.announceNemesisThreat", data)
        Logger.debug(LogCategory.NETWORK, "broadcastNotification called for ui.announceNemesisThreat")
    }

    /**
     * Sends a signal to set the local player identity for a specific connection.
     */
    suspend fun sendSetLocalPlayer(connectionId: String, player: structs.Player)
    {
        val data = SetLocalPlayerData(player)
        val payload = RpcJson.encodeToJsonElement(serializer<SetLocalPlayerData>(), data)
        val notification = RpcMessage.Notification("ui.setLocalPlayer", payload)
        
        val sessions = connectionManager?.findAllSessions(connectionId) ?: emptyList()
        if(sessions.isNotEmpty())
        {
            Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Dispatching 'ui.setLocalPlayer' to $connectionId")
            sessions.forEach { it.sendRpcMessage(notification) }
        }
        else
        {
            Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers: Cannot send setLocalPlayer, session '$connectionId' not found")
        }
    }

    /**
     * Broadcasts the game over event to all connected clients.
     *
     * In single-player mode this also fires a best-effort delete of the
     * human player's `running-game` record so a future reconnect does not
     * resurrect a snapshot of a game that has already ended. Game-over is
     * still broadcast even if the delete fails — a stale snapshot is a
     * recoverable nuisance, not a fatal error.
     *
     * **Contract pinned by TurnHarnessRunningGameTest + the new e2e probe
     * `resume-snapshot-cleared-on-game-over.mjs`:**
     *   - Reaching this method means the game has ended (win / loss /
     *     forced game-over / surrender that ended the game).
     *   - In single-player mode the running-game snapshot MUST be deleted
     *     (or marked with the consumed-sentinel) so the next login does
     *     not show the ResumeOrNewDialog for a game the player has
     *     already finished.
     *   - The disconnect path in `Server.kt:524` skips saving when
     *     `WorldManager.isGameActive=false`, so this delete-on-game-over
     *     is the only thing that prevents a finished-game snapshot from
     *     surviving a future reconnect. If a future refactor removes the
     *     `clearRunningGameForUser` call below, both the unit tests and
     *     the e2e probe will fail.
     *
     * @param data Final game over data describing placements.
     */
    suspend fun broadcastGameOver(data: GameOverData)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Broadcasting Game Over - Winner: ${data.winnerName}")
        onGameOverBroadcast?.invoke(data)
        broadcastNotification("ui.gameOver", data)

        if (gameState.WorldManager.isSinglePlayer)
        {
            val humanUserId = gameState.WorldManager.findPlayerFromStats(gameState.WorldManager.humanPlayerName)
                ?.accelByteUserId
                .orEmpty()
            if (humanUserId.isNotBlank())
            {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try
                    {
                        TurnHarness.clearRunningGameForUser(humanUserId)
                    }
                    catch (e: Exception)
                    {
                        Logger.warn(
                            LogCategory.DATABASE,
                            "UiSignalRpcHandlers: Failed to clear running-game on game-over for user=$humanUserId: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    /**
     * Broadcasts a request to open a specific widget to all clients.
     */
    suspend fun broadcastOpenWidget(data: OpenWidgetData)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Broadcasting Open Widget: ${data.widget}")
        broadcastNotification("ui.openWidget", data)
    }

    /**
     * Sends a request to open a specific widget to a specific connection.
     */
    suspend fun sendOpenWidget(connectionId: String, data: OpenWidgetData)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: Dispatching 'ui.openWidget' to $connectionId")
        val payload = RpcJson.encodeToJsonElement(serializer<OpenWidgetData>(), data)
        val notification = RpcMessage.Notification("ui.openWidget", payload)
        
        val sessions = connectionManager?.findAllSessions(connectionId) ?: emptyList()
        if(sessions.isNotEmpty())
        {
            sessions.forEach { it.sendRpcMessage(notification) }
        }
        else
        {
            Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers: Cannot send openWidget, session '$connectionId' not found")
        }
    }

    /**
     * Server-to-client push: a saved running-game record exists for the
     * given user. The client renders the ResumeOrNewDialog.
     *
     * Invoked by server-extend via the existing server↔server-extend gRPC
     * bridge after the cloud-save proxy returns a record on login. Looks
     * up the WS connection for the calling human player's accelByteUserId
     * and pushes a `client.resumeAvailable` notification over the WebSocket.
     * The notification is one-way (server → client) and carries the saved
     * snapshot's round/turn/hasAi metadata so the client can render the
     * modal without an additional round trip.
     *
     * RpcDirection.SERVER (not CLIENT) because the main server is the
     * receiver of this call: server-extend invokes it from outside the
     * main server's process. The eventual notification the client sees
     * is named `client.resumeAvailable` — distinct from the RPC method
     * name on purpose, so the push can be refactored without renaming
     * the wire-level method the client listens for.
     */
    @RpcMethod("client.resumeAvailable", RpcDirection.SERVER)
    suspend fun notifyResumeAvailable(ctx: RpcCallContext, payload: ResumeAvailabilityNotification)
    {
        val userId = payload.userId

        // BUG FIX — do NOT push the resume modal if the user is currently
        // in an active game session. The `client.resumeAvailable` push fires
        // every time the SSE rebinds (e.g., on login, on reconnect, on
        // page reload), so without this guard the ResumeOrNewDialog would
        // reappear whenever the WS reconnects mid-game, interrupting the
        // player. The guard is: if the game is active AND this user has
        // already joined as a player, the modal would only confuse the
        // user — drop the push silently.
        //
        // Edge cases:
        //  - First-time login (no game yet): `isGameActive=false`, push fires,
        //    user sees the modal. Correct.
        //  - Resume after server restart: `isGameActive=true` because the
        //    auto-restore ran on connect and the world was rehydrated,
        //    `lastRehydratedAccelByteUserId == userId`. Push fires — the user
        //    has the choice to start fresh or reload to re-resume.
        //    (Previously this is what the BUG 1/2 markers were about; we
        //    keep that path because the rehydrate-failure path needs the
        //    modal as a fallback.)
        //  - Mid-game reconnect: `isGameActive=true` AND `playerStats` has
        //    entries for this user. Drop the push. The reconnecting player
        //    stays in their running game; no modal.
        //  - Mid-game reconnect where auto-restore FAILED (i.e., the world
        //    is empty): `isGameActive=true` but the user has no player
        //    entry. The race-recovery path will handle restoring their state
        //    if the saved snapshot is still present. The push should still
        //    fire as a safety net so the user gets the modal — we detect
        //    this by checking `lastRehydratedAccelByteUserId != userId`.
        // BUG 26 (2026-06-27): the original mid-game guard only checked whether
        // a playerStats entry existed for the user. That fired a false positive
        // after the user disconnected: the playerStats entry still exists (it
        // was populated on WS connect) but the user has disconnected, so
        // pushing the modal is exactly what we want. The correct check is
        // "is the user actively playing a turn RIGHT NOW?" — the same condition
        // used by the auto-restore gate on the WS side.
        //
        // The `isConnected` flag on PlayerStats is flipped false in
        // Server.onDisconnected → updatePlayerConnectionStats, so by the
        // time the SSE-triggered push reaches this handler, the user's
        // playerStats entry has `isConnected=false` and the push proceeds.
        //
        // Revision: a user who just logged in (fresh WS connect) but
        // hasn't yet submitted a turn also has `isConnected=true` on
        // their stale playerStats entry (from a previous session that
        // never set isConnected=false on disconnect). In that case the
        // entry's `turnActive=false` because the new turn loop hasn't
        // started — we must NOT skip the push in that scenario. The
        // correct invariant: skip only when isConnected=true AND
        // turnActive=true (the user is actively mid-turn).
        if (WorldManager.isGameActive)
        {
            val humanEntry = WorldManager.playerStats.firstOrNull { it.accelByteUserId == userId }
            val userIsMidTurn = humanEntry != null && humanEntry.isConnected && humanEntry.turnActive
            val worldJustRehydratedForThisUser =
                WorldManager.lastRehydratedAccelByteUserId == userId
            if (userIsMidTurn && !worldJustRehydratedForThisUser)
            {
                Logger.info(
                    LogCategory.NETWORK,
                    "UiSignalRpcHandlers.notifyResumeAvailable: skipped (user=$userId is mid-game; modal would interrupt active session)"
                )
                return
            }
        }

        // Primary lookup: find sessions by their AccelByte user id (set at WS
        // handshake time from the `accelbyteId` query parameter). This is the
        // authoritative path because the WS session's `playerId` (a JS-generated
        // string like "kvision-ws-client-1385884541") does NOT match the
        // snapshot's stale `playerID` ("guest-user-conn-test"), so the
        // previous playerStats-based lookup always missed the live session.
        // The previous behavior was the proximate cause of the
        // "no resume dialog ever appears" bug observed on 2026-06-24.
        val sessions = connectionManager?.findAllSessionsByAccelbyteId(userId) ?: emptyList()
        if (sessions.isNotEmpty())
        {
            val element = RpcJson.encodeToJsonElement(ResumeAvailabilityNotification.serializer(), payload)
            val notification = RpcMessage.Notification("client.resumeAvailable", element)
            sessions.forEach { it.sendRpcMessage(notification) }
            Logger.info(
                LogCategory.NETWORK,
                "UiSignalRpcHandlers.notifyResumeAvailable: pushed to userId=$userId sessions=${sessions.size} round=${payload.worldRound} hasAi=${payload.hasAi}"
            )
            return
        }
        // Secondary fallback: scan WorldManager.playerStats for a matching
        // playerStats entry. Preserved for defensive coverage — e.g. when the
        // WS session's accelbyteId was set blank (older clients pre-Fix 1)
        // but the player's stats are populated by the matchmaker. If neither
        // lookup finds a target, drop the push and warn; the user can refresh
        // the page to retry.
        val fallbackConnectionId = WorldManager.playerStats
            .firstOrNull { it.accelByteUserId == userId }
            ?.playerID
            ?: ""
        if (fallbackConnectionId.isBlank())
        {
            Logger.warn(
                LogCategory.NETWORK,
                "UiSignalRpcHandlers.notifyResumeAvailable: no WS session for userId=$userId; modal push dropped (user will need to refresh)"
            )
            return
        }
        val fallbackSessions = connectionManager?.findAllSessions(fallbackConnectionId) ?: emptyList()
        if (fallbackSessions.isNotEmpty())
        {
            val element = RpcJson.encodeToJsonElement(ResumeAvailabilityNotification.serializer(), payload)
            val notification = RpcMessage.Notification("client.resumeAvailable", element)
            fallbackSessions.forEach { it.sendRpcMessage(notification) }
            Logger.info(
                LogCategory.NETWORK,
                "UiSignalRpcHandlers.notifyResumeAvailable: pushed (fallback via playerStats.playerID) to userId=$userId connectionId=$fallbackConnectionId round=${payload.worldRound} hasAi=${payload.hasAi}"
            )
        }
        else
        {
            Logger.warn(
                LogCategory.NETWORK,
                "UiSignalRpcHandlers.notifyResumeAvailable: sessions for connectionId=$fallbackConnectionId not found (userId=$userId); modal push dropped"
            )
        }
    }

    /**
     * Broadcasts an explicit request to force all clients to switch their view to the Turn Resolution Widget.
     */
    suspend fun broadcastForceShowTurnResolution()
    {
        Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers: broadcasting force show turn resolution")
        val data = ForceShowTurnResolutionData(true)
        broadcastNotification("ui.forceShowTurnResolution", data)
    }

    /**
     * Server-side registration of a Web Push subscription created in the browser.
     *
     * The KVision client's [org.ttt.autogenesis.kvisionapp.PushNotificationService]
     * calls this via the WebSocket RPC bridge after a successful
     * `pushManager.subscribe()` and a user gesture (Play button click). The
     * subscription is stored in the VFS keyed by the calling user's AccelByte
     * id so future turn-start triggers can send a push when the WS session
     * is absent.
     *
     * Failures here must NOT crash the game server — push registration is
     * best-effort and the player can still play without notifications.
     */
    @RpcMethod("client.registerPushSubscription", RpcDirection.SERVER)
    suspend fun registerPushSubscription(ctx: RpcCallContext, payload: structs.push.PushSubscriptionDto)
    {
        val accelByteId = connectionManager
            ?.findSession(ctx.connectionId)
            ?.accelbyteId
            .orEmpty()
        if (accelByteId.isBlank())
        {
            Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers.registerPushSubscription: no AccelByte id for connectionId=${ctx.connectionId} — push subscription dropped")
            return
        }
        val store = pushSubscriptionStore
        if (store == null)
        {
            Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers.registerPushSubscription: store not configured — push subscription for user=$accelByteId dropped")
            return
        }
        try
        {
            store.put(accelByteId, payload)
            Logger.info(LogCategory.NETWORK, "UiSignalRpcHandlers.registerPushSubscription: stored subscription for user=$accelByteId endpoint=${payload.endpoint.take(60)}")
        }
        catch (e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "UiSignalRpcHandlers.registerPushSubscription: store failed for user=$accelByteId: ${e.message}")
        }
    }
}
