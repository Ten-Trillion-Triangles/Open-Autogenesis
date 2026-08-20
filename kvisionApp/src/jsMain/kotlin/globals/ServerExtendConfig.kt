package globals

import globals.ClientDebug
import org.ttt.autogenesis.config.ConfigSource
import org.ttt.autogenesis.network.GrpcRpcBridgeConfig
import org.ttt.autogenesis.network.ServerExtendTransport

/**
 * Holds the endpoints and transport mode that the client should use when talking
 * to the server-extend service.
 */
object ServerExtendConfig
{
    private const val LOCAL_GRPC_PORT = 9092
    private const val LIVE_GRPC_PORT = 9091

    /**
     * URL that points at the local server-extend instance used during development.
     */
    var localServerUrl: String = "http://127.0.0.1:7070"

    /**
     * URL that targets the live server-extend deployment inside AccelByte.
     * Tenant hostname is read from ConfigSource so the public repo carries no
     * production tenant string.
     */
    var liveServerUrl: String = ""
        get() = "https://${ConfigSource.property("kvision-iam.local.properties", "kvision.liveHostname")}"
        set(value) { field = value }

    /**
     * gRPC endpoint that points at the local server-extend bridge.
     */
    var localGrpcUrl: String = GrpcRpcBridgeConfig.development(LOCAL_GRPC_PORT)

    /**
     * gRPC endpoint that targets the live server-extend bridge.
     */
    var liveGrpcUrl: String = ""
        get() = GrpcRpcBridgeConfig.production(
            ConfigSource.property("kvision-iam.local.properties", "kvision.liveHostname"),
            LIVE_GRPC_PORT
        )
        set(value) { field = value }

    /**
     * Browser-safe grpc-web base URL for the local server-extend gRPC listener.
     */
    val localGrpcWebUrl: String
        get() = GrpcRpcBridgeConfig.developmentWeb(LOCAL_GRPC_PORT)

    /**
     * Browser-safe grpc-web base URL for the live server-extend gRPC listener.
     */
    val liveGrpcWebUrl: String
        get() = GrpcRpcBridgeConfig.productionWeb(
            ConfigSource.property("kvision-iam.local.properties", "kvision.liveHostname"),
            LIVE_GRPC_PORT
        )

    /**
     * Transport mode used by the server-extend facade.
     */
    var transport: ServerExtendTransport = ServerExtendTransport.REST_SSE

    private enum class ServerTarget
    {
        LOCAL,
        LIVE
    }

    private var manualTarget: ServerTarget? = null

    /**
     * Marks the default path as the local server so RPC consumers can switch
     * to the localhost bridge after the probe succeeds.
     */
    fun useLocalServer()
    {
        manualTarget = ServerTarget.LOCAL
    }

    /**
     * Reverts the default path back to the live URL when the probe cannot reach
     * a local server-extend instance.
     */
    fun useLiveServer()
    {
        manualTarget = ServerTarget.LIVE
    }

    /**
     * Marks the server-extend facade to use the REST/SSE transport.
     */
    fun useRestTransport()
    {
        transport = ServerExtendTransport.REST_SSE
    }

    /**
     * Marks the server-extend facade to use the bidirectional gRPC transport.
     */
    fun useGrpcBidiTransport()
    {
        transport = ServerExtendTransport.GRPC_BIDI
    }

    /**
     * Marks the server-extend facade to use the grpc-web transport.
     */
    fun useGrpcWebTransport()
    {
        transport = ServerExtendTransport.GRPC_WEB
    }

    /**
     * Returns the current RPC base URL.
     *
     * The debug mode flag short-circuits the target, and any manual override
     * (set via [useLocalServer] or [useLiveServer]) is honored next.
     */
    val defaultServerUrl: String
        get() = when
        {
            ClientDebug.debugMode -> localServerUrl
            manualTarget == ServerTarget.LOCAL -> localServerUrl
            manualTarget == ServerTarget.LIVE -> liveServerUrl
            else -> liveServerUrl
        }

    /**
     * Returns the current gRPC endpoint URL.
     */
    val defaultGrpcServerUrl: String
        get() = when
        {
            ClientDebug.debugMode -> localGrpcUrl
            manualTarget == ServerTarget.LOCAL -> localGrpcUrl
            manualTarget == ServerTarget.LIVE -> liveGrpcUrl
            else -> liveGrpcUrl
        }

    /**
     * Returns the current grpc-web listener URL.
     */
    val defaultGrpcWebServerUrl: String
        get() = when
        {
            ClientDebug.debugMode -> localGrpcWebUrl
            manualTarget == ServerTarget.LOCAL -> localGrpcWebUrl
            manualTarget == ServerTarget.LIVE -> liveGrpcWebUrl
            else -> liveGrpcWebUrl
        }
}