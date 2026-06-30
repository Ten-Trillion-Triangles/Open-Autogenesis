package org.ttt.autogenesis.server

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.*
import gameState.WorldManager
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.logging.LogPriority

class ChunkingSmokeTest {

    /**
     * The previous [org.ttt.autogenesis.server.UiSignalRpcHandlers.connectionManager]
     * reference, restored by [tearDown]. We null it out in [setup] because
     * [WorldManager.emitTurnTimerState] launches `GlobalScope` broadcasts
     * through [org.ttt.autogenesis.server.UiSignalRpcHandlers.broadcastTurnTimerUpdate]
     * whenever a turn timer fires. Those calls happen during
     * [WorldManager.loadMapFromPack] initialization, before the test body
     * has had a chance to stub the singleton, so MockK's verifier sees an
     * unstubbed call to the object and throws
     * `MockKException: can't find stub UiSignalRpcHandlers(object UiSignalRpcHandlers)`.
     *
     * `broadcastNotification` (the underlying helper) already short-circuits
     * when `connectionManager == null` (see `UiSignalRpcHandlers.kt:337-340`),
     * so nulling the manager is the lightest-weight fix and matches the
     * pattern in `UiSignalNullManagerTest`.
     */
    private var savedConnectionManager: org.ttt.autogenesis.server.PlayerConnectionManager? = null

    @Before
    fun setup()
    {
        savedConnectionManager = org.ttt.autogenesis.server.UiSignalRpcHandlers.connectionManager
        org.ttt.autogenesis.server.UiSignalRpcHandlers.connectionManager = null
    }

    @After
    fun tearDown()
    {
        org.ttt.autogenesis.server.UiSignalRpcHandlers.connectionManager = savedConnectionManager
    }

    @Test
    fun `large map transfer is transparently chunked and reassembled`() = testApplication {
        // 0. Setup logger for visibility
        Logger.configure(LogPriority.DEBUG, true, 1, "test")

        // 1. Load the real 1.6MB map into WorldManager
        WorldManager.loadMapFromResources("maps/IO-map.map")
        val originalBytes = WorldManager.activeMapPackBytes ?: error("Failed to load map")
        assertTrue(originalBytes.size > 1000000, "Map should be > 1MB")

        // 2. Register a player so Server.kt triggers Initial Sync
        val playerId = "smoke-test-player"
        WorldManager.addPlayerToWorld(
            structs.Player(name = "Smoke Test Player"),
            accelbyteID = "test-id",
            connectionId = playerId
        )

        application {
            serverModule()
        }

        val client = createClient { install(WebSockets) }
        val assembler = MultipartAssembler()

        client.webSocket("/events?playerId=$playerId") {
            var mapReceived = false
            Logger.info(org.ttt.autogenesis.logging.LogCategory.NETWORK, "Client: Connected to WebSocket")

            withTimeout(30_000L) {
                while (!mapReceived) {
                    val frame = incoming.receive()
                    if (frame is Frame.Text) {
                        val raw = frame.readText()

                        // Check if it's a multipart chunk
                        val payload = if (raw.contains("\"type\":\"multipart\"")) {
                            assembler.addChunk(raw.toRpcMessage(RpcJson) as RpcMessage.Multipart)
                        } else raw

                        if (payload != null) {
                            val message = payload.toRpcMessage(RpcJson)
                            if (message is RpcMessage.Notification && message.method == "ui.loadMapPack") {
                                val mapInstruction = RpcJson.decodeFromJsonElement(
                                    org.ttt.autogenesis.network.MapLoadInstruction.serializer(),
                                    message.params ?: error("No params in map load")
                                )

                                assertContentEquals(originalBytes, mapInstruction.mapPackBytes, "Reassembled map bytes must match original")
                                mapReceived = true
                            }
                        }
                    }
                }
            }
            assertTrue(mapReceived, "Should have received and reassembled the map notification")
        }
    }
}
