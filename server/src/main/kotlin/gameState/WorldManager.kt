package gameState

import agent.builders.judgeOutcome.Results
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.ThinkingUpdateData
import org.ttt.autogenesis.server.ServerDrainingException
import org.ttt.autogenesis.server.audio.AudioTracksResourceLoader
import serverStructs.PlayerStats
import structs.GameHistory
import structs.Npc
import structs.Player
import structs.MapPackManager
import structs.WritingAgentConfig
import enums.ResourceType
import structs.Resource
import structs.Territory
import structs.World
import structs.findTerritoryByName
import structs.ui.ActionHistory
import structs.ui.ActionHistoryEvent
import structs.ui.GameEventType
import structs.ui.JudgeEventMetadata
import structs.ui.PlayerOutcomeMetadata
import structs.ui.ResourceEventMetadata
import structs.ui.TerritoryEventMetadata
import structs.ui.WorldRuleMetadata
import timer.CountdownTimer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Global object that contains a snapshot of the current world state. This contains the entire map,
 * every event on the map, and the entire global game status. The world will be updated as game events
 * are operated upon by the gameplay loop, and exists as a means to get an instant overview of
 * the state of the game world.
 */
object WorldManager
{
    /** Total duration in seconds granted to a player to complete their turn. */
    const val TURN_DURATION_SECONDS = 300L
    /**
     * Timeout used when waiting for player input; slightly padded to account for network latency.
     *
     * Debug override: setting `AUTOGENESIS_DEBUG_SHORT_TURN_TIMEOUT_MS` to a
     * positive long in the environment (or as a JVM system property) lowers
     * the effective timeout to that value. This is intended for e2e probe
     * runs (resume-preserves-round.mjs) that need the human's turn timer
     * to expire within a few seconds so `humanPlayerHasJoinedOnce` flips
     * true before the probe disconnects. Production builds never set this
     * env var.
     */
    const val TURN_TIMEOUT_MS = 302_000L
    fun effectiveTurnTimeoutMs(): Long
    {
        val raw = System.getProperty("AUTOGENESIS_DEBUG_SHORT_TURN_TIMEOUT_MS")
            ?: System.getenv("AUTOGENESIS_DEBUG_SHORT_TURN_TIMEOUT_MS")
        return raw?.toLongOrNull()?.takeIf { it > 0 } ?: TURN_TIMEOUT_MS
    }
    /** Duration in seconds for the counter-play response window. */
    const val COUNTERPLAY_DURATION_SECONDS = 180L
    /** millisecond timeout for counter-play response collection. */
    const val COUNTERPLAY_TIMEOUT_MS = 180_000L
    /** Summit points awarded per round when a Nemesis or ElderGod is present in the world. */
    const val SUMMIT_POINTS_PER_ROUND = 1
    /** Maximum summit points a player can accumulate. */
    const val MAX_SUMMIT_POINTS = 10
    /** Timeout in milliseconds for Summit response collection (3 minutes). */
    val SUMMIT_RESPONSE_TIMEOUT_MS = 180_000L

    /**
     * Critical safety mutex to lock access to the [World] class as need be when splitters are writing to it.
     * This delegates to the [World] instance's internal mutex to ensure thread safety.
     */
    val worldMutex: Mutex get() = world.mutex

    /** Determines if the game has started on this server yet or not */
    var isGameActive: Boolean = false

    /**
     * Returns `true` when no game has actually started on this server.
     *
     * Used by the single-player reconnect path
     * ([org.ttt.autogenesis.server.Server.onConnected]) as the gate for
     * auto-restore: if the world is empty AND the calling player has a
     * saved running-game snapshot, rehydrate before the initial sync.
     *
     * The predicate intentionally inspects only the round counter and the
     * history list — NOT [activePlayers]. A fresh server seeds a
     * placeholder "Player 1" into [activePlayers] at boot, so a naive
     * `activePlayers.isEmpty()` check would always be false and the
     * auto-restore never fires. The round/history invariant is the
     * canonical "no turns have run yet" signal.
     *
     * @return `true` when [roundNumber] is at or below the seed default
     *   (1) and no turn has appended to [history]. `false` once a turn
     *   has run.
     */
    fun isWorldEmpty(): Boolean
    {
        return world.roundNumber <= 1 && history.isEmpty()
    }

    /**
     * Authoritative race-recovery signal for the resume-game flow.
     *
     * Set by [TurnHarness.applyGameSnapshot] after a successful rehydrate,
     * cleared by [clearRehydratedFlag] (typically called from
     * [applyRestoredWorldAndSync] after the initial sync lands, or from
     * [clearRunningGameForUser] when the user explicitly starts a new game).
     *
     * Why this exists instead of relying on [isWorldEmpty]:
     * A game restored from a round-1 snapshot with no history is
     * indistinguishable from a fresh server with no game — both have
     * `roundNumber <= 1 && history.isEmpty()`. The race-recovery branch
     * in [GameRestoreRpcHandlers.restoreRunningGame] needs to distinguish
     * "the world was just rehydrated for this user" from "the world is
     * still pristine." This flag is the only place that distinction lives.
     *
     * @see GameRestoreRpcHandlers.isWorldAlreadyRestoredForUser
     */
    @Volatile
    var lastRehydratedAccelByteUserId: String? = null
        private set

    /**
     * Mark the world as just-restored from a snapshot for the given user.
     * Called from [TurnHarness.applyGameSnapshot] inside the success path.
     */
    fun markRehydratedFromSnapshot(accelByteUserId: String)
    {
        if (accelByteUserId.isNotBlank())
        {
            lastRehydratedAccelByteUserId = accelByteUserId
        }
    }

    /**
     * Set of accelbyteUserIds whose auto-restore coroutine is currently
     * running on the main server's `Dispatchers.IO`. Populated by
     * [Server.onConnected] before launching the restore coroutine and
     * removed once the coroutine completes (success, failure, or no-op).
     *
     * Exposed via [org.ttt.autogenesis.server.GameRestoreRpcHandlers.restoreStatus]
     * so server-extend's `ResumeAvailabilityPushService` can wait until
     * the restore is finished before pushing `client.resumeAvailable`.
     *
     * Without this set, server-extend would push the resume modal based on
     * a snapshot read milliseconds before the main server's auto-restore
     * consumes it, and the user's "Resume" click would then hit the
     * consumed-sentinel and produce "No saved game found."
     */
    private val activeRestores: java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Mark a user as having an in-flight auto-restore and return a
     * CompletableDeferred that the caller completes when the restore
     * finishes (success or failure). Used by `Server.onConnected`.
     */
    fun beginRestore(accelByteUserId: String): kotlinx.coroutines.CompletableDeferred<Unit>
    {
        val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
        activeRestores[accelByteUserId] = deferred
        return deferred
    }

    /**
     * Clear the in-flight marker for a user. The deferred returned from
     * [beginRestore] is completed just before this call. No-op if the
     * user has no active restore.
     */
    fun endRestore(accelByteUserId: String)
    {
        activeRestores.remove(accelByteUserId)
    }

    /**
     * True while a restore coroutine is in flight for this user.
     * Called by `GameRestoreRpcHandlers.restoreStatus`.
     */
    fun isRestoreInProgress(accelByteUserId: String): Boolean
    {
        return activeRestores.containsKey(accelByteUserId)
    }

    /**
     * Clear the rehydrated flag. Called from:
     *   - [applyRestoredWorldAndSync] after the initial sync notification
     *     is dispatched (the WS now knows the world is restored — no
     *     need to keep flagging it for race-recovery).
     *   - [clearRunningGameForUser] when the user explicitly discards
     *     the saved game (new-game / game-over paths).
     */
    fun clearRehydratedFlag()
    {
        lastRehydratedAccelByteUserId = null
    }

    /** Determines if the game is a single player game (1 human vs AI) */
    @Volatile
    var isSinglePlayer: Boolean = false

    /** The name of the primary human player in single player mode */
    @Volatile
    var humanPlayerName: String = ""

    /**
     * True once [org.ttt.autogenesis.server.TurnHarness.awaitPlayerAction] has
     * been reached for the human player and the player was reachable (i.e. the
     * human's WebSocket session existed and a turn could begin). Stays true
     * for the lifetime of the JVM so a session that joined-then-disconnected
     * is still recognized as "played" by later reconnect attempts.
     *
     * Gating point for save-on-disconnect at [org.ttt.autogenesis.server.Server.onDisconnected]:
     * without this flag, the save would fire on a GameInit-only disconnect
     * (server-extend bridge closed before the human joined) and persist a
     * never-played fresh-init snapshot to the user's cloud save record. The
     * user would then "resume" a phantom round-1 game with score=0 and no
     * history.
     */
    @Volatile
    var humanPlayerHasJoinedOnce: Boolean = false

    // ===== AccelByte AMS session binding state =====

    /** Guards session binding state against concurrent writes from DS Hub and ServerConnector paths. */
    private val sessionBindLock = Mutex()

    /** AccelByte session ID from MatchmakingV2ServerClaimed, set when DS is claimed. */
    @Volatile
    var activeSessionId: String = ""

    /**
     * Set of AccelByte user IDs of matched players who are expected to connect.
     * Populated from matchingAllies when the DS Hub fires MatchmakingV2ServerClaimed.
     */
    @Volatile
    var expectedPlayers: Set<String> = emptySet()

    /** Whether the DS has been claimed by a session (MatchmakingV2ServerClaimed received). */
    @Volatile
    var sessionMatched: Boolean = false

    // ===== Server drain state =====

    /** True when server has received a drain signal and should reject new session binds. */
    @Volatile
    var draining: Boolean = false

    /** True when drain is complete and DSM shutdown has been called. */
    @Volatile
    var drained: Boolean = false

    /** Count of active sessions. Incremented on bindSession, decremented on clearSession. */
    @Volatile
    var activeSessionCount: Int = 0

    /**
     * Binds the DS to a game session, atomically populating expectedPlayers from the matched player list.
     * Called when the DS Hub fires MatchmakingV2ServerClaimed OR when ServerConnector completes matchmaking.
     *
     * @param sessionId The AccelByte session ID
     * @param expectedUserIds List of AccelByte user IDs for matched players
     * @throws ServerDrainingException if the server is currently draining and cannot accept new sessions
     */
    suspend fun bindSession(sessionId: String, expectedUserIds: List<String>)
    {
        if (draining) {
            Logger.warn(
                LogCategory.SYSTEM,
                "WorldManager: Session bind rejected — server is draining (sessionId=$sessionId)"
            )
            throw ServerDrainingException()
        }

        sessionBindLock.withLock {
            activeSessionId = sessionId
            expectedPlayers = expectedUserIds.toSet()
            sessionMatched = true
            activeSessionCount++
            Logger.info(LogCategory.NETWORK, "WorldManager: Session bound sessionId=$sessionId expectedPlayers=${expectedUserIds.size}")
        }
    }

    /**
     * Checks whether an AccelByte user is among the expected players for the matched session.
     *
     * @param accelByteId The AccelByte user ID to check
     * @return true if the user is expected, false otherwise
     */
    fun isPlayerExpected(accelByteId: String): Boolean
    {
        return expectedPlayers.contains(accelByteId)
    }

    /**
     * Clears all AMS session-binding state.
     * Called when a game session ends and before a new one begins to prevent stale DS Hub
     * late-reconnect events from re-binding a previous session.
     *
     * Note: This is a suspend fun because it must synchronize with bindSession() which holds
     * sessionBindLock. Called from TurnHarness.resetState() which is not suspend. In practice,
     * resetState() is only called when no active matchmaking is in progress, so the lock
     * acquisition here is non-contested.
     */
    suspend fun clearSession()
    {
        activeSessionId = ""
        expectedPlayers = emptySet()
        sessionMatched = false
        if (activeSessionCount > 0) activeSessionCount--
        Logger.info(LogCategory.NETWORK, "WorldManager: Session cleared")
    }

    fun setDraining() {
        draining = true
        Logger.info(LogCategory.SYSTEM, "WorldManager: Server entering DRAINING state")
    }

    fun setDrained() {
        drained = true
        Logger.info(LogCategory.SYSTEM, "WorldManager: Server entering DRAINED state")
    }

    fun isDraining(): Boolean = draining
    fun isDrained(): Boolean = drained

