package org.ttt.autogenesis.server

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.serializer
import org.junit.Test
import org.junit.Ignore
import org.ttt.autogenesis.network.PingResponse
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.network.toJson
import org.ttt.autogenesis.network.toRpcMessage
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BooleanRpcTest {
    @Test
    @Ignore("Temporarily disabled due to WebSocket timing issues")
    fun `boolean rpc round trip through websocket endpoint`() = testApplication {
        application {
            serverModule()
        }

        val playerId = "test-player"
        val websocketClient = createClient { install(WebSockets) }
        websocketClient.webSocket("/events?playerId=$playerId") {
            val connectionState = readRpcMessage().also { assertTrue(it is RpcMessage.ConnectionState) }
            assertEquals(playerId, (connectionState as RpcMessage.ConnectionState).event.playerId)

            val serverRequest = readRpcMessage().also { assertTrue(it is RpcMessage.Request) }
            assertEquals("client.pong", (serverRequest as RpcMessage.Request).method)
            send(
                Frame.Text(
                    RpcMessage.Response(
                        id = serverRequest.id,
                        result = RpcJson.encodeToJsonElement(
                            serializer<PingResponse>(),
                            PingResponse(echo = playerId, timestamp = System.currentTimeMillis())
                        )
                    ).toJson()
                )
            )

            booleanRoundTrip(true)
            booleanRoundTrip(false)
        }
    }

    private suspend fun DefaultClientWebSocketSession.booleanRoundTrip(value: Boolean) {
        val requestId = "boolean-${UUID.randomUUID()}"
        send(
            Frame.Text(
                RpcMessage.Request(
                    id = requestId,
                    method = "test.boolean",
                    params = RpcJson.encodeToJsonElement(serializer<Boolean>(), value)
                ).toJson()
            )
        )

        val response = awaitResponse(requestId)
        assertEquals(requestId, response.id)
        val decodedResult = RpcJson.decodeFromJsonElement(
            serializer<Boolean>(),
            response.result ?: error("Expected boolean result")
        )
        assertEquals(value, decodedResult)
    }

    private suspend fun DefaultClientWebSocketSession.readRpcMessage(): RpcMessage = withTimeout(5_000L) {
        while (true) {
            when (val frame = incoming.receive()) {
                is Frame.Text -> return@withTimeout frame.readText().toRpcMessage(RpcJson)
                else -> Unit
            }
        }
        error("Timed out while waiting for RPC message")
    }

    private suspend fun DefaultClientWebSocketSession.awaitResponse(id: String): RpcMessage.Response = withTimeout(5_000L) {
        while (true) {
            val message = readRpcMessage()
            if (message is RpcMessage.Response && message.id == id) {
                return@withTimeout message
            }
        }
        error("Did not receive response with id $id")
    }
}
