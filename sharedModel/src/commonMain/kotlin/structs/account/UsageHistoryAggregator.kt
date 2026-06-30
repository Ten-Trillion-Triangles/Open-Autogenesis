package structs.account

/**
 * Pure aggregator that turns a player's [UsageLedger] into the
 * [GetUsageHistoryResponse] the in-game dashboard renders.
 *
 * ## Why this lives in sharedModel
 *
 * The pre-fix design had the aggregation logic inlined inside
 * `accounting.UsageHistoryRpcHandlers` (main server only) — and the
 * client calls the dashboard via `ServerExtendBridge`, so every fetch
 * 404'd with "Method usage.history.get not found". The fix is to
 * extract the pure logic here so BOTH the main server's
 * `usage.history.get` handler and a new server-extend proxy
 * `server.extend.getUsageHistory` can call the same code path. The
 * proxy is in server-extend because that's the bridge the client
 * actually uses; the main server handler is kept as a duplicate
 * surface for non-UI callers (e.g. the operator dashboard's gRPC
 * path) and to avoid an extra hop on the request.
 *
 * ## Contract
 *
 * `aggregate` is a pure function of `(ledger, request, now,
 * liveSessionId) → GetUsageHistoryResponse`. The `now` and
 * `liveSessionId` parameters are explicit (rather than read from
 * `System.currentTimeMillis()` / `Billing.sessionId` inside the
 * function) so the test fixture can drive them deterministically.
 *
 * Behaviour:
 *  - filters `ledger.entries` to the requested `window` (and
 *    optional `sessionId`)
 *  - sorts newest-first
 *  - paginates with cursor semantics (the last entryId of the
 *    current page is the next cursor)
 *  - aggregates into one [DailyUsageBucket] per UTC day with
 *    at least one entry
 *  - aggregates into one [GameUsageSummary] per sessionId,
 *    flagging the live one (`liveSessionId`) as `isLive = true`
 *  - returns a `totalCreditsUsed` = sum of `abs(creditsDelta)`
 *    over the window
 */
object UsageHistoryAggregator
{
    private const val DAY_MS = 86_400_000L

    /**
     * Pure aggregation. See class KDoc for the contract.
     *
     * @param ledger the player's persistent [UsageLedger]
     * @param request the dashboard request (window, limit, cursor, optional sessionId)
     * @param now wall-clock millis to anchor windowing on (test fixture)
     * @param liveSessionId optional `Billing.sessionId` for the
     *   `isLive` flag on game summaries (null = none live)
     * @return the response the dashboard renders
     */
    fun aggregate(
        ledger: UsageLedger,
        request: GetUsageHistoryRequest,
        now: Long,
        liveSessionId: String?
    ): GetUsageHistoryResponse
    {
        val (periodStart, periodEnd) = periodFor(request.window, now)

        val scopedEntries = ledger.entries.asSequence()
            .filter { it.timestampMillis in periodStart until periodEnd }
            .filter { request.sessionId == null || it.sessionId == request.sessionId }
            .toList()

        val sortedDesc = scopedEntries.sortedByDescending { it.timestampMillis }
        val page = paginate(sortedDesc, request.limit, request.cursorEntryId)

        val totalCreditsUsed = scopedEntries.sumOf { kotlin.math.abs(it.creditsDelta) }

        val dailyBuckets = buildDailyBuckets(scopedEntries, periodStart, periodEnd)
        val gameSummaries = buildGameSummaries(
            scopedEntries,
            ledger.accelByteUserId,
            liveSessionId
        )

        return GetUsageHistoryResponse(
            entries = page.items,
            daily = dailyBuckets,
            gameSummaries = gameSummaries,
            selectedSessionId = request.sessionId,
            totalCreditsUsed = totalCreditsUsed,
            periodStartMillis = periodStart,
            periodEndMillis = periodEnd,
            hasMore = page.hasMore,
            nextCursor = page.nextCursor
        )
    }

    /**
     * Computes the start (inclusive) and end (exclusive) epoch-millis
     * for the requested [UsageWindow] relative to `now`.
     */
    private fun periodFor(window: UsageWindow, now: Long): Pair<Long, Long>
    {
        val end = now
        val start = when (window)
        {
            UsageWindow.WEEK    -> end - (7L * DAY_MS)
            UsageWindow.MONTH   -> end - (30L * DAY_MS)
            UsageWindow.YEAR    -> end - (365L * DAY_MS)
            UsageWindow.ALL_TIME -> 0L
        }
        return Pair(start, end)
    }

