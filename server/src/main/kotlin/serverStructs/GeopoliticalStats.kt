package serverStructs

/**
 * Data class to house the geopolitical assessment of the game world.
 * This contains a political scholar like essay that writes up the geopolitics of the
 * game world. And a definition of what "normal" is in terms of the geopolitics of
 * the game's world. This value will adjust over time as context is gathered from the
 * story at large. Likewise, what is considered normal even in worlds that make zero sense
 * will also adjust as players adjust to the new "normal" as the geopolitics of the game
 * world evolves with each play and event.
 */
@kotlinx.serialization.Serializable
data class GeopoliticalStats(
    var geoPoliticalAssessment: String = "",
    var whatNormalIs: String = ""
)
