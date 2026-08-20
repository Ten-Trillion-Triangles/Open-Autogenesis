package gameState

import kotlinx.serialization.Serializable

@Serializable
data class UniverseChanges(
    val changes: MutableList<UniverseChange> = mutableListOf()
)