    // ===== Backfill state =====

    /** Lazy-initialized BackfillHandler to avoid circular dependencies at startup. */
    private val backfillHandler by lazy {
        accelbyte.backfill.BackfillHandler(this, kotlinx.coroutines.GlobalScope)
    }

    /** Callback invoked when a backfill candidate is accepted. */
    private var onBackfillAcceptedCallback: ((String) -> Unit)? = null

    /**
     * Registers a callback to be invoked when a backfill candidate is accepted.
     * The callback receives the accepted player's AccelByte ID.
     */
    fun onBackfillAccepted(callback: (String) -> Unit) {
        onBackfillAcceptedCallback = callback
    }

    /** Callback invoked when a match ends and the session should be persisted. */
    private var onMatchEndedCallback: ((accelbyte.session.MatchEndedEvent) -> Unit)? = null

    /**
     * Registers a callback to be invoked when a match ends. The callback
     * receives a [accelbyte.session.MatchEndedEvent] with the session ID,
     * outcome, and optional winner name. The callback runs on whatever
     * thread invoked [notifyMatchEnded]; treat it as fire-and-forget
     * (the actual SessionStorageHandler.writeSessionStorage call must
     * launch its own coroutine / thread for any blocking I/O).
     */
    fun onMatchEnded(callback: (accelbyte.session.MatchEndedEvent) -> Unit) {
        onMatchEndedCallback = callback
    }

    /**
     * Emits a [accelbyte.session.MatchEndedEvent] to the registered callback.
     * Called from the gameplay loop (TurnHarness.evaluateEndGame /
     * dispatchForcedGameOver) when `isGameActive` flips from `true` to `false`
     * as a real game end, not as a reset. Logs the emission at INFO so the
     * audit trail matches the SessionStorageHandler call that follows.
     */
    fun notifyMatchEnded(event: accelbyte.session.MatchEndedEvent) {
        Logger.info(
            LogCategory.SYSTEM,
            "WorldManager: Match ended for sessionId=${event.sessionId} outcome=${event.outcome} winner=${event.winnerName ?: "<none>"}"
        )
        onMatchEndedCallback?.invoke(event)
    }

    /**
     * Processes a backfill request.
     *
     * @param request The backfill ticket request
     * @return The backfill handler result indicating acceptance or rejection
     */
    fun handleBackfillRequest(request: accelbyte.backfill.BackfillTicketRequest): accelbyte.backfill.BackfillHandlerResult {
        val result = backfillHandler.handleBackfillRequest(request)
        if (!result.rejected) {
            expectedPlayers = expectedPlayers + request.userId
            Logger.info(LogCategory.NETWORK, "WorldManager: Backfill accepted for userId=${request.userId}, added to expectedPlayers")
            onBackfillAcceptedCallback?.invoke(request.userId)
        } else {
            Logger.info(LogCategory.NETWORK, "WorldManager: Backfill rejected for userId=${request.userId}, reason=${result.reason}")
        }
        return result
    }

    // ==============================================

    /**
     * Defines the actor (Player or npc) who's turn it currently is.
     */
    var activeTurnActor: String = ""
        set(value) {
            field = value
            world.activeTurnActor = value
        }
    
    /** Reference to the game world object that holds the global game state */
    @Volatile
    var world: World = World()

    /**
     * The raw bytes of the currently loaded map pack. Used to sync new clients.
     */
    @Volatile
    var activeMapPackBytes: ByteArray? = null

    /**
     * The name or path of the map pack source (e.g. 'resource:maps/StartMap.map').
     */
    @Volatile
    var activeMapPackName: String = ""

    /**
     * The writing agent configuration extracted from the active map pack.
     * Loaded alongside the world data from MapPackManager.unpack().
     */
    @Volatile
    var activeWritingAgentConfig: WritingAgentConfig = WritingAgentConfig()

    /** Stores the entire game's history. 4 elements count as a single round */
    @Volatile
    var history: MutableList<GameHistory> = mutableListOf()

    @Volatile
    private var stagedHistoryEntries: MutableMap<String, GameHistory> = mutableMapOf()

    @Volatile
    var actionHistoryLog: MutableList<ActionHistoryEvent> = mutableListOf()

    @Volatile
    var pendingActionHistoryByTurn: MutableMap<Int, MutableList<ActionHistoryEvent>> = mutableMapOf()

    @Volatile
    var pendingThinkingByTurn: MutableMap<Int, MutableList<ThinkingUpdateData>> = mutableMapOf()

    /** Maps core backend data to player data */
    @Volatile
    var playerStats: MutableList<PlayerStats> = mutableListOf()
    
    /** Turn timer. AI takes over a players turn if they run out */
    @Volatile
    var gameTimer: CountdownTimer = CountdownTimer()

    /**
     * Most recent duration passed to [startTurnTimer]/[startManualTimer], so we can keep
     * broadcasting a consistent total duration when the timer is interrupted or explicitly stopped.
     */
    @Volatile
    private var lastTimerDurationSeconds: Long = TURN_DURATION_SECONDS

    /**
     * Optional callback to execute when the turn timer expires.
     * Can be used to trigger AI takeover or turn termination.
     */
    var onTurnTimerExpired: (suspend (String) -> Unit)? = null

    /**
     * Broadcasts the latest timer snapshot to every connected client via [UiSignalRpcHandlers].
     *
     * @param remainingSeconds Seconds left on the countdown.
     * @param totalDuration Original allotted duration so clients can compute the fill ratio.
     * @param isRunning Whether the countdown is actively ticking (true for start/tick, false for stop/finish).
     */
    private fun emitTurnTimerState(remainingSeconds: Long, totalDuration: Long, isRunning: Boolean)
    {
        GlobalScope.launch {
            // 1. Broadcast to everyone
            org.ttt.autogenesis.server.UiSignalRpcHandlers.broadcastTurnTimerUpdate(
                remainingSeconds = remainingSeconds,
                totalDuration = totalDuration,
                isRunning = isRunning
            )

            // 2. Targeted update to active player (if human)
            if (activeTurnActor.isNotBlank())
            {
                findPlayerFromStats(activeTurnActor)?.playerID?.let { connectionId ->
                    if (connectionId.isNotBlank())
                    {
                        org.ttt.autogenesis.server.UiSignalRpcHandlers.sendTurnTimerUpdate(
                            connectionId = connectionId,
                            remainingSeconds = remainingSeconds,
                            totalDuration = totalDuration,
                            isRunning = isRunning
                        )
                    }
                }
            }
        }
    }

    /**
     * Starts the turn timer for a specific actor.
     *
     * The active actor is published so the timer can restart every time the harness advances,
     * and the helper defined above immediately emits an [org.ttt.autogenesis.network.TurnTimerUpdateData]
     * signal so clients start their HUD animation before the first tick fires.
     *
     * @param actorName The name of the player or NPC whose turn it is.
     * @param durationSeconds Length of the turn in seconds.
     */
    suspend fun startTurnTimer(actorName: String, durationSeconds: Long = TURN_DURATION_SECONDS)
    {
        activeTurnActor = actorName
        Logger.info(LogCategory.GENERAL, "WorldManager: Starting turn timer for $actorName ($durationSeconds s)")
        
        gameTimer.stop()
        lastTimerDurationSeconds = durationSeconds
        emitTurnTimerState(durationSeconds, durationSeconds, true)
        
        // Bind tick listener to broadcast updates to clients
        gameTimer.onTick = { snapshot ->
            emitTurnTimerState(snapshot.remainingSeconds, durationSeconds, true)
        }
        
        // Bind finish listener
        gameTimer.onFinish = {
            Logger.info(LogCategory.GENERAL, "WorldManager: Turn timer EXPIRED for $actorName")
            GlobalScope.launch {
                // Pulse one final update with 0
                emitTurnTimerState(0, durationSeconds, false)
                onTurnTimerExpired?.invoke(actorName)
            }
        }
        
        gameTimer.start(0, durationSeconds.toInt())
    }

    /**
     * Starts the timer manually without updating the active turn actor.
     * Useful for reaction phases or defense timers where the turn owner shouldn't change.
     *
     * @param durationSeconds Length of the timer in seconds.
     */
    /**
     * Starts a manual countdown that does not change [activeTurnActor] or invoke [onTurnTimerExpired].
     *
     * Useful for reaction windows or defense timers where the distinct actor shouldn’t flip, but
     * we still want clients to observe a countdown plus a final zero broadcast when the timer completes.
     *
     * @param durationSeconds Seconds to count down. The initial broadcast uses this value so clients
     * can calculate the fill ratio used by [kvisionApp.src.jsMain.kotlin.ui.gameplay.ScoreDisplay].
     */
    suspend fun startManualTimer(durationSeconds: Long = COUNTERPLAY_DURATION_SECONDS)
    {
        Logger.info(LogCategory.GENERAL, "WorldManager: Starting manual/auxiliary timer ($durationSeconds s)")

        gameTimer.stop()
        lastTimerDurationSeconds = durationSeconds
        emitTurnTimerState(durationSeconds, durationSeconds, true)

        // Bind tick listener to broadcast updates to clients
        gameTimer.onTick = { snapshot ->
            emitTurnTimerState(snapshot.remainingSeconds, durationSeconds, true)
        }

        // Bind finish listener
        gameTimer.onFinish = {
            Logger.info(LogCategory.GENERAL, "WorldManager: Manual timer EXPIRED")
            GlobalScope.launch {
                // Pulse one final update with 0
                emitTurnTimerState(0, durationSeconds, false)
                // Do NOT trigger turn expiration hooks as this is a manual auxiliary timer
            }
        }

        gameTimer.start(0, durationSeconds.toInt())
    }

    /**
     * Stops and resets the turn timer.
     */
    /**
     * Stops the shared timer and immediately notifies clients that no countdown is running.
     *
     * The existing [lastTimerDurationSeconds] is reused so the clients know the previous allotment and
     * can reset their UI fill bar to zero without waiting for a tick event or finish signal.
     */
    fun stopTurnTimer()
    {
        gameTimer.stop()
        GlobalScope.launch {
            emitTurnTimerState(0, lastTimerDurationSeconds, false)
        }
    }

    /**
     * Pauses the global [gameTimer] if it is currently running and broadcasts
     * the paused state to connected clients.
     */
    fun pauseTurnTimer()
    {
        gameTimer.pause()
        GlobalScope.launch {
            emitTurnTimerState(gameTimer.timeSnapshot().remainingSeconds, lastTimerDurationSeconds, false)
        }
    }

    /**
     * Resumes the global [gameTimer] if it was paused and broadcasts
     * the running state to connected clients.
     */
    fun resumeTurnTimer()
    {
        gameTimer.resume()
        GlobalScope.launch {
            emitTurnTimerState(gameTimer.timeSnapshot().remainingSeconds, lastTimerDurationSeconds, true)
        }
    }
    
    /** Once this reaches 4 or npc's fill, we can start the game */
    @Volatile
    var activePlayerCount: Int = 0

    /**
     * Previous stored assessment made by the system agents in regard to the geopolitical state of the game
     * world. Is updated at each assessment round and used to assist in making the agent's play assessment
     * which will affect the overall stats of a play.
     */
    @Volatile
    var geopoliticalAssessment = "The world has just been formed. The geopolitical landscape is undefined as nations begin to rise."

    /**
     * Explanation of this world's current definition of normal by geopolitical standards.
     */
    @Volatile
    var overtonWindow = ""

    /**
     * Var to check if we can skip some pipes to avoid wasting tokens.
     */
    @Volatile
    var hasUpdatedAssessmentThisRound = false

    /**
     * Resets flags that should only persist for a single round.
     */
    fun resetRoundFlags()
    {
        hasUpdatedAssessmentThisRound = false
        Logger.info(LogCategory.GENERAL, "WorldManager: Round flags reset for Round ${world.roundNumber}")
    }

