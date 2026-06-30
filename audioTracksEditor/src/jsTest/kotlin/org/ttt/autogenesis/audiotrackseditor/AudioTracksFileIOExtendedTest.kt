package org.ttt.autogenesis.audiotrackseditor

import kotlinx.serialization.SerializationException
import org.ttt.autogenesis.audio.AudioObject
import structs.audio.AudioTracks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [AudioTracksFileIO] with the new scenario-tab fields
 * (menu, start, nemesis, end).
 *
 * Two contracts to verify:
 *  1. **Forward compat** — files written with all eight fields
 *     round-trip cleanly through encode → decode.
 *  2. **Backward compat** — files written by the previous version
 *     (only the four layer fields) still load, with the new
 *     scenario fields defaulting to empty.
 *
 * Existing strict checks (missing required field, malformed JSON)
 * are preserved and pinned by the pre-existing tests in
 * `AudioTracksFileIOTest`.
 */
class AudioTracksFileIOExtendedTest
{
    private fun track(id: String, name: String): AudioObject = AudioObject(
        id = id,
        resourceName = name,
        channelId = "Music"
    )

    // ─── encode covers all 8 fields ───────────────────────────────────────

    @Test
    fun encodeEmptyTracks_producesAllEightEmptyArrays()
    {
        val json = AudioTracksFileIO.encode(AudioTracks())

        assertTrue("\"drone\":[]" in json, "drone array missing in: $json")
        assertTrue("\"melody\":[]" in json, "melody array missing in: $json")
        assertTrue("\"rhythm\":[]" in json, "rhythm array missing in: $json")
        assertTrue("\"harmony\":[]" in json, "harmony array missing in: $json")
        assertTrue("\"menu\":[]" in json, "menu array missing in: $json")
        assertTrue("\"start\":[]" in json, "start array missing in: $json")
        assertTrue("\"nemesis\":[]" in json, "nemesis array missing in: $json")
        assertTrue("\"end\":[]" in json, "end array missing in: $json")
    }

    // ─── forward compat: round-trip with new fields ───────────────────────

    @Test
    fun roundTripWithAllEightFields_preservesEverything()
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

        val decoded = AudioTracksFileIO.decode(AudioTracksFileIO.encode(original))

        assertEquals(original, decoded)
        assertEquals(1, decoded.menu.size)
        assertEquals(1, decoded.start.size)
        assertEquals(1, decoded.nemesis.size)
        assertEquals(1, decoded.end.size)
        assertEquals("music.start.conditions", decoded.start[0].resourceName)
        assertEquals("music.end.terminal", decoded.end[0].resourceName)
    }

    // ─── backward compat: old 4-field files still load ────────────────────

    @Test
    fun decodeOldFormatWithoutNewFields_succeedsAndDefaultsToEmpty()
    {
        // A file written by the previous version of the editor — only
        // the four layer fields. The loader must accept it and
        // default the new fields to empty.
        val oldFormat = """
            {
              "drone": [],
              "melody": [],
              "rhythm": [],
              "harmony": []
            }
        """.trimIndent()

        val decoded = AudioTracksFileIO.decode(oldFormat)

        assertTrue(decoded.drone.isEmpty())
        assertTrue(decoded.melody.isEmpty())
        assertTrue(decoded.rhythm.isEmpty())
        assertTrue(decoded.harmony.isEmpty())
        // New fields default to empty
        assertTrue(decoded.menu.isEmpty())
        assertTrue(decoded.start.isEmpty())
        assertTrue(decoded.nemesis.isEmpty())
        assertTrue(decoded.end.isEmpty())
    }

    @Test
    fun decodeOldFormatWithTracks_preservesLayerTracksAndDefaultsNewFields()
    {
        val oldFormat = """
            {
              "drone": [
                { "id": "d-1", "resourceName": "music.drone.pad", "channelId": "Music" }
              ],
              "melody": [],
              "rhythm": [],
              "harmony": []
            }
        """.trimIndent()

        val decoded = AudioTracksFileIO.decode(oldFormat)

        assertEquals(1, decoded.drone.size)
        assertEquals("d-1", decoded.drone[0].id)
        assertTrue(decoded.menu.isEmpty())
        assertTrue(decoded.start.isEmpty())
        assertTrue(decoded.nemesis.isEmpty())
        assertTrue(decoded.end.isEmpty())
    }

    // ─── required-fields check still catches wrong file types ─────────────

    @Test
    fun decodeMissingOriginalLayerField_stillThrows()
    {
        // Backward-compat only covers the NEW fields. The original
        // four layer fields are still required — a file without
        // `harmony` is still a wrong file, even with all new fields
        // present.
        val missingHarmony = """
            {
              "drone": [],
              "melody": [],
              "rhythm": [],
              "menu": [],
              "start": [],
              "nemesis": [],
              "end": []
            }
        """.trimIndent()

        assertFailsWith<SerializationException> {
            AudioTracksFileIO.decode(missingHarmony)
        }
    }

    @Test
    fun decodeRandomObject_stillThrows()
    {
        // Sanity: a JSON object that is not an AudioTracks file at
        // all must still be rejected.
        val randomObj = """{"foo": "bar", "baz": 42}"""

        assertFailsWith<SerializationException> {
            AudioTracksFileIO.decode(randomObj)
        }
    }
}
