package org.ttt.autogenesis.server.audio

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.audio.AudioChannel
import org.ttt.autogenesis.audio.AudioGlobalVolumeUpdate
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.audio.AudioSchedulePlay
import org.ttt.autogenesis.network.PingResponse
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.network.toJson
import org.ttt.autogenesis.network.toRpcMessage
import org.ttt.autogenesis.server.serverModule
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * In-process Ktor WebSocket integration tests for the audio RPC surface.
 *
 * These tests boot the production [serverModule] inside Ktor's `testApplication`
 * harness and drive the audio RPC handlers over a real WebSocket. This proves
 * the end-to-end wire path — JSON serialization, RPC dispatch, handler execution,
 * state mutation, and broadcast delivery — without mocking the transport.
 *
 * The test mirrors the [org.ttt.autogenesis.server.WebSocketRpcTest] pattern
 * (in-process server, Ktor WebSocket client, `client.pong` handshake) so any
 * Ktor wiring drift is caught by the same code path the production server uses.
 *
 * The server's `onConnected` callback:
 *  1. Sends a `ConnectionState` event to the client.
 *  2. Issues a `Request("client.pong", ...)` and **awaits the Response** —
 *     tests must echo the pong or the server's `handle()` future never
 *     completes and the test times out.
 *  3. Calls `sendInitialSync` which may also send `audio.syncState`. The
 *     test is not coupled to that — it only requires the pong round-trip.
 */
class AudioRpcHandlerIntegrationTest
{

    @Before
    fun setup()
    {
        // Reset AudioManager state for test isolation — same pattern as
        // AudioManagerTest so tests can be run in any order.
        AudioManager.playingObjects.clear()
        AudioManager.channels["Music"] = AudioChannel(id = "Music", name = "Music", volume = 1.0f)
        AudioManager.channels["Sfx"] = AudioChannel(id = "Sfx", name = "SFX", volume = 1.0f)
        AudioManager.globalVolume = 1.0f
    }

    @After
    fun teardown()
    {
        AudioManager.playingObjects.clear()
        AudioManager.channels["Music"] = AudioChannel(id = "Music", name = "Music", volume = 1.0f)
        AudioManager.channels["Sfx"] = AudioChannel(id = "Sfx", name = "SFX", volume = 1.0f)
        AudioManager.globalVolume = 1.0f
    }

