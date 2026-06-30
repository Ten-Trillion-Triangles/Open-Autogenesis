package org.ttt.autogenesis.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Basic cross-platform verification that the RestRpcBridge API remains stable.
 */
class RestRpcBridgeTest
{
    /**
     * Ensures the bridge exposes the public surface without a connection.
     */
    @Test
    fun `RestRpcBridge exposes the expected properties when idle`()
    {
        assertFalse(RestRpcBridge.isConnected)
        assertFalse(RestRpcBridge.isSessionReady)
        assertNull(RestRpcBridge.rpcInvoker)
    }

    /**
     * Verifies handlers can be registered via the shared registry helper.
     */
    @Test
    fun `RestRpcBridge registry accepts handler registrations`()
    {
        RestRpcBridge.registerHandlers {
            register("test.ping", RpcDirection.CLIENT) { _, _ -> null }
        }
    }

    /**
     * Confirms the configuration helper produces expected endpoints and IDs.
     */
    @Test
    fun `RestRpcBridge configuration helpers return consistent values`()
    {
        assertEquals("http://localhost:9090", RestRpcBridgeConfig.development(9090))
        assertEquals("https://prod.example.com", RestRpcBridgeConfig.production("prod.example.com"))
        assertTrue(RestRpcBridge.generatePlayerId().startsWith(RestRpcBridgeConfig.PLAYER_ID_PREFIX))
    }

    /**
     * Verifies [org.ttt.autogenesis.network.RestRpcClientClosedException] is a
     * [kotlinx.coroutines.CancellationException] subclass so awaiting
     * coroutines surface it correctly under structured cancellation.
     */
    @Test
    fun `RestRpcClientClosedException is a CancellationException`()
    {
        val err = RestRpcClientClosedException("teardown")
        assertTrue(err is kotlinx.coroutines.CancellationException)
        assertEquals("teardown", err.message)
    }

    /**
     * Verifies [org.ttt.autogenesis.network.RestRpcClientConfig] now exposes
     * a tunable `drainTimeoutMillis` knob (default 250) so callers can adjust
     * the teardown grace window without recompiling.
     */
    @Test
    fun `RestRpcClientConfig exposes drainTimeoutMillis with a sensible default`()
    {
        val cfg = RestRpcClientConfig(
            baseUrl = "http://localhost:7070",
            playerId = "rest-client-test"
        )
        assertEquals(250L, cfg.drainTimeoutMillis)
    }

    /**
     * Negative drain timeouts must be rejected so the bridge never enters a
     * state where it can hang forever on close.
     */
    @Test
    fun `RestRpcClientConfig rejects negative drainTimeoutMillis`()
    {
        val err = assertFailsWith<IllegalArgumentException> {
            RestRpcClientConfig(
                baseUrl = "http://localhost:7070",
                playerId = "rest-client-test",
                drainTimeoutMillis = -1L
            )
        }
        assertTrue(err.message?.contains("drainTimeoutMillis") == true)
    }
}

