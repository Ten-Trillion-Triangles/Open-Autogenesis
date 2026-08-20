package agent.runners

import java.io.File
import agent.builders.validateAction.PlayType
import agent.builders.validateAction.PlayTypeObj
import agent.builders.validateAction.buildPlayDetectionAgent
import agent.builders.validateAction.ActionTargetType
import agent.builders.validateAction.ActionTargetTypeObj
import agent.builders.validateAction.ActionIntent
import agent.builders.validateAction.buildTargetDetectorAgent
import agent.builders.validateAction.buildValidator
import agent.builders.validateAction.buildRailroadAgent
import agent.builders.validateAction.buildCounterResponseIntentDetector
import agent.builders.validateAction.CounterResponseIntent
import agent.builders.validateAction.buildDefensiveValidator
import agent.builders.validateAction.resolveTerritoryTargets
import interfaces.Actor
import agent.builders.modifyGameState.buildActOfGodAgent
import serverStructs.TrueFalse
import agent.enums.BranchCase
import agent.structs.AgentRetry
import com.TTT.Config.TPipeConfig
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.extractJson
import com.TTT.Util.writeStringToFile
import com.TTT.Util.serialize
import gameState.WorldManager
import gameState.TimeProvider
import globals.BedrockConfig
import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPipe
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import agent.builders.judgeOutcome.buildAssessmentAgent
import agent.builders.AgentCoroutineScope
import agent.builders.judgeOutcome.AgentAssessmentLevel
import agent.math.GameMath
import agent.math.detectSourceLocation
import agent.math.validateSourceToTargetPaths
import com.TTT.Pipeline.Splitter
import agent.builders.writingAgent.buildNeoWritingAgent
import agent.runners.getTurnTraceDir

import agent.builders.passFailAgent.buildPassFailAgent
import agent.builders.judgeOutcome.`Victory?`
import agent.builders.modifyGameState.buildReverseAgent
import agent.builders.modifyGameState.buildHardenAgent
import agent.structs.HardenSoften
import agent.builders.judgeOutcome.buildJudge
import agent.builders.judgeOutcome.StatBuff
import agent.builders.judgeOutcome.MultiActorStatChanges
import agent.builders.modifyGameState.worldUpdatesPipeline
import agent.builders.playerAgent.buildPlayerAgent
import org.ttt.autogenesis.server.GenericPrompts
import org.ttt.autogenesis.server.UiSignalRpcHandlers
import org.ttt.autogenesis.server.TurnHarness
import org.ttt.autogenesis.server.ActionHistoryRpcHandlers
import org.ttt.autogenesis.network.ConnectionStatus
import org.ttt.autogenesis.network.ResolutionStep
import structs.Npc
import structs.Player
import structs.accelbyte.cloudsave.PlayerRecordResponse
import structs.GameHistory
import agent.builders.gatherContext.buildNewCharacterScanPipeline
import agent.managers.GameResponseManager
import agent.builders.writingAgent.buildResponseRefinementAgent
import kotlinx.coroutines.withTimeoutOrNull
import agent.builders.modifyGameState.buildNemesisCreationAgent
import agent.builders.validateAction.buildResourceUsageDetectorAgent
import agent.builders.validateAction.UsedAssets
import enums.NpcType
import enums.ResourceType
import enums.CommanderTrait
import kotlin.math.abs
import org.ttt.autogenesis.server.streamPipelineOutputToAgentWorkBuffer
import org.ttt.autogenesis.server.getAllConnectedClientIds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import agent.builders.judgeOutcome.Results
import agent.builders.judgeOutcome.AbstractResourceBuff
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Main server game loop function that orchestrates and executes the player turn.
 * Runs each agent in the correct order based on the logical state of the game and manages all upkeep steps
 * to resolve a turn action.
 * @param player The player executing the turn
 * @param turnAction The action string to execute
 */
/**
 * Maps pipeline stage names (or specific pipe names) to user-friendly progress messages.
 *
 * This map is used by [attachProgressHooks] to broadcast real-time status updates to the client
 * as the backend agent pipelines execute.
 *
 * Keys can be:
 * - Exact pipe names (e.g., "gains and losses pipe")
 * - Pipeline names (e.g., "Narrative Beat Planner")
 * - Suffixes for Splitter pipes (e.g., for "narrative:Narrative Beat Planner", the key is "Narrative Beat Planner")
 */
private val pipeProgressMap = mapOf(
    "Play Detection Agent" to "Analyzing player intent...",
    "resource detection pipe" to "Verifying asset usage...",
    "pass or fail pipe" to "Determining action outcome...",
    "gains and losses pipe" to "Calculating consequences...",
    "karma pipe" to "Evaluating karmic impact...",
    "stat change pipe" to "Updating character stats...",
    "essay pipe" to "Drafting geopolitical analysis...",
    "overton window pipe" to "Analyzing Overton Window...",
    "play normalcy pipe" to "Checking convention adherence...",
    "conflict level pipe" to "Measuring global tension...",
    "written assessment pipe" to "Finalizing political report...",
    "numeric scoring pipe" to "Scoring political impact...",
    "harden story pipe" to "Intensifying narrative outcomes...",
    "reversal-pipe" to "Inverting story causality...",
    "physics changes and map tiles removed pipe" to "Updating physical world state...",
    "player action analysis pipe" to "Analyzing player patterns...",
    "story analysis pipe" to "Conceptualizing Nemesis...",
    "character design pipe" to "Designing Nemesis entity...",
    // Narrative & Planning
    "simulation story / genesis" to "Constructing initial world state...",
    "chapter goals" to "Determining narrative objectives...",
    "Narrative Beat Planner" to "Planning story events...",
    "Creative Prose Engine" to "Generating story text...",
    // Target Detection
    "Target Detector Pipe" to "Analyzing target validity...",
    "Target Refinement Pipe" to "Refining target location...",
    "Target Fallback Pipe" to "Retrying target analysis...",
    // Validation-specific pipes
    "legality checker pipe" to "Checking legality and style...",
    "legality rectifier pipe" to "Rectifying risky wording...",
    "style reapply pipe" to "Reapplying approved narrative style...",
    "railroad detection pipe" to "Scanning for railroading behavior...",
    // Response refinement helpers
    "Response Detection Pipe" to "Inspecting defender tone...",
    "Response Refinement Pipe" to "Polishing defender prose...",
    // Maintenance helpers (matching pipeline names)
    "scan" to "Scanning for new NPCs...",
    "updates" to "Applying world updates...",
    "lorebook extraction pipe" to "Updating story lorebook..."
)

/**
 * Enforces single-territory targeting for military actions.
 * If multiple territories are detected, keeps only the first (most confident) target.
 *
 * @param targetType The detected target object from target detector
 * @return Modified ActionTargetTypeObj with at most one territory target
 */
private fun enforceSingleTerritoryTarget(targetType: ActionTargetTypeObj): ActionTargetTypeObj
{
    if(targetType.type == ActionTargetType.Territory && targetType.targets.size > 1)
    {
        val filtered = targetType.targets.first()
        Logger.info(LogCategory.SYSTEM, "Enforcing single-territory targeting: filtered ${targetType.targets.size} targets to '${filtered}' (dropped: ${targetType.targets.drop(1).joinToString(", ")})")
        return targetType.copy(targets = listOf(filtered))
    }
    return targetType
}

/**
 * Attaches a completion callback to a [Pipeline] to broadcast progress updates via RPC.
 *
 * This function intercepts pipe completion events, looks up the pipe name in [pipeProgressMap],
 * and if a match is found, broadcasts a progress bar update to the client.
 *
 * @param pipeline The [Pipeline] to monitor.
 * @param baseStepIndex The base index for the progress bar (corresponds to [ResolutionStep] stages).
 *                      Used to group updates under specific high-level phases.
 */
private fun attachProgressHooks(pipeline: Pipeline, baseStepIndex: Int)
{
    val existingCallback = pipeline.pipeCompletionCallback
    pipeline.pipeCompletionCallback = { pipe, output ->
        existingCallback?.invoke(pipe, output)
        
        val rawName = pipe.pipeName
        var progressMsg = pipeProgressMap[rawName]

        // If not found, check for Splitter prefixes (e.g. "narrative:Narrative Beat Planner")
        if(progressMsg == null && rawName.contains(":"))
        {
            val strippedName = rawName.substringAfterLast(":")
            progressMsg = pipeProgressMap[strippedName]
        }

        if(progressMsg != null)
        {
            UiSignalRpcHandlers.broadcastProgressBar(baseStepIndex, progressMsg)
        }
    }
}

private const val NARRATIVE_STREAM_BUFFER_LIMIT = 512
private const val NARRATIVE_STREAM_FLUSH_DELAY_MS = 90L

/**
 * Buffers Bedrock narrative chunks until either enough text has arrived or a short delay expires.
 *
 * This throttler reduces the frequency of `UiSignalRpcHandlers.broadcastNarrativeChunk` calls during
 * story generation and refinement so the browser client receives larger, smoother updates instead of
 * hitching on tiny packets. It is intentionally scoped to the active story phase so each queued agent
 * can flush once it completes while the final `isComplete` chunk still terminates the stream.
 *
 * @param flushDelayMs Maximum time to wait before flushing pending text (when the buffer is not already full).
 * @param bufferLimit Character count that triggers an immediate flush when reached. Larger chunks use this limit to
 *   control perceived typing speed while avoiding overwhelming the WebSocket.
 */
private class NarrativeChunkThrottler(
    private val flushDelayMs: Long = NARRATIVE_STREAM_FLUSH_DELAY_MS,
    private val bufferLimit: Int = NARRATIVE_STREAM_BUFFER_LIMIT
)
{
    private val buffer = StringBuilder()
    private val mutex = Mutex()
    private var flushJob: Job? = null

    /**
     * Appends newly emitted text and triggers either an immediate flush (when the buffer limit is hit)
     * or schedules a delayed flush if the buffer is still growing.
     *
     * @param chunk The partial narrative text produced by a Bedrock "writing pipe" callback.
     */
    suspend fun append(chunk: String)
    {
        if(chunk.isEmpty()) return

        var flushNow = false
        var scheduleTimer = false
        mutex.withLock {
            buffer.append(chunk)
            if(buffer.length >= bufferLimit)
            {
                flushJob?.cancel()
                flushJob = null
                flushNow = true
            }
            else if(flushJob?.isActive != true)
            {
                scheduleTimer = true
            }
        }

        if(flushNow)
        {
            flushPending()
        }
        else if(scheduleTimer)
        {
            scheduleDelayedFlush()
        }
    }

    private fun scheduleDelayedFlush()
    {
        if(flushDelayMs <= 0L)
        {
            AgentCoroutineScope.scope.launch { flushPending() }
            return
        }

        flushJob = AgentCoroutineScope.scope.launch {
            delay(flushDelayMs)
            flushPending()
        }
    }

    /**
     * Forces any buffered text to be sent immediately.
     *
     * The `isComplete` flag should be set to `true` only for the final chunk emitted after the agent run ends.
     * Calling this after every pipeline ensures the client receives the entire story before the subsequent
     * progress updates or scene transitions begin.
     *
     * @param isComplete Whether this flush corresponds to the final chunk of the narrative stage.
     */
    suspend fun flushPending(isComplete: Boolean = false)
    {
        val chunkToSend = mutex.withLock {
            flushJob?.cancel()
            flushJob = null
            if(buffer.isEmpty()) return@withLock ""
            val text = buffer.toString()
            buffer.setLength(0)
            text
        }

        if(chunkToSend.isEmpty()) return
        UiSignalRpcHandlers.broadcastNarrativeChunk(chunkToSend, isComplete)
    }
}

/**
 * Helper to wire a Bedrock pipe into a shared throttler so every agent can reuse the buffering logic.
 *
 * This extension keeps the streaming setup consistent and prevents multiple throttler instances from
 * competing for the same `UiSignalRpcHandlers.broadcastNarrativeChunk` execution path.
 */
private fun BedrockPipe.enableBufferedNarrativeStreaming(throttler: NarrativeChunkThrottler)
{
    enableStreaming()
    val callback: suspend (String) -> Unit = { chunk -> AgentCoroutineScope.scope.launch { throttler.append(chunk) } }
    setStreamingCallback(callback)
}

/**
 * Main server game loop function that orchestrates and executes a player's turn.
 * 
 * This function defines the "Game Loop" for a single player action. It proceeds in distinct phases:
 * 1. **Setup & Takeover**: Checks if the player is actually AI-controlled (e.g. AFK or bot) via [handleInitialSetupAndAiTakeover].
 * 2. **Intent & Cost**: Identifies what type of move this is ([identifyPlayType]) and charges the player resources ([deductPoints]).
 * 3. **Targeting**: Determines who is being targeted ([detectTargets]). If targeting is invalid, the action is marked for sabotage.
 * 4. **Counter-Play**: If other players are targeted, they get a chance to respond ([handleCounterPlay]).
 * 5. **Simulation**: The action is simulated to generate a narrative and geopolitical assessment ([executeSimulationAndAssessment]).
 * 6. **Mathematics**: The abstract narrative result is converted into concrete game data (success/fail, scores) via [GameMath.resolveAction].
 * 7. **Refinement**: The narrative is updated to match the mathematical reality (e.g. if the math says failure but story said success, we [refineNarrativeOutcome]).
 * 8. **Judgement**: Final consequences are calculated ([executeJudgementPhase]).
 * 9. **Commit**: History is saved and state is broadcast ([commitHistoryAndBroadcast]).
 * 10. **Maintenance**: World updates, NPC spawning, and cleanup ([handleTurnMaintenance]).
 *
 * ## Master Schedule of Agents
 * 1. [handleInitialSetupAndAiTakeover] -> [buildPlayerAgent]
 * 2. **Validation** -> [buildValidator], [buildRailroadAgent]
 * 3. [identifyPlayType] -> [buildPlayDetectionAgent]
 * 4. [detectTargets] -> [buildTargetDetectorAgent]
 * 5. [handleCounterPlay] -> [buildResponseRefinementAgent]
 * 6. [executeSimulationAndAssessment] -> [buildNeoWritingAgent], [buildAssessmentAgent]
 * 7. **Analysis** -> [buildPassFailAgent], [buildResourceUsageDetectorAgent]
 * 10. [refineNarrativeOutcome] -> [buildReverseAgent], [buildHardenAgent], [buildActOfGodAgent]
 * 11. [executeJudgementPhase] -> [buildJudge]
 * 13. [handleTurnMaintenance] -> [buildNewCharacterScanPipeline], [worldUpdatesPipeline], [buildNemesisCreationAgent]
 *
 * @param ctx The RPC context for network calls.
 * @param player The [Player] entity executing the turn.
 * @param turnAction The natural language string describing the player's action.
 */
