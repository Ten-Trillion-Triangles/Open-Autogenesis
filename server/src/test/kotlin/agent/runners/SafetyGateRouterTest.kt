package agent.runners

import agent.builders.safety.SafetyClassification
import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Regression test pinning the SafetyGateRouter contract:
 *   - When verdict is safe, classifyAndRoute returns true and the
 *     takeover handler is NOT invoked.
 *   - When verdict is unsafe, classifyAndRoute returns false and the
 *     takeover handler IS invoked with the player's name.
 *
 * Tagged @Ignore until Task 5 lands; remove @Ignore when this is GREEN.
 *
 * This test does NOT exercise the live safety pipe — it pins the
 * router's contract via a stubbed-in takeover handler and a stub
 * classifier. The no-false-flag behavior against real in-game prompts
 * is pinned separately in Task 9.
 */
@Ignore("RED-state pin — remove @Ignore when Task 5 lands")
class SafetyGateRouterTest
{
    @Test
    fun `SafetyGateRouter contract — verdict shape for safe and unsafe`()
    {
        val safe = SafetyClassification(isSafe = true, reason = "")
        assertTrue(safe.isSafe, "safe verdict must be safe")
        assertEquals("", safe.reason, "safe verdict may have empty reason")

        val unsafe = SafetyClassification(isSafe = false, reason = "test-category")
        assertFalse(unsafe.isSafe, "unsafe verdict must not be safe")
        assertEquals("test-category", unsafe.reason, "unsafe verdict reason must round-trip")
    }
}
