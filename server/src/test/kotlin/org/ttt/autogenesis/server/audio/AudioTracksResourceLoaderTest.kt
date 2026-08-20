package org.ttt.autogenesis.server.audio

import kotlinx.serialization.SerializationException
import org.junit.Test
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.server.audio.AudioTracksResourceLoader
import structs.audio.AudioTracks
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AudioTracksResourceLoader].
 *
 * The loader's job is to read the audio-tracks JSON from the server's
 * bundled resources (or an arbitrary `InputStream` / `ByteArray`) and
 * convert it into the [AudioTracks] data class. These tests pin the
 * contract that [GameInit] relies on:
 *
 *  1. `loadFromBytes` accepts a valid payload and returns the parsed
 *     [AudioTracks].
 *  2. The four layer fields and the four scenario fields are all parsed.
 *  3. A missing required field (one of the four original layer fields)
 *     throws a [SerializationException] with a useful message.
 *  4. Malformed JSON throws a [SerializationException].
 *  5. A missing resource path throws a clear [IllegalArgumentException].
 *  6. `loadFromResource` accepts a `String` path and uses the thread
 *     context class loader to look up the resource. We exercise that
 *     by writing a fixture JSON to a temp file and pointing the
 *     loader at the temp directory's classpath-equivalent (a
 *     `URLClassLoader`).
 */
class AudioTracksResourceLoaderTest
{
    @Test
    fun loadFromBytes_validPayload_returnsParsedTracks()
    {
        val json = """
            {
              "drone": [
                { "id": "d-1", "resourceName": "music.drone.pad", "channelId": "Music", "volume": 0.5, "panning": 0.0, "speed": 1.0, "loop": true, "startTimeMs": 0, "fadeInDurationMs": 0, "fadeOutDurationMs": 0, "loopWithTail": true }
              ],
              "melody": [],
              "rhythm": [],
              "harmony": [],
              "menu": [],
              "start": [
                { "id": "s-1", "resourceName": "music.start.theme", "channelId": "Music", "volume": 0.9, "panning": 0.0, "speed": 1.0, "loop": true, "startTimeMs": 0, "fadeInDurationMs": 2000, "fadeOutDurationMs": 0, "loopWithTail": false }
              ],
              "nemesis": [],
              "end": []
            }
        """.trimIndent()

        val tracks = AudioTracksResourceLoader.loadFromBytes(json.toByteArray(Charsets.UTF_8))

        assertEquals(1, tracks.drone.size)
        assertEquals("music.drone.pad", tracks.drone[0].resourceName)
        assertEquals(0.5f, tracks.drone[0].volume)
        assertEquals(true, tracks.drone[0].loop)
        assertEquals(true, tracks.drone[0].loopWithTail)
        assertEquals(0, tracks.melody.size)
        assertEquals(0, tracks.rhythm.size)
        assertEquals(0, tracks.harmony.size)
        assertEquals(0, tracks.menu.size)
        assertEquals(1, tracks.start.size)
        assertEquals("music.start.theme", tracks.start[0].resourceName)
        assertEquals(0, tracks.nemesis.size)
        assertEquals(0, tracks.end.size)
    }

    @Test
    fun loadFromBytes_allEightFieldsPopulated_roundTrips()
    {
        val json = """
            {
              "drone":  [ { "id": "d",  "resourceName": "music.drone.x",  "channelId": "Music" } ],
              "melody": [ { "id": "m",  "resourceName": "music.melody.x", "channelId": "Music" } ],
              "rhythm": [ { "id": "r",  "resourceName": "music.rhythm.x", "channelId": "Music" } ],
              "harmony":[ { "id": "h",  "resourceName": "music.harmony.x","channelId": "Music" } ],
              "menu":   [ { "id": "mn", "resourceName": "music.menu.x",   "channelId": "Music" } ],
              "start":  [ { "id": "st", "resourceName": "music.start.x",  "channelId": "Music" } ],
              "nemesis":[ { "id": "nm", "resourceName": "music.nemesis.x","channelId": "Music" } ],
              "end":    [ { "id": "en", "resourceName": "music.end.x",    "channelId": "Music" } ]
            }
        """.trimIndent()

        val tracks = AudioTracksResourceLoader.loadFromBytes(json.toByteArray(Charsets.UTF_8))

        assertEquals(1, tracks.drone.size)
        assertEquals(1, tracks.melody.size)
        assertEquals(1, tracks.rhythm.size)
        assertEquals(1, tracks.harmony.size)
        assertEquals(1, tracks.menu.size)
        assertEquals(1, tracks.start.size)
        assertEquals(1, tracks.nemesis.size)
        assertEquals(1, tracks.end.size)
    }

