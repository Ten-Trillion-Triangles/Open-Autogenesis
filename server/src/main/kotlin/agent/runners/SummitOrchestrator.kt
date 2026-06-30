package agent.runners

import agent.builders.judgeOutcome.SummitContext
import agent.builders.judgeOutcome.buildJudge
import agent.builders.writingAgent.buildResponseRefinementAgent
import agent.managers.GameResponseManager
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Pipe.MultimodalContent
import gameState.TimeProvider
import gameState.WorldManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.ActionIntent
import org.ttt.autogenesis.server.UiSignalRpcHandlers
import structs.Player

/**
 * Result of a completed Summit orchestration.
 *
 * @param combinedNarrative The full narrative from summit orchestration including all responses.
 * @param statChangesMap Map of player name to their stat change description (e.g. "+50 reputation, +50 legitimacy").
 */
data class SummitOutcome(
    val combinedNarrative: String,
    val statChangesMap: Map<String, String>
)

/**
 * Specialized orchestrator for Summit plays.
 * Handles multi-player coordination and narrative resolution.
 *
 * A Summit is a cooperative diplomatic play where the active player (Summiter)
 * proposes a meeting to target players, collects their responses, and then
 * invokes the Judge to determine the outcome.
 *
 * @param activePlayer The player initiating the summit (Summiter).
 * @param description The action description from the Summiter.
 * @param targets The list of players being invited to the summit.
 * @param reason The Summiter's reason/motivation for calling the summit.
 * @return A [SummitOutcome] containing the combined narrative and stat change map.
 */
