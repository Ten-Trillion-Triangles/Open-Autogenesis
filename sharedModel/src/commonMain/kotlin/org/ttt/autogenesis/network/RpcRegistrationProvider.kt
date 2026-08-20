package org.ttt.autogenesis.network

/**
 * Abstraction used by generated code to register RPC handlers with a registry.
 */
fun interface RpcRegistrationProvider
{
    /**
     * Registers the provider's handlers with [rpcRegistry].
     */
    fun register(rpcRegistry : RpcRegistry)
}