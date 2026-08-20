package agent.runners

import agent.builders.judgeOutcome.buildNpcJudge
import agent.builders.judgeOutcome.StatBuff
import agent.builders.judgeOutcome.MultiActorStatChanges
import agent.builders.validateAction.buildNPCValidator
import agent.builders.writingAgent.buildNeoWritingAgent
import agent.math.GameMath
import com.TTT.Config.TPipeConfig
import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.extractJson
import com.TTT.Util.writeStringToFile
import gameState.WorldManager
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.server.UiSignalRpcHandlers
import org.ttt.autogenesis.server.TurnHarness
import org.ttt.autogenesis.server.ActionHistoryRpcHandlers
import org.ttt.autogenesis.network.ResolutionStep
import structs.GameHistory
import structs.Npc
import agent.builders.validateAction.ActionTargetType
import agent.builders.validateAction.ActionTargetTypeObj
import agent.builders.validateAction.ActionIntent
import agent.builders.validateAction.buildTargetDetectorAgent
import agent.builders.validateAction.PlayType
import agent.builders.validateAction.PlayTypeObj
import agent.builders.validateAction.buildPlayDetectionAgent
import agent.builders.validateAction.buildCounterResponseIntentDetector
import agent.builders.validateAction.CounterResponseIntent
import agent.builders.validateAction.buildDefensiveValidator
import agent.builders.validateAction.resolveTerritoryTargets
import agent.builders.judgeOutcome.`Victory?`
import agent.builders.judgeOutcome.Results
import structs.Player
import bedrockPipe.BedrockPipe
import org.ttt.autogenesis.server.AgentWorkStreamDispatcher
import org.ttt.autogenesis.server.streamPipelineOutputToAgentWorkBuffer
import org.ttt.autogenesis.server.getAllConnectedClientIds
import agent.runners.getTurnTraceDir
import com.TTT.Pipeline.Pipeline
import java.io.File
import enums.NpcType
import agent.managers.GameResponseManager
import agent.builders.writingAgent.buildResponseRefinementAgent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CoroutineScope
import agent.builders.AgentCoroutineScope
import accounting.PromptManager

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
 *
 * @visibleForTesting
 */
open class NpcNarrativeChunkThrottler(
    private val flushDelayMs: Long = NARRATIVE_STREAM_FLUSH_DELAY_MS,
    private val bufferLimit: Int = NARRATIVE_STREAM_BUFFER_LIMIT,
    private val scope: CoroutineScope = AgentCoroutineScope.scope
)
{
    private val buffer = StringBuilder()
    private val mutex = Mutex()
    private var flushJob: Job? = null

    /** Override scope in tests to use a test-controlled scope. */
    protected open val coroutineScope: CoroutineScope get() = scope

    /**
     * Appends newly emitted text and triggers either an immediate flush (when the buffer limit is hit)
     * or schedules a delayed flush if the buffer is still growing.
     *
     * @param chunk The partial narrative text produced by a Bedrock "writing pipe" callback.
     */
    suspend fun append(chunk: String)
    {
        if (chunk.isEmpty()) return

        var flushNow = false
        var scheduleTimer = false
        mutex.withLock {
            buffer.append(chunk)
            if (buffer.length >= bufferLimit)
            {
                flushJob?.cancel()
                flushJob = null
                flushNow = true
            }
            else if (flushJob?.isActive != true)
            {
                scheduleTimer = true
            }
        }

        if (flushNow)
        {
            flushPending()
        }
        else if (scheduleTimer)
        {
            scheduleDelayedFlush()
        }
    }

    private fun scheduleDelayedFlush()
    {
        if (flushDelayMs <= 0L)
        {
            coroutineScope.launch { flushPending() }
            return
        }

        flushJob = coroutineScope.launch {
            delay(flushDelayMs)
            flushPending()
        }
    }

    /**
     * Forces any buffered text to be sent immediately, cancelling any pending delayed flush.
     * Use this at turn transitions to flush without waiting for the timer.
     */
    suspend fun flushNow() {
        val chunkToSend: String? = mutex.withLock {
            flushJob?.cancel()
            flushJob = null
            if (buffer.isEmpty()) return@withLock null
            val text = buffer.toString()
            buffer.setLength(0)
            text
        }
        chunkToSend?.let { broadcastChunk(it, isComplete = false) }
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
            if (buffer.isEmpty()) return@withLock ""
            val text = buffer.toString()
            buffer.setLength(0)
            text
        }

        if (chunkToSend.isEmpty()) return
        broadcastChunk(chunkToSend, isComplete)
    }

    /**
     * Broadcasts a narrative chunk. Override in tests to intercept without the full server stack.
     * In production, this calls [UiSignalRpcHandlers.broadcastNarrativeChunk].
     */
    protected open suspend fun broadcastChunk(chunk: String, isComplete: Boolean) {
        UiSignalRpcHandlers.broadcastNarrativeChunk(chunk, isComplete)
    }

    /**
     * Returns true if there is buffered content that has not yet been flushed.
     * For testing only.
     */
    fun hasBufferedContent(): Boolean = buffer.isNotEmpty()

    /**
     * Resets the throttler state between narrative turns.
     * Clears the buffer and cancels any pending flush job so content from
     * a previous turn does not appear "stuck" when a new turn begins.
     */
    suspend fun reset() {
        mutex.withLock {
            flushJob?.cancel()
            flushJob = null
            buffer.setLength(0)
        }
    }
}

/**
 * Helper to wire a Bedrock pipe into a shared throttler so every agent can reuse the buffering logic.
 *
 * This extension keeps the streaming setup consistent and prevents multiple throttler instances from
 * competing for the same `UiSignalRpcHandlers.broadcastNarrativeChunk` execution path.
 */
private fun BedrockPipe.enableBufferedNarrativeStreaming(throttler: NpcNarrativeChunkThrottler)
{
    enableStreaming()
    val callback: suspend (String) -> Unit = { chunk -> throttler.append(chunk) }
    setStreamingCallback(callback)
}


/**
 * Maps raw pipeline step names (from TPipe) to user-friendly progress messages displayed in the UI.
 * This ensures the player sees "Analyzing intent..." instead of "Target Detector Pipe".
 * Used by [attachProgressHooks].
 */
private val pipeProgressMap = mapOf(
    // NPC Validator
    "NPC legality checker pipe" to "Checking action legality...",
    "NPC legality rectifier pipe" to "Aligning action with narrative rules...",
    "NPC style reapply pipe" to "Finalizing action formatting...",
    // Play & Target Detection
    "Play Detection Agent" to "Analyzing NPC intent...",
    "Target Detector Pipe" to "Identifying action targets...",
    "Target Refinement Pipe" to "Refining target locations...",
    "Target Fallback Pipe" to "Retrying target identification...",
    // Counter-Play Refinement
    "Response Detection Pipe" to "Analyzing player response...",
    "Response Refinement Pipe" to "Refining defensive prose...",
    // Narrative Writing
    "simulation story / genesis" to "Constructing initial world state...",
    "chapter goals" to "Determining narrative objectives...",
    "Narrative Beat Planner" to "Planning story events...",
    "Creative Prose Engine" to "Generating story text...",
    // NPC Judging
    "npc success judgment pipe" to "Adjudicating action outcome...",
    "npc gains and losses pipe" to "Calculating resource changes...",
    "npc karma pipe" to "Evaluating moral impact...",
    "npc stat change pipe" to "Updating character stats...",
    // Refusal / Retry Fallbacks
    "Refusal Detection Pass/Fail (Palmyra Fallback)" to "Retrying success judgment...",
    "Refusal Detection Gains/Losses (Palmyra Fallback)" to "Retrying consequence calculation...",
    "Refusal Detection Karma (Palmyra Fallback)" to "Retrying moral evaluation...",
    // Neo Writing Agent
    "guide pipe" to "Analyzing narrative vector...",
    "distill guide pipe" to "Optimizing story path...",
    "writing pipe" to "Generating final prose...",
    "selection pipe branch repair" to "Repairing narrative logic..."
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
    if (targetType.type == ActionTargetType.Territory && targetType.targets.size > 1)
    {
        val filtered = targetType.targets.first()
        Logger.info(LogCategory.SYSTEM, "Enforcing single-territory targeting: filtered ${targetType.targets.size} targets to '${filtered}' (dropped: ${targetType.targets.drop(1).joinToString(", ")})")
        return targetType.copy(targets = listOf(filtered))
    }
    return targetType
}

