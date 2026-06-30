package org.ttt.autogenesis.network

import kotlinx.serialization.Serializable
import structs.TerritoryExchange

/**
 * Defines whether an action is hostile or friendly toward its target.
 */
@Serializable
enum class ActionIntent
{
    /**
     * Hostile actions: attacks, invasions, sabotage, threats, coercion, hostile takeovers.
     */
    Hostile,

    /**
     * Friendly actions: gifts, alliances, aid, trade offers, cooperation, support.
     */
    Friendly
}

/**
 * Represents the distinct steps/pages in the turn resolution UI.
 */
@Serializable
enum class ResolutionStep(val index: Int) {
    START(0),
    PLAYER_ACTION(1),
    INTENT(2),
    STORY(3),
    JUDGEMENT(4),
    DISPATCH(5),
    UPDATE_NPCS(6),
    UPDATE_WORLD(7),
    COUNTER_PLAY(8),
    WAITING(9)
}

/**
 * Signal to switch the UI to a specific resolution step.
 */
@Serializable
data class ResolutionStepData(
    val step: ResolutionStep,
    val message: String? = null
)

/**
 * Signal to update the progress bar state and instruction text.
 */
@Serializable
data class ProgressBarData(
    val activeIndex: Int,
    val instruction: String? = null
)

/**
 * Signal containing a chunk of narrative text (for streaming).
 */
@Serializable
data class NarrativeChunkData(
    val chunk: String,
    val isComplete: Boolean = false
)

/**
 * Signal to prepare the story page for a new story (wipe old text).
 */
@Serializable
data class PrepareStoryData(
    val style: String? = null // Optional style hint (e.g. "glitch", "fade")
)

/**
 * Signal containing the full replaced narrative text (for refinements like harden/soften).
 */
@Serializable
data class NarrativeUpdateData(
    val text: String
)

/**
 * Signal to lock or unlock the command console.
 */
@Serializable
data class CommandInteractiveData(
    val interactive: Boolean
)
@Serializable
data class GeopoliticsUpdateData(
    val assessment: String
)

/**
 * Signal to show a message box on the client.
 */
@Serializable
data class ShowMessageBoxData(
    val title: String,
    val message: String,
    val showOk: Boolean = true,
    val showCancel: Boolean = false,
    val showThrobber: Boolean = false
)

/**
 * Widgets that can be opened via the Open Agent.
 */
@Serializable
enum class OpenWidgetType
{
    WORLD_STATS,
    PLAYER_RESOURCES,
    STATS,
    SETTINGS,
    PROMPT_STATUS,
    NEURAL_LINK,
    AGENT_STREAM,
    /**
     * Opens the per-player `DelegateWidget` so the player can author guidance for the
     * AI that takes over their character when they are unreachable. Driven by the
     * KVision left-rail pin button, the `/delegate` slash command, or the natural-language
     * Open Agent prompt (`PromptManager`).
     */
    DELEGATE
}

/**
 * Signal requesting the client to open a specific widget.
 */
@Serializable
data class OpenWidgetData(
    val widget: OpenWidgetType,
    val tabId: String? = null,
    val title: String? = null,
    val commandContext: String? = null
)

/**
 * Signal to prompt a player for a counter-play response.
 */
@Serializable
data class CounterPlayPrompt(
    val playerId: String,
    val attackerName: String,
    val actionDescription: String,
    val actionIntent: ActionIntent = ActionIntent.Hostile
)

/**
 * Signal containing the result of a Summit.
 */
@Serializable
data class SummitResultData(
    val success: Boolean,
    val narrativeSummary: String,
    val statChanges: Map<String, String>
)

/**
 * Signal containing the result summary of the judgement phase (Success/Failure/Etc).
 */
@Serializable
data class JudgementEffectData(
    val isSuccess: Boolean,
    val header: String,
    val subtext: String
)

/**
 * Signal containing the latest state of the turn timer.
 */
@Serializable
data class TurnTimerUpdateData(
    val remainingSeconds: Long,
    val totalDuration: Long = 30L,
    val isRunning: Boolean
)

/**
 * Signal containing the logistical results of the turn (assets used vs gained/lost)
 * for the Dispatch page.
 */
