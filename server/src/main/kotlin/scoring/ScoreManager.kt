package scoring

import gameState.WorldManager
import kotlinx.coroutines.sync.withLock
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.Player
import structs.ui.ActionHistory
import structs.ui.ActionHistoryEvent
import structs.ui.GameEventType
import structs.ui.NpcEventMetadata
import kotlin.math.max

object ScoreManager
{
    private const val NEMESIS_KARMA_THRESHOLD = 100

    suspend fun applyScoring(scoringResult: ScoringResult)
    {
        var nemesisRequested = false
        WorldManager.worldMutex.withLock {
            scoringResult.playerScoreChanges.forEach { (playerName, changes) ->
                WorldManager.world.activePlayers.firstOrNull { it.name == playerName }?.let { player ->
                    applyPlayerScoreChanges(player, changes)
                }
            }

            WorldManager.world.points += scoringResult.worldPointChange
            WorldManager.world.karmaPoints += scoringResult.karmaPointChange

            if (scoringResult.worldScoreReduction > 0)
            {
                reduceWorldMaxScore(scoringResult.worldScoreReduction)
            }

            if (WorldManager.world.karmaPoints >= NEMESIS_KARMA_THRESHOLD)
            {
                WorldManager.world.karmaPoints = 0
                nemesisRequested = true
            }
        }

        if (nemesisRequested)
        {
            triggerNemesisSpawn()
        }
    }

    private fun applyPlayerScoreChanges(player: Player, changes: Map<String, Int>)
    {
        changes.forEach { (reason, points) ->
            when (reason)
            {
                "successful_action", "territory_captured", "npc_captured" ->
                    player.victoryPoints += points
                "military_points" -> player.militaryPoints += points
                "diplomacy_points" -> player.diplomacyPoints += points
                "research_points" -> player.researchPoints += points
                "npc_betrayal", "territory_destroyed" ->
                    player.victoryPoints = max(0, player.victoryPoints + points)
                else -> Logger.debug(LogCategory.GENERAL, "Unhandled scoring reason='$reason' for player=${player.name}")
            }
        }
    }

    private fun reduceWorldMaxScore(reduction: Int)
    {
        WorldManager.world.mapTiles.forEach { territory ->
            if (territory.isDestroyed)
            {
                territory.pointValue = 0
            }
        }
        Logger.info(LogCategory.GENERAL, "World max score reduced by $reduction points due to territory destruction")
    }

    private suspend fun triggerNemesisSpawn()
    {
        val nemesisEvent = ActionHistory(
            player = "WORLD",
            eventType = GameEventType.NPC,
            metadata = NpcEventMetadata(
                npcName = "Nemesis",
                npcEventType = "nemesis_spawn",
                reason = "karma_threshold_reached"
            )
        )
        WorldManager.recordActionHistoryEvent(ActionHistoryEvent(nemesisEvent))
        Logger.info(LogCategory.GENERAL, "Nemesis spawn triggered after karma threshold reached")
    }
}