package org.ttt.autogenesis.gameState

import gameState.WorldManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.server.audio.AudioTracksResourceLoader
import structs.audio.AudioTracks
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration tests for the [WorldManager.loadAudioTracksFromBytes] /
 * [WorldManager.loadAudioTracksFromResource] entry points.
 *
 * These tests pin the contract [GameInit] relies on when wiring the
 * audio-tracks JSON file into the game world:
 *
 *  1. Loading a valid payload replaces `world.audioTracks` in place.
 *  2. Loading an invalid payload is a no-op (the world keeps its
 *     previous audioTracks state) AND surfaces a clear error to the
 *     caller, so GameInit can decide whether to log a warning or
 *     rethrow.
 *  3. Loading a missing resource is also a no-op + error.
 *  4. A subsequent call replaces the previous payload (idempotent in
 *     the sense that the last call wins, not that the same payload is
 *     always returned).
 */
class WorldManagerAudioTracksTest
{
    @BeforeTest
    fun resetWorldState()
    {
        WorldManager.world = structs.World()
        WorldManager.history.clear()
    }

    @AfterTest
    fun cleanupWorldState()
    {
        WorldManager.world = structs.World()
    }

    @Test
    fun loadAudioTracksFromBytes_validPayload_populatesWorldAudioTracks() = runBlocking {
        val json = """
            {
              "drone":  [ { "id": "d", "resourceName": "music.drone.x", "channelId": "Music" } ],
              "melody": [],
              "rhythm": [],
              "harmony":[]
            }
        """.trimIndent()

        WorldManager.loadAudioTracksFromBytes(json.toByteArray(Charsets.UTF_8))

        assertEquals(1, WorldManager.world.audioTracks.drone.size)
        assertEquals("music.drone.x", WorldManager.world.audioTracks.drone[0].resourceName)
    }

    @Test
    fun loadAudioTracksFromBytes_replacesExistingPayload() = runBlocking {
        // First call installs payload A.
        WorldManager.loadAudioTracksFromBytes(
            """
            {
              "drone": [ { "id": "a", "resourceName": "music.drone.A", "channelId": "Music" } ],
              "melody": [], "rhythm": [], "harmony": []
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        )
        assertEquals("music.drone.A", WorldManager.world.audioTracks.drone[0].resourceName)

        // Second call replaces it with payload B.
        WorldManager.loadAudioTracksFromBytes(
            """
            {
              "drone": [ { "id": "b", "resourceName": "music.drone.B", "channelId": "Music" } ],
              "melody": [], "rhythm": [], "harmony": []
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        )
        assertEquals(1, WorldManager.world.audioTracks.drone.size)
        assertEquals("music.drone.B", WorldManager.world.audioTracks.drone[0].resourceName)
    }

    @Test
    fun loadAudioTracksFromBytes_invalidPayload_throwsAndKeepsPreviousState() = runBlocking {
        // Seed the world with a known-good payload so we can verify it
        // survives a failed second call.
        WorldManager.loadAudioTracksFromBytes(
            """
            {
              "drone": [ { "id": "kept", "resourceName": "music.drone.keep", "channelId": "Music" } ],
              "melody": [], "rhythm": [], "harmony": []
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        )
        val beforeFailure = WorldManager.world.audioTracks.drone.size
        assertEquals(1, beforeFailure)

        // Malformed JSON. Should throw, but the previous payload must
        // still be on the world.
        assertFailsWith<SerializationException> {
            WorldManager.loadAudioTracksFromBytes("not json".toByteArray(Charsets.UTF_8))
        }
        assertEquals(
            beforeFailure,
            WorldManager.world.audioTracks.drone.size,
            "Failed load must not erase the previous audioTracks payload"
        )
        assertEquals(
            "music.drone.keep",
            WorldManager.world.audioTracks.drone[0].resourceName
        )
    }

    @Test
    fun loadAudioTracksFromResource_bundledAudioTracksFile_loadsSuccessfully() = runBlocking {
        // The packaged audio-tracks.json resource should be reachable
        // and decode cleanly when the WorldManager delegates to the
        // resource loader.
        WorldManager.loadAudioTracksFromResource("audio/audio-tracks.json")

        // The bundled file populates at least one scenario tab.
        val total = WorldManager.world.audioTracks.menu.size +
            WorldManager.world.audioTracks.start.size +
            WorldManager.world.audioTracks.nemesis.size +
            WorldManager.world.audioTracks.end.size
        assertTrue(
            total >= 1,
            "Bundled audio-tracks.json should populate at least one scenario tab, got total=$total"
        )
    }

    @Test
    fun loadAudioTracksFromResource_missingPath_throwsAndLeavesWorldUnchanged() = runBlocking {
        // Seed the world with a known payload so we can verify the
        // error path doesn't clobber it.
        WorldManager.loadAudioTracksFromBytes(
            """
            {
              "drone": [ { "id": "kept", "resourceName": "music.drone.keep", "channelId": "Music" } ],
              "melody": [], "rhythm": [], "harmony": []
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        )
        val beforeFailure = WorldManager.world.audioTracks.drone.size

        assertFailsWith<IllegalArgumentException> {
            WorldManager.loadAudioTracksFromResource("audio/does-not-exist.json")
        }

        assertEquals(
            beforeFailure,
            WorldManager.world.audioTracks.drone.size,
            "Failed resource load must not erase the previous audioTracks payload"
        )
    }

    @Test
    fun loadAudioTracksFromBytes_preservesAllEightFields() = runBlocking {
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

        WorldManager.loadAudioTracksFromBytes(json.toByteArray(Charsets.UTF_8))

        val tracks = WorldManager.world.audioTracks
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
    fun loadAudioTracksFromBytes_doesNotMutateMapTilesOrPlayers() = runBlocking {
        // Seed the world with map tiles and players to confirm the
        // audio-tracks loader is a single-field mutation.
        val originalWorld = WorldManager.world
        originalWorld.mapTiles.add(structs.Territory(name = "A"))
        originalWorld.activePlayers.add(structs.Player(name = "Alpha"))
        val mapTilesBefore = originalWorld.mapTiles.toList()
        val playersBefore = originalWorld.activePlayers.toList()

        WorldManager.loadAudioTracksFromBytes(
            """
            {
              "drone": [ { "id": "d", "resourceName": "music.drone.x", "channelId": "Music" } ],
              "melody": [], "rhythm": [], "harmony": []
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        )

        assertEquals(mapTilesBefore, WorldManager.world.mapTiles.toList())
        assertEquals(playersBefore, WorldManager.world.activePlayers.toList())
    }
}
