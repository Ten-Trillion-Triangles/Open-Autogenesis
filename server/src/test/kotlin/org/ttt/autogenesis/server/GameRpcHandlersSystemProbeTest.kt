package org.ttt.autogenesis.server

import gameState.WorldManager
import globals.AwsCredentialsBootstrap
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.SubsystemStatus
import org.ttt.autogenesis.network.SystemProbeResponse
import org.ttt.autogenesis.server.vfs.VirtualFileSystemManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [GameRpcHandlers.systemProbe].
 *
 * Exercises each subsystem branch by mocking the accessed singletons. All checks are
 * read-only; no mutable state is modified by the probe.
 */
class GameRpcHandlersSystemProbeTest
{
    @Before
    fun setup()
    {
        MockKAnnotations.init(this)
        mockkObject(WorldManager)
        mockkObject(AwsCredentialsBootstrap)
        mockkObject(org.ttt.autogenesis.server.vfs.VirtualFileSystemManager)
        mockkObject(org.ttt.autogenesis.server.GrpcServerConfig)
        mockkObject(org.ttt.autogenesis.server.TurnHarness)
    }

    @Test
    fun `probe returns module name server`()
    {
        every { WorldManager.activeSessionId } returns ""
        every { WorldManager.isGameActive } returns false
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { org.ttt.autogenesis.server.GrpcServerConfig.host } returns "0.0.0.0"
        every { org.ttt.autogenesis.server.GrpcServerConfig.port } returns 50051
        every { org.ttt.autogenesis.server.TurnHarness.isRunning() } returns false
        GameRpcHandlers.rpcRegistry = mockk(relaxed = true)

        val ctx = RpcCallContext(connectionId = "test") { }

        val result = runBlocking { GameRpcHandlers.systemProbe(ctx) }

        assertEquals("server", result.module)
        assertTrue(result.timestamp > 0)
    }

    @Test
    fun `AccelByteConfig reports configured when namespace is set`()
    {
        every { WorldManager.activeSessionId } returns ""
        every { WorldManager.isGameActive } returns false
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { org.ttt.autogenesis.server.GrpcServerConfig.host } returns "0.0.0.0"
        every { org.ttt.autogenesis.server.GrpcServerConfig.port } returns 50051
        every { org.ttt.autogenesis.server.TurnHarness.isRunning() } returns false
        GameRpcHandlers.rpcRegistry = mockk(relaxed = true)

        // Set AB_NAMESPACE via system property for this test
        val original = System.getProperty("AB_NAMESPACE")
        System.setProperty("AB_NAMESPACE", "test-namespace")

        try
        {
            val ctx = RpcCallContext(connectionId = "test") { }
            val result = runBlocking { GameRpcHandlers.systemProbe(ctx) }
            val status = result.subsystems.find { it.subsystem == "AccelByteConfig" }

            assertEquals("configured", status?.status)
            assertTrue(status?.detail?.contains("test-namespace") == true)
        }
        finally
        {
            if (original != null) System.setProperty("AB_NAMESPACE", original)
            else System.clearProperty("AB_NAMESPACE")
        }
    }

    @Test
    fun `AccelByteConfig reports not_initialized when namespace is absent`()
    {
        every { WorldManager.activeSessionId } returns ""
        every { WorldManager.isGameActive } returns false
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { org.ttt.autogenesis.server.GrpcServerConfig.host } returns "0.0.0.0"
        every { org.ttt.autogenesis.server.GrpcServerConfig.port } returns 50051
        every { org.ttt.autogenesis.server.TurnHarness.isRunning() } returns false
        GameRpcHandlers.rpcRegistry = mockk(relaxed = true)

        System.clearProperty("AB_NAMESPACE")

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { GameRpcHandlers.systemProbe(ctx) }
        val status = result.subsystems.find { it.subsystem == "AccelByteConfig" }

        assertEquals("not_initialized", status?.status)
    }

    @Test
    fun `WorldManager reports active session when sessionId is set`()
    {
        every { WorldManager.activeSessionId } returns "sess-abc123"
        every { WorldManager.isGameActive } returns true
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { org.ttt.autogenesis.server.GrpcServerConfig.host } returns "0.0.0.0"
        every { org.ttt.autogenesis.server.GrpcServerConfig.port } returns 50051
        every { org.ttt.autogenesis.server.TurnHarness.isRunning() } returns false
        GameRpcHandlers.rpcRegistry = mockk(relaxed = true)

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { GameRpcHandlers.systemProbe(ctx) }
        val status = result.subsystems.find { it.subsystem == "WorldManager" }

        assertEquals("configured", status?.status)
        assertTrue(status?.detail?.contains("sess-abc123") == true)
        assertTrue(status?.detail?.contains("gameActive=true") == true)
    }

    @Test
    fun `AwsCredentialsBootstrap reports configured when keys present`()
    {
        every { WorldManager.activeSessionId } returns ""
        every { WorldManager.isGameActive } returns false
        every { AwsCredentialsBootstrap.hasKeys() } returns true
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { org.ttt.autogenesis.server.GrpcServerConfig.host } returns "0.0.0.0"
        every { org.ttt.autogenesis.server.GrpcServerConfig.port } returns 50051
        every { org.ttt.autogenesis.server.TurnHarness.isRunning() } returns false
        GameRpcHandlers.rpcRegistry = mockk(relaxed = true)

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { GameRpcHandlers.systemProbe(ctx) }
        val status = result.subsystems.find { it.subsystem == "AwsCredentialsBootstrap" }

        assertEquals("configured", status?.status)
    }

