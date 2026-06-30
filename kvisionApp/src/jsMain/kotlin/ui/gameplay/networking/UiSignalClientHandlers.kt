package ui.gameplay.networking

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.AgentStreamPhase
import org.ttt.autogenesis.network.CommandClassificationData
import org.ttt.autogenesis.network.OpenWidgetData
import org.ttt.autogenesis.network.RpcMessage.AgentWorkStreamData
import org.ttt.autogenesis.audio.AudioSyncState
import org.ttt.autogenesis.audio.AudioReportState
import org.ttt.autogenesis.network.CommandInteractiveData
import org.ttt.autogenesis.network.NarrativeChunkData
import org.ttt.autogenesis.network.NarrativeUpdateData
import org.ttt.autogenesis.network.ProgressBarData
import org.ttt.autogenesis.network.PrepareStoryData
import org.ttt.autogenesis.network.ResolutionStep
import org.ttt.autogenesis.network.ResolutionStepData
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod
import org.ttt.autogenesis.network.SetLocalPlayerData
import org.ttt.autogenesis.network.ShowMessageBoxData
import org.ttt.autogenesis.network.NemesisThreatAnnouncementData
import org.ttt.autogenesis.network.ActiveTurnData
import org.ttt.autogenesis.network.TurnOrderAnnouncementData
import ui.gameplay.AudioSettings
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import globals.KEnv
import ui.MessageBox
import ui.gameplay.TurnResolutionWidget
import ui.gameplay.AgentWorkStreamManager

/**
 * Client-side hooks that allow the UI to react to real-time signals emitted by the server orchestrators.
 */
object UiSignalClientHandlers
{
    private var widget: TurnResolutionWidget? = null

    private var gameplayUI: ui.gameplay.GameplayUI? = null

    private var pendingMapPack: ByteArray? = null
    private var pendingWorld: structs.World? = null
    private var pendingWorldAfterMapLoad: structs.World? = null
    private var pendingTurnOrderAnnouncement: TurnOrderAnnouncementData? = null

    /** Buffer for nemesis threat announcements that arrive before the UI is ready. */
    private var pendingNemesisThreatAnnouncement: NemesisThreatAnnouncementData? = null

    /** Buffer for audio sync state that arrives before the audio engine is ready. */
    private var pendingAudioSyncState: AudioSyncState? = null

    /**
     * Attaches a [TurnResolutionWidget] so it can be controlled by server signals.
     *
     * @param turnWidget The widget to attach for signal handling.
     */
    fun attachWidget(turnWidget: TurnResolutionWidget)
    {
        widget = turnWidget
    }



