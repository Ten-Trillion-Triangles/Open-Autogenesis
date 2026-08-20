package scoring

import kotlinx.serialization.Serializable

@Serializable
data class ScoringResult(
    val playerScoreChanges: MutableMap<String, MutableMap<String, Int>> = mutableMapOf(),
    var worldPointChange: Int = 0,
    var worldScoreReduction: Int = 0,
    var karmaPointChange: Int = 0
)

fun ScoringResult.addScoreChange(player: String, reason: String, points: Int)
{
    if (player.isBlank() || reason.isBlank() || points == 0) return
    val entry = playerScoreChanges.getOrPut(player) { mutableMapOf() }
    entry[reason] = (entry[reason] ?: 0) + points
}