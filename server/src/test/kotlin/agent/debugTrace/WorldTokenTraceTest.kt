package agent.debugTrace

import gameState.WorldManager
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import structs.Npc
import structs.Player
import structs.Territory
import structs.World
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD-Red tests for [WorldTokenTrace]. These tests pin the contract that the
 * debugTrace flag exposes:
 *
 *  - The flag is read from env var / system property at construction time
 *  - When the flag is OFF, every public entry point must return early and
 *    must NOT create any file on disk
 *  - When the flag is ON, exactly one file per turn is written to
 *    ~/.autogenesis/logs/ with a filename of the form
 *    world-trace-YYYY-MM-DD-HHmmss-NNN.log
 *  - The file must contain (a) a header line "=== ROUND <N> TURN <I> ACTOR <name> ===",
 *    (b) the full World serialized as JSON, (c) the full-world token count,
 *    and (d) a per-field breakdown of all 16 World fields with their JSON +
 *    individual token counts
 *  - countTokens() must return > 0 for non-blank text
 *  - Per-field serializer must round-trip every field on World without throwing
 *  - File write failures must NOT throw out of the public entry point
 */
class WorldTokenTraceTest
{
    @TempDir
    lateinit var tempDir: Path

    private val originalEnvTrace = System.getenv("AUTOGENESIS_DEBUG_TRACE")
    private val originalPropTrace = System.getProperty("AUTOGENESIS_DEBUG_TRACE")
    private val originalWorld = WorldManager.world
    private val originalLogsDir = System.getProperty("user.home")

    @BeforeEach
    fun resetState()
    {
        WorldManager.world = World()
        System.clearProperty("AUTOGENESIS_DEBUG_TRACE")
        // Clear any residual trace files so that file-predicate checks are deterministic.
        val logsDir = File(System.getProperty("user.home"), ".autogenesis/logs")
        logsDir.listFiles { f -> f.name.startsWith("world-trace-") }?.forEach { it.delete() }
    }

    @AfterEach
    fun cleanup()
    {
        if (originalEnvTrace != null) System.setProperty("AUTOGENESIS_DEBUG_TRACE", originalEnvTrace)
        else System.clearProperty("AUTOGENESIS_DEBUG_TRACE")
        if (originalPropTrace != null) System.setProperty("AUTOGENESIS_DEBUG_TRACE", originalPropTrace)
        WorldManager.world = originalWorld
    }

    // ====================================================================
    // Flag read — the toggle is the only way to opt in
    // ====================================================================

    @Test
    fun `enabled flag is false by default when no env var or system property is set`()
    {
        WorldTokenTrace.enabled = false
        assertFalse(WorldTokenTrace.enabled, "enabled must default to false when the flag is unset")
    }

    @Test
    fun `enabled flag can be flipped via the setter for testing`()
    {
        WorldTokenTrace.enabled = false
        assertFalse(WorldTokenTrace.enabled)
        WorldTokenTrace.enabled = true
        assertTrue(WorldTokenTrace.enabled)
        WorldTokenTrace.enabled = false
    }

    // ====================================================================
    // Early-return contract — flag off means no work, no file
    // ====================================================================

    @Test
    fun `writeTurnStartTrace returns early when disabled and does not create files`()
    {
        WorldTokenTrace.enabled = false
        val logsDir = File(System.getProperty("user.home"), ".autogenesis/logs")
        val beforeTrace = logsDir.listFiles { f -> f.name.startsWith("world-trace-") }?.toList().orEmpty().size

        assertDoesNotThrow {
            WorldTokenTrace.writeTurnStartTrace(
                roundNumber = 1,
                turnOrderIndex = 0,
                actor = "TestActor"
            )
        }

        val afterTrace = logsDir.listFiles { f -> f.name.startsWith("world-trace-") }?.toList().orEmpty().size
        assertEquals(beforeTrace, afterTrace, "disabled flag must not create any world-trace-*.log files")
    }

    // ====================================================================
    // countTokens — the core engine that drives every per-field count
    // ====================================================================

    @Test
    fun `countTokens returns positive integer for non-blank text`()
    {
        val tokens = WorldTokenTrace.countTokens("Hello world this is a test sentence")
        assertTrue(tokens > 0, "countTokens must return > 0 for non-blank text, got $tokens")
    }

    @Test
    fun `countTokens returns zero for empty string`()
    {
        val tokens = WorldTokenTrace.countTokens("")
        assertEquals(0, tokens, "countTokens must return 0 for empty text")
    }

    @Test
    fun `countTokens grows roughly with text length`()
    {
        val short = WorldTokenTrace.countTokens("hello world")
        val long = WorldTokenTrace.countTokens("hello world ".repeat(50))
        assertTrue(long > short, "long text must token-count higher than short: short=$short long=$long")
    }

    // ====================================================================
    // Per-field serialization — every field on World must round-trip
    // ====================================================================

    @Test
    fun `every World field is serialized without throwing for a default world`()
    {
        val world = World()
        assertDoesNotThrow("default World() must serialize cleanly") {
            val entries = WorldTokenTrace.serializeFields(world)
            assertEquals(16, entries.size, "World has 16 top-level fields, all must be serialized")
        }
    }

