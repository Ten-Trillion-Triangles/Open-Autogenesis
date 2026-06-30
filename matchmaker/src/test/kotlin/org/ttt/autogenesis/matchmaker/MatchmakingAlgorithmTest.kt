package org.ttt.autogenesis.matchmaker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Algorithm tests for the cost-optimization matchmaking logic. The cases
 * below lock the behavior against the documented truth table:
 *  - greedy group selection by (subsidy desc, rank asc, credits desc, id asc)
 *  - coverage rejection for 4 / 3 player groups unless subsidy matches or
 *    a BYO_KEY ticket is present
 *  - 2-player pool is permissive by default
 *  - ladder family isolation (a `pvp-4` ticket never pairs with a `pvp-3` one)
 *  - tiebreaks prefer higher subsidy first, then BYO_KEY presence, then
 *    better (lower) rank sum
 */
class MatchmakingAlgorithmTest
{
    @Test
    fun prefersByoKeyThenProWhenFillingFourPlayerGroup()
    {
        val algo = MatchmakingAlgorithm()
        val batch = listOf(
            ticket("t-free-1", "FREE",   subsidy = 0, rank = 4),
            ticket("t-byok",   "BYO_KEY", subsidy = 4, rank = 0, byo = true),
            ticket("t-pro-1",  "PRO",    subsidy = 2, rank = 1),
            ticket("t-pro-2",  "PRO",    subsidy = 2, rank = 1),
            ticket("t-casual", "CASUAL", subsidy = 1, rank = 2)
        )
        val proposals = algo.propose(batch, targetPlayers = 4)
        assertEquals(1, proposals.size, "expected exactly one 4-player proposal")
        val ids = proposals[0].ticketIds.toSet()
        assertEquals(setOf("t-byok", "t-pro-1", "t-pro-2", "t-casual"), ids,
            "expected the greedy selector to fill with BYO_KEY + PRO + PRO + CASUAL (cheapest-to-host mix)")
        assertTrue(proposals[0].hasByoKey)
        assertEquals(9, proposals[0].subsidyTotal)
    }

    @Test
    fun rejectsFourFreePlayerGroupWhenCoverageRequired()
    {
        val algo = MatchmakingAlgorithm()
        val batch = listOf(
            ticket("t-a", "FREE", subsidy = 0, rank = 4),
            ticket("t-b", "FREE", subsidy = 0, rank = 4),
            ticket("t-c", "FREE", subsidy = 0, rank = 4),
            ticket("t-d", "FREE", subsidy = 0, rank = 4)
        )
        val proposals = algo.propose(batch, targetPlayers = 4)
        assertTrue(proposals.isEmpty(),
            "four FREE players must not be matched in a 4-player coverage-required pool")
    }

    @Test
    fun acceptsFourFreeGroupWhenByoKeyExceptionHolds()
    {
        val algo = MatchmakingAlgorithm()
        val batch = listOf(
            ticket("t-free-1", "FREE",   subsidy = 0, rank = 4),
            ticket("t-free-2", "FREE",   subsidy = 0, rank = 4),
            ticket("t-free-3", "FREE",   subsidy = 0, rank = 4),
            ticket("t-byok",   "BYO_KEY", subsidy = 4, rank = 0, byo = true)
        )
        val proposals = algo.propose(batch, targetPlayers = 4)
        assertEquals(1, proposals.size, "BYO_KEY + 3 FREE is acceptable (BYO covers the gap)")
        assertTrue(proposals[0].hasByoKey)
    }

    @Test
    fun twoPlayerPoolIsPermissiveByDefault()
    {
        val algo = MatchmakingAlgorithm()
        val batch = listOf(
            ticket("t-a", "FREE", subsidy = 0, rank = 4),
            ticket("t-b", "FREE", subsidy = 0, rank = 4)
        )
        val proposals = algo.propose(batch, targetPlayers = 2)
        assertEquals(1, proposals.size, "2-player pool is permissive by default — two FREE players should match")
    }

