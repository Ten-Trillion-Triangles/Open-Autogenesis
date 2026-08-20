package org.ttt.autogenesis.server

import agent.builders.modifyGameState.buildNemesisCreationAgent
import agent.builders.playerAgent.buildPlayerAgent
import agent.builders.gameplayActions.buildNpcActorAgent
import agent.builders.gameplayActions.buildHostileNpcAgent
import agent.builders.gameplayActions.buildNemesisAgent
import agent.builders.gameplayActions.buildElderGodAgent
import agent.runners.executeNpcTurn
import agent.runners.executePlayerTurn
import com.TTT.Config.TPipeConfig
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import com.TTT.Util.writeStringToFile
import enums.CommanderTrait
import enums.NpcType
import gameInit.MapSelectionService
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import gameState.WorldManager
import gameState.GameSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.ActiveTurnData
import org.ttt.autogenesis.network.GameOverData
import org.ttt.autogenesis.network.NemesisThreatAnnouncementData
import org.ttt.autogenesis.audio.MusicTrackCatalog
import org.ttt.autogenesis.audio.TurnContext
import org.ttt.autogenesis.server.audio.AudioManager
import org.ttt.autogenesis.server.audio.MusicSelector
import org.ttt.autogenesis.network.NemesisThreatKind
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.TurnOrderAnnouncementData
import org.ttt.autogenesis.network.TurnOrderParticipant
import org.ttt.autogenesis.network.ResolutionStep
import agent.runners.setCurrentTurnFolderName
import agent.runners.getTurnTraceDir
import agent.runners.saveSystemTrace
import org.ttt.autogenesis.server.GenericPrompts
import structs.Npc
import structs.Player
import structs.Resource
import structs.Territory
import structs.GameHistory
import structs.ui.ActionHistory
import structs.ui.ActionHistoryEvent
import structs.ui.GameEventType
import structs.ui.PlayerOutcomeMetadata
import java.io.File
import java.util.UUID
import kotlin.random.Random

/**
 * Central controller for the game's turn-based lifecycle, responsible for round transitions, 
 * active turn orchestration, and game-over evaluation.
 *
 * The [TurnHarness] operates as a state machine driven by a coroutine loop in [runNextTurn]. 
 * it manages the delicate handoff between human player submissions via [submitPlayerPlay] 
 * and automated AI takeovers via [handleAiTakeover].
 *
 * ### Thread Safety
 * Concurrency is managed via specialized [Mutex] locks:
 * - [announcementLock]: Prevents duplicate round-start broadcasts.
 * - [gameOverLock]: Ensures the game-over sequence executes exactly once.
 * - [turnStateLock]: Synchronizes access to the [pendingAwaiters] and [bufferedSubmissions] maps.
 */
object TurnHarness
{
    /** How long the round-start announcement persists on the client before the first turn begins. */
    private const val ROUND_START_DISPLAY_SECONDS = 10L
    /** Default action used when a player is timed out and the system takes control. */
    private val playerFallbackAction = GenericPrompts.PLAYER_GENERIC_FALLBACK
    /** Default behavioral instruction for NPC actors. */
    private val npcFallbackAction = GenericPrompts.NPC_GENERIC_FALLBACK
    /** Fallback action for AI-generated submissions when an awaiter is resumed or takeover occurs. */
    private const val AI_FALLBACK_ACTION = GenericPrompts.PLAYER_GENERIC_FALLBACK
    /** Summit points awarded to players during a Nemesis arrival event. */
    private const val SUMMIT_POINTS_ON_NEMESIS_EVENT = 1

    private const val MILITARY_FLOOR = 30
    private const val LEGITIMACY_FLOOR = 40
    private const val STAGNATION_CAP = 60

    /** Guards the [announceRoundStartIfNeeded] logic to prevent multiple rounds starting simultaneously. */
    private val announcementLock = Mutex()
    /** Guards [evaluateEndGame] to prevent race conditions during victory processing. */
    private val gameOverLock = Mutex()
    /** Synchronizes access to the submission buffers and awaiter maps. */
    private val turnStateLock = Mutex()
    /** Guards access to [processedTurns] to prevent duplicate turn execution. */
    private val turnMutex = Mutex()
    private val rng = Random.Default
    /**
     * Per-turn music picker. Pure function over a [TurnContext]; the harness
     * builds a fresh context at the start of each turn and dispatches the
     * decision via [AudioManager.broadcastMusicSchedule].
     */
    /** Per-turn music picker. The catalog is rebuilt from [WorldManager.world.audioTracks] on every
     *  turn so the editor payload is the source of truth for the picker’s pool — including all
     *  Retrograde / Inversion / Variant / Rotation / Centered / Slow Rotation variants the
     *  editor saved. See [MusicTrackCatalog.fromAudioTracks]. */
    private val musicSelector = MusicSelector(catalog = MusicTrackCatalog.default, random = rng)

    /** Build a runtime catalog from the editor payload loaded at game init. */
    private fun runtimeMusicCatalog(): org.ttt.autogenesis.audio.MusicTrackCatalog =
        org.ttt.autogenesis.audio.MusicTrackCatalog.fromAudioTracks(WorldManager.world.audioTracks)

    private var lastAnnouncedRound = 0
    private var lastActiveNemesisNames: Set<String> = emptySet()
    private var lastDefeatedNemesisNames: Set<String> = emptySet()
    private var gameOverDispatched = false
    /**
     * Callback invoked from [surrenderPlayer] when a successful surrender ends the
     * match AND the world is in single-player mode. The argument is the
     * surrendering player's `connectionId` (taken from [serverStructs.PlayerStats.playerID]),
     * which the production binding (see `Server.kt`) uses to look up the
     * [org.ttt.autogenesis.server.PlayerSession] and call
     * [org.ttt.autogenesis.server.PlayerConnectionManager.deregister].
     *
     * The subsequent `onDisconnected` handler in `Server.kt:385` then arms the
     * 15-second single-player shutdown countdown via
     * [startSinglePlayerShutdownCountdown].
     *
     * Intentionally a no-op in multiplayer — a surrendered player in a still-running
     * match must stay connected so the game can continue with other humans and NPCs.
     *
     * Reset to `null` in tests via `@AfterTest` to avoid cross-test bleed.
     */
    var onPlayerDisconnectedFromSurrender: (suspend (connectionId: String) -> Unit)? = null
    private var turnOrderIndex = 0
    /** Tracks which turns have already been processed to prevent double execution (e.g. from race between timer and loop). */
    private val processedTurns = mutableSetOf<String>()
    /** Unique identifier for the current server session, used to namespace saved world snapshots. */
    private val snapshotId = UUID.randomUUID().toString()
    /** The active coroutine job running the canonical turn loop. */
    private var loopJob: Job? = null
    /** List of NPCs currently participating in the round as interlopers. */
    private var npcInterferenceList: List<String> = emptyList()

    /**
     * The [org.ttt.autogenesis.audio.MusicDecision] most recently
     * broadcast for the active turn, kept so
     * [selectAndBroadcastMusicReroll] can re-roll the random layer
     * tracks at the turn's midpoint (just before the judge agent
     * runs). Set in [selectAndBroadcastMusicForTurn] and cleared in
     * [handlePostTurn] so the next turn starts with a clean slate.
     */
    private var currentTurnMusicDecision: org.ttt.autogenesis.audio.MusicDecision? = null

    /**
     * Active 5-minute music-reroll fallback coroutine. Scheduled by
     * [scheduleMusicRerollFallback] after every successful music switch
     * (turn start and mid-turn reroll). When the delay elapses without
     * another switch, the coroutine calls [selectAndBroadcastMusicReroll]
     * — which is a no-op for scenario-bound decisions (initial / nemesis /
     * terminal) and a fresh rule-4 reroll otherwise. Cancelled in
     * [handlePostTurn] (turn end) and [resetState] (session teardown).
     *
     * Held as a [Job] (not a [kotlinx.coroutines.flow.Flow] or
     * CompletableDeferred) so cancellation is a single, synchronous
     * `cancel()` call. The job runs on [GlobalScope], matching the
     * pattern [loopJob] uses; cancellation is the only thing that can
     * stop it.
     */
    private var musicRerollFallbackJob: Job? = null

    // ===== AccelByte AMS player arrival gate state =====

    /** Guards arrival gate state (expectedPlayers, connectedExpectedPlayers, pendingEarlyConnections). */
    private val arrivalGateLock = Mutex()

    /** Copy of WorldManager.expectedPlayers at session bind time, used for arrival gate. */
    @Volatile
    private var expectedPlayers: Set<String> = emptySet()

    /** Set of expected player AccelByte IDs who have connected. */
    private val connectedExpectedPlayers: MutableSet<String> = mutableSetOf()

    /**
     * Holding queue for connections that arrived before MatchmakingV2ServerClaimed fired.
     * These will be matched to expectedPlayers once the session is bound.
     */
    private val pendingEarlyConnections: MutableList<String> = mutableListOf()

    /** Timeout in milliseconds for all expected players to join before turn loop starts (2 minutes). */
    private val PLAYER_JOIN_TIMEOUT_MS = 120_000L

    /**
     * Wall-clock timeout for the music reroll fallback timer. Started by
     * [scheduleMusicRerollFallback] on every successful music switch (turn
     * start or mid-turn reroll); fires [selectAndBroadcastMusicReroll] when
     * 5 minutes elapse with no switch. Cancelled on [handlePostTurn] and
     * [resetState]. 5 minutes matches the orchestrator's
     * '5 minutes of music-staleness' fallback contract.
     */
    private const val MUSIC_REROLL_FALLBACK_TIMEOUT_MS = 5L * 60L * 1000L

    // ====================================================

    /**
     * Resets all internal state to prepare for a fresh game session.
     * This is called during demo mode resets and unit tests.
     */
    internal suspend fun resetState()
    {
        lastAnnouncedRound = 0
        lastActiveNemesisNames = emptySet()
        lastDefeatedNemesisNames = emptySet()
        gameOverDispatched = false
        turnOrderIndex = 0
        npcInterferenceList = emptyList()
        // Clear the active music decision so a fresh session starts
        // with no rule-4 / scenario-bound carryover from the previous
        // one. Without this, a leftover decision from a prior game or
        // test would let the first selectAndBroadcastMusicReroll hit
        // a non-null previous and broadcast a stale schedule.
        currentTurnMusicDecision = null
        loopJob?.cancel()
        loopJob = null
        // Cancel the 5-minute music-reroll fallback timer so a leftover
        // coroutine cannot fire into the freshly reset session.
        musicRerollFallbackJob?.cancel()
        musicRerollFallbackJob = null
        WorldManager.isGameActive = false
        processedTurns.clear()

        // Ensure turn order is cleared so bootstrap can run
        WorldManager.world.turnOrder.clear()

        // Reset context bank
        ContextBank.emplace("story", ContextWindow())

        // Reset arrival gate state under lock
        arrivalGateLock.withLock {
            expectedPlayers = emptySet()
            connectedExpectedPlayers.clear()
            pendingEarlyConnections.clear()
        }

        // Clear AMS session binding state (suspend, holds sessionBindLock internally)
        WorldManager.clearSession()

        Logger.info(LogCategory.SYSTEM, "TurnHarness: State reset complete. Ready for new session.")
    }

    /**
     * Atomically marks a turn as processed. Returns true if this was the first time it was marked.
     * @param turnKey A unique key for the turn (e.g. actorName-round-index).
     */
    private suspend fun markTurnAsProcessed(turnKey: String): Boolean
    {
        Logger.info(LogCategory.SYSTEM, "TurnHarness.markTurnAsProcessed: Checking if turnKey='$turnKey' is already processed...")
        Logger.info(LogCategory.SYSTEM, "TurnHarness.markTurnAsProcessed: processedTurns currently has ${processedTurns.size} entries: ${processedTurns.joinToString()}")
        val result = turnMutex.withLock {
            if(processedTurns.contains(turnKey))
            {
                Logger.error(LogCategory.SYSTEM, "TurnHarness.markTurnAsProcessed: DUPLICATE! Turn '$turnKey' already processed! Returning FALSE.")
                false
            }
            else
            {
                processedTurns.add(turnKey)
                Logger.info(LogCategory.SYSTEM, "TurnHarness.markTurnAsProcessed: Marked '$turnKey' as processed (total processed: ${processedTurns.size})")
                true
            }
        }
        Logger.info(LogCategory.SYSTEM, "TurnHarness.markTurnAsProcessed: Returning $result for turnKey='$turnKey'")
        return result
    }

    /**
     * Maps player names to [CompletableDeferred] instances waiting for network-driven input.
     * Managed via [turnStateLock].
     */
    private val pendingAwaiters = mutableMapOf<String, CompletableDeferred<PlaySubmission>>()
    /**
     * Stores [PlaySubmission] objects that arrived before the harness was ready to process them.
     * This prevents race conditions where a fast player submits before [awaitPlayerAction] is called.
     */
    private val bufferedSubmissions = mutableMapOf<String, PlaySubmission>()

    /**
     * Returns the list of NPC names that successfully interfered in the current round's turn order.
     * @see [rollNpcInterference]
     */
    internal fun getNpcInterferenceList(): List<String> = npcInterferenceList

    // ===== AccelByte AMS player arrival gate =====

    /**
     * Called from Server.kt when MatchmakingV2ServerClaimed is received.
     * Copies the expected player set from WorldManager and processes any early connections.
     *
     * @param sessionId The bound session ID
     * @param expectedUserIds The list of expected AccelByte user IDs
     */
    suspend fun onSessionBound(sessionId: String, expectedUserIds: List<String>)
    {
        arrivalGateLock.withLock {
            expectedPlayers = expectedUserIds.toSet()
            connectedExpectedPlayers.clear()
            Logger.info(LogCategory.NETWORK, "TurnHarness: Session bound sessionId=$sessionId, expectedPlayers=${expectedUserIds.size}")

            // Process any connections that arrived before the session was bound
            val early = pendingEarlyConnections.toList()
            pendingEarlyConnections.clear()
            for (accelByteId in early)
            {
                if (expectedPlayers.contains(accelByteId))
                {
                    connectedExpectedPlayers.add(accelByteId)
                    Logger.info(LogCategory.NETWORK, "TurnHarness: Early connection matched as expected: $accelByteId")
                }
            }

            val remaining = expectedPlayers - connectedExpectedPlayers
            if (remaining.isEmpty())
            {
                Logger.info(LogCategory.NETWORK, "TurnHarness: All expected players already connected (${connectedExpectedPlayers.size}/${expectedPlayers.size})")
            }
            else
            {
                Logger.info(LogCategory.NETWORK, "TurnHarness: Waiting for ${remaining.size} expected players: $remaining")
            }
        }
    }

    /**
     * Called from Server.kt when a verified expected player connects to the /events WebSocket.
     *
     * @param accelByteId The AccelByte user ID of the player who joined
     */
    suspend fun notifyPlayerJoined(accelByteId: String)
    {
        arrivalGateLock.withLock {
            if (expectedPlayers.isEmpty())
            {
                // No session bound yet — queue for later processing
                if (!pendingEarlyConnections.contains(accelByteId))
                {
                    pendingEarlyConnections.add(accelByteId)
                    Logger.info(LogCategory.NETWORK, "TurnHarness: Early connection queued: $accelByteId (session not yet bound)")
                }
                return
            }

            if (!expectedPlayers.contains(accelByteId))
            {
                Logger.warn(LogCategory.NETWORK, "TurnHarness: notifyPlayerJoined called for unexpected player: $accelByteId (expected: $expectedPlayers)")
                return
            }

            if (connectedExpectedPlayers.contains(accelByteId))
            {
                Logger.debug(LogCategory.NETWORK, "TurnHarness: Player already recorded as joined: $accelByteId")
                return
            }

            connectedExpectedPlayers.add(accelByteId)
            Logger.info(LogCategory.NETWORK, "TurnHarness: Expected player joined: $accelByteId (${connectedExpectedPlayers.size}/${expectedPlayers.size})")
        }
    }

    /**
     * Adds a backfill candidate to the expected players set.
     * Called when BackfillHandler accepts a backfill request.
     *
     * @param accelByteId The AccelByte user ID of the backfill candidate
     */
    suspend fun addExpectedPlayer(accelByteId: String)
    {
        arrivalGateLock.withLock {
            expectedPlayers = expectedPlayers + accelByteId
            Logger.info(LogCategory.NETWORK, "TurnHarness: Added backfill candidate to expected players: $accelByteId (total=${expectedPlayers.size})")
        }
    }

    /**
     * Returns true if all expected players have connected.
     */
    private suspend fun hasAllPlayersJoined(): Boolean
    {
        arrivalGateLock.withLock {
            return connectedExpectedPlayers.size >= expectedPlayers.size
        }
    }

    /**
     * Suspends until all expected players have connected or the join timeout expires.
     * In single-player mode, this returns immediately since no arrival gate is needed.
     */
    private suspend fun awaitAllPlayersJoined()
    {
        // Single-player mode has no arrival gate
        if (WorldManager.isSinglePlayer || expectedPlayers.isEmpty())
        {
            Logger.debug(LogCategory.NETWORK, "TurnHarness: No arrival gate (single-player or no expected players), proceeding immediately")
            return
        }

        if (hasAllPlayersJoined())
        {
            Logger.info(LogCategory.NETWORK, "TurnHarness: All expected players already joined")
            return
        }

        val joinedCount = arrivalGateLock.withLock { connectedExpectedPlayers.size }
        val expectedCount = expectedPlayers.size
        Logger.info(LogCategory.NETWORK, "TurnHarness: Waiting for ${expectedCount - joinedCount} expected players ($joinedCount/$expectedCount)...")

        val deadline = System.currentTimeMillis() + PLAYER_JOIN_TIMEOUT_MS
        while (!hasAllPlayersJoined() && System.currentTimeMillis() < deadline)
        {
            delay(1_000L)
        }

        val finalJoined = arrivalGateLock.withLock { connectedExpectedPlayers.toSet() }
        val finalExpected = expectedPlayers
        if (!hasAllPlayersJoined())
        {
            val missing = finalExpected - finalJoined
            Logger.warn(LogCategory.SYSTEM, "TurnHarness: Player join timeout — proceeding with ${finalJoined.size}/${finalExpected.size} players. Missing: $missing")
        }
        else
        {
            Logger.info(LogCategory.NETWORK, "TurnHarness: All expected players joined (${finalJoined.size}/${finalExpected.size})")
        }
    }

    // =======================================

    /**
     * Returns the local [File] directory where world snapshots for this session are stored.
     */
    internal fun getSnapshotDirectory(): File 
    {
        val turnTraceDir = File(getTurnTraceDir())
        val savedGamesDir = File(turnTraceDir, "saved-games")
        return File(savedGamesDir, snapshotId)
    }

    /**
     * Returns true if the core turn orchestration loop is currently active.
     */
    fun isRunning(): Boolean = loopJob?.isActive == true

    private fun isAiControlledPlayer(actorName: String): Boolean
    {
        val stats = WorldManager.findPlayerFromStats(actorName)
        return stats?.isControlledByNpc == true
    }

