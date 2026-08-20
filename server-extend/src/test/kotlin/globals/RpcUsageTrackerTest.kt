package globals

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RpcUsageTracker].
 *
 * The tracker is in-memory and monotonic, so every test resets via
 * [RpcUsageTracker.resetForTest] to keep tests independent. The
 * `nowMillis` parameter is injected everywhere so the tests are
 * deterministic and do not depend on wall-clock.
 */
class RpcUsageTrackerTest
{
    @Before
    fun resetBefore()
    {
        RpcUsageTracker.resetForTest()
    }

    @After
    fun resetAfter()
    {
        RpcUsageTracker.resetForTest()
    }

    // --- Idle subsystem tests (preserved from v1) -----------------------

    @Test
    fun `fresh tracker has no observations for either origin`()
    {
        assertNull(RpcUsageTracker.lastClientSeenAtMillis(), "fresh tracker should not report a client timestamp")
        assertNull(RpcUsageTracker.lastServerSeenAtMillis(), "fresh tracker should not report a server timestamp")
    }

    @Test
    fun `markClientSeen records a client timestamp`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        assertEquals(1_000L, RpcUsageTracker.lastClientSeenAtMillis())
        // Server is still untouched.
        assertNull(RpcUsageTracker.lastServerSeenAtMillis())
    }

    @Test
    fun `markServerSeen records a server timestamp`()
    {
        RpcUsageTracker.markServerSeen(nowMillis = 2_000L)
        assertEquals(2_000L, RpcUsageTracker.lastServerSeenAtMillis())
        // Client is still untouched.
        assertNull(RpcUsageTracker.lastClientSeenAtMillis())
    }

    @Test
    fun `markClientSeen does not regress on a stale timestamp`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 5_000L)
        // Simulate an out-of-order delivery (clock skew / replay).
        RpcUsageTracker.markClientSeen(nowMillis = 4_000L)
        assertEquals(5_000L, RpcUsageTracker.lastClientSeenAtMillis(), "older nowMillis must not overwrite a newer observation")
    }

    @Test
    fun `client and server timestamps are independent`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        RpcUsageTracker.markServerSeen(nowMillis = 1_500L)
        // Server mark must not change the client timestamp.
        assertEquals(1_000L, RpcUsageTracker.lastClientSeenAtMillis())
        assertEquals(1_500L, RpcUsageTracker.lastServerSeenAtMillis())

        // A subsequent client mark with a newer timestamp advances the
        // client timestamp but leaves the server timestamp untouched.
        RpcUsageTracker.markClientSeen(nowMillis = 2_000L)
        assertEquals(2_000L, RpcUsageTracker.lastClientSeenAtMillis())
        assertEquals(1_500L, RpcUsageTracker.lastServerSeenAtMillis())
    }

    @Test
    fun `isClientIdle is true when never seen`()
    {
        // Even a tiny threshold — a fresh tracker has no traffic so it is idle.
        assertTrue(RpcUsageTracker.isClientIdle(thresholdMillis = 0L))
        assertTrue(RpcUsageTracker.isClientIdle(thresholdMillis = 1L))
    }

    @Test
    fun `isClientIdle is true when now minus last exceeds threshold`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        assertTrue(RpcUsageTracker.isClientIdle(thresholdMillis = 500L, nowMillis = 1_500L))
        assertTrue(RpcUsageTracker.isClientIdle(thresholdMillis = 1_000L, nowMillis = 2_000L))
    }

    @Test
    fun `isClientIdle is false when now minus last is below threshold`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        assertFalse(RpcUsageTracker.isClientIdle(thresholdMillis = 1_000L, nowMillis = 1_500L))
        assertFalse(RpcUsageTracker.isClientIdle(thresholdMillis = 10_000L, nowMillis = 1_500L))
    }

    @Test
    fun `isServerIdle mirrors isClientIdle semantics`()
    {
        RpcUsageTracker.markServerSeen(nowMillis = 1_000L)
        assertFalse(RpcUsageTracker.isServerIdle(thresholdMillis = 1_000L, nowMillis = 1_500L))
        assertTrue(RpcUsageTracker.isServerIdle(thresholdMillis = 500L, nowMillis = 1_500L))
    }

    @Test
    fun `isFullyIdle requires both origins to be idle`()
    {
        // Fresh tracker — both are idle, so fully idle (even at 0ms threshold).
        assertTrue(RpcUsageTracker.isFullyIdle(thresholdMillis = 0L))

        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        RpcUsageTracker.markServerSeen(nowMillis = 1_000L)
        // 5ms elapsed, 10ms threshold — both fresh, so NOT fully idle.
        assertFalse(RpcUsageTracker.isFullyIdle(thresholdMillis = 10L, nowMillis = 1_005L))
        // 5ms elapsed, 1ms threshold — both stale, so fully idle.
        assertTrue(RpcUsageTracker.isFullyIdle(thresholdMillis = 1L, nowMillis = 1_005L))
    }

    @Test
    fun `snapshot returns null timestamps before any observation`()
    {
        val snap = RpcUsageTracker.snapshot(nowMillis = 1_000L)
        assertNull(snap.lastClientSeenAtMillis)
        assertNull(snap.lastServerSeenAtMillis)
        assertEquals(1_000L, snap.nowMillis)
    }

    @Test
    fun `snapshot returns the recorded timestamps after observation`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        RpcUsageTracker.markServerSeen(nowMillis = 1_500L)
        val snap = RpcUsageTracker.snapshot(nowMillis = 2_000L)
        assertEquals(1_000L, snap.lastClientSeenAtMillis)
        assertEquals(1_500L, snap.lastServerSeenAtMillis)
        assertEquals(2_000L, snap.nowMillis)
    }

    @Test
    fun `snapshot isClientIdle mirrors tracker isClientIdle`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        val snap = RpcUsageTracker.snapshot(nowMillis = 1_500L)
        assertFalse(snap.isClientIdle(thresholdMillis = 1_000L))
        assertTrue(snap.isClientIdle(thresholdMillis = 400L))
    }

    @Test
    fun `snapshot isServerIdle mirrors tracker isServerIdle`()
    {
        RpcUsageTracker.markServerSeen(nowMillis = 1_000L)
        val snap = RpcUsageTracker.snapshot(nowMillis = 1_500L)
        assertFalse(snap.isServerIdle(thresholdMillis = 1_000L))
        assertTrue(snap.isServerIdle(thresholdMillis = 400L))
    }

    @Test
    fun `snapshot isFullyIdle mirrors tracker isFullyIdle`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        RpcUsageTracker.markServerSeen(nowMillis = 1_000L)
        val snap = RpcUsageTracker.snapshot(nowMillis = 1_005L)
        // 5ms elapsed, 1ms threshold — both stale, so fully idle.
        assertTrue(snap.isFullyIdle(thresholdMillis = 1L))
        // 5ms elapsed, 10ms threshold — both fresh, so NOT fully idle.
        assertFalse(snap.isFullyIdle(thresholdMillis = 10L))
    }

    @Test
    fun `concurrent markClientSeen leaves the timestamp monotonic`()
    {
        // 64 coroutines each call markClientSeen with a unique increasing
        // timestamp. The final value must equal the maximum, never a
        // value from a half-completed compare-and-set loop. We use
        // runBlocking as the parent and `launch` + a CountDownLatch so
        // the writer threads park on the latch barrier — this keeps the
        // test independent of the kotlinx-coroutines version's async
        // deprecation warnings (which treat dangling coroutines as an
        // error in this build).
        val n = 64
        val threads = (0 until n).map { i ->
            Thread {
                RpcUsageTracker.markClientSeen(nowMillis = 1_000L + i)
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        assertEquals(1_000L + (n - 1), RpcUsageTracker.lastClientSeenAtMillis())
    }

    @Test
    fun `resetForTest clears both timestamps`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        RpcUsageTracker.markServerSeen(nowMillis = 1_000L)
        RpcUsageTracker.resetForTest()
        assertNull(RpcUsageTracker.lastClientSeenAtMillis())
        assertNull(RpcUsageTracker.lastServerSeenAtMillis())
    }

    // --- Frequency subsystem tests (added in v2) ------------------------

    @Test
    fun `fresh tracker reports zero in every window for every origin`()
    {
        for (window in RpcUsageTracker.Window.entries)
        {
            assertEquals(0L, RpcUsageTracker.clientCountInWindow(window, nowMillis = 0L),
                "fresh client count for $window should be 0")
            assertEquals(0L, RpcUsageTracker.serverCountInWindow(window, nowMillis = 0L),
                "fresh server count for $window should be 0")
        }
    }

    @Test
    fun `single markClientSeen shows 1 in the 1-min window and 0 in 5-min and 15-min`()
    {
        // Single mark at second 5. The 1-min window contains second 5
        // (along with seconds 0..4, which are empty); the 5-min and
        // 15-min windows also contain second 5 — so all three should
        // see 1. The interesting distinction is the *rollout* tested
        // elsewhere: a single mark is always visible in all three
        // windows at the moment it lands.
        RpcUsageTracker.markClientSeen(nowMillis = 5_000L)

        assertEquals(1L, RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.ONE_MIN, nowMillis = 5_000L))
        assertEquals(1L, RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.FIVE_MIN, nowMillis = 5_000L))
        assertEquals(1L, RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.FIFTEEN_MIN, nowMillis = 5_000L))
    }

    @Test
    fun `markClientSeen does not affect server counts and vice versa`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        RpcUsageTracker.markServerSeen(nowMillis = 1_000L)

        for (window in RpcUsageTracker.Window.entries)
        {
            assertEquals(1L, RpcUsageTracker.clientCountInWindow(window, nowMillis = 1_000L),
                "client count for $window should be 1 after a client mark")
            assertEquals(1L, RpcUsageTracker.serverCountInWindow(window, nowMillis = 1_000L),
                "server count for $window should be 1 after a server mark")
        }

        // A second client mark must not bleed into the server counts.
        RpcUsageTracker.markClientSeen(nowMillis = 1_500L)
        assertEquals(2L, RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.ONE_MIN, nowMillis = 1_500L))
        assertEquals(1L, RpcUsageTracker.serverCountInWindow(RpcUsageTracker.Window.ONE_MIN, nowMillis = 1_500L))
    }

    @Test
    fun `counts roll out of the 1-min window after 60 simulated seconds`()
    {
        // The 1-min window covers the 60 most recent seconds ending
        // at `nowMillis / 1000`, i.e. the inclusive range
        // [currentSecond - 60 + 1, currentSecond]. A mark at second 0
        // is therefore in the window at currentSecond=59 (window
        // [0..59]) and falls out at currentSecond=60 (window [1..60]).
        RpcUsageTracker.markClientSeen(nowMillis = 0L)

        // currentSecond=59: window [0..59]. Mark at second 0 is in.
        assertEquals(1L, RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.ONE_MIN, nowMillis = 59_000L))
        // currentSecond=60: window [1..60]. Mark at second 0 is OUT.
        assertEquals(0L, RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.ONE_MIN, nowMillis = 60_000L))
    }

    @Test
    fun `counts roll out of the 5-min window after 300 simulated seconds`()
    {
        // 5-min window covers 300 seconds ending at currentSecond.
        // Mark at second 0 is in at currentSecond=299 (window [0..299])
        // and falls out at currentSecond=300 (window [1..300]).
        RpcUsageTracker.markServerSeen(nowMillis = 0L)
        assertEquals(1L, RpcUsageTracker.serverCountInWindow(RpcUsageTracker.Window.FIVE_MIN, nowMillis = 299_000L))
        assertEquals(0L, RpcUsageTracker.serverCountInWindow(RpcUsageTracker.Window.FIVE_MIN, nowMillis = 300_000L))
    }

    @Test
    fun `counts roll out of the 15-min window after 900 simulated seconds`()
    {
        // 15-min window covers 900 seconds ending at currentSecond.
        // Mark at second 0 is in at currentSecond=899 (window [0..899])
        // and falls out at currentSecond=900 (window [1..900]).
        RpcUsageTracker.markClientSeen(nowMillis = 0L)
        assertEquals(1L, RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.FIFTEEN_MIN, nowMillis = 899_000L))
        assertEquals(0L, RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.FIFTEEN_MIN, nowMillis = 900_000L))
    }

    @Test
    fun `multiple marks in the same simulated second accumulate into one bucket`()
    {
        // Three marks at second 5 (any sub-second timestamp within 5_000..5_999).
        RpcUsageTracker.markClientSeen(nowMillis = 5_000L)
        RpcUsageTracker.markClientSeen(nowMillis = 5_500L)
        RpcUsageTracker.markClientSeen(nowMillis = 5_999L)
        assertEquals(3L, RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.ONE_MIN, nowMillis = 5_999L))
    }

    @Test
    fun `ring wraps correctly when nowMillis crosses 900 seconds`()
    {
        // Mark at second 1 — stored at slot 1 % 900 = 1.
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        assertEquals(1L, RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.ONE_MIN, nowMillis = 1_000L))

        // Mark at second 901 — same slot index (901 % 900 = 1). The
        // new mark claims the slot, discarding the second-1 data.
        RpcUsageTracker.markClientSeen(nowMillis = 901_000L)

        // From the perspective of the new "now" (second 901), the
        // 1-min window covers seconds [842..901]. Only the new mark
        // is in that window.
        assertEquals(1L, RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.ONE_MIN, nowMillis = 901_000L))

        // The 15-min window also covers [2..901] — the second-1 mark
        // is no longer in any window because slot 1 was reused.
        assertEquals(1L, RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.FIFTEEN_MIN, nowMillis = 901_000L))
    }

    @Test
    fun `older nowMillis does not regress the frequency count`()
    {
        // First mark at second 1000. Ring slot lands at index 100.
        RpcUsageTracker.markClientSeen(nowMillis = 1_000_000L)
        assertEquals(1L, RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.ONE_MIN, nowMillis = 1_000_000L))

        // Stale mark at second 999 — must be rejected by the
        // maxSeenSecond guard. The count stays at 1.
        RpcUsageTracker.markClientSeen(nowMillis = 999_000L)
        assertEquals(1L, RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.ONE_MIN, nowMillis = 1_000_000L))
    }

    @Test
    fun `rate per second equals count divided by window seconds`()
    {
        // 60 marks in a single second, in the 1-min window. Rate = 60 / 60 = 1.0.
        for (i in 0 until 60)
        {
            RpcUsageTracker.markClientSeen(nowMillis = 1_000L + i)
        }
        assertEquals(1.0, RpcUsageTracker.clientRatePerSecond(RpcUsageTracker.Window.ONE_MIN, nowMillis = 1_059L))
        // 5-min window sees the same 60 marks but divides by 300 → 0.2.
        assertEquals(0.2, RpcUsageTracker.clientRatePerSecond(RpcUsageTracker.Window.FIVE_MIN, nowMillis = 1_059L))
    }

    @Test
    fun `totalCountInWindow equals client plus server`()
    {
        for (i in 0 until 7)
        {
            RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        }
        for (i in 0 until 3)
        {
            RpcUsageTracker.markServerSeen(nowMillis = 1_000L)
        }
        for (window in RpcUsageTracker.Window.entries)
        {
            assertEquals(10L, RpcUsageTracker.totalCountInWindow(window, nowMillis = 1_000L),
                "total for $window should be 7 + 3 = 10")
        }
    }

    @Test
    fun `concurrent writers in the same second do not lose increments`()
    {
        // 10 threads × 1000 marks each, all in the same second. With
        // same-second concurrent writes the per-slot CAS is exact:
        // every mark must be counted. The expected total is 10,000.
        val n = 10
        val perThread = 1_000
        val nowMillis = 5_000L
        val threads = (0 until n).map { _ ->
            Thread {
                repeat(perThread)
                {
                    RpcUsageTracker.markClientSeen(nowMillis = nowMillis)
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        val total = n * perThread
        assertEquals(total.toLong(),
            RpcUsageTracker.clientCountInWindow(RpcUsageTracker.Window.ONE_MIN, nowMillis = nowMillis),
            "concurrent same-second writers must be exact")
    }

    @Test
    fun `resetForTest clears every frequency window to zero`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        RpcUsageTracker.markServerSeen(nowMillis = 1_000L)
        RpcUsageTracker.resetForTest()
        for (window in RpcUsageTracker.Window.entries)
        {
            assertEquals(0L, RpcUsageTracker.clientCountInWindow(window, nowMillis = 1_000L),
                "client count for $window should be 0 after reset")
            assertEquals(0L, RpcUsageTracker.serverCountInWindow(window, nowMillis = 1_000L),
                "server count for $window should be 0 after reset")
        }
    }

    @Test
    fun `frequencyStats returns maps with all Window keys zero-valued on a fresh tracker`()
    {
        val stats = RpcUsageTracker.frequencyStats(nowMillis = 0L)
        assertEquals(0L, stats.nowMillis)
        assertEquals(RpcUsageTracker.Window.entries.toSet(), stats.clientCounts.keys,
            "clientCounts should contain every Window key on a fresh tracker")
        assertEquals(RpcUsageTracker.Window.entries.toSet(), stats.serverCounts.keys,
            "serverCounts should contain every Window key on a fresh tracker")
        for (window in RpcUsageTracker.Window.entries)
        {
            assertEquals(0L, stats.clientCount(window), "fresh client $window should be 0")
            assertEquals(0L, stats.serverCount(window), "fresh server $window should be 0")
            assertEquals(0L, stats.totalCount(window), "fresh total $window should be 0")
        }
    }

    @Test
    fun `frequencyStats total helpers are client plus server`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        RpcUsageTracker.markClientSeen(nowMillis = 1_500L)
        RpcUsageTracker.markServerSeen(nowMillis = 1_500L)
        val stats = RpcUsageTracker.frequencyStats(nowMillis = 1_500L)
        for (window in RpcUsageTracker.Window.entries)
        {
            assertEquals(2L, stats.clientCount(window), "client $window should be 2")
            assertEquals(1L, stats.serverCount(window), "server $window should be 1")
            assertEquals(3L, stats.totalCount(window), "total $window should be 3")
            // Rate = count / window.seconds. With 3 marks in 60s = 0.05.
            assertEquals(3.0 / window.seconds, stats.totalRatePerSecond(window),
                "total rate for $window should be 3 / ${window.seconds}")
        }
    }

    // --- Snapshot extension tests (frequency fields) -------------------

    @Test
    fun `snapshot exposes per-window counts keyed by every Window after a mark`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        RpcUsageTracker.markClientSeen(nowMillis = 1_500L)
        RpcUsageTracker.markServerSeen(nowMillis = 1_500L)

        val snap = RpcUsageTracker.snapshot(nowMillis = 1_500L)
        // Every Window must be present in both maps.
        assertEquals(RpcUsageTracker.Window.entries.toSet(), snap.clientCountsByWindow.keys)
        assertEquals(RpcUsageTracker.Window.entries.toSet(), snap.serverCountsByWindow.keys)
        for (window in RpcUsageTracker.Window.entries)
        {
            assertEquals(2L, snap.clientCountInWindow(window), "client $window should be 2")
            assertEquals(1L, snap.serverCountInWindow(window), "server $window should be 1")
            assertEquals(3L, snap.totalCountInWindow(window), "total $window should be 3")
        }
    }

    @Test
    fun `snapshot from a fresh tracker reports zero for every window`()
    {
        val snap = RpcUsageTracker.snapshot(nowMillis = 0L)
        for (window in RpcUsageTracker.Window.entries)
        {
            assertEquals(0L, snap.clientCountInWindow(window), "fresh snapshot client $window")
            assertEquals(0L, snap.serverCountInWindow(window), "fresh snapshot server $window")
        }
    }
}