    /**
     * Attaches the main GameplayUI orchestration.
     */
    fun attachGameplayUI(ui: ui.gameplay.GameplayUI)
    {
        Logger.info(LogCategory.NETWORK, ">>> [CLIENT] attachGameplayUI [ENTER]")
        
        // Reset buffering state for new UI attachment
        if(gameplayUI != ui)
        {
            Logger.info(LogCategory.NETWORK, ">>> [CLIENT] New GameplayUI instance detected, resetting buffering state")
            pendingMapPack = null
            pendingWorld = null
            pendingWorldAfterMapLoad = null
            pendingTurnOrderAnnouncement = null
            pendingNemesisThreatAnnouncement = null
        }

        Logger.info(LogCategory.NETWORK, ">>> [CLIENT] attachGameplayUI: pendingMapPack=${if(pendingMapPack != null) "SET" else "NULL"}")
        Logger.info(LogCategory.NETWORK, ">>> [CLIENT] attachGameplayUI: pendingWorld=${if(pendingWorld != null) "SET" else "NULL"}")
        Logger.info(LogCategory.NETWORK, ">>> [CLIENT] attachGameplayUI: pendingTurnOrderAnnouncement=${if(pendingTurnOrderAnnouncement != null) "SET" else "NULL"}")
        gameplayUI = ui

        MainScope().launch {
            Logger.info(LogCategory.NETWORK, ">>> [CLIENT] attachGameplayUI: Flush coroutine starting")
            pendingMapPack?.let { bytes ->
                Logger.info(LogCategory.NETWORK, ">>> [CLIENT] FLUSH: Loading pending map pack")
                ui.mapViewer?.loadMapPack(bytes)
                pendingMapPack = null
            } ?: Logger.info(LogCategory.NETWORK, ">>> [CLIENT] FLUSH: No pending map pack")

            // Only flush pendingWorld if map is already loaded; otherwise buffer it
            if (ui.mapViewer?.isMapLoaded == true)
            {
                pendingWorld?.let { world ->
                    Logger.info(LogCategory.NETWORK, ">>> [CLIENT] FLUSH: Loading pending world state")
                    ui.updateWorldState(world)
                    pendingWorld = null
                } ?: Logger.info(LogCategory.NETWORK, ">>> [CLIENT] FLUSH: No pending world")
            }
            else
            {
                Logger.info(LogCategory.NETWORK, ">>> [CLIENT] FLUSH: Map not loaded yet, moving pendingWorld to pendingWorldAfterMapLoad")
                pendingWorldAfterMapLoad = pendingWorld
                pendingWorld = null
            }

            pendingTurnOrderAnnouncement?.let { data ->
                Logger.info(LogCategory.NETWORK, ">>> [CLIENT] FLUSH: Flushing pending turn order announcement")
                ui.showTurnResolution()
                widget?.showTurnOrderAnnouncement(data)
                pendingTurnOrderAnnouncement = null
            } ?: Logger.info(LogCategory.NETWORK, ">>> [CLIENT] FLUSH: No pending turn order")

            pendingNemesisThreatAnnouncement?.let { data ->
                Logger.info(LogCategory.NETWORK, ">>> [CLIENT] FLUSH: Flushing pending nemesis threat announcement for ${data.nemesisName}")
                ui.showTurnResolution()
                widget?.showNemesisThreatAnnouncement(data)
                pendingNemesisThreatAnnouncement = null
            } ?: Logger.info(LogCategory.NETWORK, ">>> [CLIENT] FLUSH: No pending nemesis threat")

            // audio.syncState is the LAST frame the server sends in the initial-sync
            // sequence (after loadMapPack + updateWorld + history). For resume
            // (sendInitialSync from GameRestoreRpcHandlers.restoreRunningGame) the
            // loadMapPack/updateWorld frames are CHUNKED over hundreds of WS messages
            // and the audio.syncState frame arrives after the chunks have started
            // reassembling. The previous implementation ran the flush immediately
            // after setLocalPlayer, so by the time chunks finished assembling the
            // flush had already completed and pendingAudioSyncState was a stale
            // field nobody would read again. Wait up to 10s for the audio frame to
            // arrive; if it does, reconcile. If it doesn't, log and continue (the
            // user can still hear music on the next turn).
            val audioWaitStart = kotlinx.browser.window.performance.now().toLong()
            val audioWaitDeadlineMs = 10_000L
            while (pendingAudioSyncState == null && (kotlinx.browser.window.performance.now().toLong() - audioWaitStart) < audioWaitDeadlineMs)
            {
                kotlinx.coroutines.delay(100)
            }
            if (pendingAudioSyncState != null)
            {
                val data = pendingAudioSyncState!!
                Logger.info(LogCategory.NETWORK, ">>> [CLIENT] FLUSH: Flushing pending audio sync state (arrived after ${kotlinx.browser.window.performance.now().toLong() - audioWaitStart}ms)")
                org.ttt.autogenesis.kvisionapp.audio.AudioEngine.reconcileWithSnapshot(data)
                // [Bug fix] The server's audio.syncState snapshot includes
                // volume=1 for every channel. The user-controlled Music
                // and Sfx channels come from the localStorage slider
                // values; re-apply them so the server snapshot does not
                // clobber the user's chosen volume. See
                // [AudioClientHandlers.handleSyncState] for the primary
                // re-apply site; this is the replay-when-late-joined
                // path and needs the same re-apply. The function is
                // idempotent.
                Logger.debug(LogCategory.NETWORK, ">>> [CLIENT] FLUSH: re-applying user-controlled audio channels (Music, Sfx) after server snapshot reconcile")
                AudioSettings.applyPersistedToEngine()
                pendingAudioSyncState = null
            }
            else
            {
                Logger.warn(LogCategory.NETWORK, ">>> [CLIENT] FLUSH: No pending audio sync state after ${audioWaitDeadlineMs}ms wait — audio will resync on next turn")
            }
            Logger.info(LogCategory.NETWORK, "<<< [CLIENT] attachGameplayUI: Flush complete")
        }
        Logger.info(LogCategory.NETWORK, "<<< [CLIENT] attachGameplayUI [EXIT]")
    }
    
    /**
     * Dummy method for backward compatibility if needed, or better to remove.
     * We'll add this to satisfy any lingering calls but logically it does nothing as Manager is singleton.
     */
    fun attachNeuralLinkManager()
    {
        // No-op, NeuralLinkManager is a singleton and initialized by GameplayUI
    }

