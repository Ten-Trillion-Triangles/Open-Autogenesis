package structs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.audio.AudioObject
import structs.audio.AudioTracks

/**
 * Contract for the new [World.audioTracks] field.
 *
 * The field stores the [AudioTracks] payload the server loaded at startup
 * (see `AudioTracksResourceLoader` on the server and `GameInit.defineGameRules`).
 * The wire format is shared with the audio-tracks editor and the JS client.
 *
 * These tests pin:
 *  1. The default value is an empty [AudioTracks] so a freshly constructed
 *     world is well-formed and the field never throws on first access.
 *  2. The field round-trips through [Json] (encoded -> decoded produces the
 *     same data).
 *  3. The new scenario tabs survive the round-trip (the editor and the
 *     server must agree on the eight field names).
 *  4. Old JSON missing the new scenario fields decodes with empty lists
 *     for those fields (backward compatibility).
 */
class WorldAudioTracksTest
{
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun world_defaultAudioTracksIsEmpty()
    {
        val world = World()

        assertNotNull(world.audioTracks)
        assertEquals(0, world.audioTracks.drone.size)
        assertEquals(0, world.audioTracks.melody.size)
        assertEquals(0, world.audioTracks.rhythm.size)
        assertEquals(0, world.audioTracks.harmony.size)
        assertEquals(0, world.audioTracks.menu.size)
        assertEquals(0, world.audioTracks.start.size)
        assertEquals(0, world.audioTracks.nemesis.size)
        assertEquals(0, world.audioTracks.end.size)
    }

    @Test
    fun world_audioTracksRoundTripsThroughJson()
    {
        val original = World(
            name = "Atlas",
            storyScenario = "The world is at war.",
            audioTracks = AudioTracks(
                drone = mutableListOf(
                    AudioObject(
                        id = "d-1",
                        resourceName = "music.drone.pad",
                        channelId = "Music",
                        volume = 0.5f,
                        loop = true
                    )
                ),
                start = mutableListOf(
                    AudioObject(
                        id = "start-1",
                        resourceName = "music.start.theme",
                        channelId = "Music",
                        volume = 0.9f
                    )
                )
            )
        )

        val encoded = json.encodeToString(World.serializer(), original)
        assertTrue(
            encoded.contains("\"audioTracks\""),
            "World JSON must include the audioTracks field, got: $encoded"
        )

        val decoded = json.decodeFromString(World.serializer(), encoded)

        assertEquals(1, decoded.audioTracks.drone.size)
        assertEquals("music.drone.pad", decoded.audioTracks.drone[0].resourceName)
        assertEquals(0.5f, decoded.audioTracks.drone[0].volume)
        assertEquals(true, decoded.audioTracks.drone[0].loop)
        assertEquals(0, decoded.audioTracks.melody.size)
        assertEquals(0, decoded.audioTracks.rhythm.size)
        assertEquals(0, decoded.audioTracks.harmony.size)
        assertEquals(0, decoded.audioTracks.menu.size)
        assertEquals(1, decoded.audioTracks.start.size)
        assertEquals("music.start.theme", decoded.audioTracks.start[0].resourceName)
        assertEquals(0, decoded.audioTracks.nemesis.size)
        assertEquals(0, decoded.audioTracks.end.size)
    }

    @Test
    fun world_decodesJsonMissingScenarioFieldsAsEmptyLists()
    {
        // JSON written by the editor version that did NOT have the four
        // scenario fields yet (menu / start / nemesis / end). They should
        // decode to empty lists, NOT throw.
        val legacyJson = """
            {
              "name": "Legacy World",
              "storyScenario": "",
              "points": 0,
              "karmaPoints": 0,
              "conflictLevel": 0,
              "actOfGodPoints": 0,
              "roundNumber": 1,
              "mapTiles": [],
              "activePlayers": [],
              "npc": [],
              "turnOrder": [],
              "worldRules": [],
              "destroyedTerritories": [],
              "activeTurnActor": "",
              "audioTracks": {
                "drone": [],
                "melody": [],
                "rhythm": [],
                "harmony": [
                  {
                    "id": "h-1",
                    "resourceName": "music.harmony.legacy",
                    "channelId": "Music",
                    "volume": 0.7,
                    "panning": 0.0,
                    "speed": 1.0,
                    "loop": false,
                    "startTimeMs": 0,
                    "fadeInDurationMs": 0,
                    "fadeOutDurationMs": 0,
                    "loopWithTail": false
                  }
                ]
              }
            }
        """.trimIndent()

        val decoded = json.decodeFromString(World.serializer(), legacyJson)

        assertEquals(1, decoded.audioTracks.harmony.size)
        assertEquals("music.harmony.legacy", decoded.audioTracks.harmony[0].resourceName)
        assertEquals(0, decoded.audioTracks.menu.size)
        assertEquals(0, decoded.audioTracks.start.size)
        assertEquals(0, decoded.audioTracks.nemesis.size)
        assertEquals(0, decoded.audioTracks.end.size)
    }

    @Test
    fun world_decodesAllEightScenarioFields()
    {
        // Belt-and-braces: pin that every AudioTracks field is preserved
        // end-to-end so a refactor in structs.audio.AudioTracks that
        // renames a field breaks the test loudly.
        val payload = AudioTracks(
            drone = mutableListOf(sampleTrack("d", "music.drone.x")),
            melody = mutableListOf(sampleTrack("m", "music.melody.x")),
            rhythm = mutableListOf(sampleTrack("r", "music.rhythm.x")),
            harmony = mutableListOf(sampleTrack("h", "music.harmony.x")),
            menu = mutableListOf(sampleTrack("menu", "music.menu.x")),
            start = mutableListOf(sampleTrack("start", "music.start.x")),
            nemesis = mutableListOf(sampleTrack("nem", "music.nemesis.x")),
            end = mutableListOf(sampleTrack("end", "music.end.x"))
        )
        val original = World(name = "Full", audioTracks = payload)

        val encoded = json.encodeToString(World.serializer(), original)
        val decoded = json.decodeFromString(World.serializer(), encoded)

        assertEquals(1, decoded.audioTracks.drone.size)
        assertEquals(1, decoded.audioTracks.melody.size)
        assertEquals(1, decoded.audioTracks.rhythm.size)
        assertEquals(1, decoded.audioTracks.harmony.size)
        assertEquals(1, decoded.audioTracks.menu.size)
        assertEquals(1, decoded.audioTracks.start.size)
        assertEquals(1, decoded.audioTracks.nemesis.size)
        assertEquals(1, decoded.audioTracks.end.size)
    }

    @Test
    fun world_audioTracksFieldAppearsInRpcJsonOutput()
    {
        // The server's RpcJson codec (used by the auto-generated RPC
        // bindings) must emit the audioTracks field. This test pins the
        // server-visible wire format.
        val world = World(
            audioTracks = AudioTracks(
                menu = mutableListOf(
                    AudioObject(
                        id = "m-1",
                        resourceName = "music.menu.x",
                        channelId = "Music"
                    )
                )
            )
        )

        val element = RpcJson.encodeToJsonElement(World.serializer(), world)
        val text = element.toString()
        assertTrue(
            text.contains("\"audioTracks\""),
            "RpcJson-encoded World must expose audioTracks, got: $text"
        )
        assertTrue(
            text.contains("\"menu\""),
            "RpcJson-encoded World.audioTracks must expose the menu field, got: $text"
        )
    }

    private fun sampleTrack(id: String, resource: String): AudioObject
    {
        return AudioObject(
            id = id,
            resourceName = resource,
            channelId = "Music",
            volume = 1.0f
        )
    }
}
