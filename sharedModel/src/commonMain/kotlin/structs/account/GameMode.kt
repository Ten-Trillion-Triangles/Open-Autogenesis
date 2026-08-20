package structs.account

import kotlinx.serialization.Serializable

/**
 * Game-mode classification used by [AccountPlan.unlocksModes] and the
 * matchmaking ticket-attribute `unlocked_modes` to gate which tiers may
 * start which mode.
 */
@Serializable
enum class GameMode
{
    MULTIPLAYER,
    ONE_V_ONE,
    ONE_V_THREE
}