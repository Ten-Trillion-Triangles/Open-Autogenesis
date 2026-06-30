package org.ttt.autogenesis.network

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the server-extend transport selector remains stable across platforms.
 */
class ServerExtendTransportTest
{
    /**
     * Ensures valid values round-trip and unknown values fall back to REST/SSE.
     */
    @Test
    fun `server extend transport parser returns stable defaults`()
    {
        assertEquals(ServerExtendTransport.REST_SSE, ServerExtendTransport.fromValue(null))
        assertEquals(ServerExtendTransport.REST_SSE, ServerExtendTransport.fromValue("unknown"))
        assertEquals(ServerExtendTransport.GRPC_BIDI, ServerExtendTransport.fromValue("grpc_bidi"))
        assertEquals(ServerExtendTransport.GRPC_WEB, ServerExtendTransport.fromValue("grpc-web"))
        assertEquals(ServerExtendTransport.GRPC_WEB, ServerExtendTransport.fromValue("GRPC_WEB"))
    }
}
