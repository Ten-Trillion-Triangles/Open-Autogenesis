package enums

import kotlinx.serialization.Serializable

/**
 * Defines classifications and categories of npc's in the game. Each classification has an escalating level
 * of NPC independence with higher level npc's being able to be treated as equal to players, hold resources,
 * take turns, and interact with the game beyond just the events in the story. Any npc can be written by the story
 * writer agent to do otherwise, and when this happens the gameplay agents may escalate their status into more
 * independent NPC's that become more impactful in the story.
 *
 * @param Subordinate Owned by a player and acts as a servant, cabinet member, general etc. Subordinates
 * Are not able to take their own actions freely by the game's AI. However as with all things, they can
 * take actions if the writer AI agent states they do. This includes betraying the player which may result
 * in their status being elevated to a higher level independent npc status.
 *
 * @param Passive Passive NPC's are typically nation state commanders, NPC's that appear as characters during
 * the writer Agent's story writing, or other NPC's that take a non-active role in the story and game. They cannot
 * take their own turns, hold territory or resources beyond their starting tile, or take offensive measures
 * against the player when they are not referenced by the story.
 *
 * @param Active Active NPC's are NPC's that are now able to take turns, and conduct direct gameplay actions
 * as if they are players. They can directly target other Active NPC's for direct game actions, as well as players.
 * They are also able to take military, diplomatic, and research turns. They have a very low chance of taking a turn
 * in a round. Which increases over time until they are able to take a turn. Active NPC's tend to have their own
 * very specific interests, and may even help players if those align. They do not tend to take very aggressive actions
 * to capture territory, or interfere with the players unless provoked.
 *
 * @param Hostile A hostile NPC is an NPC that takes aggressive and obstructive actions against players regularly.
 * Hostile NPC's have a moderate change of interfering with a turn, taking their own turn, and are actively trying to
 * expand their resources or territory. Hostile NPC's then to be opportunistic in their actions rather than
 * being malicious and carrying out grudges against one or more players. Killing or removing a hostile NPC form play
 * rewards point values and owned properties and assets they have.
 *
 * @param Nemesis A nemesis is an NPC that poses great danger to the players. They are extremely hostile,
 * and have very high odds of taking their own turns. They are actively seeking to very aggressively take territory,
 * cause trouble and wreak havoc on the players. They usually hold grudges against one or more players are seeking at
 * destroying that player by any means necessary. The nemesis is very hard to defeat and often requires multiple
 * players teaming up at once to bring them down. They provide a large number of points while they remain dead.
 * However, they are very likely to revive themselves and resume causing trouble for players.
 *
 * @param ElderGod An Elder God is a hostile npc that is a god of some kind. They do not appear normally and are
 * typically summoned by the actions of a player, or Nemesis. They have the unique power to totally destroy a map tile
 * removing it from its owner, and destroying its point value and resources completely. An Elder God will take a hostile
 * action to destroy a map tile each round.
 *
 */
@Serializable
enum class NpcType
{
    Subordinate,
    Passive,
    Active,
    Hostile,
    Nemesis,
    ElderGod
}
