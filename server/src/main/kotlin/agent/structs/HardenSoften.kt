package agent.structs

/**
 * Data class used for the harden and soften agents. Provides a % to adjust by, and the base story to modify.
 */
@kotlinx.serialization.Serializable
data class HardenSoften(
    @kotlinx.serialization.SerialName("HARDEN_PERCENT")
    var hardenPercent: Int = 0,
    var storyEvents: String = ""
)