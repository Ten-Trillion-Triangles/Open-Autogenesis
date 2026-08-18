package agent.builders.safety

import kotlinx.serialization.Serializable

@Serializable
data class SafetyClassification(
    val isSafe: Boolean = false,
    val reason: String = ""
)
