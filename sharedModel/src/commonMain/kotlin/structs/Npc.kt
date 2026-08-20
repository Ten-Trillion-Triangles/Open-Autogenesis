package structs

import enums.NpcType
import interfaces.Actor
import interfaces.ActorInternals

/**
 * Defines an NPC in the game. NPC's are any AI character, or AI player in the game. They range from existing
 * solely in the confines of the story, to entering the game directly and being able to take turns and targeted
 * actions.
 * @property name The name of the NPC
 * @property type The type classification of the NPC
 * @property description Description of the NPC
 * @property personality Personality traits of the NPC
 * @property abilities Special abilities the NPC possesses
 * @property history Background history of the NPC
 * @property pointValue Point value for defeating this NPC
 * @property capturedTerritory List of territories controlled by this NPC
 * @property resources List of resources owned by this NPC
 * @property isDefeated Whether this NPC has been defeated
 */
@kotlinx.serialization.Serializable
data class Npc(
    var name: String = "",
    var type: NpcType = NpcType.Passive,
    var description: String = "",
    var personality: String = "",
    var abilities: String = "",
    var history: String = "",
    var pointValue: Int = 4,
    var capturedTerritory: MutableList<Territory> = mutableListOf(),
    var resources: MutableList<Resource> = mutableListOf(),
    var militaryReadiness: Int = 70, 
    var legitimacy: Int = 70,
    var stagnation: Int = 0,
    var createdBy: String = "Story", //Name of the player or npc that introduced them.
    var isDefeated: Boolean = false,
    var interferenceChance: Double = 0.2
) : Actor
{
    /**
     * Returns the internal actor representation for this NPC.
     * @return ActorInternals object containing name and NPC flag
     */
    override fun getInternals(): ActorInternals
    {
        return ActorInternals(name = name, isNpc = true)
    }
}