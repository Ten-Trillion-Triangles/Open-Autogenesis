package org.ttt.autogenesis.serverextend

import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcRegistry
import org.ttt.autogenesis.network.RpcRegistrationCollector
import org.ttt.autogenesis.network.RpcRegistrationProvider
import org.ttt.autogenesis.network.SystemProbeResponse

/**
 * Manual RPC bindings for [SystemProbeHandlers].
 * Follows the same pattern as the KSP-generated bindings so that
 * [RpcRegistrationCollector] discovers and registers the handler at startup.
 */
internal fun registerSystemProbeHandlers(rpcRegistry: RpcRegistry)
{
    // Non-typed register: takes RpcHandlerBlock { ctx, params -> JsonElement? }
    // Uses RpcJson for explicit serialization (no type-inference needed).
    rpcRegistry.register("serverextend.system.probe", RpcDirection.SERVER) { ctx, _ ->
        val response = SystemProbeHandlers.systemProbe(ctx)
        RpcJson.encodeToJsonElement(SystemProbeResponse.serializer(), response)
    }
}

internal class SystemProbeHandlersRegistrationProvider : RpcRegistrationProvider
{
    override fun register(rpcRegistry: RpcRegistry)
    {
        registerSystemProbeHandlers(rpcRegistry)
    }
}

/** Triggers registration with [RpcRegistrationCollector] at class-load time. */
internal val _systemProbeHandlersProvider = SystemProbeHandlersRegistrationProvider().also {
    RpcRegistrationCollector.registerProvider(it)
}