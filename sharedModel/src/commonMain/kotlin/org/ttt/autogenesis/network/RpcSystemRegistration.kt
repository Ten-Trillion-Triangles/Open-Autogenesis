package org.ttt.autogenesis.network

/**
 * Platform-agnostic entry point that loads the generated RPC handler registrations.
 * Call this once before creating any [RpcRegistry] instances.
 */
expect fun registerRpcSystem()