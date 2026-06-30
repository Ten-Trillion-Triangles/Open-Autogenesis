package org.ttt.autogenesis.server

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.ttt.autogenesis.network.ResolutionStep
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.network.toRpcMessage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for UI signal broadcasting over WebSocket connections.
 */
class UiSignalNetworkingTest
{
    
    /**
     * Verifies that the server correctly broadcasts resolution step notifications to connected clients.
     */
    @Test
    fun `server broadcasts resolution step to connected clients`() = testApplication {
        application {
            serverModule()
        }

        val playerId = "test-ui-client"
        val websocketClient = createClient { install(WebSockets) }
        
        websocketClient.webSocket("/events?playerId=$playerId") {
            // Handle connection state and ping (order may vary)
            var connectionStateReceived = false
            var pingReceived = false
            
            repeat(2) {
                when(val msg = readRpcMessage()) {
                    is RpcMessage.ConnectionState -> connectionStateReceived = true
                    is RpcMessage.Request -> pingReceived = true
                    else -> error("Unexpected message type during init: $msg")
                }
            }
            assertTrue(connectionStateReceived, "Should have received ConnectionState")
            assertTrue(pingReceived, "Should have received Ping Request")

            // Trigger broadcast from server side (manually using the singleton)
            UiSignalRpcHandlers.broadcastResolutionStep(ResolutionStep.STORY)
            
            // Verify notification received on client
            val notification = readRpcMessage().also { assertTrue(it is RpcMessage.Notification) }
            assertEquals("ui.setResolutionStep", (notification as RpcMessage.Notification).method)
            
            // Trigger another broadcast
            UiSignalRpcHandlers.broadcastProgressBar(5, "Testing instruction")
            
            val nextNotification = readRpcMessage().also { assertTrue(it is RpcMessage.Notification) }
            assertEquals("ui.updateProgressBar", (nextNotification as RpcMessage.Notification).method)
        }
    }

    /**
     * Reads an RPC message from the WebSocket session with a timeout.
     *
     * @return The parsed RPC message.
     */
    private suspend fun DefaultClientWebSocketSession.readRpcMessage(): RpcMessage = withTimeout(5_000L)
    {
        while(true)
        {
            when(val frame = incoming.receive())
            {
                is Frame.Text -> return@withTimeout frame.readText().toRpcMessage(RpcJson)
                else -> Unit
            }
        }
        error("Timed out while waiting for RPC message")
    }
}