    /**
     * Handles the map pack loading signal.
     */
    @RpcMethod(name = "ui.loadMapPack", direction = RpcDirection.CLIENT)
    suspend fun handleLoadMapPack(_ctx: RpcCallContext, data: org.ttt.autogenesis.network.MapLoadInstruction)
    {
        val startTime = kotlinx.browser.window.performance.now()
        Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleLoadMapPack [ENTER] bytes=${data.mapPackBytes.size}")
        Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleLoadMapPack: connectionId=${_ctx.connectionId}")
        Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleLoadMapPack: gameplayUI=${if(gameplayUI != null) "ATTACHED" else "NULL"}")
        Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleLoadMapPack: widget=${if(widget != null) "ATTACHED" else "NULL"}")
        val ui = gameplayUI
        if(ui != null)
        {
            Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleLoadMapPack: UI attached, loading map pack into MapViewer")
            ui.mapViewer?.loadMapPack(data.mapPackBytes)
            Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleLoadMapPack: MapViewer.loadMapPack called")

            // After map loads, flush any pending world state that arrived before map was ready
            pendingWorldAfterMapLoad?.let { world ->
                Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleLoadMapPack: Flushing pendingWorldAfterMapLoad after map load")
                ui.updateWorldState(world)
                pendingWorldAfterMapLoad = null
            }
        }
        else
        {
            Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleLoadMapPack: GameplayUI not attached, buffering map pack")
            pendingMapPack = data.mapPackBytes
        }
        val duration = kotlinx.browser.window.performance.now() - startTime
        Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleLoadMapPack [EXIT] took ${duration.asDynamic().toFixed(2)}ms")
    }

    /**
     * Handles the transition to a specific resolution step.
     * 
     * **Flow**: Server calls `UiSignalRpcHandlers.broadcastResolutionStep` -> Client receives here -> UI updates.
     * 
     * This drives the main state machine of the [TurnResolutionWidget].
     *
     * @param _ctx The RPC call context.
     * @param data The resolution step data containing the step enum and optional message.
     */
    @RpcMethod(name = "ui.setResolutionStep", direction = RpcDirection.CLIENT)
    suspend fun handleSetResolutionStep(_ctx: RpcCallContext, data: ResolutionStepData)
    {
        Logger.debug(LogCategory.NETWORK, "UiSignalClientHandlers: Switching to step ${data.step} (index=${data.step.index})")
        Logger.debug(LogCategory.NETWORK, "[UiSignals] 🟢 Received setResolutionStep: ${data.step} (index=${data.step.index})")
        Logger.debug(LogCategory.NETWORK, "[UiSignals] Payload Details: " + JSON.stringify(data))
        
        // Skip forcing the Turn Resolution Widget view if we are moving to UPDATE_WORLD, 
        // as that step explicitly switches back to the map view to show territory changes.
        if (data.step != ResolutionStep.UPDATE_WORLD)
        {
            gameplayUI?.showTurnResolution()
        }
        else
        {
            widget?.syncMapForWorldUpdate()
        }
        
        widget?.showStep(data.step.index)
        
        // Handling message payload
        if (data.step == ResolutionStep.PLAYER_ACTION && !data.message.isNullOrEmpty())
        {
            widget?.updatePlayerActionText(data.message!!)
        }
        else if (data.step == ResolutionStep.INTENT && !data.message.isNullOrEmpty())
        {
            widget?.updateIntentText(data.message!!)
        }
        else if (data.step == ResolutionStep.COUNTER_PLAY)
        {
             // Only trigger prompt if we are actually in counter-play step
             data.message?.let { widget?.promptCommandResponse() }
        }
    }

    /**
     * Handles progress bar updates.
     *
     * @param _ctx The RPC call context.
     * @param data The progress bar data containing active index and instruction.
     */
    @RpcMethod(name = "ui.updateProgressBar", direction = RpcDirection.CLIENT)
    suspend fun handleUpdateProgressBar(_ctx: RpcCallContext, data: ProgressBarData)
    {
        Logger.debug(LogCategory.NETWORK, "UiSignalClientHandlers: Updating progress bar activeIndex=${data.activeIndex}")
        Logger.debug(LogCategory.NETWORK, "[UiSignals] 📊 Received updateProgressBar: Index=${data.activeIndex}, Text='${data.instruction}'")

        // Sync the widget page to the progress bar to prevent 'page mismatch'
        widget?.showStep(data.activeIndex)

        widget?.setProgressBarState(data.activeIndex, data.instruction)
    }

