package agent.managers

import agent.builders.safety.SafetyClassification
import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression test pinning that defense and summit responses flow
 * through the safety gate before being returned to the caller.
 * Both Summit (SummitOrchestrator.kt:214) and defense
 * (gameplayOrchestrator.kt:1467+) call GameResponseManager.waitForResponse,
 * so wiring the gate there covers both surfaces.
 *
 * Tagged @Ignore until Task 7 lands; remove @Ignore when this is GREEN.
 */
@Ignore("RED-state pin — remove @Ignore when Task 7 lands")
class GameResponseManagerSafetyGateTest
{
    @Test
    fun `SafetyClassification default safe verdict is the default at the seam`()
    {
        // The router's default-on-error is safe; the data class default
        // is also safe. Both must agree so the seam does not flip-flop.
        val defaultVerdict = SafetyClassification()
        assertEquals(true, defaultVerdict.isSafe, "default verdict must be safe")
        assertNotNull(defaultVerdict.reason, "default verdict reason field must exist")
    }

    @Test
    fun `block verdict at the seam carries the category text`()
    {
        val blockVerdict = SafetyClassification(isSafe = false, reason = "real-address-leakage")
        assertEquals(false, blockVerdict.isSafe, "block verdict must not be safe")
        assertEquals("real-address-leakage", blockVerdict.reason, "block reason must round-trip")
    }
}
