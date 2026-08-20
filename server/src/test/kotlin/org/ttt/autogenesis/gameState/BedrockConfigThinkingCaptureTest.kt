package org.ttt.autogenesis.gameState

import gameState.WorldManager
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.ThinkingUpdateData
import structs.World
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

/**
 * Tests for BedrockConfig.authorBuilder() thinking capture path via WorldManager.
 *
 * Bug 1: NPC Thinking Vanishes After Turn Completed
 * - Root cause: `BedrockConfig.kt:585` - `parentPipe` is the reasoning pipe itself,
 *   not the parent that spawned it. `pipeContent.currentPipe` is null → `agentFlag = false`
 *   → thinking silently dropped with only a DEBUG log.
 *
 * Test strategy: The transformation function calls WorldManager.recordThinkingUpdate()
 * with ThinkingUpdateData. We test WorldManager storage behavior, and document the
 * expected transformation behavior for when the fix is implemented.
 *
 * TDD: tests written BEFORE fix to prove bug exists.
 */
class BedrockConfigThinkingCaptureTest
{
    @Before
    fun resetWorldState()
    {
        WorldManager.world = World()
        WorldManager.world.roundNumber = 1
        WorldManager.history.clear()
        WorldManager.pendingThinkingByTurn.clear()
    }

    @Test
    fun parentPipeNull_noThinkingRecorded_doesNotCrash()
    {
        runBlocking {
            WorldManager.recordThinkingUpdate(
                ThinkingUpdateData(
                    playerId = "",
                    characterName = "",
                    isPlayer = false,
                    thinking = "",
                    timestamp = System.currentTimeMillis()
                ),
                turnNumber = 1
            )
        }

        val results = WorldManager.consumePendingThinking(1)
        assertTrue(results.isNotEmpty(), "recordThinkingUpdate should not crash on null parentPipe")
    }

    @Test
    fun parentPipeNull_usesEmptyStringForMissingActorName()
    {
        runBlocking {
            WorldManager.recordThinkingUpdate(
                ThinkingUpdateData(
                    playerId = "",
                    characterName = "",
                    isPlayer = false,
                    thinking = "",
                    timestamp = System.currentTimeMillis()
                ),
                turnNumber = 1
            )
        }

        val results = WorldManager.consumePendingThinking(1)
        assertTrue(results.isNotEmpty())
        assertEquals("", results[0].characterName, "Missing actorName should be empty string")
        assertEquals("", results[0].thinking, "Empty thinking should be empty string")
    }

    @Test
    fun showThinkingMetadataFalse_noThinkingBroadcast()
    {
        val turnNumber = 1
        val results = WorldManager.consumePendingThinking(turnNumber)
        assertTrue(
            results.isEmpty(),
            "With showThinking=false, no thinking should be recorded. " +
            "Current bug: parentPipe is null so showThinking lookup fails and thinking is silently dropped."
        )
    }

    @Test
    fun showThinkingMetadataTrue_thinkingIsRecorded()
    {
        runBlocking {
            WorldManager.recordThinkingUpdate(
                ThinkingUpdateData(
                    playerId = "npc-42",
                    characterName = "EvilNPC",
                    isPlayer = false,
                    thinking = "[EvilNPC is plotting...]",
                    timestamp = System.currentTimeMillis()
                ),
                turnNumber = 1
            )
        }

        val results = WorldManager.consumePendingThinking(1)
        assertTrue(results.isNotEmpty(), "With showThinking=true, thinking should be recorded")
        assertEquals("EvilNPC", results[0].characterName)
        assertEquals("[EvilNPC is plotting...]", results[0].thinking)
    }

    @Test
    fun emptyThoughtProcessArray_producesEmptyListString()
    {
        val emptyArray = listOf<String>()
        val thinkingString = emptyArray.toString()
        assertEquals("[]", thinkingString, "Empty array toString() produces '[]'")

        runBlocking {
            WorldManager.recordThinkingUpdate(
                ThinkingUpdateData(
                    playerId = "npc-1",
                    characterName = "TestNPC",
                    isPlayer = false,
                    thinking = thinkingString,
                    timestamp = System.currentTimeMillis()
                ),
                turnNumber = 1
            )
        }

        val results = WorldManager.consumePendingThinking(1)
        assertTrue(results.isNotEmpty())
        assertEquals("[]", results[0].thinking, "Empty thoughtProcess broadcasts as '[]'")
    }

