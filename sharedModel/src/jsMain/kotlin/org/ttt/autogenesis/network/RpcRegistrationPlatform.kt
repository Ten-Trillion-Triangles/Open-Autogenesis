package org.ttt.autogenesis.network

/**
 * JS implementation of RPC registration initialization.
 * Uses dynamic `require` calls because reflection is unavailable.
 */
actual fun initializeRpcRegistrationsPlatform()
{
    val requireFunc: dynamic = js("typeof require !== 'undefined' ? require : undefined")
    try {
        requireFunc?.invoke(RpcMasterRegistration.MODULE_PATH)
    } catch (e: dynamic) {
        // Module doesn't exist or failed to load, ignore.
    }
}

private object RpcMasterRegistration
{
    const val MODULE_PATH = "./org/ttt/autogenesis/network/generated/GeneratedRpcMasterRegistrationKt"
}
