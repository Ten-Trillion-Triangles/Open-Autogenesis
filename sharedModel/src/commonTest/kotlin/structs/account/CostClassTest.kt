package structs.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the [AccountSettings.costClass] and [AccountSettings.subsidyCapacity]
 * derivations against the documented truth table. The matchmaking algorithm
 * in [org.ttt.autogenesis.matchmaker.MatchmakingAlgorithm] depends on the
 * exact mapping (rank order, subsidy capacities, BYO precedence).
 */
class CostClassTest
{
    @Test
    fun byoKeyTakesPrecedenceOverEverythingElse()
    {
        val settings = AccountSettings(
            bringYourOwnApiKey = true,
            billingStatus = BillingStatus(plan = AccountPlan.PRO, autoRenew = true, credits = 999_999.0)
        )
        assertEquals(CostClass.BYO_KEY, settings.costClass())
        assertEquals(4, settings.subsidyCapacity())
    }

    @Test
    fun proPlanWithAutoRenewYieldsProClass()
    {
        val settings = AccountSettings(
            billingStatus = BillingStatus(plan = AccountPlan.PRO, autoRenew = true, credits = 0.0)
        )
        assertEquals(CostClass.PRO, settings.costClass())
        assertEquals(2, settings.subsidyCapacity())
    }

    @Test
    fun proPlanWithoutAutoRenewFallsThroughToCreditOrFree()
    {
        val withCredits = AccountSettings(
            billingStatus = BillingStatus(plan = AccountPlan.PRO, autoRenew = false, credits = 500.0)
        )
        assertEquals(CostClass.CREDIT, withCredits.costClass())

        val withoutCredits = AccountSettings(
            billingStatus = BillingStatus(plan = AccountPlan.PRO, autoRenew = false, credits = 0.0)
        )
        assertEquals(CostClass.FREE, withoutCredits.costClass())
    }

    @Test
    fun casualPlanWithAutoRenewYieldsCasualClass()
    {
        val settings = AccountSettings(
            billingStatus = BillingStatus(plan = AccountPlan.CASUAL, autoRenew = true, credits = 0.0)
        )
        assertEquals(CostClass.CASUAL, settings.costClass())
        assertEquals(1, settings.subsidyCapacity())
    }

    @Test
    fun zeroCreditsYieldsFree()
    {
        val settings = AccountSettings(
            billingStatus = BillingStatus(plan = AccountPlan.FREE, autoRenew = false, credits = 0.0)
        )
        assertEquals(CostClass.FREE, settings.costClass())
        assertEquals(0, settings.subsidyCapacity())
    }

    @Test
    fun creditClassBoostsSubsidyPerThousandAndCapsAtTwo()
    {
        val two = AccountSettings(billingStatus = BillingStatus(credits = 2_000.0))
        assertEquals(CostClass.CREDIT, two.costClass())
        // 2_000 / 1_000 * 1 = 2, capped at 2
        assertEquals(2, two.subsidyCapacity())

        val five = AccountSettings(billingStatus = BillingStatus(credits = 5_000.0))
        assertEquals(2, five.subsidyCapacity(), "credit subsidy must cap at 2 even at 5000 credits")

        val tiny = AccountSettings(billingStatus = BillingStatus(credits = 999.0))
        assertEquals(0, tiny.subsidyCapacity(), "999 credits is below the 1k threshold")
    }

    @Test
    fun rankOrderMatchesDeclarationOrder()
    {
        val expected = listOf("BYO_KEY", "PRO", "CASUAL", "CREDIT", "FREE")
        val actual = CostClass.entries.map { it.name }
        assertEquals(expected, actual, "CostClass rank order must not change — the matchmaking algorithm depends on it")
        // Sanity: each class has a distinct rank, lowest is BYO_KEY.
        assertTrue(CostClass.BYO_KEY.rank < CostClass.PRO.rank)
        assertTrue(CostClass.PRO.rank < CostClass.CASUAL.rank)
        assertTrue(CostClass.CASUAL.rank < CostClass.CREDIT.rank)
        assertTrue(CostClass.CREDIT.rank < CostClass.FREE.rank)
    }
}
