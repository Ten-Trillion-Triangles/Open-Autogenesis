package agent.structs

@kotlinx.serialization.Serializable
data class Railroad(
    var storyPrompt: String = "",
    var systemInstructions: String = ""
)