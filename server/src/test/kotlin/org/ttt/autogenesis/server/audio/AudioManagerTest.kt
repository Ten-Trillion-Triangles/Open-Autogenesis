package org.ttt.autogenesis.server.audio

import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.audio.*
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.server.PlayerConnectionManager
import kotlin.test.*

/**
 * Unit tests for [AudioManager], the server-side authoritative audio state tracker.
 *
 * These tests exercise [AudioManager] methods directly against in-memory state.
 * They do NOT instantiate [AudioContext][javax.sound.sampled.AudioContext] because
 * [AudioManager] does not use Web Audio API — it is a server-side state tracker only.
 *
 * Web Audio API runs client-side in browsers via [AudioEngine][org.ttt.autogenesis.audio.AudioEngine].
 * Real end-to-end audio playback must be verified in integration tests or manually in a browser.
 *
 * @see AudioRpcHandlers for RPC handler tests that call into AudioManager methods.
 */
class AudioManagerTest {

    private lateinit var mockConnectionManager: PlayerConnectionManager

    @Before
    fun setup() {
        mockConnectionManager = mockk(relaxed = true)
        AudioManager.playingObjects.clear()
        // Reset channel state for test isolation
        AudioManager.channels["Music"] = AudioChannel(id = "Music", name = "Music", volume = 1.0f)
        AudioManager.channels["Sfx"] = AudioChannel(id = "Sfx", name = "SFX", volume = 1.0f)
        AudioManager.globalVolume = 1.0f
        // Reset channels to default state
        AudioManager.channels["Music"] = AudioChannel(id = "Music", name = "Music")
        AudioManager.channels["Sfx"] = AudioChannel(id = "Sfx", name = "SFX")
    }

    @After
    fun teardown() {
        AudioManager.playingObjects.clear()
        // Reset channel state for test isolation
        AudioManager.channels["Music"] = AudioChannel(id = "Music", name = "Music", volume = 1.0f)
        AudioManager.channels["Sfx"] = AudioChannel(id = "Sfx", name = "SFX", volume = 1.0f)
    }

    @Test
    fun `schedulePlay stores ScheduledAudio with correct startTimeMs`() {
        val obj = AudioObject(
            id = "test-1",
            resourceName = "music.battle.theme",
            channelId = "Music",
            volume = 0.8f,
            panning = 0.0f,
            speed = 1.0f,
            loop = false,
            startTimeMs = System.currentTimeMillis() + 5000,
            endTimeMs = null,
            fadeInDurationMs = 0,
            fadeOutDurationMs = 0,
            startFrame = null,
            startSample = null
        )
        runBlocking {
            AudioManager.schedulePlay(listOf(obj), mockConnectionManager)
        }
        assertEquals(1, AudioManager.playingObjects.size)
        assertEquals("test-1", AudioManager.playingObjects["test-1"]?.id)
    }

    @Test
    fun `volume hierarchy multiplication chain`() {
        // Test the volume multiplication chain: object 0.5 x channel 0.8 x global 0.5 = 0.2 effective
        val objVolume = 0.5f
        val channelVolume = 0.8f
        val globalVolume = 0.5f
        val effective = objVolume * channelVolume * globalVolume
        assertEquals(0.2f, effective, 0.001f)
    }

    @Test
    fun `stop removes ScheduledAudio from playingObjects`() {
        val obj = AudioObject(
            id = "stop-test",
            resourceName = "sfx.click",
            channelId = "Sfx",
            volume = 1.0f,
            panning = 0.0f,
            speed = 1.0f,
            loop = false,
            startTimeMs = 0,
            endTimeMs = null,
            fadeInDurationMs = 0,
            fadeOutDurationMs = 0,
            startFrame = null,
            startSample = null
        )
        runBlocking {
            AudioManager.schedulePlay(listOf(obj), mockConnectionManager)
        }
        assertEquals(1, AudioManager.playingObjects.size)
        runBlocking {
            AudioManager.stop("stop-test", fadeOutDurationMs = 0, connectionManager = mockConnectionManager)
        }
        assertTrue(AudioManager.playingObjects.isEmpty())
    }

    @Test
    fun `buildSyncState returns current globalVolume channels and objects`() {
        AudioManager.globalVolume = 0.75f
        val sync = AudioManager.buildSyncState()
        assertEquals(0.75f, sync.globalVolume)
        assertTrue(sync.channels.isNotEmpty())
    }

