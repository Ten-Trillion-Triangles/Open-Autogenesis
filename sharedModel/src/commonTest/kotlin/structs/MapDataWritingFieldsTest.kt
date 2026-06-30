package structs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Tests for MapData writing-related fields serialization.
 */
class MapDataWritingFieldsTest
{
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun createMinimalMapData(): MapData
    {
        return MapData(
            pins = listOf(
                PinData(pinId = "pin1", territory = Territory(name = "Territory One")),
                PinData(pinId = "pin2", territory = Territory(name = "Territory Two"))
            ),
            connections = listOf(
                ConnectionData(fromPinId = "pin1", toPinId = "pin2")
            )
        )
    }

    @Test
    fun `should serialize and deserialize author field correctly`()
    {
        val mapDataOriginal = createMinimalMapData().copy(author = "Nordold Trable")

        val encoded = json.encodeToString(MapData.serializer(), mapDataOriginal)
        val decoded = json.decodeFromString(MapData.serializer(), encoded)

        assertEquals("Nordold Trable", decoded.author)
    }

    @Test
    fun `should serialize and deserialize writingInstructions field correctly`()
    {
        val mapDataOriginal = createMinimalMapData().copy(
            writingInstructions = "Create an atmosphere of mystery and intrigue"
        )

        val encoded = json.encodeToString(MapData.serializer(), mapDataOriginal)
        val decoded = json.decodeFromString(MapData.serializer(), encoded)

        assertEquals("Create an atmosphere of mystery and intrigue", decoded.writingInstructions)
    }

    @Test
    fun `should serialize and deserialize writingAgentConfig field correctly`()
    {
        val writingAgentConfig = WritingAgentConfig(
            storyWeights = StoryWeights(30, 30, 20, 20),
            selectionStrategy = WriterSelectionStrategy.WEIGHTED,
            authorEnabled = true,
            alwaysApplyRulesEnabled = false,
            guardrailsEnabled = true
        )

        val mapDataOriginal = createMinimalMapData().copy(writingAgentConfig = writingAgentConfig)

        val encoded = json.encodeToString(MapData.serializer(), mapDataOriginal)
        val decoded = json.decodeFromString(MapData.serializer(), encoded)

        assertEquals(30, decoded.writingAgentConfig.storyWeights.geopolitics)
        assertEquals(WriterSelectionStrategy.WEIGHTED, decoded.writingAgentConfig.selectionStrategy)
        assertEquals(true, decoded.writingAgentConfig.authorEnabled)
        assertEquals(false, decoded.writingAgentConfig.alwaysApplyRulesEnabled)
        assertEquals(true, decoded.writingAgentConfig.guardrailsEnabled)
    }