    /**
     * Fuzzy matches a name and aliases against the list of active players in the game world.
     *
     * @param name The primary name of the entity.
     * @param aliases List of alternative names or titles for the entity.
     * @return The matching [Player] object if found, null otherwise.
     */
    private fun fuzzyMatchPlayer(name: String, aliases: List<String> = emptyList()): Player?
    {
        val cleanName = name.replace("_", " ").replace("-", " ").trim()
        val cleanAliases = aliases.map { it.replace("_", " ").replace("-", " ").trim() }
        val allNames = (listOf(cleanName) + cleanAliases).map { it.lowercase() }

        return world.activePlayers.find { player ->
            val playerName = player.name.lowercase().replace("_", " ").replace("-", " ").trim()
            allNames.any { it == playerName || it.contains(playerName) || playerName.contains(it) }
        }
    }

    /**
     * Updates a player's history with new lore information in a thread-safe manner using fuzzy matching.
     *
     * This function is a DITL (Day In The Life) sync point. Updating the player's history directly
     * in the [world.activePlayers] ensures that both the game engine and subsequent
     * agent pipelines see the updated lore.
     *
     * @param name The extracted name from the lorebook.
     * @param aliases The extracted aliases from the lorebook.
     * @param newHistory The new lore/history text to assign to the player.
     */
    suspend fun updatePlayerHistoryDITL(name: String, aliases: List<String>, newHistory: String)
    {
        if (newHistory.isNotBlank())
        {
            worldMutex.withLock {
                val player = fuzzyMatchPlayer(name, aliases)
                if (player != null)
                {
                    player.history = newHistory
                    Logger.info(LogCategory.SYSTEM, "[DITL] Updated history for player ${player.name}")
                }
            }
        }
    }

    /**
     * Ensures subordinate ownership resources remain aligned with the creator when NPCs are added or modified.
     *
     * Grants a `ResourceType.Subordinate` entry to the owning player/NPC unless it already exists,
     * preventing duplicate grants while guaranteeing the owning entity can spend the subordinate resource.
     *
     * @param npc NPC whose creator should receive the subordinate resource.
     */
    suspend fun logNpcOwnershipDITL(npc: Npc)
    {
        val ownerName = npc.createdBy
        if (ownerName.equals("Story", ignoreCase = true)) return

        worldMutex.withLock {
            val player = world.findPlayerByName(ownerName)
            val resource = Resource(
                name = npc.name,
                type = enums.ResourceType.Subordinate,
                description = "A subordinate NPC under the command of their creator."
            )

            if (player != null)
            {
                val granted = addResourceUniquely(player.resources, resource)
                if (granted)
                {
                    Logger.info(LogCategory.SYSTEM, "Granted NPC ${npc.name} as a Resource to player ${player.name}")
                    logResourceHistoryEvent(
                        playerName = player.name,
                        resourceName = npc.name,
                        resourceType = enums.ResourceType.Subordinate,
                        isGranted = true,
                        isDestroyed = false,
                        grantAmount = 1,
                        eventType = GameEventType.RESOURCE_GRANT,
                        turnNumber = world.roundNumber,
                        timestampMillis = gameState.TimeProvider.nowMillis()
                    )
                }
            }
            else
            {
                val ownerNpc = world.findNpcByName(ownerName)
                if (ownerNpc != null)
                {
                    val granted = addResourceUniquely(ownerNpc.resources, resource)
                    if (granted)
                    {
                        Logger.info(LogCategory.SYSTEM, "Granted NPC ${npc.name} as a Resource to NPC ${ownerNpc.name}")
                        logResourceHistoryEvent(
                            playerName = ownerNpc.name,
                            resourceName = npc.name,
                            resourceType = enums.ResourceType.Subordinate,
                            isGranted = true,
                            isDestroyed = false,
                            grantAmount = 1,
                            eventType = GameEventType.RESOURCE_GRANT,
                            turnNumber = world.roundNumber,
                            timestampMillis = gameState.TimeProvider.nowMillis()
                        )
                    }
                }
            }
        }
    }

    /**
     * Flags the creator's subordinate resource as destroyed/depleted whenever an NPC leaves the subordinate
     * tier (e.g., due to escalation).
     *
     * @param npc NPC whose creator should lose the subordinate resource flag.
     */
    suspend fun revokeNpcOwnershipDITL(npc: Npc)
    {
        val ownerName = npc.createdBy
        if (ownerName.equals("Story", ignoreCase = true)) return

        worldMutex.withLock {
            val player = world.findPlayerByName(ownerName)
            if (player != null)
            {
                val resource = player.resources.find {
                    it.name.equals(npc.name, ignoreCase = true) && it.type == enums.ResourceType.Subordinate
                }
                if (resource != null)
                {
                    resource.isDestroyedOrDepleted = true
                    Logger.info(LogCategory.SYSTEM, "Revoked NPC ${npc.name} resource from player ${player.name}")
                }
            }
            else
            {
                val ownerNpc = world.findNpcByName(ownerName)
                if (ownerNpc != null)
                {
                    val resource = ownerNpc.resources.find {
                        it.name.equals(npc.name, ignoreCase = true) && it.type == enums.ResourceType.Subordinate
                    }
                    if (resource != null)
                    {
                        resource.isDestroyedOrDepleted = true
                        Logger.info(LogCategory.SYSTEM, "Revoked NPC ${npc.name} resource from NPC ${ownerNpc.name}")
                    }
                }
            }
        }
    }

    /**
     * Checks if the game has advanced to a new round based on history size.
     * Round duration is determined by the number of active players.
     */
    fun checkRoundAdvancement(): Boolean
    {
        val playerCount = world.activePlayers.size.coerceAtLeast(1)
        val currentRound = (history.size / playerCount) + 1
        if (currentRound > world.roundNumber)
        {
            Logger.info(LogCategory.GENERAL, "WorldManager: Advancing from Round ${world.roundNumber} to $currentRound (PlayerCount=$playerCount)")
            world.roundNumber = currentRound
            resetRoundFlags()
            return true
        }

        return false
    }

    /**
     * Returns a list of the most recent history entries, calculated by round count.
     *
     * @param rounds Number of full rounds of history to retrieve.
     * @return List containing at most (rounds * activePlayerCount) history entries.
     */
    fun getRecentHistory(rounds: Int): List<structs.GameHistory>
    {
        val playerCount = world.activePlayers.size.coerceAtLeast(1)
        val limit = rounds * playerCount
        return history.takeLast(limit)
    }

    /**
     * Inserts or updates a history entry for the current turn so the judge can read it immediately.
     *
     * @param turnId The identifier for the current turn history entry.
     * @param initializer Mutation that populates the staged entry fields.
     */
    suspend fun stageCurrentTurnHistory(turnId: String, initializer: GameHistory.() -> Unit)
    {
        worldMutex.withLock {
            val existingEntry = history.lastOrNull { it.id == turnId }
            val entry = existingEntry ?: GameHistory(id = turnId).also {
                history.add(it)
            }
            entry.initializer()
            stagedHistoryEntries[turnId] = entry
            Logger.debug(LogCategory.SYSTEM, "WorldManager: Staged history entry for judgement (ID=$turnId)")
        }
    }

    /**
     * Finalizes the staged history entry by applying the provided updates and clearing the staging flag.
     *
     * @param existingId Preferred ID to resolve the staged entry if it was not recorded yet.
     * @param updater Mutation that finalizes the entry fields (results, rewards, etc.).
     */
    suspend fun finalizeStagedHistoryEntry(existingId: String?, updater: GameHistory.() -> Unit): GameHistory
    {
        worldMutex.withLock {
            val entryFromMap = existingId?.let { stagedHistoryEntries.remove(it) }
            val entryFromHistory = entryFromMap ?: existingId?.let { id -> history.lastOrNull { it.id == id } }
            val entry = entryFromHistory ?: GameHistory(id = existingId ?: GameHistory().id)

            entry.updater()

            if (history.none { it.id == entry.id })
            {
                history.add(entry)
            }

            Logger.debug(LogCategory.SYSTEM, "WorldManager: Finalized history entry for judgement (ID=${entry.id})")
            return entry
        }
    }

    /**
     * Add a new player to the game world.
     * 
     * @param playerData Player data to add
     * @param accelbyteID AccelByte user ID for the player
     * @param connectionId RPC connection identifier tied to the player session
     */
    fun addPlayerToWorld(playerData: Player, accelbyteID: String, connectionId: String): PlayerStats
    {
        // 1. Check if we already have this specific reference in our stats list
        playerStats.find { it.playerData === playerData }?.let { return it }
        
        // 2. Check if a player with this name already exists in stats (handles data copies)
        playerStats.find { it.playerData.name == playerData.name }?.let { existing ->
            Logger.warn(LogCategory.GENERAL, "WorldManager.addPlayerToWorld: Player '${playerData.name}' found in stats by name but with different object reference.")
            return existing
        }

        // 3. Ensure the player is in the World's active list
        // Use the existing reference from world.activePlayers if it exists by name, else use the passed one
        val worldPlayerRef = world.activePlayers.find { it.name == playerData.name }
            ?: playerData.also { world.activePlayers.add(it) }

        // 4. Create and register the stats
        val newStats = PlayerStats(
            worldPlayerRef,
            accelbyteID,
            connectionId
        )

        Logger.info(LogCategory.GENERAL, "WorldManager: Adding player '${worldPlayerRef.name}' to world (ID: $accelbyteID, Connection: $connectionId)")
        playerStats.add(newStats)

        // Update the active player count
        activePlayerCount = world.activePlayers.size
        return newStats
    }

    /**
     * Finds a player from stats by commander name with fuzzy matching.
     * 
     * Handles underscores, dashes, and case insensitive matching.
     * 
     * @param commanderName Name to search for
     * @return Player stats if found, null otherwise
     */
    fun findPlayerFromStats(commanderName: String): PlayerStats?
    {
        /**
         * To avoid the problem where the AI tries to put in underscores, or dashes, or some other nonsensical garbage,
         * I decided to try to replace those known bad chars. However, Because I did not feel like writing all that out
         * I am now unleashing this disaster of a one-liner upon you all. Behold my wonderful gift in all it's unreadable
         * glory!!!
         */
        return playerStats.firstOrNull {
            commanderName.replace("_", " ").replace("-", " ").equals(
                it.playerData.name.replace(
                    "_", " ").replace("-", " "), ignoreCase = true)
        }
    }

    /**
     * Finds a player's stats by their connection ID.
     *
     * @param connectionId The connection ID to search for (maps to playerID).
     * @return Player stats if found, null otherwise
     */
    fun findPlayerStatsByConnectionId(connectionId: String): PlayerStats?
    {
        return playerStats.firstOrNull { it.playerID == connectionId }
    }

    suspend fun recordActionHistoryEvent(event: ActionHistoryEvent)
    {
        worldMutex.withLock {
            recordActionHistoryEventUnlocked(event)
        }
    }

    internal fun recordActionHistoryEventUnlocked(event: ActionHistoryEvent)
    {
        actionHistoryLog.add(event)
        val turnNumber = event.event.turnNumber.takeIf { it > 0 } ?: world.roundNumber
        pendingActionHistoryByTurn
            .getOrPut(turnNumber) { mutableListOf() }
            .add(event)
    }

    fun consumePendingEvents(turnNumber : Int) : List<ActionHistoryEvent>
    {
        return pendingActionHistoryByTurn.remove(turnNumber) ?: emptyList()
    }

    suspend fun recordThinkingUpdate(data: ThinkingUpdateData, turnNumber: Int)
    {
        worldMutex.withLock {
            pendingThinkingByTurn
                .getOrPut(turnNumber) { mutableListOf() }
                .add(data)
        }
    }

    fun consumePendingThinking(turnNumber: Int): List<ThinkingUpdateData>
    {
        return pendingThinkingByTurn.remove(turnNumber) ?: emptyList()
    }

    suspend fun applyResourceAdjustments(
        playerName: String,
        adjustments: List<ResourceAdjustment>,
        turnNumber: Int,
        timestampMillis: Long
    )
    {
        worldMutex.withLock {
            applyResourceAdjustmentsUnlocked(playerName, adjustments, turnNumber, timestampMillis)
        }
    }

