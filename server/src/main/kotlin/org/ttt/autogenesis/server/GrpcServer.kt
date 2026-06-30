package org.ttt.autogenesis.server

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.cors.CorsService
import com.linecorp.armeria.server.grpc.GrpcService
import io.grpc.ServerInterceptors
import org.ttt.autogenesis.config.ConfigSource
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcRegistry
import java.net.InetSocketAddress
import java.time.Duration

/**
 * Manages the gRPC server instance for RPC bridge communication.
 *
 * The server is backed by Armeria so the same listener can serve native gRPC
 * clients and browser grpc-web clients directly from the backend process.
 *
 * @param config Server configuration containing host and port settings
 * @param rpcRegistry Registry containing RPC method handlers
 */
class GrpcServer(
    private val config: GrpcServerConfig,
    private val rpcRegistry: RpcRegistry
)
{
    private val server: Server = buildServer()

    /**
     * Starts the gRPC server and begins accepting connections.
     */
    fun start()
    {
        Logger.info(
            LogCategory.NETWORK,
            "Starting gRPC/grpc-web bridge on ${config.host}:${config.port}"
        )
        server.start().join()
    }

    /**
     * Stops the gRPC server and terminates all connections.
     */
    fun stop()
    {
        Logger.info(LogCategory.NETWORK, "Stopping gRPC/grpc-web bridge")
        Logger.debug(
            LogCategory.NETWORK,
            "GrpcServer: Initiating shutdown sequence (activePorts=${config.port})"
        )
        server.stop().join()
        Logger.debug(LogCategory.NETWORK, "GrpcServer: Shutdown complete")
    }

    /**
     * Builds the in-process server that serves both gRPC and grpc-web.
     */
    private fun buildServer(): Server
    {
        val grpcService = GrpcService.builder()
            .addService(
                ServerInterceptors.intercept(
                    GrpcBridgeService(rpcRegistry),
                    PlayerIdInterceptor()
                )
            )
            .build()

        val corsOrigins = listOf(
            "http://localhost",
            "http://127.0.0.1",
            "http://localhost:4173",
            "http://127.0.0.1:4173",
            "http://localhost:8080",
            "http://127.0.0.1:8080",
            "http://0.0.0.0",
            ConfigSource.property("accelbyte.local.properties", "AB_BASE_URL"),
        )
        val corsDecorator = CorsService.builder(*corsOrigins.toTypedArray())
            .allowRequestMethods(HttpMethod.OPTIONS, HttpMethod.POST)
            .allowRequestHeaders(
                HttpHeaderNames.CONTENT_TYPE,
                HttpHeaderNames.ORIGIN,
                HttpHeaderNames.AUTHORIZATION,
                HttpHeaderNames.of("player-id"),
                HttpHeaderNames.of("x-grpc-web"),
                HttpHeaderNames.of("x-user-agent"),
                HttpHeaderNames.of("grpc-accept-encoding"),
                HttpHeaderNames.of("grpc-encoding"),
                HttpHeaderNames.of("grpc-timeout"),
                HttpHeaderNames.of("accept"),
                HttpHeaderNames.of("accept-encoding"),
                HttpHeaderNames.of("connect-protocol-version")
            )
            .exposeHeaders("grpc-status", "grpc-message")
            .maxAge(Duration.ofHours(1))
            .newDecorator()

        return Server.builder()
            .http(InetSocketAddress(config.host, config.port))
            .requestTimeoutMillis(0)
            .service(grpcService, corsDecorator)
            .build()
    }
}
