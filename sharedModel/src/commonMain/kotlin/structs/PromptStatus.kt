package structs

import kotlinx.serialization.Serializable

@Serializable
enum class PromptType {
    PLAY, ANSWER, CHAT, OPEN, CLASSIFYING, NONE
}

@Serializable
data class AgentUsage(
    var runningPlayAgent: Boolean = false,
    var runningAnswerAgent: Boolean = false,
    var runningOpenAgent: Boolean = false,
    var runningChatAgent: Boolean = false,
    var runningClassifier: Boolean = false
)