    /**
     * Handles narrative chunks for streaming text.
     * 
     * Used exclusively by the [ui.gameplay.TurnResolutionWidget.StoryStreamingPage] to display text character-by-character
     * as it is generated by the LLM on the server.
     *
     * @param _ctx The RPC call context.
     * @param data The narrative chunk data containing the text chunk.
     */
    @RpcMethod(name = "ui.narrativeChunk", direction = RpcDirection.CLIENT)
    suspend fun handleNarrativeChunk(_ctx: RpcCallContext, data: NarrativeChunkData)
    {
        Logger.debug(LogCategory.NETWORK, "UiSignalClientHandlers: Received narrative chunk size=${data.chunk.length}, isComplete=${data.isComplete}")
        widget?.updateNarrative(data.chunk)
        if(data.isComplete)
        {
            // Optional: Handle completion in widget if needed
        }
    }

    /**
     * Handles narrative updates (rewrites).
     * 
     * Triggered when a narrative refinement pipeline (e.g. [agent.builders.modifyGameState.buildReverseAgent]) changes the story
     * retroactively (e.g. flip from success to fail). The UI should play a "glitch" or wipe animation.
     *
     * @param _ctx The RPC call context.
     * @param data The narrative update data containing the new text.
     */
    @RpcMethod(name = "ui.narrativeUpdate", direction = RpcDirection.CLIENT)
    suspend fun handleNarrativeUpdate(_ctx: RpcCallContext, data: NarrativeUpdateData)
    {
        Logger.debug(LogCategory.NETWORK, "UiSignalClientHandlers: Received narrative update (rewrite). Text length=${data.text.length}")
        widget?.overwriteNarrative(data.text)
    }

    /**
     * Handles signal to wipe the story screen.
     */
    @RpcMethod(name = "ui.prepareStory", direction = RpcDirection.CLIENT)
    suspend fun handlePrepareStory(_ctx: RpcCallContext, data: PrepareStoryData)
    {
        Logger.debug(LogCategory.NETWORK, "UiSignalClientHandlers: Received Prepare Story signal. Style=${data.style}")
        widget?.prepareForNewStory()
    }

    /**
     * Handles command interactivity status.
     *
     * @param _ctx The RPC call context.
     * @param data The command interactive data containing the interactive flag.
     */
    @RpcMethod(name = "ui.setCommandInteractive", direction = RpcDirection.CLIENT)
    suspend fun handleSetCommandInteractive(_ctx: RpcCallContext, data: CommandInteractiveData)
    {
        Logger.debug(LogCategory.NETWORK, "UiSignalClientHandlers: Setting command interactive=${data.interactive}")
        Logger.debug(LogCategory.NETWORK, "[UiSignals] 🎮 Received setCommandInteractive: ${data.interactive}")
        widget?.onSetCommandInteractive?.invoke(data.interactive)
    }

    /**
     * Handles full world state updates.
     *
     * @param _ctx The RPC call context.
     * @param data The world update data.
     */
    @RpcMethod(name = "ui.updateWorld", direction = RpcDirection.CLIENT)
    suspend fun handleUpdateWorld(_ctx: RpcCallContext, data: org.ttt.autogenesis.network.WorldUpdateData)
    {
         val startTime = kotlinx.browser.window.performance.now()
         Logger.info(LogCategory.NETWORK, "[TRACE] [UiSignalClientHandlers.handleUpdateWorld] Received updateWorld")
         Logger.info(LogCategory.NETWORK, "[TRACE] [UiSignalClientHandlers.handleUpdateWorld] Round: ${data.world.roundNumber}")
         Logger.info(LogCategory.NETWORK, "[TRACE] [UiSignalClientHandlers.handleUpdateWorld] Human Players: ${data.world.activePlayers.size}")
         data.world.activePlayers.forEach { p ->
             Logger.info(LogCategory.NETWORK, "[TRACE] [UiSignalClientHandlers.handleUpdateWorld]   - Player: '${p.name}', isControlledByNpc: ${p.getInternals().isNpc}")
         }
         Logger.info(LogCategory.NETWORK, "[TRACE] [UiSignalClientHandlers.handleUpdateWorld] NPCs: ${data.world.npc.size}")

         val ui = gameplayUI ?: run {
             Logger.warn(LogCategory.NETWORK, "[TRACE] [UiSignalClientHandlers.handleUpdateWorld] FAILED: gameplayUI is null! Buffering world update.")
             pendingWorld = data.world
             return
         }

         // Only force the map-lock + page-switch when the TurnResolutionWidget
         // is actually visible. `syncMapForWorldUpdate()` is designed for the
         // UPDATE_WORLD step of the turn resolution, NOT for arbitrary world
         // updates like delegate-instructions saves. If the map is already
         // showing, calling it would briefly flash the TurnResolutionWidget's
         // "Go To Map" page (page 7) before snapping back to the map.
         val isMapAlreadyShowing = ui.centerStackPanel?.activeIndex == 0
         if (!isMapAlreadyShowing)
         {
             widget?.syncMapForWorldUpdate()
         }

         ui.updateWorldState(data.world)
         val duration = kotlinx.browser.window.performance.now() - startTime
         Logger.info(LogCategory.NETWORK, "[PERF] [CLIENT] handleUpdateWorld took ${duration.asDynamic().toFixed(2)}ms")
    }

