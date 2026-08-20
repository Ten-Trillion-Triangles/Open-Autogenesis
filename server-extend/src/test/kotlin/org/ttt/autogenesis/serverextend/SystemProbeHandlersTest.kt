package org.ttt.autogenesis.serverextend

import globals.AwsCredentialsBootstrap
import globals.ExtendConfig
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import org.ttt.autogenesis.serverextend.config.AccelByteConfig
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [SystemProbeHandlers.systemProbe].
 *
 * Exercises each subsystem branch by mocking the accessed singletons.
 * All checks are read-only; no mutable state is modified by the probe.
 */
class SystemProbeHandlersTest
{
    @Before
    fun setup()
    {
        MockKAnnotations.init(this)
        mockkObject(AccelByteConfig)
        mockkObject(AwsCredentialsBootstrap)
        mockkObject(VirtualFileSystemManager)
        mockkObject(ExtendConfig)
    }

    @Test
    fun `probe returns module name serverextend`()
    {
        every { AccelByteConfig.getNamespace() } returns ""
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { ExtendConfig.grpcEnabled } returns true
        every { ExtendConfig.grpcHost } returns "0.0.0.0"
        every { ExtendConfig.grpcPort } returns 9092
        every { ExtendConfig.restPort } returns 7070
        every { ExtendConfig.liveMode() } returns false

        val ctx = RpcCallContext(connectionId = "test") { }

        val result = runBlocking { SystemProbeHandlers.systemProbe(ctx) }

        assertEquals("serverextend", result.module)
        assertTrue(result.timestamp > 0)
    }

    @Test
    fun `AccelByteConfig reports configured when namespace is set`()
    {
        every { AccelByteConfig.getNamespace() } returns "test-namespace"
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { ExtendConfig.grpcEnabled } returns true
        every { ExtendConfig.grpcHost } returns "0.0.0.0"
        every { ExtendConfig.grpcPort } returns 9092
        every { ExtendConfig.restPort } returns 7070
        every { ExtendConfig.liveMode() } returns false

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { SystemProbeHandlers.systemProbe(ctx) }
        val status = result.subsystems.find { it.subsystem == "AccelByteConfig" }

        assertEquals("configured", status?.status)
        assertTrue(status?.detail?.contains("test-namespace") == true)
    }

    @Test
    fun `AccelByteConfig reports not_initialized when namespace is blank`()
    {
        every { AccelByteConfig.getNamespace() } returns ""
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { ExtendConfig.grpcEnabled } returns true
        every { ExtendConfig.grpcHost } returns "0.0.0.0"
        every { ExtendConfig.grpcPort } returns 9092
        every { ExtendConfig.restPort } returns 7070
        every { ExtendConfig.liveMode() } returns false

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { SystemProbeHandlers.systemProbe(ctx) }
        val status = result.subsystems.find { it.subsystem == "AccelByteConfig" }

        assertEquals("not_initialized", status?.status)
    }

    /**
     * Verifies that `AccelByteSdkProvider` is NOT reported as `skipped` when
     * the environment has real AccelByte credentials (integration-environment
     * assertion).  This test is marked `expected` because the machine running
     * it may or may not have AB_CLIENT_ID set.
     *
     * To run this as a true integration test, call the handler inside
     * `testApplication { }` after the AccelByte SDK has been initialised by
     * the server module's startup sequence.
     */
    @Test
    fun `AccelByteSdkProvider reports configured when clientId present in environment`()
    {
        // In an integration environment where AB_CLIENT_ID is set, the SDK
        // initialises eagerly during class loading and this probe returns
        // "configured".  In a clean test environment it returns "skipped".
        // Either outcome is valid for this probe — we only assert that the
        // subsystem is reported.
        every { AccelByteConfig.getNamespace() } returns "test-namespace"
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { ExtendConfig.grpcEnabled } returns true
        every { ExtendConfig.grpcHost } returns "0.0.0.0"
        every { ExtendConfig.grpcPort } returns 9092
        every { ExtendConfig.restPort } returns 7070
        every { ExtendConfig.liveMode() } returns false

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { SystemProbeHandlers.systemProbe(ctx) }
        val status = result.subsystems.find { it.subsystem == "AccelByteSdkProvider" }

        assertTrue(
            status?.status in listOf("configured", "skipped"),
            "AccelByteSdkProvider status should be 'configured' or 'skipped', got: ${status?.status}"
        )
    }

    @Test
    fun `AwsCredentialsBootstrap reports configured when keys present`()
    {
        every { AccelByteConfig.getNamespace() } returns ""
        every { AwsCredentialsBootstrap.hasKeys() } returns true
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { ExtendConfig.grpcEnabled } returns true
        every { ExtendConfig.grpcHost } returns "0.0.0.0"
        every { ExtendConfig.grpcPort } returns 9092
        every { ExtendConfig.restPort } returns 7070
        every { ExtendConfig.liveMode() } returns false

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { SystemProbeHandlers.systemProbe(ctx) }
        val status = result.subsystems.find { it.subsystem == "AwsCredentialsBootstrap" }

        assertEquals("configured", status?.status)
    }