suspend fun runSummitOrchestration(
    activePlayer: Player,
    description: String,
    targets: List<Player>,
    reason: String
): SummitOutcome
{
    Logger.info(LogCategory.GENERAL, "========================================")
    Logger.info(LogCategory.GENERAL, "Starting Summit Orchestration for ${activePlayer.name}")
    Logger.info(LogCategory.GENERAL, "Reason: $reason")
    Logger.info(LogCategory.GENERAL, "Targets: ${targets.map { it.name }}")
    Logger.info(LogCategory.GENERAL, "========================================")

    // Track which players responded vs ignored
    val playerResponded: MutableMap<String, Boolean> = mutableMapOf()

    // 1. Notify targeted players and collect responses
    val refinedNarratives = if (targets.isNotEmpty()) {
        Logger.info(LogCategory.GENERAL, "Summit: Waiting up to ${WorldManager.SUMMIT_RESPONSE_TIMEOUT_MS / 1000}s for ${targets.size} players to respond...")
        WorldManager.startManualTimer(WorldManager.SUMMIT_RESPONSE_TIMEOUT_MS / 1000)

        val results = coroutineScope {
            targets.map { target ->
                async {
                    // Check AI status before setting up deferred wait
                    val targetStats = WorldManager.findPlayerFromStats(target.name)
                    val isTargetAi = targetStats?.isControlledByNpc == true || !WorldManager.isReachable(target)

                    val rawResponse = if (isTargetAi) {
                        // AI-controlled or unreachable: generate response without waiting
                        Logger.info(LogCategory.GENERAL, "Summit: AI takeover for ${target.name} (isControlledByNpc=${targetStats?.isControlledByNpc})")
                        UiSignalRpcHandlers.broadcastCounterPlayPrompt(
                            targetId = target.name,
                            attackerName = activePlayer.name,
                            actionDescription = description,
                            actionIntent = ActionIntent.Friendly
                        )
                        val broadcastId = org.ttt.autogenesis.server.getAllConnectedClientIds().firstOrNull() ?: ""
                        val aiResponse = generateAiCounterResponse(
                            target,
                            activePlayer.name,
                            description,
                            broadcastId
                        )
                        playerResponded[target.name] = true
                        aiResponse.second  // thirdPersonResponse for narrative integration
                    } else {
                        // Human player: wait for response with timeout
                        val deferred = GameResponseManager.waitForResponse(target.name)
                        Logger.info(LogCategory.GENERAL, "Summit: Prompting player ${target.name} for response")

                        UiSignalRpcHandlers.broadcastCounterPlayPrompt(
                            targetId = target.name,
                            attackerName = activePlayer.name,
                            actionDescription = description,
                            actionIntent = ActionIntent.Friendly
                        )

                        withTimeoutOrNull(WorldManager.SUMMIT_RESPONSE_TIMEOUT_MS) { deferred.await() }
                    }

                    if (!rawResponse.isNullOrBlank()) {
                        Logger.info(LogCategory.GENERAL, "Summit: Received response from ${target.name}. Refining...")
                        if (!isTargetAi) {
                            playerResponded[target.name] = true
                        }

                        val refinementAgent = buildResponseRefinementAgent().apply {
                            init(true)
                            val broadcastIds = org.ttt.autogenesis.server.getAllConnectedClientIds()
                            org.ttt.autogenesis.server.streamPipelineOutputToAgentWorkBuffer(broadcastIds, this)
                        }
                        val result = refinementAgent.execute(MultimodalContent(rawResponse))
                        saveSystemTrace("SummitRefinement", refinementAgent, "${target.name}_${System.currentTimeMillis()}")
                        target.name to result.text
                    } else {
                        Logger.info(LogCategory.GENERAL, "Summit: Player ${target.name} ignored or timed out.")
                        if (!isTargetAi) {
                            playerResponded[target.name] = false
                        }
                        null
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        WorldManager.stopTurnTimer()
        Logger.info(LogCategory.GENERAL, "Summit: Response collection complete. ${results.size}/${targets.size} players responded")
        results
    } else {
        Logger.info(LogCategory.GENERAL, "Summit: No targets found for coordinate response.")
        emptyMap()
    }

    // 2. Build summary line exposing stakes, framing, scale
    val respondingCount = playerResponded.count { it.value }
    val ignoringCount = playerResponded.count { !it.value }
    val summaryLine = "[SUMMARY] Stakes: Diplomatic relations, Framing: Cooperative summit, Scale: $respondingCount responders + $ignoringCount non-responders"

    Logger.info(LogCategory.GENERAL, "Summit: $summaryLine")

    // 3. Assemble combined narrative
    // Format: [summary line] + [summiter reason] + [all responses in order]
    val combinedNarrative = buildString {
        appendLine(summaryLine)
        appendLine()
        appendLine("[SUMMIT_REASON]")
        appendLine(reason)
        appendLine()
        appendLine("[SUMMIT_RESPONSES]")
        // Add responses in target order for consistency
        targets.forEach { target ->
            val response = refinedNarratives[target.name]
            if (response != null) {
                appendLine("--- ${target.name} ---")
                appendLine(response)
                appendLine()
            } else {
                Logger.info(LogCategory.GENERAL, "Summit: No response recorded for ${target.name}")
            }
        }
    }

    Logger.info(LogCategory.GENERAL, "Summit: Combined narrative assembled (${combinedNarrative.length} chars)")
    Logger.debug(LogCategory.GENERAL, "Summit: Combined narrative preview: ${combinedNarrative.take(500)}...")

    // 4. Invoke buildJudge for multi-actor Summit
    Logger.info(LogCategory.GENERAL, "Summit: Invoking Judge with summit parameters...")
    Logger.info(LogCategory.GENERAL, "  - Primary actor (Summiter): ${activePlayer.name}")
    Logger.info(LogCategory.GENERAL, "  - actionIntent: Friendly")
    Logger.info(LogCategory.GENERAL, "  - isSummit: true")
    Logger.info(LogCategory.GENERAL, "  - summitParticipants: ${targets.map { it.name }}")
    Logger.info(LogCategory.GENERAL, "  - summitResponses: ${refinedNarratives.size} responses")

    val judge = buildJudge(
        player = activePlayer,
        targetActor = null,  // Summit doesn't have a single target
        knownOutcome = null,
        actionIntent = "Friendly",
        targetData = null,  // Summit uses its own target mechanism
        isSummit = true,
        summitParticipants = targets,
        summitResponses = refinedNarratives
    ).apply {
        enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        enablePipeTimeout(
            applyRecursively = true,
            duration = 180000,
            autoRetry = true,
            retryLimit = 5
        )
        init(true)
        val broadcastIds = org.ttt.autogenesis.server.getAllConnectedClientIds()
        org.ttt.autogenesis.server.streamPipelineOutputToAgentWorkBuffer(broadcastIds, this)
    }

    UiSignalRpcHandlers.broadcastProgressBar(4, "Evaluating summit outcomes...")
    judge.execute(MultimodalContent(combinedNarrative))

    // Extract judge results
    val judgeResults = judge.pipeMetaData["judge result"] as? agent.builders.judgeOutcome.Results
        ?: agent.builders.judgeOutcome.Results(resultSummary = "Summit completed")

    Logger.info(LogCategory.GENERAL, "Summit: Judge returned resultSummary: ${judgeResults.resultSummary}")

    // Apply judge results to world state
    WorldManager.applyJudgeResults(
        playerName = activePlayer.name,
        wasSuccessful = true,  // Summit is inherently cooperative, so success
        results = judgeResults,
        turnNumber = WorldManager.world.roundNumber,
        timestampMillis = TimeProvider.nowMillis()
    )

    // 5. Apply hardcoded stat buffs directly (no worldMutex — caller holds it)
    // Summiter always gets diplomatic boost for initiating
    activePlayer.reputation = (activePlayer.reputation + 25).coerceIn(0, 100)
    activePlayer.legitimacy = (activePlayer.legitimacy + 25).coerceIn(0, 100)
    Logger.info(LogCategory.GENERAL, "Summit: ${activePlayer.name} (Summiter) gets +25 reputation, +25 legitimacy for hosting summit")

    val statChangesMap = mutableMapOf<String, String>()

    // Targets get buffs based on whether they responded
    targets.forEach { target ->
        val responded = playerResponded[target.name] ?: false
        if (responded && refinedNarratives.containsKey(target.name)) {
            target.reputation = (target.reputation + 50).coerceIn(0, 100)
            target.legitimacy = (target.legitimacy + 50).coerceIn(0, 100)
            statChangesMap[target.name] = "+50 reputation, +50 legitimacy"
            Logger.info(LogCategory.GENERAL, "Summit: ${target.name} responded - gets +50 reputation, +50 legitimacy (cooperative)")
        } else {
            target.might = (target.might + 50).coerceIn(0, 100)
            target.militaryReadiness = (target.militaryReadiness + 50).coerceIn(0, 100)
            statChangesMap[target.name] = "+50 might, +50 militaryReadiness"
            Logger.info(LogCategory.GENERAL, "Summit: ${target.name} ignored - gets +50 might, +50 militaryReadiness (disengaged)")
        }
    }

    statChangesMap[activePlayer.name] = "+25 reputation, +25 legitimacy"

    saveSystemTrace("Summit", judge)
    Logger.info(LogCategory.GENERAL, "Summit orchestration complete for ${activePlayer.name}")
    Logger.info(LogCategory.GENERAL, "========================================")

    return SummitOutcome(combinedNarrative, statChangesMap)
}
