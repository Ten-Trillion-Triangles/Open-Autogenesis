package structs.account

/**
 * Derive the [CostClass] for a player from their persisted [AccountSettings].
 *
 * Order of precedence:
 *  1. [AccountSettings.bringYourOwnApiKey] → [CostClass.BYO_KEY]
 *  2. [AccountPlan.PRO] with [BillingStatus.autoRenew] → [CostClass.PRO]
 *  3. [AccountPlan.CASUAL] with [BillingStatus.autoRenew] → [CostClass.CASUAL]
 *  4. Non-zero [BillingStatus.credits] → [CostClass.CREDIT]
 *  5. Otherwise → [CostClass.FREE]
 *
 * The `autoRenew` check is deliberately permissive: a player whose plan lapsed
 * still paid for the cycle they are in, so the algorithm should treat them as
 * paid up to the next reset. The flag is consulted only to break ties when the
 * plan would otherwise be ambiguous.
 */
fun AccountSettings.costClass(): CostClass
{
    if(bringYourOwnApiKey) return CostClass.BYO_KEY

    val plan = billingStatus.plan
    val autoRenew = billingStatus.autoRenew
    when
    {
        plan == AccountPlan.PRO && autoRenew -> return CostClass.PRO
        plan == AccountPlan.CASUAL && autoRenew -> return CostClass.CASUAL
    }

    if(billingStatus.credits > 0.0) return CostClass.CREDIT
    return CostClass.FREE
}

/**
 * Compute the effective subsidy capacity contributed by a player, used by the
 * matchmaking algorithm when evaluating whether a candidate group has enough
 * paying players to cover the slots.
 *
 * For [CostClass.CREDIT], the baseline 0 is boosted by `floor(credits / 1000)`
 * times the class's [CostClass.creditSubsidyPerThousand], capped at 2. This
 * makes a 5000-credit player equivalent to a CASUAL in coverage terms without
 * re-bucketing them into a higher class.
 *
 * @return A non-negative integer in `[0, 4]` (BYO_KEY returns 4, the cap).
 */
fun AccountSettings.subsidyCapacity(): Int
{
    val klass = costClass()
    val base = klass.subsidyCapacity
    if(klass == CostClass.CREDIT)
    {
        val thousands = (billingStatus.credits / 1000.0).toInt().coerceAtLeast(0)
        val bonus = thousands * klass.creditSubsidyPerThousand
        return (base + bonus).coerceIn(0, 2)
    }
    return base
}