package enums

import kotlinx.serialization.Serializable

/**
 * Defines the size of a territory. Each territory's point value is based on it's size.
 * @param Small Worth 2 points
 * @param Medium Worth 3 points
 * @param Large Worth 4 points
 * @param Capital Worth 5 points
 */
@Serializable
enum class TerritorySize
{
    Small,
    Medium,
    Large,
    Capital
}
