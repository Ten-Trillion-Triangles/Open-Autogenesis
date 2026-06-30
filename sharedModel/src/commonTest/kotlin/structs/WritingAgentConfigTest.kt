package structs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * Tests for WritingAgentConfig serialization behavior.
 */
class WritingAgentConfigTest
{
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun `should serialize and deserialize ruleCategories with chancePercent`()
    {
        val config = WritingAgentConfig(
            ruleCategories = listOf(
                RuleCategory(name = "Military", chancePercent = 30, rules = listOf(
                    InjectableRule(id = "rule1", description = "Aggressive stance", weight = 5, category = "Military")
                ))
            )
        )

        val encoded = json.encodeToString(WritingAgentConfig.serializer(), config)
        val decoded = json.decodeFromString(WritingAgentConfig.serializer(), encoded)

        assertEquals(1, decoded.ruleCategories.size)
        assertEquals("Military", decoded.ruleCategories[0].name)
        assertEquals(30, decoded.ruleCategories[0].chancePercent)
    }

    @Test
    fun `should serialize and deserialize storyWeights correctly`()
    {
        val config = WritingAgentConfig(
            storyWeights = StoryWeights(geopolitics = 40, absurdity = 20, dreamlikeQualities = 25, unexpectedTwists = 15)
        )

        val encoded = json.encodeToString(WritingAgentConfig.serializer(), config)
        val decoded = json.decodeFromString(WritingAgentConfig.serializer(), encoded)

        assertEquals(40, decoded.storyWeights.geopolitics)
        assertEquals(20, decoded.storyWeights.absurdity)
        assertEquals(25, decoded.storyWeights.dreamlikeQualities)
        assertEquals(15, decoded.storyWeights.unexpectedTwists)
    }

    @Test
    fun `should serialize and deserialize selectionStrategy correctly`()
    {
        val configOriginal = WritingAgentConfig(selectionStrategy = WriterSelectionStrategy.WEIGHTED)

        val encoded = json.encodeToString(WritingAgentConfig.serializer(), configOriginal)
        val decoded = json.decodeFromString(WritingAgentConfig.serializer(), encoded)

        assertEquals(WriterSelectionStrategy.WEIGHTED, decoded.selectionStrategy)
    }

    @Test
    fun `should serialize and deserialize authorEnabled flag correctly`()
    {
        val configOriginal = WritingAgentConfig(authorEnabled = false)

        val encoded = json.encodeToString(WritingAgentConfig.serializer(), configOriginal)
        val decoded = json.decodeFromString(WritingAgentConfig.serializer(), encoded)

        assertEquals(false, decoded.authorEnabled)
    }

    @Test
    fun `should serialize and deserialize alwaysApplyRulesEnabled flag correctly`()
    {
        val configOriginal = WritingAgentConfig(alwaysApplyRulesEnabled = false)

        val encoded = json.encodeToString(WritingAgentConfig.serializer(), configOriginal)
        val decoded = json.decodeFromString(WritingAgentConfig.serializer(), encoded)

        assertEquals(false, decoded.alwaysApplyRulesEnabled)
    }

    @Test
    fun `should serialize and deserialize guardrailsEnabled flag correctly`()
    {
        val configOriginal = WritingAgentConfig(guardrailsEnabled = false)

        val encoded = json.encodeToString(WritingAgentConfig.serializer(), configOriginal)
        val decoded = json.decodeFromString(WritingAgentConfig.serializer(), encoded)

        assertEquals(false, decoded.guardrailsEnabled)
    }

    @Test
    fun `should serialize and deserialize alwaysApplyRules list correctly`()
    {
        val configOriginal = WritingAgentConfig(
            alwaysApplyRules = listOf("rule_a", "rule_b", "rule_c")
        )

        val encoded = json.encodeToString(WritingAgentConfig.serializer(), configOriginal)
        val decoded = json.decodeFromString(WritingAgentConfig.serializer(), encoded)

        assertEquals(3, decoded.alwaysApplyRules.size)
        assertEquals("rule_a", decoded.alwaysApplyRules[0])
        assertEquals("rule_b", decoded.alwaysApplyRules[1])
        assertEquals("rule_c", decoded.alwaysApplyRules[2])
    }

