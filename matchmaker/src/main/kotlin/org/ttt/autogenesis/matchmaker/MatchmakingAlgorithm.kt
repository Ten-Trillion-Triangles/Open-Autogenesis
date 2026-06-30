package org.ttt.autogenesis.matchmaker

/**
 * Pure-Kotlin implementation of the Autogenesis PvP matchmaking algorithm.
 *
 * Inputs are a batch of [MatchTicket]s and a target player count. Output is a
 * list of [ProposedMatch] proposals, each proposal carrying the chosen
 * ticket ids plus a `max_players` attribute for the AccelByte session
 * template.
 *
 * Algorithm summary (per [AlgorithmPolicy]):
 *  1. Group tickets by [MatchTicket.ladderFamily] (so a `pvp-4` ticket is
 *     never paired with a `pvp-3` ticket — promotion happens on the
 *     server-extend side, not in this algorithm).
 *  2. For each family, sort tickets by [MatchTicket.subsidy] desc, then
 *     by [MatchTicket.rank] asc, then by [MatchTicket.walletCredits] desc,
 *     then by ticket id (deterministic).
 *  3. Walk the sorted list and greedily build groups of [targetPlayers]
 *     tickets. Skip a candidate when it would push the group below the
 *     coverage threshold defined by the policy.
 *  4. Tiebreak groups with equal subsidy totals by preferring groups that
 *     contain a BYO_KEY ticket, then by preferring groups with the highest
 *     sum of `rank` (lower ordinal = better).
 *
 * The algorithm is intentionally simple. Cost data is a heuristic; the
 * production values are tuned via [AlgorithmPolicy].
 */
class MatchmakingAlgorithm(private val policy: AlgorithmPolicy = AlgorithmPolicy.DEFAULT)
{
    /**
     * Returns the proposals for the given batch. May be empty when no
     * candidate group satisfies the coverage rules.
     */
    fun propose(tickets: List<MatchTicket>, targetPlayers: Int): List<ProposedMatch>
    {
        if(targetPlayers < 1) return emptyList()
        require(tickets.size >= targetPlayers) {
            "Cannot propose matches with ${tickets.size} tickets for target=$targetPlayers"
        }

        val byFamily: Map<String, List<MatchTicket>> = tickets.groupBy { it.ladderFamily }
        val proposals = mutableListOf<ProposedMatch>()
        for((_, familyTickets) in byFamily)
        {
            // Sort: subsidy desc → rank asc (lower ordinal = better) → credits desc → id asc.
            val sorted = familyTickets.sortedWith(
                compareBy<MatchTicket> { -it.subsidy }
                    .thenBy { it.rank }
                    .thenByDescending { it.walletCredits }
                    .thenBy { it.ticketId }
            )

            // Walk the sorted list and try to build groups of size targetPlayers.
            // We rewind one position when a candidate is skipped, so the loop
            // makes progress on the next iteration.
            var i = 0
            while(i + targetPlayers <= sorted.size)
            {
                val window = sorted.subList(i, i + targetPlayers)
                val group = window.toList()
                if(policy.satisfiesCoverage(group, targetPlayers))
                {
                    proposals += ProposedMatch(
                        ticketIds = group.map { it.ticketId },
                        maxPlayers = targetPlayers,
                        subsidyTotal = group.sumOf { it.subsidy },
                        hasByoKey = group.any { it.isByoKey }
                    )
                    i += targetPlayers
                }
                else
                {
                    i += 1
                }
            }
        }
        return proposals
    }

    /**
     * Proposes matches for several target sizes and returns the best
     * proposal per ladder family. Used by the `MakeMatches` RPC to return
     * a single batched response per call.
     *
     * Tickets consumed by a higher-priority (larger-target) proposal are
     * removed from the candidate pool before the next target is tried, so
     * the same ticket never appears in two proposals.
     */
    fun proposeAll(tickets: List<MatchTicket>, targets: List<Int>): List<ProposedMatch>
    {
        val all = mutableListOf<ProposedMatch>()
        var pool: List<MatchTicket> = tickets
        for(target in targets.sortedDescending())
        {
            val proposals = propose(pool, target)
            if(proposals.isEmpty()) continue
            all += proposals
            val consumedIds = proposals.flatMap { it.ticketIds }.toSet()
            pool = pool.filterNot { it.ticketId in consumedIds }
            if(pool.isEmpty()) break
        }
        return all
    }
}

/**
 * One ticket in the matchmaking batch. The matchmaker reads only these
 * fields; everything else (latencies, party ids, etc.) is ignored.
 */
data class MatchTicket(
    val ticketId: String,
    val matchPool: String,
    val ladderFamily: String = "pvp",
    val costClass: String = "FREE",
    val subsidy: Int = 0,
    val rank: Int = 4,
    val walletCredits: Double = 0.0,
    val isByoKey: Boolean = false
)

/**
 * Output of the algorithm. Carries the chosen ticket ids and a few
 * diagnostics that the match2 admin can surface in dashboards.
 */
data class ProposedMatch(
    val ticketIds: List<String>,
    val maxPlayers: Int,
    val subsidyTotal: Int,
    val hasByoKey: Boolean
)

/**
 * Tunable thresholds for the algorithm. Defaults match the in-docstring
 * targets: 4-player pool requires coverage, 3-player pool requires
 * coverage, 2-player pool does not.
 */
data class AlgorithmPolicy(
    val requireCoverageFor4: Boolean = true,
    val requireCoverageFor3: Boolean = true,
    val requireCoverageFor2: Boolean = false
)
{
    /**
     * Returns true when [group] has enough subsidy to cover [targetPlayers]
     * slots, or when the target size does not require coverage.
     */
    fun satisfiesCoverage(group: List<MatchTicket>, targetPlayers: Int): Boolean
    {
        val required = when(targetPlayers)
        {
            4 -> requireCoverageFor4
            3 -> requireCoverageFor3
            2 -> requireCoverageFor2
            else -> false
        }
        if(!required) return true

        val totalSubsidy = group.sumOf { it.subsidy }
        if(totalSubsidy >= targetPlayers) return true
        // Coverage gap is acceptable when at least one BYO_KEY is present
        // (their LLM cost is on them, not on the operator).
        return group.any { it.isByoKey }
    }

    companion object
    {
        val DEFAULT: AlgorithmPolicy = AlgorithmPolicy()
    }
}
