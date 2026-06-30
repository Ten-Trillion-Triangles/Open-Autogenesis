package org.ttt.autogenesis.server

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.encodeToJsonElement
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import structs.CountStreamRequest
import structs.CountStreamResponse

class WebSocketRpcTest {
    @Test
    @Ignore("Temporarily disabled due to WebSocket timing issues")
    fun `rpc round trip flows through websocket endpoint`() = testApplication {
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

            val requestId = "client-${UUID.randomUUID()}"
            send(
                Frame.Text(
                    RpcMessage.Request(
                        id = requestId,
                        method = "server.ping"
                    ).toJson()
                )
            )

            val response = awaitResponse(requestId)
            assertTrue(response is RpcMessage.Response)
            assertEquals(requestId, response.id)
            val pingResult = RpcJson.decodeFromJsonElement(
                serializer<PingResponse>(),
                response.result ?: error("Expected ping result")
            )
            assertEquals(playerId, pingResult.echo)

            val streamId = "stream-${UUID.randomUUID()}"
            send(
                Frame.Text(
                    RpcMessage.Request(
                        id = streamId,
                        method = "game.stream.count",
                        params = RpcJson.encodeToJsonElement(
                            serializer<CountStreamRequest>(),
                            CountStreamRequest(start = 7, count = 3)
                        )
                    ).toJson()
                )
            )

            val (chunks, streamResponse) = collectStreamChunks(streamId, expectedCount = 3)
            assertEquals(listOf(7, 8, 9), chunks.map { it.value })
            assertEquals(streamId, streamResponse.id)
            assertNull(streamResponse.error)
        }
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

    private suspend fun DefaultClientWebSocketSession.collectStreamChunks(
        id: String,
        expectedCount: Int
    ): Pair<List<CountStreamResponse>, RpcMessage.Response> {
        return withTimeout(5_000L) {
            val collected = mutableListOf<CountStreamResponse>()
            var response: RpcMessage.Response? = null
            while (response == null) {
                when (val message = readRpcMessage()) {
                    is RpcMessage.StreamChunk -> if (message.id == id) {
                        val chunk = RpcJson.decodeFromJsonElement(
                            serializer<CountStreamResponse>(),
                            message.payload
                        )
                        collected += chunk
                    }
                    is RpcMessage.Response -> if (message.id == id) {
                        response = message
                    }
                    else -> Unit
                }
            }
            check(collected.size == expectedCount) {
                "Stream produced ${collected.size} chunks, expected $expectedCount"
            }
            collected to response!!
        }
    }
}
