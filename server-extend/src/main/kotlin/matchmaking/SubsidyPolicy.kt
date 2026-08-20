package matchmaking

import structs.account.AccountPlan
import structs.account.CostClass

/**
 * Tunable knobs for the matchmaking algorithm. Lives separately from
 * [CostClass] so production can override the subsidy capacities and the
 * coverage thresholds without recompiling.
 *
 * All values are env-overridable via the static [reloadFromEnv] helper. The
 * defaults match the in-docstring targets: BYO_KEY covers 4 slots, PRO covers
 * 2, CASUAL covers 1, CREDIT scales with wallet balance, FREE covers 0.
 */
object SubsidyPolicy
{
    /** Capacity contributed by each non-CREDIT class. Indexed by enum name. */
    @Volatile
    var baseSubsidy: Map<String, Int> = linkedMapOf(
        CostClass.BYO_KEY.name to 4,
        CostClass.PRO.name to 2,
        CostClass.CASUAL.name to 1,
        CostClass.CREDIT.name to 0,
        CostClass.FREE.name to 0
    )

    /** Additional capacity per 1000 wallet credits for the CREDIT class. */
    @Volatile
    var creditSubsidyPerThousand: Int = 1

    /** Cap on the credit-derived subsidy. */
    @Volatile
    var creditSubsidyCap: Int = 2

    /**
     * Whether the strict pools (4 / 3 players) require coverage: the sum of
     * subsidy capacity across the chosen tickets must equal the target player
     * count, OR at least one BYO_KEY must be present. When `false` (the
     * default for the 2-player pool), any 2 players can match.
     */
    @Volatile
    var requireCoverageFor4: Boolean = true
    @Volatile
    var requireCoverageFor3: Boolean = true
    @Volatile
    var requireCoverageFor2: Boolean = false

    /** Cap on the per-ticket subsidy that the [AccountSettings] helper can return. */
    @Volatile
    var maxSubsidy: Int = 4

    /**
     * Re-reads the policy from environment variables, falling back to the
     * current values. Recognized variables:
     *  - `MATCHMAKING_SUBSIDY_BYO`, `_PRO`, `_CASUAL`, `_CREDIT`, `_FREE`
     *  - `MATCHMAKING_CREDIT_SUBSIDY_PER_1000`
     *  - `MATCHMAKING_REQUIRE_COVERAGE_4`, `_3`, `_2`
     *  - `MATCHMAKING_MAX_SUBSIDY`
     */
    fun reloadFromEnv(env: (String) -> String? = System::getenv)
    {
        fun readInt(name: String, current: Int): Int =
            env(name)?.trim()?.toIntOrNull() ?: current
        fun readBool(name: String, current: Boolean): Boolean =
            env(name)?.trim()?.lowercase()?.let {
                when(it)
                {
                    "1", "true", "yes", "on" -> true
                    "0", "false", "no", "off" -> false
                    else -> current
                }
            } ?: current

        val updated = baseSubsidy.toMutableMap()
        updated[CostClass.BYO_KEY.name] = readInt("MATCHMAKING_SUBSIDY_BYO", updated[CostClass.BYO_KEY.name] ?: 4)
        updated[CostClass.PRO.name] = readInt("MATCHMAKING_SUBSIDY_PRO", updated[CostClass.PRO.name] ?: 2)
        updated[CostClass.CASUAL.name] = readInt("MATCHMAKING_SUBSIDY_CASUAL", updated[CostClass.CASUAL.name] ?: 1)
        updated[CostClass.CREDIT.name] = readInt("MATCHMAKING_SUBSIDY_CREDIT", updated[CostClass.CREDIT.name] ?: 0)
        updated[CostClass.FREE.name] = readInt("MATCHMAKING_SUBSIDY_FREE", updated[CostClass.FREE.name] ?: 0)
        baseSubsidy = updated
        creditSubsidyPerThousand = readInt("MATCHMAKING_CREDIT_SUBSIDY_PER_1000", creditSubsidyPerThousand)
        creditSubsidyCap = readInt("MATCHMAKING_CREDIT_CAP", creditSubsidyCap)
        requireCoverageFor4 = readBool("MATCHMAKING_REQUIRE_COVERAGE_4", requireCoverageFor4)
        requireCoverageFor3 = readBool("MATCHMAKING_REQUIRE_COVERAGE_3", requireCoverageFor3)
        requireCoverageFor2 = readBool("MATCHMAKING_REQUIRE_COVERAGE_2", requireCoverageFor2)
        maxSubsidy = readInt("MATCHMAKING_MAX_SUBSIDY", maxSubsidy)
    }

    /**
     * Returns the configured base subsidy for [klass], or 0 when the class is
     * not in the policy map.
     */
    fun baseFor(klass: CostClass): Int = baseSubsidy[klass.name] ?: 0

    /**
     * Returns whether a pool targeting [targetPlayers] players requires the
     * chosen group to have enough subsidy coverage.
     */
    fun requireCoverage(targetPlayers: Int): Boolean = when(targetPlayers)
    {
        4 -> requireCoverageFor4
        3 -> requireCoverageFor3
        2 -> requireCoverageFor2
        else -> false
    }
}

/**
 * Returns the policy subsidy capacity for the given [CostClass], treating
 * the player as [AccountPlan.FREE] / zero-credit when the inputs are blank.
 * This is the form stamped on ticket attributes.
 */
fun CostClass.policySubsidy(): Int = SubsidyPolicy.baseFor(this).coerceIn(0, SubsidyPolicy.maxSubsidy)