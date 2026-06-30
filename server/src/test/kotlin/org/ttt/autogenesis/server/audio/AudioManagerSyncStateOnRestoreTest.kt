package org.ttt.autogenesis.server.audio

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.audio.AudioChannel
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.audio.AudioSyncState
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.server.PlayerConnectionManager
import org.ttt.autogenesis.server.PlayerSession
import org.ttt.autogenesis.server.UiSignalRpcHandlers
import structs.Player
import structs.World
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression pin for the music-restoration contract on resume.
 *
 * When a player resumes — in-place via
 * [org.ttt.autogenesis.server.GameRestoreRpcHandlers.applyRestoredWorldAndSync]
 * or fresh-DS via the first [UiSignalRpcHandlers.sendInitialSync] after
 * matchmaking provisions a new dedicated server — the server must push a
 * single `audio.syncState` notification to the player whose
 * `scheduledObjects` list contains the gameplay music that was playing
 * at the time the previous session ended. That notification is what lets
 * the browser client's AudioEngine reconstruct the music state without a
 * perceptible gap when the player comes back.
 *
 * Pinned contracts (do NOT relax):
 *
 *  1. [UiSignalRpcHandlers.sendInitialSync] dispatches an
 *     `RpcMessage.Notification` with method `audio.syncState` whose
 *     payload decodes back to an [AudioSyncState] carrying every track
 *     currently scheduled in [AudioManager.playingObjects] on the Music
 *     channel.
 *  2. The same notification is dispatched even when the playing-objects
 *     map is empty (fresh-DS resume before MusicSelector has run its
 *     first turn). The payload's `scheduledObjects` is then empty, but
 *     the notification MUST still arrive so the client can re-anchor
 *     its clock and avoid drifting on first playback.
 *  3. The `scheduledObjects` payload preserves the `id` and
 *     `resourceName` of every scheduled track so the client can fetch
 *     the right audio resource and resume playback without a manifest
 *     round-trip.
 *
 * If any of these three contracts breaks, the player comes back to a
 * silent game until the next MusicSelector decision on the first turn —
 * a regression that would be easy to ship silently in a
 * UiSignalRpcHandlers refactor. These tests catch that immediately.
 */
class AudioManagerSyncStateOnRestoreTest
{
    private lateinit var mockConnectionManager: PlayerConnectionManager
    private var savedConnectionManager: PlayerConnectionManager? = null

    @Before
    fun setUp()
    {
        savedConnectionManager = UiSignalRpcHandlers.connectionManager
        mockConnectionManager = mockk(relaxed = true)
        UiSignalRpcHandlers.connectionManager = mockConnectionManager

        // Reset AudioManager state so each test starts from a known baseline.
        AudioManager.playingObjects.clear()
        AudioManager.channels["Music"] = AudioChannel(id = "Music", name = "Music")
        AudioManager.channels["Sfx"] = AudioChannel(id = "Sfx", name = "SFX")
        AudioManager.globalVolume = 1.0f
    }

    @After
    fun tearDown()
    {
        AudioManager.playingObjects.clear()
        AudioManager.channels["Music"] = AudioChannel(id = "Music", name = "Music")
        AudioManager.channels["Sfx"] = AudioChannel(id = "Sfx", name = "SFX")
        UiSignalRpcHandlers.connectionManager = savedConnectionManager
    }

    /**
     * Helper: drain the GlobalScope-launched broadcast coroutine produced
     * by [AudioManager.schedulePlay] so it doesn't pollute the
     * sendInitialSync captures. We coVerify with a timeout so the test
     * does not race the dispatcher.
     */
    private fun drainSchedulePlayBroadcast()
    {
        coVerify(timeout = 2000) { mockConnectionManager.broadcast(any()) }
    }

