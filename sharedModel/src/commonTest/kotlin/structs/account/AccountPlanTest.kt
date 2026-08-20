package structs.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountPlanTest {
    @Test fun `every plan declares a positive token cap`() {
        AccountPlan.entries.forEach { plan ->
            assertTrue(plan.tokenCapPerCycle > 0L, "Plan $plan must have a positive token cap")
        }
    }

    @Test fun `unlock modes ladder in correct order`() {
        assertEquals(setOf(GameMode.MULTIPLAYER), AccountPlan.FREE.unlocksModes)
        assertEquals(setOf(GameMode.MULTIPLAYER, GameMode.ONE_V_ONE), AccountPlan.JUNTA.unlocksModes)
        assertEquals(setOf(GameMode.MULTIPLAYER, GameMode.ONE_V_ONE), AccountPlan.REPUBLIC.unlocksModes)
        assertEquals(setOf(GameMode.MULTIPLAYER, GameMode.ONE_V_ONE, GameMode.ONE_V_THREE), AccountPlan.EMPIRE.unlocksModes)
        assertEquals(GameMode.entries.toSet(), AccountPlan.SOVEREIGN.unlocksModes)
    }

    @Test fun `tier price tags match the research HTML`() {
        assertEquals(0.0, AccountPlan.FREE.pricePerMonthUsd)
        assertEquals(25.0, AccountPlan.JUNTA.pricePerMonthUsd)
        assertEquals(50.0, AccountPlan.REPUBLIC.pricePerMonthUsd)
        assertEquals(75.0, AccountPlan.EMPIRE.pricePerMonthUsd)
        assertEquals(100.0, AccountPlan.SOVEREIGN.pricePerMonthUsd)
    }
}