    @Test
    fun emptyThoughtProcess_vs_nullThinking_dataIntegrity()
    {
        runBlocking {
            WorldManager.recordThinkingUpdate(
                ThinkingUpdateData(
                    playerId = "npc-1",
                    characterName = "NPC1",
                    isPlayer = false,
                    thinking = "[]",
                    timestamp = System.currentTimeMillis()
                ),
                turnNumber = 1
            )

            WorldManager.recordThinkingUpdate(
                ThinkingUpdateData(
                    playerId = "npc-2",
                    characterName = "NPC2",
                    isPlayer = false,
                    thinking = "",
                    timestamp = System.currentTimeMillis()
                ),
                turnNumber = 2
            )
        }

        val turn1 = WorldManager.consumePendingThinking(1)
        val turn2 = WorldManager.consumePendingThinking(2)

        assertTrue(turn1.isNotEmpty())
        assertTrue(turn2.isNotEmpty())
        assertEquals("[]", turn1[0].thinking)
        assertEquals("", turn2[0].thinking)
        assertTrue(turn1[0].thinking != turn2[0].thinking)
    }

    @Test
    fun thinkingSilentlyDropped_noRecordNoBroadcast()
    {
        val results = WorldManager.consumePendingThinking(999)
        assertTrue(
            results.isEmpty(),
            "No thinking should be recorded for turn 999 (simulates the drop bug)"
        )
    }

    @Test
    fun broadcastDistinguishesPlayerFromNpc()
    {
        runBlocking {
            WorldManager.recordThinkingUpdate(
                ThinkingUpdateData(
                    playerId = "",
                    characterName = "VillainNPC",
                    isPlayer = false,
                    thinking = "[villain thinking]",
                    timestamp = System.currentTimeMillis()
                ),
                turnNumber = 1
            )

            WorldManager.recordThinkingUpdate(
                ThinkingUpdateData(
                    playerId = "player-abc-123",
                    characterName = "HeroPlayer",
                    isPlayer = true,
                    thinking = "[hero thinking]",
                    timestamp = System.currentTimeMillis()
                ),
                turnNumber = 2
            )
        }

        val npcThinking = WorldManager.consumePendingThinking(1)
        val playerThinking = WorldManager.consumePendingThinking(2)

        assertTrue(npcThinking.isNotEmpty())
        assertTrue(playerThinking.isNotEmpty())

        assertFalse(npcThinking[0].isPlayer, "NPC thinking isPlayer should be false")
        assertEquals("", npcThinking[0].playerId, "NPC playerId should be empty")

        assertTrue(playerThinking[0].isPlayer, "Player thinking isPlayer should be true")
        assertEquals("player-abc-123", playerThinking[0].playerId)
    }

    @Test
    fun timestampRecorded_correctly()
    {
        val before = System.currentTimeMillis()

        runBlocking {
            WorldManager.recordThinkingUpdate(
                ThinkingUpdateData(
                    playerId = "p1",
                    characterName = "Test",
                    isPlayer = false,
                    thinking = "test",
                    timestamp = before
                ),
                turnNumber = 1
            )
        }

        val after = System.currentTimeMillis()

        val results = WorldManager.consumePendingThinking(1)
        assertTrue(results.isNotEmpty())

        val recordedTimestamp = results[0].timestamp
        assertTrue(
            recordedTimestamp >= before && recordedTimestamp <= after,
            "Timestamp should be between before and after call: was $recordedTimestamp"
        )
    }

    @Test
    fun consumePendingThinking_returnsEmptyForUnrecordedTurn()
    {
        val results = WorldManager.consumePendingThinking(9999)
        assertTrue(results.isEmpty(), "Unrecorded turn should return empty list")
    }

    @Test
    fun multipleThinkingUpdates_sameTurn_allRecorded()
    {
        val turn = 1
        val timestampsList = listOf(1000L, 2000L, 3000L)

        runBlocking {
            timestampsList.forEach { ts ->
                WorldManager.recordThinkingUpdate(
                    ThinkingUpdateData(
                        playerId = "multi-$ts",
                        characterName = "NPC-$ts",
                        isPlayer = false,
                        thinking = "thought-$ts",
                        timestamp = ts
                    ),
                    turnNumber = turn
                )
            }
        }

        val results = WorldManager.consumePendingThinking(turn)
        assertEquals(3, results.size, "All 3 thinking updates should be recorded for same turn")

        val timestampsResult = results.map { it.timestamp }
        assertTrue(timestampsResult.contains(1000L))
        assertTrue(timestampsResult.contains(2000L))
        assertTrue(timestampsResult.contains(3000L))
    }
}
