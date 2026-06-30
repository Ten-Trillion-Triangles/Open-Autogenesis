package org.ttt.autogenesis.server.audio

import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.audio.AudioChannel
import org.ttt.autogenesis.audio.AudioMusicSchedule
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.audio.MusicDecision
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.server.PlayerConnectionManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wire-format test for [AudioManager.broadcastMusicSchedule].
 *
 * Mirrors the existing `AudioManagerTest` capture-and-decode pattern:
 * the manager launches the broadcast from GlobalScope, so the test
 * uses mockk with a coroutine timeout to wait for the call. The captured
 * RpcMessage is decoded back to an AudioMusicSchedule and the resource
 * names are pinned.
 */
class AudioManagerMusicScheduleTest
{
    private lateinit var mockConnectionManager: PlayerConnectionManager

    @Before
    fun setup()
    {
        mockConnectionManager = mockk(relaxed = true)
        AudioManager.playingObjects.clear()
        AudioManager.channels["Music"] = AudioChannel(id = "Music", name = "Music")
        AudioManager.channels["Sfx"] = AudioChannel(id = "Sfx", name = "SFX")
        AudioManager.globalVolume = 1.0f
    }

    @After
    fun teardown()
    {
        AudioManager.playingObjects.clear()
        AudioManager.channels["Music"] = AudioChannel(id = "Music", name = "Music")
        AudioManager.channels["Sfx"] = AudioChannel(id = "Sfx", name = "SFX")
    }

    @Test
    fun `broadcastMusicSchedule sends an audio_musicSchedule notification with payload that round-trips`()
    {
        val decision = MusicDecision(
            toPlay = listOf(
                AudioObject(
                    id = "new-1",
                    resourceName = "Initial Conditions wet 1",
                    channelId = "Music",
                    volume = 1.0f,
                    loop = true,
                    fadeInDurationMs = 2000L
                )
            ),
            toFadeOut = listOf("old-1", "old-2"),
            fadeOutDurationMs = 2000L,
            fadeInDurationMs = 2000L
        )
        val slot = slot<RpcMessage>()

        runBlocking {
            AudioManager.broadcastMusicSchedule(decision, mockConnectionManager)
        }

        coVerify(timeout = 2000) { mockConnectionManager.broadcast(capture(slot)) }

        val captured = slot.captured
        assertTrue(
            captured is RpcMessage.Notification,
            "Expected RpcMessage.Notification, got ${captured::class.simpleName}"
        )
        val notification = captured as RpcMessage.Notification
        assertEquals("audio.musicSchedule", notification.method)
        assertNotNull(notification.params, "Notification params must not be null")

        val decoded = RpcJson.decodeFromJsonElement(
            serializer<AudioMusicSchedule>(),
            notification.params!!
        )
        assertEquals(listOf("old-1", "old-2"), decoded.decision.toFadeOut)
        assertEquals(2000L, decoded.decision.fadeOutDurationMs)
        assertEquals(2000L, decoded.decision.fadeInDurationMs)
        assertEquals(1, decoded.decision.toPlay.size)
        assertEquals("Initial Conditions wet 1", decoded.decision.toPlay[0].resourceName)
        assertEquals("Music", decoded.decision.toPlay[0].channelId)
        assertNotNull(decoded.serverTimestampMs)
        assertNotNull(decoded.currentServerFrame)
    }

    @Test
    fun `broadcastMusicSchedule does not require a connection manager`()
    {
        val decision = MusicDecision(
            toPlay = listOf(
                AudioObject(
                    id = "x",
                    resourceName = "Nemesis wet 1",
                    channelId = "Music"
                )
            ),
            toFadeOut = emptyList(),
            fadeOutDurationMs = 1000L
        )
        runBlocking {
            // No crash with null connectionManager; the broadcast just
            // becomes a no-op (the `?.broadcast` chain).
            AudioManager.broadcastMusicSchedule(decision, null)
        }
    }
}