/**
 * Attaches a callback to the provided [Pipeline] to intercept step completion events.
 * These events are then broadcast to the client UI as progress updates using [UiSignalRpcHandlers.broadcastProgressBar].
 *
 * @param pipeline The TPipe [Pipeline] instance to monitor.
 * @param baseStepIndex The UI step index (e.g. ResolutionStep.STORY = 3) associated with this pipeline's execution.
 *                      This ensures the progress bar updates happen on the correct "page" of the UI.
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

        Logger.info(LogCategory.GENERAL, "Pipe Completion Callback Hit: '${pipe.pipeName}'. Mapped Msg: $progressMsg")
        if(progressMsg != null)
        {
            UiSignalRpcHandlers.broadcastProgressBar(baseStepIndex, progressMsg)
        }
        else
        {
            Logger.info(LogCategory.GENERAL, "No progress message mapped for pipe: '${pipe.pipeName}'")
        }
    }
}

private fun sanitizeTraceComponent(value: String): String
{
    return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

/**
 * Orchestrates an NPC's turn in the game loop.
 *
 * This function is the central brain for NPC execution. It manages the entire lifecycle of an NPC's action:
 * 1. **Validation**: Checks if the action is legal within game rules using [buildNPCValidator].
 * 2. **Intent Parsing**: Determines what the NPC is trying to do (Play Type) and to whom (Target) using [buildPlayDetectionAgent] and [buildTargetDetectorAgent].
 * 3. **Counter-Play**: If the target is a player, it suspends execution to allow the player to respond (See "Counter-Play Logic").
 * 4. **Narrative Generation**: Uses [buildWritingAgent] to craft the story of the action.
 * 5. **Judgement**: Uses [buildNpcJudge] to decide the outcome and consequences based on the narrative.
 * 6. **State Update**: Commits changes to [WorldManager] and [GameHistory].
 *
 * **Edge Cases:**
 * - **Demo Mode**: If [PromptManager.isDemoMode] is true, target detection may be overridden to ensure specific interactions (e.g. targeting "Commander Shepard") for showcase purposes.
 * - **Sabotage**: If validation fails critically (e.g. "force_fail_action" flag), the action is allowed to proceed narratively but structurally guaranteed to fail.
 *
 * @param npc The [Npc] entity executing the turn.
 * @param turnAction The raw natural language action string generated by the NPC's high-level planner (or hardcoded in demo).
 * @param originConnectionId Player connection that triggered this NPC turn (if available).
 */
