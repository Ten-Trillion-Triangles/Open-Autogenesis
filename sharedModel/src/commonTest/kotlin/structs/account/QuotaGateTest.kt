package structs.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuotaGateTest
{
    private fun settings(plan: AccountPlan, used: Long): AccountSettings
    {
        val now = 1_700_000_000_000L
        val cap = plan.tokenCapPerCycle
        return AccountSettings(
            billingStatus = BillingStatus(
                plan = plan,
                tokensCapPerCycle = cap,
                tokensUsedThisCycle = used,
                cycleStartMillis = now - 5L * 24 * 3600 * 1000,
                cycleEndMillis = now + 25L * 24 * 3600 * 1000
            )
        )
    }

    @Test fun `developer mode bypasses every quota check`() {
        val s = settings(AccountPlan.FREE, used = 1_999_999L) // nearly at cap
        val d = QuotaGate.evaluate(s, GameMode.ONE_V_THREE, isGameStart = true, isSinglePlayer = true, isDeveloper = true)
        assertIs<QuotaDecision.Allow>(d)
    }

    @Test fun `under 80 percent usage returns Allow`() {
        val s = settings(AccountPlan.REPUBLIC, used = 1_000_000L) // ~5.5% of 18M
        val d = QuotaGate.evaluate(s, GameMode.MULTIPLAYER, isGameStart = true, isSinglePlayer = false, isDeveloper = false)
        assertIs<QuotaDecision.Allow>(d)
    }

    @Test fun `between 80 and 100 percent returns SoftWarn but the caller treats it as Allow`() {
        // 90% of 18M = 16.2M
        val s = settings(AccountPlan.REPUBLIC, used = 16_200_000L)
        val d = QuotaGate.evaluate(s, GameMode.MULTIPLAYER, isGameStart = true, isSinglePlayer = false, isDeveloper = false)
        val warn = assertIs<QuotaDecision.SoftWarn>(d)
        assertTrue(warn.usedFraction > 0.8)
        assertTrue(warn.remainingTokens > 0L)
    }

    @Test fun `multiplayer at cap with isGameStart false returns Allow (in-progress game continues)`() {
        val s = settings(AccountPlan.REPUBLIC, used = 18_000_000L)
        val d = QuotaGate.evaluate(s, GameMode.MULTIPLAYER, isGameStart = false, isSinglePlayer = false, isDeveloper = false)
        assertIs<QuotaDecision.Allow>(d)
    }

    @Test fun `multiplayer at cap with isGameStart true returns BlockNewGame`() {
        val s = settings(AccountPlan.REPUBLIC, used = 18_000_000L)
        val d = QuotaGate.evaluate(s, GameMode.MULTIPLAYER, isGameStart = true, isSinglePlayer = false, isDeveloper = false)
        assertIs<QuotaDecision.BlockNewGame>(d)
    }

    @Test fun `single-player at cap with isGameStart true returns InterruptAndEnd`() {
        val s = settings(AccountPlan.SOVEREIGN, used = 60_000_000L)
        val d = QuotaGate.evaluate(s, GameMode.ONE_V_THREE, isGameStart = true, isSinglePlayer = true, isDeveloper = false)
        assertIs<QuotaDecision.InterruptAndEnd>(d)
    }

    @Test fun `plan does not unlock the requested mode returns BlockNewGame`() {
        val s = settings(AccountPlan.FREE, used = 0L)
        val d = QuotaGate.evaluate(s, GameMode.ONE_V_ONE, isGameStart = true, isSinglePlayer = true, isDeveloper = false)
        assertIs<QuotaDecision.BlockNewGame>(d)
    }

    @Test fun `FREE plan does not unlock ONE_V_THREE even at zero usage`() {
        val s = settings(AccountPlan.FREE, used = 0L)
        val d = QuotaGate.evaluate(s, GameMode.ONE_V_THREE, isGameStart = true, isSinglePlayer = true, isDeveloper = false)
        assertIs<QuotaDecision.BlockNewGame>(d)
    }

    @Test fun `uninitialised cycle (cap zero) returns Allow for unlocked mode`() {
        // First-ever access: no cap has been written yet, but the player
        // owns a paying plan. QuotaGate must not block uninitialised cycles.
        val s = AccountSettings(
            billingStatus = BillingStatus(
                plan = AccountPlan.REPUBLIC,
                tokensCapPerCycle = 0L,
                tokensUsedThisCycle = 0L
            )
        )
        val d = QuotaGate.evaluate(s, GameMode.MULTIPLAYER, isGameStart = true, isSinglePlayer = false, isDeveloper = false)
        assertIs<QuotaDecision.Allow>(d)
    }
}