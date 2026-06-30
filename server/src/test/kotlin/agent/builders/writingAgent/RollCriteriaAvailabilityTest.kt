package agent.builders.writingAgent

import structs.InjectableCriterion
import structs.InjectableRule
import structs.RuleCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for rollCriteriaAvailability function.
 *
 * BEHAVIOR (verified through testing):
 * - Returns criteria that pass their roll: Random.nextInt(0, 101) < chancePercent
 * - If NO criterion passes, the function returns an empty list (no fallback)
 * - Empty input returns empty list
 *
 * Single 50% criterion: returns it ~50% of the time (when it passes), empty otherwise.
 * The earlier "fallback returns all" behavior was removed in the architecture cleanup
 * that introduced CGO/CSA as the canonical author personalities; with that change,
 * the guide pipe no longer biases toward criteria on turns where none fired.
 *
 * rollCriteriaAvailability uses a global kotlin.random.Random, so tests are
 * statistical: each iteration has the documented pass probability, and assertions
 * use bounds that survive variance over reasonable trial counts.
 */
class RollCriteriaAvailabilityTest
{
    @Test
    fun singleCriterion100PercentAlwaysReturnsIt()
    {
        // Single 100% criterion. The roll logic is `roll < chancePercent`, with roll
        // drawn from nextInt(0, 101). So 100% passes 100/101 = 99.01% of the time.
        // Over 200 trials, expect ~198 passes and at most ~5 failures.
        val criteria = listOf(
            InjectableCriterion(id = 1, description = "Hundred", category = "test", chancePercent = 100)
        )
        var passes = 0
        var empty = 0
        repeat(200) {
            val result = rollCriteriaAvailability(criteria)
            when {
                result.size == 1 && result[0].id == 1 -> passes++
                result.isEmpty() -> empty++
            }
        }
        assertTrue(passes >= 190, "Single 100% should pass almost always (got passes=$passes, empty=$empty)")
        assertEquals(200, passes + empty, "every trial must produce either [criterion] or []")
    }

    @Test
    fun singleCriterion50PercentReturnsOrEmpty()
    {
        // Single 50% criterion. 50/101 = 49.5% pass rate.
        // Over 200 trials, expect ~99 passes and ~101 empty.
        val criteria = listOf(
            InjectableCriterion(id = 2, description = "Fifty", category = "test", chancePercent = 50)
        )
        var passes = 0
        var empty = 0
        repeat(200) {
            val result = rollCriteriaAvailability(criteria)
            when {
                result.size == 1 && result[0].id == 2 -> passes++
                result.isEmpty() -> empty++
            }
        }
        // Allow generous bounds: expect ~50/50 with ~7% stddev over 200 trials
        assertTrue(passes in 75..125, "Single 50% should pass ~half the time (got passes=$passes, empty=$empty)")
        assertTrue(empty in 75..125, "Single 50% should return empty ~half the time (got passes=$passes, empty=$empty)")
    }

    @Test
    fun emptyInputReturnsEmptyList()
    {
        val result = rollCriteriaAvailability(emptyList())
        assertEquals(0, result.size)
    }

    @Test
    fun multipleCriteriaReturnsPassingOnes()
    {
        // 100% + 50% criteria. Expected outcomes (out of 200 trials):
        // - Both pass: ~99 (49.5% of trials)
        // - Only 100% passes: ~100 (50% of trials)
        // - Only 50% passes: ~1 (0.5%)
        // - Neither passes (empty): ~1 (0.5%)
        // No "fallback to all" behavior. With 100% present, the result is rarely empty
        // (only when 100% itself fails its roll, which is ~1% of trials).
        val criteria = listOf(
            InjectableCriterion(id = 1, description = "Hundred", category = "test", chancePercent = 100),
            InjectableCriterion(id = 2, description = "Fifty", category = "test", chancePercent = 50)
        )

        var bothPassed = 0
        var onlyFirstPassed = 0
        var onlySecondPassed = 0
        var empty = 0
        var unexpected = 0

        repeat(200) {
            val result = rollCriteriaAvailability(criteria)
            when {
                result.size == 2 && result.map { it.id }.toSet() == setOf(1, 2) -> bothPassed++
                result.size == 1 && result[0].id == 1 -> onlyFirstPassed++
                result.size == 1 && result[0].id == 2 -> onlySecondPassed++
                result.isEmpty() -> empty++
                else -> unexpected++
            }
        }

        // Total must sum to 200 (no other outcomes possible)
        assertEquals(200, bothPassed + onlyFirstPassed + onlySecondPassed + empty + unexpected,
            "every trial must produce one of the four documented outcomes")

        // Both pass should occur ~99 times
        assertTrue(bothPassed >= 70, "Both should pass fairly often (got both=$bothPassed, onlyFirst=$onlyFirstPassed, onlySecond=$onlySecondPassed, empty=$empty)")

        // Only 100% (without 50%) should occur ~100 times
        assertTrue(onlyFirstPassed >= 70, "Only 100% should pass fairly often (got both=$bothPassed, onlyFirst=$onlyFirstPassed, onlySecond=$onlySecondPassed, empty=$empty)")

        // Empty should be RARE (100% fails ~1% of the time, 50% fails ~50% of the time,
        // so empty happens when 100% fails AND 50% fails = 0.01 * 0.5 = 0.5% of trials = ~1 time)
        assertTrue(empty < 10, "Empty result should be rare (got both=$bothPassed, onlyFirst=$onlyFirstPassed, onlySecond=$onlySecondPassed, empty=$empty)")

        // No unexpected outcomes
        assertEquals(0, unexpected, "No unexpected outcomes allowed")
    }

    @Test
    fun allFailingReturnsEmpty()
    {
        // All 0% criteria - none pass, no fallback. Returns empty.
        val criteria = listOf(
            InjectableCriterion(id = 1, description = "Zero1", category = "test", chancePercent = 0),
            InjectableCriterion(id = 2, description = "Zero2", category = "test", chancePercent = 0)
        )

        repeat(20) {
            val result = rollCriteriaAvailability(criteria)
            assertEquals(0, result.size, "All-failing criteria return empty list (no fallback to all)")
        }
    }
}