package structs.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class BillingStatusTest {
    @Test fun `default cycle window is 30 days`() {
        val status = BillingStatus(cycleStartMillis = 1_000_000L)
        assertEquals(1_000_000L + 30L * 24L * 60L * 60L * 1000L, status.effectiveCycleEndMillis)
    }

    @Test fun `tokens used and cap are independently defaulted to zero`() {
        val status = BillingStatus()
        assertEquals(0L, status.tokensUsedThisCycle)
        assertEquals(0L, status.tokensCapPerCycle)
    }

    @Test fun `remainingInferenceMinutes is derived from tokensUsed at the documented rate`() {
        // 1 minute ~ 1500 tokens (avg trace-derived throughput)
        val status = BillingStatus(tokensUsedThisCycle = 150_000L, tokensCapPerCycle = 18_000_000L)
        assertEquals((18_000_000L - 150_000L) / 1500L, status.remainingInferenceMinutes)
    }

    @Test fun `remainingInferenceMinutes floors at zero when cap is reached`() {
        val status = BillingStatus(tokensUsedThisCycle = 18_000_000L, tokensCapPerCycle = 18_000_000L)
        assertEquals(0L, status.remainingInferenceMinutes)
    }

    @Test fun `legacy JSON without token fields deserializes with zero defaults`() {
        // Sanity: encoding a status with old shape and decoding into the new
        // class must default the new fields to zero without throwing.
        val legacy = BillingStatus(credits = 50.0, plan = AccountPlan.JUNTA)
        val encoded = Json.encodeToString(BillingStatus.serializer(), legacy)
        val roundTrip = Json.decodeFromString(BillingStatus.serializer(), encoded)
        assertEquals(0L, roundTrip.tokensUsedThisCycle)
        assertEquals(0L, roundTrip.tokensCapPerCycle)
    }

    @Test fun `tokensCapPerCycle is filled in from the plan when set`() {
        val status = BillingStatus(
            plan = AccountPlan.REPUBLIC,
            tokensCapPerCycle = AccountPlan.REPUBLIC.tokenCapPerCycle,
            tokensUsedThisCycle = 1_000_000L
        )
        assertEquals(18_000_000L, status.tokensCapPerCycle)
        assertTrue(status.tokensUsedThisCycle > 0)
    }
}
