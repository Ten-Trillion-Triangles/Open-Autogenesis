package structs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.ttt.autogenesis.network.RpcJson

/**
 * Integration tests for WritingAgentConfig save/load cycle through MapPackManager.
 * These tests verify that story weights, rule categories, and other writing settings
 * survive a full pack → unpack roundtrip.
 */
class MapPackManagerWritingSettingsTest {
    private val testImageBytes = "fake png image data".toByteArray()

    @Test
    fun `story weights survive pack unpack roundtrip`(): Unit = runBlocking {
        val config = WritingAgentConfig(
            storyWeights = StoryWeights(
                geopolitics = 75,
                absurdity = 60,
                dreamlikeQualities = 40,
                unexpectedTwists = 30
            )
        )
        val mapData = MapData(
            pins = listOf(
                PinData("p1", Territory(name = "Territory One")),
                PinData("p2", Territory(name = "Territory Two"))
            ),
            connections = listOf(ConnectionData("p1", "p2")),
            worldName = "Test World",
            storyScenario = "Test scenario",
            author = "Test Author",
            writingInstructions = "Focus on drama",
            writingAgentConfig = config
        )

        val packed = MapPackManager.pack("test.png", testImageBytes, mapData)
        val unpacked = MapPackManager.unpack(packed)

        assertEquals(75, unpacked.mapData.writingAgentConfig.storyWeights.geopolitics, "geopolitics should be 75")
        assertEquals(60, unpacked.mapData.writingAgentConfig.storyWeights.absurdity, "absurdity should be 60")
        assertEquals(40, unpacked.mapData.writingAgentConfig.storyWeights.dreamlikeQualities, "dreamlikeQualities should be 40")
        assertEquals(30, unpacked.mapData.writingAgentConfig.storyWeights.unexpectedTwists, "unexpectedTwists should be 30")
    }

    @Test
    fun `rule categories with custom chancePercent survive pack unpack roundtrip`(): Unit = runBlocking {
        val config = WritingAgentConfig(
            ruleCategories = listOf(
                RuleCategory(
                    name = "Absurdity",
                    chancePercent = 55,
                    rules = listOf(
                        InjectableRule("rule1", "Test rule description", weight = 3, category = "Absurdity")
                    )
                ),
                RuleCategory(
                    name = "Time Reality",
                    chancePercent = 40,
                    rules = listOf(
                        InjectableRule("rule2", "Another test rule", weight = 2, category = "Time Reality")
                    )
                ),
                RuleCategory(
                    name = "Horror",
                    chancePercent = 7,
                    rules = emptyList()
                )
            )
        )
        val mapData = MapData(
            pins = listOf(
                PinData("p1", Territory(name = "Alpha")),
                PinData("p2", Territory(name = "Beta"))
            ),
            connections = listOf(ConnectionData("p1", "p2")),
            writingAgentConfig = config
        )

        val packed = MapPackManager.pack("test.png", testImageBytes, mapData)
        val unpacked = MapPackManager.unpack(packed)

        assertEquals(3, unpacked.mapData.writingAgentConfig.ruleCategories.size, "should have 3 categories")
        assertEquals("Absurdity", unpacked.mapData.writingAgentConfig.ruleCategories[0].name)
        assertEquals(55, unpacked.mapData.writingAgentConfig.ruleCategories[0].chancePercent, "Absurdity chance should be 55")
        assertEquals(1, unpacked.mapData.writingAgentConfig.ruleCategories[0].rules.size, "Absurdity should have 1 rule")
        assertEquals("rule1", unpacked.mapData.writingAgentConfig.ruleCategories[0].rules[0].id)

        assertEquals("Time Reality", unpacked.mapData.writingAgentConfig.ruleCategories[1].name)
        assertEquals(40, unpacked.mapData.writingAgentConfig.ruleCategories[1].chancePercent, "Time Reality chance should be 40")

        assertEquals("Horror", unpacked.mapData.writingAgentConfig.ruleCategories[2].name)
        assertEquals(7, unpacked.mapData.writingAgentConfig.ruleCategories[2].chancePercent, "Horror chance should be 7")
    }

