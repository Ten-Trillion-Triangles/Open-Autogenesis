package structs.ui

import structs.GameHistory

class ActionHistoryProcessor
{
    fun processActionHistoryToGameHistory(
        actionEvents: List<ActionHistory>,
        turnPlayer: String
    ): GameHistory
    {
        val gameHistory = GameHistory().apply {
            this.turnPlayer = turnPlayer
            actionHistory = actionEvents.toMutableList()
        }

        actionEvents.forEach { event ->
            when (event.eventType)
            {
                GameEventType.STORY          -> processStoryEvent(event, gameHistory)
                GameEventType.JUDGE          -> processJudgeEvent(event, gameHistory)
                GameEventType.NPC            -> processNpcEvent(event, gameHistory)
                GameEventType.TERRITORY      -> processTerritoryEvent(event, gameHistory)
                GameEventType.RESOURCE       -> processResourceEvent(event, gameHistory)
                GameEventType.RESOURCE_GRANT -> processResourceGrantEvent(event, gameHistory)
                GameEventType.PLAYER_OUTCOME -> processPlayerOutcomeEvent(event, gameHistory)
                GameEventType.WORLD_RULE     -> processWorldRuleEvent(event, gameHistory)
                GameEventType.SCORING_UPDATE -> processScoringEvent(event, gameHistory)
                GameEventType.TURN_COMPLETE  -> processTurnCompleteEvent(event, gameHistory)
            }
        }

        return gameHistory
    }

    private fun processStoryEvent(event: ActionHistory, gameHistory: GameHistory)
    {
        val metadata = event.metadata as? StoryEventMetadata ?: return
        if (metadata.storyText.isNotBlank())
        {
            gameHistory.turnStory = metadata.storyText
        }
        if (metadata.playerAction.isNotBlank())
        {
            gameHistory.turnAction = metadata.playerAction
        }
    }

    private fun processJudgeEvent(event: ActionHistory, gameHistory: GameHistory)
    {
        val metadata = event.metadata as? JudgeEventMetadata ?: return
        gameHistory.wasPlayerSuccessful = metadata.wasSuccessful
        if (metadata.judgmentReason.isNotBlank())
        {
            gameHistory.turnResult = metadata.judgmentReason
        }
        if (metadata.resourcesUsed.isNotEmpty())
        {
            gameHistory.usingResources = metadata.resourcesUsed.toMutableList()
        }
    }

    private fun processNpcEvent(event: ActionHistory, gameHistory: GameHistory)
    {
        val metadata = event.metadata as? NpcEventMetadata ?: return
        if (metadata.reason.isNotBlank())
        {
            gameHistory.turnResult = metadata.reason
        }
        if (metadata.npcEventType.isNotBlank())
        {
            gameHistory.turnResult = metadata.npcEventType
        }
    }

    private fun processTerritoryEvent(event: ActionHistory, gameHistory: GameHistory)
    {
        val metadata = event.metadata as? TerritoryEventMetadata ?: return
        val territoryName = metadata.territoryName
        if (territoryName.isBlank()) return

        if (metadata.newOwner == event.player)
        {
            gameHistory.territoryGained.add(territoryName)
        }
        else if (metadata.previousOwner == event.player)
        {
            gameHistory.territoryLost.add(territoryName)
        }
    }

    private fun processResourceEvent(event: ActionHistory, gameHistory: GameHistory)
    {
        val metadata = event.metadata as? ResourceEventMetadata ?: return
        if (metadata.resourceName.isBlank()) return

        if (metadata.isGranted)
        {
            gameHistory.resourcesWon.add(metadata.resourceName)
        }
        if (metadata.isDepleted || metadata.isDestroyed)
        {
            gameHistory.turnResult = "${metadata.resourceName} consumed"
        }
    }

    private fun processResourceGrantEvent(event: ActionHistory, gameHistory: GameHistory)
    {
        val metadata = event.metadata as? ResourceEventMetadata ?: return
        if (metadata.resourceName.isBlank()) return

        if (metadata.isGranted)
        {
            val description = if (metadata.grantAmount > 0)
            {
                "${metadata.resourceName} x${metadata.grantAmount} granted"
            }
            else
            {
                "${metadata.resourceName} granted"
            }
            gameHistory.resourcesWon.add(metadata.resourceName)
            if (description.isNotBlank())
            {
                gameHistory.turnResult = description
            }
        }
    }

    private fun processPlayerOutcomeEvent(event: ActionHistory, gameHistory: GameHistory)
    {
        val metadata = event.metadata as? PlayerOutcomeMetadata ?: return
        gameHistory.wasPlayerSuccessful = metadata.victory
        if (metadata.reason.isNotBlank())
        {
            gameHistory.turnResult = metadata.reason
        }
    }

    private fun processWorldRuleEvent(event: ActionHistory, gameHistory: GameHistory)
    {
        val metadata = event.metadata as? WorldRuleMetadata ?: return
        if (metadata.description.isNotBlank())
        {
            gameHistory.turnResult = metadata.description
        }
    }

    private fun processScoringEvent(event: ActionHistory, gameHistory: GameHistory)
    {
        val metadata = event.metadata as? ScoringEventMetadata ?: return
        if (metadata.reason.isNotBlank())
        {
            gameHistory.turnResult = metadata.reason
        }
    }

    private fun processTurnCompleteEvent(event: ActionHistory, gameHistory: GameHistory)
    {
        val metadata = event.metadata as? TurnCompleteMetadata ?: return
        if (metadata.summary.isNotBlank())
        {
            gameHistory.turnResult = metadata.summary
        }
    }
}

class TurnAggregator(private val defaultRoundNumber: Int = 1)
{
    fun aggregateEventsByTurn(events: List<ActionHistory>): Map<String, List<ActionHistory>>
    {
        return events.groupBy { getTurnKey(it) }
    }

    fun createGameHistoryFromTurn(
        turnEvents: List<ActionHistory>,
        processor: ActionHistoryProcessor
    ): GameHistory
    {
        val turnPlayer = turnEvents.firstOrNull()?.player.orEmpty()
        return processor.processActionHistoryToGameHistory(turnEvents, turnPlayer)
    }

    fun createGameHistoriesFromEvents(
        events: List<ActionHistory>,
        processor: ActionHistoryProcessor
    ): List<GameHistory>
    {
        return aggregateEventsByTurn(events).values.map { createGameHistoryFromTurn(it, processor) }
    }

    private fun getTurnKey(event: ActionHistory): String
    {
        val turnNumber = event.turnNumber.takeIf { it > 0 } ?: defaultRoundNumber
        return "${event.player}_turn$turnNumber"
    }
}
