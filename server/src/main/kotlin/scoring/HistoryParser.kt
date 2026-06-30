package scoring

import structs.GameHistory
import structs.ui.ActionHistory
import structs.ui.GameEventType
import structs.ui.JudgeEventMetadata
import structs.ui.NpcEventMetadata
import structs.ui.ResourceEventMetadata
import structs.ui.ScoringEventMetadata
import structs.ui.TerritoryEventMetadata

class HistoryParser
{
    fun parseHistoryForScoring(gameHistory: GameHistory): ScoringResult
    {
        val scoringResult = ScoringResult()
        gameHistory.actionHistory.forEach { event ->
            when (event.eventType)
            {
                GameEventType.TERRITORY      -> parseTerritoryScoring(event, scoringResult)
                GameEventType.JUDGE          -> parseJudgeScoring(event, scoringResult)
                GameEventType.NPC            -> parseNpcScoring(event, scoringResult)
                GameEventType.RESOURCE,
                GameEventType.RESOURCE_GRANT -> parseResourceScoring(event, scoringResult)
                GameEventType.SCORING_UPDATE -> parseExplicitScoring(event, scoringResult)
                else                         -> Unit
            }
        }
        calculateWorldScoreChanges(gameHistory, scoringResult)
        return scoringResult
    }

    private fun parseTerritoryScoring(event: ActionHistory, result: ScoringResult)
    {
        val metadata = event.metadata as? TerritoryEventMetadata ?: return
        val territoryName = metadata.territoryName
        val newOwner = metadata.newOwner
        val pointValue = metadata.pointValueChange
        if (territoryName.isBlank() || newOwner.isBlank()) return

        if (metadata.isDestroyed)
        {
            result.worldScoreReduction += pointValue
            result.addScoreChange(newOwner, "territory_destroyed", -pointValue)
        }
        else
        {
            result.addScoreChange(newOwner, "territory_captured", pointValue)
        }
    }

    private fun parseJudgeScoring(event: ActionHistory, result: ScoringResult)
    {
        val metadata = event.metadata as? JudgeEventMetadata ?: return
        val player = event.player
        if (player.isBlank()) return

        if (metadata.wasSuccessful)
        {
            result.addScoreChange(player, "successful_action", 1)
        }

        metadata.resourcesUsed.forEach { resource ->
            when (getResourceType(resource))
            {
                "Military"  -> result.addScoreChange(player, "military_points", 1)
                "Diplomacy" -> result.addScoreChange(player, "diplomacy_points", 1)
                "Research"  -> result.addScoreChange(player, "research_points", 1)
                else          -> result.addScoreChange(player, "general_resource", 1)
            }
        }
    }

    private fun parseNpcScoring(event: ActionHistory, result: ScoringResult)
    {
        val metadata = event.metadata as? NpcEventMetadata ?: return
        val player = event.player
        if (player.isBlank()) return

        when (metadata.npcEventType.lowercase())
        {
            "captured"      -> result.addScoreChange(player, "npc_captured", metadata.npcPointValue.coerceAtLeast(1))
            "betrayal"      -> result.addScoreChange(player, "npc_betrayal", -2)
            "death"         -> result.karmaPointChange += 1
            "nemesis_spawn" -> result.karmaPointChange += 5
            else             -> result.addScoreChange(player, "npc_event", 1)
        }
    }

    private fun parseResourceScoring(event: ActionHistory, result: ScoringResult)
    {
        val metadata = event.metadata as? ResourceEventMetadata ?: return
        val player = metadata.recipient.ifBlank { event.player }
        if (player.isBlank()) return

        if (metadata.isGranted)
        {
            result.addScoreChange(player, "resource_granted", metadata.grantAmount.takeIf { it > 0 } ?: 1)
        }
        if (metadata.isDestroyed)
        {
            result.addScoreChange(player, "resource_destroyed", -1)
        }
    }

    private fun parseExplicitScoring(event: ActionHistory, result: ScoringResult)
    {
        val metadata = event.metadata as? ScoringEventMetadata ?: return
        val player = event.player
        result.addScoreChange(player, metadata.pointType, metadata.pointChange)
        if (metadata.pointChange < 0)
        {
            result.worldScoreReduction += metadata.pointChange
        }
    }

    private fun calculateWorldScoreChanges(gameHistory: GameHistory, result: ScoringResult)
    {
        val totalPointsAwarded = result.playerScoreChanges.values.sumOf { playerChanges ->
            playerChanges.values.sum()
        }
        result.worldPointChange = totalPointsAwarded - result.worldScoreReduction
    }

    private fun getResourceType(resourceName: String): String
    {
        return when
        {
            resourceName.contains("war", ignoreCase = true) -> "Military"
            resourceName.contains("diplomacy", ignoreCase = true) -> "Diplomacy"
            resourceName.contains("research", ignoreCase = true) -> "Research"
            else -> "General"
        }
    }
}
