package org.ttt.autogenesis.serverextend

import globals.ExtendConfig
import globals.RpcUsageQuery
import globals.RpcUsageTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcMessage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the per-origin RPC usage tracker. These tests
 * exercise the full chain from the per-session [org.ttt.autogenesis.network.RpcInvoker]
 * through the shared [network.UsageTrackingTelemetrySink] into
 * [RpcUsageTracker], using the real [RestPlayerConnectionManager]
 * (the same one used in production) but bypassing the Ktor HTTP layer.
 *
 * The HTTP layer is covered separately by the existing
 * [ServerExtendHttpTest] / [ServerExtendHttpRaceTest] for the
 * `?origin=…` query-parameter parsing in the `/events` route. Bypassing
 * the HTTP layer here keeps the tests fast and deterministic — there
 * is no SSE/POST race to navigate and no Ktor test-host lifecycle to
 * coordinate.
 */
class RpcUsageTrackingIntegrationTest
{
    private val clientPlayerId = "tracking-client-1"
    private val serverPlayerId = "tracking-server-1"

    private var savedThresholdMinutes : Long = ExtendConfig.idleThresholdMinutes

    @Before
    fun resetBefore()
    {
        RpcUsageTracker.resetForTest()
    }

    @After
    fun resetAfter()
    {
        RpcUsageTracker.resetForTest()
        ExtendConfig.idleThresholdMinutes = savedThresholdMinutes
    }

    /**
     * Helper: register a session with the given origin and return the
     * per-session invoker so the test can issue a Request directly. Uses
     * a real [RestPlayerConnectionManager] (the same class the Ktor
     * routes use) so the telemetry sink is wired through the real
     * `RpcInvoker` constructor.
     */
    private suspend fun registerAndGetInvoker(
        manager: RestPlayerConnectionManager,
        playerId: String,
        origin: org.ttt.autogenesis.network.RpcOrigin
    ) = manager.register(playerId, origin).session.invoker

    /**
     * Fire a Request through the per-session invoker. The request will
     * time out (no client is listening) but [org.ttt.autogenesis.network.RpcTelemetrySink.onRequestSent]
     * is called synchronously *before* the sender returns, so by the
     * time this helper completes the tracker has already been advanced.
     * Catches the expected timeout so the test can assert on the
     * tracker state without a 5-second wait.
     */
    private suspend fun fireRequestAndSwallowTimeout(invoker: org.ttt.autogenesis.network.RpcInvoker, method: String)
    {
        try
        {
            invoker.request(method, params = null, timeoutMillis = 200L).await()
        }
        catch (e: org.ttt.autogenesis.network.RpcTimeoutException)
        {
            // Expected — no client is listening for the response. The
            // telemetry sink's onRequestSent has already run by now.
        }
    }

    @Test
    fun `default origin marks client traffic and advances the client timestamp`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        val invoker = registerAndGetInvoker(manager, clientPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_CLIENT)

        assertNull(RpcUsageTracker.lastClientSeenAtMillis(), "no client traffic yet")
        assertNull(RpcUsageTracker.lastServerSeenAtMillis(), "no server traffic yet")

        fireRequestAndSwallowTimeout(invoker, "server.extend.getMasterRecord")

        val last = RpcUsageTracker.lastClientSeenAtMillis()
        assertNotNull(last, "client timestamp should be set after a game-client Request")
        assertNull(RpcUsageTracker.lastServerSeenAtMillis(), "server timestamp must remain unset")

