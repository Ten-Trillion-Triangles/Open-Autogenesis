package org.ttt.autogenesis.server

/**
 * Configuration settings for the gRPC server instance.
 * Defines host and port for the gRPC bridge service.
 */
object GrpcServerConfig
{
    var host: String = "0.0.0.0"
    var port: Int = 9091
}