package org.ttt.autogenesis.audiotrackseditor

import kotlinx.serialization.json.Json
import org.ttt.autogenesis.audio.AudioObject
import structs.audio.AudioTracks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the four new [AudioTracks] fields added to support the
 * editor's "scenario tabs" (MENU, START, NEMESIS, END).
 *
 * These tabs correspond to the same-named [org.ttt.autogenesis.audio.MusicCategory]
 * variants in the music-selector pipeline (InitialConditions / Nemesis /
 * TerminalConditions, plus a new Menu track for the title screen).
 *
 * The new fields default to an empty mutable list so existing code that
 * constructs [AudioTracks] with positional `drone`/`melody`/`rhythm`/`harmony`
 * arguments continues to compile, and old JSON files that omit the new
 * fields decode with the new fields set to empty.
 */
class AudioTracksExtendedFieldsTest
{
    private fun track(id: String, name: String): AudioObject = AudioObject(
        id = id,
        resourceName = name,
        channelId = "Music"
    )

    // ─── default values ────────────────────────────────────────────────────

    @Test
    fun audioTracks_hasFourNewFields()
    {
        // The new fields are: menu, start, nemesis, end.
        val tracks = AudioTracks()
        assertNotNull(tracks.menu, "AudioTracks must have a `menu` field")
        assertNotNull(tracks.start, "AudioTracks must have a `start` field")
        assertNotNull(tracks.nemesis, "AudioTracks must have a `nemesis` field")
        assertNotNull(tracks.end, "AudioTracks must have an `end` field")
        assertTrue(tracks.menu.isEmpty(), "menu must default to empty")
        assertTrue(tracks.start.isEmpty(), "start must default to empty")
        assertTrue(tracks.nemesis.isEmpty(), "nemesis must default to empty")
        assertTrue(tracks.end.isEmpty(), "end must default to empty")
    }

    // ─── populating the new fields ─────────────────────────────────────────

    @Test
    fun audioTracks_canCarryTracksInEachNewField()
    {
        val tracks = AudioTracks(
            menu = mutableListOf(track("m-1", "music.menu")),
            start = mutableListOf(track("s-1", "music.start")),
            nemesis = mutableListOf(track("n-1", "music.nemesis")),
            end = mutableListOf(track("e-1", "music.end"))
        )
        assertEquals(1, tracks.menu.size)
        assertEquals(1, tracks.start.size)
        assertEquals(1, tracks.nemesis.size)
        assertEquals(1, tracks.end.size)
        assertEquals("music.menu", tracks.menu[0].resourceName)
        assertEquals("music.start", tracks.start[0].resourceName)
        assertEquals("music.nemesis", tracks.nemesis[0].resourceName)
        assertEquals("music.end", tracks.end[0].resourceName)
    }

    // ─── round-trip ────────────────────────────────────────────────────────

    @Test
    fun audioTracks_roundTripsThroughJsonWithAllEightFields()
    {
        val original = AudioTracks(
            drone = mutableListOf(track("d-1", "music.drone.pad")),
            melody = mutableListOf(track("m-1", "music.melody.theme")),
            rhythm = mutableListOf(track("r-1", "music.rhythm.drums")),
            harmony = mutableListOf(track("h-1", "music.harmony.chord")),
            menu = mutableListOf(track("menu-1", "music.menu.theme")),
            start = mutableListOf(track("start-1", "music.start.conditions")),
            nemesis = mutableListOf(track("nem-1", "music.nemesis.theme")),
            end = mutableListOf(track("end-1", "music.end.terminal"))
        )
        val json = Json.encodeToString(AudioTracks.serializer(), original)
        val decoded = Json.decodeFromString(AudioTracks.serializer(), json)
        assertEquals(original, decoded)
    }
}
