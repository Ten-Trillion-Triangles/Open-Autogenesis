package structs

/**
 * Represents the distance and terrain obstacles between two territories.
 *
 * @property distance Number of territories in the shortest path between two territories
 * @property rivers Number of river obstacles crossed along the path
 * @property mountains Number of mountain obstacles crossed along the path
 * @property oceans Number of ocean obstacles crossed along the path
 */
@kotlinx.serialization.Serializable
data class TerritoryDistance(
    val distance: Int,
    val rivers: Int,
    val mountains: Int,
    val oceans: Int
)