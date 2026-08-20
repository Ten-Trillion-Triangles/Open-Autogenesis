package enums

import kotlinx.serialization.Serializable

@Serializable
enum class ObstacleType
{
    River,
    Mountain,
    Ocean
}