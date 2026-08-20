package org.ttt.autogenesis.serverextend

import globals.RpcUsageTracker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the [buildStatusSnapshot] helper used by both the REST
 * route (`GET /admin/status`) and the RPC handler
 * (`server.extend.getStatus`). The helper is the single source of
 * truth for the wire shape — both surfaces read from it, so testing
 * the helper pins both.
 *
 * The RPC handler itself is just `authCheck(buildStatusSnapshot())`
 * plus a log line — its test is the auth-gate behavior, which is
 * covered in [ServerExtendAdminRouteTest] for the REST route and
 * by the `unit` shape of the helper for the RPC method. A future
 * refactor that adds non-trivial logic to the RPC handler would
 * warrant an integration test against the full RPC registry.
 */
class BuildStatusSnapshotTest
{
    @Before
    fun resetBefore()
    {
        runBlocking {
            RpcUsageTracker.resetForTest()
            matchmaking.ServerConnector.clearGameSessionsForTest()
        }
    }

    @After
    fun resetAfter()
    {
        runBlocking {
            RpcUsageTracker.resetForTest()
            matchmaking.ServerConnector.clearGameSessionsForTest()
        }
    }

    @Test
    fun `snapshot on a fresh tracker reports fully idle with no sessions`()
    {
        val snapshot = runBlocking { buildStatusSnapshot(nowMillis = 1_000_000L) }
        assertEquals(1_000_000L, snapshot.nowMillis)
        assertEquals(0, snapshot.activeSessionCount)
        assertEquals(null, snapshot.oldestSessionAgeMillis)
        assertTrue(snapshot.sessions.isEmpty())
        assertEquals(null, snapshot.realActivity.lastClientRealActivityAtMillis)
        assertEquals(null, snapshot.realActivity.lastServerRealActivityAtMillis)
        assertTrue(snapshot.realActivity.isClientIdleForRealActivity)
        assertTrue(snapshot.realActivity.isServerIdleForRealActivity)
        // isFullyIdleForRealActivity requires BOTH origins idle AND
        // no active sessions — the spin-down signal.
        assertTrue(snapshot.isFullyIdleForRealActivity,
            "fresh tracker with no sessions must report fully idle (spin-down signal)")
    }

    @Test
    fun `snapshot reflects real-activity mark on the client origin`()
    {
        runBlocking {
            RpcUsageTracker.markClientRealActivitySeen(nowMillis = 1_000_000L)
        }
        // nowMillis well within threshold.
        val snapshot = runBlocking { buildStatusSnapshot(nowMillis = 1_000_001L) }
        assertEquals(1_000_000L, snapshot.realActivity.lastClientRealActivityAtMillis)
        assertTrue(!snapshot.realActivity.isClientIdleForRealActivity,
            "real client RPC within threshold must report non-idle")
    }

    @Test
    fun `snapshot reflects real-activity mark on the server origin`()
    {
        runBlocking {
            RpcUsageTracker.markServerRealActivitySeen(nowMillis = 5_000_000L)
        }
        val snapshot = runBlocking { buildStatusSnapshot(nowMillis = 5_000_001L) }
        assertEquals(5_000_000L, snapshot.realActivity.lastServerRealActivityAtMillis)
        assertTrue(!snapshot.realActivity.isServerIdleForRealActivity)
    }

    @Test
    fun `snapshot isFullyIdleForRealActivity requires both origins idle AND no sessions`()
    {
        // Both origins idle, no sessions → spin-down signal true.
        val idle = runBlocking { buildStatusSnapshot(nowMillis = 1_000_000L) }
        assertTrue(idle.isFullyIdleForRealActivity)

        // Real activity on client → no longer fully idle.
        runBlocking {
            RpcUsageTracker.markClientRealActivitySeen(nowMillis = 1_000_000L)
        }
        val activeClient = runBlocking { buildStatusSnapshot(nowMillis = 1_000_001L) }
        assertTrue(!activeClient.isFullyIdleForRealActivity,
            "real client activity must defeat the fully-idle signal")
    }

    @Test
    fun `snapshot realActivity block carries the configured threshold`()
    {
        val snapshot = runBlocking { buildStatusSnapshot(nowMillis = 1_000_000L) }
        // The threshold is read from ExtendConfig.idleThresholdMinutes,
        // default 15. Assert >= 1 — exact value is operator-configurable.
        assertTrue(snapshot.realActivity.idleThresholdMinutes >= 1L,
            "idleThresholdMinutes must be a positive integer")
    }

    @Test
    fun `snapshot nowMillis echoes the input parameter (not wall-clock)`()
    {
        // Pin the contract: the snapshot uses the supplied nowMillis
        // for every age / idle computation. A test that asserts on
        // a wall-clock-derived value would be flaky.
        val snapshot = runBlocking { buildStatusSnapshot(nowMillis = 1_234_567L) }
        assertEquals(1_234_567L, snapshot.nowMillis)
    }

    @Test
    fun `snapshot is internally consistent when activity is recent`()
    {
        // Real activity at the exact nowMillis — the age math must
        // produce 0 (or null) consistently.
        runBlocking {
            RpcUsageTracker.markClientRealActivitySeen(nowMillis = 1_000_000L)
            RpcUsageTracker.markServerRealActivitySeen(nowMillis = 1_000_000L)
        }
        val snapshot = runBlocking { buildStatusSnapshot(nowMillis = 1_000_000L) }
        assertNotNull(snapshot.realActivity.lastClientRealActivityAtMillis)
        assertNotNull(snapshot.realActivity.lastServerRealActivityAtMillis)
        // isFullyIdleForRealActivity requires no active sessions.
        // activeSessionCount is 0 in this test → both origins are
        // fresh BUT no sessions are active. Wait: the spin-down
        // signal requires BOTH origins idle. Origin fresh = NOT idle.
        // So isFullyIdleForRealActivity must be false here.
        assertTrue(!snapshot.isFullyIdleForRealActivity,
            "fresh real-activity on both origins must defeat the spin-down signal even with no sessions")
    }
}