    @Test
    fun `should preserve all writing fields through full roundtrip`()
    {
        val writingAgentConfig = WritingAgentConfig(
            ruleCategories = listOf(
                RuleCategory(name = "Atmosphere", chancePercent = 20, rules = listOf(
                    InjectableRule(id = "fog", description = "Add misty fog", weight = 2, category = "Atmosphere")
                ))
            ),
            alwaysApplyRules = listOf("universal_rule"),
            authorPersonality = "Somber and reflective",
            selectionCriteria = listOf(
                InjectableCriterion(id = 5, description = "Eerie silence", category = "Atmosphere", chancePercent = 40)
            ),
            storyWeights = StoryWeights(25, 25, 25, 25),
            selectionStrategy = WriterSelectionStrategy.GEOPOLITICS_ONLY,
            authorEnabled = false,
            alwaysApplyRulesEnabled = true,
            guardrailsEnabled = false,
            writingInstructions = "Focus on environmental storytelling"
        )

        val mapDataOriginal = MapData(
            pins = listOf(
                PinData(pinId = "a", territory = Territory(name = "Alpha")),
                PinData(pinId = "b", territory = Territory(name = "Beta"))
            ),
            connections = listOf(
                ConnectionData(fromPinId = "a", toPinId = "b")
            ),
            worldName = "Test World",
            storyScenario = "A tale of intrigue",
            author = "Test Author",
            writingInstructions = "Emphasize tension and suspense",
            storyWeights = StoryWeights(20, 30, 30, 20),
            selectionStrategy = WriterSelectionStrategy.ORIGINAL,
            authorEnabled = true,
            alwaysApplyRulesEnabled = true,
            guardrailsEnabled = true,
            writingAgentConfig = writingAgentConfig
        )

        val encoded = json.encodeToString(MapData.serializer(), mapDataOriginal)
        val decoded = json.decodeFromString(MapData.serializer(), encoded)

        // Verify basic MapData fields
        assertEquals("Test World", decoded.worldName)
        assertEquals("A tale of intrigue", decoded.storyScenario)
        assertEquals("Test Author", decoded.author)
        assertEquals("Emphasize tension and suspense", decoded.writingInstructions)

        // Verify storyWeights
        assertEquals(20, decoded.storyWeights.geopolitics)
        assertEquals(30, decoded.storyWeights.absurdity)
        assertEquals(30, decoded.storyWeights.dreamlikeQualities)
        assertEquals(20, decoded.storyWeights.unexpectedTwists)

        // Verify selectionStrategy
        assertEquals(WriterSelectionStrategy.ORIGINAL, decoded.selectionStrategy)

        // Verify boolean flags
        assertEquals(true, decoded.authorEnabled)
        assertEquals(true, decoded.alwaysApplyRulesEnabled)
        assertEquals(true, decoded.guardrailsEnabled)

        // Verify nested writingAgentConfig
        assertEquals(1, decoded.writingAgentConfig.ruleCategories.size)
        assertEquals("Atmosphere", decoded.writingAgentConfig.ruleCategories[0].name)
        assertEquals(20, decoded.writingAgentConfig.ruleCategories[0].chancePercent)
        assertEquals(1, decoded.writingAgentConfig.ruleCategories[0].rules.size)
        assertEquals("fog", decoded.writingAgentConfig.ruleCategories[0].rules[0].id)

        assertEquals(1, decoded.writingAgentConfig.alwaysApplyRules.size)
        assertEquals("universal_rule", decoded.writingAgentConfig.alwaysApplyRules[0])

        assertEquals("Somber and reflective", decoded.writingAgentConfig.authorPersonality)

        assertEquals(1, decoded.writingAgentConfig.selectionCriteria.size)
        assertEquals(40, decoded.writingAgentConfig.selectionCriteria[0].chancePercent)

        assertEquals(25, decoded.writingAgentConfig.storyWeights.geopolitics)
        assertEquals(WriterSelectionStrategy.GEOPOLITICS_ONLY, decoded.writingAgentConfig.selectionStrategy)
        assertEquals(false, decoded.writingAgentConfig.authorEnabled)
        assertEquals(true, decoded.writingAgentConfig.alwaysApplyRulesEnabled)
        assertEquals(false, decoded.writingAgentConfig.guardrailsEnabled)
        assertEquals("Focus on environmental storytelling", decoded.writingAgentConfig.writingInstructions)
    }

    @Test
    fun `should serialize and deserialize procedure field correctly`()
    {
        val mapDataOriginal = createMinimalMapData().copy(
            writingAgentConfig = WritingAgentConfig(
                writingInstructions = "Custom writing instructions",
                procedure = "###PROCEDURE:\n1. Item one\n2. Item two\n###OVERALL:\nFinal instruction"
            )
        )

        val encoded = json.encodeToString(MapData.serializer(), mapDataOriginal)
        val decoded = json.decodeFromString(MapData.serializer(), encoded)

        assertEquals(
            "###PROCEDURE:\n1. Item one\n2. Item two\n###OVERALL:\nFinal instruction",
            decoded.writingAgentConfig.procedure
        )
        assertEquals("Custom writing instructions", decoded.writingAgentConfig.writingInstructions)
    }

    @Test
    fun `should default procedure to empty string when JSON omits the field`()
    {
        // Build a JSON document that does NOT contain a 'procedure' key.
        // This simulates an old map pack saved before the field existed.
        val legacyJson = """{
            |    "pins": [
            |        { "pinId": "p1", "territory": { "name": "Legacy One" } },
            |        { "pinId": "p2", "territory": { "name": "Legacy Two" } }
            |    ],
            |    "connections": [ { "fromPinId": "p1", "toPinId": "p2" } ],
            |    "writingAgentConfig": {
            |        "writingInstructions": "Legacy instructions only"
            |    }
            |}""".trimMargin()

        val decoded = json.decodeFromString(MapData.serializer(), legacyJson)

        // Backward-compat: missing key must default to empty string, not throw.
        assertEquals("", decoded.writingAgentConfig.procedure)
        assertEquals("Legacy instructions only", decoded.writingAgentConfig.writingInstructions)
    }
}