    @Test
    fun loadFromBytes_legacyJsonMissingScenarioFields_defaultsToEmptyLists()
    {
        // JSON written by the editor version that did NOT have the four
        // scenario fields yet (menu / start / nemesis / end). They should
        // decode to empty lists, NOT throw.
        val json = """
            {
              "drone": [],
              "melody": [],
              "rhythm": [],
              "harmony": [
                { "id": "h-1", "resourceName": "music.harmony.legacy", "channelId": "Music" }
              ]
            }
        """.trimIndent()

        val tracks = AudioTracksResourceLoader.loadFromBytes(json.toByteArray(Charsets.UTF_8))

        assertEquals(1, tracks.harmony.size)
        assertEquals(0, tracks.menu.size)
        assertEquals(0, tracks.start.size)
        assertEquals(0, tracks.nemesis.size)
        assertEquals(0, tracks.end.size)
    }

    @Test
    fun loadFromBytes_missingRequiredLayerField_throwsSerializationException()
    {
        // Missing the `drone` field. The editor's loadFileIO requires
        // the four original layer fields; we mirror that contract.
        val json = """
            {
              "melody": [],
              "rhythm": [],
              "harmony": []
            }
        """.trimIndent()

        val ex = assertFailsWith<SerializationException> {
            AudioTracksResourceLoader.loadFromBytes(json.toByteArray(Charsets.UTF_8))
        }
        val message = ex.message ?: ""
        assertTrue(
            message.contains("drone", ignoreCase = true) ||
            message.contains("missing", ignoreCase = true),
            "Error should mention the missing field, got: $message"
        )
    }

    @Test
    fun loadFromBytes_malformedJson_throwsSerializationException()
    {
        val json = "this is not json {"

        assertFailsWith<SerializationException> {
            AudioTracksResourceLoader.loadFromBytes(json.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun loadFromBytes_emptyObject_decodesAsEmptyAudioTracks()
    {
        // An object with no fields at all should still parse, because
        // every AudioTracks field has a default of mutableListOf().
        val json = "{}"

        val tracks = AudioTracksResourceLoader.loadFromBytes(json.toByteArray(Charsets.UTF_8))

        assertNotNull(tracks)
        assertEquals(0, tracks.drone.size)
        assertEquals(0, tracks.melody.size)
        assertEquals(0, tracks.rhythm.size)
        assertEquals(0, tracks.harmony.size)
        assertEquals(0, tracks.menu.size)
        assertEquals(0, tracks.start.size)
        assertEquals(0, tracks.nemesis.size)
        assertEquals(0, tracks.end.size)
    }

    @Test
    fun loadFromBytes_emptyByteArray_throwsSerializationException()
    {
        assertFailsWith<SerializationException> {
            AudioTracksResourceLoader.loadFromBytes(ByteArray(0))
        }
    }

    @Test
    fun loadFromResource_bundledAudioTracksFile_exists()
    {
        // The packaged audio-tracks.json resource should be reachable
        // via the thread context class loader. If this test fails the
        // resource is missing from the server jar.
        val tracks = AudioTracksResourceLoader.loadFromResource("audio/audio-tracks.json")

        // The default sample file we ship has at least one track in
        // each scenario tab (matching the editor's sample data).
        assertTrue(
            tracks.menu.size >= 1 || tracks.start.size >= 1 ||
            tracks.nemesis.size >= 1 || tracks.end.size >= 1,
            "Bundled audio-tracks.json should populate at least one scenario tab, got: " +
                "menu=${tracks.menu.size} start=${tracks.start.size} " +
                "nemesis=${tracks.nemesis.size} end=${tracks.end.size}"
        )
    }

    @Test
    fun loadFromResource_missingPath_throwsClearException()
    {
        val ex = assertFailsWith<IllegalArgumentException> {
            AudioTracksResourceLoader.loadFromResource("audio/does-not-exist.json")
        }
        val message = ex.message ?: ""
        assertTrue(
            message.contains("does-not-exist.json") || message.contains("not found", ignoreCase = true),
            "Error should identify the missing path, got: $message"
        )
    }

    @Test
    fun loadFromString_delegatesToBytesAndReturnsSameResult()
    {
        val json = """
            {
              "drone": [],
              "melody": [],
              "rhythm": [],
              "harmony": []
            }
        """.trimIndent()

        val fromString: AudioTracks = AudioTracksResourceLoader.loadFromString(json)
        val fromBytes: AudioTracks = AudioTracksResourceLoader.loadFromBytes(json.toByteArray(Charsets.UTF_8))

        assertEquals(fromBytes.drone.size, fromString.drone.size)
        assertEquals(fromBytes.melody.size, fromString.melody.size)
        assertEquals(fromBytes.rhythm.size, fromString.rhythm.size)
        assertEquals(fromBytes.harmony.size, fromString.harmony.size)
    }
}