suspend fun executePlayerTurn(ctx: org.ttt.autogenesis.network.RpcCallContext, player: Player, turnAction: String)
{
    ensurePlayerReachable(player)

    Logger.info(LogCategory.GENERAL, ">>> STARTING PLAYER TURN EXECUTION for ${player.name} <<<")
    Logger.info(LogCategory.GENERAL, "executePlayerTurn: Player data - commanderType=${player.commanderType}, trait=${player.trait}")
    Logger.debug(LogCategory.GENERAL, "executePlayerTurn: Player stats - militaryReadiness=${player.militaryReadiness}, legitimacy=${player.legitimacy}, stagnation=${player.stagnation}")
    Logger.debug(LogCategory.GENERAL, "executePlayerTurn: Player points - victory=${player.victoryPoints}, military=${player.militaryPoints}, diplomacy=${player.diplomacyPoints}, research=${player.researchPoints}")
    Logger.debug(LogCategory.GENERAL, "executePlayerTurn: Player assets - territories=${player.capturedTerritory.size}, resources=${player.resources.size}, nemesis=${player.capturedNemesis.size}")
    Logger.info(LogCategory.GENERAL, "executePlayerTurn: Turn action - ${turnAction.take(150)}${if(turnAction.length > 150) "..." else ""}")
    Logger.info(LogCategory.GENERAL, "executePlayerTurn: Context - connectionId='${ctx.connectionId}'")
    
    /** 1. **Initial Setup**: Checks for AI takeover via [buildPlayerAgent] if needed. */
    Logger.info(LogCategory.SYSTEM, "Phase 1: Initial Setup and AI Takeover check...")
    var effectiveTurnAction = handleInitialSetupAndAiTakeover(player, turnAction, ctx.connectionId)
    if(effectiveTurnAction == null) {
        Logger.warn(LogCategory.SYSTEM, "Initial setup failed or aborted. effectiveTurnAction is null.")
        return
    }
    Logger.info(LogCategory.SYSTEM, "Phase 1 complete. effectiveTurnAction length: ${effectiveTurnAction.length}")

    /** 2. **Validation**: Validates action legality using [buildValidator] and checks for railroading with [buildRailroadAgent]. */
    Logger.info(LogCategory.SYSTEM, "Phase 2: Validation...")
    var isCaptureAttempted = false
    if(BedrockConfig.skipValidationForAi && isAiControlled(player))
    {
        Logger.info(LogCategory.GENERAL, "Skipping validation for AI player ${player.name}")
        UiSignalRpcHandlers.broadcastProgressBar(1, "AI action processing...")
    }
    else
    {
    // Run Validator and Railroad Agent in parallel to maximize throughput.
    // The Validator checks for rule compliance (including NPC ownership) and cleans up the action text.
    // The Railroad Agent checks if the player is trying to break the "fourth wall" or hijack the narrative.
    val validationSplitter = Splitter().apply {
        enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG, mergeSplitterTraces = false))

        val validatorPipeline = buildValidator(player).apply {
            pipelineName = "validator"
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
            
            // Dedicated recursive start logging for the validator pipeline only
            fun hookValidatorPipeStart(pipe: Pipe) {
                val existingPreInit = pipe.preInitFunction
                pipe.setPreInitFunction { content ->
                    Logger.info(LogCategory.GENERAL, "[VALIDATOR_TRUTH] Pipe Started: '${pipe.pipeName}'")
                    existingPreInit?.invoke(content)
                }
                pipe.validatorPipe?.let { hookValidatorPipeStart(it) }
                pipe.transformationPipe?.let { hookValidatorPipeStart(it) }
                pipe.branchPipe?.let { hookValidatorPipeStart(it) }
                pipe.reasoningPipe?.let { hookValidatorPipeStart(it) }
            }
            getPipes().forEach { hookValidatorPipeStart(it) }

            // Dedicated completion logging for the validator pipeline only
            val existingCallback = pipeCompletionCallback
            pipeCompletionCallback = { pipe, output ->
                existingCallback?.invoke(pipe, output)
                Logger.info(LogCategory.GENERAL, "[VALIDATOR_TRUTH] Pipe Completed: '${pipe.pipeName}'")
            }
        }
        attachProgressHooks(validatorPipeline, 1)
        validatorPipeline.init(true)
        addPipeline("validator", validatorPipeline)
        addContent("validator", MultimodalContent(effectiveTurnAction))

        val railroadPipeline = buildRailroadAgent().apply {
            pipelineName = "railroad"
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
        }
        railroadPipeline.init(true)
        addPipeline("railroad", railroadPipeline)
        addContent("railroad", MultimodalContent(effectiveTurnAction))

        init()
    }

    val broadcastIds = getAllConnectedClientIds()
    validationSplitter.getAllChildPipelines().forEach { streamPipelineOutputToAgentWorkBuffer(broadcastIds, it)}

    Logger.info(LogCategory.GENERAL, "STARTING Validation Splitter Execution...")
    UiSignalRpcHandlers.broadcastProgressBar(1, "Validating legality & intent...")
    try {
        validationSplitter.executePipelines().awaitAll()
    } catch (e: Exception) {
        Logger.error(LogCategory.SYSTEM, "Validation Splitter CRASHED: ${e.message}")
        throw e
    }
    Logger.info(LogCategory.GENERAL, "COMPLETED Validation Splitter Execution.")

    // Save individual traces
    val traceDirValidationBase = "ValidationSplitter/${System.currentTimeMillis()}"
    saveSystemTrace(traceDirValidationBase, validationSplitter)

    validationSplitter.getAllChildPipelines().forEach { pipeline ->
        val safeName = pipeline.pipelineName.replace(" ", "_").replace(":", "_")
        saveSystemTrace("$traceDirValidationBase/$safeName", pipeline)
    }

    // Process Railroad Result
    val railroadResult = extractJson<TrueFalse>(validationSplitter.results.contents["railroad"]?.text ?: "")
    if(railroadResult?.isTrue == true)
    {
        val points = Random.nextInt(10, 101) // 10 to 100 inclusive
        Logger.warn(LogCategory.GENERAL, "Railroading detected! Adding $points Act of God points.")
        WorldManager.worldMutex.withLock {
            WorldManager.world.actOfGodPoints += points
        }
        Logger.debug(LogCategory.SYSTEM, "ActOfGodPoints now ${WorldManager.world.actOfGodPoints} after railroad penalty")
    }

    // Process Validator Result (Update effective action if sanitized)
    val captureWindow = ContextBank.getContextFromBank("capture_attempted")
    if (captureWindow?.contextElements?.firstOrNull()?.toBoolean() == true) {
        isCaptureAttempted = true
        Logger.info(LogCategory.GENERAL, "Capture intent detected by validator.")
    }

    val validatedAction = validationSplitter.results.contents["validator"]?.text?.replace("the player", player.name, ignoreCase = true)
    if(!validatedAction.isNullOrBlank())
    {
        // If the validator changed the action (or just to be safe and ensure UI sync), broadcast it
        if(validatedAction != effectiveTurnAction)
        {
            Logger.info(LogCategory.GENERAL, "Action sanitized/updated by Validator. Broadcasting update to UI.")
            UiSignalRpcHandlers.broadcastIntentUpdate(validatedAction)
            UiSignalRpcHandlers.broadcastProgressBar(1, "Updated intent verified...")
            
            // Artificial delay to allow user to see the change
            delay(2000)
        }
        Logger.debug(LogCategory.SYSTEM, "Effective turn action after validation: \"$validatedAction\"")
        effectiveTurnAction = validatedAction
    }
    }
    Logger.info(LogCategory.SYSTEM, "Phase 2 complete.")

    // Broadcast to all clients to force them into the Turn Resolution Widget now that we kick off
    org.ttt.autogenesis.server.UiSignalRpcHandlers.broadcastForceShowTurnResolution()

    /** 3. **Play Type**: Identifies the type of play using [buildPlayDetectionAgent]. */
    Logger.info(LogCategory.SYSTEM, "Phase 3: Play Type identification...")
    val playType = identifyPlayType(player, effectiveTurnAction, ctx.connectionId)
    Logger.info(LogCategory.GENERAL, "Play Type Identified: ${playType.type}")

    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.PLAYER_ACTION, effectiveTurnAction)
    var alwaysFailPlayerAction = !playType.doesPlayerHaveEnoughPoints
    if(alwaysFailPlayerAction)
    {
        Logger.warn(LogCategory.GENERAL, "Player ${player.name} does not have enough points for ${playType.type}. Sabotaging play.")
    }

    else
    {
        deductPoints(player, playType.type)
    }
    Logger.info(LogCategory.SYSTEM, "Phase 3 complete.")

    /** 4. **Targeting**: Detects targets using [buildTargetDetectorAgent]. */
    Logger.info(LogCategory.SYSTEM, "Phase 4: Target Detection...")
    val targetDetectionResult = detectTargets(player, effectiveTurnAction, ctx.connectionId)
    var targetType = targetDetectionResult.first
    val sabotageOccurred = targetDetectionResult.second
    targetType = resolveTerritoryTargets(targetType, player.name, WorldManager.world.mapTiles)
    Logger.info(LogCategory.GENERAL, "Target Detection Complete. Sabotage: $sabotageOccurred (Type: ${targetType.type})")
    Logger.info(LogCategory.GENERAL, "Target Detection Details: intent=${targetType.actionIntent}, targets=${targetType.targets.size}, intentMismatch=${targetType.intentMismatch}")
    if(sabotageOccurred) alwaysFailPlayerAction = true
    Logger.info(LogCategory.SYSTEM, "Phase 4 complete.")

    // --- SOURCE LOCATION DETECTION ---
    val sourceLocationResult = detectSourceLocation(
        player = player,
        actionText = effectiveTurnAction,
        targetTerritories = targetType.targets,
        world = WorldManager.world
    )

    // Validate paths if source locations exist
    val pathValidation = if (sourceLocationResult.sourceTerritories.isNotEmpty()) {
        val isMilitaryAction = targetType.actionIntent == ActionIntent.Hostile && playType.type == PlayType.Military
        validateSourceToTargetPaths(
            sourceTerritories = sourceLocationResult.sourceTerritories,
            targetTerritories = targetType.targets,
            player = player,
            world = WorldManager.world,
            isMilitaryAction = isMilitaryAction
        )
    } else null

    // Add resolved source territories to targetType
    targetType = targetType.copy(actingFromTerritories = sourceLocationResult.sourceTerritories)

    Logger.info(LogCategory.GENERAL, "Source Location: specified=${sourceLocationResult.wasSpecified}, " +
        "optimized=${sourceLocationResult.wasOptimized}, reason=${sourceLocationResult.optimizationReason}")
    Logger.info(LogCategory.GENERAL, "Source Location: actingFrom=${sourceLocationResult.sourceTerritories.joinToString()}")
    if (pathValidation != null && !pathValidation.allPathsValid) {
        Logger.warn(LogCategory.GENERAL, "Source Location Warning: Paths cross hostile territories: ${pathValidation.hostileTerritoriesCrossed}")
    }

    // --- INTERCEPTED PLAYERS (Path CounterPLAY) ---
    // If attack path crosses another player's territory, that player becomes a target for counterplay
    if (pathValidation != null && pathValidation.playersOnPath.isNotEmpty()) {
        val interceptedPlayers = pathValidation.playersOnPath.filter { playerName ->
            // Exclude the original target player if already targeted
            !targetType.targets.any { it.equals(playerName, ignoreCase = true) }
        }
        if (interceptedPlayers.isNotEmpty()) {
            Logger.info(LogCategory.GENERAL, "INTERCEPTED: Attack path crosses through players: $interceptedPlayers. Adding to counterplay targets.")
            val existingTargets = targetType.targets.toMutableList()
            interceptedPlayers.forEach { playerName ->
                if (!existingTargets.contains(playerName)) {
                    existingTargets.add(playerName)
                }
            }
            targetType = targetType.copy(
                targets = existingTargets,
                type = if (targetType.type == ActionTargetType.Territory) ActionTargetType.Player else targetType.type
            )
            Logger.info(LogCategory.GENERAL, "Counterplay targets expanded to include intercepted players. New targets: ${targetType.targets}")
        }
    }

    // --- LONG RANGE WARNING ---
    if (isCaptureAttempted && targetType.type == ActionTargetType.Territory) {
        val targetTerritoryName = targetType.targets.firstOrNull()
        if (targetTerritoryName != null) {
            val validation = WorldManager.validateTerritoryAdjacency(player.name, targetTerritoryName)
            if (validation.isValid && validation.distance > 1) {
                val penalty = validation.distance.toLong() * 5
                val warning = "WARNING: Long-Range Capture Detected. A penalty of -$penalty points will be applied to your roll."
                Logger.info(LogCategory.GENERAL, "Broadcasting long-range capture warning: $warning")
                UiSignalRpcHandlers.sendCommandClassification(
                    ctx.connectionId,
                    org.ttt.autogenesis.network.CommandClassificationData(
                        connectionId = ctx.connectionId,
                        prompt = effectiveTurnAction,
                        isGameplay = true,
                        hint = warning
                    )
                )
            }
        }
    }

    // --- INTENT BROADCAST START ---
    val intentHistory = GameHistory(
        turnPlayer = player.name,
        turnAction = effectiveTurnAction,
        turnResult = "(Planning...)"
    )

    val turnId = intentHistory.id
    ActionHistoryRpcHandlers.broadcastTurnComplete(intentHistory)
    // --- INTENT BROADCAST END ---

    // --- SUMMIT PLAY TYPE ROUTING ---
    // Summit plays are cooperative diplomatic events that target all OTHER active players
    // and don't follow the standard counter-play flow
    if(playType.type == PlayType.Summit)
    {
        Logger.info(LogCategory.GENERAL, "Summit play detected. Routing to Summit-specific orchestration.")

        // Validate summit points (1 point required, already checked in playType.doesPlayerHaveEnoughPoints)
        if(!playType.doesPlayerHaveEnoughPoints)
        {
            Logger.warn(LogCategory.GENERAL, "Player ${player.name} does not have enough summit points. Sabotaging play.")
            alwaysFailPlayerAction = true
        }

        // Summit targets all OTHER active players (not territory targets)
        val summitTargets = WorldManager.world.activePlayers.filter { otherPlayer ->
            !otherPlayer.name.equals(player.name, ignoreCase = true)
        }

        Logger.info(LogCategory.GENERAL, "Summit targets: ${summitTargets.map { it.name }}")

        // Extract reason from player action - look for patterns like "because/since/for/reason:"
        val summitReason = extractSummitReason(effectiveTurnAction)

        // Call Summit orchestration - this handles the full summit flow including:
        // - Notifying target players
        // - Collecting responses
        // - Invoking Judge
        // - Applying stat buffs
        val summitOutcome = runSummitOrchestration(
            activePlayer = player,
            description = effectiveTurnAction,
            targets = summitTargets,
            reason = summitReason
        )

        Logger.info(LogCategory.GENERAL, "Summit orchestration complete. Narrative length: ${summitOutcome.combinedNarrative.length}")

        // Broadcast summit result to all connected clients
        UiSignalRpcHandlers.broadcastSummitResult(
            success = true,
            narrativeSummary = summitOutcome.combinedNarrative.take(500),
            statChanges = summitOutcome.statChangesMap
        )

        // Summit is complete - don't process counter-play responses for Summit
        // Build a minimal result to satisfy the flow
        val summitResult = SummitPlayResult(
            combinedNarrative = summitOutcome.combinedNarrative,
            wasSuccessful = true
        )

        // Broadcast summit completion
        UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.STORY)
        val summitHistory = intentHistory.copy(
            turnResult = "Summit completed successfully"
        )
        ActionHistoryRpcHandlers.broadcastTurnComplete(summitHistory)

        return
    }

    /** 5. **Counter-Play**: Manages opponent responses via [handleCounterPlay] which uses [buildResponseRefinementAgent]. */
    Logger.info(LogCategory.SYSTEM, "Phase 5: Counter-Play Processing...")
    val counterPlayResult = handleCounterPlay(player, effectiveTurnAction, targetType, playType, intentHistory, ctx.connectionId)
    val aggregatedResponseProse = counterPlayResult.aggregatedResponseProse
    val counterResponsesList = counterPlayResult.counterResponsesList
    Logger.info(LogCategory.GENERAL, "[COUNTER_RESPONSE] Received ${counterResponsesList.size} counter-responses from handleCounterPlay")
    Logger.info(LogCategory.GENERAL, "Counter-Play Processing Complete.")

    // Broadcast counter-responses to UI immediately for better player feedback
    if(counterResponsesList.isNotEmpty())
    {
        Logger.info(LogCategory.GENERAL, "[COUNTER_RESPONSE] Broadcasting counter-responses to UI")
        
        // Update history with counter-responses
        val counterPlayUpdate = intentHistory.copy(
            turnResult = "(Defenders responded)",
            counterResponses = counterResponsesList.toMutableList()
        )
        ActionHistoryRpcHandlers.broadcastTurnComplete(counterPlayUpdate)
        
        // Show counter-play page in UI
        UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.COUNTER_PLAY)
        
        // Give UI time to display counter-responses before moving to story
        delay(2000)
    }
    Logger.info(LogCategory.SYSTEM, "Phase 5 complete.")

    // 5. Base Score Calc (Pre-simulation)
    val baseScore = GameMath.calculateBaseScore(player, playType.type, targetType)
    Logger.info(LogCategory.GENERAL, "Base Score Calculated: $baseScore")

    /** 6. **Simulation**: Generates narrative via [buildNeoWritingAgent] and assesses geopolitics via [buildAssessmentAgent]. */
    Logger.info(LogCategory.SYSTEM, "Phase 6: Simulation & Assessment...")
    val (narrativeText, assessmentResult) = executeSimulationAndAssessment(ctx, player, effectiveTurnAction, aggregatedResponseProse, counterResponsesList, targetType)
    Logger.info(LogCategory.SYSTEM, "Phase 6 complete. Narrative length: ${narrativeText.length}")
    
    /** 7. **Outcome Analysis**: Checks success via [buildPassFailAgent] and resource usage via [buildResourceUsageDetectorAgent]. */
    Logger.info(LogCategory.SYSTEM, "Phase 7: Outcome Analysis & Resource Usage Detection...")
    UiSignalRpcHandlers.broadcastNarrativeChunk("", isComplete = true)
    UiSignalRpcHandlers.broadcastProgressBar(3, "Analyzing outcome & usage...")

    val analysisSplitter = Splitter().apply {
        enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG, mergeSplitterTraces = false))

        val analysisPipeline = buildPassFailAgent(player).apply {
            pipelineName = "analysis"
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
        }
        attachProgressHooks(analysisPipeline, 3)
        analysisPipeline.init(true)
        addPipeline("analysis", analysisPipeline)
        addContent("analysis", MultimodalContent(narrativeText))

        val resourcesPipeline = buildResourceUsageDetectorAgent(player, effectiveTurnAction).apply {
            pipelineName = "resources"
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
        }
        attachProgressHooks(resourcesPipeline, 3)
        resourcesPipeline.init(true)
        addPipeline("resources", resourcesPipeline)
        addContent("resources", MultimodalContent(effectiveTurnAction))

        init()

        val broadcastIds = getAllConnectedClientIds()
        getAllChildPipelines().forEach { streamPipelineOutputToAgentWorkBuffer(broadcastIds, it) }
    }

    Logger.info(LogCategory.GENERAL, "STARTING Analysis Splitter Execution...")
    try {
        analysisSplitter.executePipelines().awaitAll()
    } catch (e: Exception) {
        Logger.error(LogCategory.SYSTEM, "Analysis Splitter CRASHED: ${e.message}")
        throw e
    }
    Logger.info(LogCategory.GENERAL, "COMPLETED Analysis Splitter Execution.")

    // Save individual traces
    val traceDirAnalysisBase = "AnalysisSplitter/${System.currentTimeMillis()}"
    saveSystemTrace(traceDirAnalysisBase, analysisSplitter)

    analysisSplitter.getAllChildPipelines().forEach { pipeline ->
        val safeName = pipeline.pipelineName.replace(" ", "_").replace(":", "_")
        saveSystemTrace("$traceDirAnalysisBase/$safeName", pipeline)
    }

    val analysisOutput = analysisSplitter.results.contents["analysis"]
    val isSimulatedSuccess = extractJson<`Victory?`>(analysisOutput?.text ?: "")?.isVictory ?: false

    val resourceOutput = analysisSplitter.results.contents["resources"]
    val usedAssetsList = extractJson<UsedAssets>(resourceOutput?.text ?: "")?.usedAssets ?: emptyList()
    Logger.info(LogCategory.SYSTEM, "Phase 7 complete. isSimulatedSuccess: $isSimulatedSuccess, assets used: ${usedAssetsList.size}")

    // 8. Mathematical Resolution (The "Truth" of the turn)
    Logger.info(LogCategory.SYSTEM, "Phase 8: Mathematical Resolution...")
    val mathOutcome = GameMath.resolveAction(
        player = player,
        playType = playType.type,
        targetType = targetType,
        assessment = assessmentResult,
        isSimulatedSuccess = isSimulatedSuccess,
        usedAssets = usedAssetsList
    )

    // Force failure if sabotage active (points or targeting issues)
    val finalSuccess = if(alwaysFailPlayerAction) false else mathOutcome.finalSuccess
    Logger.info(
        LogCategory.GENERAL,
        "Math Outcome -> Score: ${mathOutcome.totalScore}, StatVictory: ${mathOutcome.statVictory}, Narrative: ${mathOutcome.narrativeVictory}, OverrideChance: ${mathOutcome.narrativeOverrideChance}, Flip: ${mathOutcome.didFlip}, Success: $finalSuccess"
    )
    Logger.info(LogCategory.SYSTEM, "Phase 8 complete.")

    // Store math outcome in ContextBank so enforceMandatoryTerritoryCapture can access it
    val mathOutcomeWindow = ContextWindow().apply {
        contextElements.add(serialize(mathOutcome))
    }
    ContextBank.emplace("math_outcome", mathOutcomeWindow)
    Logger.debug(LogCategory.SYSTEM, "[MATH_OUTCOME_STORED] statVictory=${mathOutcome.statVictory}, finalSuccess=${mathOutcome.finalSuccess}, totalScore=${mathOutcome.totalScore}")

    // 9. Stat Updates
    Logger.info(LogCategory.SYSTEM, "Phase 9: Stat Updates...")
    updateStats(player, playType.type, finalSuccess)
    Logger.info(LogCategory.SYSTEM, "Phase 9 complete.")

    /** 10. **Refinement**: Aligns story with math using [buildReverseAgent], [buildHardenAgent], or [buildActOfGodAgent]. */
    Logger.info(LogCategory.SYSTEM, "Phase 10: Narrative Refinement...")
    val finalNarrative = refineNarrativeOutcome(narrativeText, isSimulatedSuccess, finalSuccess, assessmentResult, mathOutcome, ctx.connectionId)
    Logger.info(LogCategory.GENERAL, "Narrative Refinement Complete.")
    stageCurrentTurnHistoryEntry(player, effectiveTurnAction, finalNarrative, usedAssetsList, counterResponsesList, targetType, turnId)
    
    // Detect and log reversal stories for debugging
    val lowerNarrative = finalNarrative.lowercase()
    val reversalIndicators = listOf("failed to", "did not", "never happened", "forces were repelled", "defeat was declared")
    val reversalCount = reversalIndicators.count { lowerNarrative.contains(it) }

    if(reversalCount >= 2)
    {
        Logger.warn(LogCategory.SYSTEM, "[REVERSAL STORY DETECTED] Narrative contains $reversalCount reversal indicators. Agents should interpret as non-events.")
        Logger.debug(LogCategory.SYSTEM, "[REVERSAL STORY] finalSuccess=$finalSuccess, isSimulatedSuccess=$isSimulatedSuccess")
    }
    Logger.info(LogCategory.SYSTEM, "Phase 10 complete.")
    
    /** 11. **Judgement**: Calculates final consequences via [buildJudge]. */
    Logger.info(LogCategory.SYSTEM, "Phase 11: Judgement Phase...")
    // Mid-turn music reroll: refresh the random layer tracks so the
    // music stays fresh while the judge agent runs. No-op for
    // scenario-bound (initial / nemesis / terminal) picks — see
    // [MusicSelector.reselectRandomLayers]. Wired here (rather than at
    // the start of the turn) because the user wants the reroll to fire
    // only when the turn is actually about to enter the judgement
    // phase; for short turns the judge runs almost immediately and
    // the start-of-turn music is already playing.
    TurnHarness.selectAndBroadcastMusicReroll()
    val (judgeResults, judgePipeline) = executeJudgementPhase(player, targetType, finalNarrative, finalSuccess, playType, ctx.connectionId)
    Logger.info(LogCategory.GENERAL, "Judgement Phase Complete.")
    Logger.info(LogCategory.SYSTEM, "Phase 11 complete.")
    
    // 12. Commit & Broadcast
    Logger.info(LogCategory.SYSTEM, "Phase 12: Commit & Broadcast...")
    Logger.info(LogCategory.GENERAL, "[COUNTER_RESPONSE] Passing ${counterResponsesList.size} counter-responses to commitHistoryAndBroadcast")
    commitHistoryAndBroadcast(player, effectiveTurnAction, finalNarrative, finalSuccess, judgeResults, judgePipeline, usedAssetsList, counterResponsesList, turnId)
    Logger.info(LogCategory.SYSTEM, "Phase 12 complete.")
    
    /** 13. **Maintenance**: Updates world via [worldUpdatesPipeline], scans for new NPCs via [buildNewCharacterScanPipeline], and checks for Nemesis via [buildNemesisCreationAgent]. */
    Logger.info(LogCategory.SYSTEM, "Phase 13: End of Turn Maintenance...")
    handleTurnMaintenance(player, finalNarrative, usedAssetsList, judgeResults, ctx.connectionId)
    Logger.info(LogCategory.GENERAL, ">>> PLAYER TURN EXECUTION FINISHED for ${player.name} <<<")
    Logger.info(LogCategory.SYSTEM, "Phase 13 complete.")
}