    /**
     * Contract 1: when a Music-channel track is scheduled before
     * sendInitialSync runs (mimicking the state at disconnect), the
     * player receives an `audio.syncState` notification whose payload
     * contains exactly that track.
     */
    @Test
    fun `sendInitialSync broadcasts audio_syncState containing scheduled gameplay music tracks`()
    {
        val connectionId = "test-resume-conn-${System.nanoTime()}"

        // Schedule a real music track via the public schedulePlay path —
        // identical to what the production music code does at runtime.
        val obj = AudioObject(
            id = "resumed-track-${System.nanoTime()}",
            resourceName = "music.ambient.forest",
            channelId = "Music",
            volume = 0.85f,
            panning = 0.0f,
            speed = 1.0f,
            loop = true,
            startTimeMs = 0L,
            endTimeMs = null,
            fadeInDurationMs = 2000L,
            fadeOutDurationMs = 2000L
        )
        runBlocking {
            AudioManager.schedulePlay(listOf(obj), mockConnectionManager)
        }
        assertEquals(1, AudioManager.playingObjects.size, "schedulePlay should store one track")
        drainSchedulePlayBroadcast()

        // Wire findAllSessions to return a mock session and capture every
        // sendRpcMessage call into a list.
        val session = mockk<PlayerSession>(relaxed = true)
        coEvery { mockConnectionManager.findAllSessions(connectionId) } returns listOf(session)
        val sent = mutableListOf<RpcMessage>()
        coEvery { session.sendRpcMessage(capture(sent)) } returns Unit

        runBlocking {
            UiSignalRpcHandlers.sendInitialSync(
                connectionId = connectionId,
                localPlayer = Player(name = "Commander Shepard"),
                mapPackBytes = null,
                world = World(roundNumber = 7),
                history = emptyList()
            )
        }

        val audioSync = sent.filterIsInstance<RpcMessage.Notification>()
            .firstOrNull { it.method == "audio.syncState" }
        assertNotNull(
            audioSync,
            "Expected an 'audio.syncState' notification in sendInitialSync output; got methods: " +
                sent.filterIsInstance<RpcMessage.Notification>().map { it.method }
        )

        val decoded = RpcJson.decodeFromJsonElement(
            serializer<AudioSyncState>(),
            audioSync.params!!
        )
        assertEquals(
            1,
            decoded.scheduledObjects.size,
            "audio.syncState.scheduledObjects must contain the one music track that was scheduled before sendInitialSync"
        )
        val pinned = decoded.scheduledObjects[0]
        assertEquals(obj.id, pinned.id, "scheduled object id must round-trip through the sync payload")
        assertEquals(obj.resourceName, pinned.resourceName, "scheduled object resourceName must round-trip")
        assertEquals("Music", pinned.channelId, "scheduled object channelId must be preserved")
        assertTrue(pinned.loop, "scheduled object loop flag must survive the sync round-trip")
    }

    /**
     * Contract 2: the audio.syncState notification is dispatched even when
     * AudioManager.playingObjects is empty (fresh-DS resume path, before
     * the first MusicSelector turn has run). scheduledObjects is then an
     * empty list, but the notification MUST still arrive so the client can
     * re-anchor its clock and avoid drifting on first playback.
     */
    @Test
    fun `sendInitialSync audio_syncState contains empty scheduledObjects when no music is scheduled (fresh-DS resume)`()
    {
        val connectionId = "test-resume-empty-${System.nanoTime()}"

        assertEquals(0, AudioManager.playingObjects.size, "Test premise: AudioManager starts empty")

        val session = mockk<PlayerSession>(relaxed = true)
        coEvery { mockConnectionManager.findAllSessions(connectionId) } returns listOf(session)
        val sent = mutableListOf<RpcMessage>()
        coEvery { session.sendRpcMessage(capture(sent)) } returns Unit

        runBlocking {
            UiSignalRpcHandlers.sendInitialSync(
                connectionId = connectionId,
                localPlayer = Player(name = "Commander Shepard"),
                mapPackBytes = null,
                world = World(roundNumber = 1),
                history = emptyList()
            )
        }

        val audioSync = sent.filterIsInstance<RpcMessage.Notification>()
            .firstOrNull { it.method == "audio.syncState" }
        assertNotNull(
            audioSync,
            "audio.syncState must be dispatched on a fresh-DS resume even when no music is scheduled yet"
        )

        val decoded = RpcJson.decodeFromJsonElement(
            serializer<AudioSyncState>(),
            audioSync.params!!
        )
        assertEquals(
            0,
            decoded.scheduledObjects.size,
            "scheduledObjects must be empty (NOT missing the notification entirely) when AudioManager has no tracks"
        )
        // globalVolume and channels should still come through — they are
        // part of the contract too, even on the empty path.
        assertEquals(1.0f, decoded.globalVolume)
        assertTrue(decoded.channels.isNotEmpty(), "channels list must include Music + SFX defaults")
    }

    /**
     * Contract 3: the AudioSyncState payload preserves the track id and
     * resourceName of scheduled objects so the client can fetch the right
     * audio resource without a manifest round-trip. This pins
     * [AudioManager.buildSyncState]'s wire-format contract directly
     * (synchronously) — the previous two tests pin the sendInitialSync
     * dispatch contract.
     */
    @Test
    fun `audio_syncState payload preserves track id and resourceName so the client can resume playback`()
    {
        val obj = AudioObject(
            id = "track-test-1",
            resourceName = "resources/audio/start.mp3",
            channelId = "Music",
            volume = 1.0f,
            loop = true,
            fadeInDurationMs = 1500L
        )
        runBlocking {
            AudioManager.schedulePlay(listOf(obj), mockConnectionManager)
        }
        drainSchedulePlayBroadcast()

        val state = AudioManager.buildSyncState()
        assertEquals(
            1,
            state.scheduledObjects.size,
            "buildSyncState must reflect the one track we just scheduled"
        )
        val entry = state.scheduledObjects[0]
        assertEquals("track-test-1", entry.id, "id must round-trip through ScheduledAudio.toAudioObject")
        assertEquals(
            "resources/audio/start.mp3",
            entry.resourceName,
            "resourceName must round-trip so the client can resume playback of the exact same file"
        )
        assertEquals("Music", entry.channelId, "channelId must round-trip")
    }
}