    private fun pruneTerritorylessAiPlayers(order: MutableList<String>)
    {
        val toRemove = order.filter { actor ->
            val player = WorldManager.world.findPlayerByName(actor)
            player != null && isAiControlledPlayer(player.name) && !WorldManager.ownerHasTerritories(player.name)
        }
        if(toRemove.isNotEmpty())
        {
            order.removeAll(toRemove)
            Logger.info(LogCategory.SYSTEM, "TurnHarness: Pruned AI players without territory from turn order: ${toRemove.joinToString()}")
        }
    }

    private fun enforceSinglePlayerActorFirst(order: MutableList<String>)
    {
        if(!WorldManager.isSinglePlayer)
        {
            return
        }

        val humanName = WorldManager.humanPlayerName.takeIf { it.isNotBlank() }
            ?: WorldManager.playerStats.firstOrNull { !it.isControlledByNpc }
                ?.playerData
                ?.name
                ?.takeIf { it.isNotBlank() }

        if(humanName.isNullOrBlank())
        {
            return
        }

        val currentIndex = order.indexOfFirst { it == humanName }
        if(currentIndex > 0)
        {
            order.removeAt(currentIndex)
            order.add(0, humanName)
            Logger.info(LogCategory.SYSTEM, "TurnHarness: Reordered single player turn order to put human '$humanName' first")
        }
    }

    private fun selectDominantOwner(exclude: Set<String>): String?
    {
        val owners = WorldManager.world.mapTiles
            .asSequence()
            .filter { !it.isDestroyed }
            .map { it.ruler.trim() }
            .filter { it.isNotBlank() && !exclude.contains(it) }
            .distinct()
        return owners.maxByOrNull { owner -> WorldManager.getOwnerActiveTerritoryPoints(owner) }
    }

    /**
     * Returns the current 0-based index into the turn order.
     */
    internal fun getTurnOrderIndex(): Int = turnOrderIndex

    // ===== Test seams for the 5-minute music-reroll fallback timer =====
    // These are package-internal (visible to tests in the same module) but
    // NOT part of any public API. They exist so unit tests can:
    //  - Verify the job is armed (read the field) and re-armed (compare
    //    job identity across two switches) without using reflection.
    //  - Seed `currentTurnMusicDecision` so a test can drive
    //    `selectAndBroadcastMusicReroll` from a known scenario-bound or
    //    rule-4 starting point without having to play out a full turn.

    /** Test seam: read the active fallback coroutine job, if any. */
    internal fun getMusicRerollFallbackJob(): Job? = musicRerollFallbackJob

    /** Test seam: read the active turn's [org.ttt.autogenesis.audio.MusicDecision], if any. */
    internal fun getCurrentTurnMusicDecision(): org.ttt.autogenesis.audio.MusicDecision? = currentTurnMusicDecision

    /**
     * Test seam: overwrite the active turn's
     * [org.ttt.autogenesis.audio.MusicDecision]. Production code should
     * never need this — `currentTurnMusicDecision` is owned by
     * `selectAndBroadcastMusicForTurn` (set) and `handlePostTurn` /
     * `resetState` (cleared). Tests use it to set up
     * scenario-bound / rule-4 starting points for
     * `selectAndBroadcastMusicReroll` without driving a full turn.
     */
    internal fun setCurrentTurnMusicDecisionForTest(decision: org.ttt.autogenesis.audio.MusicDecision?)
    {
        currentTurnMusicDecision = decision
    }

    /**
     * Registers a callback used by the [org.ttt.autogenesis.server.GameRpcHandlers] to inject
     * validated human play actions into the harness.
     *
     * @return A suspension lambda that routes [RpcCallContext], [Player], and action text into [submitPlayerPlay].
     */
    fun registerPlayCallback(): suspend (RpcCallContext, Player, String) -> Unit
    {
        return { ctx, player, action ->
            submitPlayerPlay(ctx, player, action)
        }
    }

    /**
     * Suspends the current turn execution until the specified [player] submits an action.
     * 
     * ### Logic Flow:
     * 1. Checks [bufferedSubmissions] for early input.
     * 2. Verifies [isReachable] to ensure the player hasn't disconnected.
     * 3. Registers a [CompletableDeferred] in [pendingAwaiters].
     * 4. Starts the [WorldManager] turn timer.
     * 5. Awaits the deferred or [TURN_TIMEOUT_MS].
     *
     * @param player The human [Player] whose turn is being awaited.
     * @return The [PlaySubmission] containing the action text and context, or null on timeout/unreachability.
     */
    private suspend fun awaitPlayerAction(player: Player): PlaySubmission?
    {
        val actorKey = normalizeActorKey(player.name)
        Logger.debug(LogCategory.NETWORK, "TurnHarness.awaitPlayerAction: Entry for player='${player.name}' (actorKey='$actorKey')")
        
        val buffered = turnStateLock.withLock { 
            Logger.debug(LogCategory.SYSTEM, "TurnHarness.awaitPlayerAction: Checking buffered submissions for $actorKey")
            bufferedSubmissions.remove(actorKey) 
        }
        if(buffered != null)
        {
            Logger.info(LogCategory.GENERAL, "TurnHarness.awaitPlayerAction: Found buffered submission for $actorKey, returning immediately")
            return buffered
        }

        Logger.info(LogCategory.NETWORK, "TurnHarness.awaitPlayerAction: Performing reachability check for '${player.name}'...")
        val isReachable = WorldManager.isReachable(player)
        if(!isReachable)
        {
            Logger.warn(LogCategory.NETWORK, "TurnHarness.awaitPlayerAction: Player '${player.name}' is UNREACHABLE - returning null to trigger AI takeover")
            return null
        }

        Logger.info(LogCategory.NETWORK, "TurnHarness.awaitPlayerAction: Player '${player.name}' is reachable, proceeding to wait for action")

        // Mark that the human has actually reached a playable turn. This is
        // the gate signal for the save-on-disconnect path in Server.kt — see
        // [shouldPersistOnDisconnect] and BUG 25 (2026-06-27): a game that
        // was initialized by GameInit but never joined by the human must NOT
        // be persisted to the user's cloud-save record, otherwise the next
        // reconnect "resumes" a phantom round-1 fresh-init snapshot.
        // Mark that the human has actually reached a playable turn. This is
        // the gate signal for the save-on-disconnect path in Server.kt — see
        // [shouldPersistOnDisconnect] and BUG 25 (2026-06-27): a game that
        // was initialized by GameInit but never joined by the human must NOT
        // be persisted to the user's cloud-save record, otherwise the next
        // reconnect "resumes" a phantom round-1 fresh-init snapshot.
        // Compare against humanPlayerName rather than inspecting playerStats
        // — this function is only ever called for the actor whose turn is up,
        // and in single-player mode that is the human by the time
        // isReachable=true.
        if (gameState.WorldManager.humanPlayerName.isNotBlank() &&
            player.name == gameState.WorldManager.humanPlayerName)
        {
            gameState.WorldManager.humanPlayerHasJoinedOnce = true
            Logger.info(LogCategory.SYSTEM, "TurnHarness.awaitPlayerAction: marked humanPlayerHasJoinedOnce=true for '${player.name}' (save-on-disconnect gate now permissive)")
        }
        else
        {
            Logger.debug(LogCategory.NETWORK, "TurnHarness.awaitPlayerAction: skipping humanPlayerHasJoinedOnce flip — player='${player.name}' is not the human (humanPlayerName='${gameState.WorldManager.humanPlayerName}')")
        }

        val deferred = CompletableDeferred<PlaySubmission>()
        turnStateLock.withLock {
            pendingAwaiters[actorKey] = deferred
            Logger.debug(LogCategory.GENERAL, "TurnHarness.awaitPlayerAction: Registered awaiter for ${player.name}")
        }

        Logger.info(LogCategory.SYSTEM, "TurnHarness.awaitPlayerAction: Starting turn timer for ${player.name} (${WorldManager.TURN_DURATION_SECONDS}s)")
        val effectiveTimeoutMs = WorldManager.effectiveTurnTimeoutMs()
        if(effectiveTimeoutMs != WorldManager.TURN_TIMEOUT_MS)
        {
            Logger.info(
                LogCategory.SYSTEM,
                "TurnHarness.awaitPlayerAction: DEBUG SHORT TURN TIMEOUT active: ${effectiveTimeoutMs}ms (default ${WorldManager.TURN_TIMEOUT_MS}ms)"
            )
        }
        WorldManager.startTurnTimer(player.name, WorldManager.TURN_DURATION_SECONDS)

        Logger.debug(LogCategory.NETWORK, "TurnHarness.awaitPlayerAction: Suspending for ${player.name}'s action (timeout=${effectiveTimeoutMs}ms)")
        val submission = withTimeoutOrNull(effectiveTimeoutMs) {
            deferred.await()
        }
        Logger.info(LogCategory.NETWORK, "TurnHarness.awaitPlayerAction: Resumed from wait for ${player.name}. Submission received: ${submission != null}")
        
        WorldManager.stopTurnTimer()

        turnStateLock.withLock {
            Logger.info(LogCategory.SYSTEM, "TurnHarness.awaitPlayerAction: Removing awaiter for $actorKey (pendingAwaiters was ${pendingAwaiters.size} entries)")
            pendingAwaiters.remove(actorKey)
            Logger.debug(LogCategory.SYSTEM, "TurnHarness.awaitPlayerAction: After removal, pendingAwaiters has ${pendingAwaiters.size} entries")
        }

        if(submission == null)
        {
            Logger.warn(LogCategory.GENERAL, "TurnHarness.awaitPlayerAction: TIMEOUT EXPIRED for ${player.name}!")
        }
        else
        {
            Logger.info(LogCategory.GENERAL, "TurnHarness: Received submission from ${player.name} (length=${submission.action.length}).")
        }
        return submission
    }

    /**
     * Triggers a round start announcement if the [WorldManager.world] round number has increased.
     */
    suspend fun startRoundIfNeeded()
    {
        announceRoundStartIfNeeded()
    }

    /**
     * Advances the internal turn pointer. If the turn order is exhausted, wraps back to 0
     * and increments the global round number.
     */
    suspend fun advanceTurn()
    {
        advanceTurnIndexAndRoundIfNeeded()
    }

    /**
     * Build a [TurnContext] for the current turn and dispatch the
     * [MusicSelector] decision via [AudioManager.broadcastMusicSchedule].
     *
     * Called from [executeSingleTurn] right after the turn-start broadcast.
     * The hook is non-fatal: if the audio RPC ever fails, the turn itself
     * continues (music is a UX concern, not a game-state one).
     */
    private fun selectAndBroadcastMusicForTurn(actor: String)
    {
        try
        {
            val world = WorldManager.world
            val isFirstTurn = world.roundNumber == 1 && turnOrderIndex == 0
            val actorNpc = world.findNpcByName(actor)
            val actorIsNemesisOrElderGod = actorNpc != null &&
                (actorNpc.type == NpcType.Nemesis || actorNpc.type == NpcType.ElderGod)
            val canWinIn4 = MusicSelector.canWinInNext4Rounds(world)
            val currentlyPlayingMusicIds = AudioManager.playingObjects.values
                .filter { it.channelId == org.ttt.autogenesis.audio.AudioChannelIds.MUSIC_MASTER_ID }
                .map { it.id }
            val ctx = TurnContext(
                actorName = actor,
                roundNumber = world.roundNumber,
                turnOrderIndex = turnOrderIndex,
                isFirstTurn = isFirstTurn,
                actorIsNemesisOrElderGod = actorIsNemesisOrElderGod,
                canWinInNext4Rounds = canWinIn4,
                currentlyPlayingMusicIds = currentlyPlayingMusicIds
            )
            val runtimeCatalog = runtimeMusicCatalog()
            val perTurnSelector = MusicSelector(catalog = runtimeCatalog, random = rng)
            val decision = perTurnSelector.selectForTurn(ctx)
            val connectionManager = UiSignalRpcHandlers.connectionManager
            org.ttt.autogenesis.server.audio.AudioManager.broadcastMusicSchedule(decision, connectionManager)
            currentTurnMusicDecision = decision
            // Re-arm the 5-minute fallback timer. Every successful music
            // switch (turn start here, judge reroll in
            // selectAndBroadcastMusicReroll) starts a fresh 5-min clock;
            // the timer body re-arms again on its own if the reroll that
            // it triggered also succeeded.
            scheduleMusicRerollFallback()
        }
        catch(e: Exception)
        {
            Logger.warn(
                LogCategory.GENERAL,
                "TurnHarness.selectAndBroadcastMusicForTurn: music selection failed (non-fatal): ${e.message}"
            )
        }
    }

    /**
     * Mid-turn music reroll. Called by the orchestrators at the start
     * of the judge phase (player turn) or the start of the NPC judge
     * block (NPC turn) so the music stays fresh during long AI turns
     * whose upstream provider stalls or whose LLM player babbles on
     * about its plans before acting.
     *
     * The reroll re-uses the same `MusicSelector` pipeline as the
     * turn-start pick, but only for the random layer tracks (drone /
     * melody / rhythm / harmony). If the active turn was a
     * scenario-bound pick — rule 1 initial conditions, rule 2 nemesis,
     * or rule 3 terminal conditions — [MusicSelector.reselectRandomLayers]
     * returns `null` and this method no-ops. The user explicitly called
     * out the terminal-conditions ("End") track as the endgame track
     * that must never be swapped at the midpoint; the implementation
     * treats the initial and nemesis categories the same way.
     *
     * On a successful reroll, the previous random layer tracks are
     * added to the new `toFadeOut` and a fresh rule-4 set is broadcast
     * via [AudioManager.broadcastMusicSchedule]. The fade-out reaches
     * the current successor of any loop-with-tail chain through the
     * engine's `familyToPlayer` map (the prior session's
     * [AudioEngine] `playFromCache(familyId = familyId)` fix is what
     * makes this work).
     *
     * Safe to call multiple times per turn (idempotent on a no-op
     * return) and non-fatal: any exception in the reroll or the
     * broadcast is caught and logged at WARN, matching the
     * non-fatal style of [selectAndBroadcastMusicForTurn].
     */
    fun selectAndBroadcastMusicReroll()
    {
        try
        {
            val previous = currentTurnMusicDecision
            if(previous == null)
            {
                Logger.debug(
                    LogCategory.SYSTEM,
                    "TurnHarness.selectAndBroadcastMusicReroll: no active turn music decision — skipping"
                )
                return
            }
            val runtimeCatalog = runtimeMusicCatalog()
            val perTurnSelector = MusicSelector(catalog = runtimeCatalog, random = rng)
            val reroll = perTurnSelector.reselectRandomLayers(previous)
            if(reroll == null)
            {
                Logger.info(
                    LogCategory.SYSTEM,
                    "TurnHarness.selectAndBroadcastMusicReroll: mid-turn reroll skipped: previous decision was scenario-bound or pools empty " +
                    "(previous.toPlay.size=${previous.toPlay.size}, resources=[${previous.toPlay.joinToString { "'${it.resourceName}'" }}])"
                )
                return
            }
            val connectionManager = UiSignalRpcHandlers.connectionManager
            org.ttt.autogenesis.server.audio.AudioManager.broadcastMusicSchedule(reroll, connectionManager)
            currentTurnMusicDecision = reroll
            // Re-arm the 5-minute fallback timer. Re-arming here is the
            // 'reset on every switch' contract — the user explicitly
            // wants the timer to refresh whenever music changes, whether
            // by turn start, judge reroll, or the timer itself firing
            // and triggering another reroll. The no-op return paths above
            // (no previous decision / scenario-bound previous) must NOT
            // re-arm: a scenario-bound turn is exactly the case the
            // 5-minute fallback must not interrupt.
            scheduleMusicRerollFallback()
            Logger.info(
                LogCategory.SYSTEM,
                "TurnHarness.selectAndBroadcastMusicReroll: mid-turn reroll applied — +${reroll.toPlay.size} tracks, -${reroll.toFadeOut.size} fade-outs, " +
                "resources=[${reroll.toPlay.joinToString { "'${it.resourceName}'" }}]"
            )
        }
        catch(e: Exception)
        {
            Logger.warn(
                LogCategory.GENERAL,
                "TurnHarness.selectAndBroadcastMusicReroll: music reroll failed (non-fatal): ${e.message}"
            )
        }
    }

    /**
     * Schedule (or re-arm) the 5-minute music-reroll fallback timer.
     *
     * Cancels any in-flight [musicRerollFallbackJob] and launches a fresh
     * one on [GlobalScope]. The coroutine delays for [delayMs] (default
     * [MUSIC_REROLL_FALLBACK_TIMEOUT_MS]) and then calls
     * [selectAndBroadcastMusicReroll]. If that reroll succeeds, it
     * re-arms the timer (its own success path calls this method), so
     * the fallback is a self-sustaining loop until either:
     *  - the turn ends ([handlePostTurn] cancels the job), or
     *  - the session resets ([resetState] cancels the job), or
     *  - the reroll that the timer triggered hits a scenario-bound
     *    previous decision and the no-op return path is taken without
     *    re-arming, breaking the chain.
     *
     * Behaviour matches the existing GlobalScope pattern used by
     * [loopJob] and `AudioManager.broadcastMusicSchedule` — the
     * trade-off (no parent-child structured-concurrency lifetime) is
     * the same one the rest of the harness accepts. Cancellation is
     * the only thing that can stop a scheduled fallback; a
     * [CancellationException] thrown by `delay()` is re-thrown to
     * honour structured cancellation contract, and any other exception
     * is caught and logged at WARN.
     *
     * @param delayMs override for tests. Production code uses the
     *   default [MUSIC_REROLL_FALLBACK_TIMEOUT_MS].
     */
    internal fun scheduleMusicRerollFallback(delayMs: Long = MUSIC_REROLL_FALLBACK_TIMEOUT_MS)
    {
        // Defensive cancel: every re-arm starts from a clean slate so a
        // fast succession of switches does not pile up timers.
        musicRerollFallbackJob?.cancel()
        musicRerollFallbackJob = GlobalScope.launch {
            try
            {
                Logger.info(
                    LogCategory.SYSTEM,
                    "TurnHarness.scheduleMusicRerollFallback: armed for ${delayMs}ms"
                )
                delay(delayMs)
                Logger.info(
                    LogCategory.SYSTEM,
                    "TurnHarness.scheduleMusicRerollFallback: timer elapsed — invoking selectAndBroadcastMusicReroll"
                )
                selectAndBroadcastMusicReroll()
            }
            catch(e: CancellationException)
            {
                // Honour structured cancellation contract — never swallow.
                throw e
            }
            catch(e: Exception)
            {
                Logger.warn(
                    LogCategory.GENERAL,
                    "TurnHarness.scheduleMusicRerollFallback: fallback reroll failed (non-fatal): ${e.message}"
                )
            }
        }
    }

    /**
     * Evaluates all win/loss conditions and triggers the [org.ttt.autogenesis.network.GameOverData]
     * broadcast if a champion is determined.
     */
    suspend fun evaluateVictory()
    {
        evaluateEndGame()
    }