private suspend fun ensurePlayerReachable(player: Player): Boolean
{
    val reachable = WorldManager.isReachable(player)
    
    if(reachable) {
        return true
    }

    val stats = WorldManager.findPlayerFromStats(player.name)
    val wasHuman = stats?.isConnected == true || stats?.isControlledByNpc == false
    
    if(wasHuman)
    {
        stats?.let {
            it.isConnected = false
            it.isControlledByNpc = true
            it.playerID.takeIf { id -> id.isNotBlank() }?.let { id ->
                UiSignalRpcHandlers.connectionManager?.broadcastConnectionEvent(id, ConnectionStatus.DISCONNECTED)
            }
        }
        Logger.warn(LogCategory.NETWORK, "Player ${player.name} unreachable; defaulting to AI takeover.")
    }
    return false
}

/**
 * Handles the initial setup for the turn: stops the timer and checks if the player should be AI-controlled.
 *
 * If the player is marked as AI-controlled (e.g. disconnected or AFK), an [buildPlayerAgent] is built and executed
 * to generate the action string on their behalf.
 *
 * @param player The player to check.
 * @param turnAction The action string provided (may be empty if AFK).
 * @return The effective action string to use (original or AI-generated), or null if the turn should abort.
 */
private suspend fun handleInitialSetupAndAiTakeover(player: Player, turnAction: String, connectionId: String): String?
{
    Logger.info(LogCategory.GENERAL, "Executing play for ${player.name}")
    gameState.WorldManager.stopTurnTimer()

    var effectiveTurnAction = turnAction
    val stats = WorldManager.findPlayerFromStats(player.name)
    if(stats != null && stats.isControlledByNpc)
    {
        Logger.info(LogCategory.GENERAL, "Player ${player.name} is AI-controlled. Generating AI action...")
        
        // Provide immediate UI feedback that the AI is thinking
        UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.PLAYER_ACTION, "${player.name} is formulating a strategy...")
        UiSignalRpcHandlers.broadcastProgressBar(0, "AI is thinking...")

        val aiPlayerAgent = buildPlayerAgent(player).apply {
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
            init(true)
        }
        val broadcastIds = getAllConnectedClientIds()
        
        // Wire the work stream buffer so the player sees the AI's internal reasoning
        streamPipelineOutputToAgentWorkBuffer(broadcastIds, aiPlayerAgent)
        
        var usedFallbackPrompt = false
        val aiResult = aiPlayerAgent.execute(MultimodalContent(""))
        if(aiResult.text.isNotBlank())
        {
            effectiveTurnAction = aiResult.text
        }
        else
        {
            usedFallbackPrompt = true
            effectiveTurnAction = GenericPrompts.PLAYER_GENERIC_FALLBACK
            Logger.warn(LogCategory.LLM, "handleInitialSetupAndAiTakeover: ${player.name}'s agent returned blank output; using generic fallback prompt.")
        }
        saveSystemTrace("AI_Player_Takeover", aiPlayerAgent)
        if(usedFallbackPrompt)
        {
            Logger.warn(LogCategory.LLM, "handleInitialSetupAndAiTakeover: Applying generic fallback prompt for ${player.name}: '${effectiveTurnAction.take(120)}'")
        }
    }

    if(effectiveTurnAction.isBlank())
    {
        Logger.error(LogCategory.GENERAL, "Turn action is blank for ${player.name}. Aborting turn.")
        return null
    }

    // Replace generic references to "the player" with the actual commander name
    // to avoid "The Player" appearing in the final narrative or history panels.
    effectiveTurnAction = effectiveTurnAction.replace("the player", player.name, ignoreCase = true)

    Logger.debug(LogCategory.GENERAL, "Final effective turn action for ${player.name}: \"$effectiveTurnAction\"")
    return effectiveTurnAction
}

private fun isAiControlled(player: Player): Boolean =
    WorldManager.findPlayerFromStats(player.name)?.isControlledByNpc ?: false

/**
 * Extracts the summit reason from a player action string.
 * Looks for patterns like "because/since/for/reason:" to identify the stated motivation.
 * Falls back to a default reason if none is found.
 *
 * @param actionText The player's action text
 * @return The extracted reason or a default "General summit discussion"
 */
private fun extractSummitReason(actionText: String): String
{
    val lowercaseAction = actionText.lowercase()

    // Look for reason indicators
    val reasonPatterns = listOf(
        "because ", "because: ", "because:",
        "since ", "since: ", "since:",
        "for ", "for: ", "for:",
        "reason: ", "reason:", "reason is: ", "reason is:"
    )

    for (pattern in reasonPatterns)
    {
        val index = lowercaseAction.indexOf(pattern)
        if (index != -1)
        {
            val reasonStart = index + pattern.length
            // Find the end of the sentence (period, newline, or end of string)
            val reasonEnd = actionText.indexOfAny(charArrayOf('.', '\n', '\r', '|'), reasonStart).takeIf { it > reasonStart } ?: actionText.length
            val reason = actionText.substring(reasonStart, reasonEnd).trim()
            if (reason.isNotBlank())
            {
                Logger.debug(LogCategory.GENERAL, "Extracted summit reason: $reason")
                return reason
            }
        }
    }

    Logger.debug(LogCategory.GENERAL, "No explicit reason found in summit action. Using default.")
    return "General summit discussion"
}