    internal fun applyResourceAdjustmentsUnlocked(
        playerName: String,
        adjustments: List<ResourceAdjustment>,
        turnNumber: Int,
        timestampMillis: Long
    )
    {
        if(adjustments.isEmpty())
        {
            return
        }

        val player = world.activePlayers.firstOrNull { it.name.equals(playerName, ignoreCase = true) }
        if(player == null)
        {
            Logger.warn(LogCategory.GENERAL, "applyResourceAdjustments: no player found for '$playerName'.")
            return
        }

        adjustments.forEach { adjustment ->
            val name = adjustment.resourceName.takeIf { it.isNotBlank() } ?: run {
                return@forEach
            }

            val resourceType = guessResourceType(name)
            when(adjustment.action)
            {
                ResourceAction.GRANT -> applyResourceGrant(player, adjustment, resourceType, turnNumber, timestampMillis)
                ResourceAction.DESTROY -> applyResourceDestroy(player, name, resourceType, turnNumber, timestampMillis)
            }
        }
    }

    suspend fun applyUniverseChanges(
        changes: List<UniverseChange>,
        turnNumber: Int,
        timestampMillis: Long,
        playerName: String?
    )
    {
        val resolvedPlayer = playerName?.takeIf { it.isNotBlank() }
            ?: resolveCurrentTurnPlayerName().takeIf { it.isNotBlank() }
            ?: "system"

        worldMutex.withLock {
            applyUniverseChangesUnlocked(changes, turnNumber, timestampMillis, resolvedPlayer)
        }
    }

    internal fun applyUniverseChangesUnlocked(
        changes: List<UniverseChange>,
        turnNumber: Int,
        timestampMillis: Long,
        playerName: String
    )
    {
        if(changes.isEmpty())
        {
            return
        }

        changes.forEach { change ->
            when(change.changeType)
            {
                ChangeType.TERRAIN_REMOVED -> applyTerrainRemoval(change, turnNumber, timestampMillis, playerName)
                ChangeType.PHYSICS_ADDED,
                ChangeType.MAGIC_DISCOVERED,
                ChangeType.WORLD_RULE_UPDATED -> applyWorldRuleUpdate(change, turnNumber, timestampMillis, playerName)
                ChangeType.UNKNOWN -> logWorldRuleEvent(change, turnNumber, timestampMillis, playerName)
            }
        }
    }

    private fun applyTerrainRemoval(
        change: UniverseChange,
        turnNumber: Int,
        timestampMillis: Long,
        playerName: String
    )
    {
        val targetTerritories = change.affectedTerritories
            .takeIf { it.isNotEmpty() }
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: listOf(change.before.trim()).filter { it.isNotBlank() }

        if(targetTerritories.isEmpty())
        {
            Logger.warn(LogCategory.GENERAL, "applyTerrainRemoval: no territories listed for change '${change.details}'.")
        }

        val territoriesToRemove = world.mapTiles.filter { territory ->
            targetTerritories.any { it.equals(territory.name, ignoreCase = true) }
        }

        territoriesToRemove.forEach { territory ->
            territory.isDestroyed = true
            if(!world.destroyedTerritories.contains(territory.name))
            {
                world.destroyedTerritories.add(territory.name)
                Logger.info(LogCategory.GENERAL, "Territory '${territory.name}' marked as destroyed")
            }
        }
        world.mapTiles.removeAll(territoriesToRemove)

        logWorldRuleEvent(change, turnNumber, timestampMillis, playerName)
    }

    private fun applyWorldRuleUpdate(
        change: UniverseChange,
        turnNumber: Int,
        timestampMillis: Long,
        playerName: String
    )
    {
        val description = change.after.ifBlank { change.details }
        if(description.isNotBlank() && world.worldRules.none { it.equals(description, ignoreCase = true) })
        {
            world.worldRules.add(description)
        }

        logWorldRuleEvent(change, turnNumber, timestampMillis, playerName)
    }

    private fun logWorldRuleEvent(
        change : UniverseChange,
        turnNumber : Int,
        timestampMillis : Long,
        playerName : String
    )
    {
        val metadata = WorldRuleMetadata(
            ruleName = change.changeType.name.lowercase(),
            description = change.details.ifBlank { change.before },
            effect = change.after.ifBlank { change.details.ifBlank { "world update" } }
        )

        val event = ActionHistory(
            player = playerName,
            eventType = GameEventType.WORLD_RULE,
            metadata = metadata,
            turnNumber = turnNumber,
            timestampMillis = timestampMillis
        )

        recordActionHistoryEventUnlocked(ActionHistoryEvent(event, recordedAtMillis = timestampMillis))
    }

    /**
     * Grants the named resource to a player while preventing duplicates that arise from duplicate history events.
     *
     * If the resource already exists (same owner, normalized name, identical type) it is treated as a repair
     * and is just marked as undepleted; otherwise the resource is appended to the owner’s inventory.
     * Logging indicates whether the grant created a new entry or detected a duplicate.
     *
     * @param player The owning player receiving the resource.
     * @param adjustment The ResourceAdjustment containing all resource metadata.
     * @param resourceType The resource category used for dedup and display.
     * @param turnNumber The current turn so the grant event includes timing for history analytics.
     * @param timestampMillis When the grant occurred (for timeline events and tracing).
     */
    private fun applyResourceGrant(
        player : Player,
        adjustment : ResourceAdjustment,
        resourceType : ResourceType,
        turnNumber : Int,
        timestampMillis : Long
    )
    {
        val resource = Resource(
            name = adjustment.resourceName,
            type = resourceType,
            depletable = adjustment.depletable,
            destructible = adjustment.destructible,
            isDestroyedOrDepleted = false,
            description = adjustment.description,
            abilities = adjustment.abilities
        )
        val added = addResourceUniquely(player.resources, resource)
        if (added)
        {
            Logger.info(LogCategory.GENERAL, "Added new resource to ${player.name}: ${adjustment.resourceName} (type: $resourceType)")
        }
        else
        {
            Logger.info(LogCategory.GENERAL, "Skipped duplicate resource for ${player.name}: ${adjustment.resourceName} (type: $resourceType)")
        }

        logResourceHistoryEvent(
            playerName = player.name,
            resourceName = adjustment.resourceName,
            resourceType = resourceType,
            isGranted = true,
            isDestroyed = false,
            grantAmount = 1,
            eventType = GameEventType.RESOURCE_GRANT,
            turnNumber = turnNumber,
            timestampMillis = timestampMillis
        )
    }

    /**
     * Grants the named resource to an NPC owner, using the same uniqueness guard as players.
     *
     * NPCs share the same subordinate-resource logic, so we reuse `addResourceUniquely` to ensure
     * the same normalized (name + type) key prevents duplicates that were previously generated from
     * racing history events.
     */
    private fun applyResourceGrant(
        npc : Npc,
        resourceName : String,
        resourceType : ResourceType,
        turnNumber : Int,
        timestampMillis : Long
    )
    {
        val resource = Resource(name = resourceName, type = resourceType)
        val added = addResourceUniquely(npc.resources, resource)
        if (added)
        {
            Logger.info(LogCategory.GENERAL, "Added new resource to NPC ${npc.name}: $resourceName (type: $resourceType)")
        }
        else
        {
            Logger.info(LogCategory.GENERAL, "Skipped duplicate resource for NPC ${npc.name}: $resourceName (type: $resourceType)")
        }

        logResourceHistoryEvent(
            playerName = npc.name,
            resourceName = resourceName,
            resourceType = resourceType,
            isGranted = true,
            isDestroyed = false,
            grantAmount = 1,
            eventType = GameEventType.RESOURCE_GRANT,
            turnNumber = turnNumber,
            timestampMillis = timestampMillis
        )
    }

    private fun applyResourceDestroy(
        player : Player,
        resourceName : String,
        resourceType : ResourceType,
        turnNumber : Int,
        timestampMillis : Long
    )
    {
        val resource = player.resources.find { it.name.equals(resourceName, ignoreCase = true) }
        resource?.isDestroyedOrDepleted = true
        player.resources.removeIf { it.name.equals(resourceName, ignoreCase = true) && it.type == resourceType }

        logResourceHistoryEvent(
            playerName = player.name,
            resourceName = resourceName,
            resourceType = resourceType,
            isGranted = false,
            isDestroyed = true,
            grantAmount = 0,
            eventType = GameEventType.RESOURCE,
            turnNumber = turnNumber,
            timestampMillis = timestampMillis
        )
    }

    private fun applyResourceDestroy(
        npc : Npc,
        resourceName : String,
        resourceType : ResourceType,
        turnNumber : Int,
        timestampMillis : Long
    )
    {
        val resource = npc.resources.find { it.name.equals(resourceName, ignoreCase = true) }
        resource?.isDestroyedOrDepleted = true
        npc.resources.removeIf { it.name.equals(resourceName, ignoreCase = true) && it.type == resourceType }

        logResourceHistoryEvent(
            playerName = npc.name,
            resourceName = resourceName,
            resourceType = resourceType,
            isGranted = false,
            isDestroyed = true,
            grantAmount = 0,
            eventType = GameEventType.RESOURCE,
            turnNumber = turnNumber,
            timestampMillis = timestampMillis
        )
    }

    internal fun logResourceHistoryEvent(
        playerName : String,
        resourceName : String,
        resourceType : ResourceType,
        isGranted : Boolean,
        isDestroyed : Boolean,
        grantAmount : Int,
        eventType : GameEventType,
        turnNumber : Int,
        timestampMillis : Long
    )
    {
        val metadata = ResourceEventMetadata(
            resourceName = resourceName,
            resourceType = resourceType.name,
            isGranted = isGranted,
            isDestroyed = isDestroyed,
            recipient = playerName,
            grantAmount = grantAmount
        )

        val event = ActionHistory(
            player = playerName,
            eventType = eventType,
            metadata = metadata,
            turnNumber = turnNumber,
            timestampMillis = timestampMillis
        )

        recordActionHistoryEventUnlocked(ActionHistoryEvent(event, recordedAtMillis = timestampMillis))
    }

    fun resolveCurrentTurnPlayerName(): String
    {
        val activeStats = playerStats.firstOrNull { it.turnActive }?.playerData?.name
        if(!activeStats.isNullOrBlank())
        {
            return activeStats
        }

        return world.activePlayers.firstOrNull()?.name.orEmpty()
    }

    /**
     * Applies judge outcomes to the world by mutating player resources, territory ownership, and
     * recording the resulting action history events so the scoring and history pipelines stay in sync.
     *
     * @param playerName Player affected by the judgment.
     * @param wasSuccessful Whether the LLM judged the play successful.
     * @param results Detailed gain/loss information returned by the judge pipeline.
     * @param turnNumber Turn number associated with this judgment.
     * @param timestampMillis Millisecond timestamp used for action history events.
     */
    suspend fun applyNpcJudgeResults(
        npcName : String,
        results : Results
    )
    {
        val turnNumber = world.roundNumber
        val timestampMillis = TimeProvider.nowMillis()

        worldMutex.withLock {
            applyNpcJudgeResultsUnlocked(npcName, results, turnNumber, timestampMillis)
        }
    }

    /**
     * Idempotently adds the resource to the provided list by comparing normalized name+type.
     *
     * @param resources The mutable list that should contain the resource.
     * @param resource The resource to grant; if a match already exists, the existing entry is restored and no new
     *                 copy is created.
     * @return true if the resource was newly added, false if an existing entry was repaired instead.
     */
    fun addResourceUniquely(resources: MutableList<Resource>, resource: Resource): Boolean
    {
        val normalizedName = resource.name.trim().lowercase()
        val existing = resources.find {
            it.name.trim().lowercase() == normalizedName && it.type == resource.type
        }
        if(existing != null)
        {
            existing.isDestroyedOrDepleted = false
            return false
        }

        resources.add(resource)
        return true
    }

