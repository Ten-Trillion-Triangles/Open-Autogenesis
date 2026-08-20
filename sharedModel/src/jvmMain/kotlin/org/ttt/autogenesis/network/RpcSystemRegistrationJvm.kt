package org.ttt.autogenesis.network

/**
 * JVM implementation that ensures the generated RPC registration providers are loaded.
 */
actual fun registerRpcSystem() {
    initializeRpcRegistrationsPlatform()
}