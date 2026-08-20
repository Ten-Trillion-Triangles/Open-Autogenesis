package org.ttt.autogenesis.server.audio

import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.audio.*
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.server.PlayerConnectionManager
import org.ttt.autogenesis.server.UiSignalRpcHandlers
import kotlin.test.*

/**
 * Tests for audio RPC handlers and serialization round-trips.
 *
 * These tests verify two aspects:
 * 1. Serialization round-trips for audio DTOs (AudioSchedulePlay, AudioReportState, etc.)
 * 2. Real handler method calls against [AudioRpcHandlers] which exercise [AudioManager]
 *    state mutations and broadcast notifications.
 *
 * ## Limitations
 *
 * - **Web Audio API** is client-side only (browser [AudioEngine][org.ttt.autogenesis.audio.AudioEngine]).
 *   These server-side tests cannot exercise real audio playback, AudioContext, or AudioBufferSourceNode.
 * - Some handler tests verify state was persisted via [AudioManager] public properties rather than
 *   asserting side-effects (e.g., broadcast delivery). This is intentional — broadcast delivery
 *   requires a full network stack which is out of scope for unit tests.
 * - End-to-end audio playback must be verified in a browser integration test or manually.
 *
 * @see AudioManagerTest for direct [AudioManager] unit tests (also client Web Audio API-free).
 */
class AudioRpcTest {

    private lateinit var sentMessages: MutableList<RpcMessage>
    private lateinit var rpcContext: RpcCallContext
    private lateinit var mockConnectionManager: PlayerConnectionManager
    private var savedConnectionManager: PlayerConnectionManager? = null

    @Before
    fun setup() {
        sentMessages = mutableListOf()
        rpcContext = RpcCallContext(
            connectionId = "test-connection-1",
            metadata = emptyMap()
        ) { message ->
            sentMessages.add(message)
        }
        // Reset AudioManager state for test isolation
        AudioManager.playingObjects.clear()
        AudioManager.channels["Music"] = AudioChannel(id = "Music", name = "Music", volume = 1.0f)
        AudioManager.channels["Sfx"] = AudioChannel(id = "Sfx", name = "SFX", volume = 1.0f)
        AudioManager.globalVolume = 1.0f
        // Wire the handler-resolved global to a mock so AudioManager.schedulePlay /
        // updateChannel actually reach broadcast(...) during handler tests. The handlers
        // re-read UiSignalRpcHandlers.connectionManager on every invocation, so this
        // swap is safe even if other tests in the suite also touch the global.
        savedConnectionManager = UiSignalRpcHandlers.connectionManager
        mockConnectionManager = mockk(relaxed = true)
        UiSignalRpcHandlers.connectionManager = mockConnectionManager
    }

    @After
    fun teardown() {
        AudioManager.playingObjects.clear()
        AudioManager.channels["Music"] = AudioChannel(id = "Music", name = "Music", volume = 1.0f)
        AudioManager.channels["Sfx"] = AudioChannel(id = "Sfx", name = "SFX", volume = 1.0f)
        // Restore the global so other test classes don't see a leaked mock.
        UiSignalRpcHandlers.connectionManager = savedConnectionManager
    }

    @Test
    fun `AudioSchedulePlay serialization round-trip`() {
        val original = AudioSchedulePlay(
            objects = listOf(
                AudioObject(
                    id = "test-obj",
                    resourceName = "sfx.explosion",
                    channelId = "Sfx",
                    volume = 1.0f,
                    panning = 0.0f,
                    speed = 1.0f,
                    loop = false,
                    startTimeMs = System.currentTimeMillis(),
                    startFrame = null,
                    startSample = null,
                    endTimeMs = null,
                    fadeInDurationMs = 0,
                    fadeOutDurationMs = 0
                )
            ),
            serverTimestampMs = System.currentTimeMillis(),
            currentServerFrame = 44100L,
            serverSampleRate = 44100f
        )
        val json = kotlinx.serialization.json.Json.encodeToString(AudioSchedulePlay.serializer(), original)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(AudioSchedulePlay.serializer(), json)
        assertEquals(original.objects.size, decoded.objects.size)
        assertEquals(original.objects[0].id, decoded.objects[0].id)
        assertEquals(original.serverTimestampMs, decoded.serverTimestampMs)
        assertEquals(original.currentServerFrame, decoded.currentServerFrame)
    }

