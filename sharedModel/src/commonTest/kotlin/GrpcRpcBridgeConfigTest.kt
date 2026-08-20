package org.ttt.autogenesis.network

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the helper URLs used by the gRPC and grpc-web client transports.
 */
class GrpcRpcBridgeConfigTest
{
    /**
     * Confirms the development grpc-web endpoint points at the direct gRPC listener.
     */
    @Test
    fun `development web endpoint uses the direct gRPC port`()
    {
        assertEquals("http://127.0.0.1:9091", GrpcRpcBridgeConfig.developmentWeb())
    }

    /**
     * Confirms the production grpc-web endpoint keeps the same host and port.
     */
    @Test
    fun `production web endpoint uses the direct gRPC port`()
    {
        assertEquals(
            "https://example.autogenesis.test:443",
            GrpcRpcBridgeConfig.productionWeb("example.autogenesis.test", 443)
        )
    }
}