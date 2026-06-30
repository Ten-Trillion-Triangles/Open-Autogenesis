package org.ttt.autogenesis.audiotrackseditor

import kotlinx.serialization.SerializationException
import org.ttt.autogenesis.audio.AudioObject
import structs.audio.AudioTracks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for the [AudioTracksFileIO] encode/decode wrapper. Each test
 * pins down one invariant of the public API so that the save/load wiring in
 * a later phase can rely on the I/O behaviour without re-deriving the rules
 * from the implementation.
 */
class AudioTracksFileIOTest
{
    /**
     * Build an [AudioObject] with every one of its 16 fields populated, used
     * by the round-trip test to confirm that the entire schema survives
     * encode/decode.
     */
    private fun fullTrack(): AudioObject = AudioObject(
        id = "full-1",
        resourceName = "music.drone.pad",
        channelId = "Music",
        volume = 0.75f,
        panning = -0.5f,
        speed = 1.5f,
        loop = true,
        startTimeMs = 0L,
        startFrame = 100L,
        startSample = 200L,
        endTimeMs = 5000L,
        fadeInDurationMs = 250L,
        fadeOutDurationMs = 500L,
        loopStart = 0.5,
        loopEnd = 4.0,
        loopWithTail = true
    )

    @Test
    fun encodeEmptyTracksProducesAllEmptyArrays()
    {
        val json = AudioTracksFileIO.encode(AudioTracks())

        assertTrue("\"drone\":[]" in json, "drone array missing in: $json")
        assertTrue("\"melody\":[]" in json, "melody array missing in: $json")
        assertTrue("\"rhythm\":[]" in json, "rhythm array missing in: $json")
        assertTrue("\"harmony\":[]" in json, "harmony array missing in: $json")
    }

    @Test
    fun encodePrettyPrintedContainsNewlinesAndIndent()
    {
        val json = AudioTracksFileIO.encode(AudioTracks())

        assertTrue("\n" in json, "expected pretty-print newlines in: $json")
        assertTrue("  " in json, "expected 2-space indent in: $json")
    }

    @Test
    fun decodeValidJsonReturnsEqualTracks()
    {
        val original = AudioTracks()

        val decoded = AudioTracksFileIO.decode(AudioTracksFileIO.encode(original))

        assertEquals(original, decoded)
        assertTrue(decoded.drone.isEmpty())
        assertTrue(decoded.melody.isEmpty())
        assertTrue(decoded.rhythm.isEmpty())
        assertTrue(decoded.harmony.isEmpty())
    }

    @Test
    fun decodeMalformedJsonThrows()
    {
        assertFailsWith<SerializationException> {
            AudioTracksFileIO.decode("{not valid json")
        }
    }

    @Test
    fun decodeWrongTypeThrows()
    {
        val bad = """{"drone":"not a list","melody":[],"rhythm":[],"harmony":[]}"""

        assertFailsWith<SerializationException> {
            AudioTracksFileIO.decode(bad)
        }
    }

    @Test
    fun decodeMissingFieldThrows()
    {
        val missingHarmony = """{"drone":[],"melody":[],"rhythm":[]}"""

        assertFailsWith<SerializationException> {
            AudioTracksFileIO.decode(missingHarmony)
        }
    }

    @Test
    fun roundTripWithFullTrackPreservesAllFields()
    {
        val original = AudioTracks(
            drone = mutableListOf(fullTrack()),
            melody = mutableListOf(),
            rhythm = mutableListOf(),
            harmony = mutableListOf()
        )

        val decoded = AudioTracksFileIO.decode(AudioTracksFileIO.encode(original))

        assertEquals(1, decoded.drone.size)
        val decodedTrack = decoded.drone[0]
        assertEquals("full-1", decodedTrack.id)
        assertEquals("music.drone.pad", decodedTrack.resourceName)
        assertEquals("Music", decodedTrack.channelId)
        assertEquals(0.75f, decodedTrack.volume)
        assertEquals(-0.5f, decodedTrack.panning)
        assertEquals(1.5f, decodedTrack.speed)
        assertEquals(true, decodedTrack.loop)
        assertEquals(0L, decodedTrack.startTimeMs)
        assertEquals(100L, decodedTrack.startFrame)
        assertEquals(200L, decodedTrack.startSample)
        assertEquals(5000L, decodedTrack.endTimeMs)
        assertEquals(250L, decodedTrack.fadeInDurationMs)
        assertEquals(500L, decodedTrack.fadeOutDurationMs)
        assertEquals(0.5, decodedTrack.loopStart)
        assertEquals(4.0, decodedTrack.loopEnd)
        assertEquals(true, decodedTrack.loopWithTail)
    }
}