@Serializable
data class DispatchData(
    val usedAssets: List<String> = emptyList(),
    val gainedAssets: List<String> = emptyList(),
    val lostAssets: List<String> = emptyList(),
    val territoryGained: List<String> = emptyList(),
    val territoryLost: List<String> = emptyList(),
    val territoryExchanges: List<TerritoryExchange> = emptyList()
)

/**
 * Represents a single actor in the turn order announcement (player or NPC).
 */
@Serializable
data class TurnOrderParticipant(
    val name: String,
    val isPlayer: Boolean = true,
    val npcType: enums.NpcType? = null
)

/**
 * Signal used to announce the order of actors for a newly started round.
 */
@Serializable
data class TurnOrderAnnouncementData(
    val roundNumber: Int,
    val participants: List<TurnOrderParticipant>,
    val firstActor: String? = null
)

/**
 * Signal indicating which actor currently owns the live turn.
 *
 * @param actorName Name of the actor currently required to take an action.
 * @param roundNumber The ongoing round number from the server state.
 * @param turnIndex Index (0-based) of this actor within the current turn order.
 * @param timerSeconds Remaining seconds granted for the actor to submit input.
 */
@Serializable
data class ActiveTurnData(
    val actorName: String,
    val roundNumber: Int,
    val turnIndex: Int,
    val timerSeconds: Long = 30L
)

/**
 * Indicates whether a Nemesis threat is a fresh arrival or a revival.
 */
@Serializable
enum class NemesisThreatKind
{
    ARRIVAL,
    REVIVAL
}

/**
 * Signal used to announce an incoming Nemesis threat during round startup.
 */
@Serializable
data class NemesisThreatAnnouncementData(
    val roundNumber: Int,
    val nemesisName: String,
    val kind: NemesisThreatKind,
    val reason: String
)

/**
 * Signal containing the updated intent/action text (e.g. after validation/sanitization).
 */
@Serializable
data class UpdateIntentData(
    val intent: String
)
/**
 * Signal to set the local player identity on the client.
 */
@Serializable
data class SetLocalPlayerData(
    val player: structs.Player
)

/**
 * Signal describing a placement entry for a completed session.
 *
 * @param name Display name of the player or NPC.
 * @param rank Placement rank (1 = champion).
 * @param territoryPoints Territory score used for ranking updates.
 * @param isPlayer True when the entry belongs to a human player.
 */
@Serializable
data class PlacementEntry(
    val name: String,
    val rank: Int,
    val territoryPoints: Int,
    val isPlayer: Boolean
)

/**
 * Signal containing the final results for a concluded game session.
 *
 * @param winnerName Name of the champion (player or NPC).
 * @param isVictory True when a human player won the match.
 * @param rounds Number of rounds played.
 * @param victoryPoints Points earned by the winner.
 * @param territoriesClaimed Territories controlled by the winner.
 * @param placements Ranked placements for all relevant actors.
 * @param tieResolvedByResourceScore True when the winner was decided by resource comparison.
 * @param tieResolvedRandomly True when resources tied and a random tiebreaker was used.
 */
@Serializable
data class GameOverData(
    val winnerName: String,
    val isVictory: Boolean,
    val rounds: Int,
    val victoryPoints: Int,
    val territoriesClaimed: Int,
    val placements: List<PlacementEntry> = emptyList(),
    val tieResolvedByResourceScore: Boolean = false,
    val tieResolvedRandomly: Boolean = false
)
@Serializable
data class ForceShowTurnResolutionData(
    val force: Boolean = true
)

/**
 * Signal containing a thinking/thought broadcast from an actor.
 *
 * @param playerId The unique identifier of the player or NPC.
 * @param characterName Display name of the character whose thinking is being broadcast.
 * @param isPlayer True if the thinking is from a player agent, false if from an NPC.
 * @param thinking The current thought or internal narrative of the actor.
 * @param timestamp Unix timestamp (ms) when this thinking was recorded.
 */
@Serializable
data class ThinkingUpdateData(
    val playerId: String,
    val characterName: String,
    val isPlayer: Boolean,
    val thinking: String,
    val timestamp: Long
)
