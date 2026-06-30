package accounting

import kotlinx.serialization.Serializable

/**
 * Represents a single API call's token usage extracted from trace data.
 *
 * @param inputTokens Number of input tokens consumed
 * @param outputTokens Number of output tokens generated
 * @param modelId The model identifier used for the call
 * @param timestamp Unix timestamp in milliseconds
 * @param pipeId Identifier of the pipeline that made the call
 * @param inferenceTimeMs Wall-clock milliseconds for this single API call
 */
@Serializable
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val modelId: String,
    val timestamp: Long,
    val pipeId: String,
    val inferenceTimeMs: Long = 0
)

/**
 * A billing record for a single turn, attributed to a player.
 *
 * @param turnKey Folder name identifying the turn
 * @param actorName Player or NPC name
 * @param accelByteUserId AccelByte user ID (empty in single-player for NPC turns)
 * @param roundNumber Game round number
 * @param turnIndex Turn index within the round
 * @param isNpcTurn Whether this was an NPC turn
 * @param tokenUsages Per-call token usage list
 * @param totalInputTokens Sum of all input tokens
 * @param totalOutputTokens Sum of all output tokens
 * @param totalCostUsd Total cost in USD
 * @param totalInferenceTimeMs Sum of inference times in ms
 * @param timestampMillis Record creation timestamp
 */
@Serializable
data class TurnBillingRecord(
    val turnKey: String,
    val actorName: String,
    val accelByteUserId: String,
    val roundNumber: Int,
    val turnIndex: Int,
    val isNpcTurn: Boolean,
    val tokenUsages: List<TokenUsage>,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalCostUsd: Double,
    val totalInferenceTimeMs: Long,
    val timestampMillis: Long
)

/**
 * Aggregated billing summary for a player over the entire game session.
 *
 * @param accelByteUserId AccelByte user ID
 * @param playerName Display name
 * @param totalInputTokens Sum of all input tokens
 * @param totalOutputTokens Sum of all output tokens
 * @param totalCostUsd Total cost in USD
 * @param totalInferenceTimeMs Sum of inference times in ms
 * @param turnCount Number of turns recorded
 * @param turnRecords List of turn keys for detail lookup
 */
@Serializable
data class PlayerBillingSummary(
    val accelByteUserId: String,
    val playerName: String,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalCostUsd: Double,
    val totalInferenceTimeMs: Long,
    val turnCount: Int,
    val turnRecords: List<String>
)

/**
 * Complete game billing report.
 *
 * @param sessionId Unique session identifier
 * @param isSinglePlayerMode Whether single-player mode was active
 * @param humanPlayerAccelByteId Human player's AccelByte ID (null in multiplayer)
 * @param humanPlayerName Human player's name
 * @param playerSummaries Per-player summaries
 * @param totalGameCostUsd Total game cost in USD
 * @param totalInputTokens Sum of all input tokens
 * @param totalOutputTokens Sum of all output tokens
 * @param totalInferenceTimeMs Sum of all inference times in ms
 * @param generatedAtMillis Report generation timestamp
 */
@Serializable
data class GameBillingReport(
    val sessionId: String,
    val isSinglePlayerMode: Boolean,
    val humanPlayerAccelByteId: String?,
    val humanPlayerName: String?,
    val playerSummaries: List<PlayerBillingSummary>,
    val totalGameCostUsd: Double,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalInferenceTimeMs: Long,
    val generatedAtMillis: Long
)
