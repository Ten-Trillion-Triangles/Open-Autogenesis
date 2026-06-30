package enums

import kotlinx.serialization.Serializable

/**
 * Defines commander personality traits that affects their interactions with other characters in the game.
 *
 * @param Warlord Warlords are aggressive commands that tend to rule their nations with an iron fist.
 * They accel at authoritarian tactics, warfare, and hostile and aggressive actions. They receive buffs
 * to military actions, debuffs to diplomatic actions, and receive buffs researching military and hostile
 * weapons and resources. They receive debuffs for other research actions. Warlords tend to have yes-men
 * as subordinate allies, incompetent allies, or ones likely to betray them.
 *
 * @param Diplomatic Diplomat commanders accel a negotiations, politics, and alliances. They receive a
 * buff on diplomatic actions but suffer debuffs for military actions both offensive and defensive. They
 * fare better researching non-military resources. Diplomatic commanders tend to depend on their allies more
 * but are more likely to have loyal and useful allies.
 *
 * @param Researcher Researchers excel at researching new resources. They have the unique ability to research
 * resources that do not exist in the limits of the games worlds. This allows them to research technology in magical worlds
 * and magic in sci-fi worlds etc. They suffer debuffs on both military and diplomatic plays, but are able to compensate
 * by researching things the other players cannot obtain. Researches tend to have subordinates focused on advancement
 * of resources rather than diplomacy or military actions.
 *
 * @param Balanced Balanced commanders do not have buffs or debuffs on any specific actions. They also
 * do not have any unique traits that allow them to bypass the game's rules or gain advantages.
 */
@Serializable
enum class CommanderTrait
{
    Warlord,
    Diplomatic,
    Researcher,
    Balanced
}