    @Test
    fun `all writing settings survive full pack unpack roundtrip`(): Unit = runBlocking {
        val config = WritingAgentConfig(
            ruleCategories = listOf(
                RuleCategory(name = "Diplomacy", chancePercent = 25, rules = listOf(
                    InjectableRule("d1", "Peaceful negotiation", weight = 3, category = "Diplomacy")
                ))
            ),
            alwaysApplyRules = listOf("global_rule_1"),
            authorPersonality = "Wise and ancient",
            selectionCriteria = listOf(
                InjectableCriterion(10, "Mystery element", "Atmosphere", 60)
            ),
            storyWeights = StoryWeights(30, 25, 25, 20),
            selectionStrategy = WriterSelectionStrategy.RANDOM_UP_TO_FIVE,
            authorEnabled = true,
            alwaysApplyRulesEnabled = true,
            guardrailsEnabled = false,
            writingInstructions = "Create suspenseful narrative"
        )
        val mapData = MapData(
            pins = listOf(PinData("a", Territory(name = "Alpha"))),
            connections = emptyList(),
            worldName = "Full Test World",
            storyScenario = "A tale of intrigue",
            author = "Test Author",
            writingInstructions = "Emphasize tension",
            storyWeights = StoryWeights(20, 30, 30, 20),
            selectionStrategy = WriterSelectionStrategy.ORIGINAL,
            authorEnabled = true,
            alwaysApplyRulesEnabled = true,
            guardrailsEnabled = true,
            writingAgentConfig = config
        )

        val packed = MapPackManager.pack("test.png", testImageBytes, mapData)
        val unpacked = MapPackManager.unpack(packed)

        // Verify nested writingAgentConfig
        assertEquals(1, unpacked.mapData.writingAgentConfig.ruleCategories.size)
        assertEquals("Diplomacy", unpacked.mapData.writingAgentConfig.ruleCategories[0].name)
        assertEquals(25, unpacked.mapData.writingAgentConfig.ruleCategories[0].chancePercent)
        assertEquals(1, unpacked.mapData.writingAgentConfig.ruleCategories[0].rules.size)
        assertEquals("d1", unpacked.mapData.writingAgentConfig.ruleCategories[0].rules[0].id)

        assertEquals(1, unpacked.mapData.writingAgentConfig.alwaysApplyRules.size)
        assertEquals("global_rule_1", unpacked.mapData.writingAgentConfig.alwaysApplyRules[0])

        assertEquals("Wise and ancient", unpacked.mapData.writingAgentConfig.authorPersonality)

        assertEquals(1, unpacked.mapData.writingAgentConfig.selectionCriteria.size)
        assertEquals(60, unpacked.mapData.writingAgentConfig.selectionCriteria[0].chancePercent)

        assertEquals(30, unpacked.mapData.writingAgentConfig.storyWeights.geopolitics)
        assertEquals(25, unpacked.mapData.writingAgentConfig.storyWeights.absurdity)
        assertEquals(25, unpacked.mapData.writingAgentConfig.storyWeights.dreamlikeQualities)
        assertEquals(20, unpacked.mapData.writingAgentConfig.storyWeights.unexpectedTwists)

        assertEquals(WriterSelectionStrategy.RANDOM_UP_TO_FIVE, unpacked.mapData.writingAgentConfig.selectionStrategy)
        assertEquals(true, unpacked.mapData.writingAgentConfig.authorEnabled)
        assertEquals(true, unpacked.mapData.writingAgentConfig.alwaysApplyRulesEnabled)
        assertEquals(false, unpacked.mapData.writingAgentConfig.guardrailsEnabled)
        assertEquals("Create suspenseful narrative", unpacked.mapData.writingAgentConfig.writingInstructions)

        // Verify top-level MapData writing fields
        assertEquals("Test Author", unpacked.mapData.author)
        assertEquals("Emphasize tension", unpacked.mapData.writingInstructions)
        assertEquals(20, unpacked.mapData.storyWeights.geopolitics)
        assertEquals(30, unpacked.mapData.storyWeights.absurdity)
        assertEquals(30, unpacked.mapData.storyWeights.dreamlikeQualities)
        assertEquals(20, unpacked.mapData.storyWeights.unexpectedTwists)
        assertEquals(WriterSelectionStrategy.ORIGINAL, unpacked.mapData.selectionStrategy)
        assertEquals(true, unpacked.mapData.authorEnabled)
        assertEquals(true, unpacked.mapData.alwaysApplyRulesEnabled)
        assertEquals(true, unpacked.mapData.guardrailsEnabled)
    }

