package org.ttt.autogenesis.serverextend

import org.junit.After
import org.junit.Test
import org.ttt.autogenesis.serverextend.ResumeAvailabilityPushService
import kotlin.test.assertEquals

/**
 * TDD tests for [ResumeAvailabilityPushService.resolveMainServerWebSocketUrl].
 *
 * The push service connects to the main server's WebSocket to deliver
 * `client.resumeAvailable` notifications. The default URL is
 * `ws://127.0.0.1:9080` (dev mode). In live mode, server-extend runs
 * in a cluster and must be pointed at the cluster-local main-server
 * WebSocket endpoint — these tests pin the env-var / JVM-property
 * override behaviour.
 */
class ResumeAvailabilityPushServiceUrlTest
{
    private val originalProperty: String? = System.getProperty("serverExtend.mainServerWsUrl")
    private val originalEnv: String? = System.getenv("SERVER_EXTEND_MAIN_SERVER_WS_URL")

    @After
    fun restore()
    {
        if (originalProperty == null)
        {
            System.clearProperty("serverExtend.mainServerWsUrl")
        }
        else
        {
            System.setProperty("serverExtend.mainServerWsUrl", originalProperty)
        }
        // Env vars are immutable post-JVM-start, so we can only test
        // the JVM-property path here. The env-var path is exercised by
        // the production deployment configuration.
    }

    @Test
    fun `resolveMainServerWebSocketUrl returns the dev default when no property or env var is set`()
    {
        System.clearProperty("serverExtend.mainServerWsUrl")
        assertEquals("ws://127.0.0.1:9080", ResumeAvailabilityPushService.resolveMainServerWebSocketUrl())
    }

    @Test
    fun `resolveMainServerWebSocketUrl honours the JVM property when set`()
    {
        System.setProperty("serverExtend.mainServerWsUrl", "wss://main.prod.example.com:9080")
        assertEquals("wss://main.prod.example.com:9080", ResumeAvailabilityPushService.resolveMainServerWebSocketUrl())
    }

    @Test
    fun `resolveMainServerWebSocketUrl coerces https URLs to wss`()
    {
        System.setProperty("serverExtend.mainServerWsUrl", "https://main.prod.example.com:9080")
        assertEquals("wss://main.prod.example.com:9080", ResumeAvailabilityPushService.resolveMainServerWebSocketUrl())
    }

    @Test
    fun `resolveMainServerWebSocketUrl coerces http URLs to ws`()
    {
        System.setProperty("serverExtend.mainServerWsUrl", "http://10.0.0.5:9080")
        assertEquals("ws://10.0.0.5:9080", ResumeAvailabilityPushService.resolveMainServerWebSocketUrl())
    }

    @Test
    fun `resolveMainServerWebSocketUrl strips a trailing slash`()
    {
        System.setProperty("serverExtend.mainServerWsUrl", "wss://main.prod.example.com:9080/")
        assertEquals("wss://main.prod.example.com:9080", ResumeAvailabilityPushService.resolveMainServerWebSocketUrl())
    }

    @Test
    fun `resolveMainServerWebSocketUrl ignores a blank JVM property and falls back to the dev default`()
    {
        System.setProperty("serverExtend.mainServerWsUrl", "   ")
        assertEquals("ws://127.0.0.1:9080", ResumeAvailabilityPushService.resolveMainServerWebSocketUrl())
    }
}