/**
 * Uses an AI agent to identify the *type* of play the player is attempting (Military, Diplomatic, Research, etc.)
 * based on their natural language input.
 *
 * This function handles:
 * 1. AI execution of the categorization agent via [buildPlayDetectionAgent].
 * 2. Real-time progress broadcasting.
 * 3. Automatic retry/swap logic via [fetchRetryLogs] and [swapPipelineModels] if the initial model fails.
 *
 * @param player The [Player] making the move.
 * @param effectiveTurnAction The text describing the move.
 * @return A [PlayTypeObj] indicating the detected type and whether the player has enough points.
 * @throws Exception If the agent fails to produce a valid classification after retries.
 */
private suspend fun identifyPlayType(player: Player, effectiveTurnAction: String, connectionId: String): PlayTypeObj
{
    val broadcastIds = getAllConnectedClientIds()
    val playIdentifier = buildPlayDetectionAgent(player).apply {
        enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        enablePipeTimeout(
            applyRecursively = true,
            duration = 180000,
            autoRetry = true,
            retryLimit = 5
        )
        init(true)
        streamPipelineOutputToAgentWorkBuffer(broadcastIds, this)
        attachProgressHooks(this, 1)
    }

    UiSignalRpcHandlers.broadcastProgressBar(1, "Identifying play type...")
    var startingPlay = playIdentifier.execute(MultimodalContent(effectiveTurnAction))
    saveSystemTrace("WritingAgents", playIdentifier)

    val errorData = fetchRetryLogs()
    if(!errorData.isEmpty())
    {
        swapPipelineModels(playIdentifier)
        startingPlay = playIdentifier.execute(MultimodalContent(effectiveTurnAction))
        saveSystemTrace("WritingAgents_Retry", playIdentifier)
        val logRetry = fetchRetryLogs()
        if(!logRetry.isEmpty()) throw Exception("${logRetry.failureReason}")
    }
    return extractJson<PlayTypeObj>(startingPlay.text) ?: PlayTypeObj()
}


/**
 * Deducts the resource cost for a specific play type from the player's [Player] stats.
 *
 * Costs are currently fixed:
 * - Military/Diplomatic/Research: 50 points
 * - Summit: 1 point
 *
 * This operation is thread-safe, utilizing [WorldManager.worldMutex].
 *
 * @param player The [Player] to charge.
 * @param type The [PlayType] of the action being executed.
 */
private suspend fun deductPoints(player: Player, type: PlayType)
{
    when(type)
    {
        PlayType.Military -> {
            Logger.info(LogCategory.GENERAL, "Subtracting 50 military points from ${player.name}")
            WorldManager.worldMutex.withLock { player.militaryPoints -= 50 }
        }
        PlayType.Diplomatic -> {
            Logger.info(LogCategory.GENERAL, "Subtracting 50 diplomatic points from ${player.name}")
            WorldManager.worldMutex.withLock { player.diplomacyPoints -= 50 }
        }
        PlayType.Research -> {
            Logger.info(LogCategory.GENERAL, "Subtracting 50 research points from ${player.name}")
            WorldManager.worldMutex.withLock { player.researchPoints -= 50 }
        }
        PlayType.Summit -> {
            Logger.info(LogCategory.GENERAL, "Subtracting 1 summit point from ${player.name}")
            WorldManager.worldMutex.withLock { player.summitPoints -= 1 }
        }
    }
    
    // Verify point deduction persisted
    val verifyPlayer = WorldManager.world.activePlayers.find { it.name == player.name }
    if(verifyPlayer != null)
    {
        val pointsMatch = when(type)
        {
            PlayType.Military -> verifyPlayer.militaryPoints == player.militaryPoints
            PlayType.Diplomatic -> verifyPlayer.diplomacyPoints == player.diplomacyPoints
            PlayType.Research -> verifyPlayer.researchPoints == player.researchPoints
            PlayType.Summit -> verifyPlayer.summitPoints == player.summitPoints
        }
        
        if(!pointsMatch)
        {
            Logger.error(LogCategory.GENERAL, "Point deduction mismatch! player.hashCode=${player.hashCode()}, activePlayers.hashCode=${verifyPlayer.hashCode()}")
        }
        else
        {
            Logger.info(LogCategory.GENERAL, "Point deduction verified for ${player.name}")
        }
    }
}


/**
 * Uses an AI agent to identify the *target(s)* of the player's action (Player, NPC, Territory, etc.).
 *
 * Similar to [identifyPlayType], this function includes:
 * - AI execution with [buildTargetDetectorAgent].
 * - Real-time progress feedback.
 * - Automatic retry logic for robustness.
 * - "Sabotage" detection: if the AI determines the action is self-sabotaging or invalid, it sets a flag.
 *
 * @param player The [Player] initiating the action.
 * @param effectiveTurnAction The text describing the action.
 * @return A [Pair] containing the [ActionTargetTypeObj] (who is targeted) and a Boolean flag for sabotage/forced failure.
 * @throws Exception If target detection fails repeatedly.
 */
private suspend fun detectTargets(player: Player, effectiveTurnAction: String, connectionId: String): Pair<ActionTargetTypeObj, Boolean>
{
    val targetDetector = buildTargetDetectorAgent(player).apply {
        enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        enablePipeTimeout(
            applyRecursively = true,
            duration = 180000,
            autoRetry = true,
            retryLimit = 5
        )
        init(true)
        streamPipelineOutputToAgentWorkBuffer(connectionId, this)
        attachProgressHooks(this, 2)
    }
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.INTENT)
    UiSignalRpcHandlers.broadcastProgressBar(2, "Detecting action target...")
    var targetOutput = targetDetector.execute(MultimodalContent(effectiveTurnAction))
    saveSystemTrace("TargetDetectors", targetDetector)

    val targetErrorData = fetchRetryLogs()
    if(!targetErrorData.isEmpty())
    {
        swapPipelineModels(targetDetector)
        targetOutput = targetDetector.execute(MultimodalContent(effectiveTurnAction))
        saveSystemTrace("TargetDetectors_Retry", targetDetector)
        val logRetry = fetchRetryLogs()
        if(!logRetry.isEmpty()) throw Exception("${logRetry.failureReason}")
    }
    val targetType = enforceSingleTerritoryTarget(extractJson<ActionTargetTypeObj>(targetOutput.text) ?: ActionTargetTypeObj())
    val sabotage = targetDetector.miniBank.contextMap["force_fail_action"] != null
    Logger.debug(LogCategory.SYSTEM, "Target detector flagged sabotage=$sabotage for ${targetType.type}")
    return Pair(targetType, sabotage)
}


/**
 * Manages the "Counter-Play" phase where targeted players can respond to an aggressive action.
 *
 * If a player targets another active player (and that target has sufficient resources), this function:
 * 1. Broadcasts a prompt to the target(s).
 * 2. Waits asynchronously for their input via [GameResponseManager] (with a 30s timeout).
 * 3. Uses an AI agent ([buildResponseRefinementAgent]) to refine/format their response prose.
 * 4. Aggregates all responses into a single string to be fed into the narrative simulation.
 *
 * This allows for dynamic, multi-player narrative interactions within a turn.
 *
 * @param player The aggressor [Player].
 * @param effectiveTurnAction The aggressor's action text.
 * @param targetType The identifed targets.
 * @param playType The type of action (determines resource check for counter-play).
 * @return A string containing the aggregated, refined responses from all defending players.
 */
/**
 * Detects the intent of a counter-response (Military/Hostile or Non-Military/Friendly).
 *
 * @param responseText The counter-response text to classify
 * @return ActionIntent.Hostile for military responses, ActionIntent.Friendly for non-military
 */
internal suspend fun detectResponseIntent(responseText: String, connectionId: String? = null): ActionIntent
{
    return try {

        //AI build only a pipe for god knows why.
        val detector = buildCounterResponseIntentDetector(responseText).apply {
        }

        //So my hand was forced to do this stupid hack to get us around it.
        val detectorPipeline = Pipeline().apply {
            add(detector)
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
            init(true)
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        }

        streamPipelineOutputToAgentWorkBuffer(connectionId, detectorPipeline)

        val content = MultimodalContent()
        val result = detectorPipeline.execute(content)
        saveSystemTrace("CounterResponseIntent", detectorPipeline)
        val intentObj = extractJson<CounterResponseIntent>(result.text)
        
        if(intentObj?.intent == "Military")
        {
            Logger.debug(LogCategory.LLM, "Counter-response classified as Military (Hostile)")
            ActionIntent.Hostile
        }
        else
        {
            Logger.debug(LogCategory.LLM, "Counter-response classified as Non-Military (Friendly)")
            ActionIntent.Friendly
        }
    } catch(e: Exception)
    {
        Logger.warn(LogCategory.LLM, "Failed to detect response intent: ${e.message}, defaulting to Friendly")
        ActionIntent.Friendly
    }
}

/**
 * Tracks a counter-play response with its source and observed intent.
 *
 * @param firstPersonResponse The original first-person response for UI display.
 * @param thirdPersonResponse The refined third-person response for narrative integration.
 * @param responseIntent The detected action intent (Hostile/Friendly) of the response, used to spot mismatches.
 * @param source The origin of the response (HUMAN, AI_UNREACHABLE, AI_TIMEOUT) used for stats and logging.
 * @param respondingTo The player this response is reacting to.
 * @param respondingToAction The specific action text this response is reacting to.
 */
private data class ResponseData(
    val player: Player, 
    val firstPersonResponse: String,
    val thirdPersonResponse: String,
    val source: String,
    val responseIntent: ActionIntent = ActionIntent.Friendly,
    val respondingTo: Player,
    val respondingToAction: String
)

/**
 * Result of counter-play processing.
 * @param aggregatedResponseProse Combined refined responses for narrative integration
 * @param counterResponsesList Formatted list of "PlayerName: response" for UI display
 */
data class CounterPlayResult(
    val aggregatedResponseProse: String,
    val counterResponsesList: List<String>
)

/**
 * Result of Summit play processing.
 * Summit plays are cooperative diplomatic events that don't produce counter-play responses.
 * @param combinedNarrative The full narrative from summit orchestration including all responses
 * @param wasSuccessful Whether the summit completed successfully
 */
data class SummitPlayResult(
    val combinedNarrative: String,
    val wasSuccessful: Boolean
)

/**
 * Data class to track cascade state for counterplay chains.
 */
private data class CascadeState(
    val attacker: Player,
    val attackerAction: String,
    val targets: List<Player>,
    val cascadeDepth: Int
)

/**
 * Manages the "Counter-Play" phase where targeted players can respond to an aggressive action.
 *
 * If a player targets another active player (and that target has sufficient resources), this function:
 * 1. Checks WebSocket reachability for each target
 * 2. For unreachable players: immediately generates AI response
 * 3. For reachable players: broadcasts prompt and waits 30s for response
 * 4. On timeout: generates AI response as fallback
 * 5. Validates all responses with defensive validator
 * 6. Detects if responses target new players (cascading)
 * 7. Continues cascade until no new targets or cycle detected
 * 8. Refines all responses and aggregates into single string
 *
 * @param player The aggressor [Player].
 * @param effectiveTurnAction The aggressor's action text.
 * @param targetType The identified targets.
 * @param playType The type of action (determines resource check for counter-play).
 * @return CounterPlayResult containing aggregated prose and formatted counter-responses list.
 */