    /**
     * Manually triggers a serialization of the current [WorldManager] state to disk.
     */
    suspend fun serializeWorldSnapshot()
    {
        serializeCurrentWorldSnapshot()
    }

    /**
     * Starts the main turn loop coroutine if it is not already running.
     * Sets [WorldManager.isGameActive] to true and begins processing turns until the game ends.
     */
    fun runNextTurn()
    {
        if(loopJob?.isActive == true)
        {
            Logger.debug(LogCategory.GENERAL, "TurnHarness: runNextTurn invoked while loop already active (round=${WorldManager.world.roundNumber}).")
            return
        }

        Logger.info(LogCategory.SYSTEM, "TurnHarness.runNextTurn: Starting turn loop (round=${WorldManager.world.roundNumber}, turnOrderIndex=$turnOrderIndex).")
        Logger.info(LogCategory.SYSTEM, "TurnHarness.runNextTurn: About to launch GlobalScope coroutine...")
        loopJob = GlobalScope.launch {
            Logger.info(LogCategory.SYSTEM, "TurnHarness Loop: COROUTINE STARTED")
            try
            {
                // Gate: wait for all expected players before starting turn loop
                Logger.info(LogCategory.SYSTEM, "TurnHarness Loop: Waiting for all expected players...")
                awaitAllPlayersJoined()
                WorldManager.isGameActive = true
                Logger.info(LogCategory.SYSTEM, "TurnHarness Loop: Bootstrapping turn order...")
                bootstrapTurnOrderIfNeeded()
                Logger.info(LogCategory.SYSTEM, "TurnHarness Loop: Starting round if needed...")
                startRoundIfNeeded()
                Logger.info(LogCategory.SYSTEM, "TurnHarness Loop: Entering while(WorldManager.isGameActive=${WorldManager.isGameActive}) loop")
                while(WorldManager.isGameActive)
                {
                    Logger.info(LogCategory.SYSTEM, "TurnHarness Loop: ===== TOP OF LOOP ITERATION =====")
                    Logger.info(LogCategory.SYSTEM, "TurnHarness Loop: Current state: round=${WorldManager.world.roundNumber}, turnOrderIndex=$turnOrderIndex, isGameActive=${WorldManager.isGameActive}")
                    Logger.info(LogCategory.SYSTEM, "TurnHarness Loop: Executing single turn...")
                    val result = executeSingleTurn()
                    if(result != null)
                    {
                        Logger.info(LogCategory.SYSTEM, "TurnHarness Loop: executeSingleTurn returned result for ${result.actorName}. Calling handlePostTurn...")
                        handlePostTurn(result)
                        Logger.info(LogCategory.SYSTEM, "TurnHarness Loop: handlePostTurn completed.")
                    }
                    else
                    {
                        Logger.warn(LogCategory.SYSTEM, "TurnHarness Loop: executeSingleTurn returned null! State: round=${WorldManager.world.roundNumber}, turnOrderIndex=$turnOrderIndex, turnOrder=${WorldManager.world.turnOrder}, activePlayers=${WorldManager.world.activePlayers.map { it.name }}, isGameActive=${WorldManager.isGameActive}")
                        Logger.warn(LogCategory.SYSTEM, "TurnHarness Loop: handlePostTurn SKIPPED - turnOrderIndex will NOT increment, next turn will reuse same index!")
                        Logger.warn(LogCategory.SYSTEM, "TurnHarness Loop: This indicates a FROZEN state - same turn will be retried!")
                    }
                }
                Logger.info(LogCategory.SYSTEM, "TurnHarness Loop: while loop exited (isGameActive=${WorldManager.isGameActive})")
            }
            catch(e: Exception)
            {
                Logger.error(LogCategory.SYSTEM, "TurnHarness Loop: CRASHED with exception: ${e.message}")
                e.printStackTrace()
            }
            finally
            {
                Logger.info(LogCategory.GENERAL, "TurnHarness: Turn loop ended. isGameActive=${WorldManager.isGameActive}")
                loopJob = null
            }
        }
        Logger.info(LogCategory.SYSTEM, "TurnHarness.runNextTurn: loopJob launched, returning.")
    }

    /**
     * Injects a player's natural language action into the harness.
     * 
     * If [awaitPlayerAction] is currently waiting for this player, the submission is delivered
     * immediately. Otherwise, it is placed in [bufferedSubmissions] for future pickup.
     *
     * @param context Network context for the caller.
     * @param player The [Player] entity submitting the move.
     * @param action The raw text describing the intended action.
     */
    suspend fun submitPlayerPlay(context: RpcCallContext, player: Player, action: String)
    {
        val key = normalizeActorKey(player.name)
        Logger.info(LogCategory.GENERAL, "TurnHarness.submitPlayerPlay: ENTRY for ${player.name} (key=$key, actionLength=${action.length}).")
        Logger.debug(LogCategory.SYSTEM, "TurnHarness.submitPlayerPlay: Current pendingAwaiters keys: ${pendingAwaiters.keys.joinToString()}")
        Logger.debug(LogCategory.SYSTEM, "TurnHarness.submitPlayerPlay: Current bufferedSubmissions keys: ${bufferedSubmissions.keys.joinToString()}")
        Logger.debug(LogCategory.SYSTEM, "TurnHarness.submitPlayerPlay: turnOrderIndex=$turnOrderIndex, loopJob.active=${loopJob?.isActive}")
        
        val submission = PlaySubmission(context = context, actorName = player.name, action = action, isAiTakeover = false)
        Logger.info(LogCategory.GENERAL, "TurnHarness.submitPlayerPlay: Checking pendingAwaiters for key=$key...")
        val awaiter = turnStateLock.withLock { pendingAwaiters.remove(key) }
        
        if(awaiter != null)
        {
            Logger.info(LogCategory.GENERAL, "TurnHarness.submitPlayerPlay: AWAITER FOUND for ${player.name}! Completing deferred now.")
            Logger.debug(LogCategory.SYSTEM, "TurnHarness.submitPlayerPlay: Awaiter had ${pendingAwaiters.size} remaining entries before removal")
            awaiter.complete(submission)
            Logger.info(LogCategory.GENERAL, "TurnHarness.submitPlayerPlay: Awaiter completed for ${player.name}. Returning without triggering runNextTurn.")
            return
        }

        Logger.warn(LogCategory.SYSTEM, "TurnHarness.submitPlayerPlay: NO AWAITER FOUND for ${player.name}! Buffering submission and triggering runNextTurn.")
        Logger.warn(LogCategory.GENERAL, "TurnHarness.submitPlayerPlay: This suggests awaitPlayerAction was NOT called before this submission arrived, or already timed out.")
        
        turnStateLock.withLock {
            bufferedSubmissions[key] = submission
            Logger.info(LogCategory.GENERAL, "TurnHarness.submitPlayerPlay: Buffered submission for $key (buffered now has ${bufferedSubmissions.size} entries)")
        }
        Logger.warn(LogCategory.SYSTEM, "TurnHarness.submitPlayerPlay: Calling runNextTurn() after buffering (loopJob.active=${loopJob?.isActive})")
        runNextTurn()
        Logger.info(LogCategory.GENERAL, "TurnHarness.submitPlayerPlay: runNextTurn() returned for ${player.name}")
    }

    /**
     * Internal helper to generate a character-specific AI action for a player.
     * Uses the [buildPlayerAgent] pipeline.
     */
    private suspend fun generateAiAction(player: Player): String
    {
        var aiAction = AI_FALLBACK_ACTION
        try
        {
            Logger.info(
                LogCategory.LLM,
                "TurnHarness.generateAiAction: Pre-build snapshot for ${player.name} — delegateInstructionsLength=${player.delegateInstructions?.length ?: 0} hasDelegateGuidance=${!player.delegateInstructions.isNullOrBlank()}"
            )
            Logger.debug(LogCategory.LLM, "TurnHarness: Building PlayerAgent pipeline for ${player.name}")
            val broadcastIds = getAllConnectedClientIds()
            val aiPlayerAgent = buildPlayerAgent(player).apply {
                enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
                enablePipeTimeout(
                    applyRecursively = true,
                    duration = 180000,
                    autoRetry = true,
                    retryLimit = 5
                )
                init(true)
                streamPipelineOutputToAgentWorkBuffer(broadcastIds, this)
            }
            
            val aiResult = aiPlayerAgent.execute(MultimodalContent(""))
            if(aiResult.text.isNotBlank())
            {
                aiAction = aiResult.text
            }
            
            // Save trace for the AI generation
            val traceDir = File(File(getTurnTraceDir()), "AI_Player_Takeover")
            if(!traceDir.exists()) traceDir.mkdirs()
            saveSystemTrace("AI_Player_Takeover", aiPlayerAgent)
            
            Logger.debug(LogCategory.LLM, "TurnHarness: AI action generated for ${player.name} (length=${aiAction.length})")
        }
        catch(e: Exception)
        {
            Logger.error(LogCategory.GENERAL, "TurnHarness: Failed to generate AI action for ${player.name}: ${e.message}")
        }
        return aiAction
    }

    /**
     * Executes an AI-driven turn for a player who has timed out or disconnected.
     * 
     * This method builds a fresh [RpcCallContext] and routes the request to [executePlayerTurn]
     * using the player fallback prompt.
     *
     * @param actorName Name of the player to take over. Defaults to [WorldManager.activeTurnActor].
     */
    suspend fun handleAiTakeover(actorName: String)
    {
        Logger.info(LogCategory.SYSTEM, "TurnHarness.handleAiTakeover: === ENTRY === actorName='$actorName', activeTurnActor='${WorldManager.activeTurnActor}'")
        Logger.info(LogCategory.SYSTEM, "TurnHarness.handleAiTakeover: Current state - turnOrderIndex=$turnOrderIndex, pendingAwaiters=${pendingAwaiters.keys.joinToString()}, bufferedSubmissions=${bufferedSubmissions.keys.joinToString()}")
        
        val actor = actorName.ifBlank { WorldManager.activeTurnActor }
        Logger.info(LogCategory.LLM, "TurnHarness.handleAiTakeover: AI TAKEOVER INITIATED for actor='$actor' (original input='$actorName')")
        
        val stackTrace = Thread.currentThread().stackTrace.take(15).joinToString("\n") { "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
        Logger.warn(LogCategory.SYSTEM, "TurnHarness.handleAiTakeover: Origin stack trace:\n$stackTrace")
        
        if(actor.isBlank())
        {
            Logger.error(LogCategory.GENERAL, "TurnHarness.handleAiTakeover: CRITICAL - Actor name is BLANK after fallback to activeTurnActor! Aborting.")
            return
        }

        // --- CHECK FOR AWAITERS FIRST ---
        val key = normalizeActorKey(actor)
        Logger.info(LogCategory.SYSTEM, "TurnHarness.handleAiTakeover: Checking for pending awaiter with key='$key'")
        Logger.info(LogCategory.SYSTEM, "TurnHarness.handleAiTakeover: pendingAwaiters before remove: ${pendingAwaiters.keys.joinToString()}")
        val awaiter = turnStateLock.withLock { pendingAwaiters.remove(key) }
        Logger.info(LogCategory.SYSTEM, "TurnHarness.handleAiTakeover: Awaiter found: ${awaiter != null}")
        
        if(awaiter != null)
        {
            Logger.info(LogCategory.GENERAL, "TurnHarness.handleAiTakeover: Found pending awaiter for $actor! Will complete it with AI action.")
            Logger.info(LogCategory.GENERAL, "TurnHarness.handleAiTakeover: Awaiter will be completed, which should wake up the turn loop.")
            // We do NOT mark as processed here, because the woken-up loop will do it.
            
            val player = WorldManager.world.findPlayerByName(actor)
            if(player == null)
            {
                Logger.error(LogCategory.GENERAL, "TurnHarness.handleAiTakeover: Player '$actor' not found for awaiter completion. Aborting!")
                return
            }
            Logger.info(LogCategory.SYSTEM, "TurnHarness.handleAiTakeover: Found player=$actor, generating AI action...")
            
            val aiAction = generateAiAction(player)
            val originConnectionId = resolvePlayerConnectionIdByName(actor) ?: "ai-takeover-awaiter"
            Logger.info(LogCategory.SYSTEM, "TurnHarness.handleAiTakeover: AI action generated (length=${aiAction.length}), creating PlaySubmission...")
            val submission = PlaySubmission(
                context = RpcCallContext(connectionId = originConnectionId, sender = {}),
                actorName = actor,
                action = aiAction,
                isAiTakeover = true
            )
            Logger.info(LogCategory.SYSTEM, "TurnHarness.handleAiTakeover: Completing awaiter with AI action...")
            awaiter.complete(submission)
            Logger.info(LogCategory.GENERAL, "TurnHarness.handleAiTakeover: Awaiter COMPLETED - turn loop should now resume!")
            return
        }

        Logger.warn(LogCategory.SYSTEM, "TurnHarness.handleAiTakeover: NO AWAITER FOUND for key='$key'! Will perform DIRECT execution.")
        Logger.warn(LogCategory.GENERAL, "TurnHarness.handleAiTakeover: This means the turn loop is NOT waiting on this actor - direct execution path.")

        // --- NO AWAITER: DIRECT EXECUTION ---
        val turnKey = "$actor-${WorldManager.world.roundNumber}-$turnOrderIndex"
        if(!markTurnAsProcessed(turnKey))
        {
            Logger.warn(LogCategory.GENERAL, "TurnHarness.handleAiTakeover: Turn $turnKey already processed (direct path), aborting.")
            return
        }

        // Set folder name for direct execution
        val folderName = "Round_${WorldManager.world.roundNumber}_Turn_${turnOrderIndex}_${actor.replace(" ", "_")}"
        setCurrentTurnFolderName(folderName)

        try {
            val player = WorldManager.world.findPlayerByName(actor)
            if(player == null)
            {
                Logger.error(LogCategory.GENERAL, "TurnHarness.handleAiTakeover: CRITICAL - Player '$actor' not found for direct takeover")
                return
            }

            Logger.info(LogCategory.GENERAL, "TurnHarness.handleAiTakeover: Generating AI action for ${player.name}...")
            var aiAction = playerFallbackAction

            try
            {
                Logger.info(
                    LogCategory.LLM,
                    "TurnHarness.handleAiTakeover: Pre-build snapshot for ${player.name} — delegateInstructionsLength=${player.delegateInstructions?.length ?: 0} hasDelegateGuidance=${!player.delegateInstructions.isNullOrBlank()}"
                )
                Logger.debug(LogCategory.LLM, "TurnHarness.handleAiTakeover: Building PlayerAgent pipeline for ${player.name}")
                val buildStart = System.currentTimeMillis()
                val broadcastIds = getAllConnectedClientIds()
                val aiPlayerAgent = buildPlayerAgent(player).apply {
                    enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
                    enablePipeTimeout(
                        applyRecursively = true,
                        duration = 180000,
                        autoRetry = true,
                        retryLimit = 5
                    )
                    init(true)
                    streamPipelineOutputToAgentWorkBuffer(broadcastIds, this)
                }
                val buildDuration = System.currentTimeMillis() - buildStart
                Logger.debug(LogCategory.LLM, "TurnHarness.handleAiTakeover: PlayerAgent pipeline built for ${player.name} (took ${buildDuration}ms)")
                val executeStart = System.currentTimeMillis()
                val aiResult = aiPlayerAgent.execute(MultimodalContent(""))
                val executeDuration = System.currentTimeMillis() - executeStart
                Logger.debug(
                    LogCategory.LLM,
                    "TurnHarness.handleAiTakeover: PlayerAgent execution finished for ${player.name} (took ${executeDuration}ms), result length=${aiResult.text.length}"
                )
                if(aiResult.text.isNotBlank())
                {
                    aiAction = aiResult.text
                }
                else
                {
                    aiAction = playerFallbackAction
                    Logger.warn(LogCategory.LLM, "TurnHarness.handleAiTakeover: PlayerAgent returned blank for ${player.name}; using generic fallback prompt.")
                }

                val traceDir = File(File(getTurnTraceDir()), "AI_Player_Takeover")
                if(!traceDir.exists()) traceDir.mkdirs()
                saveSystemTrace("AI_Player_Takeover", aiPlayerAgent)
            } catch(e: Exception)
            {
                aiAction = playerFallbackAction
                Logger.error(LogCategory.GENERAL, "TurnHarness.handleAiTakeover: Failed to generate AI action for ${player.name}: ${e.message}. Falling back to generic prompt.")
            }
            finally
            {
                Logger.warn(LogCategory.LLM, "TurnHarness.handleAiTakeover: Executing fallback action for ${player.name}: '${aiAction.take(120)}'")
            }
            
            val ctx = RpcCallContext(connectionId = resolvePlayerConnectionIdByName(actor) ?: "ai-takeover-direct", sender = {})
            executePlayerTurn(ctx, player, aiAction)
            Logger.info(LogCategory.LLM, "TurnHarness.handleAiTakeover: Direct AI TAKEOVER COMPLETED for $actor")
        } finally {
            val newTurnRecord = accounting.Billing.recordTurnBilling(folderName)
            setCurrentTurnFolderName(null)
            if (newTurnRecord != null)
            {
                accounting.BillingSync.flushTurnUsage(listOf(newTurnRecord))
            }
        }
    }

    /**
     * Generates an action for an NPC based on its type using the appropriate planning agent.
     * 
     * @param npc The NPC to generate an action for
     * @return The generated action string, or NPC_DEFAULT_ACTION if generation fails
     */
    private suspend fun generateNpcAction(npc: Npc): String
    {
        Logger.info(LogCategory.GENERAL, "TurnHarness.generateNpcAction: Generating action for ${npc.name} (type=${npc.type})")
        Logger.debug(LogCategory.GENERAL, "TurnHarness.generateNpcAction: NPC description length=${npc.description.length}, personality length=${npc.personality.length}")
        
        var npcAction = npcFallbackAction
        var actionSource = "default"
        
        try
        {
            val agent = when(npc.type)
            {
                NpcType.Active, NpcType.Subordinate, NpcType.Passive -> {
                    Logger.info(LogCategory.GENERAL, "TurnHarness.generateNpcAction: Using buildNpcActorAgent for ${npc.type} NPC")
                    buildNpcActorAgent(npc)
                }
                NpcType.Hostile -> {
                    Logger.info(LogCategory.GENERAL, "TurnHarness.generateNpcAction: Using buildHostileNpcAgent for Hostile NPC")
                    buildHostileNpcAgent(npc)
                }
                NpcType.Nemesis -> {
                    Logger.info(LogCategory.GENERAL, "TurnHarness.generateNpcAction: Using buildNemesisAgent for Nemesis NPC")
                    buildNemesisAgent(npc)
                }
                NpcType.ElderGod -> {
                    Logger.info(LogCategory.GENERAL, "TurnHarness.generateNpcAction: Using buildElderGodAgent for Elder God")
                    buildElderGodAgent(npc)
                }
            }.apply {
                enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
                enablePipeTimeout(
                    applyRecursively = true,
                    duration = 180000,
                    autoRetry = true,
                    retryLimit = 5
                )
                Logger.debug(LogCategory.GENERAL, "TurnHarness.generateNpcAction: Initializing agent for ${npc.name}")
                init(true)
                
                val broadcastIds = getAllConnectedClientIds()
                streamPipelineOutputToAgentWorkBuffer(broadcastIds, this)
                
                Logger.debug(LogCategory.GENERAL, "TurnHarness.generateNpcAction: Agent initialized successfully for ${npc.name}")
            }
            
            Logger.debug(LogCategory.GENERAL, "TurnHarness.generateNpcAction: Executing agent for ${npc.name}")
            val result = agent.execute(MultimodalContent(""))
            Logger.debug(LogCategory.GENERAL, "TurnHarness.generateNpcAction: Agent execution complete for ${npc.name}, result length=${result.text.length}")
            
            if(result.text.isNotBlank())
            {
                npcAction = result.text
                actionSource = "agent"
                Logger.info(LogCategory.GENERAL, "TurnHarness.generateNpcAction: Generated action (length=${npcAction.length})")
                Logger.debug(LogCategory.GENERAL, "TurnHarness.generateNpcAction: Action preview: ${npcAction.take(200)}")
            }
            else
            {
                actionSource = "genericFallback"
                npcAction = npcFallbackAction
                Logger.warn(LogCategory.LLM, "TurnHarness.generateNpcAction: Agent returned blank for ${npc.name}; using generic fallback prompt.")
                Logger.info(LogCategory.GENERAL, "TurnHarness.generateNpcAction: Generic fallback prompt selected for ${npc.name}: '${npcAction.take(120)}'")
            }
            
            // Save trace
            val traceSubfolder = when(npc.type)
            {
                NpcType.Active -> "NPC_Active_Turn"
                NpcType.Nemesis -> "NPC_Nemesis_Turn"
                NpcType.Hostile -> "NPC_Hostile_Turn"
                NpcType.ElderGod -> "NPC_ElderGod_Turn"
                else -> "NPC_Turn"
            }
            val traceDir = File(File(getTurnTraceDir()), traceSubfolder)
            if(!traceDir.exists()) traceDir.mkdirs()
            saveSystemTrace(traceSubfolder, agent)
            Logger.debug(LogCategory.GENERAL, "TurnHarness.generateNpcAction: Trace saved to ${traceDir.absolutePath}/trace.json")
            
        } catch(e: Exception)
        {
            actionSource = "exceptionFallback"
            Logger.error(LogCategory.LLM, "TurnHarness.generateNpcAction: Failed to generate action for ${npc.name}: ${e.message}. Using generic fallback prompt.")
            e.printStackTrace()
            
            npcAction = npcFallbackAction
            Logger.info(LogCategory.GENERAL, "TurnHarness.generateNpcAction: Generic fallback prompt applied after exception for ${npc.name}.")
        }
        
        val truncatedAction = npcAction.replace("\n", " ").take(200)
        Logger.info(LogCategory.LLM, "TurnHarness.generateNpcAction: Final action for ${npc.name} (source=$actionSource, len=${npcAction.length}) -> ${truncatedAction}")

        return npcAction
    }

    /**
     * Executes a single turn for the current actor in the turn order.
     * 
     * ### Process:
     * 1. Resolves the current actor from [WorldManager.world].
     * 2. Broadcasts the [ActiveTurnData] to all clients.
     * 3. If actor is a [Player]:
     *    - Waits for input via [awaitPlayerAction].
     *    - On success: Routes to [executePlayerTurn].
     *    - On timeout: Triggers [handleAiTakeover].
     * 4. If actor is an [Npc]:
     *    - Executes [executeNpcTurn] with [NPC_DEFAULT_ACTION].
     *
     * @return A [TurnExecutionResult] summary of what transpired.
     */
    private suspend fun executeSingleTurn(): TurnExecutionResult?
    {
        Logger.info(LogCategory.SYSTEM, "TurnHarness.executeSingleTurn: === ENTRY === round=${WorldManager.world.roundNumber}, turnOrderIndex=$turnOrderIndex")
        Logger.info(LogCategory.SYSTEM, "TurnHarness.executeSingleTurn: turnOrder=${WorldManager.world.turnOrder.joinToString()}")
        Logger.info(LogCategory.SYSTEM, "TurnHarness.executeSingleTurn: activePlayers=${WorldManager.world.activePlayers.map { it.name }}")
        Logger.info(LogCategory.SYSTEM, "TurnHarness.executeSingleTurn: pendingAwaiters=${pendingAwaiters.keys.joinToString()}, bufferedSubmissions=${bufferedSubmissions.keys.joinToString()}")
        
        val actor = resolveCurrentActor()
        if(actor == null)
        {
            Logger.error(LogCategory.SYSTEM, "TurnHarness.executeSingleTurn: CRITICAL - resolveCurrentActor returned null! Turn cannot proceed. State: round=${WorldManager.world.roundNumber}, turnOrderIndex=$turnOrderIndex, turnOrder=${WorldManager.world.turnOrder}, activePlayers=${WorldManager.world.activePlayers.map { it.name }}, npc=${WorldManager.world.npc.map { it.name }}")
            delay(200L)
            return null
        }

        Logger.info(LogCategory.GENERAL, "TurnHarness.executeSingleTurn: Resolved actor='$actor' (round=${WorldManager.world.roundNumber}, turnOrderIndex=$turnOrderIndex).")

        // Phase 6 of feature/live-pvp-and-billing: capture Bedrock credentials
        // before the turn starts so a BYO-keyed player gets billed under their
        // own provider. The snapshot is restored in the finally block below
        // after the per-turn billing flush completes.
        val actorAccelByteId = WorldManager.playerStats
            .firstOrNull { it.playerData?.name?.equals(actor, ignoreCase = true) == true }
            ?.accelByteUserId
            .orEmpty()
        val credsSnapshot = globals.BedrockCredentialResolver.snapshot(actorAccelByteId)

        val turnFolderName = "Round_${WorldManager.world.roundNumber}_Turn_${turnOrderIndex}_${actor.replace(" ", "_")}"
        setCurrentTurnFolderName(turnFolderName)

        Logger.info(LogCategory.GENERAL, "TurnHarness.executeSingleTurn: Executing turn for actor='$actor' (round=${WorldManager.world.roundNumber}, turnOrderIndex=$turnOrderIndex, turnFolderName='$turnFolderName').")
        val turnKey = "$actor-${WorldManager.world.roundNumber}-$turnOrderIndex"
        Logger.info(LogCategory.SYSTEM, "TurnHarness.executeSingleTurn: turnKey='$turnKey', checking if already processed...")
        Logger.info(LogCategory.SYSTEM, "TurnHarness.executeSingleTurn: processedTurns currently has ${processedTurns.size} entries: ${processedTurns.joinToString()}")

        Logger.info(LogCategory.SYSTEM, "TurnHarness.executeSingleTurn: Broadcasting ActiveTurnData for actor=$actor")
        UiSignalRpcHandlers.broadcastActiveTurn(
            ActiveTurnData(
                actorName = actor,
                roundNumber = WorldManager.world.roundNumber,
                turnIndex = turnOrderIndex,
                timerSeconds = WorldManager.TURN_DURATION_SECONDS
            )
        )

        // Web Push notification: if the human actor has no live PRIMARY WebSocket
        // session (player closed the tab or browser), fire a Web Push to every
        // stored subscription for that user. Multi-device (desktop + mobile,
        // multiple browser profiles) is supported by the underlying
        // PushSubscriptionStore, which holds a list of subscriptions per
        // accelByteId — the service fans out to each one and surgically prunes
        // dead endpoints (404/410) without disturbing the user's other live
        // devices. Fire-and-forget on Dispatchers.IO — does NOT block the turn
        // loop. Subscriptions are registered by the browser on the user's Play
        // button click (handled by PushNotificationService.subscribeIfPermitted
        // in the client) and refreshed on `pushsubscriptionchange` via
        // handleSubscriptionTurnMessage in PushNotificationService.
        //
        // The human-vs-AI check is done by looking up the player's stats entry
        // rather than the `val player` declared further down, so this block
        // can stay adjacent to the turn-start broadcast.
        val pushService = UiSignalRpcHandlers.pushNotificationService
        val pushStore = UiSignalRpcHandlers.pushSubscriptionStore
        val pushHumanAccelByteId = WorldManager.playerStats
            .firstOrNull { it.playerData?.name?.equals(actor, ignoreCase = true) == true }
            ?.accelByteUserId
            .orEmpty()
        if (pushService != null && pushStore != null && pushHumanAccelByteId.isNotBlank())
        {
            val connectionManager = UiSignalRpcHandlers.connectionManager
            val hasPrimarySession = connectionManager
                ?.findAllSessionsByAccelbyteId(pushHumanAccelByteId)
                ?.any { it.role == SessionRole.PRIMARY }
                ?: false
            if (!hasPrimarySession)
            {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try
                    {
                        pushService.sendTurnStart(pushHumanAccelByteId, actor, WorldManager.world.roundNumber)
                    }
                    catch (e: Throwable)
                    {
                        Logger.warn(LogCategory.NETWORK, "TurnHarness: push trigger failed for user=$pushHumanAccelByteId: ${e.message}")
                    }
                }
            }
        }

        UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.START, "It is $actor's turn")
        Logger.info(LogCategory.SYSTEM, "TurnHarness.executeSingleTurn: Broadcasted turn start for $actor")

