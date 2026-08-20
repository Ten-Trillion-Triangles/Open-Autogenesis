package ui.billing

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke-instantiation test for [UsageOverlay] plus unit tests for the
 * pure [BillingFormatting] helpers.
 *
 * The full meter-rendering flow is verified end-to-end by the Playwright
 * e2e at `browser-smoke/tests/usage-meter-population.spec.mjs` against
 * a live kvisionApp + server-extend. Real DOM instantiation in jsTest
 * is fragile (the element is created lazily on first parent-attachment);
 * the existing `LoadingScreenTest` documents this constraint.
 */
class UsageOverlayRenderTest
{
    /**
     * Verifies that the [UsageOverlay] widget can be constructed without
     * throwing. Catches "the class doesn't compile" regressions early.
     */
    @Test
    fun `UsageOverlay instantiates without throwing`() = runTest {
        val overlay = UsageOverlay()
        assertNotNull(overlay, "UsageOverlay must instantiate")
    }

    /**
     * [BillingFormatting.formatCredits] renders non-negative balances with a
     * thousands separator and 0–2 decimal places.
     */
    @Test
    fun `formatCredits formats non-negative balances with thousands separator`() {
        assertEquals("0", BillingFormatting.formatCredits(0.0))
        assertEquals("1", BillingFormatting.formatCredits(1.0))
        assertEquals("1,234", BillingFormatting.formatCredits(1234.0))
        assertEquals("9,475", BillingFormatting.formatCredits(9475.0))
        assertEquals("10,000", BillingFormatting.formatCredits(10_000.0))
    }

    /**
     * Negative credit deltas (deductions) are formatted with a leading minus
     * and a thousands separator. The function returns just the signed number
     * (no " cr" suffix); the call site adds units where needed.
     */
    @Test
    fun `formatCreditsDelta formats negative deltas with minus sign`() {
        assertEquals("-525", BillingFormatting.formatCreditsDelta(-525.0))
        assertEquals("-1,234", BillingFormatting.formatCreditsDelta(-1234.0))
    }

    /**
     * [BillingFormatting.formatCredits] handles sub-credit values (1 credit
     * = 1000 tokens, so a partial-credit value can be 0.123).
     */
    @Test
    fun `formatCredits handles sub-credit fractional values`() {
        // 123 tokens = 0.123 credits; formatCredits should preserve the fraction
        val formatted = BillingFormatting.formatCredits(0.123)
        assertTrue(formatted.contains("0.12") || formatted.contains("0.13"),
            "Expected 0.123 to round to 0.12 or 0.13, got '$formatted'")
    }
}