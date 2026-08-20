package org.ttt.autogenesis.serverextend

import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.SubsystemStatus
import org.ttt.autogenesis.network.SystemProbeResponse
import org.ttt.autogenesis.serverextend.config.AccelByteConfig

/**
 * Server-extend system probe handlers. Diagnostic endpoint that reports the
 * health and configuration state of every server-extend subsystem.
 *
 * All checks are read-only snapshots; no mutable state is modified.
 */
object SystemProbeHandlers
{
    /**
     * Diagnostic probe for the server-extend module. Returns a structured
     * [SystemProbeResponse] describing the current state of every subsystem.
     *
     * @param ctx RPC call context (unused — present for consistency)
     * @return [SystemProbeResponse] describing the current state of server-extend
     */
    @JvmStatic
    suspend fun systemProbe(ctx: RpcCallContext): SystemProbeResponse
    {
        val subsystems = mutableListOf<SubsystemStatus>()

        // AccelByteConfig
        runCatching {
            val namespace = AccelByteConfig.getNamespace()
            val baseUrl = System.getenv("AB_BASE_URL") ?: System.getProperty("AB_BASE_URL") ?: ""
            if (namespace.isNotBlank())
            {
                subsystems.add(SubsystemStatus("AccelByteConfig", "configured", "namespace=$namespace baseUrl=$baseUrl"))
            }

            else
            {
                subsystems.add(SubsystemStatus("AccelByteConfig", "not_initialized", "AB_NAMESPACE not set"))
            }
        }.onFailure { e ->
            subsystems.add(SubsystemStatus("AccelByteConfig", "error", e.message))
        }

        // AccelByte SDK
        runCatching {
            val clientId = System.getenv("AB_CLIENT_ID")
            if (clientId.isNullOrBlank())
            {
                subsystems.add(SubsystemStatus("AccelByteSdkProvider", "skipped", "AB_CLIENT_ID not set"))
            }

            else
            {
                kotlin.runCatching { accelbyte.AccelByteSdkProvider.sdk }
                subsystems.add(SubsystemStatus("AccelByteSdkProvider", "configured", "clientId prefix=${clientId.take(8)}***"))
            }
        }.onFailure { e ->
            subsystems.add(SubsystemStatus("AccelByteSdkProvider", "error", e.message))
        }

        // AWS Credentials Bootstrap
        runCatching {
            val hasKeys = globals.AwsCredentialsBootstrap.hasKeys()
            if (hasKeys)
            {
                subsystems.add(SubsystemStatus("AwsCredentialsBootstrap", "configured", "keys loaded from CloudSave or local config"))
            }
            else
            {
                subsystems.add(SubsystemStatus("AwsCredentialsBootstrap", "not_initialized", "no valid keys found"))
            }
        }.onFailure { e ->
            subsystems.add(SubsystemStatus("AwsCredentialsBootstrap", "error", e.message))
        }

        // Virtual File System
        runCatching {
            if (org.ttt.autogenesis.server.vfs.VirtualFileSystemManager.isInitialized())
            {
                val vfs = org.ttt.autogenesis.server.vfs.VirtualFileSystemManager.current()
                subsystems.add(SubsystemStatus("VirtualFileSystemManager", "configured", "mode=${vfs.mode.name} ${vfs.description}"))
            }

            else
            {
                subsystems.add(SubsystemStatus("VirtualFileSystemManager", "not_initialized", null))
            }
        }.onFailure { e ->
            subsystems.add(SubsystemStatus("VirtualFileSystemManager", "error", e.message))
        }

        // GrpcServer
        runCatching {
            val grpcPort = globals.ExtendConfig.grpcPort
            val grpcHost = globals.ExtendConfig.grpcHost
            val grpcEnabled = globals.ExtendConfig.grpcEnabled
            if (grpcEnabled)
            {
                subsystems.add(SubsystemStatus("GrpcServer", "configured", "host=$grpcHost port=$grpcPort"))
            }
            else
            {
                subsystems.add(SubsystemStatus("GrpcServer", "skipped", "gRPC disabled"))
            }
        }.onFailure { e ->
            subsystems.add(SubsystemStatus("GrpcServer", "error", e.message))
        }

        // REST / Ktor
        runCatching {
            val restPort = globals.ExtendConfig.restPort
            subsystems.add(SubsystemStatus("KtorRestServer", "configured", "port=$restPort"))
        }.onFailure { e ->
            subsystems.add(SubsystemStatus("KtorRestServer", "error", e.message))
        }

        // RPC Registry
        runCatching {
            val registry = ensureModuleRpcRegistry()
            val handlerCount = registry.registeredMethodNames().size
            subsystems.add(SubsystemStatus("RpcRegistry", "configured", "$handlerCount handlers registered"))
        }.onFailure { e ->
            subsystems.add(SubsystemStatus("RpcRegistry", "error", e.message))
        }

        // MatchPoolBootstrap (live mode only)
        runCatching {
            val liveMode = globals.ExtendConfig.liveMode()
            if (liveMode)
            {
                subsystems.add(SubsystemStatus("MatchPoolBootstrap", "configured", "liveMode=true matchmaker=autogenesis-matchmaker"))
            }
            else
            {
                subsystems.add(SubsystemStatus("MatchPoolBootstrap", "skipped", "liveMode=false"))
            }
        }.onFailure { e ->
            subsystems.add(SubsystemStatus("MatchPoolBootstrap", "error", e.message))
        }

        // Matchmaker gRPC (external process, report connection intent)
        runCatching {
            val matchmakerPort = 9095
            subsystems.add(SubsystemStatus("MatchmakerGrpc", "configured", "port=$matchmakerPort (external process)"))
        }.onFailure { e ->
            subsystems.add(SubsystemStatus("MatchmakerGrpc", "error", e.message))
        }

        return SystemProbeResponse(
            module = "serverextend",
            timestamp = System.currentTimeMillis(),
            subsystems = subsystems
        )
    }
}