package structs.account

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Contract for [UsageHistoryAggregator.aggregate].
 *
 * The pre-fix design had `usage.history.get` aggregation logic inlined
 * inside `accounting.UsageHistoryRpcHandlers` (main server only), with no
 * server-extend equivalent — so the client (which calls
 * `ServerExtendBridge`) hit a 404 on every fetch. The fix is to extract
 * the pure aggregation logic into sharedModel and call it from BOTH
 * the main server's `usage.history.get` handler AND a new
 * `server.extend.getUsageHistory` proxy.
 *
 * These tests pin down the post-refactor contract: the aggregator is
 * a pure function from `(UsageLedger, GetUsageHistoryRequest, now) →
 * GetUsageHistoryResponse` that any bridge can call.
 */
class UsageHistoryAggregatorTest
{
    // ─── Windowing ────────────────────────────────────────────────────────

    @Test
    fun aggregate_weekWindow_filtersOutEntriesOlderThan7Days()
    {
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        val recent = usageEntry(timestamp = now - 2 * day, sessionId = "s1")
        val stale = usageEntry(timestamp = now - 30 * day, sessionId = "s1")
        val ledger = UsageLedger(entries = listOf(recent, stale))

        val response = UsageHistoryAggregator.aggregate(
            ledger = ledger,
            request = GetUsageHistoryRequest(userId = "u1", window = UsageWindow.WEEK),
            now = now,
            liveSessionId = null
        )

        assertEquals(1, response.entries.size, "only the recent entry should be in the window")
        assertEquals(recent.entryId, response.entries.first().entryId)
    }

    @Test
    fun aggregate_allTimeWindow_includesAllEntries()
    {
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        val entries = listOf(
            usageEntry(timestamp = now - 1 * day),
            usageEntry(timestamp = now - 100 * day),
            usageEntry(timestamp = now - 365 * day)
        )
        val ledger = UsageLedger(entries = entries)

        val response = UsageHistoryAggregator.aggregate(
            ledger = ledger,
            request = GetUsageHistoryRequest(userId = "u1", window = UsageWindow.ALL_TIME),
            now = now,
            liveSessionId = null
        )

        assertEquals(3, response.entries.size)
        assertEquals(0L, response.periodStartMillis, "ALL_TIME must have start=0")
    }

    // ─── Session filter ──────────────────────────────────────────────────

    @Test
    fun aggregate_sessionIdFilter_keepsOnlyThatSession()
    {
        val now = 1_700_000_000_000L
        val entries = listOf(
            usageEntry(timestamp = now - 1L, sessionId = "sessionA"),
            usageEntry(timestamp = now - 2L, sessionId = "sessionB"),
            usageEntry(timestamp = now - 3L, sessionId = "sessionA")
        )
        val ledger = UsageLedger(entries = entries)

        val response = UsageHistoryAggregator.aggregate(
            ledger = ledger,
            request = GetUsageHistoryRequest(
                userId = "u1",
                window = UsageWindow.ALL_TIME,
                sessionId = "sessionA"
            ),
            now = now,
            liveSessionId = null
        )

        assertEquals(2, response.entries.size, "sessionA filter keeps 2 entries")
        assertTrue(response.entries.all { it.sessionId == "sessionA" })
    }

    // ─── Sorting + pagination ────────────────────────────────────────────

    @Test
    fun aggregate_entriesAreNewestFirst()
    {
        val now = 1_700_000_000_000L
        val entries = listOf(
            usageEntry(timestamp = now - 100, entryId = "old"),
            usageEntry(timestamp = now - 1,   entryId = "new"),
            usageEntry(timestamp = now - 50,  entryId = "mid")
        )
        val ledger = UsageLedger(entries = entries)

        val response = UsageHistoryAggregator.aggregate(
            ledger = ledger,
            request = GetUsageHistoryRequest(userId = "u1", window = UsageWindow.ALL_TIME),
            now = now,
            liveSessionId = null
        )

        assertEquals(listOf("new", "mid", "old"), response.entries.map { it.entryId })
    }

    @Test
    fun aggregate_paginationHonorsLimitAndCursor()
    {
        val now = 1_700_000_000_000L
        val entries = (1..10).map { i -> usageEntry(timestamp = now - i, entryId = "e$i") }
        val ledger = UsageLedger(entries = entries)

        val response = UsageHistoryAggregator.aggregate(
            ledger = ledger,
            request = GetUsageHistoryRequest(
                userId = "u1",
                window = UsageWindow.ALL_TIME,
                limit = 3
            ),
            now = now,
            liveSessionId = null
        )

        assertEquals(3, response.entries.size)
        assertEquals(listOf("e1", "e2", "e3"), response.entries.map { it.entryId })
        assertTrue(response.hasMore, "page 1 of size 3 over 10 entries has more")
        assertEquals("e3", response.nextCursor)

        val response2 = UsageHistoryAggregator.aggregate(
            ledger = ledger,
            request = GetUsageHistoryRequest(
                userId = "u1",
                window = UsageWindow.ALL_TIME,
                limit = 3,
                cursorEntryId = "e3"
            ),
            now = now,
            liveSessionId = null
        )

        assertEquals(listOf("e4", "e5", "e6"), response2.entries.map { it.entryId })
        assertTrue(response2.hasMore)
        assertEquals("e6", response2.nextCursor)
    }

