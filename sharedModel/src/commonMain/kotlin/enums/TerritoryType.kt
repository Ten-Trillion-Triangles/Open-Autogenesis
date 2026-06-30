package enums

import kotlinx.serialization.Serializable

@Serializable
enum class TerritoryType
{
    Land,
    Coastline,
    Island,
    Underwater,
    Desert,
    Void
}