    @RpcMethod(name = "ui.announceTurnOrder", direction = RpcDirection.CLIENT)
    suspend fun handleTurnOrderAnnouncement(_ctx: RpcCallContext, data: TurnOrderAnnouncementData)
    {
        Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleTurnOrderAnnouncement [ENTER] round=${data.roundNumber}")
        Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleTurnOrderAnnouncement: connectionId=${_ctx.connectionId}")
        Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleTurnOrderAnnouncement: gameplayUI=${if(gameplayUI != null) "ATTACHED" else "NULL"}")
        Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleTurnOrderAnnouncement: widget=${if(widget != null) "ATTACHED" else "NULL"}")
        val ui = gameplayUI
        if(ui != null)
        {
            Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleTurnOrderAnnouncement: UI attached, calling showTurnResolution() and showTurnOrderAnnouncement()")
            ui.showTurnResolution()
            widget?.showTurnOrderAnnouncement(data)
            Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleTurnOrderAnnouncement: showTurnOrderAnnouncement called")
        }
        else
        {
            Logger.info(LogCategory.NETWORK, ">>> [CLIENT] handleTurnOrderAnnouncement: GameplayUI not attached, buffering turn order announcement")
            pendingTurnOrderAnnouncement = data
        }
        Logger.info(LogCategory.NETWORK, "<<< [CLIENT] handleTurnOrderAnnouncement [EXIT]")
    }

    @RpcMethod(name = "ui.announceNemesisThreat", direction = RpcDirection.CLIENT)
    suspend fun handleNemesisThreatAnnouncement(_ctx: RpcCallContext, data: NemesisThreatAnnouncementData)
    {
        Logger.info(
            LogCategory.NETWORK,
            "UiSignalClientHandlers: Received nemesis threat for Round ${data.roundNumber} (${data.kind}) - ${data.nemesisName}"
        )
        Logger.debug(LogCategory.NETWORK, "gameplayUI=${if(gameplayUI != null) "attached" else "NULL"}, widget=${if(widget != null) "attached" else "NULL"}")
        gameplayUI?.showTurnResolution()
        val w = widget
        if(w != null)
        {
            w.showNemesisThreatAnnouncement(data)
            Logger.info(LogCategory.NETWORK, "showNemesisThreatAnnouncement called for ${data.nemesisName}")
        }
        else
        {
            Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: widget not attached, buffering nemesis threat announcement")
            pendingNemesisThreatAnnouncement = data
        }
    }

    /**
     * Processes an active turn notification and refreshes the UI workflow.
     *
     * @param _ctx RPC call context that delivered the notification.
     * @param data Metadata describing the actor currently acting.
     */
    @RpcMethod(name = "ui.activeTurn", direction = RpcDirection.CLIENT)
    suspend fun handleActiveTurn(_ctx: RpcCallContext, data: ActiveTurnData)
    {
        val localName = globals.World.localPlayer.name
        val isLocal = localName.equals(data.actorName, ignoreCase = true)
        Logger.info(
            LogCategory.NETWORK,
            "UiSignalClientHandlers: Active turn actor=${data.actorName} (local=${isLocal}) round=${data.roundNumber} index=${data.turnIndex}"
        )
        gameplayUI?.showTurnResolution()
        widget?.showActiveTurn(data)
    }

