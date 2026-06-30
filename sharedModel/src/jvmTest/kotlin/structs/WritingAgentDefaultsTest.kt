package structs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the canonical writing agent defaults in
 * [WritingAgentDefaults]. These guard against silent drift between the
 * shared defaults and the values the writing agent and mapEditor depend on.
 */
class WritingAgentDefaultsTest {

    @Test
    fun `defaultSelectionCriteria has 11 entries in canonical order`() {
        val criteria = defaultSelectionCriteria()
        assertEquals(11, criteria.size, "expected 11 default selection criteria")
        criteria.forEachIndexed { index, criterion ->
            assertEquals(index + 1, criterion.id, "criterion at index $index should have id=${index + 1}")
        }
    }

    @Test
    fun `defaultSelectionCriteria chancePercents match the canonical tuning`() {
        val expected = mapOf(
            1 to 0,    // Kafka
            2 to 5,    // Keillor
            3 to 10,   // Pitigrilli
            4 to 30,   // Geopolitics
            5 to 5,    // Wallesian
            6 to 5,    // Joycean
            7 to 10,   // Rabellesian
            8 to 5,    // Dreamlike
            9 to 5,    // Kubrickian
            10 to 5,   // Really dumb
            11 to 25   // Grounded war journalism
        )
        val actual = defaultSelectionCriteria().associate { it.id to it.chancePercent }
        assertEquals(expected, actual, "chancePercent values drifted from canonical tuning")
    }

    @Test
    fun `defaultRuleCategories has the 5 canonical categories with their original chancePercents`() {
        val categories = defaultRuleCategories()
        val expected = listOf(
            "absurdity" to 10,
            "time_reality" to 8,
            "horror" to 7,
            "geopolitics" to 10,
            "general" to 5
        )
        assertEquals(expected.size, categories.size, "expected ${expected.size} default rule categories")
        expected.forEachIndexed { index, (name, chance) ->
            assertEquals(name, categories[index].name, "category at index $index has wrong name")
            assertEquals(chance, categories[index].chancePercent, "category '${name}' chancePercent drift")
            assertTrue(categories[index].rules.isNotEmpty(), "category '${name}' should have at least one rule")
        }
    }

    @Test
    fun `defaultWritingAgentConfig composes the other defaults`() {
        val config = defaultWritingAgentConfig()
        assertEquals(defaultRuleCategories(), config.ruleCategories)
        assertEquals(defaultAlwaysApplyRules, config.alwaysApplyRules)
        assertEquals(defaultAuthorPersonality, config.authorPersonality)
        assertEquals(defaultSelectionCriteria(), config.selectionCriteria)
        assertEquals(StoryWeights(25, 25, 25, 25), config.storyWeights)
        assertEquals(WriterSelectionStrategy.RANDOM, config.selectionStrategy)
        assertEquals(true, config.authorEnabled)
        assertEquals(true, config.alwaysApplyRulesEnabled)
        assertEquals(true, config.guardrailsEnabled)
        assertEquals("", config.writingInstructions)
    }

    @Test
    fun `defaultAuthorPersonality is empty (writerAgent routes to CGO and CSA via promptMap) and defaultAlwaysApplyRules are non-empty`() {
        assertTrue(defaultAuthorPersonality.isEmpty(), "defaultAuthorPersonality should be empty so writerAgent routes to CGO/CSA via promptMap; devs can pick a specific persona (Nordold Trable, etc.) from the MapEditor dropdown")
        assertTrue(defaultAlwaysApplyRules.isNotEmpty(), "defaultAlwaysApplyRules should not be empty")
    }

    @Test
    fun `defaultProcedureText is non-empty and contains the procedure and overall markers`() {
        assertTrue(defaultProcedureText.isNotEmpty(), "defaultProcedureText should not be empty")
        assertTrue(defaultProcedureText.contains("###PROCEDURE:"), "defaultProcedureText should contain the procedure marker")
        assertTrue(defaultProcedureText.contains("###OVERALL:"), "defaultProcedureText should contain the overall marker")
    }

    @Test
    fun `defaultWritingAgentConfig round-trips through JSON serialization`() {
        val config = defaultWritingAgentConfig()
        val json = kotlinx.serialization.json.Json.encodeToString(WritingAgentConfig.serializer(), config)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(WritingAgentConfig.serializer(), json)
        assertEquals(config, decoded, "defaultWritingAgentConfig should round-trip through JSON serialization")
    }
}