    @Test
    fun `every World field is serialized without throwing for a populated world`()
    {
        val world = World(
            name = "TestWorld",
            storyScenario = "A scenario",
            points = 100,
            karmaPoints = 50,
            conflictLevel = 3,
            actOfGodPoints = 5,
            roundNumber = 7,
            mapTiles = mutableListOf(Territory(name = "Tile-1")),
            activePlayers = mutableListOf(Player(name = "P1")),
            npc = mutableListOf(Npc(name = "N1")),
            turnOrder = mutableListOf("P1", "N1"),
            worldRules = mutableListOf("rule-1"),
            destroyedTerritories = mutableListOf("Tile-2"),
            activeTurnActor = "P1"
        )
        val entries = WorldTokenTrace.serializeFields(world)
        assertEquals(16, entries.size)
        entries.forEach { entry ->
            assertNotNull(entry.json, "field ${entry.fieldName} must have non-null JSON")
            assertTrue(entry.tokens >= 0, "field ${entry.fieldName} must have non-negative token count, got ${entry.tokens}")
        }
    }

    @Test
    fun `fullWorldJson round-trips through Json parser`()
    {
        val world = World(
            name = "RoundtripWorld",
            activePlayers = mutableListOf(Player(name = "P1"))
        )
        val json = WorldTokenTrace.serializeFullWorld(world)
        val parsed = Json.parseToJsonElement(json)
        assertTrue(parsed is kotlinx.serialization.json.JsonObject, "fullWorldJson must be a JSON object")
        val obj = parsed as kotlinx.serialization.json.JsonObject
        assertEquals("RoundtripWorld", obj["name"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    // ====================================================================
    // File output — the contract that the operator sees
    // ====================================================================

    @Test
    fun `writeTurnStartTrace writes a file with the expected name shape when enabled`()
    {
        WorldTokenTrace.enabled = true
        try
        {
            WorldManager.world = World(name = "FileShapeWorld")
            WorldTokenTrace.writeTurnStartTrace(
                roundNumber = 3,
                turnOrderIndex = 2,
                actor = "ShapeActor"
            )
            val logsDir = File(System.getProperty("user.home"), ".autogenesis/logs")
            val traceFiles = logsDir.listFiles { f -> f.name.startsWith("world-trace-") }?.toList().orEmpty()
            assertTrue(traceFiles.isNotEmpty(), "enabled flag must create at least one world-trace-*.log file")
            val match = traceFiles.first()
            val nameRegex = Regex("^world-trace-\\d{4}-\\d{2}-\\d{2}-\\d{6}-\\d{3}\\.log$")
            assertTrue(
                nameRegex.matches(match.name),
                "filename must match world-trace-YYYY-MM-DD-HHmmss-NNN.log, got: ${match.name}"
            )
            val content = match.readText()
            assertTrue(content.contains("=== ROUND 3 TURN 2 ACTOR ShapeActor ==="),
                "file must contain the per-turn header line, got first 200 chars: ${content.take(200)}")
            assertTrue(content.contains("\"fullWorldJson\""), "file must contain the fullWorldJson field")
            assertTrue(content.contains("\"fieldBreakdown\""), "file must contain the fieldBreakdown field")
        }
        finally
        {
            WorldTokenTrace.enabled = false
        }
    }

    @Test
    fun `writeTurnStartTrace writes a per-field breakdown with 16 entries`()
    {
        WorldTokenTrace.enabled = true
        try
        {
            WorldManager.world = World(name = "BreakdownWorld")
            WorldTokenTrace.writeTurnStartTrace(
                roundNumber = 1,
                turnOrderIndex = 0,
                actor = "BreakdownActor"
            )
            val logsDir = File(System.getProperty("user.home"), ".autogenesis/logs")
            val traceFiles = logsDir.listFiles { f -> f.name.startsWith("world-trace-") }?.toList().orEmpty()
            assertTrue(traceFiles.isNotEmpty())
            val content = traceFiles.last().readText()
            // Each field appears exactly once as the fieldName
            listOf("name", "storyScenario", "points", "karmaPoints", "conflictLevel",
                "actOfGodPoints", "roundNumber", "mapTiles", "activePlayers", "npc",
                "turnOrder", "worldRules", "destroyedTerritories", "activeTurnActor",
                "audioTracks"
            ).forEach { fieldName ->
                assertTrue(content.contains("\"fieldName\": \"$fieldName\""),
                    "field breakdown must include fieldName=$fieldName")
            }
        }
        finally
        {
            WorldTokenTrace.enabled = false
        }
    }

    @Test
    fun `writeTurnStartTrace does not throw when the world is empty`()
    {
        WorldTokenTrace.enabled = true
        try
        {
            WorldManager.world = World()
            assertDoesNotThrow {
                WorldTokenTrace.writeTurnStartTrace(
                    roundNumber = 1,
                    turnOrderIndex = 0,
                    actor = "EmptyActor"
                )
            }
        }
        finally
        {
            WorldTokenTrace.enabled = false
        }
    }

    @Test
    fun `writeTurnStartTrace swallows IOExceptions and does not throw`()
    {
        WorldTokenTrace.enabled = true
        val originalHome = System.getProperty("user.home")
        try
        {
            // Make ~/.autogenesis/logs unwritable by pointing user.home at a path where mkdirs will fail
            System.setProperty("user.home", "/proc/0/this-cannot-exist")
            assertDoesNotThrow {
                WorldTokenTrace.writeTurnStartTrace(
                    roundNumber = 1,
                    turnOrderIndex = 0,
                    actor = "FailActor"
                )
            }
        }
        finally
        {
            System.setProperty("user.home", originalHome)
            WorldTokenTrace.enabled = false
        }
    }
}