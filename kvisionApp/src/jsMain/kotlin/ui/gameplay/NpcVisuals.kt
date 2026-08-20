package ui.gameplay

import enums.NpcType
import io.kvision.core.Color
import structs.Npc

/**
 * Represents the visual metadata that should accompany an NPC badge or icon.
 *
 * @property iconClass FontAwesome glyph used to depict the NPC.
 * @property color Accent color for the icon.
 * @property typeLabel Text describing the NPC type.
 * @property isDisabled True when the NPC is defeated and should show the disabled glyph.
 */
data class NpcVisualStyle(
    val iconClass: String,
    val color: Color,
    val typeLabel: String,
    val isDisabled: Boolean
)

/**
 * Returns the visual style for the provided NPC, swapping to the disabled glyph when it is defeated.
 */
fun getNpcVisualStyle(npc: Npc): NpcVisualStyle
{
    if(npc.isDefeated)
    {
        return NpcVisualStyle(
            iconClass = "fas fa-ban",
            color = Color.name(io.kvision.core.Col.LIGHTGRAY),
            typeLabel = npc.type.name,
            isDisabled = true
        )
    }

    return NpcVisualStyle(
        iconClass = getNpcTypeIcon(npc.type),
        color = getNpcTypeColor(npc.type),
        typeLabel = npc.type.name,
        isDisabled = false
    )
}

/**
 * Maps [NpcType] to the FontAwesome icon that represents that type when alive.
 */
fun getNpcTypeIcon(type: NpcType): String
{
    return when(type)
    {
        NpcType.Subordinate -> "fas fa-user-friends"
        NpcType.Passive -> "fas fa-user"
        NpcType.Active -> "fas fa-user-tie"
        NpcType.Hostile -> "fas fa-skull"
        NpcType.Nemesis -> "fas fa-dragon"
        NpcType.ElderGod -> "fas fa-biohazard"
    }
}

/**
 * Maps [NpcType] to the accent color used in the UI.
 */
fun getNpcTypeColor(type: NpcType): Color
{
    return when(type)
    {
        NpcType.Subordinate -> Color.name(io.kvision.core.Col.LIGHTGRAY)
        NpcType.Passive -> Color.name(io.kvision.core.Col.LIGHTBLUE)
        NpcType.Active -> Color.name(io.kvision.core.Col.LIGHTGREEN)
        NpcType.Hostile -> Color.name(io.kvision.core.Col.ORANGE)
        NpcType.Nemesis -> Color.name(io.kvision.core.Col.RED)
        NpcType.ElderGod -> Color.name(io.kvision.core.Col.PURPLE)
    }
}