    /**
     * Handles prompt status updates.
     * 
     * @param _ctx The RPC call context.
     * @param data The agent usage data.
     */
    @RpcMethod(name = "ui.updatePromptStatus", direction = RpcDirection.CLIENT)
    suspend fun handleUpdatePromptStatus(_ctx: RpcCallContext, data: structs.AgentUsage)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: Received Prompt Status Update (Classifier: ${data.runningClassifier})")
        gameplayUI?.updatePromptStatus(data)
    }

    /**
     * Handles agent streaming data.
     */
    @RpcMethod(name = "ui.agentStream", direction = RpcDirection.CLIENT)
    suspend fun handleAgentStream(_ctx: RpcCallContext, data: org.ttt.autogenesis.network.RpcMessage.AgentStreamData)
    {
        val streamId = if (data.streamId.isNotBlank()) data.streamId else "legacy-${data.tabId}"

        when (data.phase) {
            AgentStreamPhase.START -> {
                ui.gameplay.NeuralLinkManager.handleAgentStreamStart(data.tabId, streamId, data.commandContext)
            }

            AgentStreamPhase.DELTA -> {
                val delta = if (data.delta.isNotEmpty()) data.delta else data.content
                ui.gameplay.NeuralLinkManager.handleAgentStreamDelta(
                    tabId = data.tabId,
                    streamId = streamId,
                    sequence = data.sequence,
                    delta = delta
                )
            }

            AgentStreamPhase.END -> {
                ui.gameplay.NeuralLinkManager.handleAgentStreamEnd(data.tabId, streamId, data.finalText)
            }

            AgentStreamPhase.ERROR -> {
                val errorText = data.finalText ?: data.delta.ifBlank { data.content.ifBlank { "Stream error." } }
                ui.gameplay.NeuralLinkManager.handleAgentStreamError(data.tabId, streamId, errorText)
            }

            AgentStreamPhase.LEGACY -> {
                ui.gameplay.NeuralLinkManager.handleLegacyAgentStream(
                    tabId = data.tabId,
                    content = data.content,
                    isComplete = data.isComplete,
                    commandContext = data.commandContext
                )
            }
        }
    }

    /**
     * Receives classification results produced by [PromptManager] so the UI knows whether to display turn resolution.
     */
    @RpcMethod(name = "ui.commandClassification", direction = RpcDirection.CLIENT)
    suspend fun handleCommandClassification(_ctx: RpcCallContext, data: CommandClassificationData)
    {
        gameplayUI?.handleCommandClassification(data)
    }

    @RpcMethod(name = "ui.agentWorkStream", direction = RpcDirection.CLIENT)
    suspend fun handleAgentWorkStream(_ctx: RpcCallContext, data: AgentWorkStreamData)
    {
        AgentWorkStreamManager.handleStream(data)
    }

    /**
     * Handles real-time "thinking" updates broadcast from the server.
     *
     * @param _ctx The RPC call context.
     * @param data The thinking update data containing playerId, characterName, thinking text, and timestamp.
     */
    @RpcMethod(name = "ui.thinkingUpdate", direction = RpcDirection.CLIENT)
    suspend fun handleThinkingUpdate(_ctx: RpcCallContext, data: org.ttt.autogenesis.network.ThinkingUpdateData)
    {
        Logger.debug(LogCategory.NETWORK, "UiSignalClientHandlers: Received thinking update for ${data.characterName}")
        ActionHistoryClientHandlers.routeThinkingToGameHistory(data)
        ActionHistoryClientHandlers.flashDetailsTab()
    }
    
    @RpcMethod(name = "ui.openNeuralLink", direction = RpcDirection.CLIENT)
    suspend fun handleOpenNeuralLink(_ctx: RpcCallContext, open: Boolean)
    {
         Logger.debug(LogCategory.NETWORK, "UiSignalClientHandlers: Handling Open Neural Link Signal - Opening Default Channel")
         Logger.debug(LogCategory.NETWORK, "[UiSignals] 🧠 Received openNeuralLink")
         ui.gameplay.NeuralLinkManager.openWindow("Commander", "COMMANDER")
    }

    @RpcMethod(name = "ui.openWidget", direction = RpcDirection.CLIENT)
    suspend fun handleOpenWidget(_ctx: RpcCallContext, data: OpenWidgetData)
    {
        gameplayUI?.openWidget(data)
    }

    /**
     * Responds to ping requests from the server to verify reachability.
     */
    @RpcMethod(name = "client.ping", direction = RpcDirection.CLIENT)
    suspend fun handlePing(_ctx: RpcCallContext): org.ttt.autogenesis.network.PingResponse
    {
        Logger.debug(LogCategory.NETWORK, "UiSignalClientHandlers: Received ping from server")
        return org.ttt.autogenesis.network.PingResponse(
            echo = "pong",
            timestamp = kotlin.js.Date.now().toLong()
        )
    }

    /**
     * Handles the request to show a message box.
     */
    @RpcMethod(name = "ui.showMessageBox", direction = RpcDirection.CLIENT)
    suspend fun handleShowMessageBox(_ctx: RpcCallContext, data: ShowMessageBoxData)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: RECEIVED 'ui.showMessageBox' RPC - Title: ${data.title}, Message: ${data.message}")
        val box = MessageBox(
            boxTitle = data.title,
            message = data.message,
            showOk = data.showOk,
            showCancel = data.showCancel,
            showThrobber = data.showThrobber
        )
        gameplayUI?.let { gui ->
            gui.add(box)
        } ?: run {
            // No gameplay UI yet (pre-matchmaking or transitional state).
            // Show on the main root so the dialog is never silently dropped.
            Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: Showing MessageBox on mainRoot (gameplayUI not yet initialized)")
            KEnv.mainRoot?.add(box)
        }
    }

    /**
     * Handles the counter-play prompt signal.
     */
    /**
     * Handles server-side counter-play prompts by showing the TurnResolution counter UI.
     *
     * The intent classification (Hostile/Friendly) coming in `data.actionIntent` drives the counter UI styling.
     *
     * @param data Contains the attacker name, description, and the intent classification sent by the server.
     */
    @RpcMethod(name = "ui.counterPlayPrompt", direction = RpcDirection.CLIENT)
    suspend fun handleCounterPlayPrompt(_ctx: RpcCallContext, data: org.ttt.autogenesis.network.CounterPlayPrompt)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: Received Counter Play Prompt from ${data.attackerName}: ${data.actionDescription} (Intent: ${data.actionIntent})")
        Logger.debug(LogCategory.NETWORK, "[UiSignals] ⚠️ Received counterPlayPrompt")
        gameplayUI?.showTurnResolution()
        gameplayUI?.setCounterPlayMode(true)
        widget?.showCounterPlay(data.attackerName, data.actionDescription, data.actionIntent)
    }

    /**
     * Handles the judgement result summary.
     */
    @RpcMethod(name = "ui.judgementResult", direction = RpcDirection.CLIENT)
    suspend fun handleJudgementResult(_ctx: RpcCallContext, data: org.ttt.autogenesis.network.JudgementEffectData)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: Received Judgement Effect - Success: ${data.isSuccess}, Header: ${data.header}, Subtext Length: ${data.subtext.length}")
        Logger.debug(LogCategory.NETWORK, "[UiSignals] ⚖️ Received judgementResult: Success=${data.isSuccess}, Header='${data.header}', Subtext='${data.subtext}'")
        
        if(widget == null)
        {
            Logger.error(LogCategory.NETWORK, "[UiSignals] ❌ CRITICAL: widget is NULL. Cannot update Judgement Result.")
        }
        else
        {
             Logger.debug(LogCategory.NETWORK, "[UiSignals] ✅ widget is attached. Calling updateJudgementResult.")
        }

        gameplayUI?.showTurnResolution()
        widget?.updateJudgementResult(data.isSuccess, data.header, data.subtext)
    }

    /**
     * Handles the Summit result broadcast from the server.
     *
     * When a Summit completes, this handler receives the result including stat changes
     * and triggers the ScoreDisplay to animate the summit point updates.
     *
     * @param _ctx The RPC call context.
     * @param data The Summit result data containing success status, narrative, and stat changes.
     */
    @RpcMethod(name = "ui.summitResult", direction = RpcDirection.CLIENT)
    suspend fun handleSummitResult(_ctx: RpcCallContext, data: org.ttt.autogenesis.network.SummitResultData)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: Received Summit Result - Success: ${data.success}")
        Logger.debug(LogCategory.NETWORK, "[UiSignals] 🏔️ Received summitResult: success=${data.success}, changes=${data.statChanges.size} players affected")

        val localPlayerName = globals.World.localPlayer.name

        // Check if local player's stats were affected by this Summit
        val localPlayerChanges = data.statChanges[localPlayerName]
        if (localPlayerChanges != null)
        {
            Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: Local player '$localPlayerName' affected by Summit - Changes: $localPlayerChanges")

            // Parse the stat change description to determine if it's a gain or loss
            // The format could be like "+10 Military, +5 Summit" or "-3 Military"
            val isGain = !localPlayerChanges.startsWith("-")

            // Trigger the Summit panel flash animation to indicate point change
            gameplayUI?.scoreDisplay?.triggerSummitFlash(isGain)
        }
        else
        {
            Logger.debug(LogCategory.NETWORK, "UiSignalClientHandlers: Local player not directly affected by Summit")
        }

        // Show turn resolution to display the Summit result narrative
        gameplayUI?.showTurnResolution()
    }

    /**
     * Handles the turn timer update signal from the server.
     */
    @RpcMethod(name = "ui.updateTurnTimer", direction = RpcDirection.CLIENT)
    suspend fun handleUpdateTurnTimer(_ctx: RpcCallContext, data: org.ttt.autogenesis.network.TurnTimerUpdateData)
    {
        Logger.debug(LogCategory.NETWORK, "UiSignalClientHandlers: Received turn timer update - remaining=${data.remainingSeconds}")
        Logger.debug(LogCategory.NETWORK, "[UiSignals] ⏱️ Received updateTurnTimer: ${data.remainingSeconds}s")
        gameplayUI?.scoreDisplay?.updateTurnTimer(data.remainingSeconds, data.totalDuration)
    }
    /**
     * Handles the dispatch result data.
     */
    @RpcMethod(name = "ui.dispatchResult", direction = RpcDirection.CLIENT)
    suspend fun handleDispatchResult(_ctx: RpcCallContext, data: org.ttt.autogenesis.network.DispatchData)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: Received Dispatch Result")
        Logger.debug(LogCategory.NETWORK, "[UiSignals] 🚚 Received dispatchResult: " + JSON.stringify(data))
        
        gameplayUI?.showTurnResolution()
        widget?.updateDispatchData(data)
    }

    /**
     * Handles an intent update signal.
     */
    @RpcMethod(name = "ui.updateIntent", direction = RpcDirection.CLIENT)
    suspend fun handleUpdateIntent(_ctx: RpcCallContext, data: org.ttt.autogenesis.network.UpdateIntentData)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: Received Intent Update: '${data.intent}'")
        Logger.debug(LogCategory.NETWORK, "[UiSignals] 📝 Received updateIntent: '${data.intent}'")
        
        // Update both Page 1 (Player Action - currently visible) and Page 2 (Intent - next)
        widget?.updatePlayerActionText(data.intent)
        widget?.updateIntentText(data.intent)
    }

    /**
     * Handles the signal to set the local player identity.
     */
    @RpcMethod(name = "ui.setLocalPlayer", direction = RpcDirection.CLIENT)
    suspend fun handleSetLocalPlayer(_ctx: RpcCallContext, data: SetLocalPlayerData)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: Received Set Local Player - ${data.player.name}")
        globals.World.localPlayer = data.player

        // Ensure GameplayUI is displayed before updating world state
        // When browser connects late (after GAME_STARTED signal was already consumed),
        // this is the first entry point that knows a game session exists for this player.
        // Trigger GameplayUI creation if not already present.
        if(gameplayUI == null)
        {
            Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: GameplayUI not yet attached, triggering from setLocalPlayer")
            ui.DebugConsole.triggerGameStarted()
        }

        // Trigger a UI refresh if world data is already present
        if(globals.World.worldData.roundNumber > 0)
        {
            gameplayUI?.updateWorldState(globals.World.worldData)
        }
    }

    /**
     * Handles the game conclusion signal.
     */
    @RpcMethod(name = "ui.gameOver", direction = RpcDirection.CLIENT)
    suspend fun handleGameOver(_ctx: RpcCallContext, data: org.ttt.autogenesis.network.GameOverData)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: Received Game Over - Winner: ${data.winnerName}")
        gameplayUI?.showGameEnd(data)
    }

    /**
     * Handles the explicit request to force the Turn Resolution Widget to show.
     */
    @RpcMethod(name = "ui.forceShowTurnResolution", direction = RpcDirection.CLIENT)
    suspend fun handleForceShowTurnResolution(_ctx: RpcCallContext, data: org.ttt.autogenesis.network.ForceShowTurnResolutionData)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: Received force show Turn Resolution Widget signal")
        gameplayUI?.showTurnResolution()
    }

    /**
     * Handles audio sync state from server (late join / reconnect).
     *
     * @param _ctx The RPC call context.
     * @param data The audio sync state snapshot.
     */
    @RpcMethod(name = "audio.syncState", direction = RpcDirection.CLIENT)
    suspend fun handleAudioSyncState(_ctx: RpcCallContext, data: AudioSyncState)
    {
        Logger.info(LogCategory.NETWORK, "UiSignalClientHandlers: Received audio.syncState - volume=${data.globalVolume}, channels=${data.channels.size}, objects=${data.scheduledObjects.size}")
        pendingAudioSyncState = data
    }
}
