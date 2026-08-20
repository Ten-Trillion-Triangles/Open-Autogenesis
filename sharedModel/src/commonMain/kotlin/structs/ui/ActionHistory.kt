package structs.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class GameEventType
{
    STORY,
    JUDGE,
    NPC,
    TERRITORY,
    RESOURCE,
    RESOURCE_GRANT,
    PLAYER_OUTCOME,
    WORLD_RULE,
    SCORING_UPDATE,
    TURN_COMPLETE
}

@Serializable
sealed interface ActionHistoryMetadata
{
    fun validate(eventType: GameEventType): Boolean = true
}

@Serializable
@SerialName("action_history.metadata.empty")
object EmptyActionHistoryMetadata : ActionHistoryMetadata

@Serializable
@SerialName("action_history.metadata.story")
data class StoryEventMetadata(
    val storyText: String,
    val playerAction: String,
    val involvedCharacters: List<String> = emptyList(),
    val affectedTerritories: List<String> = emptyList()
) : ActionHistoryMetadata
{
    override fun validate(eventType: GameEventType): Boolean
    {
        return storyText.isNotBlank() && playerAction.isNotBlank()
    }
}

@Serializable
@SerialName("action_history.metadata.judge")
data class JudgeEventMetadata(
    val wasSuccessful: Boolean,
    val judgmentReason: String,
    val resourcesUsed: List<String> = emptyList(),
    val difficultyModifiers: Map<String, Int> = emptyMap()
) : ActionHistoryMetadata
{
    override fun validate(eventType: GameEventType): Boolean
    {
        return judgmentReason.isNotBlank()
    }
}

@Serializable
@SerialName("action_history.metadata.npc")
data class NpcEventMetadata(
    val npcName: String,
    val npcEventType: String,
    val npcPointValue: Int = 0,
    val reason: String = ""
) : ActionHistoryMetadata
{
    override fun validate(eventType: GameEventType): Boolean
    {
        return npcName.isNotBlank() && npcEventType.isNotBlank()
    }
}

@Serializable
@SerialName("action_history.metadata.territory")
data class TerritoryEventMetadata(
    val territoryName: String,
    val previousOwner: String,
    val newOwner: String,
    val isDestroyed: Boolean = false,
    val pointValueChange: Int = 0
) : ActionHistoryMetadata
{
    override fun validate(eventType: GameEventType): Boolean
    {
        return territoryName.isNotBlank() && newOwner.isNotBlank()
    }
}

@Serializable
@SerialName("action_history.metadata.resource")
data class ResourceEventMetadata(
    val resourceName: String,
    val resourceType: String,
    val isGranted: Boolean = false,
    val isDepleted: Boolean = false,
    val isDestroyed: Boolean = false,
    val recipient: String = "",
    val grantAmount: Int = 0
) : ActionHistoryMetadata
{
    override fun validate(eventType: GameEventType): Boolean
    {
        return resourceName.isNotBlank()
    }
}

@Serializable
@SerialName("action_history.metadata.player_outcome")
data class PlayerOutcomeMetadata(
    val outcome: String,
    val victory: Boolean,
    val reason: String = ""
) : ActionHistoryMetadata
{
    override fun validate(eventType: GameEventType): Boolean
    {
        return outcome.isNotBlank()
    }
}

@Serializable
@SerialName("action_history.metadata.world_rule")
data class WorldRuleMetadata(
    val ruleName: String,
    val description: String,
    val effect: String
) : ActionHistoryMetadata
{
    override fun validate(eventType: GameEventType): Boolean
    {
        return ruleName.isNotBlank() && effect.isNotBlank()
    }
}

@Serializable
@SerialName("action_history.metadata.scoring")
data class ScoringEventMetadata(
    val pointType: String,
    val pointChange: Int,
    val reason: String,
    val totalPoints: Int = 0
) : ActionHistoryMetadata
{
    override fun validate(eventType: GameEventType): Boolean
    {
        return pointType.isNotBlank()
    }
}

@Serializable
@SerialName("action_history.metadata.turn_complete")
data class TurnCompleteMetadata(
    val summary: String = "",
    val turnNumber: Int = 0
) : ActionHistoryMetadata

@Serializable
@SerialName("action_history.metadata.generic")
data class GenericActionHistoryMetadata(
    val details: Map<String, JsonElement> = emptyMap()
) : ActionHistoryMetadata

@Serializable
data class ActionHistory(
    var player: String = "",
    var eventType: GameEventType = GameEventType.STORY,
    var metadata: ActionHistoryMetadata = EmptyActionHistoryMetadata,
    var turnNumber: Int = 1,
    var timestampMillis: Long = 0L
)

fun ActionHistory.validate(): Boolean
{
    return metadata.validate(eventType)
}

@Serializable
data class ActionHistoryEvent(
    val event: ActionHistory,
    val recordedAtMillis: Long = 0L
)

@Serializable
data class ActionHistoryBatch(
    val events: List<ActionHistoryEvent> = emptyList(),
    val turnPlayer: String = "",
    val turnNumber: Int = 1
)