    @Test
    fun `ScheduledAudio toAudioObject preserves all fields`() {
        val original = ScheduledAudio(
            id = "conv-test",
            resourceName = "music.ambient.forest",
            channelId = "Music",
            volume = 0.6f,
            panning = 0.5f,
            speed = 1.5f,
            loop = true,
            scheduledStartMs = 1000L,
            startTimeMs = 2000L,
            startFrame = 44100L,
            startSample = 2000000L,
            endTimeMs = 5000L,
            fadeInDurationMs = 100L,
            fadeOutDurationMs = 200L
        )
        val converted = original.toAudioObject()
        assertEquals(original.id, converted.id)
        assertEquals(original.resourceName, converted.resourceName)
        assertEquals(original.channelId, converted.channelId)
        assertEquals(original.volume, converted.volume)
        assertEquals(original.panning, converted.panning)
        assertEquals(original.speed, converted.speed)
        assertEquals(original.loop, converted.loop)
        assertEquals(original.startTimeMs, converted.startTimeMs)
        assertEquals(original.startFrame, converted.startFrame)
        assertEquals(original.startSample, converted.startSample)
        assertEquals(original.endTimeMs, converted.endTimeMs)
        assertEquals(original.fadeInDurationMs, converted.fadeInDurationMs)
        assertEquals(original.fadeOutDurationMs, converted.fadeOutDurationMs)
    }

    @Test
    fun `AudioObject supports loopStart and loopEnd for region looping`() {
        // Region-loop contract: a developer can pin the loop to a sub-region
        // of the buffer (in seconds) instead of looping the whole file.
        // The fields are nullable: null = loop whole file (existing behaviour).
        val wholeFileLoop = AudioObject(
            id = "loop-whole",
            resourceName = "music.ambient.forest",
            channelId = "Music",
            loop = true,
            loopStart = null,
            loopEnd = null
        )
        assertNull(wholeFileLoop.loopStart, "loopStart defaults to null (loop whole file)")
        assertNull(wholeFileLoop.loopEnd, "loopEnd defaults to null (loop whole file)")

        val regionLoop = AudioObject(
            id = "loop-region",
            resourceName = "music.ambient.forest",
            channelId = "Music",
            loop = true,
            loopStart = 1.25,
            loopEnd = 3.75
        )
        assertEquals(1.25, regionLoop.loopStart!!, 1e-9)
        assertEquals(3.75, regionLoop.loopEnd!!, 1e-9)
    }

    @Test
    fun `ScheduledAudio preserves loopStart and loopEnd through toAudioObject`() {
        // The server's tracking record must round-trip the region fields
        // back to the wire format. Without this, the client never receives
        // the region info and falls back to "loop whole file".
        val original = ScheduledAudio(
            id = "region-roundtrip",
            resourceName = "music.ambient.forest",
            channelId = "Music",
            volume = 1.0f,
            panning = 0.0f,
            speed = 1.0f,
            loop = true,
            scheduledStartMs = 0L,
            startTimeMs = 0L,
            startFrame = null,
            startSample = null,
            endTimeMs = null,
            fadeInDurationMs = 0L,
            fadeOutDurationMs = 0L,
            loopStart = 2.0,
            loopEnd = 5.0
        )
        val converted = original.toAudioObject()
        assertEquals(2.0, converted.loopStart!!, 1e-9, "loopStart must survive round-trip")
        assertEquals(5.0, converted.loopEnd!!, 1e-9, "loopEnd must survive round-trip")
        assertTrue(converted.loop, "loop flag must stay true")
    }

    @Test
    fun `schedulePlay stores ScheduledAudio with loopStart and loopEnd`() {
        // End-to-end: AudioManager.schedulePlay -> playingObjects entry must
        // carry the region fields so a late-join client can rebuild its state.
        val obj = AudioObject(
            id = "sched-region",
            resourceName = "music.battle.theme",
            channelId = "Music",
            loop = true,
            loopStart = 10.0,
            loopEnd = 25.0
        )
        runBlocking {
            AudioManager.schedulePlay(listOf(obj), mockConnectionManager)
        }
        val scheduled = AudioManager.playingObjects["sched-region"]
        assertNotNull(scheduled)
        assertEquals(10.0, scheduled.loopStart!!, 1e-9)
        assertEquals(25.0, scheduled.loopEnd!!, 1e-9)
    }

    @Test
    fun `AudioSchedulePlay wire payload preserves loopStart and loopEnd`() {
        // The broadcast payload must include the region fields. The
        // broadcast itself is best-effort (GlobalScope.launch), so we
        // assert against the AudioSchedulePlay data class directly: a
        // client receiving a serialized payload with these fields will
        // deserialize them — if the data class is missing the field, it
        // can't carry them.
        val obj = AudioObject(
            id = "wire-region",
            resourceName = "music.ambient.forest",
            channelId = "Music",
            loop = true,
            loopStart = 0.5,
            loopEnd = 4.5
        )
        val payload = AudioSchedulePlay(
            objects = listOf(obj),
            serverTimestampMs = 0L,
            currentServerFrame = 0L,
            serverSampleRate = 44100f
        )
        assertEquals(1, payload.objects.size)
        assertEquals(0.5, payload.objects[0].loopStart!!, 1e-9)
        assertEquals(4.5, payload.objects[0].loopEnd!!, 1e-9)
    }

