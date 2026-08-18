package globals

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RpcUsageQuery].
 *
 * The query object is a thin pass-through over [RpcUsageTracker]; the
 * meaningful assertion is that the facade reads from the correct
 * tracker field (real-activity vs total) so a future refactor that
 * accidentally swaps the delegation cannot silently regress the
 * cost-control signal.
 */
class RpcUsageQueryTest
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

    // --- Real-activity passthroughs (Phase 1 / cost-control plan) ------

    @Test
    fun `isClientIdleForRealActivityNow is true when tracker has never seen a real client RPC`()
    {
        // Fresh tracker → no client real-activity observation → idle.
        assertTrue(RpcUsageQuery.isClientIdleForRealActivityNow())
    }

    @Test
    fun `isClientIdleForRealActivityNow is false when real client RPC happened within threshold`()
    {
        // Real activity at second 1_000_000 (well within the 15-min
        // threshold at nowMillis = 1_000_000).
        RpcUsageTracker.markClientRealActivitySeen(nowMillis = 1_000_000L)
        assertFalse(RpcUsageQuery.isClientIdleForRealActivityNow(nowMillis = 1_000_000L))
    }

    @Test
    fun `isClientIdleForRealActivityNow stays true even when only heartbeats have arrived`()
    {
        // Heartbeat-style pattern. Total counter advances but
        // real-activity counter does not.
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        RpcUsageTracker.markClientSeen(nowMillis = 2_000L)
        // Total counter is fresh; real-activity counter is null.
        // The cost-control query must report idle.
        assertTrue(RpcUsageQuery.isClientIdleForRealActivityNow(),
            "real-activity query must ignore heartbeat-only traffic")
    }

    @Test
    fun `isServerIdleForRealActivityNow mirrors client semantics for server origin`()
    {
        RpcUsageTracker.markServerSeen(nowMillis = 1_000L)
        assertTrue(RpcUsageQuery.isServerIdleForRealActivityNow(),
            "server real-activity query must ignore heartbeat-only traffic")
    }

    @Test
    fun `lastClientRealActivityAtMillis returns null on a fresh tracker`()
    {
        assertNull(RpcUsageQuery.lastClientRealActivityAtMillis())
    }

    @Test
    fun `lastClientRealActivityAtMillis returns the markClientRealActivitySeen timestamp`()
    {
        RpcUsageTracker.markClientRealActivitySeen(nowMillis = 1_234L)
        assertEquals(1_234L, RpcUsageQuery.lastClientRealActivityAtMillis())
    }

    @Test
    fun `lastServerRealActivityAtMillis returns null on a fresh tracker`()
    {
        assertNull(RpcUsageQuery.lastServerRealActivityAtMillis())
    }

    @Test
    fun `lastServerRealActivityAtMillis returns the markServerRealActivitySeen timestamp`()
    {
        RpcUsageTracker.markServerRealActivitySeen(nowMillis = 5_678L)
        assertEquals(5_678L, RpcUsageQuery.lastServerRealActivityAtMillis())
    }

    @Test
    fun `lastClientRealActivityAtMillis differs from lastClientSeenAtMillis when traffic was heartbeat-only`()
    {
        // Pin the delegation: heartbeat traffic must produce a non-null
        // lastClientSeenAtMillis but a null lastClientRealActivityAtMillis.
        RpcUsageTracker.markClientSeen(nowMillis = 1_000L)
        assertEquals(1_000L, RpcUsageTracker.lastClientSeenAtMillis())
        assertNull(RpcUsageQuery.lastClientRealActivityAtMillis(),
            "query lastClientRealActivityAtMillis must read the real-activity field, not the total field")
    }
}