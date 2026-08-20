package org.ttt.autogenesis.network

/**
 * Configuration required to establish a gRPC-based RPC connection.
 */
data class GrpcRpcClientConfig(
    val endpoint: String,
    val playerId: String,
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(endpoint.isNotBlank()) { "endpoint cannot be blank" }
        require(playerId.isNotBlank()) { "playerId cannot be blank" }
    }
}