package accounting

import gameState.WorldManager
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import java.io.File

/**
 * Aggregates token usage from traces into billing records and maps actor names to AccelByte IDs.
 */
object BillingAggregator
{
    /**
     * Generates a TurnBillingRecord for a completed turn.
     *
     * @param turnFolderName The folder name (e.g., "Round_1_Turn_0_Commander_Shepard")
     * @param actorName The actor name extracted from folder name (may differ in casing/underscores)
     * @param isNpcTurn Whether this turn was an NPC turn
     * @return TurnBillingRecord, or null if no traces found
     */
    fun generateTurnBillingRecord(turnFolderName: String, actorName: String, isNpcTurn: Boolean): TurnBillingRecord?
    {
        val traceDir = File(File(TraceParser.getTraceRoot()), turnFolderName)
        if (!traceDir.exists())
        {
            Logger.warn(LogCategory.SYSTEM, "BillingAggregator: Turn trace directory not found: ${traceDir.absolutePath}")
            return null
        }

        val usages = TraceParser.parseTraceDirectory(traceDir.absolutePath)
        if (usages.isEmpty())
        {
            Logger.debug(LogCategory.SYSTEM, "BillingAggregator: No token usage found in $turnFolderName")
            return null
        }

        val (roundNumber, turnIndex) = parseTurnIndices(turnFolderName)
        val accelByteUserId = resolveAccelByteId(actorName)

        var totalInput = 0
        var totalOutput = 0
        var totalCost = 0.0
        var totalInference = 0L
        for (u in usages)
        {
            totalInput += u.inputTokens
            totalOutput += u.outputTokens
            totalCost += ModelPricing.calculateCost(u.modelId, u.inputTokens, u.outputTokens)
            totalInference += u.inferenceTimeMs
        }

        val record = TurnBillingRecord(
            turnKey = turnFolderName,
            actorName = actorName,
            accelByteUserId = accelByteUserId,
            roundNumber = roundNumber,
            turnIndex = turnIndex,
            isNpcTurn = isNpcTurn,
            tokenUsages = usages,
            totalInputTokens = totalInput,
            totalOutputTokens = totalOutput,
            totalCostUsd = totalCost,
            totalInferenceTimeMs = totalInference,
            timestampMillis = System.currentTimeMillis()
        )

        Logger.info(
            LogCategory.SYSTEM,
            "BillingAggregator: Generated billing record for $turnFolderName — " +
                "input=$totalInput, output=$totalOutput, cost=$%.6f, inferenceMs=$totalInference".format(totalCost)
        )

        return record
    }

    /**
     * Resolves an actor name to an AccelByte user ID.
     * In single-player mode, always returns the human player's AccelByte ID.
     * In multiplayer, fuzzy-matches the actor name against playerStats.
     *
     * @param actorName The actor name to resolve
     * @return The AccelByte user ID, or empty string if not found
     */
    fun resolveAccelByteId(actorName: String): String
    {
        if (WorldManager.isSinglePlayer)
        {
            val humanStats = WorldManager.playerStats.firstOrNull {
                !it.isControlledByNpc && it.accelByteUserId.isNotBlank()
            }
            return humanStats?.accelByteUserId
                ?: WorldManager.playerStats.firstOrNull()?.accelByteUserId
                ?: ""
        }

        val normalizedName = actorName.replace("_", " ").replace("-", " ")
        val stats = WorldManager.findPlayerFromStats(normalizedName)
        return stats?.accelByteUserId ?: ""
    }

    /**
     * Parses round and turn indices from a folder name.
     * Format: "Round_N_Turn_M_ActorName" or "Round_N_Turn_M_Actor-Name"
     *
     * @param folderName The turn folder name
     * @return Pair of (roundNumber, turnIndex)
     */
    fun parseTurnIndices(folderName: String): Pair<Int, Int>
    {
        val roundMatch = Regex("Round_(\\d+)").find(folderName)
        val turnMatch = Regex("Turn_(\\d+)").find(folderName)
        val round = roundMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val turn = turnMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Pair(round, turn)
    }

    /**
     * Aggregates a list of turn billing records into per-player summaries.
     *
     * @param turnRecords List of turn billing records to aggregate
     * @return List of PlayerBillingSummary, one per unique AccelByte ID
     */
    fun aggregatePlayerSummaries(turnRecords: List<TurnBillingRecord>): List<PlayerBillingSummary>
    {
        return turnRecords
            .groupBy { it.accelByteUserId }
            .map { (accelByteId, records) ->
                val firstRecord = records.first()
                var totalInput = 0
                var totalOutput = 0
                var totalCost = 0.0
                var totalInference = 0L
                for (r in records)
                {
                    totalInput += r.totalInputTokens
                    totalOutput += r.totalOutputTokens
                    totalCost += r.totalCostUsd
                    totalInference += r.totalInferenceTimeMs
                }
                PlayerBillingSummary(
                    accelByteUserId = accelByteId,
                    playerName = firstRecord.actorName,
                    totalInputTokens = totalInput,
                    totalOutputTokens = totalOutput,
                    totalCostUsd = totalCost,
                    totalInferenceTimeMs = totalInference,
                    turnCount = records.size,
                    turnRecords = records.map { it.turnKey }
                )
            }
    }

    /**
     * Extracts the actor name from a turn folder name.
     * Format: "Round_N_Turn_M_ActorName" — actor is everything after Turn_M_
     *
     * @param turnFolderName The turn folder name
     * @return The actor name
     */
    fun extractActorName(turnFolderName: String): String
    {
        val parts = turnFolderName.split("_")
        // Format: Round_N_Turn_M_[ActorName parts...]
        return if (parts.size > 4)
        {
            parts.drop(4).joinToString(" ")
        }
        else
        {
            turnFolderName
        }
    }
}