        // Music selector: pick a track for the new turn and broadcast the decision
        // to every connected client. Gated on the same processedTurns check the rest of
        // the turn logic uses so retries do not re-broadcast the same schedule.
        selectAndBroadcastMusicForTurn(actor)

        val player = WorldManager.world.findPlayerByName(actor)
        var actionCategory = ActionCategory.OTHER
        var isAiTakeover = false

        Logger.info(LogCategory.SYSTEM, "TurnHarness.executeSingleTurn: About to call awaitPlayerAction() for ${actor}")
        try {
            if(player != null)
            {
                Logger.info(LogCategory.GENERAL, "TurnHarness.executeSingleTurn: Player found - name='${player.name}', calling awaitPlayerAction()...")
                val submission = awaitPlayerAction(player)
                Logger.info(LogCategory.SYSTEM, "TurnHarness.executeSingleTurn: awaitPlayerAction() returned. submission=${submission != null}")
                
                if(submission != null)
                {
                    Logger.info(LogCategory.SYSTEM, "TurnHarness.executeSingleTurn: Submission received! Checking markTurnAsProcessed(turnKey='$turnKey')...")
                    if(markTurnAsProcessed(turnKey))
                    {
                        Logger.info(LogCategory.GENERAL, "TurnHarness.executeSingleTurn: Player ${player.name} submitted action, routing to executePlayerTurn()")
                        Logger.debug(LogCategory.GENERAL, "TurnHarness.executeSingleTurn: Action preview - ${submission.action.take(100)}${if(submission.action.length > 100) "..." else ""}")
                        
                        try {
                            executePlayerTurn(submission.context, player, submission.action)
                            Logger.info(LogCategory.SYSTEM, "TurnHarness: executePlayerTurn completed for ${player.name}")
                        } catch (e: Exception) {
                            Logger.error(LogCategory.SYSTEM, "TurnHarness: executePlayerTurn CRASHED for ${player.name}: ${e.message}")
                            e.printStackTrace()
                        }
                        
                        actionCategory = categorizeAction(submission.action)
                        isAiTakeover = submission.isAiTakeover
                    }
                    else
                    {
                        Logger.info(LogCategory.GENERAL, "TurnHarness.executeSingleTurn: Turn $turnKey already processed (likely by parallel AI takeover), skipping.")
                        return null
                    }
                }
                else
                {
                    Logger.warn(LogCategory.GENERAL, "TurnHarness.executeSingleTurn: awaitPlayerAction returned null for ${player.name}")
                    Logger.info(LogCategory.GENERAL, "TurnHarness.executeSingleTurn: Reason: Either player unreachable OR timeout expired")
                    Logger.info(LogCategory.GENERAL, "TurnHarness.executeSingleTurn: Triggering AI takeover for ${player.name}...")
                    handleAiTakeover(actor)
                    isAiTakeover = true
                    Logger.info(LogCategory.GENERAL, "TurnHarness.executeSingleTurn: AI takeover completed for ${player.name}")
                }
            }
            else
            {
                Logger.debug(LogCategory.GENERAL, "TurnHarness.executeSingleTurn: Player not found for actor='$actor', checking for NPC")
                val npc = WorldManager.world.findNpcByName(actor)
                if(npc != null)
                {
                    if(markTurnAsProcessed(turnKey))
                    {
                        Logger.info(LogCategory.GENERAL, "TurnHarness.executeSingleTurn: Found NPC '$actor', generating NPC action")
                        val npcAction = generateNpcAction(npc)
                        
                        try {
                            executeNpcTurn(npc, npcAction)
                            Logger.info(LogCategory.SYSTEM, "TurnHarness: executeNpcTurn completed for ${npc.name}")
                        } catch (e: Exception) {
                            Logger.error(LogCategory.SYSTEM, "TurnHarness: executeNpcTurn CRASHED for ${npc.name}: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                    else
                    {
                        Logger.info(LogCategory.GENERAL, "TurnHarness.executeSingleTurn: NPC turn $turnKey already processed, skipping.")
                        return null
                    }
                }
                else
                {
                    Logger.error(LogCategory.GENERAL, "TurnHarness.executeSingleTurn: Actor '$actor' not found as Player or NPC - this should not happen")
                }
            }
        } finally {
            val newTurnRecord = accounting.Billing.recordTurnBilling(turnFolderName)
            setCurrentTurnFolderName(null)
            if (newTurnRecord != null)
            {
                accounting.BillingSync.flushTurnUsage(listOf(newTurnRecord))
            }
            // Phase 6 of feature/live-pvp-and-billing: restore the platform
            // Bedrock credentials so the next turn is not billed under the
            // previous player's BYO key.
            globals.BedrockCredentialResolver.restore(credsSnapshot)
        }

        return TurnExecutionResult(
            actorName = actor,
            isPlayerTurn = player != null,
            actionCategory = actionCategory,
            isAiTakeover = isAiTakeover
        )
    }

    /**
     * Performs post-turn cleanup including point decay, moral evaluation, 
     * world persistence, and round advancement.
     */
    private suspend fun handlePostTurn(result: TurnExecutionResult)
    {
        // The turn is over — drop the per-turn music decision so a
        // reroll on the next turn starts from a clean slate.
        currentTurnMusicDecision = null
        // Cancel any in-flight 5-minute fallback timer. The next turn's
        // selectAndBroadcastMusicForTurn will re-arm a fresh one if the
        // game is still active. This line runs for BOTH the normal
        // post-turn path and the game-over early-return path (it is the
        // first statement in handlePostTurn), so a game-ending turn
        // cannot leave a stale timer that fires into a torn-down
        // connectionManager.
        musicRerollFallbackJob?.cancel()
        musicRerollFallbackJob = null
        Logger.info(LogCategory.SYSTEM, "TurnHarness.handlePostTurn: === ENTRY === for ${result.actorName}")
        Logger.info(LogCategory.SYSTEM, "TurnHarness.handlePostTurn: Details - isPlayerTurn=${result.isPlayerTurn}, isAiTakeover=${result.isAiTakeover}, category=${result.actionCategory}")
        Logger.info(LogCategory.SYSTEM, "TurnHarness.handlePostTurn: Current state - round=${WorldManager.world.roundNumber}, turnOrderIndex=$turnOrderIndex, isGameActive=${WorldManager.isGameActive}")
        
        val activePlayerName = if(result.isPlayerTurn) result.actorName else null
        Logger.info(LogCategory.SYSTEM, "TurnHarness.handlePostTurn: Applying decay for activePlayerName=$activePlayerName...")
        applyDecayForAllPlayers(activePlayerName, result.actionCategory)
        Logger.info(LogCategory.SYSTEM, "TurnHarness.handlePostTurn: Decay applied. Now handling nemesis...")
        handleNemesisFromKarma(result.actorName)
        rollNemesisRevival()
        Logger.info(LogCategory.SYSTEM, "TurnHarness.handlePostTurn: Broadcasting world snapshot...")
        broadcastWorldSnapshot()
        serializeCurrentWorldSnapshot()
        Logger.info(LogCategory.SYSTEM, "TurnHarness.handlePostTurn: Checking territory-based game end...")
        if(handleTerritoryBasedGameEnd())
        {
            Logger.info(LogCategory.SYSTEM, "TurnHarness.handlePostTurn: Game ended (territory-based). Returning early.")
            return
        }
        Logger.info(LogCategory.SYSTEM, "TurnHarness.handlePostTurn: Evaluating end game...")
        evaluateEndGame()
        if(WorldManager.isGameActive)
        {
            Logger.info(LogCategory.SYSTEM, "TurnHarness.handlePostTurn: Game still active. Calling advanceTurnIndexAndRoundIfNeeded()...")
            advanceTurnIndexAndRoundIfNeeded()
            Logger.info(LogCategory.SYSTEM, "TurnHarness.handlePostTurn: Turn advancement completed. New turnOrderIndex=$turnOrderIndex")
        }
        else
        {
            Logger.info(LogCategory.SYSTEM, "TurnHarness.handlePostTurn: Game is no longer active, skipping turn advancement")
        }
        Logger.info(LogCategory.SYSTEM, "TurnHarness.handlePostTurn: === COMPLETED === for ${result.actorName}")
    }

    private suspend fun handleTerritoryBasedGameEnd(): Boolean
    {
        if(WorldManager.isSinglePlayer)
        {
            val humanName = WorldManager.playerStats
                .firstOrNull { !it.isControlledByNpc }
                ?.playerData
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: WorldManager.humanPlayerName.takeIf { it.isNotBlank() }

            if(!humanName.isNullOrBlank() && !WorldManager.ownerHasTerritories(humanName))
            {
                val winner = selectDominantOwner(setOf(humanName))
                    ?: WorldManager.playerStats.firstOrNull { it.isControlledByNpc }?.playerData?.name
                    ?: WorldManager.world.npc.firstOrNull { !it.isDefeated }?.name
                    ?: "A.I. Forces"
                dispatchForcedGameOver(winner, false)
                return true
            }
        }

        val aiPlayerNames = WorldManager.playerStats
            .filter { it.isControlledByNpc }
            .mapNotNull { it.playerData.name.takeIf { name -> name.isNotBlank() } }
            .distinct()

        if(aiPlayerNames.isNotEmpty() && aiPlayerNames.none { WorldManager.ownerHasTerritories(it) })
        {
            val humanName = WorldManager.playerStats
                .firstOrNull { !it.isControlledByNpc }
                ?.playerData
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: WorldManager.humanPlayerName.takeIf { it.isNotBlank() }

            if(!humanName.isNullOrBlank())
            {
                dispatchForcedGameOver(humanName, true)
                return true
            }
        }

        return false
    }


    private fun resolvePlayerConnectionId(player: Player): String?
    {
        return WorldManager.playerStats.firstOrNull { it.playerData.name.equals(player.name, ignoreCase = true) }?.playerID
    }

    private fun resolvePlayerConnectionIdByName(name: String): String?
    {
        val player = WorldManager.world.findPlayerByName(name)
        if(player != null)
        {
            return resolvePlayerConnectionId(player)
        }
        return null
    }

    /**
     * Orchestrates the Round Start sequence:
     * 1. Resets point pools for all players.
     * 2. Checks for new Nemesis arrivals or revivals.
     * 3. Calculates the turn order, accounting for NPC interference.
     * 4. Broadcasts [TurnOrderAnnouncementData] and [NemesisThreatAnnouncementData].
     * 5. Pauses for [ROUND_START_DISPLAY_SECONDS] to allow UI animations to finish.
     */
    suspend fun announceRoundStartIfNeeded()
    {
        val currentRound = WorldManager.world.roundNumber
        var shouldAnnounce = false
        announcementLock.withLock {
            if(currentRound > lastAnnouncedRound)
            {
                lastAnnouncedRound = currentRound
                shouldAnnounce = true
            }
        }

        if(!shouldAnnounce)
        {
            return
        }

        var announcementData: TurnOrderAnnouncementData
        var nemesisAnnouncement: NemesisThreatAnnouncementData? = null
        WorldManager.worldMutex.withLock {
            val world = WorldManager.world
            world.activePlayers.forEach { player ->
                player.militaryPoints = 100
                player.diplomacyPoints = 100
                player.researchPoints = 100
                player.summitPoints += 0
            }

            val activeNemesisNames = world.npc
                .filter { it.type == NpcType.Nemesis && !it.isDefeated }
                .map { it.name }
                .toSet()
            val defeatedNemesisNames = world.npc
                .filter { it.type == NpcType.Nemesis && it.isDefeated }
                .map { it.name }
                .toSet()

            val newlyActivatedNemesis = activeNemesisNames.firstOrNull { !lastActiveNemesisNames.contains(it) }
            if(newlyActivatedNemesis != null)
            {
                val kind = if(lastDefeatedNemesisNames.contains(newlyActivatedNemesis))
                {
                    NemesisThreatKind.REVIVAL
                }
                else
                {
                    NemesisThreatKind.ARRIVAL
                }
                val reason = if(kind == NemesisThreatKind.REVIVAL)
                {
                    "$newlyActivatedNemesis has returned to the battlefield. Summit points remain disabled (+0)."
                }
                else
                {
                    "$newlyActivatedNemesis has emerged as a world-level threat. Summit points remain disabled (+0)."
                }
                nemesisAnnouncement = NemesisThreatAnnouncementData(currentRound, newlyActivatedNemesis, kind, reason)
            }

            val baseOrder = world.turnOrder.mapNotNull { name -> world.findPlayerByName(name)?.name }
                .ifEmpty { world.activePlayers.map { it.name } }
                .distinct()
                .toMutableList()
            pruneTerritorylessAiPlayers(baseOrder)
            enforceSinglePlayerActorFirst(baseOrder)

            world.turnOrder.clear()
            world.turnOrder.addAll(baseOrder)
            val interferenceList = rollNpcInterference(world.npc)
            npcInterferenceList = interferenceList
            insertInterferingNpcs(world.turnOrder, interferenceList)

            // Award per-round summit points if Nemesis or ElderGod is active
            val hasSummitThreat = world.npc.any { (it.type == NpcType.Nemesis || it.type == NpcType.ElderGod) && !it.isDefeated }
            if (hasSummitThreat)
            {
                world.activePlayers.forEach { player ->
                    player.summitPoints = (player.summitPoints + WorldManager.SUMMIT_POINTS_PER_ROUND).coerceAtMost(WorldManager.MAX_SUMMIT_POINTS)
                }
                Logger.info(LogCategory.GENERAL, "TurnHarness: Awarded ${WorldManager.SUMMIT_POINTS_PER_ROUND} summit point(s) per round to ${world.activePlayers.size} players (Nemesis/ElderGod active)")
            }

            val participants = world.turnOrder.map { 
            TurnOrderParticipant(name = it, isPlayer = !world.npc.any { n -> n.name == it })
        }
        announcementData = TurnOrderAnnouncementData(currentRound, participants)
        Logger.info(LogCategory.SYSTEM, "TurnHarness: Round $currentRound started. Turn order: ${participants.joinToString { p -> if(!p.isPlayer) "[NPC]${p.name}" else p.name }}")

            val firstActor = world.turnOrder.firstOrNull()
            WorldManager.activeTurnActor = firstActor.orEmpty()
            turnOrderIndex = 0

            announcementData = TurnOrderAnnouncementData(
                roundNumber = currentRound,
                participants = participants,
                firstActor = firstActor
            )

            lastActiveNemesisNames = activeNemesisNames
            lastDefeatedNemesisNames = defeatedNemesisNames
        }

        // Sync client world state with new round values (points, turn order, round number)
        broadcastWorldSnapshot()

        Logger.info(
            LogCategory.GENERAL,
            "TurnHarness: Broadcasting round start for round $currentRound with participants=${announcementData.participants.map { it.name }} firstActor=${announcementData.firstActor}"
        )

        nemesisAnnouncement?.let { UiSignalRpcHandlers.broadcastNemesisThreatAnnouncement(it) }
        UiSignalRpcHandlers.broadcastTurnOrderAnnouncement(announcementData)

        Logger.info(LogCategory.GENERAL, "TurnHarness: Displaying round $currentRound announcement for $ROUND_START_DISPLAY_SECONDS seconds")
        delay(ROUND_START_DISPLAY_SECONDS * 1000)
    }

    /**
     * Captures the current [WorldManager.world] and session metadata into a
     * [GameSnapshot] under [WorldManager.worldMutex]. Safe to call from any
     * coroutine. Used by both the on-disk snapshot path and the per-user
     * "running game" record path so the two persistence targets always agree
     * on the captured state.
     */
    private suspend fun buildCurrentGameSnapshot(): GameSnapshot
    {
        return WorldManager.worldMutex.withLock {
            GameSnapshot(
                world = WorldManager.world,
                history = WorldManager.history.toList(),
                geopoliticalAssessment = WorldManager.geopoliticalAssessment,
                playerStats = WorldManager.playerStats.toList(),
                turnOrderIndex = turnOrderIndex,
                npcInterferenceList = npcInterferenceList,
                lastAnnouncedRound = lastAnnouncedRound,
                lastActiveNemesisNames = lastActiveNemesisNames,
                lastDefeatedNemesisNames = lastDefeatedNemesisNames,
                mapPackName = WorldManager.activeMapPackName,
                isSinglePlayer = WorldManager.isSinglePlayer,
                humanPlayerName = WorldManager.humanPlayerName
            )
        }
    }

    /**
     * Serializes the current [WorldManager.world] and session metadata to JSON files in [getSnapshotDirectory].
     */
    private suspend fun serializeCurrentWorldSnapshot()
    {
        Logger.debug(LogCategory.GENERAL, "TurnHarness: Serializing world and session snapshot.")

        val targetDir = getSnapshotDirectory()
        if(!targetDir.exists())
        {
            if (targetDir.mkdirs())
            {
                Logger.info(LogCategory.SYSTEM, "Created snapshot directory: ${targetDir.absolutePath}")
            }
            else
            {
                Logger.error(LogCategory.SYSTEM, "FAILED to create snapshot directory: ${targetDir.absolutePath}")
            }
        }

        val snapshot = buildCurrentGameSnapshot()

        try
        {
            // 1. Save Full Game Snapshot
            val fullSerialized = serialize(snapshot)
            val fullDestination = File(targetDir, "game_snapshot.json")
            writeStringToFile(fullDestination.absolutePath, fullSerialized)

            // 2. Save World Only (for backward compatibility)
            val worldSerialized = serialize(snapshot.world)
            val worldDestination = File(targetDir, "world.json")
            writeStringToFile(worldDestination.absolutePath, worldSerialized)

            Logger.info(LogCategory.SYSTEM, "TurnHarness: Successfully persisted game snapshot to ${fullDestination.absolutePath}")
        }
        catch(e: Exception)
        {
            Logger.error(LogCategory.SYSTEM, "TurnHarness: Failed to persist game snapshot: ${e.message}")
        }
    }

    /**
     * Captures the current [GameSnapshot] and persists it to the given user's
     * [org.ttt.autogenesis.server.vfs.VirtualFileSystem] account record under
     * the [structs.storage.RUNNING_GAME_KEY] key.
     *
     * Used by the single-player disconnect handler so the player can resume
     * the match from the exact turn they left off on after a reconnect or
     * dedicated-server restart.
     *
     * @param accelByteUserId The AccelByte user id of the human player whose
     *   account record should receive the snapshot. Must be non-blank.
     * @return [Result.success] if the snapshot was successfully written,
     *   [Result.failure] otherwise. The caller should not treat a failure as
     *   fatal — the shutdown timer still proceeds.
     */
    suspend fun serializeCurrentWorldSnapshotToUserRecord(accelByteUserId: String): Result<Unit>
    {
        if (accelByteUserId.isBlank())
        {
            Logger.warn(LogCategory.DATABASE, "TurnHarness.serializeCurrentWorldSnapshotToUserRecord: blank accelByteUserId, skipping running-game save")
            return Result.failure(IllegalArgumentException("accelByteUserId is blank"))
        }

        return try
        {
            val snapshot = buildCurrentGameSnapshot()
            val json = serialize(snapshot)

            val vfs = org.ttt.autogenesis.server.vfs.VirtualFileSystemManager.forUser(accelByteUserId)
            val saveResult = vfs.saveUserRecordFromJsonString(
                accelByteUserId,
                structs.storage.RUNNING_GAME_KEY,
                json
            )

            saveResult.fold(
                onSuccess = {
                    Logger.info(
                        LogCategory.DATABASE,
                        "TurnHarness: Persisted running-game snapshot for user=$accelByteUserId " +
                                "(round=${snapshot.world.roundNumber}, turnIndex=${snapshot.turnOrderIndex}, " +
                                "historyEntries=${snapshot.history.size})"
                    )
                    Result.success(Unit)
                },
                onFailure = { err ->
                    Logger.error(
                        LogCategory.DATABASE,
                        "TurnHarness: Failed to persist running-game snapshot for user=$accelByteUserId: ${err.message ?: err::class.simpleName}"
                    )
                    Result.failure(err)
                }
            )
        }
        catch (e: kotlinx.coroutines.CancellationException)
        {
            throw e
        }
        catch (e: Exception)
        {
            Logger.error(LogCategory.DATABASE, "TurnHarness.serializeCurrentWorldSnapshotToUserRecord: unexpected failure for user=$accelByteUserId: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Reads a previously saved running-game snapshot from the given user's
     * VFS account record and rehydrates [WorldManager] plus this [TurnHarness]
     * so the match resumes from where it was paused.
     *
     * After a successful apply the record is invalidated (delete when the
     * VFS allows it, `consumed`-sentinel when the cloud refuses the
     * delete with errorCode 20013). The sentinel is a small
     * `{"consumed": true, "consumedAt": "..."}` payload designed to fail
     * [GameSnapshot] deserialization; see
     * [GameRestoreRpcHandlers.hasRunningGame] for the matching consumer
     * that treats such a sentinel as "no saved game". The sentinel is
     * transient — the next disconnect overwrites the slot with a fresh
     * snapshot from the resumed game.
     *
     * @param accelByteUserId The AccelByte user id of the player whose
     *   running-game record should be loaded. Must be non-blank.
     * @return [Result.success] with `true` if a snapshot was found and applied,
     *   [Result.success] with `false` if no running-game record exists for the
     *   user, or [Result.failure] on a transport/deserialization error.
     */
    /**
     * Restores the running-game snapshot for the given user, with the calling
     * WS connection's playerId. The snapshot's `playerStats[*].playerID` is
     * remapped to [currentConnectionId] for the human player entry so that
     * `PlayerConnectionManager.hasAnyPrimarySession()` and
     * `WorldManager.findPlayerStatsByConnectionId` both resolve correctly
     * on the freshly-restored DS. Without the remap, `hasAnyPrimarySession`
     * filters `session.playerId in playerStats[*].playerID` and returns
     * `false` (the snapshot's playerID is the OLD session's playerId, not
     * the live one), which makes `startSinglePlayerShutdownCountdown` arm
     * the 60s shutdown timer even though the user is still connected.
     *
     * @param accelByteUserId The AccelByte user id whose snapshot to load.
     *   Must be non-blank.
     * @param currentConnectionId The WS connection id of the calling session
     *   (the live WS that should own the restored playerStats entry). When
     *   blank the remap is skipped and the snapshot's saved playerIDs are
     *   preserved verbatim — only use this when the caller does not have a
     *   WS connection (e.g. test-only entrypoints or the phase-D DS-respawn
     *   bootstrap in `GameInit.defineGameRules`).
     * @return [Result.success] with `true` if a snapshot was found and applied,
     *   [Result.success] with `false` if no running-game record exists for the
     *   user, or [Result.failure] on a transport/deserialization error.
     */
    suspend fun restoreWorldFromUserRecord(accelByteUserId: String, currentConnectionId: String = ""): Result<Boolean>
    {
        if (accelByteUserId.isBlank())
        {
            return Result.failure(IllegalArgumentException("accelByteUserId is blank"))
        }

        return try
        {
            val vfs = org.ttt.autogenesis.server.vfs.VirtualFileSystemManager.forUser(accelByteUserId)
            val fetchResult = vfs.fetchUserRecord(accelByteUserId, structs.storage.RUNNING_GAME_KEY)

            fetchResult.fold(
                onSuccess = { response ->
                    val value = response.value
                    if (value == null)
                    {
                        Logger.debug(LogCategory.DATABASE, "TurnHarness.restoreWorldFromUserRecord: no running-game record for user=$accelByteUserId")
                        return@fold Result.success(false)
                    }

                    val actualValue = if (value is kotlinx.serialization.json.JsonObject && value.containsKey("value"))
                    {
                        value["value"]!!
                    }
                    else
                    {
                        value
                    }

                    val jsonString = actualValue.toString()
                    val snapshot: GameSnapshot? = com.TTT.Util.deserialize<GameSnapshot>(jsonString)
                    if (snapshot == null)
                    {
                        val msg = "running-game record deserialized to null for user=$accelByteUserId"
                        Logger.error(LogCategory.DATABASE, "TurnHarness.restoreWorldFromUserRecord: $msg")
                        return@fold Result.failure(IllegalStateException(msg))
                    }
                    applyGameSnapshot(snapshot, remapConnectionIdForUser = accelByteUserId, remapToConnectionId = currentConnectionId)
                    Logger.info(
                        LogCategory.DATABASE,
                        "TurnHarness: Rehydrated running-game snapshot for user=$accelByteUserId " +
                                "(round=${snapshot.world.roundNumber}, turnIndex=${snapshot.turnOrderIndex}, " +
                                "historyEntries=${snapshot.history.size}, remappedConnectionId=${currentConnectionId.ifBlank { "<none>" }})"
                    )

                    // Post-restore hydration: arm the turn timer if the saved
                    // snapshot's active actor is the human (BUG 6), and replay
                    // the per-turn music decision so the world doesn't mount
                    // silent (BUG 4). Both are gated on the snapshot state being
                    // non-empty so the no-snapshot race-recovery branch is not
                    // affected.
                    hydratePostRestoreState(snapshot, accelByteUserId, currentConnectionId)

                    // Mark the world as just-restored for this user. The flag is
                    // the authoritative race-recovery signal that distinguishes
                    // "round-1 snapshot just rehydrated" from "fresh server with
                    // no game" — both look identical under `isWorldEmpty()` (see
                    // `WorldManager.lastRehydratedAccelByteUserId` docstring).
                    // Without this, GameRestoreRpcHandlers.isWorldAlreadyRestoredForUser
                    // returns false for round-1 games and the race-recovery branch
                    // fails to fire, surfacing as "No saved game found" on the
                    // user's Resume click.
                    gameState.WorldManager.markRehydratedFromSnapshot(accelByteUserId)

                    // BUG FIX — do NOT invalidate the snapshot on restore. The user
                    // must be able to click Resume multiple times (e.g., after a
                    // browser reload) without burning the saved state and being forced
                    // to re-play the whole turn pipeline from scratch. The next
                    // disconnect will overwrite the same CloudSave slot with a fresh
                    // snapshot (Server.kt:566 `serializeCurrentWorldSnapshotToUserRecord`),
                    // so the slot is naturally one-shot per *session* rather than per
                    // *restore*. Only an explicit "New Game" or game-over path should
                    // invalidate (see [clearRunningGameForUser] and the comment block
                    // for the documented contract).
                    //
                    // The previous comment claimed "Invalidate on restore — once the
                    // snapshot has been rehydrated it is one-shot" but that contradicts
                    // the docstring on [invalidateRunningGameRecord] which says mid-game
                    // disconnect must NOT call this. Removing this call makes the
                    // behavior match the documented contract.

                    Result.success(true)
                },
                onFailure = { err ->
                    // RecordNotFoundException means no saved game — that is a normal "no-op" result.
                    val msg = err.message ?: ""
                    if (err is org.ttt.autogenesis.server.vfs.RecordNotFoundException ||
                        msg.contains("not found", ignoreCase = true))
                    {
                        Logger.debug(LogCategory.DATABASE, "TurnHarness.restoreWorldFromUserRecord: no running-game record for user=$accelByteUserId")
                        Result.success(false)
                    }
                    else
                    {
                        Logger.warn(LogCategory.DATABASE, "TurnHarness.restoreWorldFromUserRecord: failed to fetch for user=$accelByteUserId: $msg")
                        Result.failure(err)
                    }
                }
            )
        }
        catch (e: kotlinx.coroutines.CancellationException)
        {
            throw e
        }
        catch (e: Exception)
        {
            Logger.error(LogCategory.DATABASE, "TurnHarness.restoreWorldFromUserRecord: unexpected failure for user=$accelByteUserId: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Applies the captured [snapshot] back into the live [WorldManager] and
     * this [TurnHarness]. Caller must hold no locks; this method acquires
     * [WorldManager.worldMutex] for the world mutations and
     * [turnStateLock] for the turn-harness bookkeeping.
     *
     * When [remapToConnectionId] is non-blank, the human player's
     * `playerStats` entry (matched by `accelByteUserId ==
     * remapConnectionIdForUser`) has its `playerID` field rewritten to
     * [remapToConnectionId] so that the live WS session's playerId
     * resolves through `WorldManager.findPlayerStatsByConnectionId` and
     * `PlayerConnectionManager.hasAnyPrimarySession`. Without the remap,
     * the snapshot's stale `playerID` (the WS playerId alive at the time
     * of capture) would never match the new WS connection, the user
     * would appear "unregistered" to the connection manager, and the
     * 60-second single-player shutdown timer would fire even though the
     * user is still connected.
     */
    private suspend fun applyGameSnapshot(snapshot: GameSnapshot, remapConnectionIdForUser: String = "", remapToConnectionId: String = "")
    {
        WorldManager.worldMutex.withLock {
            WorldManager.world = snapshot.world
            WorldManager.history = snapshot.history.toMutableList()
            WorldManager.geopoliticalAssessment = snapshot.geopoliticalAssessment
            val restoredStats = snapshot.playerStats.toMutableList()
            if (remapConnectionIdForUser.isNotBlank() && remapToConnectionId.isNotBlank())
            {
                // Find the human player's entry and rewrite the WS playerId so
                // the live session registers as the player. Skip when no
                // matching accelByteUserId (a corrupted snapshot would otherwise
                // be silently tolerated; in that case the snapshot is unusable
                // and we let the existing playerIDs through unchanged so a
                // caller can still inspect what was loaded).
                val remapTargets = restoredStats.filter { it.accelByteUserId == remapConnectionIdForUser }
                if (remapTargets.isNotEmpty())
                {
                    val previousIds = remapTargets.joinToString(",") { it.playerID.ifBlank { "<blank>" } }
                    val previousNpcFlags = remapTargets.joinToString(",") { it.isControlledByNpc.toString() }
                    for (entry in remapTargets)
                    {
                        entry.playerID = remapToConnectionId
                        // Force isConnected=true so the remap is observable to
                        // TurnHarness.getActivePlayer() and the gameplay
                        // orchestrator's first-turn check. The snapshot may
                        // have captured the player mid-disconnect with
                        // isConnected=false.
                        entry.isConnected = true
                        // Flip isControlledByNpc=false — the live WS session is
                        // a human reloading, not the AI-controlled snapshot
                        // entity. NPC entries (blank accelByteUserId) are
                        // never in this list, so they remain untouched.
                        entry.isControlledByNpc = false
                    }
                    Logger.info(
                        LogCategory.DATABASE,
                        "TurnHarness.applyGameSnapshot: remapped playerID for accelByteUserId='$remapConnectionIdForUser' " +
                                "from previous=[$previousIds] to '$remapToConnectionId' on ${remapTargets.size} entry(ies); " +
                                "flipped isControlledByNpc from [$previousNpcFlags] to [false]"
                    )
                }
                else
                {
                    Logger.warn(
                        LogCategory.DATABASE,
                        "TurnHarness.applyGameSnapshot: remap requested for accelByteUserId='$remapConnectionIdForUser' " +
                                "but no playerStats entry matched; leaving playerIDs unchanged " +
                                "(snapshot playerStats accelByteUserIds=${restoredStats.map { it.accelByteUserId }})"
                    )
                }
            }
            WorldManager.playerStats = restoredStats
            WorldManager.activeMapPackName = snapshot.mapPackName
            WorldManager.isSinglePlayer = snapshot.isSinglePlayer
            WorldManager.humanPlayerName = snapshot.humanPlayerName
        }

        turnStateLock.withLock {
            turnOrderIndex = snapshot.turnOrderIndex
            npcInterferenceList = snapshot.npcInterferenceList
            lastAnnouncedRound = snapshot.lastAnnouncedRound
            lastActiveNemesisNames = snapshot.lastActiveNemesisNames
            lastDefeatedNemesisNames = snapshot.lastDefeatedNemesisNames
            gameOverDispatched = false
        }

        // The map pack bytes are not part of the [GameSnapshot] payload, so we
        // re-resolve them by name now that the snapshot's activeMapPackName is
        // committed. Mirrors the [MapSelectionService] fallback chain
        // (uploaded repository first, packaged classpath second) so a saved
        // session that originally ran a packaged map resolves the same way.
        val resolvedMapBytes = if (snapshot.mapPackName.isNotBlank())
        {
            try
            {
                MapSelectionService.loadBytesByName(snapshot.mapPackName)
            }
            catch (e: Exception)
            {
                Logger.warn(
                    LogCategory.SYSTEM,
                    "TurnHarness.applyGameSnapshot: map pack lookup threw for name='${snapshot.mapPackName}': ${e.message}"
                )
                null
            }
        }
        else
        {
            null
        }

        if (resolvedMapBytes != null)
        {
            WorldManager.worldMutex.withLock {
                WorldManager.activeMapPackBytes = resolvedMapBytes
            }
        }
        else if (WorldManager.activeMapPackBytes == null)
        {
            // Snapshot is still valid; the map just will not render until the
            // operator copies the original map pack into the server's classpath
            // or the player uploads it again. We log under SYSTEM (not ERROR)
            // because resume without the map is a recoverable state, not a
            // broken save.
            Logger.warn(
                LogCategory.SYSTEM,
                "TurnHarness.applyGameSnapshot: could not resolve map pack bytes for name='${snapshot.mapPackName}'; " +
                        "UI map render will be missing until the pack is restored."
            )
        }
    }

    /**
     * Post-restore hydration: after a snapshot has been applied to the live
     * [WorldManager], arm the per-turn UI hooks so the rehydrated world
     * looks like a game in progress, not a fresh empty server.
     *
     * Two responsibilities:
     *
     * 1. **Turn timer** (BUG 6): if the saved snapshot's
     *    [GameSnapshot.turnOrderIndex] points at the human player, arm
     *    [WorldManager.startTurnTimer] so the UI countdown mounts the moment
     *    the gameplay view appears. If the NPC was up when the game shut
     *    down, no timer arms — the loop tick on the next submit will fire
     *    [executeSingleTurn] which arms the timer itself.
     *
     * 2. **Music** (BUG 4): the [GameSnapshot] does not capture the
     *    [org.ttt.autogenesis.audio.MusicDecision], so on a fresh server
     *    [org.ttt.autogenesis.server.audio.AudioManager.playingObjects] is
     *    empty after rehydrate. Re-run [MusicSelector.selectForTurn] with
     *    the same [org.ttt.autogenesis.audio.TurnContext] that
     *    [selectAndBroadcastMusicForTurn] would have built for the saved
     *    round/turn — this fires the rule-1 "initialConditions" bucket on
     *    round 1 (matching what a fresh round-1 game does) or the
     *    nemesis/terminal/layer rules on later rounds. The decision is
     *    broadcast via [org.ttt.autogenesis.server.audio.AudioManager.broadcastMusicSchedule]
     *    so the client receives [ui.audio.schedule] before [sendInitialSync]
     *    lands.
     *
     * Both helpers are no-ops on an empty world (defensive — pin the
     * boundary so the no-snapshot race-recovery branch is never affected).
     */
    private suspend fun hydratePostRestoreState(snapshot: GameSnapshot, accelByteUserId: String, currentConnectionId: String)
    {
        val world = WorldManager.world
        val turnOrder = world.turnOrder
        if (turnOrder.isEmpty())
        {
            Logger.debug(LogCategory.SYSTEM, "TurnHarness.hydratePostRestoreState: empty turn order — skipping post-restore hydration")
            return
        }
        val activeIndex = snapshot.turnOrderIndex.coerceIn(0, turnOrder.size - 1)
        val activeActor = turnOrder[activeIndex]

        // BUG 6 — turn timer. Only arm when the saved actor is the human
        // (the live session just identified via remap). NPC actors take
        // their turn when executeSingleTurn fires inside the loop tick.
        if (currentConnectionId.isNotBlank() && activeActor == snapshot.humanPlayerName)
        {
            WorldManager.startTurnTimer(snapshot.humanPlayerName, WorldManager.TURN_DURATION_SECONDS)
            Logger.info(
                LogCategory.SYSTEM,
                "TurnHarness.hydratePostRestoreState: armed turn timer for human='${snapshot.humanPlayerName}' (saved turnOrderIndex=$activeIndex, round=${world.roundNumber})"
            )
        }
        else
        {
            Logger.info(
                LogCategory.SYSTEM,
                "TurnHarness.hydratePostRestoreState: saved actor='$activeActor' is not the human ('${snapshot.humanPlayerName}') — turn timer will arm on next executeSingleTurn"
            )
        }

        // BUG 6 fix — RESUME-RESTORES-CORRECT-TURN: after rehydrating the
        // world, the turn-harness loop is no longer running (it died when
        // the browser disconnected). If the saved actor is the NPC, we must
        // restart the loop so the NPC's AI turn actually fires. Without
        // this call, the UI shows the default "Your Turn To Act" prompt
        // (because the client never receives a `ui.activeTurn` notification
        // for the NPC), the AI pipeline never runs, and the game is stuck
        // waiting for human input that should never come.
        //
        // We call runNextTurn() regardless of who the active actor is — the
        // loop's first iteration will pick up the saved actor from
        // turnOrderIndex, which is the correct behavior.
        if (loopJob?.isActive != true) {
            Logger.info(
                LogCategory.SYSTEM,
                "TurnHarness.hydratePostRestoreState: turn-harness loop is not active; calling runNextTurn() to resume the saved turn (activeIndex=$activeIndex, activeActor='$activeActor', round=${world.roundNumber})"
            )
            runNextTurn()
        }

        // BUG FIX (music stacking) — Use the current AudioManager state
        // instead of an empty list. Without this fix, every resume
        // broadcast told the client "fade out nothing" and added 4 new
        // tracks, accumulating layers on every reconnect. The normal
        // turn path at TurnHarness.kt:699 reads `AudioManager.playingObjects`
        // correctly; we mirror that here.
        try
        {
            val isFirstTurn = world.roundNumber == 1 && snapshot.turnOrderIndex == 0
            val actorNpc = world.findNpcByName(activeActor)
            val actorIsNemesisOrElderGod = actorNpc != null &&
                (actorNpc.type == NpcType.Nemesis || actorNpc.type == NpcType.ElderGod)
            val canWinIn4 = MusicSelector.canWinInNext4Rounds(world)
            val currentlyPlayingMusicIds = org.ttt.autogenesis.server.audio.AudioManager.playingObjects.values
                .filter { it.channelId == org.ttt.autogenesis.audio.AudioChannelIds.MUSIC_MASTER_ID }
                .map { it.id }
            val ctx = org.ttt.autogenesis.audio.TurnContext(
                actorName = activeActor,
                roundNumber = world.roundNumber,
                turnOrderIndex = snapshot.turnOrderIndex,
                isFirstTurn = isFirstTurn,
                actorIsNemesisOrElderGod = actorIsNemesisOrElderGod,
                canWinInNext4Rounds = canWinIn4,
                currentlyPlayingMusicIds = currentlyPlayingMusicIds
            )
            val runtimeCatalog = runtimeMusicCatalog()
            val perTurnSelector = MusicSelector(catalog = runtimeCatalog, random = rng)
            val decision = perTurnSelector.selectForTurn(ctx)
            val connectionManager = UiSignalRpcHandlers.connectionManager
            org.ttt.autogenesis.server.audio.AudioManager.broadcastMusicSchedule(decision, connectionManager)
            currentTurnMusicDecision = decision
            Logger.info(
                LogCategory.GENERAL,
                "TurnHarness.hydratePostRestoreState: broadcast music schedule (rule for actor='$activeActor', round=${world.roundNumber}, toPlay.size=${decision.toPlay.size}, toFadeOut.size=${decision.toFadeOut.size})"
            )
        }
        catch (e: Exception)
        {
            Logger.warn(
                LogCategory.GENERAL,
                "TurnHarness.hydratePostRestoreState: music replay failed (non-fatal): ${e.message}"
            )
        }
    }

    /**
     * Invalidates the [structs.storage.RUNNING_GAME_KEY] record for the
     * given human player.
     *
     * Called from two flows:
     *
     *   1. **Game-over path** (`UiSignalRpcHandlers.broadcastGameOver` in
     *      single-player mode). The game ended via natural win / loss /
     *      surrender; the snapshot must NOT be available to restore from
     *      on the next login, because restoring a finished game would
     *      confuse the player about whether they had won/lost.
     *
     *   2. **Explicit New Game / Cancel-then-start-fresh path** —
     *      [GameRestoreRpcHandlers.clearRunningGame] RPC and the
     *      `MatchmakingClient.clearRunningGame` client call. The player
     *      explicitly chose to discard the save before starting a new match.
     *
     * Best-effort fire-and-forget: failure is logged at WARN and the caller
     * does not need to gate game flow on the result. See
     * [invalidateRunningGameRecord] for the underlying delete-then-sentinel
     * strategy; this is a thin wrapper that just resolves the VFS for the
     * user and delegates.
     *
     * **Contract pinned by TurnHarnessRunningGameTest:**
     *   - Mid-game disconnect must NOT call this. The save-on-disconnect
     *     path in `Server.kt:524` only runs `serializeCurrentWorldSnapshot
     *     ToUserRecord` (preserves the snapshot for restore).
     *   - `broadcastGameOver` in single-player mode MUST eventually call
     *     this (whether directly or via the post-game `evaluateEndGame`
     *     path that triggers `broadcastGameOver`). The new e2e probe
     *     `resume-snapshot-cleared-on-game-over.mjs` verifies this end
     *     to end.
     *   - Surrender that ends the game is covered by the `broadcastGameOver`
     *     path — surrender itself does not need to call this directly.
     *
     * @param accelByteUserId The AccelByte user id whose record should be
     *   cleared. Blank ids are ignored without an I/O call.
     * @return [Result.success] when the invalidate succeeded (real delete
     *   on a VFS that allows it, or consumed-sentinel write on cloud VFS
     *   that rejects the delete); [Result.failure] on a transport error
     *   that the sentinel could not recover from either.
     */
    suspend fun clearRunningGameForUser(accelByteUserId: String): Result<Unit>
    {
        if (accelByteUserId.isBlank())
        {
            return Result.success(Unit)
        }
        return try
        {
            val vfs = org.ttt.autogenesis.server.vfs.VirtualFileSystemManager.forUser(accelByteUserId)
            invalidateRunningGameRecord(vfs, accelByteUserId)
            // Also clear the rehydrated flag if it was set for this user.
            // The flag is set by `applyGameSnapshot` after a successful
            // rehydrate; clearing here means a subsequent "New Game" / game-over
            // does not get short-circuited by race-recovery thinking the world
            // is still in the resumed state.
            if (gameState.WorldManager.lastRehydratedAccelByteUserId == accelByteUserId)
            {
                gameState.WorldManager.clearRehydratedFlag()
            }
            Result.success(Unit)
        }
        catch (e: kotlinx.coroutines.CancellationException)
        {
            throw e
        }
        catch (e: Exception)
        {
            Logger.warn(LogCategory.DATABASE, "TurnHarness.clearRunningGameForUser: unexpected failure for user=$accelByteUserId: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Invalidates the [structs.storage.RUNNING_GAME_KEY] record for [accelByteUserId].
     *
     * Strategy:
     *  1. Try the VFS delete (the path that works for local mode and for any
     *     future ops grant of the CLOUDSAVE:RECORD delete permission).
     *  2. On any failure other than [RecordNotFoundException] (which is a
     *     successful no-op for our purposes), fall back to writing a small
     *     `{"consumed": true, "consumedAt": "<ISO>"}` sentinel. The
     *     sentinel is designed to fail [GameSnapshot] deserialization, so
     *     [GameRestoreRpcHandlers.hasRunningGame] treats it as "no saved
     *     game" and stops offering the resume modal. The sentinel is
     *     transient — the next disconnect overwrites the slot with a
     *     fresh snapshot from the resumed game.
     *
     * This is the single source of truth for the "how do we forget a
     * running game when the cloud refuses the delete" trick. Three
     * call sites converge here:
     *
     *   - [restoreWorldFromUserRecord] (post-apply TTL — the consumed-
     *     sentinel after a successful rehydrate, so the next reconnect
     *     does not replay the same restore).
     *   - [clearRunningGameForUser] (explicit New Game / game-over —
     *     called from `UiSignalRpcHandlers.broadcastGameOver` in
     *     single-player mode and from `GameRestoreRpcHandlers.
     *     clearRunningGame` RPC).
     *   - (No third site today, but the helper stays private to keep
     *     the delete-then-sentinel behaviour consistent across all
     *     call sites if a future path needs to invalidate the snapshot
     *     without going through [clearRunningGameForUser].)
     *
     * @param vfs The VFS instance for the user (resolved by the caller so
     *   we don't repeat the [VirtualFileSystemManager.forUser] routing).
     * @param accelByteUserId The AccelByte user id whose record should be
     *   invalidated. Must be non-blank.
     */
    private suspend fun invalidateRunningGameRecord(
        vfs: org.ttt.autogenesis.server.vfs.VirtualFileSystem,
        accelByteUserId: String
    )
    {
        val deleteResult = vfs.deleteUserRecord(accelByteUserId, structs.storage.RUNNING_GAME_KEY)
        deleteResult.fold(
            onSuccess = {
                Logger.debug(
                    LogCategory.DATABASE,
                    "TurnHarness.invalidateRunningGameRecord: deleted running-game for user=$accelByteUserId"
                )
            },
            onFailure = { err ->
                val msg = err.message ?: ""
                if (err is org.ttt.autogenesis.server.vfs.RecordNotFoundException ||
                    msg.contains("not found", ignoreCase = true))
                {
                    Logger.debug(
                        LogCategory.DATABASE,
                        "TurnHarness.invalidateRunningGameRecord: no running-game to invalidate for user=$accelByteUserId"
                    )
                    return
                }

                // Cloud-side delete is forbidden (CLOUDSAVE:RECORD delete
                // permission is not granted on the admin client). Fall
                // back to writing a sentinel whose value fails
                // GameSnapshot deserialization.
                val sentinel = "{\"consumed\":true,\"consumedAt\":\"${java.time.Instant.now()}\"}"
                val writeResult = vfs.saveUserRecordFromJsonString(
                    accelByteUserId,
                    structs.storage.RUNNING_GAME_KEY,
                    sentinel
                )
                writeResult.fold(
                    onSuccess = {
                        Logger.info(
                            LogCategory.DATABASE,
                            "TurnHarness.invalidateRunningGameRecord: wrote consumed-sentinel for user=$accelByteUserId " +
                                    "(delete failed with: ${err::class.simpleName}: $msg)"
                        )
                    },
                    onFailure = { writeErr ->
                        Logger.warn(
                            LogCategory.DATABASE,
                            "TurnHarness.invalidateRunningGameRecord: BOTH delete and sentinel-write failed for user=$accelByteUserId " +
                                    "(delete=${err::class.simpleName}: $msg; write=${writeErr::class.simpleName}: ${writeErr.message})"
                        )
                    }
                )
            }
        )
    }

    /**
     * Evaluates victory conditions (territory control, resource dominance, or round limits).
     * If met, halts the game and broadcasts [GameOverData].
     */
    private suspend fun evaluateEndGame()
    {
        Logger.debug(LogCategory.GENERAL, "TurnHarness: Evaluating end game conditions.")
        gameOverLock.withLock {
            if(gameOverDispatched)
            {
                return
            }

            val context = captureEndGameContext()
            val outcome = determineWinningOutcome(context) ?: return
            val gameOver = buildGameOverData(outcome, context)
            UiSignalRpcHandlers.broadcastGameOver(gameOver)
            gameOverDispatched = true
            WorldManager.isGameActive = false
            WorldManager.clearSession()
            // matchEndHook: emit onMatchEnded so SessionStorageHandler persists the outcome.
            // Fire-and-forget: notifyMatchEnded invokes the registered callback synchronously,
            // and the SessionStorageHandler.writeSessionStorage subscriber in Server.kt
            // launches its own Dispatchers.IO coroutine for the actual write.
            gameState.WorldManager.notifyMatchEnded(
                accelbyte.session.MatchEndedEvent(
                    sessionId = gameState.WorldManager.activeSessionId,
                    outcome = "victory",
                    winnerName = gameOver.winnerName
                )
            )
            // Per-turn flushTurnUsage writes have already persisted the ledger and balance.
            accounting.Billing.exportBillingReport()
            Logger.info(LogCategory.GENERAL, "TurnHarness: Game over dispatched for winner=${gameOver.winnerName}.")
        }
    }

    private suspend fun dispatchForcedGameOver(winnerName: String, isPlayerVictory: Boolean)
    {
        Logger.info(LogCategory.SYSTEM, "TurnHarness: Dispatching forced game over for $winnerName (playerVictory=$isPlayerVictory).")
        gameOverLock.withLock {
            if(gameOverDispatched)
            {
                return
            }

            val context = captureEndGameContext()
            val outcome = GameOverOutcome(
                winnerName = winnerName,
                isPlayerVictory = isPlayerVictory,
                tieResolvedByResources = false,
                tieResolvedRandomly = false
            )
            val gameOver = buildGameOverData(outcome, context)
            UiSignalRpcHandlers.broadcastGameOver(gameOver)
            gameOverDispatched = true
            WorldManager.isGameActive = false
            WorldManager.clearSession()
            // matchEndHook: emit onMatchEnded so SessionStorageHandler persists the outcome.
            // Forced game overs use outcome="forced_end" so the persisted GameState
            // distinguishes them from natural victories / defeats.
            gameState.WorldManager.notifyMatchEnded(
                accelbyte.session.MatchEndedEvent(
                    sessionId = gameState.WorldManager.activeSessionId,
                    outcome = "forced_end",
                    winnerName = winnerName
                )
            )
            // Per-turn flushTurnUsage writes have already persisted the ledger and balance.
            accounting.Billing.exportBillingReport()
            Logger.info(LogCategory.GENERAL, "TurnHarness: Forced game over dispatched for winner=${gameOver.winnerName}.")
        }
    }

    /**
     * Result of [surrenderPlayer]. Returned to the [GameRpcHandlers.surrender] RPC layer
     * so the client gets a structured response without having to query the harness twice.
     */
    data class SurrenderResult(
        val accepted: Boolean,
        val reason: String = "",
        val gameEnded: Boolean = false,
        val winnerName: String? = null
    )

    /**
     * Concedes the match on behalf of [playerName]. The player must be a current,
     * connected human player in the active world. The call:
     *  1. Marks the player as surrendered (`Player.isSurrendered = true`).
     *  2. Removes their name from `world.turnOrder` so their turn is skipped.
     *  3. Releases every non-destroyed tile they own back to the unowned pool
     *     (`ruler = ""`); tiles are NOT destroyed and keep their point value.
     *  4. Appends a [GameHistory] entry to [WorldManager.history] and broadcasts it
     *     via [ActionHistoryRpcHandlers.broadcastTurnComplete] so the Details panel
     *     shows the surrender.
     *  5. Records an `ActionHistoryEvent` of type `PLAYER_OUTCOME` so the action
     *     history log picks it up.
     *  6. Re-broadcasts the world snapshot and persists it.
     *  7. Re-evaluates end-of-game conditions via [handleTerritoryBasedGameEnd] and
     *     [evaluateEndGame]. If the surrender resolved the match, [SurrenderResult.gameEnded]
     *     and [SurrenderResult.winnerName] reflect the outcome.
     *
     * Reject reasons (in `SurrenderResult.reason`):
     *  - `game_not_active`        : the game loop is not running.
     *  - `unknown_player`         : no active player matches the given name.
     *  - `not_a_human`            : the matched player is AI-controlled.
     *  - `already_surrendered`    : the player has already conceded.
     */
    suspend fun surrenderPlayer(playerName: String, reason: String): SurrenderResult
    {
        val trimmedName = playerName.trim()
        if(trimmedName.isBlank())
        {
            return SurrenderResult(accepted = false, reason = "unknown_player")
        }
        if(!WorldManager.isGameActive)
        {
            return SurrenderResult(accepted = false, reason = "game_not_active")
        }

        // Phase 1: validate and mutate the world under the world mutex.
        val releaseResult = WorldManager.worldMutex.withLock {
            val player = WorldManager.world.findPlayerByName(trimmedName)
                ?: return@withLock SurrenderResult(accepted = false, reason = "unknown_player")
            if(player.isSurrendered)
            {
                return@withLock SurrenderResult(accepted = false, reason = "already_surrendered")
            }
            val stats = WorldManager.findPlayerFromStats(trimmedName)
            if(stats != null && stats.isControlledByNpc)
            {
                return@withLock SurrenderResult(accepted = false, reason = "not_a_human")
            }

            Logger.info(LogCategory.SYSTEM, "TurnHarness.surrenderPlayer: Surrendering $trimmedName (reason='$reason').")

            // Mark the player as surrendered. The Player stays in activePlayers so
            // end-of-game placements, billing reports, and history still see them.
            player.isSurrendered = true

            // Remove the player from the turn order so their turn is skipped.
            val removedFromOrder = WorldManager.world.turnOrder.removeAll {
                it.trim().equals(trimmedName, ignoreCase = true)
            }
            Logger.info(
                LogCategory.SYSTEM,
                "TurnHarness.surrenderPlayer: removed $trimmedName from turnOrder (wasPresent=$removedFromOrder, remaining=${WorldManager.world.turnOrder.size})."
            )

            // Release all non-destroyed tiles owned by the surrendering player.
            // Tiles are NOT destroyed; their point value stays on the board and
            // their ruler is cleared so they become claimable by any other owner.
            val releasedTiles = mutableListOf<String>()
            WorldManager.world.mapTiles.forEach { tile ->
                if(!tile.isDestroyed && tile.ruler.trim().equals(trimmedName, ignoreCase = true))
                {
                    tile.ruler = ""
                    releasedTiles.add(tile.name)
                }
            }
            Logger.info(
                LogCategory.SYSTEM,
                "TurnHarness.surrenderPlayer: released ${releasedTiles.size} tiles owned by $trimmedName: $releasedTiles"
            )

            // Build the GameHistory entry. This drives the Details panel via the
            // existing ui.turnComplete RPC that ActionHistoryRpcHandlers emits.
            val story = reason.ifBlank { "$trimmedName has surrendered." }
            val historyEntry = GameHistory(
                turnPlayer = trimmedName,
                turnAction = "Surrender",
                turnStory = story,
                wasPlayerSuccessful = false,
                turnResult = "Surrendered"
            )
            WorldManager.history.add(historyEntry)

            // Record the corresponding action history event for the action-history
            // log so audit tooling sees the same outcome.
            WorldManager.recordActionHistoryEventUnlocked(
                ActionHistoryEvent(
                    event = ActionHistory(
                        player = trimmedName,
                        eventType = GameEventType.PLAYER_OUTCOME,
                        metadata = PlayerOutcomeMetadata(
                            outcome = "Surrendered",
                            victory = false,
                            reason = reason
                        ),
                        turnNumber = WorldManager.world.roundNumber,
                        timestampMillis = System.currentTimeMillis()
                    )
                )
            )

            // Broadcast the GameHistory entry to all clients so the Details panel
            // picks it up. The broadcast itself is fire-and-forget.
            try
            {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    ActionHistoryRpcHandlers.broadcastTurnComplete(historyEntry)
                }
            }
            catch(e: Exception)
            {
                Logger.warn(
                    LogCategory.NETWORK,
                    "TurnHarness.surrenderPlayer: Failed to broadcast surrender history entry: ${e.message}"
                )
            }

            // Re-broadcast the world snapshot so every client sees the cleared tiles
            // and the now-surrendered player in the same tick.
            try
            {
                UiSignalRpcHandlers.broadcastWorldUpdate(WorldManager.world)
            }
            catch(e: Exception)
            {
                Logger.warn(
                    LogCategory.NETWORK,
                    "TurnHarness.surrenderPlayer: Failed to broadcast world update: ${e.message}"
                )
            }

            SurrenderResult(accepted = true, reason = "ok")
        }

        if(!releaseResult.accepted)
        {
            return releaseResult
        }

        // Phase 2: persist the world snapshot.
        try
        {
            serializeCurrentWorldSnapshot()
        }
        catch(e: Exception)
        {
            Logger.warn(
                LogCategory.DATABASE,
                "TurnHarness.surrenderPlayer: snapshot serialization failed (non-fatal): ${e.message}"
            )
        }

        // Phase 3: re-evaluate end-of-game. handleTerritoryBasedGameEnd covers the
        // single-player fast path; evaluateEndGame routes through
        // determineWinningOutcome (which now also has the "last non-surrendered
        // contender wins" rule) and the threshold / round-cap logic.
        handleTerritoryBasedGameEnd()
        if(WorldManager.isGameActive)
        {
            evaluateEndGame()
        }

        val gameEnded = !WorldManager.isGameActive
        val winnerName = if(gameEnded)
        {
            // gameOverDispatched was set inside evaluateEndGame / dispatchForcedGameOver.
            // We don't have the winner name handy here without re-running context capture,
            // but the RPC layer can read the last broadcast GameOverData through its
            // own UiSignalRpcHandlers.onGameOverBroadcast callback. The single-player
            // path returns the dominant owner via dispatchForcedGameOver; the multi-player
            // path ends through evaluateEndGame. For the response payload we surface
            // null when we cannot cheaply read it; the next ui.gameOver broadcast carries
            // the canonical winner name to all clients regardless.
            null
        }
        else null

        Logger.info(
            LogCategory.SYSTEM,
            "TurnHarness.surrenderPlayer: surrender applied for $trimmedName (gameEnded=$gameEnded)."
        )

        // Single-player surrender ends the match and there is no one left to
        // play. Notify the host so the surrendering player's session can be
        // deregistered — the existing onDisconnected handler at Server.kt:385
        // then arms the single-player shutdown countdown via
        // startSinglePlayerShutdownCountdown. Without this, the surrender
        // path leaves the player's WebSocket alive and the server ticks
        // CountdownTimer for ~28s before the socket finally drops.
        //
        // Multiplayer surrender is intentionally a no-op here: a surrendered
        // player in a still-running match must stay connected while other
        // humans and NPCs continue. Gating on isSinglePlayer (not just
        // gameEnded) is what makes this safe.
        //
        // We resolve the connectionId from PlayerStats because that is the
        // exact key PlayerConnectionManager keys its session map on. If the
        // stats entry is missing we silently skip — there is no session to
        // deregister, so firing the hook with a blank id would be worse than
        // not firing it at all.
        if(gameEnded && WorldManager.isSinglePlayer)
        {
            val surrenderingConnectionId = WorldManager.findPlayerFromStats(trimmedName)?.playerID
            if(!surrenderingConnectionId.isNullOrBlank())
            {
                Logger.info(
                    LogCategory.SYSTEM,
                    "TurnHarness.surrenderPlayer: single-player surrender ended the game; requesting disconnect of $surrenderingConnectionId."
                )
                onPlayerDisconnectedFromSurrender?.invoke(surrenderingConnectionId)
            }
        }

        return SurrenderResult(
            accepted = true,
            reason = "ok",
            gameEnded = gameEnded,
            winnerName = winnerName
        )
    }

    /**
     * Increments [turnOrderIndex] and manages wrapping logic.
     * If the index returns to 0, [WorldManager.world.roundNumber] is incremented and round flags are reset.
     */
    private suspend fun advanceTurnIndexAndRoundIfNeeded()
    {
        var roundAdvanced = false
        WorldManager.worldMutex.withLock {
            val orderSize = WorldManager.world.turnOrder.size
            if(orderSize <= 0)
            {
                Logger.error(LogCategory.SYSTEM, "TurnHarness.advanceTurnIndexAndRoundIfNeeded: CRITICAL - turnOrder.size is $orderSize! Cannot advance. turnOrderIndex=$turnOrderIndex, activePlayers=${WorldManager.world.activePlayers.map { it.name }}, npc=${WorldManager.world.npc.map { it.name }}")
                return@withLock
            }

            val oldIndex = turnOrderIndex
            turnOrderIndex = (turnOrderIndex + 1) % orderSize
            Logger.info(LogCategory.SYSTEM, "TurnHarness.advanceTurnIndexAndRoundIfNeeded: Advanced turnOrderIndex from $oldIndex to $turnOrderIndex (orderSize=$orderSize)")
            
            if(turnOrderIndex == 0)
            {
                val oldRound = WorldManager.world.roundNumber
                WorldManager.world.roundNumber += 1
                WorldManager.resetRoundFlags()
                roundAdvanced = true
                Logger.info(LogCategory.GENERAL, "TurnHarness.advanceTurnIndexAndRoundIfNeeded: Turn order wrapped, advanced round from $oldRound to ${WorldManager.world.roundNumber}.")
            }

            val nextActor = WorldManager.world.turnOrder.getOrNull(turnOrderIndex).orEmpty()
            if(nextActor.isNotBlank())
            {
                WorldManager.activeTurnActor = nextActor
                Logger.debug(LogCategory.SYSTEM, "TurnHarness.advanceTurnIndexAndRoundIfNeeded: Set next actor to '$nextActor'")
            }
            else
            {
                Logger.warn(LogCategory.SYSTEM, "TurnHarness.advanceTurnIndexAndRoundIfNeeded: Next actor at index $turnOrderIndex is blank or null! turnOrder=${WorldManager.world.turnOrder}")
            }
        }

        if(roundAdvanced)
        {
            announceRoundStartIfNeeded()
        }
    }

    /**
     * Ensures the [WorldManager.world] turn order is populated. Defaults to player list if empty.
     */
    private suspend fun bootstrapTurnOrderIfNeeded()
    {
        WorldManager.worldMutex.withLock {
            val world = WorldManager.world
            Logger.debug(LogCategory.SYSTEM, "TurnHarness.bootstrapTurnOrderIfNeeded: Entry - turnOrder.size=${world.turnOrder.size}, activePlayers.size=${world.activePlayers.size}")
            
            if(world.turnOrder.isEmpty())
            {
                if(world.activePlayers.isEmpty())
                {
                    Logger.warn(LogCategory.SYSTEM, "TurnHarness.bootstrapTurnOrderIfNeeded: activePlayers is empty! Cannot populate turnOrder.")
                }
                else
                {
                    world.turnOrder.addAll(world.activePlayers.map { it.name })
                    Logger.info(LogCategory.SYSTEM, "TurnHarness.bootstrapTurnOrderIfNeeded: Populated turnOrder with ${world.turnOrder.size} players: ${world.turnOrder}")
                }
            }
            
            if(world.turnOrder.isNotEmpty())
            {
                pruneTerritorylessAiPlayers(world.turnOrder)
                enforceSinglePlayerActorFirst(world.turnOrder)
                WorldManager.activeTurnActor = world.turnOrder.first()
                Logger.info(LogCategory.SYSTEM, "TurnHarness.bootstrapTurnOrderIfNeeded: Set initial activeTurnActor to '${WorldManager.activeTurnActor}'")
            }
            else
            {
                Logger.error(LogCategory.SYSTEM, "TurnHarness.bootstrapTurnOrderIfNeeded: CRITICAL - turnOrder is still empty after bootstrap attempt!")
            }
            
            val oldIndex = turnOrderIndex
            turnOrderIndex = turnOrderIndex.coerceIn(0, (world.turnOrder.size - 1).coerceAtLeast(0))
            if(oldIndex != turnOrderIndex)
            {
                Logger.info(LogCategory.SYSTEM, "TurnHarness.bootstrapTurnOrderIfNeeded: Coerced turnOrderIndex from $oldIndex to $turnOrderIndex (turnOrder.size=${world.turnOrder.size})")
            }
        }
    }

    private suspend fun resolveCurrentActor(): String?
    {
        return WorldManager.worldMutex.withLock {
            val world = WorldManager.world
            pruneTerritorylessAiPlayers(world.turnOrder)
            if(world.turnOrder.isEmpty())
            {
                Logger.debug(LogCategory.SYSTEM, "TurnHarness.resolveCurrentActor: turnOrder is empty, attempting to populate from activePlayers/npc")
                if(world.activePlayers.isEmpty() && world.npc.isEmpty())
                {
                    Logger.error(LogCategory.SYSTEM, "TurnHarness.resolveCurrentActor: CRITICAL - Both activePlayers and npc are empty! Cannot resolve actor. turnOrderIndex=$turnOrderIndex")
                    return@withLock null
                }
                world.turnOrder.addAll(world.activePlayers.map { it.name })
                Logger.debug(LogCategory.SYSTEM, "TurnHarness.resolveCurrentActor: Added ${world.activePlayers.size} activePlayers to turnOrder")
                enforceSinglePlayerActorFirst(world.turnOrder)
                if(world.turnOrder.isEmpty())
                {
                    val eligibleNpcs = world.npc.filter { !it.isDefeated && it.type != NpcType.Passive }
                    world.turnOrder.addAll(eligibleNpcs.map { it.name })
                    Logger.debug(LogCategory.SYSTEM, "TurnHarness.resolveCurrentActor: Added ${eligibleNpcs.size} npcs to turnOrder")
                }
            }

            if(world.turnOrder.isEmpty())
            {
                Logger.error(LogCategory.SYSTEM, "TurnHarness.resolveCurrentActor: CRITICAL - turnOrder still empty after population attempt! activePlayers=${world.activePlayers.size}, npc=${world.npc.size}")
                return@withLock null
            }

            if(turnOrderIndex >= world.turnOrder.size)
            {
                Logger.warn(LogCategory.SYSTEM, "TurnHarness.resolveCurrentActor: turnOrderIndex ($turnOrderIndex) >= turnOrder.size (${world.turnOrder.size}), resetting to 0")
                turnOrderIndex = 0
            }
            
            val actor = world.turnOrder.getOrNull(turnOrderIndex)
            if(actor == null)
            {
                Logger.error(LogCategory.SYSTEM, "TurnHarness.resolveCurrentActor: CRITICAL - turnOrder.getOrNull($turnOrderIndex) returned null! turnOrder=${ world.turnOrder}, size=${world.turnOrder.size}")
            }
            else
            {
                Logger.debug(LogCategory.SYSTEM, "TurnHarness.resolveCurrentActor: Resolved actor='$actor' at index=$turnOrderIndex")
            }
            actor
        }
    }

    /**
     * Evaluates karma threshold and triggers [buildNemesisCreationAgent] to spawn a world-level threat.
     */
    private suspend fun handleNemesisFromKarma(seedText: String)
    {
        val activeBeforeSpawn = WorldManager.worldMutex.withLock {
            WorldManager.world.npc
                .filter { it.type == NpcType.Nemesis && !it.isDefeated }
                .map { it.name }
                .toSet()
        }

        var shouldSpawn = false
        WorldManager.worldMutex.withLock {
            if(WorldManager.world.karmaPoints >= 100)
            {
                shouldSpawn = true
                WorldManager.world.karmaPoints = 0
            }
        }

        if(!shouldSpawn)
        {
            return
        }

        try
        {
            val broadcastIds = getAllConnectedClientIds()
            val agent = buildNemesisCreationAgent().apply {
                enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
                enablePipeTimeout(
                    applyRecursively = true,
                    duration = 180000,
                    autoRetry = true,
                    retryLimit = 5
                )
                init(true)
                streamPipelineOutputToAgentWorkBuffer(broadcastIds, this)
            }
            withContext(Dispatchers.Default) {
                agent.execute(MultimodalContent(seedText))
            }
        }
        catch(e: Exception)
        {
            Logger.error(LogCategory.SYSTEM, "TurnHarness: Nemesis spawn agent failed: ${e.message}")
        }

        var spawnedName: String? = null
        WorldManager.worldMutex.withLock {
            val activeAfterSpawn = WorldManager.world.npc
                .filter { it.type == NpcType.Nemesis && !it.isDefeated }
                .map { it.name }
                .toSet()
            spawnedName = activeAfterSpawn.firstOrNull { !activeBeforeSpawn.contains(it) }
            if(spawnedName != null)
            {
                WorldManager.world.activePlayers.forEach { player ->
                    player.summitPoints += SUMMIT_POINTS_ON_NEMESIS_EVENT
                }
                Logger.info(LogCategory.GENERAL, "TurnHarness: Awarded $SUMMIT_POINTS_ON_NEMESIS_EVENT summit point(s) to ${WorldManager.world.activePlayers.size} players due to nemesis arrival: $spawnedName")
            }
        }

        spawnedName?.let { nemesisName ->
            UiSignalRpcHandlers.broadcastNemesisThreatAnnouncement(
                NemesisThreatAnnouncementData(
                    roundNumber = WorldManager.world.roundNumber,
                    nemesisName = nemesisName,
                    kind = NemesisThreatKind.ARRIVAL,
                    reason = "$nemesisName has emerged as a global threat. Summit points remain disabled (+0)."
                )
            )
        }
    }

    /**
     * Rolls a random check to revive previously defeated Nemesis NPCs.
     */
    private suspend fun rollNemesisRevival()
    {
        val revivedName = WorldManager.worldMutex.withLock {
            val defeated = WorldManager.world.npc.filter { it.type == NpcType.Nemesis && it.isDefeated }
            if(defeated.isEmpty())
            {
                return@withLock null
            }
            if(rng.nextInt(100) >= 25)
            {
                return@withLock null
            }
            val chosen = defeated.random(rng)
            chosen.isDefeated = false
            chosen.name
        } ?: return

        UiSignalRpcHandlers.broadcastNemesisThreatAnnouncement(
            NemesisThreatAnnouncementData(
                roundNumber = WorldManager.world.roundNumber,
                nemesisName = revivedName,
                kind = NemesisThreatKind.REVIVAL,
                reason = "$revivedName has returned to the battlefield. Summit points remain disabled (+0)."
            )
        )

        WorldManager.worldMutex.withLock {
            WorldManager.world.activePlayers.forEach { player ->
                player.summitPoints += SUMMIT_POINTS_ON_NEMESIS_EVENT
            }
            Logger.info(LogCategory.GENERAL, "TurnHarness: Awarded $SUMMIT_POINTS_ON_NEMESIS_EVENT summit point(s) to ${WorldManager.world.activePlayers.size} players due to nemesis revival: $revivedName")
        }
    }

    /**
     * Broadcasts a full copy of the [WorldManager.world] to all clients.
     */
    private suspend fun broadcastWorldSnapshot()
    {
        Logger.debug(LogCategory.GENERAL, "TurnHarness: Broadcasting world snapshot for round ${WorldManager.world.roundNumber}.")
        val worldSnapshot = WorldManager.worldMutex.withLock { WorldManager.world.copy() }
        UiSignalRpcHandlers.broadcastWorldUpdate(worldSnapshot)
    }

    private suspend fun applyDecayForAllPlayers(activePlayerName: String?, actionCategory: ActionCategory)
    {
        WorldManager.worldMutex.withLock {
            WorldManager.world.activePlayers.forEach { player ->
                val playerActionCategory = if(activePlayerName != null && player.name.equals(activePlayerName, ignoreCase = true))
                {
                    actionCategory
                }
                else
                {
                    ActionCategory.OTHER
                }
                applyDecayForActor(player, playerActionCategory)
            }
        }
    }

    private fun categorizeAction(action: String): ActionCategory
    {
        val text = action.lowercase()
        if(text.contains("attack") || text.contains("invade") || text.contains("military") || text.contains("defend"))
        {
            return ActionCategory.MILITARY
        }
        if(text.contains("alliance") || text.contains("treaty") || text.contains("diplom") || text.contains("negot"))
        {
            return ActionCategory.DIPLOMATIC
        }
        if(text.contains("research") || text.contains("develop") || text.contains("invent") || text.contains("science"))
        {
            return ActionCategory.RESEARCH
        }
        return ActionCategory.OTHER
    }

    private fun applyDecayForActor(player: Player, actionCategory: ActionCategory)
    {
        val militaryDelta = when(player.trait)
        {
            CommanderTrait.Diplomatic -> 10
            CommanderTrait.Balanced -> 5
            CommanderTrait.Warlord -> 0
            CommanderTrait.Researcher -> 5
        }
        val legitimacyDelta = when(player.trait)
        {
            CommanderTrait.Warlord -> 10
            CommanderTrait.Balanced -> 5
            CommanderTrait.Diplomatic -> 0
            CommanderTrait.Researcher -> 5
        }
        val stagnationDelta = if(player.trait == CommanderTrait.Researcher) 0 else 5

        player.militaryReadiness = if(actionCategory == ActionCategory.MILITARY)
        {
            (player.militaryReadiness + militaryDelta).coerceIn(MILITARY_FLOOR, 100)
        }
        else
        {
            (player.militaryReadiness - militaryDelta).coerceIn(MILITARY_FLOOR, 100)
        }

        player.legitimacy = if(actionCategory == ActionCategory.DIPLOMATIC)
        {
            (player.legitimacy + legitimacyDelta).coerceIn(LEGITIMACY_FLOOR, 100)
        }
        else
        {
            (player.legitimacy - legitimacyDelta).coerceIn(LEGITIMACY_FLOOR, 100)
        }

        player.stagnation = if(actionCategory == ActionCategory.RESEARCH)
        {
            (player.stagnation - stagnationDelta).coerceIn(0, STAGNATION_CAP)
        }
        else
        {
            (player.stagnation + stagnationDelta).coerceIn(0, STAGNATION_CAP)
        }

        when(player.trait)
        {
            CommanderTrait.Warlord ->
            {
                player.militaryReadiness = (player.militaryReadiness + 10).coerceIn(MILITARY_FLOOR, 100)
            }
            CommanderTrait.Diplomatic ->
            {
                player.legitimacy = (player.legitimacy + 10).coerceIn(LEGITIMACY_FLOOR, 100)
            }
            CommanderTrait.Researcher ->
            {
                player.stagnation = (player.stagnation - 5).coerceIn(0, STAGNATION_CAP)
            }
            CommanderTrait.Balanced ->
            {
                player.militaryReadiness = (player.militaryReadiness + 5).coerceIn(MILITARY_FLOOR, 100)
            }
        }
    }

    private fun rollNpcInterference(npcs: List<Npc>): List<String>
    {
        val maxSlots = rng.nextInt(1, 5) // Roll between 1 and 4 slots
        Logger.info(LogCategory.SYSTEM, "TurnHarness: Rolling for NPC interference slots this round: $maxSlots")

        val results = mutableListOf<String>()
        val eligibleNpcs = npcs.filter { !it.isDefeated && it.type != NpcType.Passive }.shuffled(rng)

        for (npc in eligibleNpcs)
        {
            if (results.size >= maxSlots) break
            if (rng.nextDouble() < npc.interferenceChance)
            {
                results.add(npc.name)
            }
        }

        Logger.info(LogCategory.SYSTEM, "TurnHarness: NPC interference slots filled: ${results.size}/$maxSlots (${results.joinToString()})")
        return results
    }

    private fun insertInterferingNpcs(order: MutableList<String>, interfering: List<String>)
    {
        interfering.forEach { npcName ->
            if(order.contains(npcName))
            {
                return@forEach
            }
            val insertAt = rng.nextInt(0, order.size + 1)
            order.add(insertAt, npcName)
        }
    }

    private suspend fun captureEndGameContext(): EndGameContext
    {
        return WorldManager.worldMutex.withLock {
            val owners = WorldManager.world.mapTiles
                .map { it.ruler.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            EndGameContext(
                roundNumber = WorldManager.world.roundNumber,
                owners = owners,
                playerSnapshots = WorldManager.world.activePlayers
                    .filter { !it.isSurrendered }
                    .map { player ->
                    PlayerSnapshot(
                        name = player.name,
                        victoryPoints = player.victoryPoints,
                        resources = player.resources.map { it.copy() }
                    )
                },
                npcSnapshots = WorldManager.world.npc.map { npc ->
                    NpcSnapshot(
                        name = npc.name,
                        pointValue = npc.pointValue,
                        isDefeated = npc.isDefeated,
                        type = npc.type
                    )
                },
                mapTiles = WorldManager.world.mapTiles.map { it.copy() }
            )
        }
    }

    /**
     * Player win threshold by active-player count. 4 players → 51%, 3 → 55%, 2 → 60%. Any other
     * count (1 or 0) defaults to 60% as the safest fallback so the game still progresses.
     */
    private fun playerWinThreshold(playerCount: Int): Double
    {
        return when(playerCount.coerceIn(1, 4))
        {
            4 -> 51.0
            3 -> 55.0
            else -> 60.0
        }
    }

    /** Nemesis win threshold (and Elder-God destruction threshold) is fixed at 50%. */
    private const val NPC_WIN_THRESHOLD_PERCENT: Double = 50.0

    private fun ownerHitsShareThreshold(
        ownerName: String,
        thresholdPercent: Double
    ): Boolean
    {
        return WorldManager.hasOwnerTerritoryCountShare(ownerName, thresholdPercent) ||
            WorldManager.hasOwnerTerritoryPointShare(ownerName, thresholdPercent)
    }

    private fun determineWinningOutcome(context: EndGameContext): GameOverOutcome?
    {
        val playerThreshold = playerWinThreshold(context.playerSnapshots.size)

        // 1. Player win: any active player hits the player-count-aware threshold by EITHER
        //    active map count OR active map point value.
        val winningPlayers = context.playerSnapshots
            .filter { ownerHitsShareThreshold(it.name, playerThreshold) }
            .map { it.name }
        if(winningPlayers.size == 1)
        {
            val winner = winningPlayers.first()
            Logger.info(
                LogCategory.GENERAL,
                "TurnHarness: End game winner determined by player territory threshold " +
                    "(${playerThreshold}%): $winner."
            )
            return GameOverOutcome(winner, isPlayerVictory = true, tieResolvedByResources = false, tieResolvedRandomly = false)
        }
        if(winningPlayers.size > 1)
        {
            return resolveByResources(context, winningPlayers)
        }

        // 2. Nemesis win: any undefeated Nemesis NPC controls ≥50% by EITHER metric.
        val winningNemeses = context.npcSnapshots
            .filter { !it.isDefeated && it.type == enums.NpcType.Nemesis }
            .filter { ownerHitsShareThreshold(it.name, NPC_WIN_THRESHOLD_PERCENT) }
            .map { it.name }
        if(winningNemeses.size == 1)
        {
            val winner = winningNemeses.first()
            Logger.info(
                LogCategory.GENERAL,
                "TurnHarness: End game winner determined by Nemesis territory threshold " +
                    "(${NPC_WIN_THRESHOLD_PERCENT}%): $winner."
            )
            return GameOverOutcome(winner, isPlayerVictory = false, tieResolvedByResources = false, tieResolvedRandomly = false)
        }
        if(winningNemeses.size > 1)
        {
            return resolveByResources(context, winningNemeses)
        }

        // 3. Elder-God destruction win: ≥50% of map tiles destroyed.
        if(WorldManager.hasDestroyedTerritoryShare(NPC_WIN_THRESHOLD_PERCENT))
        {
            Logger.info(
                LogCategory.GENERAL,
                "TurnHarness: Destroyed territory threshold (${NPC_WIN_THRESHOLD_PERCENT}%) met, Elder God triggered."
            )
            return GameOverOutcome("Elder God", isPlayerVictory = false, tieResolvedByResources = false, tieResolvedRandomly = false)
        }

        // 4. 25-round cap: resolve by resource score over all players.
        if(context.roundNumber >= 25)
        {
            return resolveByResources(context, context.playerSnapshots.map { it.name })
        }

        return null
    }

    private fun resolveByResources(context: EndGameContext, candidates: List<String>): GameOverOutcome?
    {
        if(context.playerSnapshots.isEmpty())
        {
            return candidates.firstOrNull()?.let {
                GameOverOutcome(it, false, tieResolvedByResources = false, tieResolvedRandomly = false)
            }
        }

        val normalizedCandidates = candidates.map { it.lowercase() }.toSet()
        val scopedPlayers = context.playerSnapshots.filter { candidate ->
            normalizedCandidates.isEmpty() || normalizedCandidates.contains(candidate.name.lowercase())
        }.ifEmpty { context.playerSnapshots }

        val resourceScores = scopedPlayers.associate { snapshot ->
            val points = snapshot.resources.count { !it.isDestroyedOrDepleted }
            snapshot.name to points
        }

        val maxPoints = resourceScores.values.maxOrNull() ?: return null
        val topPlayers = resourceScores.filter { it.value == maxPoints }.keys.toList()
        val winnerName = if(topPlayers.size == 1) topPlayers.first() else topPlayers.random(rng)
        Logger.info(
            LogCategory.GENERAL,
            "TurnHarness: Resource tie resolved in favor of $winnerName (players=${topPlayers.joinToString()}, points=$maxPoints, randomTie=${topPlayers.size > 1})."
        )
        return GameOverOutcome(
            winnerName = winnerName,
            isPlayerVictory = true,
            tieResolvedByResources = true,
            tieResolvedRandomly = topPlayers.size > 1
        )
    }

    private fun buildGameOverData(outcome: GameOverOutcome, context: EndGameContext): GameOverData
    {
        val victoryPoints = context.playerSnapshots.firstOrNull { it.name.equals(outcome.winnerName, ignoreCase = true) }?.victoryPoints
            ?: context.npcSnapshots.firstOrNull { it.name.equals(outcome.winnerName, ignoreCase = true) }?.pointValue
            ?: 0

        val territoriesClaimed = context.mapTiles.count { tile ->
            !tile.isDestroyed && tile.ruler.equals(outcome.winnerName, ignoreCase = true)
        }

        val gameOver = GameOverData(
            winnerName = outcome.winnerName,
            isVictory = outcome.isPlayerVictory,
            rounds = context.roundNumber,
            victoryPoints = victoryPoints,
            territoriesClaimed = territoriesClaimed,
            placements = buildPlacements(context),
            tieResolvedByResourceScore = outcome.tieResolvedByResources,
            tieResolvedRandomly = outcome.tieResolvedRandomly
        )
        Logger.info(LogCategory.GENERAL, "TurnHarness: GameOverData prepared (winner=${gameOver.winnerName}, rounds=${gameOver.rounds}, placements=${gameOver.placements.size}).")
        return gameOver
    }

    private fun buildPlacements(context: EndGameContext): List<org.ttt.autogenesis.network.PlacementEntry>
    {
        val pointTotals = context.mapTiles
            .filter { !it.isDestroyed && it.ruler.isNotBlank() }
            .groupBy { it.ruler.trim() }
            .mapValues { (_, tiles) -> tiles.sumOf { it.pointValue } }

        val contenders = mutableListOf<Pair<String, Boolean>>()
        contenders.addAll(context.playerSnapshots.map { it.name to true })
        contenders.addAll(context.npcSnapshots.filter { !it.isDefeated }.map { it.name to false })

        return contenders
            .map { (name, isPlayer) ->
                org.ttt.autogenesis.network.PlacementEntry(
                    name = name,
                    rank = 0,
                    territoryPoints = pointTotals[name] ?: 0,
                    isPlayer = isPlayer
                )
            }
            .sortedWith(compareByDescending<org.ttt.autogenesis.network.PlacementEntry> { it.territoryPoints }.thenBy { it.name.lowercase() })
            .mapIndexed { index, entry ->
                entry.copy(rank = index + 1)
            }
    }

    private fun normalizeActorKey(actorName: String): String
    {
        return actorName.trim().lowercase()
    }

    /**
     * Internal container for turn execution metadata used by [handlePostTurn].
     */
    /**
     * Test-only: completes any pending awaiter for [playerName] with a
     * synthetic isAiTakeover submission. Used by /debug/advance-turn so the
     * push-turn-start e2e probe can drive the TurnHarness loop past a
     * disconnected player's first turn without waiting for the LLM-driven
     * AI takeover (which takes minutes and broadcasts streaming work events
     * to the now-gone WS).
     *
     * Returns true if an awaiter was completed, false if no awaiter existed
     * for that player name (e.g. the loop already moved on).
     */
    fun testForceAdvanceAwaiter(playerName: String): Boolean
    {
        val key = normalizeActorKey(playerName)
        val awaiter = kotlinx.coroutines.runBlocking {
            turnStateLock.withLock { pendingAwaiters.remove(key) }
        } ?: return false
        // Build a stub RpcCallContext. The submission context is only used
        // for downstream logging; the sender lambda is never invoked because
        // the test-advance path short-circuits AI takeover work that would
        // try to broadcast to the (already-gone) WS session.
        val context = RpcCallContext(
            connectionId = "test-advance-turn",
            sender = { /* no-op sender; the test path never invokes notify */ }
        )
        val submission = PlaySubmission(
            context = context,
            actorName = playerName,
            action = "test-advance-turn",
            isAiTakeover = true
        )
        awaiter.complete(submission)
        Logger.info(LogCategory.SYSTEM, "TurnHarness.testForceAdvanceAwaiter: completed awaiter for $playerName")
        return true
    }

    private data class TurnExecutionResult(
        val actorName: String,
        val isPlayerTurn: Boolean,
        val actionCategory: ActionCategory,
        val isAiTakeover: Boolean
    )

    /**
     * Encapsulates a validated player move submission.
     */
    private data class PlaySubmission(
        val context: RpcCallContext,
        val actorName: String,
        val action: String,
        val isAiTakeover: Boolean
    )

    private enum class ActionCategory
    {
        MILITARY,
        DIPLOMATIC,
        RESEARCH,
        OTHER
    }

    private data class PlayerSnapshot(
        val name: String,
        val victoryPoints: Int,
        val resources: List<Resource>
    )

    private data class NpcSnapshot(
        val name: String,
        val pointValue: Int,
        val isDefeated: Boolean,
        val type: enums.NpcType
    )

    private data class EndGameContext(
        val roundNumber: Int,
        val owners: List<String>,
        val playerSnapshots: List<PlayerSnapshot>,
        val npcSnapshots: List<NpcSnapshot>,
        val mapTiles: List<Territory>
    )

    private data class GameOverOutcome(
        val winnerName: String,
        val isPlayerVictory: Boolean,
        val tieResolvedByResources: Boolean,
        val tieResolvedRandomly: Boolean
    )
}