    @Test
    fun aggregate_pagination_lastPage_signalsNoMore()
    {
        val now = 1_700_000_000_000L
        val entries = (1..3).map { i -> usageEntry(timestamp = now - i, entryId = "e$i") }
        val ledger = UsageLedger(entries = entries)

        val response = UsageHistoryAggregator.aggregate(
            ledger = ledger,
            request = GetUsageHistoryRequest(
                userId = "u1",
                window = UsageWindow.ALL_TIME,
                limit = 10
            ),
            now = now,
            liveSessionId = null
        )

        assertEquals(3, response.entries.size)
        assertFalse(response.hasMore)
        assertNull(response.nextCursor)
    }

    // ─── Aggregations ─────────────────────────────────────────────────────

    @Test
    fun aggregate_dailyBuckets_groupByUtcDay()
    {
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        val entries = listOf(
            usageEntry(timestamp = now - 1_000, sessionId = "s1"),       // day D
            usageEntry(timestamp = now - 2_000, sessionId = "s1"),       // day D
            usageEntry(timestamp = now - day - 1_000, sessionId = "s1"), // day D-1
            usageEntry(timestamp = now - 2 * day, sessionId = "s1")      // day D-2
        )
        val ledger = UsageLedger(entries = entries)

        val response = UsageHistoryAggregator.aggregate(
            ledger = ledger,
            request = GetUsageHistoryRequest(userId = "u1", window = UsageWindow.ALL_TIME),
            now = now,
            liveSessionId = null
        )

        assertEquals(3, response.daily.size, "three distinct UTC days")
        assertTrue(response.daily.first().dayStartMillis < response.daily.last().dayStartMillis,
            "daily buckets must be sorted ascending (oldest first) so the chart reads chronologically")
    }

    @Test
    fun aggregate_gameSummaries_flagsLiveSession()
    {
        val now = 1_700_000_000_000L
        val entries = listOf(
            usageEntry(timestamp = now - 1, sessionId = "sessionLive"),
            usageEntry(timestamp = now - 2, sessionId = "sessionOld")
        )
        val ledger = UsageLedger(entries = entries)

        val response = UsageHistoryAggregator.aggregate(
            ledger = ledger,
            request = GetUsageHistoryRequest(userId = "u1", window = UsageWindow.ALL_TIME),
            now = now,
            liveSessionId = "sessionLive"
        )

        val live = response.gameSummaries.first { it.sessionId == "sessionLive" }
        val old = response.gameSummaries.first { it.sessionId == "sessionOld" }
        assertTrue(live.isLive)
        assertFalse(old.isLive)
    }

    @Test
    fun aggregate_totalCreditsUsed_isAbsoluteValueSum()
    {
        val now = 1_700_000_000_000L
        val entries = listOf(
            usageEntry(timestamp = now - 1, creditsDelta = -5.0, sessionId = "s1"),
            usageEntry(timestamp = now - 2, creditsDelta = -3.0, sessionId = "s1"),
            usageEntry(timestamp = now - 3, creditsDelta = 10.0, sessionId = "s1") // refill
        )
        val ledger = UsageLedger(entries = entries)

        val response = UsageHistoryAggregator.aggregate(
            ledger = ledger,
            request = GetUsageHistoryRequest(userId = "u1", window = UsageWindow.ALL_TIME),
            now = now,
            liveSessionId = null
        )

        assertEquals(18.0, response.totalCreditsUsed, 0.0001, "5+3+10 (refill also counts as absolute)")
    }

    @Test
    fun aggregate_emptyLedger_returnsEmptyResponse()
    {
        val response = UsageHistoryAggregator.aggregate(
            ledger = UsageLedger(entries = emptyList()),
            request = GetUsageHistoryRequest(userId = "u1"),
            now = 1_700_000_000_000L,
            liveSessionId = null
        )
        assertTrue(response.entries.isEmpty())
        assertTrue(response.daily.isEmpty())
        assertTrue(response.gameSummaries.isEmpty())
        assertEquals(0.0, response.totalCreditsUsed)
        assertFalse(response.hasMore)
        assertNull(response.nextCursor)
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private fun usageEntry(
        entryId: String = "e-${System.nanoTime()}-${Math.random()}",
        timestamp: Long,
        sessionId: String = "sess",
        creditsDelta: Double = -1.0
    ) = UsageEntry(
        entryId = entryId,
        timestampMillis = timestamp,
        sourceLabel = "Test entry $entryId",
        sourceIconKey = "turn-resolution",
        creditsDelta = creditsDelta,
        balanceAfter = 100.0,
        sessionId = sessionId,
        inputTokens = 0,
        outputTokens = 0,
        accelByteUserId = "u1"
    )
}