    @Test
    fun `VirtualFileSystemManager reports configured when initialized`()
    {
        every { WorldManager.activeSessionId } returns ""
        every { WorldManager.isGameActive } returns false
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns true
        every {
            VirtualFileSystemManager.current()
        } returns mockk(relaxed = true) {
            every { mode } returns mockk(relaxed = true) {
                every { name } returns "LOCAL"
            }
            every { description } returns "local disk storage"
        }
        every { org.ttt.autogenesis.server.GrpcServerConfig.host } returns "0.0.0.0"
        every { org.ttt.autogenesis.server.GrpcServerConfig.port } returns 50051
        every { org.ttt.autogenesis.server.TurnHarness.isRunning() } returns false
        GameRpcHandlers.rpcRegistry = mockk(relaxed = true)

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { GameRpcHandlers.systemProbe(ctx) }
        val status = result.subsystems.find { it.subsystem == "VirtualFileSystemManager" }

        assertEquals("configured", status?.status)
        assertTrue(status?.detail?.contains("LOCAL") == true)
    }

    @Test
    fun `TurnHarness reports configured when running`()
    {
        every { WorldManager.activeSessionId } returns ""
        every { WorldManager.isGameActive } returns false
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { org.ttt.autogenesis.server.GrpcServerConfig.host } returns "0.0.0.0"
        every { org.ttt.autogenesis.server.GrpcServerConfig.port } returns 50051
        every { org.ttt.autogenesis.server.TurnHarness.isRunning() } returns true
        GameRpcHandlers.rpcRegistry = mockk(relaxed = true)

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { GameRpcHandlers.systemProbe(ctx) }
        val status = result.subsystems.find { it.subsystem == "TurnHarness" }

        assertEquals("configured", status?.status)
        assertTrue(status?.detail?.contains("running=true") == true)
    }

    @Test
    fun `RpcRegistry reports handler count`()
    {
        every { WorldManager.activeSessionId } returns ""
        every { WorldManager.isGameActive } returns false
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { org.ttt.autogenesis.server.GrpcServerConfig.host } returns "0.0.0.0"
        every { org.ttt.autogenesis.server.GrpcServerConfig.port } returns 50051
        every { org.ttt.autogenesis.server.TurnHarness.isRunning() } returns false

        val mockRegistry = mockk<org.ttt.autogenesis.network.RpcRegistry>(relaxed = true)
        every { mockRegistry.registeredMethodNames() } returns listOf("server.ping", "game.world.snapshot", "server.system.probe")
        GameRpcHandlers.rpcRegistry = mockRegistry

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { GameRpcHandlers.systemProbe(ctx) }
        val status = result.subsystems.find { it.subsystem == "RpcRegistry" }

        assertEquals("configured", status?.status)
        assertTrue(status?.detail?.contains("3 handlers") == true)
    }

    @Test
    fun `probe response contains all expected subsystem names`()
    {
        every { WorldManager.activeSessionId } returns ""
        every { WorldManager.isGameActive } returns false
        every { AwsCredentialsBootstrap.hasKeys() } returns false
        every { VirtualFileSystemManager.isInitialized() } returns false
        every { org.ttt.autogenesis.server.GrpcServerConfig.host } returns "0.0.0.0"
        every { org.ttt.autogenesis.server.GrpcServerConfig.port } returns 50051
        every { org.ttt.autogenesis.server.TurnHarness.isRunning() } returns false
        GameRpcHandlers.rpcRegistry = mockk(relaxed = true)

        val expectedSubsystems = listOf(
            "AccelByteConfig",
            "AccelByteSdkProvider",
            "DsHubClient",
            "AmsWatchdogClient",
            "AwsCredentialsBootstrap",
            "VirtualFileSystemManager",
            "GrpcServer",
            "RpcRegistry",
            "WorldManager",
            "TurnHarness"
        )

        val ctx = RpcCallContext(connectionId = "test") { }
        val result = runBlocking { GameRpcHandlers.systemProbe(ctx) }
        val subsystemNames = result.subsystems.map { it.subsystem }

        expectedSubsystems.forEach { name ->
            assertTrue(subsystemNames.contains(name), "Expected subsystem '$name' not found in probe response")
        }
    }

    @Test
    fun `no credentials appear in any subsystem detail field`()
    {
        every { WorldManager.activeSessionId } returns ""
        every { WorldManager.isGameActive } returns false
        every { AwsCredentialsBootstrap.hasKeys() } returns true
        every { VirtualFileSystemManager.isInitialized() } returns true
        every { VirtualFileSystemManager.current() } returns mockk(relaxed = true) {
            every { mode } returns mockk(relaxed = true) { every { name } returns "LOCAL" }
            every { description } returns "local disk"
        }
        every { org.ttt.autogenesis.server.GrpcServerConfig.host } returns "0.0.0.0"
        every { org.ttt.autogenesis.server.GrpcServerConfig.port } returns 50051
        every { org.ttt.autogenesis.server.TurnHarness.isRunning() } returns false
        GameRpcHandlers.rpcRegistry = mockk(relaxed = true)

        System.setProperty("AB_NAMESPACE", "test-ns")
        System.setProperty("AB_BASE_URL", "https://secret.internal.example.com")

        try
        {
            val ctx = RpcCallContext(connectionId = "test") { }
            val result = runBlocking { GameRpcHandlers.systemProbe(ctx) }

            val secretPatterns = listOf("client_secret", "aws_secret_access_key", "AB_CLIENT_SECRET", "AB_CLIENT_SECRET=")
            result.subsystems.forEach { status ->
                val detail = status.detail ?: return@forEach
                val matchedPattern = secretPatterns.find { detail.contains(it) }
                assertTrue(
                    matchedPattern == null,
                    "Subsystem '${status.subsystem}' detail may contain credentials ($matchedPattern): $detail"
                )
            }
        }
        finally
        {
            System.clearProperty("AB_NAMESPACE")
            System.clearProperty("AB_BASE_URL")
        }
    }
}