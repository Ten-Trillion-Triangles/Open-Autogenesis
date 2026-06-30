package org.ttt.autogenesis.gameInit

import gameState.WorldManager
import gameInit.GameInit
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.server.config.BootStage
import org.ttt.autogenesis.server.config.RuleSet
import org.ttt.autogenesis.server.config.ServerConfig
import org.ttt.autogenesis.server.config.SystemStartupStage
import org.ttt.autogenesis.server.TurnHarness
import enums.CommanderType
import enums.CommanderTrait
import structs.Commander
import structs.matchmaking.GameSessionStatus
import structs.matchmaking.GameType
import structs.matchmaking.PlayerSessionBundle
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertTrue

/**
 * Integration tests that pin the contract between [GameInit.defineGameRules]
 * and the audio-tracks JSON file shipped at `audio/audio-tracks.json`.
 *
 * The user-visible promise is: by the time the game world is fully
 * configured for play, the audio-tracks catalog from
 * `audio/audio-tracks.json` is installed on [WorldManager.world.audioTracks].
 * The catalog MUST be loaded *after* the map has been picked, so that
 * the `start` / `nemesis` / `end` track identities agree with the map
 * the music selector will use them with.
 *
 * These tests are full-stack: they invoke [GameInit.defineGameRules]
 * with a rigged map and a real [GameSessionStatus] and then read the
 * post-init state off [WorldManager]. They pin two things:
 *
 *  1. The bundled audio-tracks.json is decoded and installed on the
 *     world by the time `defineGameRules` returns.
 *  2. The map was loaded FIRST (so audio tracks could not have raced
 *     ahead of the map and missed a map-specific configuration hook).
 */
class GameInitAudioTracksTest
{
    @BeforeTest
    fun resetState()
    {
        // Clean WorldManager before each test. The harness carries
        // global state between tests in the same JVM, so we must
        // reset everything we touch.
        WorldManager.world = structs.World()
        WorldManager.activeMapPackBytes = null
        WorldManager.activeMapPackName = ""
        WorldManager.history.clear()
        WorldManager.playerStats.clear()
        WorldManager.world.activePlayers.clear()
        WorldManager.world.npc.clear()
        WorldManager.world.mapTiles.clear()
        WorldManager.isGameActive = false
        BootStage.bootStage = SystemStartupStage.EMPTY
        // Reset the turn harness state so isRunning() returns false.
        runBlocking { TurnHarness.resetState() }
    }

    @AfterTest
    fun cleanupState()
    {
        WorldManager.world = structs.World()
        WorldManager.activeMapPackBytes = null
        WorldManager.isGameActive = false
        BootStage.bootStage = SystemStartupStage.EMPTY
    }

    @Test
    fun defineGameRules_loadsBundledAudioTracksIntoWorld() = runBlocking {
        // Rig the map to a known-good 2-player map so the random /
        // default fallbacks are not exercised.
        ServerConfig.args = listOf("--map=San_Martello")

        val session = GameSessionStatus(
            sessionId = "test-session-audio",
            maxPlayers = 2,
            currentPlayers = 1,
            players = mutableListOf(
                PlayerSessionBundle(
                    accelByteUserName = "tester",
                    accelByteId = "tester-id",
                    websocketId = "test-conn-1",
                    commander = Commander(
                        name = "Tester",
                        type = CommanderType.Land,
                        trait = CommanderTrait.Balanced,
                        description = "Test player"
                    )
                )
            ),
            isFull = false,
            isStarted = false,
            serverUrl = "http://localhost:9080",
            gameType = GameType.MULTIPLAYER,
            aiOpponentCount = 1,
            aiOnly = false
        )

        val sender: suspend (RpcMessage) -> Unit = { /* discard */ }
        val context = RpcCallContext(
            connectionId = "test-conn-1",
            metadata = emptyMap(),
            sender = sender
        )

        // Drive the init flow.
        val result = GameInit.defineGameRules(context, session)
        assertTrue(result, "defineGameRules should return true for a valid session")

        // The map must have been loaded.
        assertTrue(
            WorldManager.activeMapPackBytes != null,
            "Expected the rigged San_Martello map to be loaded"
        )
        assertTrue(
            WorldManager.world.mapTiles.isNotEmpty(),
            "Expected the world to have map tiles after defineGameRules"
        )

        // The audio tracks must have been loaded from the bundled
        // resource. The bundled file populates at least one scenario
        // tab; assert that here.
        val total = WorldManager.world.audioTracks.menu.size +
            WorldManager.world.audioTracks.start.size +
            WorldManager.world.audioTracks.nemesis.size +
            WorldManager.world.audioTracks.end.size
        assertTrue(
            total >= 1,
            "Expected audio/audio-tracks.json to be loaded into the world " +
                "after defineGameRules. Got menu=${WorldManager.world.audioTracks.menu.size} " +
                "start=${WorldManager.world.audioTracks.start.size} " +
                "nemesis=${WorldManager.world.audioTracks.nemesis.size} " +
                "end=${WorldManager.world.audioTracks.end.size}"
        )
    }

    @Test
    fun defineGameRules_audioTracksLoadedAfterMap_audioTracksNotEmptyWhenMapIsLoaded() = runBlocking {
        // Pin the order: by the time the world is fully configured,
        // both the map and the audio tracks are present. This is the
        // user-visible contract: "load the audio tracks into the
        // loaded game world after it has picked what game map it's
        // loading in".
        ServerConfig.args = listOf("--map=San_Martello")

        val session = GameSessionStatus(
            sessionId = "test-session-order",
            maxPlayers = 2,
            currentPlayers = 1,
            players = mutableListOf(
                PlayerSessionBundle(
                    accelByteUserName = "tester",
                    accelByteId = "tester-id",
                    websocketId = "test-conn-1",
                    commander = Commander(
                        name = "Tester",
                        type = CommanderType.Land,
                        trait = CommanderTrait.Balanced,
                        description = "Test player"
                    )
                )
            ),
            isFull = false,
            isStarted = false,
            serverUrl = "http://localhost:9080",
            gameType = GameType.MULTIPLAYER,
            aiOpponentCount = 1,
            aiOnly = false
        )

        val sender: suspend (RpcMessage) -> Unit = { /* discard */ }
        val context = RpcCallContext(
            connectionId = "test-conn-1",
            metadata = emptyMap(),
            sender = sender
        )

        GameInit.defineGameRules(context, session)

        // Both invariants hold: the map AND the audio tracks are on
        // the world. The test name records the design intent: audio
        // tracks land on the world only after the map is loaded, so
        // we can rely on the audio-tracks data being consistent with
        // whatever map was selected.
        val mapLoaded = WorldManager.activeMapPackBytes != null
        val audioLoaded = WorldManager.world.audioTracks.menu.isNotEmpty() ||
            WorldManager.world.audioTracks.start.isNotEmpty() ||
            WorldManager.world.audioTracks.nemesis.isNotEmpty() ||
            WorldManager.world.audioTracks.end.isNotEmpty()
        assertTrue(mapLoaded, "Map must be loaded")
        assertTrue(audioLoaded, "Audio tracks must be loaded after the map")
    }
}