    internal fun applyNpcJudgeResultsUnlocked(
        npcName : String,
        results : Results,
        turnNumber : Int,
        timestampMillis : Long
    )
    {
        // For NPCs, we don't have a separate judge/player outcome log split like players yet,
        // but we can reuse the generic history log.
        logJudgeOutcome(npcName, true, results, turnNumber, timestampMillis)

        val npc = world.findNpcByName(npcName)
        if(npc == null)
        {
            Logger.warn(LogCategory.GENERAL, "applyNpcJudgeResults: no matching NPC '$npcName' found.")
            return
        }

        // Only add tangible resources to inventory; abstract resources grant stat buffs instead
        val classifiedResources = results.classifiedResources
        if(classifiedResources != null)
        {
            Logger.info(LogCategory.GENERAL, "applyNpcJudgeResults: $npcName classification -> ${classifiedResources.tangible.size} tangible, ${classifiedResources.abstract.size} abstract")
            classifiedResources.tangible.forEach { resourceName ->
                if(resourceName.isBlank()) return@forEach
                
                val existingNpc = world.findNpcByName(resourceName)
                if (existingNpc != null) {
                    Logger.info(LogCategory.GENERAL, "Skipping resource grant for existing NPC: $resourceName")
                    return@forEach
                }
                
                val classification = classifiedResources.classifications.find { 
                    it.resourceName.equals(resourceName, ignoreCase = true) 
                }
                
                val isDefeated = classification?.isDefeated ?: false
                val defeatReason = classification?.defeatReason ?: ""
                val description = if (isDefeated && defeatReason.isNotBlank()) {
                    "${classification?.description ?: ""}\n[DEFEATED: $defeatReason]"
                } else {
                    classification?.description ?: ""
                }
                
                Logger.info(LogCategory.GENERAL, "Granting resource to NPC ${npc.name}: $resourceName${if(isDefeated) " [DEFEATED]" else ""}")
                
                applyResourceGrant(npc, resourceName, guessResourceType(resourceName), turnNumber, timestampMillis)
                
                if (isDefeated) {
                    val resource = npc.resources.find { it.name.equals(resourceName, ignoreCase = true) }
                    if (resource != null) {
                        resource.isDestroyedOrDepleted = true
                    }
                }
            }
        }
        else
        {
            results.assetsGained.forEach { resourceName ->
                if(resourceName.isBlank()) return@forEach
                applyResourceGrant(npc, resourceName, guessResourceType(resourceName), turnNumber, timestampMillis)
            }
        }

        results.assetsLost.forEach { resourceName ->
            if(resourceName.isBlank()) return@forEach
            applyResourceDestroy(npc, resourceName, guessResourceType(resourceName), turnNumber, timestampMillis)
        }

        results.assetExchanges.forEach { exchange ->
            if(exchange.assetName.isBlank()) return@forEach
            transferResource(exchange.assetName, exchange.from, exchange.to, turnNumber, timestampMillis)
        }

        // Depose territories
        results.territoriesDeposed.forEach { territoryName ->
            if (territoryName.isNotBlank())
            {
                deposeTerritory(territoryName, turnNumber, timestampMillis)
            }
        }

        applyTerritoryChanges(npcName, results, turnNumber, timestampMillis)

        // Apply territory stat changes
        if (results.territoryStatChanges.isNotEmpty())
        {
            applyTerritoryStatChanges(results.territoryStatChanges)
        }
    }

    suspend fun applyKarmaChange(isPositive : Boolean)
    {
        worldMutex.withLock {
            if(isPositive)
            {
                world.karmaPoints += 5
            }
            else
            {
                world.karmaPoints -= 5
            }
        }
    }

    /**
     * Applies the judge results for the named player and synchronizes world state updates.
     *
     * The collection now respects the `ClassifiedResources` metadata that the judge pipeline stores so abstract
     * resources only contribute stat buffs while tangible ones go into the inventory. If the metadata is absent, the
     * method falls back to the legacy behavior of granting everything directly.
     *
     * @param playerName Name of the player whose turn just resolved.
     * @param wasSuccessful Whether the judged play succeeded.
     * @param results The `Results` blob from [buildJudge], including territories, assets, and now classification data.
     * @param turnNumber The current round to stamp history/timestamp entries with.
     * @param timestampMillis Milliseconds since epoch for logging/fan-out events.
     */
    suspend fun applyJudgeResults(
        playerName : String,
        wasSuccessful : Boolean,
        results : Results,
        turnNumber : Int,
        timestampMillis : Long
    )
    {
        worldMutex.withLock {
            applyJudgeResultsUnlocked(playerName, wasSuccessful, results, turnNumber, timestampMillis)
        }
    }

    internal fun applyJudgeResultsUnlocked(
        playerName : String,
        wasSuccessful : Boolean,
        results : Results,
        turnNumber : Int,
        timestampMillis : Long
    )
    {
        Logger.debug(LogCategory.GENERAL, "[TERRITORY_DEBUG] applyJudgeResultsUnlocked called for $playerName")
        Logger.debug(LogCategory.GENERAL, "[TERRITORY_DEBUG] Results.territoryGained: ${results.territoryGained}")
        Logger.debug(LogCategory.GENERAL, "[TERRITORY_DEBUG] Results.territoryLost: ${results.territoryLost}")
        Logger.debug(LogCategory.GENERAL, "[TERRITORY_DEBUG] Results.territoryExchanges: ${results.territoryExchanges}")
        
        logJudgeOutcome(playerName, wasSuccessful, results, turnNumber, timestampMillis)
        logPlayerOutcome(playerName, wasSuccessful, turnNumber, timestampMillis)

        val player = world.activePlayers.firstOrNull { it.name.equals(playerName, ignoreCase = true) }
        if(player == null)
        {
            Logger.warn(LogCategory.GENERAL, "applyJudgeResults: no matching player '$playerName' found.")
            return
        }

        // Only add tangible resources to inventory; abstract resources grant stat buffs instead
        val classifiedResources = results.classifiedResources
        
        // Logic fix: Only use classification if it actually classified something.
        // If the LLM returned empty lists (often due to over-filtering or confusion), 
        // we fall back to the raw assetsGained list to ensure rewards aren't lost.
        val hasClassificationData = classifiedResources != null && 
            (classifiedResources.tangible.isNotEmpty() || classifiedResources.abstract.isNotEmpty() || classifiedResources.npcs.isNotEmpty())

        if(hasClassificationData)
        {
            val abstractSummary = classifiedResources!!.abstract.take(5).let { sample ->
                if(sample.isEmpty())
                {
                    "none"
                }
                else
                {
                    val moreLabel = if(classifiedResources.abstract.size > sample.size) " +${classifiedResources.abstract.size - sample.size} more" else ""
                    sample.joinToString(", ") + moreLabel
                }
            }
            Logger.info(
                LogCategory.GENERAL,
                "applyJudgeResults: $playerName classification -> ${classifiedResources.tangible.size} tangible, ${classifiedResources.abstract.size} abstract ($abstractSummary)"
            )
            classifiedResources.tangible.forEach { resourceName ->
                if(resourceName.isBlank()) return@forEach
                
                // Check if the player already has this resource
                val alreadyHas = player.resources.any { it.name.equals(resourceName, ignoreCase = true) }
                if (alreadyHas) {
                    Logger.info(LogCategory.GENERAL, "Skipping resource grant for existing resource: $resourceName (already owned by ${player.name})")
                    return@forEach
                }
                
                // Get classification details for defeat status
                val classification = classifiedResources.classifications.find { 
                    it.resourceName.equals(resourceName, ignoreCase = true) 
                }
                
                val isDefeated = classification?.isDefeated ?: false
                val defeatReason = classification?.defeatReason ?: ""
                
                // Update description to include defeat status
                val description = if (isDefeated && defeatReason.isNotBlank()) {
                    "${classification?.description ?: ""}\n[DEFEATED: $defeatReason]"
                } else {
                    classification?.description ?: ""
                }
                
                Logger.info(
                    LogCategory.GENERAL, 
                    "Granting resource to ${player.name}: $resourceName (type: ${guessResourceType(resourceName)})${if(isDefeated) " [DEFEATED]" else ""}"
                )
                
                // Create ResourceAdjustment with classification details
                val adjustment = ResourceAdjustment(
                    resourceName = resourceName,
                    action = ResourceAction.GRANT,
                    description = description,
                    abilities = classification?.reasoning ?: ""
                )
                applyResourceGrant(player, adjustment, guessResourceType(resourceName), turnNumber, timestampMillis)
                
                // Mark as defeated if necessary
                if (isDefeated) {
                    val resource = player.resources.find { it.name.equals(resourceName, ignoreCase = true) }
                    if (resource != null) {
                        val updatedResource = resource.copy(isDestroyedOrDepleted = true)
                        player.resources.remove(resource)
                        player.resources.add(updatedResource)
                        Logger.info(LogCategory.GENERAL, "  Marked as defeated: $defeatReason")
                    }
                }
            }
        }
        else
        {
            if (classifiedResources != null && results.assetsGained.isNotEmpty()) {
                Logger.warn(
                    LogCategory.GENERAL,
                    "applyJudgeResults: $playerName classification was empty but assetsGained has ${results.assetsGained.size} items. Falling back to granting all assetsGained."
                )
            } else {
                Logger.info(
                    LogCategory.GENERAL,
                    "applyJudgeResults: $playerName missing classification metadata or empty; applying all ${results.assetsGained.size} gained resources"
                )
            }
            // Fallback to old behavior if classification not present or empty (backward compatibility)
            results.assetsGained.forEach { resourceName ->
                if(resourceName.isBlank()) return@forEach
                val adjustment = ResourceAdjustment(
                    resourceName = resourceName,
                    action = ResourceAction.GRANT
                )
                applyResourceGrant(player, adjustment, guessResourceType(resourceName), turnNumber, timestampMillis)
            }
        }

        results.assetsLost.forEach { resourceName ->
            if(resourceName.isBlank()) return@forEach
            applyResourceDestroy(player, resourceName, guessResourceType(resourceName), turnNumber, timestampMillis)
        }

        results.assetExchanges.forEach { exchange ->
            if(exchange.assetName.isBlank()) return@forEach
            transferResource(exchange.assetName, exchange.from, exchange.to, turnNumber, timestampMillis)
        }

        // Depose territories
        results.territoriesDeposed.forEach { territoryName ->
            if (territoryName.isNotBlank())
            {
                deposeTerritory(territoryName, turnNumber, timestampMillis)
            }
        }

        applyTerritoryChanges(playerName, results, turnNumber, timestampMillis)
        
        // Apply territory stat changes
        if (results.territoryStatChanges.isNotEmpty())
        {
            applyTerritoryStatChanges(results.territoryStatChanges)
        }
        
        // Ensure subordinate resources have corresponding NPC entities
        player.resources.filter { it.type == ResourceType.Subordinate }.forEach { subordinate ->
            val npcExists = world.npc.any { it.name.equals(subordinate.name, ignoreCase = true) }
            if (!npcExists) {
                Logger.warn(LogCategory.GENERAL, "Subordinate resource '${subordinate.name}' has no corresponding NPC entity - NPC scan may have missed this character")
            }
        }
    }

    private fun transferResource(
        resourceName: String,
        from: String,
        to: String,
        turnNumber: Int,
        timestampMillis: Long
    )
    {
        val fromActor = world.findPlayerByName(from) ?: world.findNpcByName(from)
        val toActor = world.findPlayerByName(to) ?: world.findNpcByName(to)

        if (fromActor != null) {
            val resources = if (fromActor is Player) fromActor.resources else (fromActor as? Npc)?.resources
            resources?.removeIf { it.name.equals(resourceName, ignoreCase = true) }
            logResourceHistoryEvent(fromActor.getInternals().name, resourceName, guessResourceType(resourceName), false, true, 0, GameEventType.RESOURCE, turnNumber, timestampMillis)
        }

        if (toActor != null) {
            val type = guessResourceType(resourceName)
            val resources = if (toActor is Player) toActor.resources else (toActor as? Npc)?.resources
            if (resources != null) {
                val existing = resources.find { it.name.equals(resourceName, ignoreCase = true) }
                if (existing != null) {
                    existing.isDestroyedOrDepleted = false
                } else {
                    resources.add(Resource(name = resourceName, type = type))
                }
            }
            logResourceHistoryEvent(toActor.getInternals().name, resourceName, type, true, false, 1, GameEventType.RESOURCE_GRANT, turnNumber, timestampMillis)
        }
    }

    private fun logJudgeOutcome(
        playerName : String,
        wasSuccessful : Boolean,
        results : Results,
        turnNumber : Int,
        timestampMillis : Long
    )
    {
        val metadata = JudgeEventMetadata(
            wasSuccessful = wasSuccessful,
            judgmentReason = if(wasSuccessful) "Judge pipeline awarded success." else "Judge pipeline determined failure.",
            resourcesUsed = results.assetsLost,
            difficultyModifiers = emptyMap()
        )

        val event = ActionHistory(
            player = playerName,
            eventType = GameEventType.JUDGE,
            metadata = metadata,
            turnNumber = turnNumber,
            timestampMillis = timestampMillis
        )

        recordActionHistoryEventUnlocked(ActionHistoryEvent(event, recordedAtMillis = timestampMillis))
    }

