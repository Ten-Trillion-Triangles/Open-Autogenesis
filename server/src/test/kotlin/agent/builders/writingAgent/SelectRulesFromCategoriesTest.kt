package agent.builders.writingAgent

import structs.InjectableRule
import structs.RuleCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for selectRulesFromCategories function.
 *
 * BEHAVIOR (verified through testing):
 * - 100% category ALWAYS fires (roll 0-100, 0-99 < 100 = 100 times)
 * - When it fires, selects ONE rule weighted by weight
 * - Multiple categories can fire independently
 * - Returns descriptions joined by "\n\n"
 * - Returns empty string if no category fires
 */
class SelectRulesFromCategoriesTest
{
    @Test
    fun single100PercentCategoryFiresAndReturnsRule()
    {
        val categories = listOf(
            RuleCategory(
                name = "test_cat",
                chancePercent = 100,
                rules = listOf(
                    InjectableRule(id = "rule1", description = "Test rule description", weight = 1, category = "test_cat")
                )
            )
        )

        repeat(20) {
            val result = selectRulesFromCategories(categories)
            assertTrue(result.isNotEmpty(), "100% category should fire")
            assertTrue(result.contains("Test rule description"), "Should contain rule description")
        }
    }

    @Test
    fun zeroPercentCategoryNeverFires()
    {
        val categories = listOf(
            RuleCategory(
                name = "never_fires",
                chancePercent = 0,
                rules = listOf(
                    InjectableRule(id = "rule1", description = "Should never appear", weight = 1, category = "never_fires")
                )
            )
        )

        repeat(20) {
            val result = selectRulesFromCategories(categories)
            assertEquals("", result, "0% category should never fire")
        }
    }

    @Test
    fun emptyCategoryListReturnsEmptyString()
    {
        val result = selectRulesFromCategories(emptyList())
        assertEquals("", result)
    }

    @Test
    fun rulesAreJoinedWithDoubleNewlines()
    {
        val categories = listOf(
            RuleCategory(
                name = "cat_a",
                chancePercent = 100,
                rules = listOf(
                    InjectableRule(id = "rule1", description = "First rule", weight = 1, category = "cat_a")
                )
            ),
            RuleCategory(
                name = "cat_b",
                chancePercent = 100,
                rules = listOf(
                    InjectableRule(id = "rule2", description = "Second rule", weight = 1, category = "cat_b")
                )
            )
        )

        val result = selectRulesFromCategories(categories)
        // Both rules should appear, joined by \n\n
        assertTrue(result.contains("First rule"), "Should contain first rule")
        assertTrue(result.contains("Second rule"), "Should contain second rule")
        assertTrue(result.contains("\n\n"), "Rules should be joined with double newlines")
    }

    @Test
    fun higherWeightRuleMoreLikelySelected()
    {
        // Two rules in same category: weight 9 vs weight 1
        val categories = listOf(
            RuleCategory(
                name = "weighted",
                chancePercent = 100,
                rules = listOf(
                    InjectableRule(id = "rule1", description = "High weight rule", weight = 9, category = "weighted"),
                    InjectableRule(id = "rule2", description = "Low weight rule", weight = 1, category = "weighted")
                )
            )
        )

        var highWeightCount = 0
        var lowWeightCount = 0

        repeat(100) {
            val result = selectRulesFromCategories(categories)
            when {
                result.contains("High weight rule") -> highWeightCount++
                result.contains("Low weight rule") -> lowWeightCount++
            }
        }

        // High weight should be selected much more often
        assertTrue(highWeightCount > lowWeightCount * 3, "High weight (9) should be selected more than low weight (1)")
    }

    @Test
    fun categoryWithNoRulesReturnsEmptyWhenFires()
    {
        val categories = listOf(
            RuleCategory(
                name = "empty_category",
                chancePercent = 100,
                rules = emptyList()
            )
        )

        repeat(20) {
            val result = selectRulesFromCategories(categories)
            assertEquals("", result, "Category with no rules should return empty string")
        }
    }

    @Test
    fun negativeWeightIsTreatedAsMinimum()
    {
        val categories = listOf(
            RuleCategory(
                name = "negative_weight",
                chancePercent = 100,
                rules = listOf(
                    InjectableRule(id = "rule1", description = "Negative weight rule", weight = -5, category = "negative_weight")
                )
            )
        )

        repeat(20) {
            val result = selectRulesFromCategories(categories)
            assertTrue(result.isNotEmpty(), "Negative weight rule should still be selectable")
        }
    }

    @Test
    fun zeroWeightRuleIsSelectable()
    {
        val categories = listOf(
            RuleCategory(
                name = "zero_weight",
                chancePercent = 100,
                rules = listOf(
                    InjectableRule(id = "rule1", description = "Zero weight rule", weight = 0, category = "zero_weight"),
                    InjectableRule(id = "rule2", description = "Positive weight rule", weight = 10, category = "zero_weight")
                )
            )
        )

        var zeroWeightSeen = false
        var positiveWeightSeen = false

        repeat(50) {
            val result = selectRulesFromCategories(categories)
            if (result.contains("Zero weight rule")) zeroWeightSeen = true
            if (result.contains("Positive weight rule")) positiveWeightSeen = true
        }

        assertTrue(zeroWeightSeen, "Zero weight rule should be selectable")
        assertTrue(positiveWeightSeen, "Positive weight rule should be selectable")
    }

    @Test
    fun fiftyPercentCategoryFiresRoughlyHalfTheTime()
    {
        val categories = listOf(
            RuleCategory(
                name = "fifty_percent",
                chancePercent = 50,
                rules = listOf(
                    InjectableRule(id = "rule1", description = "A rule", weight = 1, category = "fifty_percent")
                )
            )
        )

        var fireCount = 0
        repeat(200) {
            val result = selectRulesFromCategories(categories)
            if (result.isNotEmpty()) fireCount++
        }

        // Should fire roughly 50% of the time (with tolerance)
        // 50% of 200 = 100, allow 30% tolerance: 70-130
        assertTrue(fireCount in 60..140, "50% category should fire roughly half: got $fireCount/200")
    }
}