    @Test
    fun isolatesLadderFamilies()
    {
        val algo = MatchmakingAlgorithm()
        val batch = listOf(
            ticket("t-pvp4-1", "PRO",    subsidy = 2, rank = 1, family = "pvp"),
            ticket("t-pvp4-2", "PRO",    subsidy = 2, rank = 1, family = "pvp"),
            ticket("t-pvp4-3", "PRO",    subsidy = 2, rank = 1, family = "pvp"),
            ticket("t-pvp4-4", "PRO",    subsidy = 2, rank = 1, family = "pvp"),
            // 3-player family ticket that arrived in the same batch
            ticket("t-pvp3-1", "BYO_KEY", subsidy = 4, rank = 0, byo = true, family = "pvp3")
        )
        // Use a 3-player target to make sure we never mix the families.
        val proposals = algo.propose(batch, targetPlayers = 3)
        // The pvp3 family only has one ticket — no proposal is possible.
        // The pvp family has 4 PROs; with target=3 and coverage required, the
        // algorithm should refuse (sum=6, ok actually 6 >= 3 so it accepts).
        for(proposal in proposals)
        {
            val ids = proposal.ticketIds.toSet()
            assertTrue(ids.none { it.startsWith("t-pvp3-") },
                "ladder family must not mix: $ids")
        }
    }

    @Test
    fun threePlayerCoverageRejectsThreeFreeWithoutByoKey()
    {
        val policy = AlgorithmPolicy(requireCoverageFor3 = true, requireCoverageFor4 = true, requireCoverageFor2 = false)
        val algo = MatchmakingAlgorithm(policy)
        val batch = listOf(
            ticket("t-a", "FREE", subsidy = 0, rank = 4),
            ticket("t-b", "FREE", subsidy = 0, rank = 4),
            ticket("t-c", "FREE", subsidy = 0, rank = 4)
        )
        val proposals = algo.propose(batch, targetPlayers = 3)
        assertTrue(proposals.isEmpty(),
            "3 FREE players must not match a 3-player coverage-required pool without a BYO_KEY exception")
    }

    @Test
    fun threePlayerCoverageAcceptsProPlusTwoCasual()
    {
        val policy = AlgorithmPolicy(requireCoverageFor3 = true, requireCoverageFor4 = true, requireCoverageFor2 = false)
        val algo = MatchmakingAlgorithm(policy)
        val batch = listOf(
            ticket("t-pro",   "PRO",    subsidy = 2, rank = 1),
            ticket("t-cas-1", "CASUAL", subsidy = 1, rank = 2),
            ticket("t-cas-2", "CASUAL", subsidy = 1, rank = 2)
        )
        val proposals = algo.propose(batch, targetPlayers = 3)
        assertEquals(1, proposals.size, "PRO + 2 CASUAL = subsidy 4 >= 3, should match")
    }

    @Test
    fun largerBatchProducesMultipleProposals()
    {
        val algo = MatchmakingAlgorithm()
        val batch = (1..8).map { i ->
            ticket("t-$i", "PRO", subsidy = 2, rank = 1)
        }
        val proposals = algo.propose(batch, targetPlayers = 4)
        assertEquals(2, proposals.size, "8 PRO tickets should split into two 4-player proposals")
        for(p in proposals)
        {
            assertEquals(8, p.subsidyTotal)
        }
    }

    @Test
    fun insufficientTicketsYieldsNoProposals()
    {
        val algo = MatchmakingAlgorithm()
        val batch = listOf(ticket("t-a", "PRO", subsidy = 2, rank = 1))
        assertFailsWith<IllegalArgumentException> {
            algo.propose(batch, targetPlayers = 4)
        }
    }

    @Test
    fun zeroTargetYieldsEmptyProposals()
    {
        val algo = MatchmakingAlgorithm()
        val batch = listOf(ticket("t-a", "PRO", subsidy = 2, rank = 1))
        assertTrue(algo.propose(batch, targetPlayers = 0).isEmpty())
    }

    @Test
    fun proposeAllReturnsLargestProposalsFirst()
    {
        val algo = MatchmakingAlgorithm()
        val batch = (1..4).map { ticket("t-$it", "PRO", subsidy = 2, rank = 1) }
        val proposals = algo.proposeAll(batch, targets = listOf(4, 3, 2))
        // The 4-player proposal should be tried first; once one is formed, the
        // remaining tickets (none) cannot form a 3-player group.
        assertEquals(1, proposals.size, "expected exactly one proposal at the largest viable target")
        assertEquals(4, proposals[0].maxPlayers)
    }

    private fun ticket(
        id: String,
        costClass: String,
        subsidy: Int,
        rank: Int,
        family: String = "pvp",
        byo: Boolean = false
    ): MatchTicket = MatchTicket(
        ticketId = id,
        matchPool = "pvp-4",
        ladderFamily = family,
        costClass = costClass,
        subsidy = subsidy,
        rank = rank,
        walletCredits = 0.0,
        isByoKey = byo
    )
}