internal suspend fun handleCounterPlay(player: Player, effectiveTurnAction: String, targetType: ActionTargetTypeObj, playType: PlayTypeObj, intentHistory: GameHistory, connectionId: String): CounterPlayResult
{
    val startTime = System.currentTimeMillis()
    val allResponses = mutableListOf<ResponseData>()
    val targetingChain = mutableSetOf<String>() // Track "Attacker→Target" relationships
    val cascadeQueue = mutableListOf<CascadeState>()

    Logger.debug(LogCategory.GENERAL, "Counter-play phase begins with ${targetType.targets.size} detected targets (type=${targetType.type})")

    // Resolve Territory targets to their Player owners for counterplay
    val counterplayTargets = if(targetType.type == ActionTargetType.Territory)
    {
        val resolvedOwners = mutableListOf<String>()
        targetType.targets.forEach { territoryName ->
            val territory = WorldManager.world.mapTiles.find { it.name.equals(territoryName, true) }
            if(territory != null && territory.ruler.isNotBlank())
            {
                val owner = WorldManager.world.findPlayerByName(territory.ruler)
                if(owner != null)
                {
                    resolvedOwners.add(owner.name)
                    Logger.info(LogCategory.GENERAL, "Resolved territory '$territoryName' to owner '${owner.name}' for counterplay")
                }
                else
                {
                    Logger.debug(LogCategory.GENERAL, "Territory '$territoryName' owner '${territory.ruler}' is not a player (skipping counterplay)")
                }
            }
            else
            {
                Logger.debug(LogCategory.GENERAL, "Territory '$territoryName' has no owner (skipping counterplay)")
            }
        }

        if(resolvedOwners.isEmpty())
        {
            Logger.debug(LogCategory.GENERAL, "No player-owned territories found for counterplay")
            return CounterPlayResult("", emptyList())
        }

        Logger.info(LogCategory.GENERAL, "Converted ${targetType.targets.size} territory target(s) to ${resolvedOwners.size} player owner(s) for counterplay")
        resolvedOwners
    }
    else if(targetType.type == ActionTargetType.Player)
    {
        targetType.targets
    }
    else
    {
        Logger.debug(LogCategory.GENERAL, "Counter-play skipped because targetType=${targetType.type} is not a player or territory")
        return CounterPlayResult("", emptyList())
    }

    // Initialize cascade queue with initial targets
    val initialTargets = mutableListOf<Player>()
    counterplayTargets.forEach { targetName ->
        WorldManager.world.activePlayers.find { 
            it.name.replace("_", " ").replace("-", " ").equals(targetName.replace("_", " ").replace("-", " "), ignoreCase = true) 
        }?.let { foundPlayer ->
            // PREVENT SELF-TARGETING: An actor cannot trigger counter-play against themselves.
            if (!foundPlayer.name.equals(player.name, ignoreCase = true))
            {
                initialTargets.add(foundPlayer)
            }
            else
            {
                Logger.info(LogCategory.GENERAL, "Skipping counter-play for ${player.name} targeting themselves.")
            }
        }
    }

    if(initialTargets.isEmpty())
    {
        Logger.debug(LogCategory.GENERAL, "No reachable targeted players found for counter-play.")
        return CounterPlayResult("", emptyList())
    }

    /**
     * Sends an incremental `GameHistory` snapshot so defender replies appear progressively in the UI.
     */
    suspend fun broadcastPartialCounterResponse(note: String)
    {
        if(allResponses.isEmpty()) return
        val formattedResponses = allResponses.map { "${it.player.name}: ${it.firstPersonResponse}" }
        val partialHistory = intentHistory.copy(
            counterResponses = formattedResponses.toMutableList(),
            turnResult = note
        )
        Logger.info(LogCategory.NETWORK, "[COUNTER_RESPONSE] Broadcasting partial GameHistory (#${allResponses.size}) with note: $note")
        ActionHistoryRpcHandlers.broadcastTurnComplete(partialHistory)
    }

    // Add initial cascade state
    cascadeQueue.add(CascadeState(player, effectiveTurnAction, initialTargets, 0))

    // Process cascade queue iteratively by depth batches
    var currentDepth = 0
    while(true)
    {
        val currentDepthBatch = cascadeQueue.filter { it.cascadeDepth == currentDepth }
        if(currentDepthBatch.isEmpty()) break
        cascadeQueue.removeAll(currentDepthBatch)

        Logger.info(LogCategory.SYSTEM, "[CASCADE] Processing cascade level $currentDepth. Batch size: ${currentDepthBatch.size}")

        UiSignalRpcHandlers.broadcastProgressBar(2, "Checking player reachability (cascade ${currentDepth})...")

        val responsesAtThisDepth = mutableListOf<ResponseData>()
        val responseChannel = Channel<ResponseData>(Channel.UNLIMITED)

        coroutineScope {
            val collectorJob = launch {
                for(responseData in responseChannel)
                {
                    responsesAtThisDepth.add(responseData)
                    allResponses.add(responseData)
                    val note = "(Counter-responses: ${allResponses.size})"
                    broadcastPartialCounterResponse(note)
                }
            }

            val responseJobs = currentDepthBatch.flatMap { cascade ->
                // Filter for qualified defenders
                val activePlayers = cascade.targets.filter { tp ->
                    when(playType.type)
                    {
                        PlayType.Military -> tp.militaryPoints >= 50
                        PlayType.Diplomatic -> tp.diplomacyPoints >= 50
                        PlayType.Research -> tp.researchPoints >= 50
                        else -> false
                    }
                }

                activePlayers.map { tp ->
                    async {
                        Logger.debug(LogCategory.SYSTEM, "[CASCADE] processing response for ${tp.name} at depth $currentDepth")
                        val isReachable = WorldManager.isReachable(tp)

                        val responseData: ResponseData = if(!isReachable)
                        {
                            Logger.warn(LogCategory.LLM, "[CASCADE] AI takeover (unreachable): ${tp.name}")
                            val (firstPerson, thirdPerson) = generateAiCounterResponse(tp, cascade.attackerAction, cascade.attacker.name, connectionId)
                            val intent = detectResponseIntent(thirdPerson, connectionId)
                            ResponseData(tp, firstPerson, thirdPerson, "AI_UNREACHABLE", intent, cascade.attacker, cascade.attackerAction)
                        }
                        else
                        {
                            val deferred = GameResponseManager.waitForResponse(tp.name)
                            val sharedIntent = when(targetType.actionIntent)
                            {
                                ActionIntent.Hostile -> org.ttt.autogenesis.network.ActionIntent.Hostile
                                ActionIntent.Friendly -> org.ttt.autogenesis.network.ActionIntent.Friendly
                            }

                            UiSignalRpcHandlers.broadcastCounterPlayPrompt(tp.name, cascade.attacker.name, cascade.attackerAction, sharedIntent)

                            // Use withTimeoutOrNull for individual responses
                            WorldManager.startManualTimer(WorldManager.COUNTERPLAY_DURATION_SECONDS)
                            val humanResponse = withTimeoutOrNull(WorldManager.COUNTERPLAY_TIMEOUT_MS)
                            {
                                deferred.await()
                            }

                            if(humanResponse == "[IGNORE]")
                            {
                                ResponseData(tp, "...", "${tp.name} chooses to ignore the situation, offering no response.", "HUMAN_IGNORE", ActionIntent.Friendly, cascade.attacker, cascade.attackerAction)
                            }
                            else if(humanResponse != null)
                            {
                                val validatedResponse = if(BedrockConfig.skipValidationForAi && isAiControlled(tp)) humanResponse
                                else
                                {
                                    val validator = buildDefensiveValidator(tp, cascade.attacker, cascade.attackerAction).apply {
                                        pipelineName = "defensive_validator_${tp.name}_depth${currentDepth}"
                                        enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
                                        enablePipeTimeout(
                                            applyRecursively = true,
                                            duration = 180000,
                                            autoRetry = true,
                                            retryLimit = 5
                                        )
                                        // Set streaming callbacks for all pipes
                                        getPipes().filterIsInstance<BedrockPipe>().forEach { pipe ->
                                            val callback: suspend (String) -> Unit = { chunk: String ->
                                                UiSignalRpcHandlers.broadcastNarrativeChunk(chunk)
                                            }
                                            pipe.setStreamingCallback(callback)
                                        }
                                        init(true)
                                        streamPipelineOutputToAgentWorkBuffer(connectionId, this)
                                    }
                                    val result = validator.execute(MultimodalContent(humanResponse)).text
                                    saveSystemTrace("DefensiveValidator/${System.currentTimeMillis()}", validator, "${tp.name}_cascade${currentDepth}_validation")
                                    result
                                }
                                val intent = detectResponseIntent(validatedResponse, connectionId)
                                ResponseData(tp, humanResponse, validatedResponse, "HUMAN", intent, cascade.attacker, cascade.attackerAction)
                            }
                            else
                            {
                                Logger.warn(LogCategory.LLM, "[CASCADE] AI takeover (timeout): ${tp.name}")
                                val (firstPerson, thirdPerson) = generateAiCounterResponse(tp, cascade.attackerAction, cascade.attacker.name, connectionId)
                                val intent = detectResponseIntent(thirdPerson, connectionId)
                                ResponseData(tp, firstPerson, thirdPerson, "AI_TIMEOUT", intent, cascade.attacker, cascade.attackerAction)
                            }
                        }
                        responseChannel.send(responseData)
                        responseData
                    }
                }
            }
            responseJobs.awaitAll()
            responseChannel.close()
            collectorJob.join()
        }

        // Stop timer after all response jobs at the current depth are finished
        WorldManager.stopTurnTimer()

        // Process new targets for the next depth
        for(responseData in responsesAtThisDepth)
        {
            val targetingKey = "${responseData.respondingTo.name}→${responseData.player.name}"
            targetingChain.add(targetingKey)

            val targetDetector = buildTargetDetectorAgent(responseData.player).apply {
                enablePipeTimeout(
                    applyRecursively = true,
                    duration = 180000,
                    autoRetry = true,
                    retryLimit = 5
                )
                // Set streaming callbacks for all pipes
                getPipes().filterIsInstance<BedrockPipe>().forEach { pipe ->
                    val callback: suspend (String) -> Unit = { chunk: String ->
                        UiSignalRpcHandlers.broadcastNarrativeChunk(chunk)
                    }
                    pipe.setStreamingCallback(callback)
                }
                init(true)
                streamPipelineOutputToAgentWorkBuffer(connectionId, this)
            }
            val targetResult = targetDetector.execute(MultimodalContent(responseData.thirdPersonResponse))
            val detectedTargets = extractJson<ActionTargetTypeObj>(targetResult.text)

            if(detectedTargets != null && detectedTargets.type == ActionTargetType.Player && detectedTargets.targets.isNotEmpty())
            {
                val newTargets = mutableListOf<Player>()
                for(targetName in detectedTargets.targets)
                {
                    val targetPlayer = WorldManager.world.activePlayers.find { 
                        it.name.replace("_", " ").replace("-", " ").equals(targetName.replace("_", " ").replace("-", " "), ignoreCase = true) 
                    }
                    if(targetPlayer != null)
                    {
                        val newTargetingKey = "${responseData.player.name}→${targetPlayer.name}"
                        // Cycle detection: A -> B -> A
                        if(targetPlayer.name == responseData.respondingTo.name)
                        {
                            Logger.warn(LogCategory.GENERAL, "[CASCADE] CYCLE DETECTED: ${responseData.player.name} targets original attacker ${targetPlayer.name}. Terminating cascade.")
                        }
                        else if(targetingChain.contains(newTargetingKey))
                        {
                            Logger.warn(LogCategory.GENERAL, "[CASCADE] DUPLICATE targeting detected: ${newTargetingKey}. Skipping.")
                        }
                        else
                        {
                            newTargets.add(targetPlayer)
                        }
                    }
                }
                if(newTargets.isNotEmpty())
                {
                    cascadeQueue.add(CascadeState(responseData.player, responseData.thirdPersonResponse, newTargets, currentDepth + 1))
                }
            }
        }
        currentDepth++
    }

    Logger.info(LogCategory.GENERAL, "[CASCADE] Cascade complete. Total responses collected: ${allResponses.size}")

    val humanCount = allResponses.count { it.source == "HUMAN" || it.source == "HUMAN_IGNORE" }
    val unreachableCount = allResponses.count { it.source == "AI_UNREACHABLE" }
    val timeoutCount = allResponses.count { it.source == "AI_TIMEOUT" }
    Logger.info(LogCategory.GENERAL, "[COUNTER_RESPONSE_DIAGNOSTIC] Breakdown: Human=$humanCount, AI_Unreachable=$unreachableCount, AI_Timeout=$timeoutCount")
    Logger.info(LogCategory.GENERAL, "[COUNTER_RESPONSE_DIAGNOSTIC] Targeting Chain: ${targetingChain.joinToString(" -> ")}")

    // Detect intent mismatches
    val originalIntent = targetType.actionIntent
    val hasMismatch = allResponses.any { it.responseIntent != originalIntent }
    if(hasMismatch)
    {
        targetType.intentMismatch = true
        val mismatchedResponses = allResponses.filter { it.responseIntent != originalIntent }
        Logger.warn(LogCategory.GENERAL, "[INTENT_MISMATCH] Detected intent mismatch! Original=${originalIntent}, Mismatched responses: ${mismatchedResponses.map { "${it.player.name}(${it.responseIntent})" }.joinToString(", ")}")
    }

    // Refine all responses
    UiSignalRpcHandlers.broadcastProgressBar(2, "Refining responses...")
    val refined = coroutineScope {
        allResponses.map { responseData ->
            async {
                val agent = buildResponseRefinementAgent().apply {
                    enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
                    enablePipeTimeout(
                        applyRecursively = true,
                        duration = 180000,
                        autoRetry = true,
                        retryLimit = 5
                    )
                    // Use the actual action they are responding to
                    miniBank.contextMap["AttackerIntent"] = ContextWindow().apply { contextElements.add(responseData.respondingToAction) }
                    miniBank.contextMap["TargetIdentity"] = ContextWindow().apply { contextElements.add(responseData.player.name) }

                    // Set streaming callbacks for all pipes
                    getPipes().filterIsInstance<BedrockPipe>().forEach { pipe ->
                        val callback: suspend (String) -> Unit = { chunk: String ->
                            UiSignalRpcHandlers.broadcastNarrativeChunk(chunk)
                        }
                        pipe.setStreamingCallback(callback)
                    }

                    init(true)
                    streamPipelineOutputToAgentWorkBuffer(connectionId, this)
                    attachProgressHooks(this, 2)
                }
                val result = agent.execute(MultimodalContent(responseData.thirdPersonResponse)).text
                saveSystemTrace("ResponseRefinement", agent)
                result
            }
        }.awaitAll()
    }

    val aggregatedResponseProse = refined.joinToString(" ")
    val counterResponsesList = allResponses.map { "${it.player.name}: ${it.firstPersonResponse}" }

    val duration = System.currentTimeMillis() - startTime
    Logger.info(LogCategory.GENERAL, "Counter-play responses: ${allResponses.size} total, duration: ${duration}ms")

    return CounterPlayResult(aggregatedResponseProse, counterResponsesList)
}
/**
 * Executes the core Simulation and Assessment phase using parallel pipelines via a [Splitter].
 *
 * This function runs two major branches concurrently:
 * 1. **Narrative Generation ([buildNeoWritingAgent])**: Simulates the events of the turn based on the action and counter-plays.
 * 2. **Geopolitical Assessment ([buildAssessmentAgent])**: Analyzes the political/social impact of the action (Overton window, conflict levels, etc.).
 *
 * The results are combined to inform the final game state.
 *
 * @param ctx RPC context for broadcasting updates.
 * @param player The [Player] acting.
 * @param effectiveTurnAction The primary action text.
 * @param responseProse Aggregated text from defending players (if any).
 * @param counterResponsesList List of formatted counter-responses for assessment.
 * @param targetType The target information including intent and target count.
 * @return A [Pair] containing the generated `narrative` string and the `assessmentResult` logic object.
 */
