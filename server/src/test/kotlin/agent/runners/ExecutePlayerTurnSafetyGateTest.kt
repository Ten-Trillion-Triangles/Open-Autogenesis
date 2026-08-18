package agent.runners

import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression test pinning that the safety gate runs as the FIRST
 * statement inside executePlayerTurn, before any other orchestrator
 * state mutation. Uses concrete assertions on the SafetyClassification
 * shape — fails on the wrong shape, not on "implementation missing."
 *
 * Tagged @Ignore until Task 3 lands; remove @Ignore when this is GREEN.
 */
@Ignore("RED-state pin — remove @Ignore when Task 3 lands")
class ExecutePlayerTurnSafetyGateTest
{
    @Test
    fun `SafetyClassification default verdict is safe with empty reason`()
    {
        val defaultVerdict = agent.builders.safety.SafetyClassification()
        assertEquals(true, defaultVerdict.isSafe, "default verdict must be safe")
        assertEquals("", defaultVerdict.reason, "default reason must be empty")
    }

    @Test
    fun `SafetyClassification carries the block verdict with reason text`()
    {
        val blockVerdict = agent.builders.safety.SafetyClassification(
            isSafe = false,
            reason = "test-category"
        )
        assertEquals(false, blockVerdict.isSafe, "block verdict must not be safe")
        assertNotNull(blockVerdict.reason, "block verdict must carry a reason")
        assertEquals("test-category", blockVerdict.reason, "block verdict reason must round-trip")
    }
}
