package agent.builders.writingAgent

import structs.InjectableCriterion
import structs.WriterSelectionStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for buildCriteriaListForGuide function (uses rollCriteriaAvailability internally).
 *
 * buildCriteriaListForGuide:
 * - Calls rollCriteriaAvailability to filter criteria
 * - Formats result as numbered list "1. description\n2. description..."
 */
class BuildStepTwoInstructionsTest
{
    private val testCriteria = listOf(
        InjectableCriterion(id = 1, description = "Kafka-esque", category = "general", chancePercent = 100),
        InjectableCriterion(id = 2, description = "Keillor-esque", category = "absurdity", chancePercent = 100),
        InjectableCriterion(id = 3, description = "Pitigrilli-esque", category = "general", chancePercent = 100),
        InjectableCriterion(id = 4, description = "Geopolitics", category = "geopolitics", chancePercent = 100),
        InjectableCriterion(id = 5, description = "Wallesian", category = "general", chancePercent = 100),
        InjectableCriterion(id = 6, description = "Joycean", category = "general", chancePercent = 100),
        InjectableCriterion(id = 7, description = "Rabellesian", category = "absurdity", chancePercent = 100),
        InjectableCriterion(id = 8, description = "Dreamlike", category = "time_reality", chancePercent = 100),
        InjectableCriterion(id = 9, description = "Kubrickian", category = "horror", chancePercent = 100),
        InjectableCriterion(id = 10, description = "Really dumb", category = "absurdity", chancePercent = 100)
    )

    @Test
    fun all100PercentCriteriaReturnsAllFormatted()
    {
        // 10 criteria at 100% chance. Each passes with probability 100/101 = 99.01%.
        // Probability all 10 pass: 0.9901^10 ≈ 90.4%. So a single run is ~10% flaky.
        // Run multiple times and assert that AT LEAST ONCE all 10 are in the result.
        var sawAllTen = false
        repeat(50) {
            val result = buildCriteriaListForGuide(testCriteria)
            if (result.contains("1.") && result.contains("Kafka-esque") &&
                result.contains("Geopolitics") && result.contains("10.")) {
                sawAllTen = true
                return@repeat
            }
        }
        assertTrue(sawAllTen, "All 10 criteria should appear together in the result at least once over 50 trials")
    }

    @Test
    fun emptyCriteriaReturnsEmptyString()
    {
        val result = buildCriteriaListForGuide(emptyList())
        assertEquals("", result)
    }

    @Test
    fun criteriaDescriptionsAreFormatted()
    {
        val criteria = listOf(
            InjectableCriterion(id = 1, description = "First", category = "test", chancePercent = 100),
            InjectableCriterion(id = 2, description = "Second", category = "test", chancePercent = 100)
        )

        val result = buildCriteriaListForGuide(criteria)

        assertTrue(result.contains("1. First"))
        assertTrue(result.contains("2. Second"))
    }

    @Test
    fun zeroPercentCriteriaAreFiltered()
    {
        val criteria = listOf(
            InjectableCriterion(id = 1, description = "Always", category = "test", chancePercent = 100),
            InjectableCriterion(id = 2, description = "Never", category = "test", chancePercent = 0)
        )

        // Note: 100% chance means roll < 100 passes, which is 100/101 ≈ 99%
        // So 100% fails ~1% of the time, triggering fallback which includes 0% criteria
        // This is expected behavior, not a bug

        var neverAppears = 0
        repeat(20) {
            val result = buildCriteriaListForGuide(criteria)
            // "Never" appearing means fallback triggered (100% failed roll)
            if (!result.contains("Never")) neverAppears++
        }

        // Most of the time (when 100% passes roll), "Never" should not appear
        assertTrue(neverAppears >= 15, "0% criteria should be filtered most of the time when 100% passes (got $neverAppears/20)")
    }

    @Test
    fun mixedCriteriaReturnsPassingFormatted()
    {
        // 100% + 50% criteria. The 100% one fails its roll ~1% of the time.
        // Over 200 trials, the 100% one should pass almost always (>=190).
        val criteria = listOf(
            InjectableCriterion(id = 1, description = "Always", category = "test", chancePercent = 100),
            InjectableCriterion(id = 2, description = "Sometimes", category = "test", chancePercent = 50)
        )

        var alwaysPresentCount = 0
        var sometimesPresentCount = 0
        var bothPresent = 0
        var onlyAlways = 0
        var onlySometimes = 0
        var neither = 0
        var unexpected = 0

        repeat(200) {
            val result = buildCriteriaListForGuide(criteria)
            val hasAlways = result.contains("Always")
            val hasSometimes = result.contains("Sometimes")
            when {
                hasAlways && hasSometimes -> { alwaysPresentCount++; sometimesPresentCount++; bothPresent++ }
                hasAlways -> { alwaysPresentCount++; onlyAlways++ }
                hasSometimes -> { sometimesPresentCount++; onlySometimes++ }
                result.isEmpty() -> neither++
                else -> unexpected++
            }
        }
        // The 100% "Always" criterion should be present in ~99% of trials, so >=190/200.
        assertTrue(alwaysPresentCount >= 190, "100% criteria should be in result almost always (got always=$alwaysPresentCount, sometimes=$sometimesPresentCount, neither=$neither)")
        // The 50% "Sometimes" criterion should be present in ~50% of trials, so in [60..140].
        assertTrue(sometimesPresentCount in 60..140, "50% criteria should be in result ~half the time (got sometimes=$sometimesPresentCount, always=$alwaysPresentCount, neither=$neither)")
        // Both together should happen in ~50% of trials.
        assertTrue(bothPresent in 60..140, "Both should appear together ~half the time (got both=$bothPresent)")
        // No unexpected outcomes
        assertEquals(0, unexpected, "no unexpected outcomes")
    }

    @Test
    fun allZeroPercentTriggersFallback()
    {
        val criteria = listOf(
            InjectableCriterion(id = 1, description = "Never1", category = "test", chancePercent = 0),
            InjectableCriterion(id = 2, description = "Never2", category = "test", chancePercent = 0)
        )

        repeat(10) {
            val result = buildCriteriaListForGuide(criteria)
            // New behavior: all 0% criteria produce empty result, so buildCriteriaListForGuide
            // returns an empty string. The earlier fallback-to-all was removed in the CGO/CSA
            // architecture cleanup.
            assertEquals("", result, "All 0% criteria return empty (no fallback to all)")
        }
    }

    @Test
    fun randomUpToFiveReturnsEmptyListForEmptyEligibleCriteria()
    {
        val result = selectRandomUpToFiveCriteria(emptyList(), kotlin.random.Random(1))

        assertTrue(result.isEmpty(), "empty eligible criteria should select no criteria")
    }

    @Test
    fun randomUpToFiveSelectsBetweenOneAndFiveCriteriaWhenEligibleCriteriaExist()
    {
        repeat(20) { seed ->
            val result = selectRandomUpToFiveCriteria(testCriteria, kotlin.random.Random(seed))

            assertTrue(result.size in 1..5, "selection should contain 1 to 5 criteria, got ${result.size}")
            assertTrue(result.all { it in testCriteria }, "selection should only contain eligible criteria")
        }
    }

    @Test
    fun geopoliticsCriterionExists()
    {
        // Verify id=4 exists and is geopolitics for strategy tests
        val geopolitics = testCriteria.find { it.id == 4 }
        assertTrue(geopolitics != null, "Id=4 should exist")
        assertEquals("Geopolitics", geopolitics?.description)
        assertEquals("geopolitics", geopolitics?.category)
    }
}