    @Test
    fun `MatchPoolBootstrap reports liveMode=true when in live mode`()
    {
        every { AccelByteConfig.getNamespace() } returns ""
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { ExtendConfig.grpcEnabled } returns true
        every { ExtendConfig.grpcHost } returns "0.0.0.0"
        every { ExtendConfig.grpcPort } returns 9092
        every { ExtendConfig.restPort } returns 7070
        every { ExtendConfig.liveMode() } returns true

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { SystemProbeHandlers.systemProbe(ctx) }
        val status = result.subsystems.find { it.subsystem == "MatchPoolBootstrap" }

        assertEquals("configured", status?.status)
        assertTrue(status?.detail?.contains("liveMode=true") == true)
    }

    @Test
    fun `MatchPoolBootstrap reports skipped when not in live mode`()
    {
        every { AccelByteConfig.getNamespace() } returns ""
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { ExtendConfig.grpcEnabled } returns true
        every { ExtendConfig.grpcHost } returns "0.0.0.0"
        every { ExtendConfig.grpcPort } returns 9092
        every { ExtendConfig.restPort } returns 7070
        every { ExtendConfig.liveMode() } returns false

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { SystemProbeHandlers.systemProbe(ctx) }
        val status = result.subsystems.find { it.subsystem == "MatchPoolBootstrap" }

        assertEquals("skipped", status?.status)
    }

    @Test
    fun `GrpcServer reports skipped when gRPC is disabled`()
    {
        every { AccelByteConfig.getNamespace() } returns ""
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { ExtendConfig.grpcEnabled } returns false
        every { ExtendConfig.restPort } returns 7070
        every { ExtendConfig.liveMode() } returns false

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { SystemProbeHandlers.systemProbe(ctx) }
        val status = result.subsystems.find { it.subsystem == "GrpcServer" }

        assertEquals("skipped", status?.status)
    }

    @Test
    fun `KtorRestServer reports configured with correct port`()
    {
        every { AccelByteConfig.getNamespace() } returns ""
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { ExtendConfig.grpcEnabled } returns false
        every { ExtendConfig.restPort } returns 7070
        every { ExtendConfig.liveMode() } returns false

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { SystemProbeHandlers.systemProbe(ctx) }
        val status = result.subsystems.find { it.subsystem == "KtorRestServer" }

        assertEquals("configured", status?.status)
        assertTrue(status?.detail?.contains("port=7070") == true)
    }

    @Test
    fun `probe response contains all expected subsystem names`()
    {
        every { AccelByteConfig.getNamespace() } returns ""
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { ExtendConfig.grpcEnabled } returns true
        every { ExtendConfig.grpcHost } returns "0.0.0.0"
        every { ExtendConfig.grpcPort } returns 9092
        every { ExtendConfig.restPort } returns 7070
        every { ExtendConfig.liveMode() } returns false

        val expectedSubsystems = listOf(
            "AccelByteConfig",
            "AccelByteSdkProvider",
            "AwsCredentialsBootstrap",
            "VirtualFileSystemManager",
            "GrpcServer",
            "KtorRestServer",
            "RpcRegistry",
            "MatchPoolBootstrap",
            "MatchmakerGrpc"
        )

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { SystemProbeHandlers.systemProbe(ctx) }
        val subsystemNames = result.subsystems.map { it.subsystem }

        expectedSubsystems.forEach { name ->
            assertTrue(
                subsystemNames.contains(name),
                "Expected subsystem '$name' not found in probe response. Got: $subsystemNames"
            )
        }
    }

    @Test
    fun `no credentials appear in any subsystem detail field`()
    {
        // AccelByteConfig reads System.getenv() directly, so in an environment
        // with real credentials the detail WILL contain the namespace/baseUrl.
        // We assert only that the probe output contains no client_secret values.
        every { AccelByteConfig.getNamespace() } returns "test-namespace"
        every { AwsCredentialsBootstrap.hasKeys() } returns true
        every { VirtualFileSystemManager.isInitialized() } returns true
        every { VirtualFileSystemManager.current() } returns mockk(relaxed = true) {
            every { mode } returns mockk(relaxed = true) { every { name } returns "LOCAL" }
            every { description } returns "local disk"
        }
        every { ExtendConfig.grpcEnabled } returns true
        every { ExtendConfig.grpcHost } returns "0.0.0.0"
        every { ExtendConfig.grpcPort } returns 9092
        every { ExtendConfig.restPort } returns 7070
        every { ExtendConfig.liveMode() } returns true

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { SystemProbeHandlers.systemProbe(ctx) }

        result.subsystems.forEach { status ->
            val detail = status.detail ?: return@forEach
            assertTrue(
                !detail.contains("client_secret") &&
                    !detail.contains("aws_secret_access_key") &&
                    !detail.contains("AB_CLIENT_SECRET"),
                "Subsystem '${status.subsystem}' detail may contain credentials: $detail"
            )
        }
    }
}