    @Test
    fun `should serialize and deserialize selectionCriteria with chancePercent`()
    {
        val configOriginal = WritingAgentConfig(
            selectionCriteria = listOf(
                InjectableCriterion(id = 1, description = "Test criterion", category = "Test", chancePercent = 75),
                InjectableCriterion(id = 2, description = "Another criterion", category = "Test", chancePercent = 50)
            )
        )

        val encoded = json.encodeToString(WritingAgentConfig.serializer(), configOriginal)
        val decoded = json.decodeFromString(WritingAgentConfig.serializer(), encoded)

        assertEquals(2, decoded.selectionCriteria.size)
        assertEquals(75, decoded.selectionCriteria[0].chancePercent)
        assertEquals(50, decoded.selectionCriteria[1].chancePercent)
    }

    @Test
    fun `should serialize and deserialize authorPersonality correctly`()
    {
        val configOriginal = WritingAgentConfig(authorPersonality = "Sarcastic and cunning")

        val encoded = json.encodeToString(WritingAgentConfig.serializer(), configOriginal)
        val decoded = json.decodeFromString(WritingAgentConfig.serializer(), encoded)

        assertEquals("Sarcastic and cunning", decoded.authorPersonality)
    }

    @Test
    fun `should serialize and deserialize writingInstructions correctly`()
    {
        val configOriginal = WritingAgentConfig(writingInstructions = "Focus on dramatic tension")

        val encoded = json.encodeToString(WritingAgentConfig.serializer(), configOriginal)
        val decoded = json.decodeFromString(WritingAgentConfig.serializer(), encoded)

        assertEquals("Focus on dramatic tension", decoded.writingInstructions)
    }

    @Test
    fun `should preserve all fields through roundtrip serialization`()
    {
        val configOriginal = WritingAgentConfig(
            ruleCategories = listOf(
                RuleCategory(name = "Diplomacy", chancePercent = 25, rules = listOf(
                    InjectableRule(id = "d1", description = "Peaceful negotiation", weight = 3, category = "Diplomacy")
                ))
            ),
            alwaysApplyRules = listOf("global_rule_1"),
            authorPersonality = "Wise and ancient",
            selectionCriteria = listOf(
                InjectableCriterion(id = 10, description = "Mystery element", category = "Atmosphere", chancePercent = 60)
            ),
            storyWeights = StoryWeights(30, 25, 25, 20),
            selectionStrategy = WriterSelectionStrategy.RANDOM_UP_TO_FIVE,
            authorEnabled = true,
            alwaysApplyRulesEnabled = true,
            guardrailsEnabled = false,
            writingInstructions = "Create suspenseful narrative"
        )

        val encoded = json.encodeToString(WritingAgentConfig.serializer(), configOriginal)
        val decoded = json.decodeFromString(WritingAgentConfig.serializer(), encoded)

        // Verify ruleCategories
        assertEquals(1, decoded.ruleCategories.size)
        assertEquals("Diplomacy", decoded.ruleCategories[0].name)
        assertEquals(25, decoded.ruleCategories[0].chancePercent)
        assertEquals(1, decoded.ruleCategories[0].rules.size)
        assertEquals("d1", decoded.ruleCategories[0].rules[0].id)

        // Verify alwaysApplyRules
        assertEquals(1, decoded.alwaysApplyRules.size)
        assertEquals("global_rule_1", decoded.alwaysApplyRules[0])

        // Verify authorPersonality
        assertEquals("Wise and ancient", decoded.authorPersonality)

        // Verify selectionCriteria
        assertEquals(1, decoded.selectionCriteria.size)
        assertEquals(60, decoded.selectionCriteria[0].chancePercent)

        // Verify storyWeights
        assertEquals(30, decoded.storyWeights.geopolitics)
        assertEquals(25, decoded.storyWeights.absurdity)
        assertEquals(25, decoded.storyWeights.dreamlikeQualities)
        assertEquals(20, decoded.storyWeights.unexpectedTwists)

        // Verify selectionStrategy
        assertEquals(WriterSelectionStrategy.RANDOM_UP_TO_FIVE, decoded.selectionStrategy)

        // Verify boolean flags
        assertEquals(true, decoded.authorEnabled)
        assertEquals(true, decoded.alwaysApplyRulesEnabled)
        assertEquals(false, decoded.guardrailsEnabled)

        // Verify writingInstructions
        assertEquals("Create suspenseful narrative", decoded.writingInstructions)
    }
}