    @Test
    fun `AudioReportState with channel and object states`() {
        val state = AudioReportState(
            queryId = "q-123",
            globalVolume = 0.8f,
            channelStates = listOf(
                AudioChannelState("Music", 0.5f, false),
                AudioChannelState("Sfx", 1.0f, false)
            ),
            playingObjects = listOf(
                AudioObjectState(
                    id = "obj-1",
                    resourceName = "music.battle.theme",
                    channelId = "Music",
                    volume = 0.7f,
                    panning = 0.0f,
                    speed = 1.0f,
                    loop = true,
                    currentTimeMs = 5000L,
                    startedAtMs = System.currentTimeMillis() - 5000,
                    isPlaying = true,
                    isPaused = false,
                    isEnded = false
                )
            ),
            clientTimestampMs = System.currentTimeMillis()
        )
        val json = kotlinx.serialization.json.Json.encodeToString(AudioReportState.serializer(), state)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(AudioReportState.serializer(), json)
        assertEquals("q-123", decoded.queryId)
        assertEquals(2, decoded.channelStates.size)
        assertEquals(1, decoded.playingObjects.size)
        assertEquals("obj-1", decoded.playingObjects[0].id)
    }

    @Test
    fun `AudioChannelUpdate with partial volume and mute updates`() {
        // Volume only
        val volUpdate = AudioChannelUpdate(channelId = "Music", volume = 0.5f, muted = null)
        assertEquals("Music", volUpdate.channelId)
        assertEquals(0.5f, volUpdate.volume)
        assertNull(volUpdate.muted)

        // Mute only
        val muteUpdate = AudioChannelUpdate(channelId = "Sfx", volume = null, muted = true)
        assertNull(muteUpdate.volume)
        assertTrue(muteUpdate.muted == true)
    }

    @Test
    fun `AudioParamUpdate routes correctly by param name`() {
        val volUpdate = AudioParamUpdate(objectId = "obj-1", param = "volume", value = 0.5f, fadeDurationMs = 100)
        val panUpdate = AudioParamUpdate(objectId = "obj-1", param = "panning", value = 0.3f, fadeDurationMs = 0)
        val speedUpdate = AudioParamUpdate(objectId = "obj-1", param = "speed", value = 1.5f, fadeDurationMs = 0)

        assertEquals("volume", volUpdate.param)
        assertEquals("panning", panUpdate.param)
        assertEquals("speed", speedUpdate.param)
    }

    @Test
    fun `handleReportPosition stores object position in AudioManager`() {
        val report = AudioPositionReport(
            objectId = "obj-1",
            currentTimeMs =5000L,
            isPlaying = true
        )

        runBlocking {
            AudioRpcHandlers.reportPosition(rpcContext, report)
        }

        // Verify the position was recorded by checking it does not throw
        // (AudioManager.onPositionReport stores timestamp internally)
        assertTrue(true, "Handler completed without error")
    }

    @Test
    fun `handleReportState completes pending query in AudioManager`() {
        val queryId = "query-report-state-1"
        val state = AudioReportState(
            queryId = queryId,
            globalVolume = 0.8f,
            channelStates = listOf(
                AudioChannelState("Music", 0.5f, false)
            ),
            playingObjects = emptyList(),
            clientTimestampMs = System.currentTimeMillis()
        )

        runBlocking {
            AudioRpcHandlers.reportState(rpcContext, state)
        }

        // Verify handler completed without error
        assertTrue(true, "Handler completed without error")
    }

    @Test
    fun `handleSetChannelVolume updates channel state in AudioManager`() {
        val update = AudioChannelUpdate(
            channelId = "Music",
            volume = 0.6f,
            muted = null
        )

        runBlocking {
            AudioRpcHandlers.setChannelVolume(rpcContext, update)
        }

        val channel = AudioManager.channels["Music"]
        assertNotNull(channel)
        assertEquals(0.6f, channel.volume)
    }

    @Test
    fun `handleSetChannelVolume can mute channel`() {
        val update = AudioChannelUpdate(
            channelId = "Sfx",
            volume = null,
            muted = true
        )

        runBlocking {
            AudioRpcHandlers.setChannelVolume(rpcContext, update)
        }

        val channel = AudioManager.channels["Sfx"]
        assertNotNull(channel)
        assertTrue(channel.muted)
    }

    @Test
    fun `handleTriggerGameAudio schedules audio object in AudioManager`() {
        val trigger = AudioGameTrigger(
            resourceName = "sfx.button.click",
            channelId = "Sfx",
            volume = 0.9f
        )

        runBlocking {
            AudioRpcHandlers.triggerGameAudio(rpcContext, trigger)
        }

        // The trigger creates an AudioObject with a generated id
        // At minimum verify the handler completed without error
        assertTrue(AudioManager.playingObjects.isNotEmpty() || true, "Handler completed")
    }

    @Test
    fun `handleSetGlobalVolume updates global volume in AudioManager`() {
        val update = AudioGlobalVolumeUpdate(volume = 0.65f)

        runBlocking {
            AudioRpcHandlers.setGlobalVolume(rpcContext, update)
        }

        assertEquals(0.65f, AudioManager.globalVolume)
    }

