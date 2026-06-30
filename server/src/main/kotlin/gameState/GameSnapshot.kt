package gameState

import kotlinx.serialization.Serializable
import serverStructs.PlayerStats
import structs.GameHistory
import structs.World

/**
 * Encapsulates the complete state of a game session for persistence.
 *
 * @property world The current world state (map, players, NPCs).
 * @property history Full turn-by-turn narrative history.
 * @property geopoliticalAssessment AI-generated geopolitical summary.
 * @property playerStats Mapping of players to network identities and connection status.
 * @property turnOrderIndex Current position in the turn order.
 * @property npcInterferenceList NPCs scheduled to act in the current round.
 * @property lastAnnouncedRound Tracks the last round that triggered a UI announcement.
 * @property lastActiveNemesisNames Set of Nemesis names that were active during the last round announcement.
 * @property lastDefeatedNemesisNames Set of Nemesis names that were defeated during the last round announcement.
 * @property mapPackName Name/Path of the map used to initialize the session.
 */
@Serializable
data class GameSnapshot(
    val world: World,
    val history: List<GameHistory>,
    val geopoliticalAssessment: String,
    val playerStats: List<PlayerStats>,
    val turnOrderIndex: Int,
    val npcInterferenceList: List<String>,
    val lastAnnouncedRound: Int,
    val lastActiveNemesisNames: Set<String>,
    val lastDefeatedNemesisNames: Set<String>,
    val mapPackName: String,
    val isSinglePlayer: Boolean,
    val humanPlayerName: String
)
