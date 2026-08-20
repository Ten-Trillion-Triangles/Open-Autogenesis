package matchmaking

/**
 * Static specification of a single step in the matchmaking promotion ladder.
 *
 * The pool named [name] is the cohort a ticket starts in (or is promoted into).
 * The matchmaker is expected to keep tickets in the same ladder family together
 * (i.e., it never crosses a `pvp-4` ticket into a `pvp-3` proposal — promotion
 * happens by recreating the ticket in the next pool, not by reassigning).
 *
 * @property name AccelByte match2 pool name (e.g. `"pvp-4"`)
 * @property targetPlayers Number of players the pool tries to assemble (4 / 3 / 2)
 * @property holdSeconds Seconds the server-extend ladder keeps a ticket in this
 *                       pool before deleting and recreating in the next tier. The
 *                       pool's own `ticket_expiration_seconds` is bootstrapped to
 *                       the same value, so the AccelByte-side expiration never
 *                       pre-empts our promotion timer.
 * @property sessionTemplateName AccelByte session template name that carries the
 *                               right `maxPlayers` for this tier. The DS receives
 *                               this as `GameSessionStatus.maxPlayers` and
 *                               `GameInit.defineGameRules` picks the map from it.
 * @property family Ladder family used to gate cross-tier matching at the
 *                  matchmaker. Tickets in `pvp-4` are not visible to a `pvp-3`
 *                  proposal and vice versa.
 */
data class MatchPoolSpec(
    val name: String,
    val targetPlayers: Int,
    val holdSeconds: Int,
    val sessionTemplateName: String,
    val family: String = "pvp"
)
{
    init
    {
        require(name.isNotBlank()) { "MatchPoolSpec.name must not be blank" }
        require(targetPlayers in 1..MAX_TARGET_PLAYERS) {
            "MatchPoolSpec.targetPlayers=$targetPlayers is out of range 1..$MAX_TARGET_PLAYERS"
        }
        require(holdSeconds > 0) { "MatchPoolSpec.holdSeconds=$holdSeconds must be positive" }
        require(sessionTemplateName.isNotBlank()) {
            "MatchPoolSpec.sessionTemplateName must not be blank"
        }
    }

    companion object
    {
        /** Hard cap on target players per pool. Mirrors the largest map the DS loads. */
        const val MAX_TARGET_PLAYERS: Int = 4

        /**
         * Default ladder: 4 → 3 → 2. The first entry is the entry point for every
         * PvP ticket. The matchmaker bootstrap reads this list and ensures each
         * pool exists with the right `ticket_expiration_seconds` and
         * `session_template`.
         */
        val DEFAULT_LADDER: List<MatchPoolSpec> = listOf(
            MatchPoolSpec(
                name = "pvp-4",
                targetPlayers = 4,
                holdSeconds = 60,
                sessionTemplateName = "pvp-4p-session"
            ),
            MatchPoolSpec(
                name = "pvp-3",
                targetPlayers = 3,
                holdSeconds = 90,
                sessionTemplateName = "pvp-3p-session"
            ),
            MatchPoolSpec(
                name = "pvp-2",
                targetPlayers = 2,
                holdSeconds = 60,
                sessionTemplateName = "pvp-2p-session"
            )
        )
    }
}

/**
 * Ordered ladder used by the promotion timer. The first entry is where every
 * ticket starts; subsequent entries are the pools the ticket is recreated in
 * after [MatchPoolSpec.holdSeconds] elapses.
 */
class MatchmakingLadder(specs: List<MatchPoolSpec>)
{
    /** Stable copy of the input list, in promotion order. Never empty. */
    val tiers: List<MatchPoolSpec> = specs.toList()

    init
    {
        require(specs.isNotEmpty()) { "MatchmakingLadder requires at least one tier" }
        val seen = mutableSetOf<String>()
        for(spec in specs)
        {
            require(seen.add(spec.name)) {
                "MatchmakingLadder has duplicate pool name '${spec.name}'"
            }
        }
    }

    /** Tier the ticket starts in. Always the first element. */
    val entry: MatchPoolSpec
        get() = tiers.first()

    /**
     * Returns the tier that comes after [current] in the ladder, or `null` if
     * [current] is the final tier (in which case the ladder gives up).
     */
    fun nextAfter(current: MatchPoolSpec): MatchPoolSpec?
    {
        val idx = tiers.indexOfFirst { it.name == current.name }
        if(idx < 0 || idx >= tiers.size - 1) return null
        return tiers[idx + 1]
    }

    /** Looks up a tier by pool name. Returns `null` when not present. */
    fun byName(name: String): MatchPoolSpec? = tiers.firstOrNull { it.name == name }

    companion object
    {
        /** Ladder built from [MatchPoolSpec.DEFAULT_LADDER]. */
        val DEFAULT: MatchmakingLadder = MatchmakingLadder(MatchPoolSpec.DEFAULT_LADDER)
    }
}