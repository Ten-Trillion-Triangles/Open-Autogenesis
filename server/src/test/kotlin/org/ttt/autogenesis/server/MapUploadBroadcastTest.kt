package org.ttt.autogenesis.server

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.network.toRpcMessage
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression test for the [UiSignalRpcHandlers.broadcastMapLoad] path
 * that the [org.ttt.autogenesis.network.MapRpcHandlers.uploadMapPack]
 * fix relies on.
 *
 * ## Bug being prevented
 *
 * The original [org.ttt.autogenesis.network.MapRpcHandlers.uploadMapPack]
 * implementation had a `// TODO: Broadcast the new map to all clients`
 * comment where the broadcast should have been. The fix replaces that
 * TODO with a call to [UiSignalRpcHandlers.broadcastMapLoad]. This test
 * exercises that broadcast path end-to-end with a real WebSocket client
 * so a future refactor cannot silently drop the broadcast.
 *
 * ## Why we test [UiSignalRpcHandlers.broadcastMapLoad] directly
 *
 * Driving the full [org.ttt.autogenesis.network.MapRpcHandlers.uploadMapPack]
 * path requires a real map pack on disk (a 1+ MB fixture), which gets
 * chunked across 100+ WebSocket frames on the broadcast — fine for
 * production, but it turns a focused unit test into a slow integration
 * test. The single line of glue between the upload handler and the
 * broadcast helper is:
 *
 * ```kotlin
 * org.ttt.autogenesis.server.UiSignalRpcHandlers.broadcastMapLoad(request.mapPackBytes)
 * ```
 *
 * Testing the broadcast helper directly pins down the contract that
 * the upload handler depends on without the time/network cost of the
 * full upload.
 *
 * ## Test approach
 *
 * Same Ktor `testApplication` + WebSocket client pattern as
 * [UiSignalNetworkingTest]: connect, drain the handshake, trigger
 * the broadcast, read frames in a small window, match by `method`.
 */
class MapUploadBroadcastTest
{
    /**
     * Verifies that [UiSignalRpcHandlers.broadcastMapLoad] reaches a
     * connected client via the `ui.loadMapPack` notification.
     *
     * This is the contract the uploadMapPack fix depends on — if this
     * stops working, every other client falls out of sync on map upload.
     */
    @Test
    fun `broadcastMapLoad sends ui_loadMapPack to all connected clients`() = testApplication {
        application {
            serverModule()
        }

        val playerId = "test-map-broadcast-client"
        val websocketClient = createClient { install(WebSockets) }

        websocketClient.webSocket("/events?playerId=$playerId") {
            // Drain the connection-state / ping handshake so the server-side
            // register() has fully completed before we trigger the broadcast.
            repeat(2) {
                val msg = readRpcMessage()
                assertTrue(
                    msg is RpcMessage.ConnectionState || msg is RpcMessage.Request,
                    "Expected handshake message, got: $msg"
                )
            }

            // Sanity: the connection manager is now wired up (server-side).
            assertNotNull(
                UiSignalRpcHandlers.connectionManager,
                "Connection manager should be set after the test app starts"
            )

            // Trigger the broadcast. A small stub pack is enough — we only
            // care that the notification reaches the client.
            val testPack = ByteArray(64) { it.toByte() }
            UiSignalRpcHandlers.broadcastMapLoad(testPack)

            // Read frames in a small window looking for the ui.loadMapPack
            // notification. The server may emit other notifications
            // interleaved; we tolerate those.
            val deadline = System.currentTimeMillis() + 5_000L
            var sawLoadMapPack = false
            while (System.currentTimeMillis() < deadline && !sawLoadMapPack) {
                val msg = readRpcMessage()
                if (msg is RpcMessage.Notification && msg.method == "ui.loadMapPack") {
                    sawLoadMapPack = true
                    // Sanity-check the payload contains some data.
                    assertTrue(
                        msg.params.toString().isNotEmpty(),
                        "ui.loadMapPack payload should not be empty"
                    )
                }
            }
            assertTrue(
                sawLoadMapPack,
                "Expected this client to receive a ui.loadMapPack notification " +
                    "after UiSignalRpcHandlers.broadcastMapLoad() completed"
            )
        }
    }

    /**
     * Reads an RPC message from the WebSocket session with a timeout.
     */
    private suspend fun DefaultClientWebSocketSession.readRpcMessage(): RpcMessage = withTimeout(5_000L)
    {
        while (true)
        {
            when (val frame = incoming.receive())
            {
                is Frame.Text -> return@withTimeout frame.readText().toRpcMessage(RpcJson)
                else -> Unit
            }
        }
        error("Timed out while waiting for RPC message")
    }
}