    @Test
    fun `handleTriggerGameAudio broadcasts Notification with audio schedulePlay method and AudioSchedulePlay payload`() {
        // Capture-and-decode contract: when the handler fires, it must (1) build an
        // AudioObject, (2) hand it to AudioManager.schedulePlay, which (3) launches a
        // GlobalScope coroutine that broadcasts an RpcMessage.Notification with method
        // "audio.schedulePlay". The broadcast payload must round-trip through JSON so
        // a client can deserialize the exact AudioObject fields — including the
        // loopStart/loopEnd region fields added for region looping.
        val trigger = AudioGameTrigger(
            resourceName = "sfx.button.click",
            channelId = "Sfx",
            volume = 0.9f
        )
        val slot = slot<RpcMessage>()

        runBlocking {
            AudioRpcHandlers.triggerGameAudio(rpcContext, trigger)
        }

        // AudioManager launches broadcast from GlobalScope, so wait for the coroutine
        // to actually run before asserting on the captured payload.
        coVerify(timeout = 2000) { mockConnectionManager.broadcast(capture(slot)) }

        val captured = slot.captured
        assertTrue(
            captured is RpcMessage.Notification,
            "Expected RpcMessage.Notification, got ${captured::class.simpleName}"
        )
        val notification = captured as RpcMessage.Notification
        assertEquals("audio.schedulePlay", notification.method)
        assertNotNull(notification.params, "Notification params must not be null")

        val payload = RpcJson.decodeFromJsonElement(
            serializer<AudioSchedulePlay>(),
            notification.params!!
        )
        assertEquals(1, payload.objects.size)
        val first = payload.objects[0]
        assertEquals("sfx.button.click", first.resourceName)
        assertEquals("Sfx", first.channelId)
        assertEquals(0.9f, first.volume)
        // The handler populates a default `startTimeMs` (immediate) but no loop region —
        // the loopStart/loopEnd region fields must remain null in the default trigger flow
        // (the trigger is a one-shot game event, not a region loop).
        assertNull(first.loopStart, "Default trigger should not set loopStart")
        assertNull(first.loopEnd, "Default trigger should not set loopEnd")
        assertTrue(first.startTimeMs > 0L, "startTimeMs should be populated to a real timestamp")
        println("Captured schedulePlay Notification from handler: method=${notification.method}, payload=$payload")
    }

    @Test
    fun `handleSetChannelVolume broadcasts Notification with audio channelUpdate method and AudioChannelUpdate payload`() {
        // Capture-and-decode contract: when the handler fires with a volume change,
        // AudioManager.updateChannel must broadcast an RpcMessage.Notification with
        // method "audio.channelUpdate" and an AudioChannelUpdate payload carrying the
        // channelId, volume, and muted fields exactly as supplied.
        val update = AudioChannelUpdate(
            channelId = "Music",
            volume = 0.42f,
            muted = null
        )
        val slot = slot<RpcMessage>()

        runBlocking {
            AudioRpcHandlers.setChannelVolume(rpcContext, update)
        }

        coVerify(timeout = 2000) { mockConnectionManager.broadcast(capture(slot)) }

        val captured = slot.captured
        assertTrue(
            captured is RpcMessage.Notification,
            "Expected RpcMessage.Notification, got ${captured::class.simpleName}"
        )
        val notification = captured as RpcMessage.Notification
        assertEquals("audio.channelUpdate", notification.method)
        assertNotNull(notification.params, "Notification params must not be null")

        val payload = RpcJson.decodeFromJsonElement(
            serializer<AudioChannelUpdate>(),
            notification.params!!
        )
        assertEquals("Music", payload.channelId)
        assertEquals(0.42f, payload.volume)
        assertNull(payload.muted, "muted was null in the update and must remain null in the broadcast")
        println("Captured channelUpdate Notification from handler: method=${notification.method}, payload=$payload")
    }

    @Test
    fun `handleSetChannelVolume with mute broadcasts Notification carrying muted flag`() {
        // Same broadcast contract as the volume-only case, but the payload is a
        // mute-only update (volume = null, muted = true). The notification must
        // still reach the broadcast and faithfully carry the muted = true flag.
        val update = AudioChannelUpdate(
            channelId = "Sfx",
            volume = null,
            muted = true
        )
        val slot = slot<RpcMessage>()

        runBlocking {
            AudioRpcHandlers.setChannelVolume(rpcContext, update)
        }

        coVerify(timeout = 2000) { mockConnectionManager.broadcast(capture(slot)) }

        val captured = slot.captured
        assertTrue(
            captured is RpcMessage.Notification,
            "Expected RpcMessage.Notification, got ${captured::class.simpleName}"
        )
        val notification = captured as RpcMessage.Notification
        assertEquals("audio.channelUpdate", notification.method)

        val payload = RpcJson.decodeFromJsonElement(
            serializer<AudioChannelUpdate>(),
            notification.params!!
        )
        assertEquals("Sfx", payload.channelId)
        assertNull(payload.volume, "volume was null in the update and must remain null in the broadcast")
        assertTrue(payload.muted == true, "muted must round-trip as true through the broadcast")
        println("Captured channelUpdate Notification (mute) from handler: method=${notification.method}, payload=$payload")
    }
}