    @Test
    fun `default story weights serialize with encodeDefaults=true`(): Unit = runBlocking {
        // Test that default values (25) are preserved with RpcJson
        val config = WritingAgentConfig(
            storyWeights = StoryWeights(25, 25, 25, 25)
        )
        val mapData = MapData(
            pins = listOf(PinData("p1", Territory(name = "Test"))),
            connections = emptyList(),
            writingAgentConfig = config
        )

        val packed = MapPackManager.pack("test.png", testImageBytes, mapData)
        val unpacked = MapPackManager.unpack(packed)

        assertEquals(25, unpacked.mapData.writingAgentConfig.storyWeights.geopolitics)
        assertEquals(25, unpacked.mapData.writingAgentConfig.storyWeights.absurdity)
        assertEquals(25, unpacked.mapData.writingAgentConfig.storyWeights.dreamlikeQualities)
        assertEquals(25, unpacked.mapData.writingAgentConfig.storyWeights.unexpectedTwists)
    }

    @Test
    fun `authorEnabled false survives pack unpack roundtrip`(): Unit = runBlocking {
        val config = WritingAgentConfig(authorEnabled = false)
        val mapData = MapData(
            pins = listOf(PinData("p1", Territory(name = "Test"))),
            connections = emptyList(),
            writingAgentConfig = config
        )

        val packed = MapPackManager.pack("test.png", testImageBytes, mapData)
        val unpacked = MapPackManager.unpack(packed)

        assertEquals(false, unpacked.mapData.writingAgentConfig.authorEnabled)
    }

    @Test
    fun `procedure survives pack unpack roundtrip`(): Unit = runBlocking {
        val config = WritingAgentConfig(
            writingInstructions = "Custom writing instructions",
            procedure = "###PROCEDURE:\n1. Item one\n2. Item two\n###OVERALL:\nFinal instruction"
        )
        val mapData = MapData(
            pins = listOf(
                PinData("p1", Territory(name = "Territory One")),
                PinData("p2", Territory(name = "Territory Two"))
            ),
            connections = listOf(ConnectionData("p1", "p2")),
            worldName = "Test World",
            writingAgentConfig = config
        )

        val packed = MapPackManager.pack("test.png", testImageBytes, mapData)
        val unpacked = MapPackManager.unpack(packed)

        assertEquals(
            "###PROCEDURE:\n1. Item one\n2. Item two\n###OVERALL:\nFinal instruction",
            unpacked.mapData.writingAgentConfig.procedure,
            "procedure should survive pack/unpack roundtrip"
        )
        assertEquals(
            "Custom writing instructions",
            unpacked.mapData.writingAgentConfig.writingInstructions,
            "writingInstructions should survive pack/unpack roundtrip"
        )
    }

    @Test
    fun `empty procedure survives pack unpack roundtrip`(): Unit = runBlocking {
        val config = WritingAgentConfig(
            writingInstructions = "Only writing instructions, no procedure override"
        )
        val mapData = MapData(
            pins = listOf(PinData("p1", Territory(name = "Territory One"))),
            connections = emptyList(),
            writingAgentConfig = config
        )

        val packed = MapPackManager.pack("test.png", testImageBytes, mapData)
        val unpacked = MapPackManager.unpack(packed)

        assertEquals("", unpacked.mapData.writingAgentConfig.procedure, "empty procedure should remain empty after pack/unpack")
        assertEquals(
            "Only writing instructions, no procedure override",
            unpacked.mapData.writingAgentConfig.writingInstructions
        )
    }

    // NOTE: The JS counterpart of this test (jsTest variant) is intentionally not covered;
    // matches existing convention for the rest of this test class.

    @Test
    fun `defaultWritingAgentConfig criteria chancePercents survive pack unpack roundtrip`(): Unit = runBlocking {
        val config = defaultWritingAgentConfig()
        val mapData = MapData(
            pins = listOf(PinData("p1", Territory(name = "Territory One"))),
            connections = emptyList(),
            writingAgentConfig = config
        )

        val packed = MapPackManager.pack("test.png", testImageBytes, mapData)
        val unpacked = MapPackManager.unpack(packed)

        val expectedChances = mapOf(
            1 to 0, 2 to 5, 3 to 10, 4 to 30, 5 to 5,
            6 to 5, 7 to 10, 8 to 5, 9 to 5, 10 to 5,
            11 to 25
        )
        val actualChances = unpacked.mapData.writingAgentConfig.selectionCriteria
            .associate { it.id to it.chancePercent }
        assertEquals(expectedChances, actualChances, "default criteria chancePercents should survive pack/unpack roundtrip")
        assertEquals(
            defaultRuleCategories().map { it.name to it.chancePercent },
            unpacked.mapData.writingAgentConfig.ruleCategories.map { it.name to it.chancePercent },
            "default rule categories should survive pack/unpack roundtrip"
        )
    }
}