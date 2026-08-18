package structs.account

import kotlinx.serialization.Serializable

/**
 * Subscription tier for a player's account.
 *
 * Tier names are thematically aligned with the game's setting (geopolitical
 * strategy, autocracy, satire of real-world politics). The five-tier ladder
 * mirrors the subscription-model research at
 * `tools/autogenesis_subscription_model.html` (Section 4.5).
 *
 * Each tier carries:
 * - [tokenCapPerCycle] the monthly inference budget the player can burn
 * - [unlocksModes] the set of game modes the tier is permitted to start
 * - [pricePerMonthUsd] the headline price, used for tier-mix revenue projections
 *
 * @see structs.account.BillingStatus
 */
@Serializable
enum class AccountPlan(
    val tokenCapPerCycle: Long,
    val pricePerMonthUsd: Double,
    val unlocksModes: Set<GameMode>
)
{
    /** Free tier — multiplayer only, smallest monthly token budget. */
    FREE(
        tokenCapPerCycle = 2_000_000L,
        pricePerMonthUsd = 0.0,
        unlocksModes = setOf(GameMode.MULTIPLAYER)
    ),

    /** Entry paid tier ($25/mo). Multiplayer + 1v1 single-player, larger cap than FREE. */
    JUNTA(
        tokenCapPerCycle = 8_000_000L,
        pricePerMonthUsd = 25.0,
        unlocksModes = setOf(GameMode.MULTIPLAYER, GameMode.ONE_V_ONE)
    ),

    /** Mid-tier ($50/mo). Same modes as JUNTA, larger cap. */
    REPUBLIC(
        tokenCapPerCycle = 18_000_000L,
        pricePerMonthUsd = 50.0,
        unlocksModes = setOf(GameMode.MULTIPLAYER, GameMode.ONE_V_ONE)
    ),

    /** Heavy tier ($75/mo). Unlocks 1v3 single-player. */
    EMPIRE(
        tokenCapPerCycle = 32_000_000L,
        pricePerMonthUsd = 75.0,
        unlocksModes = setOf(GameMode.MULTIPLAYER, GameMode.ONE_V_ONE, GameMode.ONE_V_THREE)
    ),

    /** Top tier ($100/mo). Full access, largest cap. */
    SOVEREIGN(
        tokenCapPerCycle = 60_000_000L,
        pricePerMonthUsd = 100.0,
        unlocksModes = GameMode.entries.toSet()
    )
}
