package models

import kotlinx.serialization.Serializable

@Serializable
data class IconConfig(
    val territoryId: String,
    val iconImage: String,
    val size: IconSize,
    val shape: IconShape,
    val ownerColor: String
)

enum class IconSize(val pixels: Int) {
    SMALL(32),
    MEDIUM(48),
    LARGE(64),
    XLARGE(96)
}

enum class IconShape {
    CIRCLE,
    SQUARE,
    ROUNDED_SQUARE
}

enum class TerritoryState {
    NORMAL,
    CAPTURED,
    CONTESTED,
    FORTIFIED,
    DESTROYED
}