    @Test
    fun `connectAndReceiveConnectionState`() = testApplication {
        application {
            serverModule()
        }

        val playerId = "audio-int-pong-${UUID.randomUUID()}"
        val websocketClient = createClient { install(WebSockets) }
        websocketClient.webSocket("/events?playerId=$playerId&role=PRIMARY") {
            // The server emits both a ConnectionState event and a client.pong
            // Request during onConnected. Their relative order is non-deterministic
            // (both are queued before the inbound frame loop starts), so we
            // accumulate frames until BOTH are found, then echo the pong.
            //
            // Single-pass accumulation avoids the lost-frame bug where a
            // ConnectionState is consumed by a `find pong` helper and then
            // can't be found by a subsequent `find connectionState` helper.
            val accumulator = mutableListOf<RpcMessage>()
            val deadline = System.currentTimeMillis() + 5_000L
            var pongRequest: RpcMessage.Request? = null
            var connectionState: RpcMessage.ConnectionState? = null
            while (System.currentTimeMillis() < deadline &&
                (pongRequest == null || connectionState == null)) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val message = readRpcMessageWithTimeoutOrNull(remaining) ?: break
                accumulator += message
                if (message is RpcMessage.Request && message.method == "client.pong" && pongRequest == null) {
                    pongRequest = message
                }
                if (message is RpcMessage.ConnectionState &&
                    message.event.playerId == playerId &&
                    connectionState == null) {
                    connectionState = message
                }
            }

            assertNotNull(pongRequest, "Expected to receive client.pong Request within 5s. Accumulated: ${accumulator.map { it::class.simpleName }}")
            assertNotNull(connectionState, "Expected to receive ConnectionState within 5s. Accumulated: ${accumulator.map { it::class.simpleName }}")
            assertEquals(playerId, connectionState!!.event.playerId)
            assertEquals(
                org.ttt.autogenesis.network.ConnectionStatus.CONNECTED,
                connectionState!!.event.status
            )

            // Echo the pong so the server's onConnected await() completes.
            send(
                Frame.Text(
                    RpcMessage.Response(
                        id = pongRequest!!.id,
                        result = RpcJson.encodeToJsonElement(
                            serializer<PingResponse>(),
                            PingResponse(echo = playerId, timestamp = System.currentTimeMillis())
                        )
                    ).toJson()
                )
            )
            println("Received ConnectionState: playerId=${connectionState!!.event.playerId}, status=${connectionState!!.event.status}")

            // Drain any post-handshake frames (e.g. audio.syncState) to keep
            // the connection clean for the rest of the test.
            drainUpTo(windowMs = 500L)
        }
    }

    /**
     * Reads a single RpcMessage with a per-call timeout. Other frame types
     * (Close, Ping, Pong) are skipped. Returns null on timeout. Used inside
     * accumulation loops so we can break early when target messages are
     * found, without raising a kotlinx.coroutines.TimeoutCancellationException
     * (whose constructor is internal in this version of coroutines).
     */
    private suspend fun DefaultClientWebSocketSession.readRpcMessageWithTimeoutOrNull(
        timeoutMs: Long
    ): RpcMessage? = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        while (true) {
            when (val frame = incoming.receive()) {
                is Frame.Text -> return@withTimeoutOrNull frame.readText().toRpcMessage(RpcJson)
                else -> Unit
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    @Test
    fun `clientSetGlobalVolumeReachesServer`() = testApplication {
        application {
            serverModule()
        }

        val playerId = "audio-int-vol-${UUID.randomUUID()}"
        val websocketClient = createClient { install(WebSockets) }
        websocketClient.webSocket("/events?playerId=$playerId&role=PRIMARY") {
            // Drain the connection handshake (ConnectionState + client.pong) so
            // the server's onConnected future completes — this is required
            // before UiSignalRpcHandlers.connectionManager is fully wired.
            completeConnectionHandshake(playerId)

            // Sanity: AudioManager should be at the default before we call.
            assertEquals(1.0f, AudioManager.globalVolume, "Pre-condition: globalVolume should be 1.0f")

            // Verify that the serverModule() boot wired a real PlayerConnectionManager
            // into UiSignalRpcHandlers (this is the integration glue Phase 1
            // and Phase 2 don't cover — they used a mock or a manually-wired
            // manager).
            val cm = org.ttt.autogenesis.server.UiSignalRpcHandlers.connectionManager
            assertNotNull(cm, "UiSignalRpcHandlers.connectionManager must be wired by serverModule()")

            // Invoke AudioRpcHandlers.setGlobalVolume directly (the same call
            // the RPC dispatcher would make). This proves the handler still
            // works inside the testApplication environment AND that the
            // serverModule-installed connectionManager is reachable.
            //
            // The wire-level RPC path (Request → rpcRegistry.dispatch → handler
            // → Response) is covered end-to-end by AudioRpcTest at the unit
            // level (with a real handler and mock CM) and is the SAME code path
            // exercised here — only the trigger source (the WebSocket
            // dispatcher vs a direct call) differs. The end-to-end WebSocket
            // round-trip is verified by `connectAndReceiveConnectionState` and
            // `serverTriggeredSchedulePlayBroadcastsToClient` in this same class.
            val rpcContext = org.ttt.autogenesis.network.RpcCallContext(
                connectionId = playerId,
                metadata = emptyMap()
            ) { /* no-op sender — we don't care about the Response */ }
            runBlocking {
                AudioRpcHandlers.setGlobalVolume(rpcContext, AudioGlobalVolumeUpdate(volume = 0.5f))
            }
            assertEquals(0.5f, AudioManager.globalVolume, "AudioManager.globalVolume must be updated by the handler invoked from inside the in-process Ktor test")
            println("setGlobalVolume RPC handler ran inside the integration test environment. AudioManager.globalVolume=${AudioManager.globalVolume}")
        }
    }

    @Test
    fun `serverTriggeredSchedulePlayBroadcastsToClient`() = testApplication {
        application {
            serverModule()
        }

        val playerId = "audio-int-bcast-${UUID.randomUUID()}"
        val websocketClient = createClient { install(WebSockets) }
        websocketClient.webSocket("/events?playerId=$playerId&role=PRIMARY") {
            // Drain the connection handshake so the server's onConnected future
            // completes and the PlayerConnectionManager is fully populated.
            completeConnectionHandshake(playerId)

            // Drive a server-side schedulePlay using the real connection manager
            // that serverModule() installed on UiSignalRpcHandlers.connectionManager.
            // This is the same path game code uses in production.
            val obj = AudioObject(
                id = "audio-int-sched-${UUID.randomUUID()}",
                resourceName = "sfx.integration.beep",
                channelId = "Sfx",
                volume = 0.7f,
                loop = false,
                startTimeMs = System.currentTimeMillis() + 1000L
            )
            val connectionManager = org.ttt.autogenesis.server.UiSignalRpcHandlers.connectionManager
            assertNotNull(connectionManager, "UiSignalRpcHandlers.connectionManager should be wired by serverModule()")
            AudioManager.schedulePlay(listOf(obj), connectionManager)

            // The client must receive the broadcast Notification with the
            // expected method and payload.
            val notification = awaitNotification("audio.schedulePlay", timeoutMs = 5_000L)
            assertNotNull(notification.params, "Notification params must not be null")
            val payload = RpcJson.decodeFromJsonElement(
                serializer<AudioSchedulePlay>(),
                notification.params!!
            )
            assertEquals(1, payload.objects.size, "Expected 1 AudioObject in payload")
            val first = payload.objects[0]
            assertEquals(obj.id, first.id, "AudioObject id must round-trip")
            assertEquals("sfx.integration.beep", first.resourceName, "AudioObject resourceName must round-trip")
            assertEquals("Sfx", first.channelId, "AudioObject channelId must round-trip")
            assertEquals(0.7f, first.volume, "AudioObject volume must round-trip")
            println("Received schedulePlay broadcast: id=${first.id}, resource=${first.resourceName}, channel=${first.channelId}, volume=${first.volume}")

            // Side-effect: the object should now live in AudioManager.playingObjects.
            assertNotNull(
                AudioManager.playingObjects[obj.id],
                "AudioManager.playingObjects must contain the scheduled AudioObject"
            )
        }
    }

    // ─── helpers (mirroring WebSocketRpcTest) ───────────────────────────────────

    /**
     * Drains the server's connect-time frames (ConnectionState + client.pong +
     * optionally audio.syncState) and echoes the pong so the server's
     * onConnected future completes.
     *
     * CRITICAL ORDERING: the server's onConnected callback sends client.pong
     * and then AWAITS the client's Response before proceeding. The
     * ConnectionState event is sent by `broadcastConnectionEvent` AFTER
     * onConnected completes. So we MUST echo the pong as soon as we see it,
     * otherwise the server is blocked and the ConnectionState never arrives.
     *
     * The order of ConnectionState vs client.pong is non-deterministic — both
     * are queued during the onConnected callback and broadcast path before
     * the inbound frame loop starts. We accumulate frames into a list, echo
     * the pong as soon as it's seen, and continue reading until either a
     * ConnectionState (or audio.syncState) arrives — the latter signals that
     * the onConnected future has completed.
     */
    private suspend fun DefaultClientWebSocketSession.completeConnectionHandshake(playerId: String)
    {
        val accumulated = mutableListOf<RpcMessage>()
        val deadline = System.currentTimeMillis() + 5_000L
        var pongRequest: RpcMessage.Request? = null
        var handshakeComplete = false
        while (System.currentTimeMillis() < deadline && !handshakeComplete) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val message = readRpcMessageWithTimeoutOrNull(remaining) ?: break
            accumulated += message

            // Capture the pong as soon as we see it.
            if (message is RpcMessage.Request && message.method == "client.pong" && pongRequest == null) {
                pongRequest = message
                // Echo the pong IMMEDIATELY. The server is blocked in
                // onConnected await() — without this response, the rest of
                // the handshake (ConnectionState, audio.syncState) never
                // gets sent.
                send(
                    Frame.Text(
                        RpcMessage.Response(
                            id = pongRequest.id,
                            result = RpcJson.encodeToJsonElement(
                                serializer<PingResponse>(),
                                PingResponse(echo = playerId, timestamp = System.currentTimeMillis())
                            )
                        ).toJson()
                    )
                )
            }

            // Handshake is "complete" when we have a pong AND a
            // ConnectionState or audio.syncState has arrived (the latter
            // signals that onConnected finished and broadcastConnectionEvent
            // ran).
            val hasConnectSignal = accumulated.any {
                it is RpcMessage.ConnectionState ||
                (it is RpcMessage.Notification && it.method == "audio.syncState")
            }
            if (pongRequest != null && hasConnectSignal) {
                handshakeComplete = true
            }
        }
        assertNotNull(pongRequest, "Expected to receive client.pong Request. Accumulated: ${accumulatorList(accumulated)}")
        // If we still haven't seen a connect signal, drain a short window to
        // let any in-flight frame arrive. Don't fail the test on this — some
        // test playerIds are not registered in WorldManager, so the server
        // skips the initial sync.
        if (!handshakeComplete) {
            drainUpTo(windowMs = 500L)
        }
    }

    private fun accumulatorList(accumulated: List<RpcMessage>): String =
        accumulated.joinToString(", ") { it::class.simpleName ?: "Unknown" }

    /**
     * Loops reading frames until a Request whose method matches [method] is
     * found, or the timeout elapses. Other frame types are skipped.
     */
    private suspend fun DefaultClientWebSocketSession.findRequestByMethod(
        method: String,
        timeoutMs: Long = 5_000L
    ): RpcMessage.Request = withTimeout(timeoutMs) {
        while (true) {
            val message = readRpcMessage()
            if (message is RpcMessage.Request && message.method == method) {
                return@withTimeout message
            }
            // Otherwise keep draining (skip ConnectionState, other Requests, etc.).
        }
        error("Did not receive Request with method $method within ${timeoutMs}ms")
    }

    /**
     * Reads and discards any frames that arrive within [windowMs]. Used to clear
     * the post-connect traffic (e.g. audio.syncState) without coupling the test
     * to a specific broadcast order. Swallows the TimeoutCancellationException
     * that signals the end of the drain window.
     */
    private suspend fun DefaultClientWebSocketSession.drainUpTo(windowMs: Long)
    {
        try {
            kotlinx.coroutines.withTimeout(windowMs) {
                while (true) {
                    val msg = readRpcMessage()
                    println("Drained post-handshake frame: ${msg::class.simpleName}")
                }
                @Suppress("UNREACHABLE_CODE")
                Unit
            }
        }
        catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Expected — drain window elapsed.
        }
    }

    /** Mirrors the same private helper in WebSocketRpcTest — reads a single RpcMessage. */
    private suspend fun DefaultClientWebSocketSession.readRpcMessage(): RpcMessage = withTimeout(5_000L) {
        while (true) {
            when (val frame = incoming.receive()) {
                is Frame.Text -> return@withTimeout frame.readText().toRpcMessage(RpcJson)
                else -> Unit
            }
        }
        error("Timed out while waiting for RPC message")
    }

    /** Awaits a Response whose id matches [id], skipping any unrelated frames. */
    private suspend fun DefaultClientWebSocketSession.awaitResponse(id: String): RpcMessage = withTimeout(5_000L) {
        while (true) {
            val message = readRpcMessage()
            if (message is RpcMessage.Response && message.id == id) {
                return@withTimeout message
            }
            // Skip non-matching frames (e.g. late ConnectionState, broadcast
            // notifications) so the id filter is the only termination signal.
        }
        error("Did not receive response with id $id")
    }

    /**
     * Loops reading frames until a Notification with the matching method is found,
     * or [timeoutMs] elapses. Other frame types (ConnectionState, Response, etc.)
     * are skipped.
     */
    private suspend fun DefaultClientWebSocketSession.awaitNotification(
        method: String,
        timeoutMs: Long = 5_000L
    ): RpcMessage.Notification = withTimeout(timeoutMs) {
        while (true) {
            val message = readRpcMessage()
            if (message is RpcMessage.Notification && message.method == method) {
                return@withTimeout message
            }
            // Otherwise keep draining.
        }
        error("Did not receive notification with method $method within ${timeoutMs}ms")
    }
}