private suspend fun executeSimulationAndAssessment(ctx: org.ttt.autogenesis.network.RpcCallContext, player: Player, effectiveTurnAction: String, responseProse: String, counterResponsesList: List<String>, targetType: ActionTargetTypeObj): Pair<String, AgentAssessmentLevel> {
    val currentRound = WorldManager.world.roundNumber
    val skipAssessment = currentRound < 4
    
    if(skipAssessment)
    {
        Logger.info(LogCategory.GENERAL, "[ASSESSMENT] Skipping geopolitics assessment for round $currentRound (starts at round 4)")
    }
    
    val splitter = Splitter()
    
    // Convert ActionIntent to String for assessment agent
    val intentString = when(targetType.actionIntent)
    {
        ActionIntent.Hostile -> "Hostile"
        ActionIntent.Friendly -> "Friendly"
    }
    
    if(!skipAssessment)
    {
        Logger.info(LogCategory.LLM, "[ASSESSMENT] Building assessment agent with intent=${intentString}, targetCount=${targetType.targets.size}, intentMismatch=${targetType.intentMismatch}")
    
        val assessmentAgent = buildAssessmentAgent(
            player = player,
            actionIntent = intentString,
            targetCount = targetType.targets.size,
            intentMismatch = targetType.intentMismatch
        ).apply { 
            pipelineName = "assessment"
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
            init(true)
            val broadcastIds = getAllConnectedClientIds()
            streamPipelineOutputToAgentWorkBuffer(broadcastIds, this)
            attachProgressHooks(this, 3) 
        }
        
        splitter.addPipeline("assessment", assessmentAgent)

        splitter.addPipeline("assessment", assessmentAgent)
        
        // Build formatted assessment input with counter-responses
        Logger.info(LogCategory.GENERAL, "[ASSESSMENT] Building assessment input")
        Logger.debug(LogCategory.GENERAL, "[ASSESSMENT] Attacker: ${player.name}, Counter-responses: ${counterResponsesList.size}")
        
        val assessmentInput = buildString {
            append("## ATTACKER ACTION ##\n")
            append("Player: ${player.name}\n")
            append("Action: $effectiveTurnAction\n")
            
            if(counterResponsesList.isNotEmpty())
            {
                append("\n## DEFENDER COUNTER-RESPONSES ##\n")
                append("Number of Defenders: ${counterResponsesList.size}\n\n")
                
                counterResponsesList.forEach { response ->
                    val colonIndex = response.indexOf(": ")
                    if(colonIndex > 0)
                    {
                        val defenderName = response.substring(0, colonIndex)
                        val defenderResponse = response.substring(colonIndex + 2)
                        append("## DEFENDER: $defenderName ##\n")
                        append("$defenderResponse\n\n")
                    }
                }
            }
        }
        
        Logger.debug(LogCategory.GENERAL, "[ASSESSMENT] Input length: ${assessmentInput.length} chars")
        Logger.debug(LogCategory.GENERAL, "[ASSESSMENT] Input preview:\n${assessmentInput.take(500)}")
        
        splitter.addContent("assessment", MultimodalContent(assessmentInput))
    }
    
    val narrativeAgent = buildNeoWritingAgent(player, WorldManager.activeWritingAgentConfig).apply { 
        pipelineName = "narrative"
        enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        enablePipeTimeout(
            applyRecursively = true,
            duration = 180000,
            autoRetry = true,
            retryLimit = 5
        )
        init(true)
        val broadcastIds = getAllConnectedClientIds()
        streamPipelineOutputToAgentWorkBuffer(broadcastIds, this)
        attachProgressHooks(this, 3) 
    }
    
    splitter.addPipeline("narrative", narrativeAgent)
    // Combine effectiveTurnAction and responseProse for the new agent input
    val narrativeInput = if(responseProse.isNotEmpty()) "$effectiveTurnAction. $responseProse" else effectiveTurnAction
    splitter.addContent("narrative", MultimodalContent(narrativeInput))
    
    splitter.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG, mergeSplitterTraces = false))
    splitter.init()

    val narrativeThrottler = NarrativeChunkThrottler()
    narrativeAgent.getPipes().filterIsInstance<BedrockPipe>().find { it.pipeName.contains("writing pipe", true) }?.enableBufferedNarrativeStreaming(narrativeThrottler)

    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.STORY)
    UiSignalRpcHandlers.broadcastProgressBar(3, "Generating narrative...")
    UiSignalRpcHandlers.broadcastPrepareStory() // Clear screen for new story
    splitter.executePipelines().awaitAll()
    narrativeThrottler.flushPending()

    saveSystemTrace("NeoWritingAgent", narrativeAgent)

    // Update story lorebook with entities from narrative
    val narrative = splitter.results.contents["narrative"]?.text ?: ""
    val assessment = if(skipAssessment)
    {
        AgentAssessmentLevel()
    }
    else
    {
        extractJson<AgentAssessmentLevel>(splitter.results.contents["assessment"]?.text ?: "") ?: AgentAssessmentLevel()
    }
    
    if(skipAssessment)
    {
        Logger.info(LogCategory.GENERAL, "[ASSESSMENT] Using default assessment (round $currentRound < 4)")
    }
    else
    {
        Logger.info(LogCategory.GENERAL, "[ASSESSMENT] Assessment complete: Favor=${assessment.favorPoints}, Risk=${assessment.riskLevel}, Defense=${assessment.playTargetDefensePoints}")
    }
    Logger.debug(LogCategory.GENERAL, "[ASSESSMENT] Contested: ${counterResponsesList.isNotEmpty()}")
    Logger.debug(LogCategory.GENERAL, "Simulation produced narrative len=${narrative.length}, abstract favorPoints=${assessment.favorPoints}, riskLevel=${assessment.riskLevel}")
    
    // Update lorebook with current turn's narrative (fire and forget)
    if(narrative.isNotBlank())
    {
        AgentCoroutineScope.scope.launch {
            try
            {
                val lorebookAgent = agent.builders.lorebook.buildLorebookUpdateAgent().apply {
                    pipelineName = "lorebook"
                    enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
                    enablePipeTimeout(
                        applyRecursively = true,
                        duration = 180000,
                        autoRetry = true,
                        retryLimit = 5
                    )
                    init(true)
                    val broadcastIds = getAllConnectedClientIds()
                    streamPipelineOutputToAgentWorkBuffer(broadcastIds, this)
                    attachProgressHooks(this, 3)
                }
                lorebookAgent.execute(MultimodalContent(narrative))
                saveSystemTrace("LorebookUpdate", lorebookAgent)
            } catch(e: Exception)
            {
                Logger.error(LogCategory.SYSTEM, "Failed to background update lorebook: ${e.message}")
            }
        }
    }

    ActionHistoryRpcHandlers.broadcastGeopoliticsUpdate(ctx, org.ttt.autogenesis.network.GeopoliticsUpdateData(WorldManager.geopoliticalAssessment))
    val traceDir = "${getTurnTraceDir()}/TurnResolutionSplitter"
    saveSystemTrace("TurnResolutionSplitter", splitter)

    splitter.getAllChildPipelines().forEach { pipeline ->
        val safeName = pipeline.pipelineName.replace(" ", "_").replace(":", "_")
        saveSystemTrace("TurnResolutionSplitter/$safeName", pipeline)
    }
    
    return Pair(narrative, assessment)
}


/**
 * Analyzes the generated narrative to determine if the *simulation* considers the action a success or failure.
 *
 * This uses the [buildPassFailAgent] to read the story and output a binary outcome.
 * Note that this is the *narrative* outcome, which may be overridden later by the *mathematical* outcome logic.
 *
 * @param player The [Player] involved.
 * @param narrative The generated story text to analyze.
 * @return `true` if the simulation depicts a victory/success, `false` otherwise.
 */
private suspend fun analyzeNarrativeOutcome(player: Player, narrative: String, connectionId: String? = null): Boolean {
    UiSignalRpcHandlers.broadcastNarrativeChunk("", isComplete = true)
    UiSignalRpcHandlers.broadcastProgressBar(3, "Analyzing outcome...")
    val agent = buildPassFailAgent(player).apply {
        enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        init(true)
        if(!connectionId.isNullOrBlank())
        {
            streamPipelineOutputToAgentWorkBuffer(connectionId, this)
        }
    }
    val output = agent.execute(MultimodalContent(narrative))
    saveSystemTrace("PassFailAgent", agent)
    Logger.debug(LogCategory.GENERAL, "PassFailAgent output length=${output.text.length}")
    return extractJson<`Victory?`>(output.text)?.isVictory ?: false
}


/**
 * Detects which game assets (resources, items, etc.) were mentioned as being *used* in the action text.
 *
 * @param player The [Player].
 * @param action The action text.
 * @return A list of asset names identified by the [buildResourceUsageDetectorAgent].
 */
private suspend fun detectAssetUsage(player: Player, action: String, connectionId: String? = null): List<String> {
    val agent = buildResourceUsageDetectorAgent(player, action).apply { 
        enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        init(true)
        if(!connectionId.isNullOrBlank())
        {
            streamPipelineOutputToAgentWorkBuffer(connectionId, this)
        }
    }
    val result = agent.execute(MultimodalContent(action))
    saveSystemTrace("AssetUsage", agent)
    Logger.debug(LogCategory.GENERAL, "Asset usage detector found ${result.text.length} chars of response")
    return extractJson<UsedAssets>(result.text)?.usedAssets ?: emptyList()
}


/**
 * Updates the player's core stats based on the [PlayType] and the success of the turn.
 *
 * - **Military**: Modifies `militaryReadiness` (+/- 15).
 * - **Diplomatic**: Modifies `legitimacy` (+/- 15).
 * - **Research**: Reduces `stagnation` on success (-15).
 * - **Summit/Other**: No stat changes.
 *
 * @param player The [Player] to update.
 * @param type The type of play attempted.
 * @param success Whether the mathematical resolution deemed the turn a success.
 */
private fun updateStats(player: Player, type: PlayType, success: Boolean)
{
    val delta = if(success) 15 else -15
    Logger.debug(LogCategory.GENERAL, "Updating stats for ${player.name}: type=$type, success=$success, delta=$delta")
    when(type)
    {
        PlayType.Military -> player.militaryReadiness = (player.militaryReadiness + delta).coerceIn(0, 100)
        PlayType.Diplomatic -> player.legitimacy = (player.legitimacy + delta).coerceIn(0, 100)
        PlayType.Research -> if(success) player.stagnation = (player.stagnation - 15).coerceAtLeast(0)
        else -> {}
    }
}


/**
 * Refines the generated narrative to ensure consistency with the *mathematical* outcome of the turn.
 *
 * This function addresses "ludonarrative dissonance": if the cool story said "You win!" but the dice said "You fail",
 * this function runs the [buildReverseAgent] to rewrite the story ending to match the failure (or vice versa).
 *
 * It also handles "Hardening/Softening" of the narrative tone if the [GameMath] logic called for it, using [buildHardenAgent].
 * If an "Act of God" is triggered, [buildActOfGodAgent] is employed to intervene.
 *
 * @param narrative The original generated story.
 * @param simSuccess The simulated outcome (what the story originally said).
 * @param finalSuccess The mathematical outcome (the source of truth).
 * @param assessment The geopolitical assessment data.
 * @param math The full [serverStructs.MathOutcome] object.
 * @return The finalized, consistent narrative string.
 */
private suspend fun refineNarrativeOutcome(narrative: String, simSuccess: Boolean, finalSuccess: Boolean, assessment: AgentAssessmentLevel, math: agent.math.MathOutcome, connectionId: String): String {
    var current = narrative

    // Act of God Check
    if(WorldManager.world.actOfGodPoints >= 100)
    {
        Logger.warn(LogCategory.GENERAL, "ACT OF GOD TRIGGERED! Points: ${WorldManager.world.actOfGodPoints}")
        UiSignalRpcHandlers.broadcastProgressBar(3, "SUMMONING DIVINE INTERVENTION...")
        UiSignalRpcHandlers.broadcastPrepareStory("glitch")

        val actOfGodThrottler = NarrativeChunkThrottler()
        val actOfGodAgent = buildActOfGodAgent().apply {
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
            init(true)
            streamPipelineOutputToAgentWorkBuffer(connectionId, this)
            attachProgressHooks(this, 3)

            getPipes().filterIsInstance<BedrockPipe>().forEach { pipe ->
                pipe.enableBufferedNarrativeStreaming(actOfGodThrottler)
            }
        }

        current = actOfGodAgent.execute(MultimodalContent(current)).text
        actOfGodThrottler.flushPending()
        saveSystemTrace("ActOfGodAgent", actOfGodAgent)
        
        // Reset points and force failure/chaos
        WorldManager.worldMutex.withLock {
            WorldManager.world.actOfGodPoints = 0
        }
        // Note: We can't easily change 'finalSuccess' here as it's passed in, but the narrative now reflects chaos.
        // The judgement phase will see this narrative.
    }
    else
    {
        Logger.debug(LogCategory.GENERAL, "Act of God not triggered (points=${WorldManager.world.actOfGodPoints}).")
    }
    
    if(simSuccess != finalSuccess)
    {
        Logger.info(LogCategory.GENERAL, "Narrative Mismatch Detected (Sim: $simSuccess vs Math: $finalSuccess). Initiating Reversal...")
        UiSignalRpcHandlers.broadcastProgressBar(3, "Aligning story with simulation results...")
        
        // Signal UI to wipe and prepare for rewrite
        UiSignalRpcHandlers.broadcastPrepareStory("glitch")
        
        val reversalThrottler = NarrativeChunkThrottler()
        val agent = buildReverseAgent().apply {
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
            init(true)
            streamPipelineOutputToAgentWorkBuffer(connectionId, this)
            attachProgressHooks(this, 3)
            getPipes().filterIsInstance<BedrockPipe>().forEach { pipe ->
                Logger.info(LogCategory.GENERAL, "Setting up streaming for Refinement Agent...")
                pipe.enableBufferedNarrativeStreaming(reversalThrottler)
            }
        }
        try
        {
            current = agent.execute(MultimodalContent(current)).text
            reversalThrottler.flushPending()
        } finally {
            saveSystemTrace("ReversalAgent", agent)
        }
        // No need to broadcast update, chunks were streamed
    }
    
    val hardened = math.guidance.contains("HARDENED", true)
    val softened = math.guidance.contains("SOFTENED", true)
    val currentRound = WorldManager.world.roundNumber
    
    if((hardened || softened) && currentRound >= 4)
    {
        val mode = if(hardened) "HARDENED" else "SOFTENED"
        Logger.info(LogCategory.GENERAL, "Outcome Guidance indicates $mode result for round $currentRound. Adjusting intensity...")
        UiSignalRpcHandlers.broadcastProgressBar(3, "Adjusting narrative intensity ($mode)...")
        
        // Signal UI to wipe and prepare for rewrite
        UiSignalRpcHandlers.broadcastPrepareStory("glitch")

        val hardenThrottler = NarrativeChunkThrottler()
        val agent = buildHardenAgent(hardened).apply {
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
            init(true)
            streamPipelineOutputToAgentWorkBuffer(connectionId, this)
            attachProgressHooks(this, 3)
            getPipes().filterIsInstance<BedrockPipe>().forEach { pipe ->
                pipe.enableBufferedNarrativeStreaming(hardenThrottler)
            }
        }
        val intensity = abs(assessment.favorPoints).coerceAtLeast(5)
        current = agent.execute(MultimodalContent(serialize(HardenSoften(intensity, current)))).text
        hardenThrottler.flushPending()
        saveSystemTrace("HardenAgent", agent)
        // No need to broadcast update, chunks were streamed
    }
    else
    {
        Logger.debug(LogCategory.GENERAL, "Narrative intensity left unaltered (guidance=${math.guidance})")
    }
    return current
}


/**
 * Executes the final judgement phase to determine specific consequences (gains/losses).
 *
 * This runs the [buildJudge] pipeline, which:
 * 1. Re-evaluates the final narrative.
 * 2. Determines specific territory/resource exchanges.
 * 3. Applies karma and stat changes to all involved actors.
 *
 * @param player The acting [Player].
 * @param targetType The identifed target info.
 * @param narrative The final, refined story.
 * @param success The boolean success state.
 * @param playType The type of play (Military, Diplomatic, etc.) used to determine action intent.
 * @return A Pair containing the Results object and the judge Pipeline for metadata access.
 */
private suspend fun executeJudgementPhase(player: Player, targetType: ActionTargetTypeObj, narrative: String, success: Boolean, playType: PlayTypeObj, connectionId: String): Pair<Results, Pipeline>
{
    val primaryTargetName = targetType.targets.firstOrNull() ?: ""
    val targetActor = when(targetType.type)
    {
        ActionTargetType.Player -> WorldManager.world.findPlayerByName(primaryTargetName)
        ActionTargetType.Npc -> WorldManager.world.findNpcByName(primaryTargetName)
        ActionTargetType.Territory -> WorldManager.world.mapTiles.find { it.name.equals(primaryTargetName, true) }?.ruler?.let { 
            WorldManager.world.findPlayerByName(it) ?: WorldManager.world.findNpcByName(it) 
        }
        else -> null
    }
    val resolvedTargetName = when(targetActor)
    {
        is Player -> targetActor.name
        is Npc -> targetActor.name
        else -> "<none>"
    }
    Logger.debug(LogCategory.GENERAL, "Judgement phase resolved targetActor=$resolvedTargetName (primaryTarget='$primaryTargetName')")

    Logger.info(LogCategory.GENERAL, "Initializing Judgement Phase...")
    
    // Use the actual detected action intent, not a hardcoded mapping from play type
    val actionIntent = when(targetType.actionIntent)
    {
        ActionIntent.Hostile -> "Hostile"
        ActionIntent.Friendly -> "Friendly"
    }
    Logger.debug(LogCategory.GENERAL, "Action intent for judge: $actionIntent (detected from action, playType=${playType.type})")
    
    // Store play type and success for Judge access
    val playTypeContext = mapOf(
        "playType" to playType.type.name,
        "wasSuccessful" to success
    )
    val playTypeWindow = ContextWindow().apply {
        contextElements.add(serialize(playTypeContext))
    }
    ContextBank.emplace("play_type_context", playTypeWindow)
    Logger.debug(LogCategory.GENERAL, "Stored play_type_context: ${playType.type.name}, success=$success")
    
    val judge = buildJudge(player, targetActor, success, actionIntent, targetType).apply { 
        enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        enablePipeTimeout(
            applyRecursively = true,
            duration = 180000,
            autoRetry = true,
            retryLimit = 5
        )
        init(true) 
        streamPipelineOutputToAgentWorkBuffer(connectionId, this)
        attachProgressHooks(this, 4) 
    }
    
    UiSignalRpcHandlers.broadcastProgressBar(4, "Determining consequences...")
    judge.execute(MultimodalContent(narrative))
    
    Logger.debug(LogCategory.GENERAL, "[JUDGE_DEBUG] judge.pipeMetaData keys: ${judge.pipeMetaData.keys}")
    Logger.debug(LogCategory.GENERAL, "[JUDGE_DEBUG] judge.pipeMetaData['judge result'] type: ${judge.pipeMetaData["judge result"]?.javaClass?.simpleName}")
    
    val judgeResults = judge.pipeMetaData["judge result"] as? Results ?: Results(resultSummary = if(success) "Success" else "Failure")
    
    Logger.info(LogCategory.GENERAL, "[JUDGE_DEBUG] judgeResults.resultSummary: ${judgeResults.resultSummary}")
    Logger.debug(LogCategory.GENERAL, "[JUDGE_DEBUG] Using fallback: ${judge.pipeMetaData["judge result"] == null}")

    // Synchronously apply judge results to the world state.
    WorldManager.applyJudgeResults(
        playerName = player.name,
        wasSuccessful = success,
        results = judgeResults,
        turnNumber = WorldManager.world.roundNumber,
        timestampMillis = TimeProvider.nowMillis()
    )

    // Validate research play stat buffs
    if (playType.type == PlayType.Research && success) {
        val playerStatChanges = (judge.pipeMetaData["actor stat changes"] as? MultiActorStatChanges)
            ?.changes?.get(player.name)
        
        val hasStatBuff = playerStatChanges?.let { buff ->
            buff.might != 0 || buff.wealth != 0 || buff.reputation != 0 || buff.luckPoints != 0
        } ?: false
        
        if (!hasStatBuff) {
            Logger.warn(LogCategory.GENERAL, "Successful Research play for ${player.name} has no stat buffs. Applying fallback based on trait: ${player.trait}")
            applyFallbackResearchBuff(player, judgeResults)
        } else {
            Logger.info(LogCategory.GENERAL, "Research play stat buffs validated for ${player.name}")
        }
    }

    saveSystemTrace("Judge", judge)
    Logger.info(LogCategory.GENERAL, "Judgement Complete and Applied.")
    Logger.debug(LogCategory.GENERAL, "World history updated with ${judgeResults.territoryGained.size} territories gained and ${judgeResults.assetsGained.size} assets gained")

    return Pair(judgeResults, judge)
}

