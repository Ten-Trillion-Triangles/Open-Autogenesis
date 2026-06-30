package org.ttt.autogenesis.network

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Collects registered [RpcRegistrationProvider] implementations so they can be run
 * whenever a new [RpcRegistry] is constructed.
 */
object RpcRegistrationCollector
{
    private val registrationProviders = mutableListOf<RpcRegistrationProvider>()

    /**
     * Adds a provider to the global collection so it will run for every [RpcRegistry].
     */
    fun registerProvider(provider : RpcRegistrationProvider)
    {
        Logger.debug(LogCategory.SYSTEM, "RpcRegistrationCollector: Registering provider ${provider::class.simpleName}")
        registrationProviders.add(provider)
    }

    /**
     * Executes all collected providers against the specified [rpcRegistry].
     */
    fun registerAll(rpcRegistry : RpcRegistry)
    {
        Logger.info(LogCategory.SYSTEM, "RpcRegistrationCollector: Running ${registrationProviders.size} providers")
        registrationProviders.forEach { 
            Logger.debug(LogCategory.SYSTEM, "RpcRegistrationCollector: Executing provider ${it::class.simpleName}")
            it.register(rpcRegistry) 
        }
    }
    
    /**
     * Returns the number of registered providers for verification.
     */
    fun getProviderCount(): Int = registrationProviders.size
}
