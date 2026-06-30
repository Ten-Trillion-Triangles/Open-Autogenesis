package globals

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import globals.ClientDebug
import org.ttt.autogenesis.network.ServerExtendTransport

/**
 * Verifies the server-extend configuration helpers used by the KVision frontend.
 */
class ServerExtendConfigTest
{
    private val originalTransport = ServerExtendConfig.transport
    private val originalLocalServerUrl = ServerExtendConfig.localServerUrl
    private val originalLiveServerUrl = ServerExtendConfig.liveServerUrl
    private val originalLocalGrpcUrl = ServerExtendConfig.localGrpcUrl
    private val originalLiveGrpcUrl = ServerExtendConfig.liveGrpcUrl
    private val originalDebugMode = ClientDebug.debugMode

    /**
     * Restores mutable configuration after the test runs.
     */
    @AfterTest
    fun restoreDefaults()
    {
        ServerExtendConfig.transport = originalTransport
        ServerExtendConfig.localServerUrl = originalLocalServerUrl
        ServerExtendConfig.liveServerUrl = originalLiveServerUrl
        ServerExtendConfig.localGrpcUrl = originalLocalGrpcUrl
        ServerExtendConfig.liveGrpcUrl = originalLiveGrpcUrl
        ClientDebug.debugMode = originalDebugMode
    }

    /**
     * Confirms the default URLs and transport mode remain the expected fallbacks.
     */
    @Test
    fun `server extend config exposes the expected defaults`()
    {
        ClientDebug.debugMode = true
        assertEquals(ServerExtendTransport.REST_SSE, ServerExtendConfig.transport)
        assertTrue(ServerExtendConfig.defaultServerUrl.startsWith("http"))
        assertEquals("127.0.0.1:9092", ServerExtendConfig.defaultGrpcServerUrl)
        assertEquals("http://127.0.0.1:9092", ServerExtendConfig.defaultGrpcWebServerUrl)
    }

    /**
     * Confirms transport toggles remain explicit and reversible.
     */
    @Test
    fun `server extend config transport helpers switch modes`()
    {
        ServerExtendConfig.useGrpcWebTransport()
        assertEquals(ServerExtendTransport.GRPC_WEB, ServerExtendConfig.transport)

        ServerExtendConfig.useGrpcBidiTransport()
        assertEquals(ServerExtendTransport.GRPC_BIDI, ServerExtendConfig.transport)

        ServerExtendConfig.useRestTransport()
        assertEquals(ServerExtendTransport.REST_SSE, ServerExtendConfig.transport)
    }
}