private suspend fun applyFallbackResearchBuff(player: Player, judgeResults: agent.builders.judgeOutcome.Results) {
    val buff = when (player.trait) {
        CommanderTrait.Warlord -> StatBuff(
            might = 20,
            luckPoints = 10,
            wealth = 0,
            reputation = 0,
            militaryReadiness = 0,
            legitimacy = 0,
            stagnation = 0
        )
        CommanderTrait.Diplomatic -> StatBuff(
            might = 0,
            luckPoints = 10,
            wealth = 15,
            reputation = 20,
            militaryReadiness = 0,
            legitimacy = 0,
            stagnation = 0
        )
        else -> StatBuff( // Balanced
            might = 15,
            luckPoints = 10,
            wealth = 10,
            reputation = 10,
            militaryReadiness = 0,
            legitimacy = 0,
            stagnation = 0
        )
    }
    
    WorldManager.worldMutex.withLock {
        player.might += buff.might
        player.luckPoints += buff.luckPoints
        player.wealth += buff.wealth
        player.reputation += buff.reputation
        
        // Cap stats
        player.might = player.might.coerceAtMost(250)
        player.luckPoints = player.luckPoints.coerceAtMost(100)
        player.wealth = player.wealth.coerceAtMost(250)
        player.reputation = player.reputation.coerceAtMost(250)
    }
    
    Logger.info(LogCategory.GENERAL, "Applied fallback research buff for ${player.name}: might+${buff.might}, luck+${buff.luckPoints}, wealth+${buff.wealth}, rep+${buff.reputation}")
}


/**
 * Commits the resolved turn to the permanent [WorldManager.history] and broadcasts the result to the client.
 *
 * **Crucial**: This uses the `id` from the initial "Planning" broadcast (passed as [existingId]) to ensure
 * the client updates the *existing* history entry rather than creating a duplicate.
 */

/**
 * Calculates affected players from territory and asset exchanges.
 *
 * Aggregates all exchanges to determine which players gained/lost what,
 * and calculates a net outcome (Positive/Negative/Neutral) for each.
 *
 * @param territoryExchanges List of territory transfers from judge
 * @param assetExchanges List of asset transfers from judge
 * @return Map of player names to their outcomes
 */
internal fun calculateAffectedPlayers(
    turnPlayer: String,
    results: agent.builders.judgeOutcome.Results
): MutableMap<String, structs.PlayerOutcome>
{
    val outcomes = mutableMapOf<String, structs.PlayerOutcome>()
    
    // Process direct gains/losses for turn player
    if(turnPlayer.isNotBlank()) {
        val outcome = outcomes.getOrPut(turnPlayer) { 
            structs.PlayerOutcome(playerName = turnPlayer) 
        }
        outcome.territoriesGained.addAll(results.territoryGained)
        outcome.territoriesLost.addAll(results.territoryLost)
        outcome.resourcesGained.addAll(results.assetsGained)
        outcome.resourcesLost.addAll(results.assetsLost)
    }
    
    // Process territory exchanges
    for(exchange in results.territoryExchanges)
    {
        // Player losing territory
        if(exchange.from.isNotBlank())
        {
            val outcome = outcomes.getOrPut(exchange.from) { 
                structs.PlayerOutcome(playerName = exchange.from) 
            }
            outcome.territoriesLost.add(exchange.territoryName)
        }
        
        // Player gaining territory
        if(exchange.to.isNotBlank())
        {
            val outcome = outcomes.getOrPut(exchange.to) { 
                structs.PlayerOutcome(playerName = exchange.to) 
            }
            outcome.territoriesGained.add(exchange.territoryName)
        }
    }
    
    // Process asset exchanges
    for(exchange in results.assetExchanges)
    {
        // Player losing asset
        if(exchange.from.isNotBlank())
        {
            val outcome = outcomes.getOrPut(exchange.from) { 
                structs.PlayerOutcome(playerName = exchange.from) 
            }
            outcome.resourcesLost.add(exchange.assetName)
        }
        
        // Player gaining asset
        if(exchange.to.isNotBlank())
        {
            val outcome = outcomes.getOrPut(exchange.to) { 
                structs.PlayerOutcome(playerName = exchange.to) 
            }
            outcome.resourcesGained.add(exchange.assetName)
        }
    }
    
    // Process each outcome to remove duplicates and calculate net outcome
    for(outcome in outcomes.values)
    {
        outcome.territoriesGained = outcome.territoriesGained.distinct().toMutableList()
        outcome.territoriesLost = outcome.territoriesLost.distinct().toMutableList()
        outcome.resourcesGained = outcome.resourcesGained.distinct().toMutableList()
        outcome.resourcesLost = outcome.resourcesLost.distinct().toMutableList()
        
        val gained = outcome.territoriesGained.size + outcome.resourcesGained.size
        val lost = outcome.territoriesLost.size + outcome.resourcesLost.size
        
        outcome.netOutcome = when {
            gained > lost -> "Positive"
            lost > gained -> "Negative"
            else -> "Neutral"
        }
    }
    
    return outcomes
}

/**
 * Stages a history entry containing the turn data the judge needs to evaluate.
 */
private suspend fun stageCurrentTurnHistoryEntry(
    player: Player,
    action: String,
    narrative: String,
    assets: List<String>,
    counterResponseList: List<String>,
    targetType: ActionTargetTypeObj,
    turnId: String
)
{
    WorldManager.stageCurrentTurnHistory(turnId) {
        turnPlayer = player.name
        turnAction = action
        usingResources.clear()
        usingResources.addAll(assets)
        turnStory = narrative
        counterResponses.clear()
        counterResponses.addAll(counterResponseList)
        targetIntent = targetType.actionIntent.name
        targetEntities.clear()
        targetEntities.addAll(targetType.targets)
        turnResult = "(Judgement pending)"
    }
    Logger.debug(LogCategory.GENERAL, "Staged current turn history for judgement (ID=$turnId)")
}

/**
 * Commits the turn to history and broadcasts it to all clients.
 *
 * @param player The [Player].
 * @param action The final action text.
 * @param narrative The final story text.
 * @param success The success state.
 * @param results The gains/losses details.
 * @param judgePipeline The judge pipeline for accessing metadata.
 * @param assets List of assets used.
 * @param counterResponses List of formatted counter-responses from defenders.
 * @param existingId The UUID of the history entry created at the start of the turn (for UI continuity).
 */
private suspend fun commitHistoryAndBroadcast(player: Player, action: String, narrative: String, success: Boolean, results: Results, judgePipeline: Pipeline, assets: List<String>, counterResponsesList: List<String>, existingId: String? = null)
{
    Logger.info(LogCategory.GENERAL, "[COUNTER_RESPONSE] commitHistoryAndBroadcast received ${counterResponsesList.size} counter-responses")
    
    // Extract abstract resource buff mappings from pipeline metadata
    val abstractBuffs = judgePipeline.pipeMetaData["abstract buffs"] as? List<AbstractResourceBuff> ?: emptyList()
    val buffMap = abstractBuffs.associate { buff ->
        buff.resourceName to formatStatBuff(buff.statBuff)
    }.toMutableMap()
    if(buffMap.isNotEmpty())
    {
        Logger.info(LogCategory.GENERAL, "CommitHistory: ${player.name} will record ${buffMap.size} abstract stat buff(s): ${buffMap.keys.joinToString(", ")}")
    }
    else
    {
        Logger.info(LogCategory.GENERAL, "CommitHistory: No abstract stat buffs captured for ${player.name} (abstract metadata held ${abstractBuffs.size} entr${if(abstractBuffs.size == 1) "y" else "ies"})")
    }
    
    // Extract actor stat changes from pipeline metadata
    val actorStatChanges = judgePipeline.pipeMetaData["actor stat changes"] as? agent.builders.judgeOutcome.MultiActorStatChanges
    if(actorStatChanges != null && actorStatChanges.changes.isNotEmpty())
    {
        actorStatChanges.changes.forEach { (actorName, statBuff) ->
            val formattedStats = formatStatBuff(statBuff)
            if(formattedStats.isNotBlank())
            {
                buffMap[actorName] = formattedStats
            }
        }
        Logger.info(LogCategory.GENERAL, "CommitHistory: Added stat changes for ${actorStatChanges.changes.size} actor(s)")
    }
    
    val history = WorldManager.finalizeStagedHistoryEntry(existingId) {
        usingResources.clear()
        usingResources.addAll(assets)
        turnStory = narrative
        wasPlayerSuccessful = success
        turnResult = results.resultSummary.ifBlank { if(success) "Success" else "Failure" }
        statBuffsGained.clear()
        statBuffsGained.putAll(buffMap)
        counterResponses.clear()
        counterResponses.addAll(counterResponsesList)
        territoryGained.clear()
        territoryGained.addAll(results.territoryGained)
        territoryLost.clear()
        territoryLost.addAll(results.territoryLost)
        resourcesWon.clear()
        resourcesWon.addAll(results.assetsGained)
        resourcesLost.clear()
        resourcesLost.addAll(results.assetsLost)
        territoryExchanges.clear()
        territoryExchanges.addAll(results.territoryExchanges.map { structs.TerritoryExchange(it.territoryName, it.from, it.to) })
        assetExchanges.clear()
        assetExchanges.addAll(results.assetExchanges.map { structs.AssetExchange(it.assetName, it.from, it.to) })
        affectedPlayers.clear()
        affectedPlayers.putAll(
            calculateAffectedPlayers(
                player.name,
                results
            )
        )
        turnPlayer = player.name
        turnAction = action
        // Soft fix for BUG #4: round number can advance between when
        // recordThinkingUpdate is called and when we consume, leaving the
        // current round's pending thinking orphaned. Try the previous
        // round as a fallback so a stale round doesn't silently drop the
        // thinking data. Full fix requires refactoring the thinking
        // pipeline to write-then-read in a single turn boundary.
        val currentRound = WorldManager.world.roundNumber
        val consumed = WorldManager.consumePendingThinking(currentRound)
        if (consumed.isEmpty() && currentRound > 1)
        {
            val fromPrev = WorldManager.consumePendingThinking(currentRound - 1)
            if (fromPrev.isNotEmpty())
            {
                Logger.warn(LogCategory.SYSTEM, "commitHistoryAndBroadcast: pulled ${fromPrev.size} stale thinking updates from round ${currentRound - 1} (round=$currentRound had none).")
            }
            thinkingUpdates.addAll(fromPrev)
        }
        else
        {
            thinkingUpdates.addAll(consumed)
        }
    }
    Logger.info(LogCategory.GENERAL, "[COUNTER_RESPONSE] History entry updated with ${history.counterResponses.size} counter-responses (ID: ${history.id})")
    Logger.debug(LogCategory.GENERAL, "History entry updated (ID=${history.id}, assets=${assets.size}, narrativeLen=${history.turnStory.length})")
    
    val resultHeader = if(success) "SUCCESS" else "FAILURE"
    Logger.info(LogCategory.NETWORK, "[BROADCAST_DEBUG] results.resultSummary = '${results.resultSummary}'")
    Logger.info(LogCategory.NETWORK, "[BROADCAST_DEBUG] results.resultSummary.isNotBlank() = ${results.resultSummary.isNotBlank()}")
    val resultSubtext = if(results.resultSummary.isNotBlank()) results.resultSummary else "No gains or losses occured."
    Logger.info(LogCategory.NETWORK, "[BROADCAST_DEBUG] resultSubtext = '$resultSubtext'")

    Logger.info(LogCategory.NETWORK, "Broadcasting Player Judgement UI Signal...")
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.JUDGEMENT)
    UiSignalRpcHandlers.broadcastJudgementResult(success, resultHeader, resultSubtext)
    
    Logger.info(LogCategory.NETWORK, ">>> DISPATCHING TURN DETAILS (ID: ${history.id}, StoryLen: ${history.turnStory.length}) <<<")
    Logger.info(LogCategory.NETWORK, "[COUNTER_RESPONSE] Broadcasting GameHistory with ${history.counterResponses.size} counter-responses")
    ActionHistoryRpcHandlers.broadcastTurnComplete(history)
    Logger.info(LogCategory.NETWORK, ">>> TURN DETAILS DISPATCHED (Orchestrator Handoff Complete) <<<")
    Logger.debug(LogCategory.NETWORK, "Broadcasted judgement result for ${player.name} (success=$success)")
}

/**
 * Formats a StatBuff object into a human-readable string for UI display.
 *
 * @param buff The StatBuff to format.
 * @return A formatted string like "+15 Wealth, +10 Reputation" or empty string if no buffs.
 */
private fun formatStatBuff(buff: StatBuff): String
{
    val parts = mutableListOf<String>()
    if(buff.luckPoints != 0) parts.add("${if(buff.luckPoints > 0) "+" else ""}${buff.luckPoints} Luck")
    if(buff.reputation != 0) parts.add("${if(buff.reputation > 0) "+" else ""}${buff.reputation} Reputation")
    if(buff.might != 0) parts.add("${if(buff.might > 0) "+" else ""}${buff.might} Might")
    if(buff.wealth != 0) parts.add("${if(buff.wealth > 0) "+" else ""}${buff.wealth} Wealth")
    if(buff.militaryReadiness != 0) parts.add("${if(buff.militaryReadiness > 0) "+" else ""}${buff.militaryReadiness} Readiness")
    if(buff.legitimacy != 0) parts.add("${if(buff.legitimacy > 0) "+" else ""}${buff.legitimacy} Legitimacy")
    if(buff.stagnation != 0) parts.add("${if(buff.stagnation > 0) "+" else ""}${buff.stagnation} Stagnation")
    return parts.joinToString(", ")
}