suspend fun executeNpcTurn(npc: Npc, turnAction: String, originConnectionId: String? = null)
{
    val isDemo = PromptManager.isDemoMode
    val recipientIds = getAllConnectedClientIds()

    // Set trace folder for NPC turn
    val npcTurnFolderName = "Round_${WorldManager.world.roundNumber}_Turn_${WorldManager.world.turnOrder.indexOf(npc.name).coerceAtLeast(0)}_${npc.name.replace(" ", "_")}"
    agent.runners.setCurrentTurnFolderName(npcTurnFolderName)

    try {
    Logger.info(LogCategory.GENERAL, "executeNpcTurn: Entry - npc.name='${npc.name}', npc.type=${npc.type}, isDemo=$isDemo")
    Logger.debug(LogCategory.GENERAL, "executeNpcTurn: NPC data - personality='${npc.personality}', abilities='${npc.abilities}', history='${npc.history}'")
    Logger.debug(LogCategory.GENERAL, "executeNpcTurn: NPC stats - militaryReadiness=${npc.militaryReadiness}, legitimacy=${npc.legitimacy}, stagnation=${npc.stagnation}")
    Logger.debug(LogCategory.GENERAL, "executeNpcTurn: NPC assets - territories=${npc.capturedTerritory.size}, resources=${npc.resources.size}, pointValue=${npc.pointValue}")
    Logger.debug(LogCategory.GENERAL, "executeNpcTurn: Turn action - '$turnAction'")
    
    Logger.info(
        LogCategory.NETWORK,
        "[NPC_STREAM] Turn origin='${originConnectionId ?: "none"}', recipients=${recipientIds.size}${if(recipientIds.isEmpty()) "" else ", ids=${recipientIds.joinToString(",")}"}"
    )

    /**
     * Top level declaration. If true this will always flip any play that's a success into a negative direction.
     */
    var alwaysFailPlayerAction = false

    // 1. NPC Validation and Rectification
    // We only enforce strict narrative validation on "Active" NPCs who are participants, not high-level threats.
    val finalActionText: String

    // Ensure the displayed action always starts with the NPC name for clarity
    val displayAction = if(turnAction.startsWith(npc.name, ignoreCase = true)) turnAction else "${npc.name} $turnAction"

    Logger.info(LogCategory.NETWORK, "Broadcasting step: PLAYER_ACTION")
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.PLAYER_ACTION, displayAction)

    if(npc.type == enums.NpcType.Active)
    {
        val npcValidator = buildNPCValidator().apply {
            enableTracing()
            enablePipeTimeout(
                applyRecursively = true,
                duration = 180000,
                autoRetry = true,
                retryLimit = 5
            )
            init(true)
            streamPipelineOutputToAgentWorkBuffer(recipientIds, this)
        }
        attachProgressHooks(npcValidator, 1)

        Logger.info(LogCategory.GENERAL, "Validating NPC action legality for Active NPC...")
        UiSignalRpcHandlers.broadcastProgressBar(1, "Validating NPC action...")
        val validatedActionOutput = npcValidator.execute(MultimodalContent(turnAction))
        finalActionText = validatedActionOutput.text.ifEmpty { turnAction }

        val validatorTrace = npcValidator.getTraceReport(TraceFormat.JSON)
        saveSystemTrace("NPC_Validation", npcValidator)
    }
    else
    {
        Logger.info(LogCategory.GENERAL, "Bypassing validation for high-level NPC type: ${npc.type}")
        UiSignalRpcHandlers.broadcastProgressBar(1, "Processing action...")
        delay(3000)
        finalActionText = turnAction
    }

    // Broadcast to all clients to force them into the Turn Resolution Widget now that we kick off
    org.ttt.autogenesis.server.UiSignalRpcHandlers.broadcastForceShowTurnResolution()

    // --- INTENT BROADCAST START ---
    Logger.info(LogCategory.GENERAL, "Broadcasting NPC Intent Early...")
    val intentHistory = GameHistory(
        turnPlayer = npc.name,
        turnAction = finalActionText,
        turnResult = "(Planning...)"
    )
    val turnId = intentHistory.id
    ActionHistoryRpcHandlers.broadcastTurnComplete(intentHistory)
    // --- INTENT BROADCAST END ---

    // 1.5: Play and Target Detection
    val playDetector = buildPlayDetectionAgent(npc).apply {
        enableTracing()
        enablePipeTimeout(
            applyRecursively = true,
            duration = 180000,
            autoRetry = true,
            retryLimit = 5
        )
        init(true)
        streamPipelineOutputToAgentWorkBuffer(recipientIds, this)
    }
    attachProgressHooks(playDetector, 1) // Step 1.5 still grouped with 1 in some UI contexts
    val playOutput = playDetector.execute(MultimodalContent(finalActionText))
    
    val playTypeTrace = playDetector.getTraceReport(TraceFormat.JSON)
    saveSystemTrace("NPC_PlayType", playDetector)
    
    val playType = extractJson<PlayTypeObj>(playOutput.text) ?: PlayTypeObj()
    
    
    Logger.info(LogCategory.GENERAL, "CHECKPOINT: Target Detection Started")
    val targetDetector = buildTargetDetectorAgent(npc).apply {
        enableTracing()
        enablePipeTimeout(
            applyRecursively = true,
            duration = 180000,
            autoRetry = true,
            retryLimit = 5
        )
        init(true)
        streamPipelineOutputToAgentWorkBuffer(recipientIds, this)
    }
    attachProgressHooks(targetDetector, 2)
    Logger.info(LogCategory.NETWORK, "Broadcasting step: INTENT")
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.INTENT, "Analyzing NPC Intent...")
    UiSignalRpcHandlers.broadcastProgressBar(2, "Detecting NPC target...")
    var targetOutput = targetDetector.execute(MultimodalContent(finalActionText))

    saveSystemTrace("NPC_TargetDetectors", targetDetector)

    // Retry Logic for Target Detector
    Logger.debug(LogCategory.GENERAL, "Extracting error data for NPC targetDetector")
    val targetErrorData = fetchRetryLogs()
    if(!targetErrorData.isEmpty())
    {
        Logger.debug(LogCategory.GENERAL, "Retrying NPC target detector with fallback models")
        swapPipelineModels(targetDetector)
        targetOutput = targetDetector.execute(MultimodalContent(finalActionText))
        saveSystemTrace("NPC_TargetDetectors_Retry", targetDetector)
        val logRetry = fetchRetryLogs()

        if(!logRetry.isEmpty())
        {
            Logger.error(LogCategory.GENERAL, "Failed to retry NPC target detector")
            throw Exception("${logRetry.failureReason}")
        }
    }

    val detectedTargets = enforceSingleTerritoryTarget(extractJson<ActionTargetTypeObj>(targetOutput.text) ?: ActionTargetTypeObj())
    val targetType = resolveTerritoryTargets(detectedTargets, npc.name, WorldManager.world.mapTiles)
    Logger.info(LogCategory.GENERAL, "CHECKPOINT: Target Detection Finished. Type: ${targetType.type}")
    
    // Sabotage Check:
    // If the target detection pipeline flagged "force_fail_action" (usually due to nonsensical targets),
    // we set a flag to ensure the final outcome is FAILURE, regardless of what the Judge says later.
    // This allows the narrative to play out (for comedy or drama) but enforces rules.
    val sabotage = targetDetector.miniBank.contextMap["force_fail_action"]
    if(sabotage != null)
    {
        alwaysFailPlayerAction = true
        Logger.info(LogCategory.GENERAL, "NPC Action sabotaged due to invalid target validation.")
    }

    // --- DEMO MODE OVERRIDES ---
    if(isDemo)
    {
        Logger.info(LogCategory.GENERAL, "DEMO MODE: Running Target Override Logic...")
        // Force target Shepherd if it's a Player target type but no valid player was found, 
        // or if we want to ensure the developer sees the prompt.
        if(targetType.type == ActionTargetType.Player && targetType.targets.isEmpty())
        {
            Logger.info(LogCategory.GENERAL, "DEMO MODE: No player target found. Forcing target to 'Commander Shepard' for UI testing.")
            targetType.targets = listOf("Commander Shepard")
        }
        
        // Verbose dump of target detection
        val logMsg = "DEMO MODE: Target Type: ${targetType.type}, Targets: ${targetType.targets.joinToString()}"
        Logger.info(LogCategory.GENERAL, logMsg)
        File(System.getenv("NPC_DEBUG_LOG") ?: "/tmp/debug_npc.log").appendText("$logMsg\n")

        // Force target Shepherd if we failed to identify a valid target connection
        if(targetType.type == ActionTargetType.NoTarget || targetType.type == ActionTargetType.Abstract || (targetType.type == ActionTargetType.Player && targetType.targets.isEmpty()))
        {
            val overrideMsg = "DEMO MODE: Target Invalid/Empty. OVERRIDING to 'Commander Shepard' for UI testing."
            Logger.info(LogCategory.GENERAL, overrideMsg)
            File(System.getenv("NPC_DEBUG_LOG") ?: "/tmp/debug_npc.log").appendText("$overrideMsg\n")
            targetType.type = ActionTargetType.Player
            targetType.targets = listOf("Commander Shepard")
        }
    }

    val primaryTargetName = targetType.targets.firstOrNull() ?: ""
    val targetActor = when(targetType.type)
    {
        ActionTargetType.Player -> WorldManager.world.findPlayerByName(primaryTargetName)
        ActionTargetType.Npc -> WorldManager.world.findNpcByName(primaryTargetName)
        ActionTargetType.Territory ->
        {
            val territory = WorldManager.world.mapTiles.find { it.name.equals(primaryTargetName, ignoreCase = true) }
            if(territory != null && territory.ruler.isNotBlank())
            {
                WorldManager.world.findPlayerByName(territory.ruler) ?: WorldManager.world.findNpcByName(territory.ruler)
            }
            else null
        }
        else -> null
    }

    if(targetActor != null)
    {
        Logger.info(LogCategory.GENERAL, "NPC Primary Target identified: ${targetActor.getInternals().name}")
        
        // --- UI UX: Show Identified Target ---
        Logger.info(LogCategory.NETWORK, "Broadcasting step: INTENT (Target Identified)")
        UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.INTENT, "Target Identified: ${targetActor.getInternals().name}")
        Logger.info(LogCategory.GENERAL, "Pausing for 6 seconds to allow player to see target intent...")
        delay(6000)
        // -------------------------------------
    }

    // --- COUNTER-PLAY / RESPONSE LOGIC ---
    val counterPlayResult = handleNpcCounterPlay(
        npc = npc,
        finalActionText = finalActionText,
        targetType = targetType,
        playType = playType,
        intentHistory = intentHistory,
        isDemo = isDemo,
        recipientIds = recipientIds
    )
    val aggregatedResponseProse = counterPlayResult.aggregatedResponseProse
    val counterResponsesList = counterPlayResult.counterResponsesList
    val fairnessDefenders = counterPlayResult.defendingPlayers
    val fairnessScopeApplied = counterPlayResult.fairnessScopeApplied
    Logger.info(LogCategory.GENERAL, "[NPC_COUNTERPLAY] Collected ${counterResponsesList.size} counter-responses (fairnessScopeApplied=$fairnessScopeApplied)")

    if(counterResponsesList.isNotEmpty())
    {
        val counterPlayUpdate = intentHistory.copy(
            turnResult = "(Defenders responded)",
            counterResponses = counterResponsesList.toMutableList()
        )
        ActionHistoryRpcHandlers.broadcastTurnComplete(counterPlayUpdate)
        UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.COUNTER_PLAY)
        delay(2000)
    }
    // --- END COUNTER-PLAY LOGIC ---

    // --- UI UX: Show Validated Intent ---
    // Show the player what the NPC is doing before we start generating the story.
    // This gives weight to the decision.
    Logger.info(LogCategory.NETWORK, "Broadcasting step: INTENT (Show Action Text)")
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.INTENT, "Resolving Outcome for: $finalActionText")
    Logger.info(LogCategory.GENERAL, "Pausing for 4 seconds to allow player to read intent...")
    delay(4000)
    // ------------------------------------

    // 2. Sequential Execution (Narrative -> Judge)
    // We run the narrative FIRST so the Judge can evaluate the actual story events.

    // A. Writing Agent: Generates the story for the turn.
    // Combine effectiveTurnAction and responseProse for the new agent input
    val narrativeInput = if (aggregatedResponseProse.isNotEmpty()) "$finalActionText. $aggregatedResponseProse" else finalActionText
    
    val narrativeAgent = buildNeoWritingAgent(npc, WorldManager.activeWritingAgentConfig)

    Logger.info(LogCategory.GENERAL, "CHECKPOINT: Narrative Generation Started")
    Logger.info(LogCategory.GENERAL, "Generating NPC Narrative...")
    Logger.info(LogCategory.NETWORK, "Broadcasting step: STORY")
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.STORY)
    Logger.info(LogCategory.NETWORK, "Broadcasting progress bar: STORY (3)")
    UiSignalRpcHandlers.broadcastProgressBar(3, "Generating NPC narrative...")

    // Hook up live narrative streaming with throttler to prevent memory leaks.
    // Create BEFORE broadcastPrepareStory so we can flush any leftover content from the previous turn.
    val narrativeThrottler = NpcNarrativeChunkThrottler()
    narrativeAgent.getPipes().filterIsInstance<BedrockPipe>().find { it.pipeName.contains("writing pipe", true) }?.enableBufferedNarrativeStreaming(narrativeThrottler)

    // Flush any leftover content from previous turn BEFORE clearing screen.
    // This ensures old narrative chunks don't appear stuck when a new turn begins.
    narrativeThrottler.flushNow()
    UiSignalRpcHandlers.broadcastPrepareStory() // Clear screen for new story

    // Execute Narrative Pipeline
    narrativeAgent.enableTracing()
    narrativeAgent.enablePipeTimeout(
        applyRecursively = true,
        duration = 180000,
        autoRetry = true,
        retryLimit = 5
    )
    narrativeAgent.init(true)
    streamPipelineOutputToAgentWorkBuffer(recipientIds, narrativeAgent)
    attachProgressHooks(narrativeAgent, 3)

    val narrativeOutput = narrativeAgent.execute(MultimodalContent(narrativeInput))
    narrativeThrottler.flushPending()
    val narrativeResult = narrativeOutput.text.ifEmpty { "The NPC acts in the shadows." }

    saveSystemTrace("NPC_Narrative", narrativeAgent)

    // Update story lorebook with entities from NPC narrative
    if(narrativeResult.isNotBlank() && narrativeResult != "The NPC acts in the shadows.")
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
            streamPipelineOutputToAgentWorkBuffer(recipientIds, this)
            attachProgressHooks(this, 3)
        }
        lorebookAgent.execute(MultimodalContent(narrativeResult))
        saveSystemTrace("NPC_LorebookUpdate", lorebookAgent)    }

    // Complete Narrative Streaming
    narrativeThrottler.flushPending(isComplete = true)

    // B. NPC Judge: Pass targetActor AND the generated narrative for context
    Logger.info(LogCategory.GENERAL, "CHECKPOINT: Narrative Generation Finished. Initializing NPC Judgement Phase...")
    
    // --- UI FIX: Transition to 'Judgement Processing' screen ---
    Logger.info(LogCategory.NETWORK, "Broadcasting step: JUDGEMENT (Processing) for sequential processing")
    // Broadcast JUDGEMENT step. The client's JudgementSummaryPage will reset to a 'Processing' state (spinner)
    // until the final results are sent.
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.JUDGEMENT, "Resolving Outcome for: $finalActionText")
    // -------------------------------------------------------------------------------------

    Logger.info(LogCategory.NETWORK, "Broadcasting progress bar: JUDGING (4)")
    UiSignalRpcHandlers.broadcastProgressBar(4, "Judging NPC actions...")

    // Store play type and success for Judge access
    val playTypeContext = mapOf(
        "playType" to playType.type.name,
        "wasSuccessful" to true // NPC actions are assumed successful at this point
    )
    val playTypeWindow = ContextWindow().apply {
        contextElements.add(com.TTT.Util.serialize(playTypeContext))
    }
    ContextBank.emplace("play_type_context", playTypeWindow)
    Logger.debug(LogCategory.GENERAL, "Stored play_type_context for NPC: ${playType.type.name}")

    // Mid-turn music reroll (same rationale as the player-turn reroll:
    // refresh the random layer tracks so the music stays fresh while
    // the judge agent runs). No-op for scenario-bound (initial /
    // nemesis / terminal) picks — see
    // [MusicSelector.reselectRandomLayers]. Note that NPC turns for
    // Nemesis / Elder God actors always start with the nemesis
    // scenario track (rule 2), so the reroll will be a no-op on those
    // turns by design — we do NOT want to swap the nemesis track out
    // for random layers mid-turn.
    TurnHarness.selectAndBroadcastMusicReroll()

    val judgeAgent = buildNpcJudge(npc, targetActor, targetType)
    judgeAgent.enableTracing()
    judgeAgent.enablePipeTimeout(
        applyRecursively = true,
        duration = 180000,
        autoRetry = true,
        retryLimit = 5
    )
    judgeAgent.init(true)
    streamPipelineOutputToAgentWorkBuffer(recipientIds, judgeAgent)
    attachProgressHooks(judgeAgent, 4)

    // Execute Judge Pipeline with the NARRATIVE as input
    judgeAgent.execute(MultimodalContent(narrativeResult))

    // Synchronously apply results to the world state.
    val contextKey = "npcJudgeResult-${npc.name}"
    val resultContext = ContextBank.getContextFromBank(contextKey)
    val judgingResult = if(!resultContext.isEmpty()) {
         resultContext.findLoreBookEntry("result")?.value?.let { extractJson<agent.builders.judgeOutcome.Results>(it) }
    } else null

    if(judgingResult != null)
    {
        Logger.info(LogCategory.GENERAL, "Applying NPC Judge Results synchronously for ${npc.name}...")
        WorldManager.applyNpcJudgeResults(npc.name, judgingResult)
        
        // Validate research play stat buffs for NPCs
        if (playType.type == PlayType.Research) {
            val npcStatChanges = (judgeAgent.pipeMetaData["actor stat changes"] as? MultiActorStatChanges)
                ?.changes?.get(npc.name)
            
            val hasStatBuff = npcStatChanges?.let { buff ->
                buff.militaryReadiness != 0 || buff.legitimacy != 0 || buff.stagnation != 0
            } ?: false
            
            if (!hasStatBuff) {
                Logger.warn(LogCategory.GENERAL, "Successful Research play for NPC ${npc.name} has no stat buffs. Applying fallback.")
                applyNpcFallbackResearchBuff(npc)
            } else {
                Logger.info(LogCategory.GENERAL, "Research play stat buffs validated for NPC ${npc.name}")
            }
        }
    }

    val karmaKey = "npcKarmaResult-${npc.name}"
    val karmaContext = ContextBank.getContextFromBank(karmaKey)
    if(!karmaContext.isEmpty())
    {
        val isPositive = karmaContext.findLoreBookEntry("isPositive")?.value?.toBoolean() ?: true
        Logger.info(LogCategory.GENERAL, "Applying NPC Karma Result synchronously for ${npc.name}...")
        WorldManager.applyKarmaChange(isPositive)
    }

    saveSystemTrace("NPC_Judge", judgeAgent)
    Logger.info(LogCategory.GENERAL, "CHECKPOINT: Judgement Finished and Applied.")

    // 4. Extraction and History Commit
    // Extract actual success from judge outcome in ContextBank
    val judgeContext = ContextBank.getContextFromBank("npcJudgeOutcome")
    var judgeContextSuccess = if(!judgeContext.isEmpty())
    {
        val entry = judgeContext.findLoreBookEntry("victory")
        entry?.value?.let { extractJson<`Victory?`>(it) }?.isVictory ?: true
    }
    else
    {
        true
    }

    if(fairnessScopeApplied && fairnessDefenders.isNotEmpty())
    {
        val riskLevel = when
        {
            targetType.intentMismatch -> 80
            targetType.targets.size > 1 -> 65
            else -> 45
        }
        val fairnessOutcome = GameMath.resolveNpcVsPlayerConflict(
            npc = npc,
            defenders = fairnessDefenders,
            playType = playType.type,
            isSimulatedSuccess = judgeContextSuccess,
            usedAssets = emptyList(),
            riskLevel = riskLevel
        )
        judgeContextSuccess = fairnessOutcome.finalSuccess
        Logger.info(
            LogCategory.GENERAL,
            "[NPC_FAIRNESS] npcPressure=${fairnessOutcome.npcPressure}, defenderPressure=${fairnessOutcome.defenderPressure}, score=${fairnessOutcome.totalScore}, statVictory=${fairnessOutcome.statVictory}, final=${fairnessOutcome.finalSuccess}"
        )
    }

    // Final Success check (Sabotage override)
    val finalSuccess = if (alwaysFailPlayerAction) false else judgeContextSuccess
    
    // --- UI SIGNAL FIX: Broadcast Judgement Result ---
    Logger.info(LogCategory.GENERAL, "Broadcasting NPC Judgement UI Signal...")
    Logger.info(LogCategory.NETWORK, "Broadcasting step: JUDGEMENT")
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.JUDGEMENT)
    val resultHeader = if(finalSuccess) "SUCCESS" else "FAILURE"
    val resultSubtext = if(finalSuccess) "${npc.name} successfully executed their plans." else "${npc.name} failed to execute their plans."
    Logger.info(LogCategory.NETWORK, "Broadcasting Judgement Result data...")
    UiSignalRpcHandlers.broadcastJudgementResult(finalSuccess, resultHeader, resultSubtext)
    // ------------------------------------------------

    // Wait for player to read the judgement
    Logger.info(LogCategory.GENERAL, "Starting judgement delay (6000ms)...")
    delay(6000)
    Logger.info(LogCategory.GENERAL, "Judgement delay finished.")

    // --- Step 5.5: Dispatch Resources ---
    // Visualize the logistical transfer of assets/territory
    Logger.info(LogCategory.GENERAL, "Initiating NPC Dispatch Step...")
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.DISPATCH)
    UiSignalRpcHandlers.broadcastProgressBar(5, "Dispatching assets...")
    
    // NPCs currently don't have granular resource tracking in their judge pipeline yet, so we send empty for now.
    // Wrap in independent scope to avoid compiler limits
    agent.builders.AgentCoroutineScope.scope.launch {
        // judgingResult is already fetched and applied synchronously above.
        // We use it here to visualize the transfer in the UI.

        val usedAssets = emptyList<String>()
        val gainedAssets = judgingResult?.assetsGained ?: emptyList()
        val lostAssets = judgingResult?.assetsLost ?: emptyList()
        val territoryGained = judgingResult?.territoryGained ?: emptyList()
        val territoryLost = judgingResult?.territoryLost ?: emptyList()

        org.ttt.autogenesis.server.DispatchRpcHandler.broadcastDispatchResult(
            usedAssets = usedAssets,
            gainedAssets = gainedAssets,
            lostAssets = lostAssets,
            territoryGained = territoryGained,
            territoryLost = territoryLost,
            territoryExchanges = judgingResult?.territoryExchanges?.map { structs.TerritoryExchange(it.territoryName, it.from, it.to) } ?: emptyList()
        ) 
    }
    
    Logger.info(LogCategory.GENERAL, "Dispatch step delay (4000ms)...")
    delay(4000)

    // 4.5: Deterministic Stat Updates for the NPC (Primary Actor)
    when(playType.type)
    {
        PlayType.Military ->
        {
            val delta = if(finalSuccess) 15 else -15
            npc.militaryReadiness = (npc.militaryReadiness + delta).coerceIn(0, 100)
            Logger.info(LogCategory.GENERAL, "NPC Military Readiness changed by $delta to ${npc.militaryReadiness}")
        }
        PlayType.Diplomatic ->
        {
            val delta = if(finalSuccess) 15 else -15
            npc.legitimacy = (npc.legitimacy + delta).coerceIn(0, 100)
            Logger.info(LogCategory.GENERAL, "NPC Legitimacy changed by $delta to ${npc.legitimacy}")
        }
        PlayType.Research ->
        {
            if(finalSuccess)
            {
                npc.stagnation = (npc.stagnation - 15).coerceAtLeast(0)
                Logger.info(LogCategory.GENERAL, "NPC Stagnation reduced to ${npc.stagnation}")
            }
        }
        else -> {}
    }

    Logger.info(LogCategory.GENERAL, "Committing NPC History for ${npc.name}...")
    
    val historyResultText = judgingResult?.resultSummary?.takeIf { it.isNotBlank() }
        ?: if (finalSuccess) "Success" else "Failure"

    val turnHistory = GameHistory(
        turnPlayer = npc.name,
        turnAction = finalActionText,
        turnStory = narrativeResult,
        wasPlayerSuccessful = finalSuccess,
        turnResult = historyResultText,
        counterResponses = counterResponsesList.toMutableList(),
        targetIntent = targetType.actionIntent.name,
        targetEntities = targetType.targets.toMutableList(),
        id = turnId // REUSE THE SAME ID to update the existing client entry
    )

    if (judgingResult != null) {
        turnHistory.territoryGained.addAll(judgingResult.territoryGained)
        turnHistory.territoryLost.addAll(judgingResult.territoryLost)
        turnHistory.resourcesWon.addAll(judgingResult.assetsGained)
        turnHistory.resourcesLost.addAll(judgingResult.assetsLost)
        turnHistory.territoryExchanges.addAll(judgingResult.territoryExchanges.map { structs.TerritoryExchange(it.territoryName, it.from, it.to) })
        turnHistory.assetExchanges.addAll(judgingResult.assetExchanges.map { structs.AssetExchange(it.assetName, it.from, it.to) })
        turnHistory.affectedPlayers.putAll(calculateAffectedPlayers(npc.name, judgingResult))

        val statBuffsToDisplay = mutableMapOf<String, String>()
        val npcStatChanges = (judgeAgent.pipeMetaData["actor stat changes"] as? agent.builders.judgeOutcome.MultiActorStatChanges)
        
        npcStatChanges?.changes?.forEach { (actorName, buff) ->
            val parts = mutableListOf<String>()
            if(buff.luckPoints != 0) parts.add("${if(buff.luckPoints > 0) "+" else ""}${buff.luckPoints} Luck")
            if(buff.reputation != 0) parts.add("${if(buff.reputation > 0) "+" else ""}${buff.reputation} Reputation")
            if(buff.might != 0) parts.add("${if(buff.might > 0) "+" else ""}${buff.might} Might")
            if(buff.wealth != 0) parts.add("${if(buff.wealth > 0) "+" else ""}${buff.wealth} Wealth")
            if(buff.militaryReadiness != 0) parts.add("${if(buff.militaryReadiness > 0) "+" else ""}${buff.militaryReadiness} Readiness")
            if(buff.legitimacy != 0) parts.add("${if(buff.legitimacy > 0) "+" else ""}${buff.legitimacy} Legitimacy")
            if(buff.stagnation != 0) parts.add("${if(buff.stagnation > 0) "+" else ""}${buff.stagnation} Stagnation")
            
            if(parts.isNotEmpty())
            {
                statBuffsToDisplay[actorName] = parts.joinToString(", ")
            }
        }
        turnHistory.statBuffsGained.putAll(statBuffsToDisplay)
    }

    turnHistory.thinkingUpdates.addAll(WorldManager.consumePendingThinking(WorldManager.world.roundNumber))

    WorldManager.history.add(turnHistory)
    Logger.info(LogCategory.NETWORK, "Broadcasting TurnComplete")
    ActionHistoryRpcHandlers.broadcastTurnComplete(turnHistory)
    Logger.info(LogCategory.NETWORK, "Broadcasting step: UPDATE_WORLD")
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.UPDATE_WORLD)
    UiSignalRpcHandlers.broadcastWorldUpdate(WorldManager.world)
    UiSignalRpcHandlers.broadcastProgressBar(7, "NPC turn complete.")
    Logger.info(LogCategory.GENERAL, "NPC Turn Complete for ${npc.name}.")

    // Wait for world update/dispatch animations
    Logger.info(LogCategory.GENERAL, "Starting world update delay (4000ms)...")
    delay(4000)
    Logger.info(LogCategory.GENERAL, "World update delay finished.")

    Logger.info(LogCategory.NETWORK, "Broadcasting step: WAITING")
    UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.WAITING)
    UiSignalRpcHandlers.broadcastCommandInteractive(true)
    } finally {
        // Phase 4 of feature/live-pvp-and-billing: flush the NPC turn to the persistent
        // ledger so the operator dashboard reflects the cost attributed to the NPC's owner.
        // We collect the record (which may be null if no trace was found) and hand it to
        // BillingSync.flushTurnUsage, which applies the cost-class policy and updates the
        // running balance in cloud save.
        val npcRecord = accounting.Billing.recordNpcTurnBilling(npcTurnFolderName, npc.name)
        if (npcRecord != null)
        {
            try
            {
                accounting.BillingSync.flushTurnUsage(listOf(npcRecord))
            }
            catch (err: Throwable)
            {
                org.ttt.autogenesis.logging.Logger.warn(
                    org.ttt.autogenesis.logging.LogCategory.SYSTEM,
                    "npcOrchestrator: failed to flush NPC turn usage to ledger for ${npc.name}: ${err.message}"
                )
            }
        }
        agent.runners.setCurrentTurnFolderName(null)
    }
}