    private fun logPlayerOutcome(
        playerName : String,
        wasSuccessful : Boolean,
        turnNumber : Int,
        timestampMillis : Long
    )
    {
        val metadata = PlayerOutcomeMetadata(
            outcome = if(wasSuccessful) "successful_play" else "failed_play",
            victory = wasSuccessful,
            reason = "Judgment pipeline recorded the player's turn outcome."
        )

        val event = ActionHistory(
            player = playerName,
            eventType = GameEventType.PLAYER_OUTCOME,
            metadata = metadata,
            turnNumber = turnNumber,
            timestampMillis = timestampMillis
        )

        recordActionHistoryEventUnlocked(ActionHistoryEvent(event, recordedAtMillis = timestampMillis))
    }

    private fun applyTerritoryChanges(
        playerName : String,
        results : Results,
        turnNumber : Int,
        timestampMillis : Long
    )
    {
        Logger.debug(LogCategory.GENERAL, "[TERRITORY_DEBUG] applyTerritoryChanges called for $playerName")
        Logger.debug(LogCategory.GENERAL, "[TERRITORY_DEBUG] territoryGained count: ${results.territoryGained.size}")
        Logger.debug(LogCategory.GENERAL, "[TERRITORY_DEBUG] territoryLost count: ${results.territoryLost.size}")
        Logger.debug(LogCategory.GENERAL, "[TERRITORY_DEBUG] territoryExchanges count: ${results.territoryExchanges.size}")

        // Build conflict set: territories that player gained (may conflict with exchanges)
        val conflictSet = results.territoryGained
            .filter { it.isNotBlank() }
            .map { it.trim().lowercase() }
            .toSet()

        // Pre-process: Detect bidirectional exchanges for Rule 3 (attack conflict → contested)
        // Group exchanges by territory name, look for A→B and B→A pattern
        val contestedTerritories = mutableSetOf<String>()
        val territoriesWithMultipleExchanges = results.territoryExchanges
            .filter { it.territoryName.isNotBlank() }
            .groupBy { it.territoryName.trim().lowercase() }
            .filter { it.value.size >= 2 }

        for ((territoryName, exchanges) in territoriesWithMultipleExchanges)
        {
            // Check for bidirectional pattern: A→B and B→A
            val hasBidirectional = exchanges.any { ex1 ->
                exchanges.any { ex2 ->
                    ex1.territoryName.trim().lowercase() == ex2.territoryName.trim().lowercase() &&
                    ex1.from.trim().lowercase() == ex2.to.trim().lowercase() &&
                    ex1.to.trim().lowercase() == ex2.from.trim().lowercase() &&
                    ex1.from.isNotBlank() && ex2.from.isNotBlank()
                }
            }
            if (hasBidirectional)
            {
                contestedTerritories.add(territoryName)
                Logger.warn(LogCategory.GENERAL, "[TERRITORY_CONFLICT] Rule 3: Bidirectional attack detected for '$territoryName' - marking CONTESTED")
            }
        }

        results.territoryGained.forEach { territoryName ->
            if(territoryName.isBlank()) return@forEach
            Logger.info(LogCategory.GENERAL, "[TERRITORY_DEBUG] Transferring territory TO $playerName: $territoryName")
            val captureOwner = resolveTerritoryCaptureOwner(playerName)
            Logger.debug(LogCategory.GENERAL, "[TERRITORY_DEBUG] Redirecting capture of $territoryName to $captureOwner (requested by $playerName)")
            transferTerritory(territoryName, "", captureOwner, turnNumber, timestampMillis, skipAdjacencyCheck = true)
        }

        results.territoryLost.forEach { territoryName ->
            if(territoryName.isBlank()) return@forEach
            Logger.info(LogCategory.GENERAL, "[TERRITORY_DEBUG] Transferring territory FROM $playerName: $territoryName")
            transferTerritory(territoryName, playerName, "", turnNumber, timestampMillis, skipAdjacencyCheck = true)
        }

        results.territoryExchanges.forEach { exchange ->
            if(exchange.territoryName.isBlank()) return@forEach

            val normalizedTerritoryName = exchange.territoryName.trim().lowercase()

            // Rule 3: Skip if territory is contested (bidirectional attack detected)
            if (contestedTerritories.contains(normalizedTerritoryName))
            {
                Logger.warn(LogCategory.GENERAL, "[TERRITORY_CONFLICT] Rule 3: Bidirectional attack - marking '${exchange.territoryName}' CONTESTED")
                val territory = world.mapTiles.findTerritoryByName(exchange.territoryName)
                if (territory != null)
                {
                    world.activePlayers.forEach { player ->
                        player.capturedTerritory.removeIf { it.name.equals(exchange.territoryName, ignoreCase = true) }
                    }
                    world.npc.forEach { npc ->
                        npc.capturedTerritory.removeIf { it.name.equals(exchange.territoryName, ignoreCase = true) }
                    }
                    territory.ruler = ""
                    territory.isCaptured = false
                }
                return@forEach
            }

            // Rule 1 & 2: Check if territory is in conflictSet (player already gained it)
            if (conflictSet.contains(normalizedTerritoryName))
            {
                val recipientPlayer = world.findPlayerByName(exchange.to)
                if (recipientPlayer != null)
                {
                    // Rule 2: Another player gets the same territory → Acting player wins
                    Logger.info(LogCategory.GENERAL, "[TERRITORY_CONFLICT] Rule 2: Acting player '$playerName' wins over '$exchange.to' for '${exchange.territoryName}'")
                }
                else
                {
                    // Rule 1: NPC gets the same territory → Player wins
                    Logger.info(LogCategory.GENERAL, "[TERRITORY_CONFLICT] Rule 1: Player '$playerName' wins over NPC '${exchange.to}' for '${exchange.territoryName}'")
                }
                return@forEach
            }

            Logger.info(LogCategory.GENERAL, "[TERRITORY_DEBUG] Exchanging territory: ${exchange.territoryName} from ${exchange.from} to ${exchange.to}")
            transferTerritory(exchange.territoryName, exchange.from, exchange.to, turnNumber, timestampMillis, skipAdjacencyCheck = true)
        }
    }

    private fun resolveTerritoryCaptureOwner(actorName: String): String
    {
        val normalized = actorName.trim()
        if(normalized.isBlank())
        {
            return actorName
        }
        val subordinate = world.npc.firstOrNull { n ->
            n.name.equals(normalized, ignoreCase = true) &&
            n.type == enums.NpcType.Subordinate &&
            n.createdBy.isNotBlank() &&
            !n.createdBy.equals("Story", ignoreCase = true)
        }
        return subordinate?.createdBy ?: actorName
    }

    private fun transferTerritory(
        territoryName: String,
        from: String,
        to: String,
        turnNumber: Int,
        timestampMillis: Long,
        skipAdjacencyCheck: Boolean = false
    )
    {
        Logger.info(LogCategory.GENERAL, "[TERRITORY_DEBUG] transferTerritory called: '$territoryName' from '$from' to '$to'")
        
        val territory = world.mapTiles.findTerritoryByName(territoryName)
        if(territory == null)
        {
            Logger.warn(LogCategory.GENERAL, "[TERRITORY_DEBUG] transferTerritory: failed to find territory '$territoryName'.")
            return
        }

        // Validate adjacency if transferring to a player (not neutral)
        if (!skipAdjacencyCheck && to.isNotBlank())
        {
            val targetPlayer = world.findPlayerByName(to)
            if(targetPlayer != null)
            {
                val validation = validateTerritoryAdjacency(to, territoryName)
                if (!validation.isValid)
                {
                    Logger.warn(
                        LogCategory.GENERAL,
                        "[ADJACENCY_BLOCK] Blocked non-adjacent territory transfer: $territoryName to $to. ${validation.reason}"
                    )
                    return
                }
            }
        }

        Logger.debug(LogCategory.GENERAL, "[TERRITORY_DEBUG] Found territory '$territoryName', current ruler: '${territory.ruler}'")
        
        val previousOwnerName = territory.ruler
        val targetFrom = from.ifBlank { previousOwnerName }

        // Remove from previous owner's list
        if(targetFrom.isNotBlank())
        {
            val oldPlayer = world.findPlayerByName(targetFrom)
            if(oldPlayer != null)
            {
                oldPlayer.capturedTerritory.removeIf { it.name.equals(territoryName, ignoreCase = true) }
            }
            else
            {
                val oldNpc = world.findNpcByName(targetFrom)
                oldNpc?.capturedTerritory?.removeIf { it.name.equals(territoryName, ignoreCase = true) }
            }
        }

        // Add to new owner's list if they exist
        val newPlayer = world.findPlayerByName(to)
        val newNpc = if(newPlayer == null) world.findNpcByName(to) else null

        Logger.debug(LogCategory.GENERAL, "[TERRITORY_DEBUG] New owner lookup: player='${newPlayer?.name}', npc='${newNpc?.name}'")

        if(newPlayer != null)
        {
            Logger.info(LogCategory.GENERAL, "[TERRITORY_DEBUG] Assigning territory '$territoryName' to player '${newPlayer.name}'")
            territory.ruler = newPlayer.name
            territory.isCaptured = true
            if(newPlayer.capturedTerritory.none { it.name.equals(territoryName, ignoreCase = true) })
            {
                newPlayer.capturedTerritory.add(territory)
                Logger.debug(LogCategory.GENERAL, "[TERRITORY_DEBUG] Added to player's capturedTerritory list")
            }
            else
            {
                Logger.debug(LogCategory.GENERAL, "[TERRITORY_DEBUG] Territory already in player's capturedTerritory list")
            }
        }
        else if(newNpc != null)
        {
            territory.ruler = newNpc.name
            territory.isCaptured = true
            if(newNpc.capturedTerritory.none { it.name.equals(territoryName, ignoreCase = true) })
            {
                newNpc.capturedTerritory.add(territory)
            }
        }
        else
        {
            // If the recipient is unknown or blank, make it neutral
            territory.ruler = ""
            territory.isCaptured = false
        }

        logTerritoryEvent(
            playerName = to.ifBlank { resolveCurrentTurnPlayerName() },
            territory = territory,
            previousOwner = targetFrom,
            newOwner = territory.ruler,
            turnNumber = turnNumber,
            timestampMillis = timestampMillis
        )
    }

    private fun logTerritoryEvent(
        playerName : String,
        territory : Territory,
        previousOwner : String,
        newOwner : String,
        turnNumber : Int,
        timestampMillis : Long
    )
    {
        val metadata = TerritoryEventMetadata(
            territoryName = territory.name,
            previousOwner = previousOwner,
            newOwner = newOwner,
            isDestroyed = territory.isDestroyed,
            pointValueChange = territory.pointValue
        )

        val event = ActionHistory(
            player = playerName,
            eventType = GameEventType.TERRITORY,
            metadata = metadata,
            turnNumber = turnNumber,
            timestampMillis = timestampMillis
        )

        recordActionHistoryEventUnlocked(ActionHistoryEvent(event, recordedAtMillis = timestampMillis))
    }

    private fun guessResourceType(resourceName : String) : ResourceType
    {
        // Check if it's likely a person name (NPC)
        val npcPatterns = listOf(
            Regex("^(General|Admiral|Commander|Captain|Lieutenant|Colonel|Major|Sergeant) .+", RegexOption.IGNORE_CASE),
            Regex("^(Ambassador|Diplomat|Envoy|Minister|Chancellor|President|King|Queen) .+", RegexOption.IGNORE_CASE),
            Regex("^(Doctor|Professor|Scientist|Engineer|Researcher) .+", RegexOption.IGNORE_CASE),
            Regex("^(Agent|Spy|Operative|Assassin) .+", RegexOption.IGNORE_CASE),
            Regex("^[A-Z][a-z]+ [A-Z][a-z]+$"),  // "John Smith" pattern
            Regex("^[A-Z][a-z]+ [A-Z]\\. [A-Z][a-z]+$")  // "John Q. Smith" pattern
        )
        
        if (npcPatterns.any { it.matches(resourceName) }) {
            return ResourceType.Subordinate
        }
        
        return when
        {
            resourceName.contains("war", ignoreCase = true) -> ResourceType.Military
            resourceName.contains("diplomacy", ignoreCase = true) -> ResourceType.Diplomatic
            resourceName.contains("research", ignoreCase = true) -> ResourceType.Scientific
            resourceName.contains("science", ignoreCase = true) -> ResourceType.Scientific
            resourceName.contains("tech", ignoreCase = true) || resourceName.contains("technology", ignoreCase = true) -> ResourceType.Technological
            resourceName.contains("magic", ignoreCase = true) -> ResourceType.Magical
            resourceName.contains("supernatural", ignoreCase = true) -> ResourceType.Supernatural
            resourceName.contains("economic", ignoreCase = true) || resourceName.contains("trade", ignoreCase = true) -> ResourceType.Economic
            else -> ResourceType.Military
        }
    }

