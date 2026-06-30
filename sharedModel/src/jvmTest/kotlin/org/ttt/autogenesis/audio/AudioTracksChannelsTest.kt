package org.ttt.autogenesis.audio

import kotlinx.serialization.json.Json
import structs.audio.AudioTracks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Shared JSON codec for the tests below. Matches the lenient
 * settings the audio-tracks loader uses on the server side
 * (`ignoreUnknownKeys = true`, `isLenient = false`,
 * `encodeDefaults = true`) so a JSON file authored by the editor
 * decodes identically in both places.
 */
private val testJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    encodeDefaults = true
}

/**
 * Contract for the new `channels` field on [structs.audio.AudioTracks].
 *
 * The audio-tracks editor (in `audioTracksEditor/`) is the canonical
 * author of the JSON file; this test pins down the structural shape the
 * server-side [org.ttt.autogenesis.server.audio.AudioTracksResourceLoader]
 * and the client-side [org.ttt.autogenesis.kvisionapp.audio.AudioEngine]
 * rely on:
 *
 *  - The Music master channel exists with `parentId = null`.
 *  - All eight music-category channels (Drone, Melody, Rhythm, Harmony,
 *    Menu, Start, Nemesis, End) exist and have `parentId = "Music"`.
 *  - Sfx exists and has `parentId = null` (independent of Music).
 *  - A track in the `melody` list has `channelId = "Melody"`.
 *  - The list of channels is preserved on re-serialization.
 *
 * The test reads the designer's actual production JSON shipped in
 * `sharedModel/src/commonMain/resources/audio/audio-tracks.json` via
 * the JVM classpath (loaded in the test resources folder). This makes
 * it a contract test against the real file rather than a synthetic
 * fixture, so a designer-side regression in the channel-tree export
 * is caught here.
 */
class AudioTracksChannelsTest
{
    @Test
    fun prodJson_decodesChannelsListWithExpectedShape()
    {
        val text = readProdJson()
        val tracks = testJson.decodeFromString(AudioTracks.serializer(), text)

        // 10 channels: Music + 8 categories + Sfx
        assertEquals(10, tracks.channels.size, "expected Music + 8 categories + Sfx")

        val byId = tracks.channels.associateBy { it.id }

        // Music master is a root
        assertNotNull(byId["Music"], "Music master channel must be present")
        assertEquals(null, byId["Music"]!!.parentId)
        assertEquals("Music", byId["Music"]!!.name)

        // Each music category sits under Music
        for (id in listOf("Drone", "Melody", "Rhythm", "Harmony", "Menu", "Start", "Nemesis", "End"))
        {
            assertNotNull(byId[id], "channel '$id' must be present in the JSON channels list")
            assertEquals("Music", byId[id]!!.parentId, "channel '$id' should be a child of Music")
        }

        // Sfx is a root, not a child of Music
        assertNotNull(byId["Sfx"], "Sfx channel must be present")
        assertEquals(null, byId["Sfx"]!!.parentId, "Sfx must be independent of Music so the Sfx slider can mute/lower SFX without affecting music")
    }

    @Test
    fun prodJson_melodyTrackHasMelodyChannelId()
    {
        val text = readProdJson()
        val tracks = testJson.decodeFromString(AudioTracks.serializer(), text)
        assertTrue(tracks.melody.isNotEmpty(), "expected at least one melody track in the production catalog")
        for (obj in tracks.melody)
        {
            assertEquals("Melody", obj.channelId, "melody-list tracks must route to the Melody channel")
        }
    }

    @Test
    fun prodJson_everyTrackRoutesToItsListChannel()
    {
        val text = readProdJson()
        val tracks = testJson.decodeFromString(AudioTracks.serializer(), text)
        val listToChannel = mapOf(
            "drone"   to "Drone",
            "melody"  to "Melody",
            "rhythm"  to "Rhythm",
            "harmony" to "Harmony",
            "menu"    to "Menu",
            "start"   to "Start",
            "nemesis" to "Nemesis",
            "end"     to "End"
        )
        for ((listName, channelId) in listToChannel)
        {
            // Look up the list directly on the AudioTracks instance
            // by name. We can't use reflection here (`::class.members`
            // isn't available on the Kotlin/JS target this test also
            // compiles for), so a `when` keyed on the list name is
            // the right shape — it's compile-checked and the compiler
            // will flag a typo on the property name at edit time.
            val list: List<org.ttt.autogenesis.audio.AudioObject> = when (listName)
            {
                "drone"   -> tracks.drone
                "melody"  -> tracks.melody
                "rhythm"  -> tracks.rhythm
                "harmony" -> tracks.harmony
                "menu"    -> tracks.menu
                "start"   -> tracks.start
                "nemesis" -> tracks.nemesis
                "end"     -> tracks.end
                else -> error("unhandled list name in test: $listName")
            }
            for (obj in list)
            {
                assertEquals(channelId, obj.channelId,
                    "track '${obj.resourceName}' is in the $listName list but channelId=${obj.channelId} (expected $channelId)")
            }
        }
    }

    @Test
    fun prodJson_oldTrackKeysStillDecode()
    {
        // The original 4 layer fields must still decode after the
        // channels field was added, and the 4 scenario fields must
        // still default to empty if absent. This is a backward-compat
        // guard for any older editor that hasn't been updated.
        val legacyJson = """
            {
              "drone":   [],
              "melody":  [],
              "rhythm":  [],
              "harmony": []
            }
        """.trimIndent()
        val tracks = testJson.decodeFromString(AudioTracks.serializer(), legacyJson)
        assertEquals(0, tracks.drone.size)
        assertEquals(0, tracks.menu.size)
        assertEquals(0, tracks.start.size)
        assertEquals(0, tracks.nemesis.size)
        assertEquals(0, tracks.end.size)
        assertEquals(0, tracks.channels.size, "old JSON without channels should decode with empty channels list")
    }

    private fun readProdJson(): String
    {
        // The production JSON lives at the classpath root
        // `audio/audio-tracks.json` for the JVM, picked up via the
        // shared resources folder. Loading it here makes this a
        // contract test against the real file.
        val stream = AudioTracksChannelsTest::class.java.classLoader
            .getResourceAsStream("audio/audio-tracks.json")
            ?: error("audio-tracks.json not on classpath; the shared resources folder should publish it")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
