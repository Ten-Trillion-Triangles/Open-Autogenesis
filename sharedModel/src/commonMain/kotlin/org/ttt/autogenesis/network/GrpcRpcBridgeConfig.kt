package org.ttt.autogenesis.network

/**
 * Configuration helper for gRPC RPC bridge endpoints.
 * Provides convenient methods for constructing server endpoint URLs.
 */
object GrpcRpcBridgeConfig
{
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 9091

    /**
     * Creates a development endpoint URL using localhost.
     *
     * @param port The port number for the gRPC server (defaults to [DEFAULT_PORT])
     * @return Formatted endpoint string in "host:port" format
     */
    fun development(port: Int = DEFAULT_PORT): String = "$DEFAULT_HOST:$port"

    /**
     * Creates a development grpc-web endpoint URL using localhost.
     *
     * @param port The port number for the gRPC server (defaults to [DEFAULT_PORT])
     * @return Formatted endpoint string in "http://host:port" format
     */
    fun developmentWeb(port: Int = DEFAULT_PORT): String = "http://$DEFAULT_HOST:$port"
    
    /**
     * Creates a production endpoint URL with custom host and port.
     *
     * @param host The hostname or IP address of the gRPC server
     * @param port The port number for the gRPC server
     * @return Formatted endpoint string in "host:port" format
     */
    fun production(host: String, port: Int): String = "${host.trim().trimEnd(':')}:$port"

    /**
     * Creates a production grpc-web endpoint URL with custom host and port.
     *
     * @param host The hostname or IP address of the gRPC server
     * @param port The port number for the gRPC server
     * @return Formatted endpoint string in "https://host:port" format
     */
    fun productionWeb(host: String, port: Int): String = "https://${host.trim().trimEnd(':')}:$port"
}
