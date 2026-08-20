package org.ttt.autogenesis.kvisionapp

import globals.ServerExtendConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import org.ttt.autogenesis.network.ServerExtendTransport
import kotlin.test.assertEquals

/**
 * Verifies the KVision-facing server-extend facade tracks the configured transport mode.
 */
class ServerExtendBridgeTest
{
    private val originalTransport = ServerExtendConfig.transport

    /**
     * Restores the selected transport after each test.
     */
    @AfterTest
    fun restoreTransport()
    {
        ServerExtendConfig.transport = originalTransport
    }

    /**
     * Confirms the facade reports the currently selected transport.
     */
    @Test
    fun `active transport mirrors server extend config`() 
    {
        ServerExtendConfig.useGrpcWebTransport()
        assertEquals(ServerExtendTransport.GRPC_WEB, ServerExtendBridge.activeTransport())

        ServerExtendConfig.useGrpcBidiTransport()
        assertEquals(ServerExtendTransport.GRPC_BIDI, ServerExtendBridge.activeTransport())

        ServerExtendConfig.useRestTransport()
        assertEquals(ServerExtendTransport.REST_SSE, ServerExtendBridge.activeTransport())
    }
}