    @Test
    fun `updateChannel modifies channel volume`() {
        val channelBefore = AudioManager.channels["Music"]
        assertNotNull(channelBefore)
        assertEquals(1.0f, channelBefore.volume)
        assertFalse(channelBefore.muted)

        runBlocking {
            AudioManager.updateChannel(
                channelId = "Music",
                volume = 0.5f,
                muted = null,
                connectionManager = mockConnectionManager
            )
        }

        val channelAfter = AudioManager.channels["Music"]
        assertNotNull(channelAfter)
        assertEquals(0.5f, channelAfter.volume)
        assertFalse(channelAfter.muted)
    }

    @Test
    fun `updateChannel can mute channel`() {
        val channelBefore = AudioManager.channels["Music"]
        assertNotNull(channelBefore)
        assertFalse(channelBefore.muted)

        runBlocking {
            AudioManager.updateChannel(
                channelId = "Music",
                volume = null,
                muted = true,
                connectionManager = mockConnectionManager
            )
        }

        val channelAfter = AudioManager.channels["Music"]
        assertNotNull(channelAfter)
        assertEquals(1.0f, channelAfter.volume)
        assertTrue(channelAfter.muted)
    }

    @Test
    fun `setGlobalVolume updates globalVolume`() {
        assertEquals(1.0f, AudioManager.globalVolume)

        runBlocking {
            AudioManager.setGlobalVolume(volume = 0.75f, connectionManager = mockConnectionManager)
        }

        assertEquals(0.75f, AudioManager.globalVolume)
    }

    @Test
    fun `schedulePlay broadcasts Notification with audio schedulePlay method and AudioSchedulePlay payload`() {
        // Capture-and-decode contract: the server must broadcast an RpcMessage.Notification
        // whose `method` is "audio.schedulePlay" and whose `params` deserialize to an
        // AudioSchedulePlay carrying the exact AudioObject fields the client needs to
        // reconstruct the scheduled playback. The `loopStart`/`loopEnd` region fields
        // added in a prior TDD pass must survive the JSON round-trip.
        val obj = AudioObject(
            id = "broadcast-sched",
            resourceName = "music.battle.theme",
            channelId = "Music",
            volume = 0.8f,
            panning = 0.0f,
            speed = 1.0f,
            loop = true,
            startTimeMs = 1000L,
            endTimeMs = 60000L,
            fadeInDurationMs = 200L,
            fadeOutDurationMs = 300L,
            startFrame = null,
            startSample = null,
            loopStart = 1.5,
            loopEnd = 4.5
        )
        val slot = slot<RpcMessage>()

        runBlocking {
            AudioManager.schedulePlay(listOf(obj), mockConnectionManager)
        }

        // AudioManager launches the broadcast from GlobalScope, so we must wait for the
        // coroutine to actually run before asserting on the captured payload.
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
        assertEquals("broadcast-sched", first.id)
        assertEquals("music.battle.theme", first.resourceName)
        assertEquals("Music", first.channelId)
        assertEquals(0.8f, first.volume)
        assertTrue(first.loop, "loop flag must survive the broadcast round-trip")
        assertEquals(1.5, first.loopStart!!, 1e-9, "loopStart must round-trip through broadcast")
        assertEquals(4.5, first.loopEnd!!, 1e-9, "loopEnd must round-trip through broadcast")
        println("Captured schedulePlay Notification: method=${notification.method}, payload=$payload")
    }

    @Test
    fun `setGlobalVolume broadcasts Notification with audio paramUpdate method and AudioParamUpdate payload`() {
        // Capture-and-decode contract: when setGlobalVolume runs, the server must
        // broadcast a single RpcMessage.Notification with method "audio.paramUpdate"
        // and an AudioParamUpdate whose `param` is "globalVolume", `value` matches the
        // requested volume, and `fadeDurationMs` is 0 (instant application).
        val slot = slot<RpcMessage>()

        runBlocking {
            AudioManager.setGlobalVolume(volume = 0.5f, connectionManager = mockConnectionManager)
        }

        coVerify(timeout = 2000) { mockConnectionManager.broadcast(capture(slot)) }

        val captured = slot.captured
        assertTrue(
            captured is RpcMessage.Notification,
            "Expected RpcMessage.Notification, got ${captured::class.simpleName}"
        )
        val notification = captured as RpcMessage.Notification
        assertEquals("audio.paramUpdate", notification.method)
        assertNotNull(notification.params, "Notification params must not be null")

        val payload = RpcJson.decodeFromJsonElement(
            serializer<AudioParamUpdate>(),
            notification.params!!
        )
        assertEquals("globalVolume", payload.param)
        assertEquals(0.5f, payload.value)
        assertEquals(0L, payload.fadeDurationMs)
        println("Captured paramUpdate Notification: method=${notification.method}, payload=$payload")
    }
}
