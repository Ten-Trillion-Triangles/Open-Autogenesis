package structs.account

import kotlinx.serialization.Serializable

/**
 * Cost classification used by the matchmaking algorithm to choose the cheapest-to-host
 * mix of players for a PvP match. Derived from [AccountSettings] (see
 * [AccountSettings.costClass]).
 *
 * The order of the enum constants is meaningful: [rank] is computed from declaration
 * order, and the matchmaking algorithm sorts candidates so that the highest-rank (lowest
 * ordinal) class is filled first. The lower the ordinal, the better for the operator
 * (cheaper to host or higher revenue).
 *
 * Each constant carries a baseline [subsidyCapacity] — the number of other player slots
 * the cost class can effectively subsidize — and a [creditSubsidyPerThousand] used to
 * derive an additional credit-based subsidy for the [CREDIT] class.
 *
 * @see AccountSettings.costClass
 * @see AccountSettings.subsidyCapacity
 */
@Serializable
enum class CostClass
{
    /** Player supplies their own LLM API key; cheapest cost class for the operator. */
    BYO_KEY
    {
        override val subsidyCapacity: Int = 4
        override val creditSubsidyPerThousand: Int = 0
    },
    /** Active PRO subscription; the plan is paid, so the operator has margin. */
    PRO
    {
        override val subsidyCapacity: Int = 2
        override val creditSubsidyPerThousand: Int = 0
    },
    /** Active CASUAL subscription; lower margin than PRO. */
    CASUAL
    {
        override val subsidyCapacity: Int = 1
        override val creditSubsidyPerThousand: Int = 0
    },
    /** No active sub, but the player has a non-zero wallet balance. */
    CREDIT
    {
        override val subsidyCapacity: Int = 0
        override val creditSubsidyPerThousand: Int = 1
    },
    /** No sub and no credits; the operator subsidizes the LLM cost entirely. */
    FREE
    {
        override val subsidyCapacity: Int = 0
        override val creditSubsidyPerThousand: Int = 0
    };

    /**
     * Number of other player slots this cost class can subsidize, ignoring the
     * credit-wallet boost that the [CREDIT] class may also receive.
     */
    abstract val subsidyCapacity: Int

    /**
     * Number of subsidizing slots contributed per 1000 wallet credits, applied on top
     * of [subsidyCapacity] (and capped at 2 by the algorithm). Only meaningful for
     * [CREDIT]; other classes return 0.
     */
    abstract val creditSubsidyPerThousand: Int

    /**
     * The matchmaking rank, derived from the enum declaration order. Lower is better
     * (filled first). Used as a stable tiebreak.
     */
    val rank: Int
        get() = ordinal
}