/**
 * Captures an individual defensive response throughout the NPC counter-play cascade.
 *
 * @property player Defender who produced (or was assigned) the response.
 * @property firstPersonResponse UI-facing original response text.
 * @property thirdPersonResponse Narrative-integrated response text.
 * @property source Response source classification (HUMAN, AI_TIMEOUT, AI_UNREACHABLE).
 * @property responseIntent Classified intent of the response used for mismatch detection.
 */
private data class NpcCounterResponseData(
    val player: Player,
    val firstPersonResponse: String,
    val thirdPersonResponse: String,
    val source: String,
    val responseIntent: ActionIntent = ActionIntent.Friendly
)

/**
 * Represents one queue item in the cascading defender response flow.
 *
 * @property attackerName Current attacker identity for this cascade layer.
 * @property attackerAction Action text this layer is reacting to.
 * @property attackerPlayer Optional player reference when a defending player becomes the next attacker in-chain.
 * @property targets Defenders who need to answer this attacker layer.
 * @property cascadeDepth Current recursion depth represented iteratively.
 */
private data class NpcCascadeState(
    val attackerName: String,
    val attackerAction: String,
    val attackerPlayer: Player?,
    val targets: List<Player>,
    val cascadeDepth: Int
)

/**
 * Output of NPC counter-play collection and refinement.
 *
 * @property aggregatedResponseProse Refined prose merged for narrative generation input.
 * @property counterResponsesList Raw-ish responses preserved for UI and history.
 * @property defendingPlayers Player defenders involved at root scope for fairness math.
 * @property fairnessScopeApplied True when this turn qualifies for player-impact fairness mechanics.
 */
