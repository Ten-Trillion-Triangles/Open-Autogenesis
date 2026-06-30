package structs

import structs.ui.ActionHistory
import org.ttt.autogenesis.network.ThinkingUpdateData

/**
 * Represents the outcome for a single player/NPC affected by a turn.
 *
 * @param playerName Name of the affected player/NPC
 * @param territoriesGained Territories this player gained
 * @param territoriesLost Territories this player lost
 * @param resourcesGained Resources this player gained
 * @param resourcesLost Resources this player lost
 * @param netOutcome Overall outcome: "Positive", "Negative", or "Neutral"
 */
@kotlinx.serialization.Serializable
data class PlayerOutcome(
    var playerName: String = "",
    var territoriesGained: MutableList<String> = mutableListOf(),
    var territoriesLost: MutableList<String> = mutableListOf(),
    var resourcesGained: MutableList<String> = mutableListOf(),
    var resourcesLost: MutableList<String> = mutableListOf(),
    var netOutcome: String = "" // "Positive", "Negative", "Neutral"
)

/**
 * Represents a territory transfer between players.
 *
 * @param territoryName Name of the territory
 * @param from Player/NPC losing the territory (empty if contested)
 * @param to Player/NPC gaining the territory (empty if becoming contested)
 */
@kotlinx.serialization.Serializable
data class TerritoryExchange(
    var territoryName: String = "",
    var from: String = "",
    var to: String = ""
)

/**
 * Represents an asset/resource transfer between players.
 *
 * @param assetName Name of the asset
 * @param from Player/NPC losing the asset
 * @param to Player/NPC gaining the asset
 */
@kotlinx.serialization.Serializable
data class AssetExchange(
    var assetName: String = "",
    var from: String = "",
    var to: String = ""
)

/**
 * Defines the history of the game. This contains each individual turn, the actions taken by the player, the story of the
 * turn, and the results of the turn.
 *
 * @param turnPlayer Defines the player or npc who's turn was taken.
 * @param turnAction Summary of the action that was taken.
 * @param usingResources List of which resources are being used in the play. Affects judgment and outcome of the
 * action.
 * @param turnStory The entire story written by the writing agent. This is saved here after any reversals, or other
 * external modifications by extra gameplay agents or other hidden stats.
 * @param wasPlayerSuccessful Determines if the player won or lost the play.
 * @param turnResult This is the summarized final outcome of the player's turn.
 * @param territoryGained List of territories won by the player.
 * @param territoryLost List of territories lost by the player.
 * @param resourcesWon List of resources won by the player.
 * @param territoryExchanges All territory transfers between any players/NPCs
 * @param assetExchanges All asset transfers between any players/NPCs
 * @param affectedPlayers Map of player names to their outcomes (for multi-player display)
 * @param actionHistory List of actions taken by the player. This is used to display the history of the player's actions
 * in the UI.
 * @param targetIntent The resolved intent (Hostile/Friendly) associated with the action. Used for judge context seeding.
 * @param targetEntities The concrete targets that were identified for the turn, supplied to downstream evaluators.
 *
 * @see [ActionHistory]
 */
@kotlinx.serialization.Serializable
data class GameHistory(
    var turnPlayer: String = "",
    var turnAction: String = "",
    var usingResources: MutableList<String> = mutableListOf(),
    var turnStory: String = "",
    var wasPlayerSuccessful: Boolean = false,
    var turnResult: String = "",
    var territoryGained: MutableList<String> = mutableListOf(),
    var territoryLost: MutableList<String> = mutableListOf(),
    var resourcesWon: MutableList<String> = mutableListOf(),
    var resourcesLost: MutableList<String> = mutableListOf(),
    var statBuffsGained: MutableMap<String, String> = mutableMapOf(),
    var counterResponses: MutableList<String> = mutableListOf(),
    var territoryExchanges: MutableList<TerritoryExchange> = mutableListOf(),
    var assetExchanges: MutableList<AssetExchange> = mutableListOf(),
    var affectedPlayers: MutableMap<String, PlayerOutcome> = mutableMapOf(),
    var targetIntent: String = "",
    var targetEntities: MutableList<String> = mutableListOf(),
    var thinkingUpdates: MutableList<ThinkingUpdateData> = mutableListOf(),
    @kotlinx.serialization.Transient
    var actionHistory: MutableList<ActionHistory> = mutableListOf(),
    val id: String = generateId()
)

private fun generateId(): String {
    val charPool : List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    return (1..16)
        .map { kotlin.random.Random.nextInt(0, charPool.size) }
        .map(charPool::get)
        .joinToString("")
}
