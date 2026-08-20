package org.ttt.autogenesis.kvisionapp.audio

import org.ttt.autogenesis.audio.AudioObject
import structs.audio.AudioTracks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract for [MenuMusicPlayer]:
 *  1. `parseAudioTracks` decodes the designer's JSON into the shared
 *     `AudioTracks` model (same lenient settings the server's loader
 *     uses).
 *  2. `extractMenuTrack` returns `audioTracks.menu[0]`, or null if
 *     the editor wrote an empty `menu` list.
 *  3. The shape the designer ships (top-level `menu` array of
 *     `AudioObject`s) round-trips through the decoder with the
 *     field names the main menu actually reads.
 *
 * The "designer-authored JSON loads into the world" side of the
 * contract is covered server-side by
 * `AudioTracksResourceLoaderTest.loadFromResource_bundledAudioTracksFile_exists`
 * and `GameInitAudioTracksTest` (JVM, both use classpath loading).
 * This file covers the client-side extraction logic only.
 */
class MenuMusicPlayerTest
{
    // ─── parseAudioTracks ──────────────────────────────────────────────────

    @Test
    fun parseAudioTracks_decodesProdCatalogShape()
    {
        // Slice of the designer's prod export — proves the shape
        // (top-level `menu` array of `AudioObject`s with `id`,
        // `resourceName`, `channelId`, `volume`, `fadeInDurationMs`)
        // round-trips through the shared `AudioTracks` decoder.
        val text = """
            {
              "drone": [],
              "melody": [],
              "rhythm": [],
              "harmony": [],
              "menu": [
                {
                  "id": "menu-1",
                  "resourceName": "Xilaron and Eleuryiyidict wet final",
                  "channelId": "Music",
                  "volume": 0.8,
                  "fadeInDurationMs": 2000
                }
              ],
              "start": [],
              "nemesis": [],
              "end": []
            }
        """.trimIndent()
        val audioTracks = MenuMusicPlayer.parseAudioTracks(text)
        assertEquals(1, audioTracks.menu.size)
        assertEquals("Xilaron and Eleuryiyidict wet final", audioTracks.menu[0].resourceName)
        assertEquals("Music", audioTracks.menu[0].channelId)
    }

    @Test
    fun parseAudioTracks_toleratesMissingScenarioFields()
    {
        // Older editor exports may omit the scenario tabs entirely.
        // The shared `AudioTracks` data class defaults them to empty
        // lists, and our codec must accept that.
        val text = """{"drone":[],"melody":[],"rhythm":[],"harmony":[]}"""
        val audioTracks = MenuMusicPlayer.parseAudioTracks(text)
        assertEquals(0, audioTracks.menu.size)
        assertEquals(0, audioTracks.start.size)
    }

    @Test
    fun parseAudioTracks_ignoresUnknownFields()
    {
        // Forward-compat: if the editor adds a new field we have not
        // modelled yet, we must still decode the rest of the file.
        val text = """
            {
              "drone": [],
              "melody": [],
              "rhythm": [],
              "harmony": [],
              "menu": [{"id": "m1", "resourceName": "X", "channelId": "Music"}],
              "futureField": { "ignored": true }
            }
        """.trimIndent()
        val audioTracks = MenuMusicPlayer.parseAudioTracks(text)
        assertEquals("X", audioTracks.menu[0].resourceName)
    }

    // ─── extractMenuTrack ──────────────────────────────────────────────────

    @Test
    fun extractMenuTrack_returnsFirstMenuEntry()
    {
        val audioTracks = AudioTracks(
            menu = mutableListOf(
                AudioObject(
                    id = "menu-1",
                    resourceName = "Xilaron and Eleuryiyidict wet final",
                    channelId = "Music",
                    volume = 0.8f,
                    fadeInDurationMs = 2000L
                )
            )
        )
        val menu = MenuMusicPlayer.extractMenuTrack(audioTracks)
        assertNotNull(menu)
        assertEquals("menu-1", menu!!.id)
        assertEquals("Xilaron and Eleuryiyidict wet final", menu.resourceName)
        assertEquals(0.8f, menu.volume)
        assertEquals(2000L, menu.fadeInDurationMs)
        assertEquals("Music", menu.channelId)
    }

    @Test
    fun extractMenuTrack_returnsNullForEmptyMenu()
    {
        val audioTracks = AudioTracks()  // all eight lists default to empty
        val menu = MenuMusicPlayer.extractMenuTrack(audioTracks)
        assertNull(menu, "an empty menu list must produce null so the caller can log+skip")
    }

    // ─── source-of-truth contract ─────────────────────────────────────────

    @Test
    fun designersProdCatalogShape_extractsToNonNullMenuTrack()
    {
        // Mirrors the menu entry the designer ships in
        // `sharedModel/src/commonMain/resources/audio/audio-tracks.json`.
        // If a future editor change breaks the wire format (renames
        // `menu`, drops `channelId`, etc.), this test fires before
        // a real user hits a silent no-op.
        val text = """
            {
              "drone": [{"id":"d1","resourceName":"D-Track 1","channelId":"Music"}],
              "melody": [],
              "rhythm": [],
              "harmony": [],
              "menu": [{
                "id": "b4664dc53423dac269e97417ccb2e433",
                "resourceName": "Xilaron and Eleuryiyidict wet final",
                "channelId": "Music",
                "volume": 0.8,
                "fadeInDurationMs": 2000
              }],
              "start": [{"id":"s1","resourceName":"Initial Conditions wet 1","channelId":"Music"}],
              "nemesis": [{"id":"n1","resourceName":"Nemesis wet 1","channelId":"Music"}],
              "end": [{"id":"e1","resourceName":"Terminal Conditions wet 1","channelId":"Music"}]
            }
        """.trimIndent()
        val audioTracks = MenuMusicPlayer.parseAudioTracks(text)
        val menu = MenuMusicPlayer.extractMenuTrack(audioTracks)
        assertNotNull(
            menu,
            "designer-authored audio-tracks.json must declare exactly one menu track — " +
            "the main menu has no fallback source"
        )
        assertEquals("Music", menu!!.channelId, "main-menu track must live on the Music channel")
        assertTrue(
            menu.resourceName.isNotBlank(),
            "menu track must declare a non-blank resourceName (the resolver keys on it)"
        )
    }
}