private data class NpcCounterPlayResult(
    val aggregatedResponseProse: String,
    val counterResponsesList: List<String>,
    val defendingPlayers: List<Player>,
    val fairnessScopeApplied: Boolean
)

/**
 * Normalizes display names so target matching is resilient to separators and casing.
 *
 * @param value Raw player/target name.
 * @return Canonicalized comparison string.
 */
private fun normalizeTargetName(value: String): String
{
    return value.replace("_", " ").replace("-", " ").trim()
}

/**
 * Builds a temporary player-like view for an NPC attacker so defensive validation can run safely.
 *
 * This proxy exists because [buildDefensiveValidator] expects a [Player] attacker model.
 *
 * @param npc Acting NPC for the current turn.
 * @param attackerName Display name used in prompts/history.
 * @return Lightweight [Player] projection carrying offensive-relevant stats.
 */
private fun buildNpcAttackerProxy(npc: Npc, attackerName: String): Player
{
    val projectedWealth = ((100 - npc.stagnation).coerceAtLeast(0) / 2).coerceAtLeast(0)
    return Player(
        name = attackerName,
        militaryReadiness = npc.militaryReadiness,
        legitimacy = npc.legitimacy,
        stagnation = npc.stagnation,
        might = (npc.militaryReadiness / 2).coerceAtLeast(0),
        reputation = (npc.legitimacy / 2).coerceAtLeast(0),
        wealth = projectedWealth
    )
}