    /**
     * Loads a map from a map pack byte array.
     *
     * @param packBytes The raw bytes of the map pack.
     * @param source Description of the source (e.g., "upload", "default").
     */
    suspend fun loadMapFromPack(packBytes: ByteArray, source: String)
    {
        worldMutex.withLock {
            Logger.info(LogCategory.GENERAL, "WorldManager: Loading map from $source (${packBytes.size} bytes)...")
            Logger.debug(LogCategory.GENERAL, "WorldManager: Preparing to parse map pack from $source (packBytesHash=${packBytes.hashCode()})")
            try
            {
                // First unpack to extract writing agent config before loading the world
                val unpackedForConfig = MapPackManager.unpack(packBytes)
                activeWritingAgentConfig = unpackedForConfig.mapData.writingAgentConfig
                Logger.debug(LogCategory.GENERAL, """WorldManager: Extracted writingAgentConfig from map pack:
                    |  ruleCategories=${activeWritingAgentConfig.ruleCategories.map { "${it.name}(${it.chancePercent}%,${it.rules.size}r)" }}
                    |  selectionCriteria.count=${activeWritingAgentConfig.selectionCriteria.size}
                    |  selectionStrategy=${activeWritingAgentConfig.selectionStrategy}
                    |  storyWeights=(geo=${activeWritingAgentConfig.storyWeights.geopolitics}, absur=${activeWritingAgentConfig.storyWeights.absurdity}, dream=${activeWritingAgentConfig.storyWeights.dreamlikeQualities}, twist=${activeWritingAgentConfig.storyWeights.unexpectedTwists})
                    |  authorEnabled=${activeWritingAgentConfig.authorEnabled}, alwaysApplyRulesEnabled=${activeWritingAgentConfig.alwaysApplyRulesEnabled}, guardrailsEnabled=${activeWritingAgentConfig.guardrailsEnabled}
                    |  writingInstructions.length=${activeWritingAgentConfig.writingInstructions.length}, procedure.length=${activeWritingAgentConfig.procedure.length}
                """.trimMargin())

                // Load the world structure from the shared library
                val newWorld = structs.loadWorldFromMapPack(packBytes)
                Logger.debug(LogCategory.GENERAL, "WorldManager: Parsed world '${newWorld.name}' with ${newWorld.mapTiles.size} tiles and ${newWorld.activePlayers.size} activePlayers")

                val tileSummaries = newWorld.mapTiles
                    .take(16)
                    .joinToString(" | ") { tile ->
                        val borderCount = listOf(
                            tile.northBorders.size,
                            tile.southBorders.size,
                            tile.eastBorders.size,
                            tile.westBorders.size,
                            tile.northEastBorders.size,
                            tile.northWestBorders.size,
                            tile.southEastBorders.size,
                            tile.southWestBorders.size
                        ).sum()
                        val neighborNames = tile.adjacentTerritoryNames.joinToString(", ") { it }
                        "${tile.name}(borders=$borderCount, neighbors=${neighborNames.ifBlank { "none" }}/ruler='${tile.ruler}')"
                    }
                Logger.debug(LogCategory.GENERAL, "WorldManager: Tile adjacency summary (first ${newWorld.mapTiles.take(16).size}): $tileSummaries")

                // Update global state
                world = newWorld
                activeMapPackBytes = packBytes
                activeMapPackName = source

                Logger.info(LogCategory.GENERAL, "WorldManager: Successfully loaded map '${newWorld.name}'. " +
                        "Tiles: ${newWorld.mapTiles.size}, Scenario: '${newWorld.storyScenario}'")
            }
            catch (e: Exception)
            {
                Logger.error(LogCategory.GENERAL, "WorldManager: Failed to load map from $source: ${e.message}")
                throw e
            }
        }
    }

    /**
     * wrapper to load map from resources
     */
    suspend fun loadMapFromResources(resourcePath: String)
    {
        val contextClassLoader = Thread.currentThread().contextClassLoader
        val resourceStream = contextClassLoader.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")

        val bytes = resourceStream.use { it.readBytes() }
        loadMapFromPack(bytes, "resource:$resourcePath")
    }

    /**
     * Load the audio-tracks catalog (drone/melody/rhythm/harmony plus the
     * four scenario tabs menu/start/nemesis/end) from a JSON byte payload
     * and install it onto [world] under [World.audioTracks].
     *
     * Wraps the parse in [worldMutex] so it serialises against the rest of
     * the harness (map loads, player mutations, turn-timer updates). On a
     * parse failure the world is left in its previous state and the
     * exception is rethrown — callers (see `GameInit.defineGameRules`)
     * decide whether to log and continue or to propagate.
     *
     * @param bytes The raw JSON bytes (UTF-8 encoded).
     * @throws kotlinx.serialization.SerializationException on bad JSON.
     */
    suspend fun loadAudioTracksFromBytes(bytes: ByteArray)
    {
        worldMutex.withLock {
            val tracks = AudioTracksResourceLoader.loadFromBytes(bytes)
            world.audioTracks = tracks
            Logger.info(
                LogCategory.GENERAL,
                "WorldManager: Audio tracks installed onto world (drone=${tracks.drone.size}, melody=${tracks.melody.size}, " +
                    "rhythm=${tracks.rhythm.size}, harmony=${tracks.harmony.size}, " +
                    "menu=${tracks.menu.size}, start=${tracks.start.size}, " +
                    "nemesis=${tracks.nemesis.size}, end=${tracks.end.size})."
            )
        }
    }

    /**
     * Convenience wrapper that reads the audio-tracks JSON from the
     * server's bundled resources (typically `audio/audio-tracks.json`)
     * and installs it onto [world]. Used by `GameInit.defineGameRules`
     * once the map has been picked.
     *
     * @param resourcePath Classpath-relative path, e.g.
     *   `"audio/audio-tracks.json"`.
     * @throws IllegalArgumentException if the resource is not on the
     *   classpath.
     * @throws kotlinx.serialization.SerializationException on bad JSON.
     */
    suspend fun loadAudioTracksFromResource(resourcePath: String)
    {
        worldMutex.withLock {
            Logger.info(LogCategory.GENERAL, "WorldManager: Loading audio tracks from resource $resourcePath")
            val tracks = AudioTracksResourceLoader.loadFromResource(resourcePath)
            world.audioTracks = tracks
            Logger.info(
                LogCategory.GENERAL,
                "WorldManager: Audio tracks installed onto world from resource $resourcePath " +
                    "(drone=${tracks.drone.size}, melody=${tracks.melody.size}, " +
                    "rhythm=${tracks.rhythm.size}, harmony=${tracks.harmony.size}, " +
                    "menu=${tracks.menu.size}, start=${tracks.start.size}, " +
                    "nemesis=${tracks.nemesis.size}, end=${tracks.end.size})."
            )
        }
    }

    /**
     * Calculates the percent share of active map territory points controlled by the named owner.
     *
     * Destroyed territories are excluded from the map total and owner total. Ownership is resolved from
     * `territory.ruler` on `world.mapTiles` and is matched case-insensitively.
     *
     * @param ownerName Name of the player or NPC whose territory-point share is being measured.
     * @return The owner's share as a percent in the range `0.0..100.0`. Returns `0.0` when no active points remain.
     */
    fun getOwnerTerritoryPointSharePercent(ownerName: String): Double
    {
        val totalActivePoints = getTotalActiveMapTerritoryPoints()
        if(totalActivePoints <= 0)
        {
            return 0.0
        }

        val ownerPoints = getOwnerActiveTerritoryPoints(ownerName)
        return (ownerPoints.toDouble() / totalActivePoints.toDouble()) * 100.0
    }

    /**
     * Checks whether a named owner controls at least the requested share of active map territory points.
     *
     * Threshold values are clamped to `0.0..100.0` before evaluation. Destroyed territories are excluded from
     * both map total and owner total. If no active map points remain, this helper always returns `false`.
     *
     * @param ownerName Name of the player or NPC whose ownership share should be evaluated.
     * @param targetPercent Target percent threshold to compare against.
     * @return `true` when the owner's share is greater than or equal to the clamped threshold; `false` otherwise.
     */
    fun hasOwnerTerritoryPointShare(ownerName: String, targetPercent: Double): Boolean
    {
        val totalActivePoints = getTotalActiveMapTerritoryPoints()
        if(totalActivePoints <= 0)
        {
            return false
        }

        val ownerPoints = getOwnerActiveTerritoryPoints(ownerName)
        val sharePercent = (ownerPoints.toDouble() / totalActivePoints.toDouble()) * 100.0
        val threshold = targetPercent.coerceIn(0.0, 100.0)
        return sharePercent >= threshold
    }

    /**
     * Calculates the percent of active (non-destroyed) map territories controlled by the named owner by tile count.
     *
     * Destroyed territories are excluded from both the map total and the owner total. Ownership is resolved from
     * `territory.ruler` on `world.mapTiles` and is matched case-insensitively. When the map has no active tiles,
     * this returns `0.0`.
     *
     * @param ownerName Name of the player or NPC whose territory-count share is being measured.
     * @return The owner's share as a percent in the range `0.0..100.0`. Returns `0.0` when no active tiles remain.
     */
    fun getOwnerTerritoryCountSharePercent(ownerName: String): Double
    {
        val normalizedOwnerName = ownerName.trim()
        if(normalizedOwnerName.isBlank())
        {
            return 0.0
        }

        val activeTiles = world.mapTiles.asSequence().filter { !it.isDestroyed }
        val totalActiveTiles = activeTiles.count()
        if(totalActiveTiles <= 0)
        {
            return 0.0
        }

        val ownerTileCount = activeTiles
            .count { it.ruler.trim().equals(normalizedOwnerName, ignoreCase = true) }
        return (ownerTileCount.toDouble() / totalActiveTiles.toDouble()) * 100.0
    }

    /**
     * Checks whether a named owner controls at least the requested share of active map territories by tile count.
     *
     * Threshold values are clamped to `0.0..100.0` before evaluation. Destroyed territories are excluded from
     * both map total and owner total. If no active tiles remain, this helper always returns `false`.
     *
     * @param ownerName Name of the player or NPC whose ownership share should be evaluated.
     * @param targetPercent Target percent threshold to compare against.
     * @return `true` when the owner's share is greater than or equal to the clamped threshold; `false` otherwise.
     */
    fun hasOwnerTerritoryCountShare(ownerName: String, targetPercent: Double): Boolean
    {
        val normalizedOwnerName = ownerName.trim()
        if(normalizedOwnerName.isBlank())
        {
            return false
        }

        val activeTiles = world.mapTiles.asSequence().filter { !it.isDestroyed }
        val totalActiveTiles = activeTiles.count()
        if(totalActiveTiles <= 0)
        {
            return false
        }

        val ownerTileCount = activeTiles
            .count { it.ruler.trim().equals(normalizedOwnerName, ignoreCase = true) }
        val sharePercent = (ownerTileCount.toDouble() / totalActiveTiles.toDouble()) * 100.0
        val threshold = targetPercent.coerceIn(0.0, 100.0)
        return sharePercent >= threshold
    }