    /**
     * Cursor-based pagination over a list of [UsageEntry]s already
     * sorted newest-first. Returns the page items, a `hasMore` flag,
     * and the next cursor (the entryId to pass back) or null when
     * no more pages exist.
     */
    private data class Page<T>(val items: List<T>, val hasMore: Boolean, val nextCursor: String?)

    private fun paginate(entries: List<UsageEntry>, limit: Int, cursorEntryId: String?): Page<UsageEntry>
    {
        if (limit <= 0) return Page(emptyList(), hasMore = false, nextCursor = null)
        val startIndex = if (cursorEntryId == null) 0 else entries.indexOfFirst { it.entryId == cursorEntryId } + 1
        if (startIndex < 0) return Page(emptyList(), hasMore = false, nextCursor = null)
        val endIndex = (startIndex + limit).coerceAtMost(entries.size)
        val items = if (startIndex >= entries.size) emptyList() else entries.subList(startIndex, endIndex)
        val hasMore = endIndex < entries.size
        val nextCursor = if (hasMore && items.isNotEmpty()) items.last().entryId else null
        return Page(items, hasMore, nextCursor)
    }

    /**
     * Aggregates the supplied entries into one [DailyUsageBucket] per
     * UTC day. Days with no entries are not emitted.
     */
    private fun buildDailyBuckets(
        entries: List<UsageEntry>,
        @Suppress("UNUSED_PARAMETER") periodStart: Long,
        @Suppress("UNUSED_PARAMETER") periodEnd: Long
    ): List<DailyUsageBucket>
    {
        if (entries.isEmpty()) return emptyList()
        val byDay = entries.groupBy { (it.timestampMillis / DAY_MS) * DAY_MS }
        return byDay.map { (dayStart, dayEntries) ->
            DailyUsageBucket(
                dayStartMillis = dayStart,
                creditsUsed = dayEntries.sumOf { kotlin.math.abs(it.creditsDelta) },
                inputTokens = dayEntries.sumOf { it.inputTokens },
                outputTokens = dayEntries.sumOf { it.outputTokens },
                turnCount = dayEntries.size
            )
        }.sortedBy { it.dayStartMillis }
    }

    /**
     * Aggregates the supplied entries into one [GameUsageSummary] per
     * [UsageEntry.sessionId]. The currently-running `liveSessionId` is
     * flagged `isLive = true`.
     */
    private fun buildGameSummaries(
        entries: List<UsageEntry>,
        fallbackUserId: String,
        liveSessionId: String?
    ): List<GameUsageSummary>
    {
        if (entries.isEmpty()) return emptyList()
        val bySession = entries.groupBy { it.sessionId }
        return bySession.map { (sessionId, sessionEntries) ->
            val start = sessionEntries.minOf { it.timestampMillis }
            val end = sessionEntries.maxOf { it.timestampMillis }
            val isLive = sessionId == liveSessionId
            val label = deriveSessionLabel(sessionEntries, sessionId, fallbackUserId)
            GameUsageSummary(
                sessionId = sessionId,
                sessionLabel = label,
                isLive = isLive,
                startMillis = start,
                endMillis = end,
                totalCredits = sessionEntries.sumOf { kotlin.math.abs(it.creditsDelta) },
                turnCount = sessionEntries.size,
                inputTokens = sessionEntries.sumOf { it.inputTokens },
                outputTokens = sessionEntries.sumOf { it.outputTokens }
            )
        }.sortedByDescending { it.endMillis }
    }

    /**
     * Picks a human-readable label for a session. Uses the
     * `sourceLabel` of the earliest entry; falls back to a short
     * sessionId-derived string.
     */
    private fun deriveSessionLabel(
        entries: List<UsageEntry>,
        sessionId: String,
        fallbackUserId: String
    ): String
    {
        if (entries.isNotEmpty())
        {
            val first = entries.minByOrNull { it.timestampMillis } ?: return "Game #${sessionId.take(8)}"
            return first.sourceLabel
        }
        val shortId = sessionId.take(8)
        return if (fallbackUserId.isBlank()) "Game #$shortId" else "Game #$shortId"
    }
}
