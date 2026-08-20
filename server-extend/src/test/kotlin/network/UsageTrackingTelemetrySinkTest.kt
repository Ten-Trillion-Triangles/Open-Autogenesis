package network

import globals.RpcUsageTracker
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcError
import org.ttt.autogenesis.network.RpcOrigin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [UsageTrackingTelemetrySink].
 *
 * The sink takes a single [RpcOrigin] at construction time (the
 * per-session origin) and is therefore parameter-free at call
 * time. Every test resets the global [RpcUsageTracker] via
 * [RpcUsageTracker.resetForTest] to keep assertions independent.
 */
class UsageTrackingTelemetrySinkTest
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

    @Test
    fun `sink tagged GAME_CLIENT advances the client counter on onRequestSent`()
    {
        val sink = UsageTrackingTelemetrySink(RpcOrigin.GAME_CLIENT)
        sink.onRequestSent(id = "req-1", method = "server.extend.getMasterRecord")
        assertNotNull(RpcUsageTracker.lastClientSeenAtMillis(), "client timestamp should be set")
        assertNull(RpcUsageTracker.lastServerSeenAtMillis(), "server timestamp should remain unset")
    }

    @Test
    fun `sink tagged GAME_SERVER advances the server counter on onRequestSent`()
    {
        val sink = UsageTrackingTelemetrySink(RpcOrigin.GAME_SERVER)
        sink.onRequestSent(id = "req-1", method = "server.extend.getUsageLedger")
        assertNotNull(RpcUsageTracker.lastServerSeenAtMillis(), "server timestamp should be set")
        assertNull(RpcUsageTracker.lastClientSeenAtMillis(), "client timestamp should remain unset")
    }

    @Test
    fun `sink ignores the per-RPC id parameter — origin is fixed at construction`()
    {
        val sink = UsageTrackingTelemetrySink(RpcOrigin.GAME_CLIENT)
        // The `id` argument is a random long; the sink must not consult it
        // and must always classify by its captured origin. Different
        // ids route to the same counter.
        sink.onRequestSent(id = "any-id-a", method = "m1")
        sink.onRequestSent(id = "any-id-b", method = "m2")
        assertNotNull(RpcUsageTracker.lastClientSeenAtMillis())
        assertNull(RpcUsageTracker.lastServerSeenAtMillis(), "a GAME_CLIENT sink must never advance the server counter")
    }

    @Test
    fun `onRequestCompleted does not advance any counter`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 100L)
        val sink = UsageTrackingTelemetrySink(RpcOrigin.GAME_CLIENT)
        sink.onRequestCompleted(
            id = "req-1",
            method = "server.extend.getMasterRecord",
            durationMillis = 50L,
            error = null
        )
        assertEquals(100L, RpcUsageTracker.lastClientSeenAtMillis(), "client timestamp must be unchanged by onRequestCompleted")
    }

    @Test
    fun `onRequestCompleted with an error does not advance any counter`()
    {
        RpcUsageTracker.markServerSeen(nowMillis = 200L)
        val sink = UsageTrackingTelemetrySink(RpcOrigin.GAME_SERVER)
        sink.onRequestCompleted(
            id = "req-1",
            method = "server.extend.getUsageLedger",
            durationMillis = 50L,
            error = RpcError(code = 500, message = "boom")
        )
        assertEquals(200L, RpcUsageTracker.lastServerSeenAtMillis(), "server timestamp must be unchanged by onRequestCompleted even on error")
    }

    @Test
    fun `onRequestCancelled does not advance any counter`()
    {
        RpcUsageTracker.markClientSeen(nowMillis = 100L)
        val sink = UsageTrackingTelemetrySink(RpcOrigin.GAME_CLIENT)
        sink.onRequestCancelled(id = "req-1", method = "server.extend.getMasterRecord", reason = "client_gone")
        assertEquals(100L, RpcUsageTracker.lastClientSeenAtMillis(), "client timestamp must be unchanged by onRequestCancelled")
    }

    @Test
    fun `multiple onRequestSent calls advance the timestamp monotonically`()
    {
        val sink = UsageTrackingTelemetrySink(RpcOrigin.GAME_CLIENT)
        sink.onRequestSent(id = "1", method = "a")
        val first = RpcUsageTracker.lastClientSeenAtMillis()
        sink.onRequestSent(id = "2", method = "b")
        val second = RpcUsageTracker.lastClientSeenAtMillis()
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(second >= first, "the timestamp should be monotonic (or equal) across multiple onRequestSent calls")
    }

    private fun assertTrue(condition: Boolean, message: String)
    {
        if (!condition) throw AssertionError(message)
    }
}