/**
 * Classifies a defensive response intent so mismatch handling can mirror player turn behavior.
 *
 * Falls back to [ActionIntent.Friendly] if detector invocation fails.
 *
 * @param responseText Defender response text to classify.
 * @param recipientIds Agent-work stream recipients for detector execution.
 * @param traceLabel Label suffix for persisted detector traces.
 * @return Detected hostile/friendly intent.
 */
private suspend fun detectNpcResponseIntent(
    responseText: String,
    recipientIds: Collection<String>,
    traceLabel: String
): ActionIntent
{
    Logger.debug(
        LogCategory.LLM,
        "[NPC_COUNTERPLAY] Detecting response intent (traceLabel=$traceLabel, responseLength=${responseText.length}, recipients=${recipientIds.size})"
    )
    return try
    {
        val detector = buildCounterResponseIntentDetector(responseText)
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
            streamPipelineOutputToAgentWorkBuffer(recipientIds, this)
        }

        val result = detectorPipeline.execute(MultimodalContent())
        saveSystemTrace(
            "NPC_CounterResponseIntent_${sanitizeTraceComponent(traceLabel)}",
            detectorPipeline
        )
        val intentObj = extractJson<CounterResponseIntent>(result.text)
        val resolvedIntent = if(intentObj?.intent == "Military") ActionIntent.Hostile else ActionIntent.Friendly
        Logger.debug(LogCategory.LLM, "[NPC_COUNTERPLAY] Intent detector resolved '$resolvedIntent' for traceLabel=$traceLabel")
        resolvedIntent
    }
    catch (e: Exception)
    {
        Logger.warn(LogCategory.LLM, "NPC counter-response intent detection failed: $${e.message}; defaulting to Friendly")
        ActionIntent.Friendly
    }
}