    /**
     * Calculates the percent of territories currently marked destroyed on the map.
     *
     * This helper uses territory count (not point value). When the map has no territories, this returns `0.0`.
     *
     * @return Destroyed-territory percentage in the range `0.0..100.0`.
     */
    fun getDestroyedTerritoryPercent(): Double
    {
        val totalTerritories = world.mapTiles.size
        if(totalTerritories <= 0)
        {
            return 0.0
        }

        val destroyedTerritories = world.mapTiles.count { it.isDestroyed }
        return (destroyedTerritories.toDouble() / totalTerritories.toDouble()) * 100.0
    }

    /**
     * Checks whether destroyed-territory percentage meets or exceeds a threshold.
     *
     * Threshold values are clamped to `0.0..100.0`. When no territories exist on the map, this returns `false`.
     *
     * @param targetPercent Target destroyed percentage threshold.
     * @return `true` when destroyed percent is greater than or equal to the clamped threshold; `false` otherwise.
     */
    fun hasDestroyedTerritoryShare(targetPercent: Double): Boolean
    {
        val totalTerritories = world.mapTiles.size
        if(totalTerritories <= 0)
        {
            return false
        }

        val destroyedPercent = getDestroyedTerritoryPercent()
        val threshold = targetPercent.coerceIn(0.0, 100.0)
        return destroyedPercent >= threshold
    }

    /**
     * Sums all map territory points that are still active.
     */
    private fun getTotalActiveMapTerritoryPoints(): Int
    {
        return world.mapTiles
            .asSequence()
            .filter { !it.isDestroyed }
            .sumOf { it.pointValue }
    }

    /**
     * Sums active territory points controlled by the provided owner name.
     */
    internal fun getOwnerActiveTerritoryPoints(ownerName: String): Int
    {
        val normalizedOwnerName = ownerName.trim()
        if(normalizedOwnerName.isBlank())
        {
            return 0
        }

        return world.mapTiles
            .asSequence()
            .filter { !it.isDestroyed }
            .filter { territory ->
                territory.ruler.trim().equals(normalizedOwnerName, ignoreCase = true)
            }
            .sumOf { it.pointValue }
    }

    internal fun ownerHasTerritories(ownerName: String): Boolean
    {
        return getOwnerActiveTerritoryPoints(ownerName) > 0
    }

    /**
     * Result of adjacency validation check.
     */
    data class AdjacencyValidationResult(
        val isValid: Boolean,
        val reason: String,
        val closestOwnedTerritory: String? = null,
        val distance: Int = Int.MAX_VALUE
    )

    /**
     * Validates whether a player owns a territory adjacent to the target territory.
     * 
     * @param playerName Name of the player attempting to capture
     * @param territoryName Name of the territory being captured
     * @return AdjacencyValidationResult with validation status and details
     */
    fun validateTerritoryAdjacency(playerName: String, territoryName: String): AdjacencyValidationResult
    {
        Logger.debug(LogCategory.GENERAL, "validateTerritoryAdjacency: player='$playerName', territory='$territoryName', round=${world.roundNumber}, mapTiles=${world.mapTiles.size}")

        // NPCs are immune to adjacency rules because they often start without any territory
        val isNpc = world.npc.any { it.name.equals(playerName, ignoreCase = true) }
        if (isNpc)
        {
            Logger.info(LogCategory.GENERAL, "validateTerritoryAdjacency: '$playerName' is an NPC; bypassing adjacency check.")
            return AdjacencyValidationResult(isValid = true, reason = "NPCs are immune to adjacency rules")
        }

        val player = world.findPlayerByName(playerName)
        if (player == null)
        {
            Logger.warn(LogCategory.GENERAL, "validateTerritoryAdjacency: player not found ('$playerName')")
            return AdjacencyValidationResult(false, "Player not found")
        }

        Logger.debug(LogCategory.GENERAL, "validateTerritoryAdjacency: found player '${player.name}' with capturedTerritory=${player.capturedTerritory.size} and startingTile='${player.startingTile.name}'")

        val territory = world.mapTiles.findTerritoryByName(territoryName)
        if (territory == null)
        {
            Logger.warn(LogCategory.GENERAL, "validateTerritoryAdjacency: territory not found ('$territoryName')")
            return AdjacencyValidationResult(false, "Territory not found")
        }

        Logger.debug(LogCategory.GENERAL, "validateTerritoryAdjacency: checking adjacency for territory '${territory.name}' (ruler='${territory.ruler}', destroyed=${territory.isDestroyed})")

        val hasAdjacent = world.hasAdjacentTerritory(territory, player)

        Logger.debug(LogCategory.GENERAL, "validateTerritoryAdjacency: adjacency check result for '$territoryName' -> $hasAdjacent")

        if (!hasAdjacent)
        {
            val closestTerritory = player.capturedTerritory.minByOrNull {
                world.getTerritoryDistance(it, territory).distance
            }
            val distance = closestTerritory?.let { world.getTerritoryDistance(it, territory).distance } ?: Int.MAX_VALUE

            Logger.info(
                LogCategory.GENERAL,
                "validateTerritoryAdjacency: player '${player.name}' has no adjacent territory to '$territoryName'. " +
                        "Closest owned territory='${closestTerritory?.name}' distance=$distance. Note: Non-adjacent captures are permitted but penalized."
            )

            return AdjacencyValidationResult(
                isValid = true, // Capture is allowed, penalties are applied elsewhere
                reason = "Player does not own any territory adjacent to '$territoryName'. Closest owned territory is '${closestTerritory?.name}' at distance $distance.",
                closestOwnedTerritory = closestTerritory?.name,
                distance = distance
            )
        }

        Logger.info(LogCategory.GENERAL, "validateTerritoryAdjacency: player '${player.name}' owns an adjacent territory to '$territoryName'")
        return AdjacencyValidationResult(isValid = true, reason = "Player owns adjacent territory", distance = 1)
    }

    /**
     * Applies territory stat changes from judge results.
     * 
     * @param statChanges List of territory stat changes to apply
     */
    internal fun applyTerritoryStatChanges(statChanges: List<agent.builders.judgeOutcome.TerritoryStatChange>)
    {
        statChanges.forEach { change ->
            val territory = world.mapTiles.findTerritoryByName(change.territoryName)
            if (territory == null)
            {
                Logger.warn(LogCategory.GENERAL, "[TERRITORY_STATS] Territory not found: ${change.territoryName}")
                return@forEach
            }

            territory.militaryThreatStat += change.militaryThreatStat
            territory.diplomacyThreatStat += change.diplomacyThreatStat

            Logger.info(LogCategory.GENERAL, "[TERRITORY_STATS] Applied stat changes to ${change.territoryName}: military ${if (change.militaryThreatStat >= 0) "+" else ""}${change.militaryThreatStat}, diplomacy ${if (change.diplomacyThreatStat >= 0) "+" else ""}${change.diplomacyThreatStat}. Reason: ${change.reasoning}")
        }
    }

    /**
     * Deposes a territory's government, making it neutral and applying stat debuffs.
     * 
     * @param territoryName Name of territory to depose
     * @param turnNumber Current turn number
     * @param timestampMillis Timestamp of deposition
     */
    private fun deposeTerritory(territoryName: String, turnNumber: Int, timestampMillis: Long)
    {
        val territory = world.mapTiles.findTerritoryByName(territoryName)
        if (territory == null)
        {
            Logger.warn(LogCategory.GENERAL, "[TERRITORY_DEPOSE] Territory not found: $territoryName")
            return
        }

        val previousOwner = territory.ruler
        
        // Remove from owner's list
        if (previousOwner.isNotBlank())
        {
            val ownerPlayer = world.findPlayerByName(previousOwner)
            val ownerNpc = if (ownerPlayer == null) world.findNpcByName(previousOwner) else null
            
            ownerPlayer?.capturedTerritory?.removeIf { it.name.equals(territoryName, ignoreCase = true) }
            ownerNpc?.capturedTerritory?.removeIf { it.name.equals(territoryName, ignoreCase = true) }
        }

        // Make neutral
        territory.ruler = ""
        territory.isCaptured = false

        // Apply severe stat debuffs
        territory.militaryThreatStat -= 30
        territory.diplomacyThreatStat -= 30

        Logger.info(LogCategory.GENERAL, "[TERRITORY_DEPOSE] Deposed government of $territoryName (previously owned by '$previousOwner'). Applied -30 to both stats.")
    }

    /**
     * Checks if a player is reachable via the network or is explicitly marked as AI-controlled.
     * 
     * ### Reachability Rules:
     * 1. If player stats are missing, defaults to reachable=true.
     * 2. If player is marked as AI-controlled ([serverStructs.PlayerStats.isControlledByNpc]), returns false.
     * 3. If player ID is blank, defaults to reachable=true.
     * 4. If a WebSocket session exists, performs a network ping with a 3s timeout.
     * 
     * @param player The [Player] whose reachability is being verified.
     * @return True if the player is a human with a live connection; false if AI-controlled or unreachable.
     */
    suspend fun isReachable(player: Player): Boolean
    {
        Logger.debug(LogCategory.NETWORK, "WorldManager.isReachable: Checking reachability for player='${player.name}'")
        
        val stats = findPlayerFromStats(player.name)
        if(stats == null)
        {
            Logger.warn(LogCategory.NETWORK, "WorldManager.isReachable: No PlayerStats found for '${player.name}', defaulting to reachable=true")
            return true
        }

        if(stats.isControlledByNpc)
        {
            Logger.info(LogCategory.NETWORK, "WorldManager.isReachable: Player '${player.name}' is marked as AI-controlled - returning false to trigger immediate takeover")
            return false
        }
        
        val playerId = stats.playerID
        if(playerId.isBlank())
        {
            Logger.warn(LogCategory.NETWORK, "WorldManager.isReachable: PlayerID is blank for '${player.name}', defaulting to reachable=true")
            return true
        }

        Logger.debug(LogCategory.NETWORK, "WorldManager.isReachable: Looking up WebSocket session for playerId='$playerId'")
        val connectionManager = org.ttt.autogenesis.server.UiSignalRpcHandlers.connectionManager

        // Check ALL sessions for this playerId — PRIMARY (browser) + CONTROLLER (Python) alike.
        // If ANY session responds to ping, the player is reachable.
        val allSessions = connectionManager?.findAllSessions(playerId) ?: emptyList()
        val reachable = if(allSessions.isNotEmpty())
        {
            Logger.debug(LogCategory.NETWORK, "WorldManager.isReachable: Found ${allSessions.size} sessions for playerId='$playerId', pinging all...")
            // Try each session; reachability is true if at least one responds.
            var success = false
            for(session in allSessions)
            {
                Logger.debug(LogCategory.NETWORK, "WorldManager.isReachable: Pinging ${session.role} session for '$playerId'...")
                val pong = connectionManager?.ping(playerId, timeoutMillis = 15000L) ?: false
                if(pong)
                {
                    Logger.info(LogCategory.NETWORK, "WorldManager.isReachable: Ping SUCCESS via ${session.role} session for '$playerId'")
                    success = true
                    break
                }
                else
                {
                    Logger.debug(LogCategory.NETWORK, "WorldManager.isReachable: Ping failed for ${session.role} session, trying next...")
                }
            }
            if(!success)
            {
                Logger.info(LogCategory.NETWORK, "WorldManager.isReachable: All ${allSessions.size} sessions for '$playerId' failed ping — marking UNREACHABLE")
            }
            success
        }
        else
        {
            Logger.debug(LogCategory.NETWORK, "WorldManager.isReachable: No sessions found for playerId='$playerId'")
            false
        }
        
        Logger.info(LogCategory.NETWORK, "WorldManager.isReachable: Final reachability for '${player.name}' (playerId='$playerId'): ${if(reachable) "CONNECTED" else "DISCONNECTED"}")
        
        if(reachable)
        {
            stats.isConnected = true
            stats.isControlledByNpc = false
            Logger.debug(LogCategory.NETWORK, "WorldManager.isReachable: Updated stats for '${player.name}' - isConnected=true, isControlledByNpc=false")
        }
        else
        {
            stats.isConnected = false
            stats.isControlledByNpc = true
            Logger.warn(LogCategory.NETWORK, "WorldManager.isReachable: Player '${player.name}' is UNREACHABLE - Updated stats: isConnected=false, isControlledByNpc=true")
            
            // Broadcast disconnection event
            connectionManager?.broadcastConnectionEvent(playerId, org.ttt.autogenesis.network.ConnectionStatus.DISCONNECTED)
        }
        return reachable
    }
}