/**
 * Performs end-of-turn maintenance tasks after the player's action is visually complete.
 *
 * Tasks include:
 * 1. Variable delays to allow the user to read the story.
 * 2. Broadcasting explicit dispatch updates for resources/territories.
 * 3. Triggering the Dispatch UI phase.
 * 4. Scanning for newly introduced NPCs ([buildNewCharacterScanPipeline]) and adding them to the database.
 * 5. Running global world updates ([worldUpdatesPipeline]) to reflect changes in the map/state.
 * 6. Checking for "Nemesis" spawn conditions via [checkNemesisProtocol].
 * 7. Resetting the UI to interactive mode.
 *
 * @param player The [Player].
 * @param narrative The story text (used for Nemesis generation context).
 * @param assets Assets used.
 * @param results Judge results (used for dispatch).
 */
private suspend fun handleTurnMaintenance(player: Player, narrative: String, assets: List<String>, results: Results, connectionId: String)
{
    Logger.info(LogCategory.GENERAL, "Starting turn maintenance delay (6000ms)...")
    delay(6000)
    
    Logger.info(LogCategory.GENERAL, "Initiating Dispatch Step...")
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.DISPATCH)
    UiSignalRpcHandlers.broadcastProgressBar(5, "Dispatching assets...")
    
    AgentCoroutineScope.scope.launch {
        org.ttt.autogenesis.server.DispatchRpcHandler.broadcastDispatchResult(
             usedAssets = assets,
             gainedAssets = results.assetsGained,
             lostAssets = results.assetsLost,
             territoryGained = results.territoryGained,
             territoryLost = results.territoryLost,
             territoryExchanges = results.territoryExchanges.map { structs.TerritoryExchange(it.territoryName, it.from, it.to) }
        )
    }
    
    Logger.debug(LogCategory.GENERAL, "Dispatch broadcast included ${results.assetsGained.size} gained assets and ${results.territoryGained.size} gained territories")
    delay(4000)
    
    Logger.info(LogCategory.GENERAL, "Initiating Maintenance Phase (Parallel)...")
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.UPDATE_NPCS)
    UiSignalRpcHandlers.broadcastProgressBar(6, "Updating NPCs & World State...")

    val maintenanceSplitter = Splitter().apply {
        enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG, mergeSplitterTraces = false))
        
        val scanPipe = buildNewCharacterScanPipeline().apply {
            pipelineName = "scan"
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
            init(true)
            attachProgressHooks(this, 6)
            streamPipelineOutputToAgentWorkBuffer(connectionId, this)
        }
        addPipeline("scan", scanPipe)
        addContent("scan", MultimodalContent(narrative)) // Passing narrative as per plan

        val updatePipe = worldUpdatesPipeline().apply {
            pipelineName = "updates"
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
            init(true)
            attachProgressHooks(this, 7)
            streamPipelineOutputToAgentWorkBuffer(connectionId, this)
        }
        addPipeline("updates", updatePipe)
        addContent("updates", MultimodalContent("")) // World updates typically checks global state
        
        init()
        
        val broadcastIds = getAllConnectedClientIds()
        getAllChildPipelines().forEach { streamPipelineOutputToAgentWorkBuffer(broadcastIds, it) }
    }

    Logger.info(LogCategory.GENERAL, "STARTING Maintenance Splitter Execution...")
    try
    {
        maintenanceSplitter.executePipelines().awaitAll()
    }
    catch(e: Exception)
    {
       Logger.error(LogCategory.GENERAL, "Error during Maintenance Splitter Execution: ${e.message}")
       e.printStackTrace()
    }
    Logger.info(LogCategory.GENERAL, "COMPLETED Maintenance Splitter Execution.")

    // Save individual traces
    val traceDirMaintenanceBase = "MaintenanceSplitter/${System.currentTimeMillis()}"
    saveSystemTrace(traceDirMaintenanceBase, maintenanceSplitter)

    maintenanceSplitter.getAllChildPipelines().forEach { pipeline ->
        val safeName = pipeline.pipelineName.replace(" ", "_").replace(":", "_")
        saveSystemTrace("$traceDirMaintenanceBase/$safeName", pipeline)
    }
    Logger.debug(LogCategory.GENERAL, "Maintenance splitter completed; scan outputs: ${maintenanceSplitter.results.contents.keys.joinToString(",")}")
    
    // Ensure subordinate resources have corresponding NPC entities
    player.resources.filter { it.type == ResourceType.Subordinate }.forEach { subordinate ->
        val npcExists = WorldManager.world.npc.any { it.name.equals(subordinate.name, ignoreCase = true) }
        if (!npcExists) {
            Logger.warn(LogCategory.GENERAL, "Creating missing NPC entity for subordinate resource: ${subordinate.name}")
            val newNpc = Npc().apply {
                name = subordinate.name
                description = subordinate.description
                abilities = subordinate.abilities
                type = NpcType.Subordinate
                createdBy = player.name
                history = "Created as subordinate of ${player.name}. ${subordinate.description}"
                personality = ""
                pointValue = 4
                militaryReadiness = 70
                legitimacy = 70
                stagnation = 0
                isDefeated = subordinate.isDestroyedOrDepleted
            }
            WorldManager.world.npc.add(newNpc)
            WorldManager.logNpcOwnershipDITL(newNpc)
            Logger.info(LogCategory.GENERAL, "Created NPC entity for ${subordinate.name} (subordinate of ${player.name})")
        }
    }
    
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.UPDATE_WORLD)
    UiSignalRpcHandlers.broadcastWorldUpdate(WorldManager.world)
    UiSignalRpcHandlers.broadcastProgressBar(7, null)
    
    Logger.info(LogCategory.GENERAL, "Broadcasting map update and waiting 4s for visual confirmation...")
    delay(4000) // Allow players to see the territory changes
    
    Logger.info(LogCategory.GENERAL, "Checking Nemesis Protocol...")
    checkNemesisProtocol(narrative, connectionId)
    Logger.info(LogCategory.GENERAL, "Nemesis Protocol Check Complete.")
    
    Logger.info(LogCategory.GENERAL, "Turn Execution Complete for ${player.name}")
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.WAITING)
    UiSignalRpcHandlers.broadcastCommandInteractive(true)
}

/**
 * Checks if the global "Karma" threshold has been breached and possibly spawns a "Nemesis".
 * 
 * **Metric**: [WorldManager.world.karmaPoints]
 * **Threshold**: +/- 100
 *
 * If the world is in chaos (high negative karma) or too orderly (high positive karma), a Nemesis entity
 * may be spawned or revived to disrupt the status quo. This is a balancing mechanic.
 *
 * If a new Nemesis is needed, [buildNemesisCreationAgent] is invoked to generating it.
 *
 * @param narrative The narrative text of the current turn (used to seed the Nemesis backstory).
 */
private suspend fun checkNemesisProtocol(narrative: String, connectionId: String)
{
    Logger.info(LogCategory.GENERAL, "Checking Karma Levels...")
    val currentKarma = WorldManager.world.karmaPoints
    if(abs(currentKarma) < 100)
    {
        Logger.debug(LogCategory.GENERAL, "Karma threshold not met (value=$currentKarma); skipping Nemesis protocol")
        return
    }

    Logger.warn(LogCategory.GENERAL, "Karma Threshold Reached ($currentKarma). Initiating Nemesis Protocol.")
    var shouldSpawnNew = false
    
    WorldManager.worldMutex.withLock {
        val activeNemesis = WorldManager.world.npc.filter { it.type == NpcType.Nemesis && !it.isDefeated }
        val defeatedNemesis = WorldManager.world.npc.filter { it.type == NpcType.Nemesis && it.isDefeated }
        Logger.info(LogCategory.GENERAL, "[NPC] Nemesis status – ${activeNemesis.size} active, ${defeatedNemesis.size} defeated tracked.")
        
        if(activeNemesis.isEmpty())
        {
            if(defeatedNemesis.isNotEmpty())
            {
                // Revive an old enemy
                val reviving = defeatedNemesis.random()
                reviving.isDefeated = false
                Logger.info(LogCategory.GENERAL, "NEMESIS REVIVED: ${reviving.name} has returned to the world!")
            }
            else
            {
                shouldSpawnNew = true
            }
        }
        else
        {
            // Chance to reinforce existing chaos
            val roll = Random.nextInt(100)
            if(roll < 35)
            {
                Logger.info(LogCategory.GENERAL, "Chaos Escalates! Rolling for Nemesis Reinforcement... SUCCESS ($roll < 35).")
                shouldSpawnNew = true
            }
            else
            {
                Logger.info(LogCategory.GENERAL, "Chaos Escalates! Rolling for Nemesis Reinforcement... FAILED ($roll >= 35).")
            }
        }
        // Reset karma after check to prevent infinite spawn loops
        WorldManager.world.karmaPoints = 0
        Logger.info(LogCategory.GENERAL, "Karma Reset to 0.")
    }

    if(shouldSpawnNew)
    {
        Logger.info(LogCategory.GENERAL, "Spawning NEW Nemesis...")
        UiSignalRpcHandlers.broadcastProgressBar(7, "Constructing new Nemesis entity...")
        val agent = buildNemesisCreationAgent().apply { 
            enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
            init(true) 
            streamPipelineOutputToAgentWorkBuffer(connectionId, this)
            attachProgressHooks(this, 7)
        }
        agent.execute(MultimodalContent(narrative))
        saveSystemTrace("NemesisCreation", agent)
        Logger.info(LogCategory.GENERAL, "New Nemesis Spawned and Registered.")
    }
    else
    {
        Logger.debug(LogCategory.GENERAL, "Nemesis protocol evaluated but no new spawn needed.")
    }
}


/**
 * Helper to retrieve retry logs from the [ContextBank] for the "errorStatus" key.
 * Used by agent retry logic to understand why a previous attempt failed.
 */
suspend fun fetchRetryLogs(): AgentRetry
{
    val context = ContextBank.getContextFromBank("errorStatus")
    if(context.isEmpty())
    {
        Logger.debug(LogCategory.SYSTEM, "No retry logs present in context bank.")
        return AgentRetry()
    }
    Logger.debug(LogCategory.SYSTEM, "Fetched retry logs; ${context.contextElements.size} entries present")
    val json = context.contextElements[0]
    val retryData = extractJson<AgentRetry>(json) ?: AgentRetry()
    context.contextElements.remove(json)
    ContextBank.emplaceWithMutex("errorStatus", context)
    return retryData
}


/**
 * Swaps the models of a pipeline to a fallback configuration (typically smaller/faster or more robust models)
 * when a retry is triggered.
 *
 * - Nova -> Palmyra
 * - Deepseek/NovaPro -> Qwen
 *
 * @param pipeline The [Pipeline] to modify in-place.
 */
fun swapPipelineModels(pipeline: Pipeline)
{
    pipeline.getPipes().forEach { pipe ->
        val modelName = pipe.getModelName()
        if(modelName == BedrockConfig.novaModelName)
        {
            pipe.setModel(BedrockConfig.PalmyraX5); pipe.setTokenBudget(BedrockConfig.palmyraBudgetSettings); pipe.disableReasoning()
            Logger.info(LogCategory.GENERAL, "Swapped pipe ${pipe.pipeName} from Nova -> PalmyraX5 due to retry")
        }
        else if(listOf(BedrockConfig.deepseekModelName, BedrockConfig.deepseekV31, BedrockConfig.novaProModelName).contains(modelName))
        {
            pipe.setModel(BedrockConfig.qwen235B); pipe.setTokenBudget(BedrockConfig.generativeBudgetSettings); pipe.disableReasoning()
            Logger.info(LogCategory.GENERAL, "Swapped pipe ${pipe.pipeName} from $modelName -> QwenCoder480B due to retry")
        }
    }
}


/**
 * Generates an AI counter-response for a player being attacked.
 * 
 * Uses buildPlayerAgent to generate a full strategic counter-play response.
 * Injects counter-play information into WorldManager.history so the agent sees it
 * as part of the current game state during its analysis phase.
 * 
 * @param player The defending player
 * @param attackerAction The attacker's action text
 * @param attackerName The name of the attacking player
 * @return AI-generated counter-play action text
 */
/**
 * Generates an AI counter-response for a targeted player.
 * 
 * Returns both the first-person planning output (for UI display) and the third-person
 * execution output (for narrative integration).
 * 
 * @return Pair of (firstPersonResponse, thirdPersonAction)
 */
suspend fun generateAiCounterResponse(player: Player, attackerAction: String, attackerName: String, connectionId: String): Pair<String, String>
{
    Logger.info(LogCategory.LLM, "Generating AI counter-response for ${player.name} against $attackerName")
    
    // Inject counter-play as a temporary history entry so buildPlayerAgent sees it
    val counterPlayHistory = GameHistory(
        turnPlayer = attackerName,
        turnAction = attackerAction,
        turnResult = "COUNTER-PLAY REQUIRED: ${player.name} must respond to this attack.",
        wasPlayerSuccessful = true
    )
    
    // Temporarily add to history
    WorldManager.worldMutex.withLock {
        WorldManager.history.add(counterPlayHistory)
    }
    
    val agent = buildPlayerAgent(player).apply {
        enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        init(true)
    }
    
    streamPipelineOutputToAgentWorkBuffer(connectionId, agent)
    
    val traceDir = "${getTurnTraceDir()}/AI_Counter_Response"
    val dir = File(traceDir)
    if(!dir.exists()) dir.mkdirs()
    
    try
    {
        val result = agent.execute(MultimodalContent(""))

        // Remove temporary history entry
        WorldManager.worldMutex.withLock {
            WorldManager.history.remove(counterPlayHistory)
        }

        // Extract response outputs from metadata
        val executionOutput = result.metadata["executionOutput"] as? String
        val planningOutput = result.metadata["planningOutput"] as? String
       
        val firstPersonResponse = executionOutput ?: planningOutput ?: result.text
        val thirdPersonAction = result.text
       
        Logger.info(LogCategory.LLM, "[COUNTER_RESPONSE] AI counter-response generated for ${player.name}")
        Logger.debug(LogCategory.LLM, "[COUNTER_RESPONSE] Display version length: ${firstPersonResponse.length}, Narrative version length: ${thirdPersonAction.length}")
        Logger.debug(LogCategory.LLM, "[COUNTER_RESPONSE] Display preview: ${firstPersonResponse.take(200)}...")
        Logger.debug(LogCategory.LLM, "[COUNTER_RESPONSE] Narrative preview: ${thirdPersonAction.take(100)}...")
        saveSystemTrace("AI_Counter_Response", agent, "${player.name}_${System.currentTimeMillis()}")

        Logger.debug(LogCategory.LLM, "Counter-response trace saved at ${dir.absolutePath}")
        return Pair(firstPersonResponse, thirdPersonAction)
    }
    catch(e: Exception)
    {
        // Save trace even on failure
        saveSystemTrace("AI_Counter_Response", agent, "${player.name}_FAILED_${System.currentTimeMillis()}")
        WorldManager.worldMutex.withLock {
            WorldManager.history.remove(counterPlayHistory)
        }
        Logger.error(LogCategory.LLM, "AI counter-response generation failed for ${player.name}: ${e.message}")
        throw e
    }
}