        manager.deregister(clientPlayerId)
    }

    @Test
    fun `GAME_SERVER origin marks server traffic and advances the server timestamp`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        val invoker = registerAndGetInvoker(manager, serverPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_SERVER)

        fireRequestAndSwallowTimeout(invoker, "server.extend.getUsageLedger")

        val lastServer = RpcUsageTracker.lastServerSeenAtMillis()
        assertNotNull(lastServer, "server timestamp should be set after a game-server Request")
        assertNull(RpcUsageTracker.lastClientSeenAtMillis(), "client timestamp must remain unset")

        manager.deregister(serverPlayerId)
    }

    @Test
    fun `client and server origins are independent`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        val clientInvoker = registerAndGetInvoker(manager, clientPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_CLIENT)
        val serverInvoker = registerAndGetInvoker(manager, serverPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_SERVER)

        fireRequestAndSwallowTimeout(clientInvoker, "a")
        val clientTs = RpcUsageTracker.lastClientSeenAtMillis()
        assertNotNull(clientTs)

        fireRequestAndSwallowTimeout(serverInvoker, "b")
        val serverTs = RpcUsageTracker.lastServerSeenAtMillis()
        assertNotNull(serverTs)

        // The two timestamps are independent — neither overwrites the other.
        assertEquals(clientTs, RpcUsageTracker.lastClientSeenAtMillis())
        assertEquals(serverTs, RpcUsageTracker.lastServerSeenAtMillis())

        manager.deregister(clientPlayerId)
        manager.deregister(serverPlayerId)
    }

    @Test
    fun `RpcUsageQuery facade uses ExtendConfig threshold`() = runBlocking {
        savedThresholdMinutes = ExtendConfig.idleThresholdMinutes
        ExtendConfig.idleThresholdMinutes = 1L

        val manager = RestPlayerConnectionManager()
        // No traffic yet — both origins idle.
        assertTrue(RpcUsageQuery.isClientIdleNow(), "no client traffic yet → idle")
        assertTrue(RpcUsageQuery.isServerIdleNow(), "no server traffic yet → idle")
        assertTrue(RpcUsageQuery.isFullyIdleNow(), "no traffic yet → fully idle")

        val invoker = registerAndGetInvoker(manager, clientPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_CLIENT)
        fireRequestAndSwallowTimeout(invoker, "a")

        assertFalse(
            RpcUsageQuery.isClientIdleNow(),
            "client RPC just landed → not idle at 1-min threshold"
        )
        assertTrue(
            RpcUsageQuery.isServerIdleNow(),
            "no server RPC yet → still idle"
        )
        assertFalse(
            RpcUsageQuery.isFullyIdleNow(),
            "client fresh, server idle → not fully idle"
        )

        manager.deregister(clientPlayerId)
    }

    @Test
    fun `RpcUsageQuery snapshot returns both timestamps after mixed traffic`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        val clientInvoker = registerAndGetInvoker(manager, clientPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_CLIENT)
        val serverInvoker = registerAndGetInvoker(manager, serverPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_SERVER)

        fireRequestAndSwallowTimeout(clientInvoker, "a")
        fireRequestAndSwallowTimeout(serverInvoker, "b")

        val snap = RpcUsageQuery.snapshot()
        assertNotNull(snap.lastClientSeenAtMillis)
        assertNotNull(snap.lastServerSeenAtMillis)

        manager.deregister(clientPlayerId)
        manager.deregister(serverPlayerId)
    }

    @Test
    fun `notifications do not advance any counter`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        val invoker = registerAndGetInvoker(manager, clientPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_CLIENT)
        // Inject a Notification through the invoker's per-session sender
        // by writing to the SSE channel — easier to verify the
        // counter does not move than to drive the Ktor SSE/POST plumbing
        // here. We rely on the unit tests in UsageTrackingTelemetrySinkTest
        // to confirm the sink ignores Notifications (the sink's only
        // side effect is on onRequestSent, so any non-Request path is
        // a no-op by construction).
        val session = manager.findSession(clientPlayerId)!!
        session.sendRpcMessage(
            RpcMessage.Notification(method = "session.ready")
        )
        // Yield so the channel has a chance to flush.
        delay(50)
        assertNull(RpcUsageTracker.lastClientSeenAtMillis(), "Notifications must not advance the counter")
        assertNull(RpcUsageTracker.lastServerSeenAtMillis(), "Notifications must not advance the counter")
        manager.deregister(clientPlayerId)
    }

    @Test
    fun `originFor returns the tag assigned at register time`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        manager.register(clientPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_CLIENT)
        manager.register(serverPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_SERVER)
        assertEquals(org.ttt.autogenesis.network.RpcOrigin.GAME_CLIENT, manager.originFor(clientPlayerId))
        assertEquals(org.ttt.autogenesis.network.RpcOrigin.GAME_SERVER, manager.originFor(serverPlayerId))
        assertNull(manager.originFor("nobody"), "unknown player has no origin")
        manager.deregister(clientPlayerId)
        manager.deregister(serverPlayerId)
    }

    @Test
    fun `default origin defaults to GAME_CLIENT for backwards compatibility`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        // Call the no-origin overload — must default to GAME_CLIENT.
        manager.register("legacy-player")
        assertEquals(org.ttt.autogenesis.network.RpcOrigin.GAME_CLIENT, manager.originFor("legacy-player"))
        manager.deregister("legacy-player")
    }

    // --- Frequency-subsystem integration tests (added in v2) -----------
    //
    // These tests exercise the same `RestPlayerConnectionManager` ->
    // per-session `UsageTrackingTelemetrySink` -> `RpcUsageTracker`
    // chain as the idle tests above, but assert on the per-window
    // counts instead of the idle timestamps. They guarantee that the
    // ring buffer is fed by the same telemetry hook as the idle
    // subsystem, so a per-origin control-plane consumer can trust
    // `RpcUsageQuery.clientCountInWindow(...)` without an end-to-end
    // replay through Ktor.

    @Test
    fun `client RPC through the sink increments client count in every window`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        val invoker = registerAndGetInvoker(manager, clientPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_CLIENT)

        repeat(5) {
            fireRequestAndSwallowTimeout(invoker, "server.extend.getMasterRecord")
        }

        val stats = RpcUsageQuery.frequencyStats()
        for (window in globals.RpcUsageTracker.Window.entries)
        {
            assertEquals(5L, stats.clientCount(window),
                "client count in $window should be 5 after 5 client requests")
            assertEquals(0L, stats.serverCount(window),
                "server count in $window should be 0 — no server traffic")
        }

        manager.deregister(clientPlayerId)
    }

    @Test
    fun `server RPC through the sink does not pollute client counts`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        val clientInvoker = registerAndGetInvoker(manager, clientPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_CLIENT)
        val serverInvoker = registerAndGetInvoker(manager, serverPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_SERVER)

        repeat(3) {
            fireRequestAndSwallowTimeout(clientInvoker, "a")
        }
        repeat(7) {
            fireRequestAndSwallowTimeout(serverInvoker, "b")
        }

        val stats = RpcUsageQuery.frequencyStats()
        for (window in globals.RpcUsageTracker.Window.entries)
        {
            assertEquals(3L, stats.clientCount(window),
                "client $window should reflect only client traffic")
            assertEquals(7L, stats.serverCount(window),
                "server $window should reflect only server traffic")
            assertEquals(10L, stats.totalCount(window),
                "total $window should be client + server = 10")
        }

        manager.deregister(clientPlayerId)
        manager.deregister(serverPlayerId)
    }

    @Test
    fun `snapshot from RpcUsageQuery carries per-window counts after mixed traffic`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        val clientInvoker = registerAndGetInvoker(manager, clientPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_CLIENT)
        val serverInvoker = registerAndGetInvoker(manager, serverPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_SERVER)

        repeat(2) { fireRequestAndSwallowTimeout(clientInvoker, "a") }
        repeat(4) { fireRequestAndSwallowTimeout(serverInvoker, "b") }

        val snap = RpcUsageQuery.snapshot()
        assertNotNull(snap.lastClientSeenAtMillis)
        assertNotNull(snap.lastServerSeenAtMillis)
        for (window in globals.RpcUsageTracker.Window.entries)
        {
            assertEquals(2L, snap.clientCountInWindow(window),
                "snapshot client $window should be 2")
            assertEquals(4L, snap.serverCountInWindow(window),
                "snapshot server $window should be 4")
            assertEquals(6L, snap.totalCountInWindow(window),
                "snapshot total $window should be 6")
        }

        manager.deregister(clientPlayerId)
        manager.deregister(serverPlayerId)
    }

    @Test
    fun `client rate per second reflects observed client traffic in the 1-min window`() = runBlocking {
        val manager = RestPlayerConnectionManager()
        val invoker = registerAndGetInvoker(manager, clientPlayerId, org.ttt.autogenesis.network.RpcOrigin.GAME_CLIENT)

        repeat(30) { fireRequestAndSwallowTimeout(invoker, "a") }

        // 30 marks within the same simulated instant all land in the
        // current second. Rate = 30 / 60 = 0.5 RPCs/second over the
        // 1-min window.
        val rate = RpcUsageQuery.clientRatePerSecond(globals.RpcUsageTracker.Window.ONE_MIN)
        assertEquals(30.0 / 60.0, rate, 1e-9)

        manager.deregister(clientPlayerId)
    }
}