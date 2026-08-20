package agent.builders.judgeOutcome

import agent.builders.validateAction.ActionTargetType
import agent.builders.validateAction.ActionTargetTypeObj
import gameState.WorldManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import structs.GameHistory
import structs.Npc
import structs.Territory
import structs.World

class NpcJudgeNormalizationTest
{
    @BeforeTest
    fun resetWorld()
    {
        WorldManager.world = World()
        WorldManager.history.clear()
        WorldManager.playerStats.clear()
    }

    @Test
    fun normalizeNpcJudgeResults_movesInvalidNpcLossesToExchange()
    {
        val npc = Npc(name = "Overlord")
        val owned = Territory(name = "Citadel", ruler = npc.name, isCaptured = true)
        val foreign = Territory(name = "Delta", ruler = "Commander Shepard", isCaptured = true)
        npc.capturedTerritory.add(owned)
        WorldManager.world.npc.add(npc)
        WorldManager.world.mapTiles.addAll(listOf(owned, foreign))

        val input = NpcJudgeInput(
            userPrompt = "Attack Delta",
            npcData = npc,
            previousTurn = GameHistory(turnPlayer = npc.name),
            knownNPCs = emptyList(),
            victoryStatus = `Victory?`(false)
        )

        val normalized = normalizeNpcJudgeResults(
            results = Results(
                territoryLost = mutableListOf("Delta")
            ),
            npcName = npc.name,
            input = input
        )

        assertTrue(normalized.territoryLost.isEmpty(), "NPC cannot lose territory it never owned")
        assertTrue(
            normalized.territoryExchanges.any { it.territoryName == "Delta" && it.from == "Commander Shepard" && it.to.isBlank() },
            "Invalid loss should be converted to contested exchange using current ruler as source"
        )
    }

    @Test
    fun normalizeNpcJudgeResults_allowsTerritoryEffectsForPlayerTargetPerSupremeRule()
    {
        val npc = Npc(name = "Overlord")
        val controlled = Territory(name = "Outpost", ruler = npc.name, isCaptured = true)
        npc.capturedTerritory.add(controlled)
        WorldManager.world.npc.add(npc)
        WorldManager.world.mapTiles.add(controlled)

        val input = NpcJudgeInput(
            userPrompt = "Sabotage the player",
            npcData = npc,
            previousTurn = null,
            knownNPCs = emptyList(),
            victoryStatus = `Victory?`(true),
            targetData = ActionTargetTypeObj(
                type = ActionTargetType.Player,
                targets = listOf("Commander Shepard")
            )
        )

        val normalized = normalizeNpcJudgeResults(
            results = Results(
                territoryGained = mutableListOf("Outpost"),
                territoryLost = mutableListOf("Outpost"),
                territoryExchanges = mutableListOf(TerritoryExchange("Outpost", npc.name, "Commander Shepard")),
                territoriesDeposed = mutableListOf("Outpost")
            ),
            npcName = npc.name,
            input = input
        )

        // Per Supreme Rule (Narrative Override), these should now be preserved if present in results
        assertEquals(listOf("Outpost"), normalized.territoryGained)
        assertEquals(listOf("Outpost"), normalized.territoryLost)
        assertEquals(1, normalized.territoryExchanges.size)
        assertEquals(listOf("Outpost"), normalized.territoriesDeposed)
    }

    @Test
    fun normalizeNpcJudgeResults_filtersUnknownTerritoriesAndBackfillsSummary()
    {
        val npc = Npc(name = "Overlord")
        val known = Territory(name = "KnownTile", ruler = "", isCaptured = false)
        WorldManager.world.npc.add(npc)
        WorldManager.world.mapTiles.add(known)

        val normalized = normalizeNpcJudgeResults(
            results = Results(
                resultSummary = "",
                territoryGained = mutableListOf("KnownTile", "GhostTile"),
                territoryExchanges = mutableListOf(
                    TerritoryExchange("KnownTile", "", npc.name),
                    TerritoryExchange("GhostTile", "", npc.name)
                )
            ),
            npcName = npc.name,
            input = NpcJudgeInput(
                userPrompt = "Capture ground",
                npcData = npc,
                previousTurn = null,
                knownNPCs = emptyList(),
                victoryStatus = `Victory?`(true)
            )
        )

        assertEquals(listOf("KnownTile"), normalized.territoryGained)
        assertEquals(1, normalized.territoryExchanges.size)
        assertFalse(normalized.resultSummary.isBlank(), "Normalizer should backfill an empty summary")
    }
}