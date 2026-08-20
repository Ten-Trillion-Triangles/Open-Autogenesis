package structs.account

import kotlinx.serialization.Serializable

/**
 * One row in a player's persistent usage ledger. Each completed turn appends one
 * [UsageEntry] to the ledger via the game server's `flushTurnUsage` flow.
 *
 * @param entryId Stable UUID generated server-side; used as a pagination cursor.
 * @param timestampMillis Wall-clock time the deduction was applied.
 * @param sourceLabel Human-readable description, e.g. "Turn 14 — Nemesis Agent".
 * @param sourceIconKey UI icon hint; one of
 *   "turn-resolution" | "agent-run" | "narrative" | "judge" | "geo-politics" |
 *   "purchase" | "refill" | "reset" | "game-complete".
 * @param creditsDelta Negative for deductions, positive for refills or purchases.
 * @param balanceAfter Player's [BillingStatus.credits] immediately after this entry.
 * @param sessionId Originating [accounting.Billing.sessionId]; groups entries per game.
 * @param turnKey Optional link to [accounting.TurnBillingRecord.turnKey].
 * @param inputTokens Input tokens that produced this deduction (0 for non-turn entries).
 * @param outputTokens Output tokens that produced this deduction (0 for non-turn entries).
 * @param accelByteUserId Owner of the entry.
 * @param model Short pricing key for the model that produced this entry, e.g.
 *   `qwen.qwen3-235b-a22b-2507-v1:0`. Empty for non-turn entries (chat, answer, etc.).
 *   Resolved via [accounting.ModelPricing.resolveShortId] at flush time.
 * @param byoKey `true` when the active player had [AccountSettings.bringYourOwnApiKey]
 *   set at the time of the turn. Drives the operator dashboard distinction between
 *   operator-paid inference and player-paid inference.
 * @param costClass [CostClass] name resolved from the player's [AccountSettings] at
 *   the time of the turn. One of `BYO_KEY`, `PRO`, `CASUAL`, `CREDIT`, `FREE`.
 *   Drives the per-class credit-delta policy.
 * @param region AWS region this turn's inference was billed to. For BYO-keyed turns
 *   this is the region pinned to the player's key; for platform-paid turns it is
 *   the active `bedrockEnv` region. Used to reconcile Bedrock invoicing against
 *   the per-game report.
 * @param operatorCostUsd Raw LLM cost for this turn in USD, computed via
 *   [accounting.ModelPricing.calculateCost]. Always populated so the operator
 *   dashboard reflects the true inference cost regardless of the cost-class
 *   deduction policy (which only determines what the player pays, not what the
 *   operator owes).
 */
@Serializable
data class UsageEntry(
    val entryId: String,
    val timestampMillis: Long,
    val sourceLabel: String,
    val sourceIconKey: String,
    val creditsDelta: Double,
    val balanceAfter: Double,
    val sessionId: String,
    val turnKey: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val accelByteUserId: String,
    val model: String = "",
    val byoKey: Boolean = false,
    val costClass: String = "FREE",
    val region: String = "",
    val operatorCostUsd: Double = 0.0
)

/**
 * Daily aggregation bucket used by the dashboard chart. The server groups
 * filtered ledger entries by UTC day and emits one [DailyUsageBucket] per day.
 *
 * @param dayStartMillis Epoch millis for midnight UTC of the day this bucket covers.
 * @param creditsUsed Sum of `abs(creditsDelta)` for entries on this day.
 * @param inputTokens Sum of input tokens on this day.
 * @param outputTokens Sum of output tokens on this day.
 * @param turnCount Number of turn entries on this day.
 */
@Serializable
data class DailyUsageBucket(
    val dayStartMillis: Long,
    val creditsUsed: Double,
    val inputTokens: Int,
    val outputTokens: Int,
    val turnCount: Int
)

/**
 * Per-game summary derived from a player's ledger entries that share the same
 * [sessionId]. The server emits one [GameUsageSummary] per session.
 *
 * @param sessionId Originating [accounting.Billing.sessionId].
 * @param sessionLabel Human-readable label, e.g. "Game vs Commander Shepard"
 *   or "Game #a1b2c3d4" if no commander's name is available.
 * @param isLive True when [sessionId] matches the server's currently-running
 *   [accounting.Billing.sessionId].
 * @param startMillis Earliest entry timestamp in this session.
 * @param endMillis Latest entry timestamp in this session (== lastUpdate for live games).
 * @param totalCredits Sum of `abs(creditsDelta)` for this session.
 * @param turnCount Number of turn entries for this session.
 * @param inputTokens Sum of input tokens for this session.
 * @param outputTokens Sum of output tokens for this session.
 */
@Serializable
data class GameUsageSummary(
    val sessionId: String,
    val sessionLabel: String,
    val isLive: Boolean,
    val startMillis: Long,
    val endMillis: Long,
    val totalCredits: Double,
    val turnCount: Int,
    val inputTokens: Int,
    val outputTokens: Int
)

/**
 * Time window selector for the usage dashboard.
 */
@Serializable
enum class UsageWindow
{
    WEEK,
    MONTH,
    YEAR,
    ALL_TIME
}

/**
 * Request payload for the server's `usage.history.get` RPC.
 *
 * @param userId The AccelByte user ID to query.
 * @param window Time window to filter on.
 * @param limit Maximum number of entries to return (default 50, server may cap).
 * @param cursorEntryId When set, the server returns entries strictly older than this entry id.
 * @param sessionId When set, the server filters entries AND the per-game summary to a single session.
 */
@Serializable
data class GetUsageHistoryRequest(
    val userId: String,
    val window: UsageWindow = UsageWindow.WEEK,
    val limit: Int = 50,
    val cursorEntryId: String? = null,
    val sessionId: String? = null
)

/**
 * Response payload for the server's `usage.history.get` RPC. Contains the
 * per-turn entry list, per-day chart buckets, and per-game summaries so the
 * client can render the dashboard in a single round trip.
 *
 * @param entries Per-turn [UsageEntry]s, sorted newest-first, paginated.
 * @param daily Per-day [DailyUsageBucket]s for the requested window.
 * @param gameSummaries Per-game [GameUsageSummary]s derived from `entries`.
 * @param selectedSessionId Echoes the optional `sessionId` filter from the request.
 * @param totalCreditsUsed Sum of `abs(creditsDelta)` over the requested window.
 * @param periodStartMillis Window start (inclusive).
 * @param periodEndMillis Window end (exclusive).
 * @param hasMore True when more entries exist beyond `limit` and `cursorEntryId`.
 * @param nextCursor Pass back as `cursorEntryId` for the next page, or null.
 */
@Serializable
data class GetUsageHistoryResponse(
    val entries: List<UsageEntry>,
    val daily: List<DailyUsageBucket>,
    val gameSummaries: List<GameUsageSummary>,
    val selectedSessionId: String? = null,
    val totalCreditsUsed: Double,
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    val hasMore: Boolean,
    val nextCursor: String?
)

/**
 * Persistent per-user cloud-save record holding the full usage ledger.
 * Capped at [maxEntries]; oldest entries are pruned when the cap is exceeded.
 *
 * @param accelByteUserId Owner of the ledger.
 * @param entries All known [UsageEntry]s for this user, newest-first.
 * @param lastUpdatedMillis Last time the ledger was persisted.
 * @param maxEntries Soft cap on stored entries; oldest pruned when exceeded.
 */
@Serializable
data class UsageLedger(
    val accelByteUserId: String = "",
    val entries: List<UsageEntry> = emptyList(),
    val lastUpdatedMillis: Long = 0L,
    val maxEntries: Int = 500
)