/**
 * Runs NPC counter-play with fairness parity for player-impact actions.
 *
 * Uses reachability checks, AI fallback responders, tiered defensive validation, and cascade propagation.
 * If the turn does not affect players (directly or via owned territory), this returns immediately with parity disabled.
 *
 * @param npc Acting NPC.
 * @param finalActionText Validated NPC action text.
 * @param targetType Target detector output used for scope and cascade routing.
 * @param playType Play-type detector output used for resource gating.
 * @param intentHistory Planning-stage history item updated as responses arrive.
 * @param isDemo Whether demo mode bypasses defender point costs.
 * @param recipientIds Agent-work stream recipients for helper pipelines.
 * @return Aggregated response payload plus defender set for fairness resolution.
 */
private suspend fun handleNpcCounterPlay(
    npc: Npc,
    finalActionText: String,
    targetType: ActionTargetTypeObj,
    playType: PlayTypeObj,
    intentHistory: GameHistory,
    isDemo: Boolean,
    recipientIds: Collection<String>
): NpcCounterPlayResult
{
    Logger.info(
        LogCategory.GENERAL,
        "[NPC_COUNTERPLAY] Start npc=${npc.name}, playType=${playType.type}, targetType=${targetType.type}, targetCount=${targetType.targets.size}, recipients=${recipientIds.size}"
    )
    val fairnessScopeApplied = targetType.type == ActionTargetType.Player || targetType.type == ActionTargetType.Territory
    if(!fairnessScopeApplied)
    {
        Logger.debug(LogCategory.GENERAL, "[NPC_COUNTERPLAY] Skipping advanced counter-play because targetType=${targetType.type}")
        return NpcCounterPlayResult("", emptyList(), emptyList(), false)
    }

    val resolvedOwnerNames = if(targetType.type == ActionTargetType.Territory)
    {
        targetType.targets.mapNotNull { territoryName ->
            val territory = WorldManager.world.mapTiles.find { it.name.equals(territoryName, ignoreCase = true) }
            if(territory != null && territory.ruler.isNotBlank())
            {
                val owner = WorldManager.world.findPlayerByName(territory.ruler) ?: WorldManager.world.findNpcByName(territory.ruler)
                if (owner != null)
                {
                    // PREVENT SELF-TARGETING: An NPC cannot trigger counter-play for their own territory.
                    if (owner.getInternals().name.equals(npc.name, ignoreCase = true))
                    {
                        Logger.info(LogCategory.GENERAL, "Skipping NPC counter-play for ${npc.name} targeting their own territory.")
                        null
                    }
                    else if (owner is Player)
                    {
                        owner.name
                    }
                    else
                    {
                        null // Only players give counter-responses currently
                    }
                }
                else
                {
                    null
                }
            }
            else
            {
                null
            }
        }
    }
    else
    {
        targetType.targets
    }

    val initialTargets = resolvedOwnerNames.mapNotNull { targetName ->
        WorldManager.world.activePlayers.find { normalizeTargetName(it.name).equals(normalizeTargetName(targetName), ignoreCase = true) }
    }
    Logger.info(LogCategory.GENERAL, "[NPC_COUNTERPLAY] Initial defenders: ${initialTargets.map { it.name }}")

    File(System.getenv("NPC_DEBUG_LOG") ?: "/tmp/debug_npc.log").appendText("Targeted Players: ${initialTargets.joinToString { it.name }}\n")

    if(initialTargets.isEmpty())
    {
        Logger.info(LogCategory.GENERAL, "[NPC_COUNTERPLAY] No player defenders resolved for targetType=${targetType.type}")
        return NpcCounterPlayResult("", emptyList(), emptyList(), true)
    }

    val responses = mutableListOf<NpcCounterResponseData>()
    val allResponses = mutableListOf<NpcCounterResponseData>()
    val targetingChain = mutableSetOf<String>()
    val cascadeQueue = mutableListOf(
        NpcCascadeState(
            attackerName = npc.name,
            attackerAction = finalActionText,
            attackerPlayer = null,
            targets = initialTargets,
            cascadeDepth = 0
        )
    )

    while(cascadeQueue.isNotEmpty())
    {
        val current = cascadeQueue.removeAt(0)
        responses.clear()

        val activeDefenders = current.targets.filter { defender ->
            if(isDemo)
            {
                true
            }
            else
            {
                when(playType.type)
                {
                    PlayType.Military -> defender.militaryPoints >= 50
                    PlayType.Diplomatic -> defender.diplomacyPoints >= 50
                    PlayType.Research -> defender.researchPoints >= 50
                    else -> false
                }
            }
        }

        if(activeDefenders.isEmpty())
        {
            Logger.debug(LogCategory.GENERAL, "[NPC_COUNTERPLAY] No active defenders for cascade depth ${current.cascadeDepth}")
            continue
        }
        Logger.info(
            LogCategory.GENERAL,
            "[NPC_COUNTERPLAY] Cascade depth ${current.cascadeDepth}: attacker='${current.attackerName}', defenders=${activeDefenders.map { it.name }}"
        )

        if(!isDemo)
        {
            WorldManager.worldMutex.withLock {
                activeDefenders.forEach { defender ->
                    when(playType.type)
                    {
                        PlayType.Military -> defender.militaryPoints -= 50
                        PlayType.Diplomatic -> defender.diplomacyPoints -= 50
                        PlayType.Research -> defender.researchPoints -= 50
                        else -> {}
                    }
                }
            }
        }

        UiSignalRpcHandlers.broadcastProgressBar(2, "Resolving defender responses (cascade ${current.cascadeDepth})...")
        val responseChannel = Channel<NpcCounterResponseData>(Channel.UNLIMITED)

        coroutineScope {
            val collector = launch {
                for (responseData in responseChannel)
                {
                    responses.add(responseData)
                    allResponses.add(responseData)

                    val partialHistory = intentHistory.copy(
                        turnResult = "(Counter-responses: ${allResponses.size})",
                        counterResponses = allResponses.map { "${it.player.name}: ${it.firstPersonResponse}" }.toMutableList()
                    )
                    ActionHistoryRpcHandlers.broadcastTurnComplete(partialHistory)
                }
            }

            val responseJobs = activeDefenders.map { defender ->
                async {
                    val isReachable = WorldManager.isReachable(defender)

                    val responseData: NpcCounterResponseData = if(!isReachable)
                    {
                        Logger.warn(LogCategory.LLM, "[NPC_COUNTERPLAY] AI fallback (unreachable defender): ${defender.name}")
                        val (firstPerson, thirdPerson) = generateAiCounterResponse(
                            player = defender,
                            attackerAction = current.attackerAction,
                            attackerName = current.attackerName,
                            connectionId = ""
                        )
                        val intent = detectNpcResponseIntent(
                            responseText = thirdPerson,
                            recipientIds = recipientIds,
                            traceLabel = "AI_UNREACHABLE_${defender.name}_depth${current.cascadeDepth}"
                        )
                        NpcCounterResponseData(defender, firstPerson, thirdPerson, "AI_UNREACHABLE", intent)
                    }
                    else
                    {
                        val deferred = GameResponseManager.waitForResponse(defender.name)
                        UiSignalRpcHandlers.broadcastCounterPlayPrompt(
                            targetId = defender.name,
                            attackerName = current.attackerName,
                            actionDescription = current.attackerAction
                        )
                        WorldManager.startManualTimer(WorldManager.COUNTERPLAY_TIMEOUT_MS / 1000)
                        val humanResponse = withTimeoutOrNull(WorldManager.COUNTERPLAY_TIMEOUT_MS) { deferred.await() }
                        WorldManager.stopTurnTimer()

                        val humanResponseData: NpcCounterResponseData = if(humanResponse == "[IGNORE]")
                        {
                            Logger.info(LogCategory.LLM, "[NPC_COUNTERPLAY] Player ${defender.name} explicitly ignored the event.")
                            NpcCounterResponseData(
                                player = defender,
                                firstPersonResponse = "...",
                                thirdPersonResponse = "${defender.name} chooses to ignore the situation, offering no response.",
                                source = "HUMAN_IGNORE",
                                responseIntent = ActionIntent.Friendly
                            )
                        }
                        else if(humanResponse.isNullOrBlank())
                        {
                            Logger.warn(LogCategory.LLM, "[NPC_COUNTERPLAY] AI fallback (timeout defender): ${defender.name}")
                            val (firstPerson, thirdPerson) = generateAiCounterResponse(
                                player = defender,
                                attackerAction = current.attackerAction,
                                attackerName = current.attackerName,
                                connectionId = ""
                            )
                            val intent = detectNpcResponseIntent(
                                responseText = thirdPerson,
                                recipientIds = recipientIds,
                                traceLabel = "AI_TIMEOUT_${defender.name}_depth${current.cascadeDepth}"
                            )
                            NpcCounterResponseData(defender, firstPerson, thirdPerson, "AI_TIMEOUT", intent)
                        }
                        else
                        {
                            val needsStrictValidation = targetType.actionIntent == ActionIntent.Hostile ||
                                targetType.targets.size > 1 ||
                                current.cascadeDepth > 0

                            val validatedResponse = if(needsStrictValidation)
                            {
                                Logger.info(
                                    LogCategory.GENERAL,
                                    "[NPC_COUNTERPLAY] Running defensive validator for defender='${defender.name}' depth=${current.cascadeDepth}"
                                )
                                val attackerProxy = current.attackerPlayer ?: buildNpcAttackerProxy(npc, current.attackerName)
                                val validator = buildDefensiveValidator(defender, attackerProxy, current.attackerAction).apply {
                                    pipelineName = "npc_defensive_validator_${defender.name}_depth${current.cascadeDepth}"
                                    enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
                                    enablePipeTimeout(
                                        applyRecursively = true,
                                        duration = 180000,
                                        autoRetry = true,
                                        retryLimit = 5
                                    )
                                    init(true)
                                    streamPipelineOutputToAgentWorkBuffer(recipientIds, this)
                                }
                                val validatorOutput = validator.execute(MultimodalContent(humanResponse)).text.ifBlank { humanResponse }
                                saveSystemTrace(
                                    "NPC_DefensiveValidator_${sanitizeTraceComponent(defender.name)}_Depth${current.cascadeDepth}",
                                    validator
                                )
                                validatorOutput
                            }
                            else
                            {
                                humanResponse
                            }

                            val intent = detectNpcResponseIntent(
                                responseText = validatedResponse,
                                recipientIds = recipientIds,
                                traceLabel = "HUMAN_${defender.name}_depth${current.cascadeDepth}"
                            )
                            NpcCounterResponseData(defender, humanResponse, validatedResponse, "HUMAN", intent)
                        }

                        humanResponseData
                    }

                    responseChannel.send(responseData)
                }
            }

            responseJobs.awaitAll()
            responseChannel.close()
            collector.join()
        }

        for(responseData in responses)
        {
            val edge = "${current.attackerName}→${responseData.player.name}"
            targetingChain.add(edge)

            val detector = buildTargetDetectorAgent(responseData.player).apply {
                enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
                init(true)
                streamPipelineOutputToAgentWorkBuffer(recipientIds, this)
            }
            val detected = extractJson<ActionTargetTypeObj>(
                detector.execute(MultimodalContent(responseData.thirdPersonResponse)).text
            )
            saveSystemTrace(
                "NPC_CascadeTargetDetector_${sanitizeTraceComponent(responseData.player.name)}_Depth${current.cascadeDepth}",
                detector
            )

            if(detected?.type == ActionTargetType.Player && detected.targets.isNotEmpty())
            {
                val newTargets = detected.targets.mapNotNull { targetName ->
                    WorldManager.world.activePlayers.find {
                        normalizeTargetName(it.name).equals(normalizeTargetName(targetName), ignoreCase = true)
                    }
                }.filter { nextTarget ->
                    val backEdge = "${responseData.player.name}→${nextTarget.name}"
                    when
                    {
                        nextTarget.name == current.attackerName -> false
                        targetingChain.contains(backEdge) -> false
                        else -> true
                    }
                }

                if(newTargets.isNotEmpty())
                {
                    Logger.info(
                        LogCategory.GENERAL,
                        "[NPC_COUNTERPLAY] Cascade expansion from '${responseData.player.name}' -> ${newTargets.map { it.name }} at depth ${current.cascadeDepth + 1}"
                    )
                    cascadeQueue.add(
                        NpcCascadeState(
                            attackerName = responseData.player.name,
                            attackerAction = responseData.thirdPersonResponse,
                            attackerPlayer = responseData.player,
                            targets = newTargets,
                            cascadeDepth = current.cascadeDepth + 1
                        )
                    )
                }
            }
        }
    }

    val hasMismatch = allResponses.any { it.responseIntent != targetType.actionIntent }
    if(hasMismatch)
    {
        targetType.intentMismatch = true
    }

    UiSignalRpcHandlers.broadcastProgressBar(2, "Refining responses...")
    val refinedResponses = coroutineScope {
        allResponses.map { responseData ->
            async {
                val refinementAgent = buildResponseRefinementAgent().apply {
                    enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
                    enablePipeTimeout(
                        applyRecursively = true,
                        duration = 180000,
                        autoRetry = true,
                        retryLimit = 5
                    )
                    miniBank.contextMap["AttackerIntent"] = ContextWindow().apply { contextElements.add(finalActionText) }
                    miniBank.contextMap["TargetIdentity"] = ContextWindow().apply { contextElements.add(responseData.player.name) }
                    init(true)
                    streamPipelineOutputToAgentWorkBuffer(recipientIds, this)
                }
                attachProgressHooks(refinementAgent, 2)
                val refined = refinementAgent.execute(MultimodalContent(responseData.thirdPersonResponse)).text
                saveSystemTrace(
                    "NPC_ResponseRefinement_${sanitizeTraceComponent(responseData.player.name)}",
                    refinementAgent
                )
                refined
            }
        }.awaitAll()
    }

    val aggregatedResponseProse = refinedResponses.joinToString(" ")
    val counterResponsesList = allResponses.map { "${it.player.name}: ${it.firstPersonResponse}" }
    Logger.info(
        LogCategory.GENERAL,
        "[NPC_COUNTERPLAY] Completed responses=${counterResponsesList.size}, mismatch=${targetType.intentMismatch}, aggregatedLength=${aggregatedResponseProse.length}"
    )
    return NpcCounterPlayResult(
        aggregatedResponseProse = aggregatedResponseProse,
        counterResponsesList = counterResponsesList,
        defendingPlayers = initialTargets.distinctBy { it.name },
        fairnessScopeApplied = true
    )
}

private suspend fun applyNpcFallbackResearchBuff(npc: Npc) {
    // NPCs get modest stat buffs for research
    val buff = StatBuff(
        might = 0,
        luckPoints = 0,
        wealth = 0,
        reputation = 0,
        militaryReadiness = 10,
        legitimacy = 10,
        stagnation = -5
    )
    
    WorldManager.worldMutex.withLock {
        npc.militaryReadiness = (npc.militaryReadiness + buff.militaryReadiness).coerceIn(0, 100)
        npc.legitimacy = (npc.legitimacy + buff.legitimacy).coerceIn(0, 100)
        npc.stagnation = (npc.stagnation + buff.stagnation).coerceIn(0, 100)
    }
    
    Logger.info(LogCategory.GENERAL, "Applied fallback research buff for NPC ${npc.name}: readiness+${buff.militaryReadiness}, legitimacy+${buff.legitimacy}, stagnation${buff.stagnation}")
}