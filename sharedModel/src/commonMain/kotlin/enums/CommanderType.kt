package enums

import kotlinx.serialization.Serializable

/**
 * Defines the internal typing a command has upon their character creation.
 * Commander types affect chances against certain types of tiles, and also impacts what starting tiles
 * a commander can be dropped into.
 *
 * @param Land Land based commanders cannot be dropped onto aquatic tiles as their starting tiles,
 * and have no inherit advantage against obstacles, or water. Land based commanders must research
 * methods to overcome obstacle tiles like mountains, rivers, and bodies of water to avoid being debuffed
 * attacking across those points. Land based commanders have a defensive advantage against non-land based
 * commanders attacking them across landlocked map tiles.
 *
 * @param Aquatic Aquatic commanders can be placed in water tiles as their starting nations, have advantages
 * attacking islands and coastlines, and resist attacks from Flying, and Land type commanders that attack
 * their aquatic bases without researching a transport method. Aquatic commanders can attack any coastline
 * or island from any coastline, island, or aquatic exterior tile they own. Aquatic commanders suffer debuffs
 * when trying to attack landlocked area without researching transportation prior.
 *
 * @param Flying Flying commanders have the ability to fly over obstacles and make a direct attack between
 * any tile they are on, and any tile that's adjacent regardless of what obstacles are between them. Flying
 * commanders also receive a buff when attacking an enemy tile across an obstacle. However, they are
 * debuffed when attacking an underwater tile, or when attacking across a landlocked area without bypassing
 * an obstacle.
 */
@Serializable
enum class CommanderType
{
    Land,
    Aquatic,
    Flying
}
